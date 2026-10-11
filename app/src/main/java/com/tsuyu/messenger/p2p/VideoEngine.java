package com.tsuyu.messenger.p2p;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Видео поверх своего P2P-канала: камера и кодек H.264 — встроенные средства Android
 * (MediaCodec и Camera2), никаких сторонних библиотек. Кадры режутся на пакеты по 900 байт
 * и собираются на другом конце.
 *
 * <p>Если камера или кодек на устройстве не подошли, видео просто отключается — голос
 * продолжает работать: {@link Listener#onFailed(String)}.
 */
public final class VideoEngine {

    public interface Listener {
        void onFailed(String reason);
    }

    private static final int MAX_CHUNK = 900;
    private static final String MIME = "video/avc";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int FRAME_RATE = 20;
    private static final int BITRATE = 600_000;

    private final PeerLink link;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private java.util.concurrent.ExecutorService encoderDrain;
    private java.util.concurrent.ExecutorService decoderDrain;
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicInteger frameCounter = new AtomicInteger();
    private final List<Chunk> pending = new ArrayList<>();

    private HandlerThread thread;
    private Handler handler;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private MediaCodec decoder;
    private Surface remoteSurface;
    private Surface previewSurface;
    private Context context;
    private String cameraId;
    private boolean frontFacing = true;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private byte[] codecConfig;
    private int pid;
    private int lastPid = -1;
    private long decodeTimestamp;
    private Listener listener;

    private static final class Chunk {
        final int id;
        final int offset;
        final byte[] data;
        final boolean first;
        final boolean last;

        Chunk(int id, int offset, byte[] data, boolean first, boolean last) {
            this.id = id;
            this.offset = offset;
            this.data = data;
            this.first = first;
            this.last = last;
        }
    }

    public VideoEngine(PeerLink link) {
        this.link = link;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isFrontFacing() {
        return frontFacing;
    }

    /** Своя картинка показывается в маленьком окне, собеседник — в большом. */
    public void attachSurfaces(Surface localPreview, Surface remoteOutput) {
        previewSurface = localPreview;
        remoteSurface = remoteOutput;
        if (remoteSurface != null) startReceiver();
        if (running.get()) restartCamera();
    }

    public boolean start(Context context, Surface localPreview, Surface remoteOutput) {
        this.context = context == null ? null : context.getApplicationContext();
        this.previewSurface = localPreview;
        this.remoteSurface = remoteOutput;
        if (context == null) return false;
        if (running.get()) return true;
        try {
            thread = new HandlerThread("tsuyu-video");
            thread.start();
            handler = new Handler(thread.getLooper());
            if (!startSender()) return false;
            if (remoteSurface != null) startReceiver();
            running.set(true);
            return true;
        } catch (Throwable error) {
            fail("видео недоступно: " + error.getClass().getSimpleName());
            return false;
        }
    }

    private void fail(String reason) {
        if (failed.compareAndSet(false, true)) {
            Listener target = listener;
            if (target != null) target.onFailed(reason);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // Отправка: камера → кодек → сеть
    // ------------------------------------------------------------------

    private boolean startSender() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            encoder = MediaCodec.createEncoderByType(MIME);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();
            encoderDrain = P2pThreads.loop("tsuyu-video-tx");
            encoderDrain.execute(this::drainEncoder);
            if (!openCamera()) return false;
            return true;
        } catch (Throwable error) {
            fail("камера недоступна: " + error.getClass().getSimpleName());
            return false;
        }
    }

    private boolean openCamera() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return false;
            if (cameraId == null) {
                String front = null;
                String back = null;
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT && front == null) front = id;
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && back == null) back = id;
                }
                cameraId = front != null ? front : back;
                frontFacing = front != null;
            }
            if (cameraId == null) return false;
            android.util.Log.i("TsuyuVideo", "камера " + cameraId + " " + WIDTH + "x" + HEIGHT);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    camera = device;
                    createSession();
                }

                @Override public void onDisconnected(CameraDevice device) {
                    try { device.close(); } catch (Throwable ignored) { }
                    camera = null;
                }

                @Override public void onError(CameraDevice device, int code) {
                    try { device.close(); } catch (Throwable ignored) { }
                    camera = null;
                }
            }, handler);
            return true;
        } catch (Throwable error) {
            fail("камера занята");
            return false;
        }
    }

    private void createSession() {
        try {
            CameraDevice device = camera;
            Surface target = encoderSurface;
            if (device == null || target == null) return;
            List<Surface> outputs = new ArrayList<>();
            outputs.add(target);
            if (previewSurface != null && previewSurface.isValid()) outputs.add(previewSurface);
            device.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    session = configured;
                    try {
                        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        builder.addTarget(target);
                        if (previewSurface != null && previewSurface.isValid()) builder.addTarget(previewSurface);
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        session.setRepeatingRequest(builder.build(), null, handler);
                    } catch (Throwable error) {
                        fail("камера не запустилась");
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    fail("камера не настроилась");
                }
            }, handler);
        } catch (Throwable error) {
            fail("камера не запустилась");
        }
    }

    /** Готовые кадры кодека уходят в сеть кусками: так пролезет даже большой ключевой кадр. */
    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running.get() || encoder != null) {
            MediaCodec current = encoder;
            if (current == null) break;
            try {
                int index = current.dequeueOutputBuffer(info, 200_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    ByteBuffer config = current.getOutputFormat().getByteBuffer("csd-0");
                    if (config != null) {
                        codecConfig = new byte[config.remaining()];
                        config.get(codecConfig);
                    }
                    continue;
                }
                if (index < 0) continue;
                ByteBuffer buffer = current.getOutputBuffer(index);
                if (buffer == null) {
                    current.releaseOutputBuffer(index, false);
                    continue;
                }
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                int size = info.size + (keyframe && codecConfig != null ? codecConfig.length : 0);
                byte[] accessUnit = new byte[size];
                if (keyframe && codecConfig != null) {
                    System.arraycopy(codecConfig, 0, accessUnit, 0, codecConfig.length);
                    buffer.get(accessUnit, codecConfig.length, info.size);
                } else {
                    buffer.get(accessUnit, 0, info.size);
                }
                current.releaseOutputBuffer(index, false);
                if (info.size > 0) sendFrame(accessUnit);
            } catch (Throwable error) {
                if (!running.get()) break;
            }
        }
    }

    private void sendFrame(byte[] accessUnit) {
        int id = frameCounter.incrementAndGet() & 0xffff;
        int offset = 0;
        while (offset < accessUnit.length) {
            int size = Math.min(MAX_CHUNK, accessUnit.length - offset);
            byte[] payload = new byte[5 + size];
            payload[0] = (byte) (id >> 8);
            payload[1] = (byte) id;
            payload[2] = (byte) (offset >> 8);
            payload[3] = (byte) offset;
            payload[4] = (byte) ((offset == 0 ? 1 : 0) | (offset + size >= accessUnit.length ? 2 : 0));
            System.arraycopy(accessUnit, offset, payload, 5, size);
            link.send(PeerLink.VIDEO, payload);
            offset += size;
        }
    }

    public void switchCamera() {
        frontFacing = !frontFacing;
        restartCamera();
    }

    private void restartCamera() {
        try {
            if (session != null) {
                session.close();
                session = null;
            }
            if (camera != null) {
                camera.close();
                camera = null;
            }
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager != null) {
                String wanted = null;
                int target = frontFacing ? CameraCharacteristics.LENS_FACING_FRONT
                        : CameraCharacteristics.LENS_FACING_BACK;
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == target) {
                        wanted = id;
                        break;
                    }
                }
                cameraId = wanted != null ? wanted : cameraId;
            }
            openCamera();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------
    // Приём: сеть → кодек → экран
    // ------------------------------------------------------------------

    private void startReceiver() {
        if (decoder != null) return;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
            decoder = MediaCodec.createDecoderByType(MIME);
            decoder.configure(format, remoteSurface, null, 0);
            decoder.start();
            decoderDrain = P2pThreads.loop("tsuyu-video-rx");
            decoderDrain.execute(this::drainDecoder);
        } catch (Throwable error) {
            decoder = null;
            // Без приёма видео голос всё равно продолжается.
            fail("приём видео недоступен");
        }
    }

    /** Собираем куски в целый кадр и отдаём декодеру. */
    public void onVideo(int sequence, byte[] payload) {
        if (payload == null || payload.length < 5) return;
        try {
            int id = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
            int offset = ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
            int flags = payload[4] & 0xff;
            byte[] data = new byte[payload.length - 5];
            System.arraycopy(payload, 5, data, 0, data.length);
            if (id != pid) {
                pid = id;
                pending.clear();
            }
            if (offset != totalPending()) {
                // Кусок потерялся: кадр не собрать, ждём следующий.
                pending.clear();
                return;
            }
            pending.add(new Chunk(id, offset, data, (flags & 1) != 0, (flags & 2) != 0));
            if ((flags & 2) == 0) return;
            int size = totalPending();
            if (size <= 0 || size > 300_000) {
                pending.clear();
                return;
            }
            byte[] accessUnit = new byte[size];
            int cursor = 0;
            for (Chunk chunk : pending) {
                System.arraycopy(chunk.data, 0, accessUnit, cursor, chunk.data.length);
                cursor += chunk.data.length;
            }
            pending.clear();
            queueForDecode(accessUnit);
        } catch (Throwable ignored) { }
    }

    private int totalPending() {
        int size = 0;
        for (Chunk chunk : pending) size += chunk.data.length;
        return size;
    }

    private void queueForDecode(byte[] accessUnit) {
        MediaCodec current = decoder;
        if (current == null || !running.get()) return;
        try {
            int index = current.dequeueInputBuffer(10_000);
            if (index < 0) return;
            ByteBuffer buffer = current.getInputBuffer(index);
            if (buffer == null) return;
            buffer.clear();
            buffer.put(accessUnit);
            decodeTimestamp += 33_333L;
            current.queueInputBuffer(index, 0, accessUnit.length, decodeTimestamp, 0);
        } catch (Throwable ignored) { }
    }

    private void drainDecoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running.get() || decoder != null) {
            MediaCodec current = decoder;
            if (current == null) break;
            try {
                int index = current.dequeueOutputBuffer(info, 200_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (index < 0) continue;
                current.releaseOutputBuffer(index, true);
            } catch (Throwable error) {
                if (!running.get()) break;
            }
        }
    }

    public void stop() {
        running.set(false);
        // Разбор кадров идёт на своих управляемых потоках: конец звонка их гасит.
        P2pThreads.shutdown(encoderDrain);
        encoderDrain = null;
        P2pThreads.shutdown(decoderDrain);
        decoderDrain = null;
        try {
            if (session != null) session.close();
        } catch (Throwable ignored) { }
        session = null;
        try {
            if (camera != null) camera.close();
        } catch (Throwable ignored) { }
        camera = null;
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Throwable ignored) { }
        encoder = null;
        try {
            if (encoderSurface != null) encoderSurface.release();
        } catch (Throwable ignored) { }
        encoderSurface = null;
        try {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
        } catch (Throwable ignored) { }
        decoder = null;
        if (thread != null) {
            try { thread.quitSafely(); } catch (Throwable ignored) { }
            thread = null;
        }
        handler = null;
        pending.clear();
    }
}
