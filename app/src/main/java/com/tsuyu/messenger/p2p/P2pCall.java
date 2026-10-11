package com.tsuyu.messenger.p2p;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.call.CallService;
import com.tsuyu.messenger.call.CallSignal;
import com.tsuyu.messenger.call.NetworkPriority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Звонок целиком своими силами: адреса и ключи обмениваются через базу, а сам разговор
 * идёт напрямую между телефонами по UDP (см. {@link PeerLink}). Голос — {@link VoiceEngine},
 * видео — {@link VideoEngine}. Ни WebRTC, ни других сторонних P2P-библиотек здесь нет.
 */
public final class P2pCall implements PeerLink.Listener {

    public interface Listener {
        /** state: ringing | connecting | active | declined | ended | missed | failed */
        void onState(String state, String detail);
    }

    private static final String FIELD_STATE = "state";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicBoolean finished = new AtomicBoolean(true);

    private Context context;
    private PeerLink link;
    private VoiceEngine voice;
    private VideoEngine video;
    private Surface localSurface;
    private Surface remoteSurface;

    private DatabaseReference callRef;
    private DatabaseReference mirrorRef;
    private DatabaseReference peerRef;
    private DatabaseReference peerMirrorRef;
    private ValueEventListener stateListener;
    private ValueEventListener peerListener;

    private String chatId;
    private String callId;
    private String myUid;
    private String peerUid;
    private boolean videoCall;
    private boolean incoming;
    private boolean accepted;
    private boolean pendingActive;
    private long startedAt;
    private boolean muted;
    private boolean speaker;
    private boolean peerVideo;
    /** Собеседник взял трубку (по базе) — только тогда включаем звук. */
    private boolean peerAccepted;
    private boolean reconnecting;
    private Runnable ringTimeout;

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isVideoCall() {
        return videoCall;
    }

    public boolean isMuted() {
        return muted;
    }

    public boolean isSpeaker() {
        return speaker;
    }

    public String getChatId() {
        return chatId;
    }

    public String getCallId() {
        return callId;
    }

    public String getPeerUid() {
        return peerUid;
    }

    public boolean wasIncoming() {
        return incoming;
    }

    public int relayCount() {
        return Stun.SERVERS.length;
    }

    public long elapsedMillis() {
        return startedAt <= 0L ? 0L : System.currentTimeMillis() - startedAt;
    }

    // ------------------------------------------------------------------
    // Запуск
    // ------------------------------------------------------------------

    public void startOutgoing(Context context, String chatId, String myUid, String peerUid, boolean video) {
        reset();
        this.context = context.getApplicationContext();
        this.chatId = chatId;
        this.myUid = myUid;
        this.peerUid = peerUid;
        this.videoCall = video;
        this.incoming = false;
        this.finished.set(false);
        callRef = FirebaseDatabase.getInstance().getReference("chats").child(chatId).child("calls").push();
        callId = callRef.getKey();
        link = new PeerLink(callId);
        mirrorRef = mirror(chatId, myUid, callId);
        peerMirrorRef = mirror(chatId, peerUid, callId);
        peerRef = callRef.child("p2p").child(peerUid);
        Map<String, Object> call = new HashMap<>();
        call.put("from", myUid);
        call.put("to", peerUid);
        call.put("video", video);
        call.put(FIELD_STATE, "ringing");
        call.put("createdAt", ServerValue.TIMESTAMP);
        publish(call);
        NetworkPriority.refresh(this.context);
        CallService.start(this.context);
        CallSignal.announce(this.context, chatId, callId, myUid, peerUid, video);
        armRingTimeout();
        notifyState("ringing", null);
        openLink();
        listenToCall();
        listenToPeer();
    }

    public void startIncoming(Context context, String chatId, String myUid, String peerUid,
                              String callId, boolean video) {
        reset();
        this.context = context.getApplicationContext();
        this.chatId = chatId;
        this.myUid = myUid;
        this.peerUid = peerUid;
        this.callId = callId;
        this.videoCall = video;
        this.incoming = true;
        this.finished.set(false);
        link = new PeerLink(callId);
        callRef = FirebaseDatabase.getInstance().getReference("chats").child(chatId).child("calls").child(callId);
        mirrorRef = mirror(chatId, myUid, callId);
        peerMirrorRef = mirror(chatId, peerUid, callId);
        peerRef = callRef.child("p2p").child(peerUid);
        NetworkPriority.refresh(this.context);
        CallService.start(this.context);
        armRingTimeout();
        notifyState("ringing", null);
        openLink();
        listenToCall();
        listenToPeer();
    }

    /** Собеседник нажал «Ответить»: публикуем свои адреса и начинаем пробивку. */
    public void accept() {
        if (accepted) return;
        accepted = true;
        Map<String, Object> update = new HashMap<>();
        update.put(FIELD_STATE, "connecting");
        publish(update);
        notifyState("connecting", null);
        CallSignal.answer(context, chatId, callId, myUid, peerUid);
        announceSelf();
        peerAccepted = true;
        maybeActivate();
    }

    // ------------------------------------------------------------------
    // Канал
    // ------------------------------------------------------------------

    private void openLink() {
        if (link == null) return;
        if (!link.open(this)) {
            finishWith("failed");
            return;
        }
        // Ключ обмена: своё число публикуем сразу, чужое подхватим из базы.
        Map<String, Object> self = new HashMap<>();
        self.put("nonce", link.ownNonceHex());
        self.put("video", videoCall);
        publishSelf(self);
        announceSelf();
    }

    /** Публикуем свой публичный адрес (STUN) и локальные адреса — в фоне, чтобы не тормозить экран. */
    private void announceSelf() {
        final PeerLink current = link;
        if (current == null) return;
        Thread worker = new Thread(() -> {
            try {
                Stun.Endpoint external = current.discoverOwnEndpoint();
                String port = current.localPort();
                List<String> candidates = new ArrayList<>();
                if (external != null) candidates.add(external.value());
                if (port != null) {
                    for (Stun.Endpoint local : Stun.localEndpoints()) {
                        candidates.add(local.host + ":" + port);
                    }
                }
                if (candidates.isEmpty()) return;
                Map<String, Object> self = new HashMap<>();
                self.put("endpoint", join(candidates));
                self.put("at", ServerValue.TIMESTAMP);
                publishSelf(self);
            } catch (Throwable ignored) { }
        }, "tsuyu-p2p-stun");
        worker.setDaemon(true);
        worker.start();
    }

    /** Слушаем данные собеседника: его адреса, его число для ключа и режим видео. */
    private void listenToPeer() {
        if (peerListener != null) return;
        peerListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                String endpoint = snapshot.child("endpoint").getValue(String.class);
                String nonce = snapshot.child("nonce").getValue(String.class);
                Boolean peerWantsVideo = snapshot.child("video").getValue(Boolean.class);
                if (nonce != null && link != null) link.setPeerNonce(nonce);
                if (endpoint != null && link != null) link.setPeerEndpoints(split(endpoint));
                peerVideo = Boolean.TRUE.equals(peerWantsVideo);
                maybeStartMedia();
            }

            @Override public void onCancelled(DatabaseError error) { }
        };
        if (peerRef != null) peerRef.addValueEventListener(peerListener);
        if (peerMirrorRef != null) {
            peerMirrorRef.child("p2p").child(peerUid).addValueEventListener(peerListener);
        }
    }

    /** Состояние разговора: отбой, отказ или «пропущен» приходят от собеседника. */
    private void listenToCall() {
        if (stateListener != null) return;
        stateListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                String state = snapshot.child(FIELD_STATE).getValue(String.class);
                if (state == null) return;
                if ("ended".equals(state) || "declined".equals(state) || "missed".equals(state)) {
                    notifyState(state, null);
                    finish();
                    return;
                }
                if ("connecting".equals(state) || "active".equals(state)) {
                    peerAccepted = true;
                    if (!incoming) notifyState("connecting", null);
                    maybeActivate();
                }
            }

            @Override public void onCancelled(DatabaseError error) { }
        };
        if (callRef != null) callRef.addValueEventListener(stateListener);
        if (peerMirrorRef != null) peerMirrorRef.addValueEventListener(stateListener);
        // Если собеседник уже ждёт ответа — начальный снимок сразу расскажет его данные.
    }

    private void publish(Map<String, Object> values) {
        if (values == null) return;
        try {
            if (callRef != null) callRef.updateChildren(values);
        } catch (Throwable ignored) { }
        try {
            if (mirrorRef != null) mirrorRef.updateChildren(values);
        } catch (Throwable ignored) { }
    }

    private void publishSelf(Map<String, Object> values) {
        if (values == null || myUid == null) return;
        try {
            if (callRef != null) callRef.child("p2p").child(myUid).updateChildren(values);
        } catch (Throwable ignored) { }
        try {
            if (mirrorRef != null) mirrorRef.child("p2p").child(myUid).updateChildren(values);
        } catch (Throwable ignored) { }
    }

    private static DatabaseReference mirror(String chatId, String uid, String callId) {
        if (chatId == null || uid == null || callId == null) return null;
        try {
            return FirebaseDatabase.getInstance().getReference("chats").child(chatId)
                    .child("typing").child(uid).child("call").child(callId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Состояния канала
    // ------------------------------------------------------------------

    @Override public void onConnected() {
        if (finished.get()) return;
        // Канал открылся — но пока трубку не взяли, разговор не начинаем.
        if (incoming && !accepted) {
            notifyState("ringing", null);
            return;
        }
        maybeActivate();
    }

    /** Начинаем разговор только когда канал открыт и оба готовы говорить. */
    private void maybeActivate() {
        if (finished.get() || pendingActive) return;
        if (link == null || !link.isConnected()) return;
        if (incoming && !accepted) return;
        if (!incoming && !peerAccepted && !accepted) return;
        pendingActive = true;
        startedAt = System.currentTimeMillis();
        cancelRingTimeout();
        if (!reconnecting) {
            Map<String, Object> update = new HashMap<>();
            update.put(FIELD_STATE, "active");
            publish(update);
        }
        reconnecting = false;
        notifyState("active", null);
        maybeStartMedia();
    }

    @Override public void onAudio(int sequence, byte[] pcm) {
        try {
            if (voice != null) voice.onAudio(sequence, pcm);
        } catch (Throwable ignored) { }
    }

    @Override public void onVideo(int sequence, byte[] accessUnit) {
        try {
            if (video != null) video.onVideo(sequence, accessUnit);
        } catch (Throwable ignored) { }
    }

    @Override public void onClosed(String reason) {
        if (finished.get()) return;
        if ("peer".equals(reason)) {
            notifyState("ended", null);
            finish();
            return;
        }
        // Связь пропала: один раз пробуем пробиться заново с новым портом.
        if (!reconnecting) {
            reconnecting = true;
            notifyState("connecting", "reconnect");
            Thread worker = new Thread(this::reconnect, "tsuyu-p2p-reconnect");
            worker.setDaemon(true);
            worker.start();
            return;
        }
        notifyState("failed", null);
        finish();
    }

    private void reconnect() {
        try {
            if (link != null) link.close(null);
            link = new PeerLink(callId);
            if (!link.open(this)) {
                main.post(() -> finishWith("failed"));
                return;
            }
            Map<String, Object> self = new HashMap<>();
            self.put("nonce", link.ownNonceHex());
            publishSelf(self);
            announceSelf();
            final long deadline = System.currentTimeMillis() + 15000L;
            while (!finished.get() && !link.isConnected() && System.currentTimeMillis() < deadline) {
                Thread.sleep(300L);
            }
            if (!link.isConnected()) main.post(() -> finishWith("failed"));
        } catch (Throwable ignored) {
            main.post(() -> finishWith("failed"));
        }
    }

    /** Голос включаем, как только канал открыт; видео — когда его ждут обе стороны. */
    private void maybeStartMedia() {
        if (finished.get() || !pendingActive || link == null || !link.isConnected()) return;
        if (voice == null) {
            voice = new VoiceEngine(link);
            if (!voice.start(context)) notifyState("active", "no-audio");
        }
        if (voice != null) voice.setMuted(muted);
        if (videoCall && peerVideo && video == null) {
            video = new VideoEngine(link);
            video.setListener(reason -> notifyState("active", reason));
            if (video.start(context, localSurface, remoteSurface)) {
                notifyState("active", "video");
            } else {
                video = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Управление разговором
    // ------------------------------------------------------------------

    public void attachSurfaces(Surface local, Surface remote) {
        localSurface = local;
        remoteSurface = remote;
        if (video != null) video.attachSurfaces(local, remote);
    }

    public void setMuted(boolean value) {
        muted = value;
        if (voice != null) voice.setMuted(value);
    }

    public void setSpeaker(boolean value) {
        speaker = value;
        try {
            android.media.AudioManager manager =
                    (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) manager.setSpeakerphoneOn(value);
        } catch (Throwable ignored) { }
    }

    public void setVideoEnabled(boolean enabled) {
        if (video == null) {
            if (enabled && videoCall) maybeStartMedia();
            return;
        }
        if (!enabled) {
            try { video.stop(); } catch (Throwable ignored) { }
            video = null;
        } else {
            video = new VideoEngine(link);
            video.start(context, localSurface, remoteSurface);
        }
    }

    public void switchCamera() {
        if (video != null) video.switchCamera();
    }

    public void decline() {
        Map<String, Object> update = new HashMap<>();
        update.put(FIELD_STATE, "declined");
        update.put("endedBy", myUid);
        publish(update);
        notifyState("declined", null);
        CallSignal.cancel(context, chatId, callId, myUid, peerUid, "declined");
        finish();
    }

    public void hangup() {
        if (!finished.get()) {
            Map<String, Object> update = new HashMap<>();
            update.put(FIELD_STATE, "ended");
            update.put("endedBy", myUid);
            update.put("endedAt", ServerValue.TIMESTAMP);
            publish(update);
        }
        notifyState("ended", null);
        CallSignal.cancel(context, chatId, callId, myUid, peerUid, "ended");
        finish();
    }

    private void finishWith(String state) {
        notifyState(state, null);
        finish();
    }

    public void finish() {
        finished.set(true);
        cancelRingTimeout();
        try {
            if (stateListener != null && callRef != null) callRef.removeEventListener(stateListener);
            if (stateListener != null && peerMirrorRef != null) peerMirrorRef.removeEventListener(stateListener);
        } catch (Throwable ignored) { }
        stateListener = null;
        try {
            if (peerListener != null && peerRef != null) peerRef.removeEventListener(peerListener);
            if (peerListener != null && peerMirrorRef != null) {
                peerMirrorRef.child("p2p").child(peerUid).removeEventListener(peerListener);
            }
        } catch (Throwable ignored) { }
        peerListener = null;
        try {
            if (video != null) video.stop();
        } catch (Throwable ignored) { }
        video = null;
        try {
            if (voice != null) voice.stop();
        } catch (Throwable ignored) { }
        voice = null;
        try {
            if (link != null) link.close(null);
        } catch (Throwable ignored) { }
        link = null;
        final DatabaseReference stale = mirrorRef;
        if (stale != null) {
            main.postDelayed(() -> {
                try { stale.removeValue(); } catch (Throwable ignored) { }
            }, 4000L);
        }
        mirrorRef = null;
        peerMirrorRef = null;
        releaseAudio();
        pendingActive = false;
        startedAt = 0L;
        reconnecting = false;
        try {
            com.tsuyu.messenger.call.CallNotifier.clear(context);
            CallService.stop(context);
        } catch (Throwable ignored) { }
        NetworkPriority.refresh(context);
    }

    private void releaseAudio() {
        try {
            android.media.AudioManager manager =
                    (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) {
                manager.setSpeakerphoneOn(false);
                manager.setMode(android.media.AudioManager.MODE_NORMAL);
            }
        } catch (Throwable ignored) { }
    }

    private void reset() {
        finished.set(false);
        accepted = false;
        pendingActive = false;
        peerVideo = false;
        peerAccepted = false;
        startedAt = 0L;
        stateListener = null;
        peerListener = null;
    }

    private void armRingTimeout() {
        cancelRingTimeout();
        ringTimeout = () -> {
            ringTimeout = null;
            if (pendingActive || finished.get()) return;
            Map<String, Object> update = new HashMap<>();
            update.put(FIELD_STATE, "missed");
            update.put("endedBy", myUid);
            update.put("endedAt", ServerValue.TIMESTAMP);
            publish(update);
            CallSignal.cancel(context, chatId, callId, myUid, peerUid, "missed");
            notifyState("missed", null);
            finish();
        };
        main.postDelayed(ringTimeout, 45_000L);
    }

    private void cancelRingTimeout() {
        if (ringTimeout != null) {
            main.removeCallbacks(ringTimeout);
            ringTimeout = null;
        }
    }

    private void notifyState(String state, String detail) {
        if (listeners.isEmpty()) return;
        main.post(() -> {
            for (Listener listener : listeners) listener.onState(state, detail);
        });
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) out.add(part.trim());
        }
        return out;
    }
}
