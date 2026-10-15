package com.tsuyu.messenger.call;

import android.content.Context;
import android.view.Surface;

import com.tsuyu.messenger.p2p.P2pCall;

import java.util.Locale;

/**
 * Фасад звонков для интерфейса. Внутри — свой чистый P2P ({@link P2pCall}):
 * прямые UDP-сокеты, свой STUN, своё шифрование AES-256-GCM, голос и видео на
 * встроенных средствах Android. Сторонних P2P-библиотек в проекте больше нет.
 */
public final class CallEngine {

    public interface Listener {
        /** state: ringing | connecting | active | declined | ended | missed | failed */
        void onCallState(String state, String detail);
    }

    private static CallEngine instance;

    private final P2pCall call = new P2pCall();
    private final java.util.List<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private CallEngine() {
        call.addListener((state, detail) -> {
            for (Listener listener : listeners) listener.onCallState(state, detail);
        });
    }

    public static synchronized CallEngine get() {
        if (instance == null) instance = new CallEngine();
        return instance;
    }

    /** True, когда идёт незавершённый разговор. */
    public static synchronized boolean busy() {
        return instance != null && instance.call.getCallId() != null && !instance.finished;
    }

    private volatile boolean finished = true;

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isVideoCall() {
        return call.isVideoCall();
    }

    public boolean isMuted() {
        return call.isMuted();
    }

    public boolean isSpeaker() {
        return call.isSpeaker();
    }

    public int relayCount() {
        return call.relayCount();
    }

    public long elapsedMillis() {
        return call.elapsedMillis();
    }

    public String getCallId() {
        return call.getCallId();
    }

    public String getChatId() {
        return call.getChatId();
    }

    public String getPeerUid() {
        return call.getPeerUid();
    }

    public boolean wasIncoming() {
        return call.wasIncoming();
    }

    public void attachSurfaces(Surface local, Surface remote) {
        call.attachSurfaces(local, remote);
    }

    public void startOutgoing(Context context, String chatId, String myUid, String peerUid, boolean video) {
        finished = false;
        call.startOutgoing(context, chatId, myUid, peerUid, video);
    }

    public void startIncoming(Context context, String chatId, String myUid, String peerUid,
                              String callId, boolean video) {
        finished = false;
        call.startIncoming(context, chatId, myUid, peerUid, callId, video);
    }

    public void accept() {
        call.accept();
    }

    public void decline() {
        call.decline();
        finished = true;
    }

    public void hangup() {
        call.hangup();
        finished = true;
    }

    public void finish() {
        call.finish();
        finished = true;
    }

    public void setMuted(boolean value) {
        call.setMuted(value);
    }

    public void setSpeaker(boolean value) {
        call.setSpeaker(value);
    }

    public void setVideoEnabled(boolean enabled) {
        call.setVideoEnabled(enabled);
    }

    public void switchCamera() {
        call.switchCamera();
    }

    public static String formatDuration(long millis) {
        long total = Math.max(0L, millis / 1000L);
        return String.format(Locale.getDefault(), "%02d:%02d", total / 60L, total % 60L);
    }
}
