package com.tsuyu.messenger.ui;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.tsuyu.messenger.R;

import java.io.File;
import java.util.Locale;

/**
 * Full-screen circular video note recorder — the Android twin of the web client's
 * circle camera modal: round 260dp preview, timer and switch / torch / cancel / record.
 */
public final class CircleCameraDialog {
    /** 30 s cap: a video note is short and has to survive a single encrypted JSON payload. */
    private static final long MAX_DURATION_MS = 30_000L;

    public interface Listener {
        void onRecorded(File file);
    }

    private final Activity activity;
    private final Listener listener;
    private final Dialog dialog;
    private final PreviewView previewView;
    private final TextView timer;
    private final ImageView switchButton;
    private final ImageView torchButton;
    private final ImageView actionButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ProcessCameraProvider provider;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private Camera camera;
    private boolean frontFacing = true;
    private boolean torchOn;
    private boolean active;
    private boolean delivered;
    private long startedAt;
    private File output;
    private Runnable ticker;

    public static void show(Activity activity, Listener listener) {
        if (activity == null || activity.isFinishing()) return;
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, 207);
            Toast.makeText(activity, R.string.circle_permission, Toast.LENGTH_LONG).show();
            return;
        }
        new CircleCameraDialog(activity, listener).open();
    }

    private CircleCameraDialog(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_circle_camera, null, false);
        dialog.setContentView(content);
        previewView = content.findViewById(R.id.circle_preview);
        timer = content.findViewById(R.id.circle_timer);
        switchButton = content.findViewById(R.id.circle_switch);
        torchButton = content.findViewById(R.id.circle_torch);
        actionButton = content.findViewById(R.id.circle_action);
        content.findViewById(R.id.circle_cancel).setOnClickListener(v -> cancel());
        switchButton.setOnClickListener(v -> {
            if (active) return;
            frontFacing = !frontFacing;
            torchOn = false;
            bindCamera();
        });
        torchButton.setOnClickListener(v -> {
            if (camera == null || frontFacing) return;
            torchOn = !torchOn;
            camera.getCameraControl().enableTorch(torchOn);
            torchButton.setAlpha(torchOn ? 1f : 0.55f);
        });
        actionButton.setOnClickListener(v -> {
            if (active) stopAndSend(); else startRecording();
        });
        dialog.setOnDismissListener(ignored -> release());
    }

    private void open() {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        bindCamera();
    }

    private void bindCamera() {
        final java.util.concurrent.Future<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(activity);
        ProcessCameraProvider.getInstance(activity).addListener(() -> {
            try {
                provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.SD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                CameraSelector selector = frontFacing ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                provider.unbindAll();
                camera = provider.bindToLifecycle((LifecycleOwner) activity, selector, preview, videoCapture);
                torchButton.setAlpha(frontFacing ? 0.4f : 0.55f);
                torchButton.setEnabled(!frontFacing);
            } catch (Exception failed) {
                Toast.makeText(activity, R.string.circle_failed, Toast.LENGTH_SHORT).show();
                dismiss();
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    private void startRecording() {
        if (videoCapture == null) return;
        output = new File(activity.getCacheDir(), "tsuyu-circle-" + System.currentTimeMillis() + ".mp4");
        boolean withAudio = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        try {
            PendingRecording pending = videoCapture.getOutput()
                    .prepareRecording(activity, new FileOutputOptions.Builder(output).build());
            if (withAudio) pending = pending.withAudioEnabled();
            recording = pending.start(ContextCompat.getMainExecutor(activity), event -> {
                if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finish = (VideoRecordEvent.Finalize) event;
                    File file = output;
                    if (finish.hasError() || file == null || !file.exists() || file.length() == 0) {
                        if (!finish.hasError()) Toast.makeText(activity, R.string.circle_failed, Toast.LENGTH_SHORT).show();
                        if (file != null) file.delete();
                        dismiss();
                        return;
                    }
                    if (delivered) {
                        file.delete();
                    } else {
                        delivered = true;
                        listener.onRecorded(file);
                    }
                    dismiss();
                }
            });
        } catch (Exception failed) {
            Toast.makeText(activity, R.string.circle_failed, Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }
        active = true;
        startedAt = System.currentTimeMillis();
        actionButton.setImageResource(R.drawable.ic_send_plane);
        actionButton.setBackgroundResource(R.drawable.bg_circle_recording);
        actionButton.setColorFilter(ContextCompat.getColor(activity, R.color.tsuyu_white));
        switchButton.setAlpha(0.4f);
        torchButton.setAlpha(0.4f);
        ticker = new Runnable() {
            @Override public void run() {
                long elapsed = System.currentTimeMillis() - startedAt;
                timer.setText(String.format(Locale.ROOT, "%d:%02d", elapsed / 60000, (elapsed / 1000) % 60));
                if (elapsed >= MAX_DURATION_MS) {
                    stopAndSend();
                    return;
                }
                handler.postDelayed(this, 200);
            }
        };
        handler.post(ticker);
    }

    private void stopAndSend() {
        if (!active || recording == null) return;
        active = false;
        handler.removeCallbacksAndMessages(null);
        try { recording.stop(); } catch (Exception ignored) { }
        recording = null;
    }

    private void cancel() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        if (recording != null) {
            try { recording.close(); } catch (Exception ignored) { }
            recording = null;
        }
        if (output != null) output.delete();
        dismiss();
    }

    private void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    /** Unbinds the camera so the sensor is released as soon as the modal closes. */
    private void release() {
        handler.removeCallbacksAndMessages(null);
        if (provider != null) provider.unbindAll();
        if (recording != null) {
            try { recording.close(); } catch (Exception ignored) { }
            recording = null;
        }
        provider = null;
        videoCapture = null;
        camera = null;
    }

    /** Kept for callers that need the permission callback contract. */
    public static boolean hasCameraPermission(@NonNull android.content.Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
}
