package com.tsuyu.messenger.p2p;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Голос поверх своего P2P-канала: микрофон и динамик Android напрямую, без сторонних
 * библиотек и без кодеков — 8 кГц моно, кадр 20 мс. Такой поток (128 кбит/с) спокойно
 * идёт через обычный мобильный интернет.
 */
public final class VoiceEngine {

    public static final int SAMPLE_RATE = 8000;
    private static final int FRAME_BYTES = (SAMPLE_RATE / 1000) * 20 * 2; // 20 мс, 16 бит моно

    private final PeerLink link;
    private final Object bufferLock = new Object();
    /** Буфер переупорядочивания: сеть может прийти не в том порядке. */
    private final TreeMap<Integer, byte[]> jitter = new TreeMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);

    private AudioRecord recorder;
    private AudioTrack player;
    private Thread captureThread;
    private Thread playbackThread;
    private int expectedSequence = -1;

    public VoiceEngine(PeerLink link) {
        this.link = link;
    }

    public boolean isMuted() {
        return muted.get();
    }

    public void setMuted(boolean value) {
        muted.set(value);
    }

    /** Включаем микрофон и динамик. Если что-то не поддержано — вернём false, звонок не упадёт. */
    public boolean start(android.content.Context context) {
        if (running.get()) return true;
        try {
            if (context != null) {
                AudioManager manager = (AudioManager) context.getSystemService(android.content.Context.AUDIO_SERVICE);
                if (manager != null) manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) minimum = FRAME_BYTES * 4;
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, FRAME_BYTES * 4));
            player = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(FRAME_BYTES * 6)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED || player.getState() != AudioTrack.STATE_INITIALIZED) {
                return false;
            }
            running.set(true);
            recorder.startRecording();
            player.play();
            captureThread = new Thread(this::captureLoop, "tsuyu-voice-tx");
            captureThread.setDaemon(true);
            captureThread.start();
            playbackThread = new Thread(this::playbackLoop, "tsuyu-voice-rx");
            playbackThread.setDaemon(true);
            playbackThread.start();
            return true;
        } catch (Throwable error) {
            stop();
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void captureLoop() {
        byte[] frame = new byte[FRAME_BYTES];
        while (running.get()) {
            try {
                AudioRecord current = recorder;
                if (current == null) break;
                int read = current.read(frame, 0, frame.length);
                if (read <= 0) continue;
                if (muted.get()) continue;
                byte[] payload = new byte[read];
                System.arraycopy(frame, 0, payload, 0, read);
                link.send(PeerLink.AUDIO, payload);
            } catch (Throwable ignored) {
                if (!running.get()) break;
            }
        }
    }

    /** Голос собеседника приходит из канала: кладём в буфер и играем по порядку. */
    public void onAudio(int sequence, byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        synchronized (bufferLock) {
            if (jitter.size() > 60) jitter.clear(); // канал «залип» — начинаем заново
            jitter.put(sequence, pcm);
            bufferLock.notifyAll();
        }
    }

    private void playbackLoop() {
        while (running.get()) {
            byte[] frame = null;
            synchronized (bufferLock) {
                if (jitter.isEmpty()) {
                    try {
                        bufferLock.wait(60L);
                    } catch (InterruptedException stop) {
                        break;
                    }
                }
                if (!jitter.isEmpty()) {
                    // Держим 2-3 кадра запаса, чтобы голос не «дрожал», но и не отставал.
                    if (jitter.size() < 2) {
                        try {
                            bufferLock.wait(30L);
                        } catch (InterruptedException stop) {
                            break;
                        }
                    }
                    Map.Entry<Integer, byte[]> first = jitter.firstEntry();
                    if (first != null) {
                        Integer number = first.getKey();
                        if (expectedSequence < 0 || number <= expectedSequence + 4) {
                            frame = jitter.pollFirstEntry().getValue();
                            expectedSequence = number;
                        } else {
                            // Пакеты потерялись: пропускаем дырку, иначе голос «застынет».
                            jitter.clear();
                            expectedSequence = -1;
                        }
                    }
                }
            }
            if (frame == null) continue;
            try {
                AudioTrack current = player;
                if (current != null) current.write(frame, 0, frame.length);
            } catch (Throwable ignored) {
                if (!running.get()) break;
            }
        }
    }

    public void stop() {
        running.set(false);
        synchronized (bufferLock) {
            jitter.clear();
            bufferLock.notifyAll();
        }
        Thread capture = captureThread;
        captureThread = null;
        if (capture != null) capture.interrupt();
        Thread playback = playbackThread;
        playbackThread = null;
        if (playback != null) playback.interrupt();
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Throwable ignored) { }
        recorder = null;
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Throwable ignored) { }
        player = null;
    }
}
