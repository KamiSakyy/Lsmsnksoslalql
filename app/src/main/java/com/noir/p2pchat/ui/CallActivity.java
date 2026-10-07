package com.noir.p2pchat.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.noir.p2pchat.AppKernel;
import com.noir.p2pchat.core.P2pEngine;
import com.noir.p2pchat.service.ChatConnectionService;

import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.Map;

/** Foreground call UI for incoming, outgoing, audio and video WebRTC calls. */
public final class CallActivity extends ComponentActivity implements P2pEngine.Listener {
    public static final String EXTRA_CALL_ID = "media_call_id";
    public static final String EXTRA_PEER_UID = "media_call_peer_uid";
    public static final String EXTRA_VIDEO = "media_call_video";
    public static final String EXTRA_ACCEPT_ON_OPEN = "media_call_accept_on_open";

    private AppKernel app;
    private P2pEngine engine;
    private String callId;
    private String peerUid;
    private boolean video;
    private boolean incoming = true;
    private boolean muted;
    private boolean speakerEnabled;
    private boolean videoEnabled = true;
    private boolean terminalHandled;
    private TextView statusView;
    private TextView peerAvatar;
    private LinearLayout acceptActions;
    private LinearLayout activeActions;
    private SurfaceViewRenderer remoteRenderer;
    private SurfaceViewRenderer localRenderer;
    private VideoTrack attachedRemoteTrack;
    private VideoTrack attachedLocalTrack;
    private EglBase.Context eglContext;
    private ActivityResultLauncher<String[]> callPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (AppKernel) getApplication();
        engine = app.p2p();
        Intent intent = getIntent();
        callId = intent.getStringExtra(EXTRA_CALL_ID);
        peerUid = intent.getStringExtra(EXTRA_PEER_UID);
        video = intent.getBooleanExtra(EXTRA_VIDEO, false);
        speakerEnabled = video;
        if (callId == null || !callId.matches("[A-Za-z0-9_-]{1,128}")
                || peerUid == null || !peerUid.matches("[A-Za-z0-9_-]{8,128}")) {
            finish();
            return;
        }
        callPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                this::onCallPermissionsResult);
        setUpSystemBars();
        LinearLayout screen = buildScreen();
        setContentView(screen);
        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left + Ui.dp(this, 22), bars.top + Ui.dp(this, 20),
                    bars.right + Ui.dp(this, 22), bars.bottom + Ui.dp(this, 18));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(screen);
        if (video) initializeRenderers();
        engine.addListener(this);
        engine.start();
        startConnectionService();
        String current = engine.getMediaCallState(callId);
        if (engine.isOutgoingMediaCall(callId) || "calling".equals(current) || "connecting".equals(current)
                || "connected".equals(current) || "reconnecting".equals(current)) incoming = false;
        boolean acceptImmediately = intent.getBooleanExtra(EXTRA_ACCEPT_ON_OPEN, false);
        updateUi(current);
        if ("unknown".equals(current)) {
            statusView.postDelayed(() -> {
                if (!isFinishing() && incoming && "unknown".equals(engine.getMediaCallState(callId))) {
                    Toast.makeText(this, "Звонок больше недоступен", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }, 30_000L);
        }
        if (acceptImmediately) acceptWithPermissionCheck();
        else if (!incoming && !hasRequiredPermissions()) {
            Toast.makeText(this, "Для звонка нужны разрешения на микрофон и камеру", Toast.LENGTH_LONG).show();
            engine.endMediaCall(callId);
            finish();
        }
    }

    private void startConnectionService() {
        Intent service = new Intent(this, ChatConnectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
    }

    private void setUpSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightNavigationBars(false);
    }

    private LinearLayout buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 18));
        root.setBackgroundColor(Ui.BACKGROUND);

        TextView title = Ui.text(this, video ? "NOIR · ВИДЕОЗВОНОК" : "NOIR · АУДИОЗВОНОК", 11, Ui.MUTED);
        title.setLetterSpacing(0.14f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 34)));

        FrameLayout stage = new FrameLayout(this);
        stage.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 26), Ui.STROKE));
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(-1, 0, 1);
        stageParams.topMargin = Ui.dp(this, 12);
        stageParams.bottomMargin = Ui.dp(this, 18);
        root.addView(stage, stageParams);

        peerAvatar = Ui.text(this, video ? "▣" : "N", 42, Ui.ACCENT);
        peerAvatar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        peerAvatar.setGravity(Gravity.CENTER);
        peerAvatar.setBackground(Ui.rounded(Color.rgb(39, 34, 60), Ui.dp(this, 72), Color.TRANSPARENT));
        FrameLayout.LayoutParams avatarParams = new FrameLayout.LayoutParams(Ui.dp(this, 144), Ui.dp(this, 144), Gravity.CENTER);

        if (video) {
            remoteRenderer = new SurfaceViewRenderer(this);
            remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            remoteRenderer.setMirror(false);
            remoteRenderer.setEnableHardwareScaler(true);
            stage.addView(remoteRenderer, new FrameLayout.LayoutParams(-1, -1));
            stage.addView(peerAvatar, avatarParams);

            localRenderer = new SurfaceViewRenderer(this);
            localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            localRenderer.setMirror(true);
            localRenderer.setZOrderMediaOverlay(true);
            localRenderer.setEnableHardwareScaler(true);
            FrameLayout.LayoutParams localParams = new FrameLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 158),
                    Gravity.TOP | Gravity.END);
            localParams.setMargins(0, Ui.dp(this, 14), Ui.dp(this, 14), 0);
            stage.addView(localRenderer, localParams);
        }

        if (!video) stage.addView(peerAvatar, avatarParams);

        TextView peer = Ui.text(this, shortId(peerUid), 13, Ui.TEXT);
        peer.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        peer.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams peerParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        peerParams.setMargins(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 28));
        stage.addView(peer, peerParams);

        statusView = Ui.text(this, "Подключаемся…", 16, Ui.TEXT);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        statusParams.bottomMargin = Ui.dp(this, 10);
        root.addView(statusView, statusParams);

        acceptActions = new LinearLayout(this);
        acceptActions.setGravity(Gravity.CENTER);
        TextView decline = actionButton("Отклонить", Color.rgb(190, 58, 73));
        decline.setOnClickListener(v -> {
            engine.declineMediaCall(callId);
            finish();
        });
        TextView accept = actionButton("Принять", Ui.GREEN);
        accept.setOnClickListener(v -> acceptWithPermissionCheck());
        acceptActions.addView(decline, actionParams());
        acceptActions.addView(accept, actionParams());
        root.addView(acceptActions, new LinearLayout.LayoutParams(-1, Ui.dp(this, 58)));

        activeActions = new LinearLayout(this);
        activeActions.setGravity(Gravity.CENTER);
        TextView speaker = actionButton(speakerEnabled ? "Динамик ✓" : "Динамик", Ui.SURFACE_ALT);
        speaker.setOnClickListener(v -> {
            speakerEnabled = !speakerEnabled;
            engine.setMediaCallSpeakerEnabled(callId, speakerEnabled);
            speaker.setText(speakerEnabled ? "Динамик ✓" : "Динамик");
        });
        activeActions.addView(speaker, actionParams());
        TextView mute = actionButton("Микрофон", Ui.SURFACE_ALT);
        mute.setOnClickListener(v -> {
            muted = !muted;
            engine.setMediaCallMuted(callId, muted);
            mute.setText(muted ? "Включить звук" : "Микрофон");
        });
        activeActions.addView(mute, actionParams());
        if (video) {
            TextView camera = actionButton("Камера", Ui.SURFACE_ALT);
            camera.setOnClickListener(v -> {
                videoEnabled = !videoEnabled;
                engine.setMediaCallVideoEnabled(callId, videoEnabled);
                localRenderer.setVisibility(videoEnabled ? View.VISIBLE : View.INVISIBLE);
                camera.setText(videoEnabled ? "Камера" : "Включить видео");
            });
            activeActions.addView(camera, actionParams());
            TextView flip = actionButton("↻", Ui.SURFACE_ALT);
            flip.setContentDescription("Переключить камеру");
            flip.setOnClickListener(v -> engine.switchMediaCallCamera(callId));
            activeActions.addView(flip, actionParams());
        }
        TextView hangup = actionButton("Завершить", Color.rgb(190, 58, 73));
        hangup.setOnClickListener(v -> {
            engine.endMediaCall(callId);
            finish();
        });
        activeActions.addView(hangup, actionParams());
        root.addView(activeActions, new LinearLayout.LayoutParams(-1, Ui.dp(this, 58)));
        return root;
    }

    private void initializeRenderers() {
        eglContext = engine.getEglBaseContext();
        if (eglContext == null) {
            Toast.makeText(this, "Графический WebRTC-контекст ещё запускается", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            remoteRenderer.init(eglContext, null);
            localRenderer.init(eglContext, null);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Не удалось подготовить видео: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TextView actionButton(String text, int color) {
        TextView button = Ui.text(this, text, 13, Ui.TEXT);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.rounded(color, Ui.dp(this, 18), Ui.STROKE));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f);
        params.leftMargin = Ui.dp(this, 5);
        params.rightMargin = Ui.dp(this, 5);
        return params;
    }

    private String[] requiredPermissions() {
        return video
                ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}
                : new String[]{Manifest.permission.RECORD_AUDIO};
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void acceptWithPermissionCheck() {
        if (!hasRequiredPermissions()) {
            callPermissionLauncher.launch(requiredPermissions());
            return;
        }
        incoming = false;
        engine.acceptMediaCall(callId);
        updateUi("connecting");
    }

    private void onCallPermissionsResult(Map<String, Boolean> results) {
        boolean granted = true;
        for (String permission : requiredPermissions()) {
            granted &= Boolean.TRUE.equals(results.get(permission))
                    || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
        }
        if (!granted) {
            Toast.makeText(this, "Разрешения не предоставлены — звонок завершён", Toast.LENGTH_LONG).show();
            engine.declineMediaCall(callId);
            finish();
            return;
        }
        if (incoming) acceptWithPermissionCheck();
        else if (!hasRequiredPermissions()) {
            engine.endMediaCall(callId);
            finish();
        }
    }

    private void updateUi(String state) {
        if (statusView == null) return;
        statusView.setText(stateText(state));
        boolean waitingForAnswer = incoming && ("ringing".equals(state) || "unknown".equals(state));
        acceptActions.setVisibility(waitingForAnswer ? View.VISIBLE : View.GONE);
        activeActions.setVisibility(waitingForAnswer ? View.GONE : View.VISIBLE);
        peerAvatar.setVisibility(video && attachedRemoteTrack != null ? View.GONE : View.VISIBLE);
        if (!terminalHandled && state != null && ("ended".equals(state) || "declined".equals(state) || "missed".equals(state)
                || "no_answer".equals(state) || "failed".equals(state))) {
            terminalHandled = true;
            acceptActions.setVisibility(View.GONE);
            activeActions.setVisibility(View.GONE);
            statusView.postDelayed(this::finish, 1_500);
        }
    }

    private static String stateText(String state) {
        if (state == null) return "Подключаемся…";
        switch (state) {
            case "calling": return "Вызов…";
            case "ringing": return "Входящий звонок";
            case "connecting": return "Соединяем звонок…";
            case "connected": return "На связи";
            case "reconnecting": return "Связь прервалась · восстанавливаем…";
            case "unknown": return "Получаем данные звонка…";
            case "declined": return "Звонок отклонён";
            case "missed": return "Пропущенный звонок";
            case "no_answer": return "Нет ответа";
            case "failed": return "Не удалось установить звонок";
            default: return "Звонок завершён";
        }
    }

    private void attachTracks() {
        if (!video || remoteRenderer == null || localRenderer == null || eglContext == null) return;
        VideoTrack remote = engine.getRemoteVideoTrack(callId);
        if (remote != attachedRemoteTrack) {
            if (attachedRemoteTrack != null) attachedRemoteTrack.removeSink(remoteRenderer);
            attachedRemoteTrack = remote;
            if (remote != null) remote.addSink(remoteRenderer);
        }
        VideoTrack local = engine.getLocalVideoTrack(callId);
        if (local != attachedLocalTrack) {
            if (attachedLocalTrack != null) attachedLocalTrack.removeSink(localRenderer);
            attachedLocalTrack = local;
            if (local != null) local.addSink(localRenderer);
        }
        peerAvatar.setVisibility(remote == null ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.addListener(this);
    }

    @Override
    public void onMediaCallState(String incomingCallId, String incomingPeerUid, boolean isVideo, String state) {
        if (!callId.equals(incomingCallId)) return;
        if ("calling".equals(state) || "connecting".equals(state) || "connected".equals(state)
                || "reconnecting".equals(state)) incoming = false;
        runOnUiThread(() -> updateUi(state));
    }

    @Override
    public void onCallTracksChanged(String changedCallId) {
        if (callId.equals(changedCallId)) runOnUiThread(() -> attachTracks());
    }

    @Override
    protected void onStop() {
        engine.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (attachedRemoteTrack != null && remoteRenderer != null) attachedRemoteTrack.removeSink(remoteRenderer);
        if (attachedLocalTrack != null && localRenderer != null) attachedLocalTrack.removeSink(localRenderer);
        if (remoteRenderer != null) {
            try { remoteRenderer.release(); } catch (Exception ignored) { }
        }
        if (localRenderer != null) {
            try { localRenderer.release(); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    private static String shortId(String value) {
        return value.length() <= 16 ? value : value.substring(0, 16) + "…";
    }
}
