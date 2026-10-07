package com.noir.p2pchat.core;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import com.noir.p2pchat.AppKernel;
import com.noir.p2pchat.BuildConfig;
import com.noir.p2pchat.MessageNotifications;
import com.noir.p2pchat.core.MessageStore.Message;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * P2P messaging engine. Firebase Realtime Database is used only for authenticated WebRTC signaling;
 * message text and media bytes travel through encrypted WebRTC DataChannels.
 */
public final class P2pEngine {
    private static final String TAG = "NoirP2P";
    private static final int MAX_FILE_BYTES = 50 * 1024 * 1024;
    private static final int FILE_HEADER_BYTES = 41;
    private static final int FILE_CHUNK_BYTES = 15_000;
    private static final long MAX_BUFFERED_BYTES = 512 * 1024;
    private static final long PUBLIC_TURN_CREDENTIAL_TTL_SECONDS = 7L * 24 * 60 * 60;

    // Open Relay publishes this shared static-auth secret for its public TURN service. It is not
    // an app-private key and is extractable from any APK; use a private credential broker for production.
    // The old openrelayproject/openrelayproject public pair is retired, so derive expiring REST credentials.
    private static final String OPEN_RELAY_AUTH_SECRET = "openrelayprojectsecret";
    private static final String[] PUBLIC_STUN_URLS = {
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302",
            "stun:stun3.l.google.com:19302",
            "stun:stun4.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
    };
    private static final String[] OPEN_RELAY_TURN_URLS = {
            "turn:staticauth.openrelay.metered.ca:80?transport=udp",
            "turn:staticauth.openrelay.metered.ca:80?transport=tcp",
            "turn:staticauth.openrelay.metered.ca:443?transport=udp",
            "turn:staticauth.openrelay.metered.ca:443?transport=tcp",
            "turns:staticauth.openrelay.metered.ca:443?transport=tcp"
    };

    private final Context appContext;
    private final FirebaseRestClient firebase;
    private final MessageStore messages;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService protocolExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Map<String, PeerSession> sessionsByPeer = new ConcurrentHashMap<>();
    private final Map<String, PeerSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> pendingInvites = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> reconnectAttempts = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean manuallyStopped = new AtomicBoolean(false);
    private final AtomicBoolean inboxReadRunning = new AtomicBoolean(false);
    private final AtomicBoolean inboxReadRequested = new AtomicBoolean(false);
    private volatile PeerConnectionFactory factory;
    private volatile String uid;
    private volatile String status = "Ожидание подключения к Firebase";
    private volatile String activeChatPeer;

    public P2pEngine(Context context, FirebaseRestClient firebase, MessageStore messages) {
        this.appContext = context.getApplicationContext();
        this.firebase = firebase;
        this.messages = messages;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        String currentUid = uid;
        if (currentUid != null) mainHandler.post(() -> listener.onIdentity(currentUid));
        mainHandler.post(() -> listener.onEngineStatus(status));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public String getUid() {
        return uid;
    }

    public String getStatus() {
        return status;
    }

    public List<String> getPendingInvites() {
        ArrayList<String> result = new ArrayList<>(pendingInvites.keySet());
        Collections.sort(result);
        return result;
    }

    public void setActiveChatPeer(String peerUid) {
        activeChatPeer = peerUid;
    }

    public void start() {
        manuallyStopped.set(false);
        if (!started.compareAndSet(false, true)) return;
        setStatus("Авторизация в Firebase…");
        ioExecutor.execute(() -> {
            try {
                firebase.ensureIdToken();
                if (!started.get()) return;
                uid = firebase.currentUid();
                if (uid == null) throw new IOException("Firebase не вернул идентификатор пользователя");
                initializeWebRtc();
                publishProfile();
                if (!started.get()) return;
                notifyIdentity(uid);
                setStatus("Сигналинг Firebase активен");
                startInboxStream();
                processPendingOutbox();
            } catch (Exception e) {
                Log.e(TAG, "Unable to start signaling", e);
                started.set(false);
                setStatus(firebaseErrorText(e));
                String detail = e.getMessage() == null ? "" : e.getMessage();
                if (!detail.contains("OPERATION_NOT_ALLOWED") && !detail.contains("PERMISSION_DENIED")
                        && !detail.contains("HTTP 401") && !detail.contains("HTTP 403")) {
                    scheduler.schedule(() -> {
                        if (!manuallyStopped.get()) start();
                    }, 8, TimeUnit.SECONDS);
                }
            }
        });
    }

    public synchronized void stop() {
        manuallyStopped.set(true);
        if (!started.compareAndSet(true, false)) return;
        FirebaseRestClient.StreamHandle inbox = inboxStream;
        inboxStream = null;
        if (inbox != null) inbox.close();
        for (PeerSession session : sessionsByPeer.values()) session.close();
        sessionsByPeer.clear();
        sessionsById.clear();
        reconnectAttempts.clear();
        setStatus("P2P-сервис остановлен");
    }

    private volatile FirebaseRestClient.StreamHandle inboxStream;

    private void initializeWebRtc() {
        if (factory != null) return;
        synchronized (P2pEngine.class) {
            if (factory != null) return;
            PeerConnectionFactory.InitializationOptions options =
                    PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions();
            PeerConnectionFactory.initialize(options);
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
        }
    }

    private void publishProfile() throws IOException, JSONException {
        JSONObject profile = new JSONObject();
        profile.put("app", "NoirP2P");
        profile.put("protocol", 1);
        profile.put("updatedAt", System.currentTimeMillis());
        firebase.put("profiles/" + uid, profile);
    }

    private synchronized void startInboxStream() {
        if (!started.get()) return;
        FirebaseRestClient.StreamHandle old = inboxStream;
        if (old != null) old.close();
        FirebaseRestClient.StreamHandle next = firebase.stream("inbox/" + uid, new FirebaseRestClient.StreamListener() {
            @Override
            public void onEvent(String event, JSONObject payload) {
                if (status.startsWith("Сигналинг переподключается")) setStatus("Сигналинг Firebase активен");
                readInbox();
            }

            @Override
            public void onStreamError(Exception error) {
                Log.w(TAG, "Inbox stream reconnecting", error);
                if (started.get()) setStatus("Сигналинг переподключается…");
            }
        });
        if (started.get()) inboxStream = next;
        else next.close();
    }

    private void readInbox() {
        if (!started.get() || uid == null) return;
        if (!inboxReadRunning.compareAndSet(false, true)) {
            inboxReadRequested.set(true);
            return;
        }
        ioExecutor.execute(() -> {
            try {
                do {
                    inboxReadRequested.set(false);
                    readInboxSnapshot();
                } while (started.get() && inboxReadRequested.getAndSet(false));
            } finally {
                inboxReadRunning.set(false);
                if (inboxReadRequested.getAndSet(false)) readInbox();
            }
        });
    }

    private void readInboxSnapshot() {
        try {
            JSONObject inbox = firebase.get("inbox/" + uid);
            if (inbox == null) return;
            Iterator<String> keys = inbox.keys();
            while (keys.hasNext()) {
                String sessionId = keys.next();
                if (!sessionId.matches("[A-Za-z0-9_-]{1,128}")) continue;
                JSONObject invite = inbox.optJSONObject(sessionId);
                if (invite == null) continue;
                String callerUid = invite.optString("from", "");
                if (!isValidUid(callerUid) || callerUid.equals(uid)) continue;
                readCall(sessionId, callerUid);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not refresh signaling inbox", e);
            if (started.get()) setStatus("Нет связи с Firebase · повторяем подключение");
        }
    }

    private void readCall(String sessionId, String expectedCaller) {
        ioExecutor.execute(() -> {
            try {
                JSONObject call = firebase.get("calls/" + sessionId);
                if (call == null) return;
                String caller = call.optString("caller", "");
                String callee = call.optString("callee", "");
                if (!expectedCaller.equals(caller) || !uid.equals(callee)) return;
                if (!messages.isContact(caller)) {
                    Set<String> ids = pendingInvites.computeIfAbsent(caller, ignored -> ConcurrentHashMap.newKeySet());
                    if (ids.add(sessionId)) {
                        notifyInvite(caller);
                        MessageNotifications.showIncoming(appContext, caller);
                    }
                    return;
                }
                acceptCall(sessionId, caller, call);
            } catch (Exception e) {
                Log.w(TAG, "Could not load signaling call " + sessionId, e);
            }
        });
    }

    public void addContact(String peerUid) {
        String clean = peerUid == null ? "" : peerUid.trim();
        if (!isValidUid(clean) || clean.equals(uid)) {
            setStatus("Введите корректный ID собеседника");
            return;
        }
        messages.addContact(clean);
        pendingInvites.remove(clean);
        notifyContactsChanged();
        readInbox();
        requestConnection(clean);
    }

    public void requestConnection(String peerUid) {
        if (!isValidUid(peerUid) || peerUid.equals(uid)) return;
        if (!started.get()) start();
        // start() resumes queued contacts after Firebase authentication succeeds.
        if (uid == null) return;
        PeerSession current = sessionsByPeer.get(peerUid);
        if (current != null && !current.closed.get()) {
            if (current.isOpen()) dispatchPending(peerUid);
            return;
        }
        // Deterministic offerer avoids simultaneous-offer glare: the lexicographically smaller UID calls.
        if (uid.compareTo(peerUid) < 0) beginOutgoing(peerUid);
        else notifyPeerState(peerUid, "Ожидаем приглашение от собеседника");
    }

    private synchronized void beginOutgoing(String peerUid) {
        if (!started.get() || uid == null || uid.compareTo(peerUid) >= 0) return;
        PeerSession existing = sessionsByPeer.get(peerUid);
        if (existing != null && !existing.closed.get()) return;
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        PeerSession session = new PeerSession(sessionId, peerUid, true);
        PeerSession previous = sessionsByPeer.put(peerUid, session);
        if (previous != null) previous.close();
        sessionsById.put(sessionId, session);
        notifyPeerState(peerUid, "Создаём P2P-канал…");
        try {
            createPeerConnection(session);
            session.dataChannel = session.peerConnection.createDataChannel("noir-chat-v1", orderedChannel());
            watchDataChannel(session, session.dataChannel);
            watchSignaling(session);
            session.peerConnection.createOffer(new SdpObserverAdapter() {
                @Override public void onCreateSuccess(SessionDescription offer) {
                    session.peerConnection.setLocalDescription(new SdpObserverAdapter() {
                        @Override public void onSetSuccess() { publishOffer(session, offer); }
                        @Override public void onSetFailure(String error) { failSession(session, "Не удалось установить SDP offer: " + error); }
                    }, offer);
                }
                @Override public void onCreateFailure(String error) { failSession(session, "Не удалось создать SDP offer: " + error); }
            }, new MediaConstraints());
        } catch (Exception e) {
            failSession(session, "Не удалось создать P2P-соединение: " + e.getMessage());
        }
    }

    private DataChannel.Init orderedChannel() {
        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        init.maxRetransmits = -1;
        return init;
    }

    private void publishOffer(PeerSession session, SessionDescription offer) {
        JSONObject call = new JSONObject();
        try {
            call.put("caller", uid);
            call.put("callee", session.peerUid);
            call.put("createdAt", System.currentTimeMillis());
            call.put("offer", sessionDescriptionJson(offer));
        } catch (JSONException e) {
            failSession(session, "Некорректное SDP-предложение");
            return;
        }
        ioExecutor.execute(() -> {
            try {
                firebase.put("calls/" + session.id, call);
                firebase.put("inbox/" + session.peerUid + "/" + session.id,
                        new JSONObject().put("from", uid).put("createdAt", System.currentTimeMillis()));
                session.published.set(true);
                flushLocalCandidates(session);
                setStatus("Ожидаем P2P-ответ собеседника");
                notifyPeerState(session.peerUid, "Ожидаем ответ собеседника…");
                refreshCall(session);
            } catch (Exception e) {
                failSession(session, "Firebase не принял сигналинг: " + e.getMessage());
            }
        });
    }

    private synchronized void acceptCall(String sessionId, String peerUid, JSONObject call) {
        PeerSession current = sessionsByPeer.get(peerUid);
        if (current != null && !current.closed.get()) {
            if (current.id.equals(sessionId)) refreshCall(current);
            return;
        }
        PeerSession session = new PeerSession(sessionId, peerUid, false);
        PeerSession previous = sessionsByPeer.put(peerUid, session);
        if (previous != null) previous.close();
        sessionsById.put(sessionId, session);
        pendingInvites.remove(peerUid);
        notifyPeerState(peerUid, "Принимаем P2P-подключение…");
        try {
            createPeerConnection(session);
            watchSignaling(session);
            JSONObject offerJson = call.optJSONObject("offer");
            if (offerJson == null) throw new IOException("В приглашении нет SDP offer");
            SessionDescription offer = sessionDescriptionFromJson(offerJson);
            session.remoteDescriptionStarted.set(true);
            session.peerConnection.setRemoteDescription(new SdpObserverAdapter() {
                @Override public void onSetSuccess() {
                    markRemoteDescriptionReady(session);
                    session.peerConnection.createAnswer(new SdpObserverAdapter() {
                        @Override public void onCreateSuccess(SessionDescription answer) {
                            session.peerConnection.setLocalDescription(new SdpObserverAdapter() {
                                @Override public void onSetSuccess() { publishAnswer(session, answer); }
                                @Override public void onSetFailure(String error) { failSession(session, "Не удалось установить SDP answer: " + error); }
                            }, answer);
                        }
                        @Override public void onCreateFailure(String error) { failSession(session, "Не удалось создать SDP answer: " + error); }
                    }, new MediaConstraints());
                }
                @Override public void onSetFailure(String error) { failSession(session, "Не удалось принять SDP offer: " + error); }
            }, offer);
            handleCallSnapshot(session, call);
        } catch (Exception e) {
            failSession(session, "Не удалось принять приглашение: " + e.getMessage());
        }
    }

    private void publishAnswer(PeerSession session, SessionDescription answer) {
        ioExecutor.execute(() -> {
            try {
                firebase.put("calls/" + session.id + "/answer", sessionDescriptionJson(answer));
                // Only the callee may clear its own inbox item. The call record remains for the caller's ICE stream.
                firebase.delete("inbox/" + uid + "/" + session.id);
                session.published.set(true);
                flushLocalCandidates(session);
                refreshCall(session);
            } catch (Exception e) {
                failSession(session, "Не удалось опубликовать SDP answer: " + e.getMessage());
            }
        });
    }

    private void watchSignaling(PeerSession session) {
        session.callStream = firebase.stream("calls/" + session.id, new FirebaseRestClient.StreamListener() {
            @Override public void onEvent(String event, JSONObject payload) {
                ioExecutor.execute(() -> refreshCall(session));
            }
            @Override public void onStreamError(Exception error) {
                Log.w(TAG, "Call stream reconnecting", error);
            }
        });
    }

    private void refreshCall(PeerSession session) {
        if (session.closed.get()) return;
        ioExecutor.execute(() -> {
            try {
                JSONObject call = firebase.get("calls/" + session.id);
                if (call != null && !session.closed.get()) handleCallSnapshot(session, call);
            } catch (Exception e) {
                Log.w(TAG, "Could not refresh call signaling", e);
            }
        });
    }

    private void handleCallSnapshot(PeerSession session, JSONObject call) {
        if (session.closed.get()) return;
        if (session.offerer) {
            JSONObject answer = call.optJSONObject("answer");
            if (answer != null && session.remoteDescriptionStarted.compareAndSet(false, true)) {
                try {
                    SessionDescription remoteAnswer = sessionDescriptionFromJson(answer);
                    session.peerConnection.setRemoteDescription(new SdpObserverAdapter() {
                        @Override public void onSetSuccess() { markRemoteDescriptionReady(session); }
                        @Override public void onSetFailure(String error) { failSession(session, "Не удалось принять SDP answer: " + error); }
                    }, remoteAnswer);
                } catch (Exception e) {
                    failSession(session, "Некорректный SDP answer: " + e.getMessage());
                }
            }
        }
        JSONObject allCandidates = call.optJSONObject("candidates");
        JSONObject remoteCandidates = allCandidates == null ? null : allCandidates.optJSONObject(session.peerUid);
        if (remoteCandidates == null) return;
        Iterator<String> keys = remoteCandidates.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!session.remoteCandidateKeys.add(key)) continue;
            JSONObject json = remoteCandidates.optJSONObject(key);
            if (json == null) continue;
            IceCandidate candidate = new IceCandidate(
                    json.isNull("sdpMid") ? null : json.optString("sdpMid", null),
                    json.optInt("sdpMLineIndex", 0),
                    json.optString("candidate", ""));
            if (candidate.sdp == null || candidate.sdp.isEmpty()) continue;
            if (session.remoteDescriptionReady.get()) session.peerConnection.addIceCandidate(candidate);
            else session.pendingRemoteCandidates.add(candidate);
        }
    }

    private void markRemoteDescriptionReady(PeerSession session) {
        session.remoteDescriptionReady.set(true);
        synchronized (session.pendingRemoteCandidates) {
            for (IceCandidate candidate : session.pendingRemoteCandidates) {
                session.peerConnection.addIceCandidate(candidate);
            }
            session.pendingRemoteCandidates.clear();
        }
        refreshCall(session);
    }

    private void createPeerConnection(PeerSession session) throws IOException {
        PeerConnectionFactory currentFactory = factory;
        if (currentFactory == null) throw new IOException("WebRTC не инициализирован");
        List<PeerConnection.IceServer> servers = iceServers();
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(servers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        session.peerConnection = currentFactory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                onIceState(session, state);
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
            @Override public void onIceCandidate(IceCandidate candidate) { publishLocalCandidate(session, candidate); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(org.webrtc.MediaStream stream) { }
            @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }
            @Override public void onDataChannel(DataChannel channel) {
                session.dataChannel = channel;
                watchDataChannel(session, channel);
            }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, org.webrtc.MediaStream[] streams) { }
            @Override public void onTrack(org.webrtc.RtpTransceiver transceiver) { }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) { }
            @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState state) { }
        });
        if (session.peerConnection == null) throw new IOException("WebRTC не создал PeerConnection");
    }

    private List<PeerConnection.IceServer> iceServers() {
        ArrayList<PeerConnection.IceServer> result = new ArrayList<>();
        for (String url : PUBLIC_STUN_URLS) {
            result.add(PeerConnection.IceServer.builder(url).createIceServer());
        }

        // Prefer project-owned credentials when configured, then retain the public shared fallback.
        addConfiguredTurnServers(result);
        addOpenRelayTurnServers(result);
        return result;
    }

    private void addConfiguredTurnServers(List<PeerConnection.IceServer> result) {
        String urls = BuildConfig.TURN_URLS == null ? "" : BuildConfig.TURN_URLS.trim();
        String username = BuildConfig.TURN_USERNAME == null ? "" : BuildConfig.TURN_USERNAME;
        String credential = BuildConfig.TURN_CREDENTIAL == null ? "" : BuildConfig.TURN_CREDENTIAL;
        if (urls.isEmpty() || username.isEmpty() || credential.isEmpty()) return;
        for (String raw : urls.split(",")) {
            String url = raw.trim();
            if (!url.isEmpty()) {
                result.add(PeerConnection.IceServer.builder(url)
                        .setUsername(username)
                        .setPassword(credential)
                        .createIceServer());
            }
        }
    }

    private void addOpenRelayTurnServers(List<PeerConnection.IceServer> result) {
        long expiresAt = System.currentTimeMillis() / 1000L + PUBLIC_TURN_CREDENTIAL_TTL_SECONDS;
        String userId = uid == null || uid.isEmpty() ? "noirp2p" : uid;
        String username = expiresAt + ":" + userId;
        try {
            String credential = createTurnRestCredential(username, OPEN_RELAY_AUTH_SECRET);
            for (String url : OPEN_RELAY_TURN_URLS) {
                result.add(PeerConnection.IceServer.builder(url)
                        .setUsername(username)
                        .setPassword(credential)
                        .createIceServer());
            }
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Could not generate public TURN credentials", e);
        }
    }

    private static String createTurnRestCredential(String username, String sharedSecret)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
    }

    private void publishLocalCandidate(PeerSession session, IceCandidate candidate) {
        JSONObject json = new JSONObject();
        try {
            json.put("sdpMid", candidate.sdpMid == null ? JSONObject.NULL : candidate.sdpMid);
            json.put("sdpMLineIndex", candidate.sdpMLineIndex);
            json.put("candidate", candidate.sdp);
        } catch (JSONException ignored) { return; }
        if (!session.published.get()) {
            session.pendingLocalCandidates.add(json);
            return;
        }
        writeCandidate(session, json);
    }

    private void flushLocalCandidates(PeerSession session) {
        synchronized (session.pendingLocalCandidates) {
            while (!session.pendingLocalCandidates.isEmpty()) writeCandidate(session, session.pendingLocalCandidates.remove(0));
        }
    }

    private void writeCandidate(PeerSession session, JSONObject candidate) {
        ioExecutor.execute(() -> {
            try {
                firebase.post("calls/" + session.id + "/candidates/" + uid, candidate);
            } catch (Exception e) {
                Log.w(TAG, "Could not publish ICE candidate", e);
            }
        });
    }

    private void watchDataChannel(PeerSession session, DataChannel channel) {
        if (!session.observedChannels.add(channel)) return;
        channel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) {
                if (channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() < MAX_BUFFERED_BYTES) {
                    dispatchPending(session.peerUid);
                }
            }
            @Override public void onStateChange() {
                DataChannel.State state = channel.state();
                if (state == DataChannel.State.OPEN) {
                    reconnectAttempts.remove(session.peerUid);
                    notifyPeerState(session.peerUid, "P2P-соединение защищено и активно");
                    setStatus("P2P-канал активен · сообщения идут напрямую");
                    scheduleSignalingCleanup(session);
                    dispatchPending(session.peerUid);
                } else if (state == DataChannel.State.CLOSED) {
                    notifyPeerState(session.peerUid, "P2P-канал закрыт · переподключаемся");
                }
            }
            @Override public void onMessage(DataChannel.Buffer buffer) {
                ByteBuffer duplicate = buffer.data.duplicate();
                byte[] bytes = new byte[duplicate.remaining()];
                duplicate.get(bytes);
                protocolExecutor.execute(() -> processChannelData(session, buffer.binary, bytes));
            }
        });
        if (channel.state() == DataChannel.State.OPEN) dispatchPending(session.peerUid);
    }

    private void scheduleSignalingCleanup(PeerSession session) {
        if (!session.cleanupScheduled.compareAndSet(false, true)) return;
        scheduler.schedule(() -> {
            if (session.closed.get() || sessionsByPeer.get(session.peerUid) != session) return;
            ioExecutor.execute(() -> {
                try {
                    String calleeUid = session.offerer ? session.peerUid : uid;
                    firebase.delete("inbox/" + calleeUid + "/" + session.id);
                    firebase.delete("calls/" + session.id);
                } catch (Exception e) {
                    Log.w(TAG, "Could not clean up completed signaling session", e);
                } finally {
                    FirebaseRestClient.StreamHandle stream = session.callStream;
                    session.callStream = null;
                    if (stream != null) stream.close();
                }
            });
        }, 15, TimeUnit.SECONDS);
    }

    private void onIceState(PeerSession session, PeerConnection.IceConnectionState state) {
        if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
            reconnectAttempts.remove(session.peerUid);
            notifyPeerState(session.peerUid, "P2P-сеть согласована · открываем чат");
        } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
            notifyPeerState(session.peerUid, "Связь нестабильна · ждём восстановления…");
            scheduleReconnect(session, 10);
        } else if (state == PeerConnection.IceConnectionState.FAILED) {
            notifyPeerState(session.peerUid, "Не удалось пройти NAT · переподключаемся");
            scheduleReconnect(session, 4);
        }
    }

    private void scheduleReconnect(PeerSession session, int delaySeconds) {
        if (!session.reconnectScheduled.compareAndSet(false, true)) return;
        AtomicInteger attempts = reconnectAttempts.computeIfAbsent(session.peerUid, ignored -> new AtomicInteger());
        int attempt = Math.min(attempts.getAndIncrement(), 4);
        long delay = Math.min(delaySeconds * (1L << attempt), 60L);
        scheduler.schedule(() -> {
            session.reconnectScheduled.set(false);
            if (!started.get() || sessionsByPeer.get(session.peerUid) != session || session.isIceConnected()) return;
            closeSession(session);
            requestConnection(session.peerUid);
        }, delay, TimeUnit.SECONDS);
    }

    private void closeSession(PeerSession session) {
        if (sessionsByPeer.remove(session.peerUid, session)) {
            sessionsById.remove(session.id, session);
            discardSignaling(session);
            session.close();
        }
    }

    private void discardSignaling(PeerSession session) {
        if (uid == null) return;
        ioExecutor.execute(() -> {
            String calleeUid = session.offerer ? session.peerUid : uid;
            try { firebase.delete("inbox/" + calleeUid + "/" + session.id); }
            catch (Exception e) { Log.w(TAG, "Could not remove abandoned invite", e); }
            try { firebase.delete("calls/" + session.id); }
            catch (Exception e) { Log.w(TAG, "Could not remove abandoned signaling record", e); }
        });
    }

    private void failSession(PeerSession session, String error) {
        Log.e(TAG, error);
        notifyPeerState(session.peerUid, error);
        closeSession(session);
    }

    public void sendText(String peerUid, String text) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) return;
        if (body.length() > 12_000) {
            setStatus("Сообщение слишком длинное (лимит 12 000 символов)");
            return;
        }
        if (!isValidUid(peerUid) || uid == null) return;
        long now = System.currentTimeMillis();
        Message message = new Message(UUID.randomUUID().toString(), peerUid, uid, "text", body,
                "text/plain", null, now, true, "pending", 0);
        messages.insertMessage(message);
        notifyMessages(peerUid);
        requestConnection(peerUid);
        dispatchPending(peerUid);
    }

    public void sendAttachment(String peerUid, Uri source, String requestedKind) {
        if (source == null || !isValidUid(peerUid) || uid == null) return;
        String messageId = UUID.randomUUID().toString();
        ioExecutor.execute(() -> {
            File copied = null;
            try {
                ContentResolver resolver = appContext.getContentResolver();
                String name = queryDisplayName(resolver, source);
                String mime = resolver.getType(source);
                if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
                File mediaDirectory = new File(appContext.getFilesDir(), "media");
                if (!mediaDirectory.exists() && !mediaDirectory.mkdirs()) throw new IOException("Не удалось подготовить хранилище медиа");
                copied = new File(mediaDirectory, "out_" + messageId);
                long size = copyUriToFile(resolver, source, copied);
                if (size <= 0L) throw new IOException("Файл пустой");
                if (size > MAX_FILE_BYTES) throw new IOException("Лимит вложения — 50 МБ");
                String kind = requestedKind == null ? kindFromMime(mime) : requestedKind;
                Message message = new Message(messageId, peerUid, uid, kind, name, mime,
                        copied.getAbsolutePath(), System.currentTimeMillis(), true, "pending", size);
                messages.insertMessage(message);
                notifyMessages(peerUid);
                requestConnection(peerUid);
                dispatchPending(peerUid);
            } catch (Exception e) {
                if (copied != null) //noinspection ResultOfMethodCallIgnored
                    copied.delete();
                Log.w(TAG, "Could not prepare attachment", e);
                setStatus("Не удалось подготовить файл: " + e.getMessage());
            }
        });
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        String name = null;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = "Вложение";
        name = new File(name).getName().replaceAll("[\\r\\n]", "_");
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private static long copyUriToFile(ContentResolver resolver, Uri uri, File destination) throws IOException {
        long count = 0L;
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Не удалось открыть выбранный файл");
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > MAX_FILE_BYTES) throw new IOException("Лимит вложения — 50 МБ");
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            throw e;
        }
        return count;
    }

    private static String kindFromMime(String mime) {
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        return "file";
    }

    private void dispatchPending(String peerUid) {
        PeerSession session = sessionsByPeer.get(peerUid);
        if (session == null || !session.isOpen() || !session.dispatching.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            try {
                for (Message message : messages.getPending(peerUid, 100)) {
                    if (!started.get() || sessionsByPeer.get(peerUid) != session || !session.isOpen()) break;
                    boolean sent;
                    if ("text".equals(message.kind)) sent = sendTextFrame(session, message);
                    else sent = sendFileFrames(session, message);
                    if (!sent) break;
                    messages.markSent(message.id);
                    notifyMessages(peerUid);
                }
            } finally {
                session.dispatching.set(false);
                if (session.isOpen() && !messages.getPending(peerUid, 1).isEmpty()) {
                    scheduler.schedule(() -> dispatchPending(peerUid), 500, TimeUnit.MILLISECONDS);
                }
            }
        });
    }

    private boolean sendTextFrame(PeerSession session, Message message) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", "text");
            json.put("id", message.id);
            json.put("body", message.body);
            json.put("timestamp", message.createdAt);
            return sendJson(session, json);
        } catch (JSONException e) {
            return false;
        }
    }

    private boolean sendFileFrames(PeerSession session, Message message) {
        File file = message.filePath == null ? null : new File(message.filePath);
        if (file == null || !file.isFile() || file.length() > MAX_FILE_BYTES) {
            messages.updateStatus(message.id, "failed");
            notifyMessages(message.peerUid);
            return false;
        }
        JSONObject start = new JSONObject();
        try {
            start.put("type", "file_start");
            start.put("id", message.id);
            start.put("name", message.body);
            start.put("mime", message.mime == null ? "application/octet-stream" : message.mime);
            start.put("kind", message.kind);
            start.put("size", file.length());
        } catch (JSONException e) { return false; }
        if (!sendJsonWaiting(session, start)) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] payload = new byte[FILE_CHUNK_BYTES];
            int index = 0;
            int read;
            while ((read = input.read(payload)) != -1) {
                if (!session.isOpen() || !started.get()) return false;
                ByteBuffer frame = ByteBuffer.allocate(FILE_HEADER_BYTES + read);
                frame.put((byte) 'F');
                byte[] id = message.id.getBytes(StandardCharsets.US_ASCII);
                byte[] paddedId = new byte[36];
                System.arraycopy(id, 0, paddedId, 0, Math.min(36, id.length));
                frame.put(paddedId);
                frame.putInt(index++);
                frame.put(payload, 0, read);
                if (!sendBinaryWaiting(session, frame.array())) return false;
            }
        } catch (IOException e) {
            Log.w(TAG, "Attachment transfer read failed", e);
            messages.updateStatus(message.id, "failed");
            return false;
        }
        JSONObject end = new JSONObject();
        try {
            end.put("type", "file_end");
            end.put("id", message.id);
        } catch (JSONException e) { return false; }
        return sendJsonWaiting(session, end);
    }

    private boolean sendJsonWaiting(PeerSession session, JSONObject json) {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2);
        while (session.isOpen() && started.get() && System.currentTimeMillis() < deadline) {
            if (session.dataChannel.bufferedAmount() < MAX_BUFFERED_BYTES
                    && session.dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false))) return true;
            sleepQuietly(40);
        }
        return false;
    }

    private boolean sendBinaryWaiting(PeerSession session, byte[] bytes) {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2);
        while (session.isOpen() && started.get() && System.currentTimeMillis() < deadline) {
            if (session.dataChannel.bufferedAmount() < MAX_BUFFERED_BYTES
                    && session.dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), true))) return true;
            sleepQuietly(30);
        }
        return false;
    }

    private static boolean sendJson(PeerSession session, JSONObject json) {
        if (!session.isOpen()) return false;
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        return session.dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
    }

    private void processChannelData(PeerSession session, boolean binary, byte[] bytes) {
        if (session.closed.get()) return;
        if (binary) {
            receiveFileChunk(session, bytes);
            return;
        }
        try {
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String type = json.optString("type", "");
            if ("text".equals(type)) {
                String id = json.optString("id", "");
                String body = json.optString("body", "");
                if (id.isEmpty() || body.length() > 12_000) return;
                messages.insertMessage(new Message(id, session.peerUid, session.peerUid, "text", body,
                        "text/plain", null, json.optLong("timestamp", System.currentTimeMillis()), false,
                        "received", 0));
                try { sendJson(session, new JSONObject().put("type", "text_ack").put("id", id)); }
                catch (JSONException ignored) { }
                notifyMessages(session.peerUid);
                if (appContext instanceof AppKernel && !((AppKernel) appContext).shouldSuppressNotification(session.peerUid)) {
                    MessageNotifications.showMessage(appContext, session.peerUid, body);
                }
            } else if ("file_start".equals(type)) {
                beginIncomingFile(session, json);
            } else if ("file_end".equals(type)) {
                finishIncomingFile(session, json.optString("id", ""));
            } else if ("file_ack".equals(type) || "text_ack".equals(type)) {
                String id = json.optString("id", "");
                messages.updateStatus(id, "delivered");
                notifyMessages(session.peerUid);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Ignored invalid DataChannel message", e);
        }
    }

    private void beginIncomingFile(PeerSession session, JSONObject json) {
        String id = json.optString("id", "");
        String name = json.optString("name", "Вложение");
        String mime = json.optString("mime", "application/octet-stream");
        String kind = json.optString("kind", kindFromMime(mime));
        long size = json.optLong("size", -1L);
        if (!id.matches("[A-Za-z0-9_-]{1,80}") || size < 0 || size > MAX_FILE_BYTES) return;
        try {
            File directory = new File(appContext.getFilesDir(), "media");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Не удалось создать каталог медиа");
            File partial = new File(directory, "incoming_" + id + ".part");
            IncomingFile old = session.incomingFile;
            if (old != null) {
                old.closeQuietly();
                //noinspection ResultOfMethodCallIgnored
                old.partial.delete();
                messages.updateStatus(old.id, "failed");
            }
            FileOutputStream output = new FileOutputStream(partial, false);
            IncomingFile incoming = new IncomingFile(id, partial, name, mime, kind, size, output);
            session.incomingFile = incoming;
            messages.insertMessage(new Message(id, session.peerUid, session.peerUid, kind, name,
                    mime, partial.getAbsolutePath(), System.currentTimeMillis(), false, "receiving", size));
            messages.updateFilePath(id, partial, "receiving");
            notifyMessages(session.peerUid);
        } catch (Exception e) {
            Log.w(TAG, "Could not receive attachment", e);
        }
    }

    private void receiveFileChunk(PeerSession session, byte[] frame) {
        if (frame.length < FILE_HEADER_BYTES || frame[0] != (byte) 'F') return;
        String id = new String(frame, 1, 36, StandardCharsets.US_ASCII).trim();
        int index = ByteBuffer.wrap(frame, 37, 4).getInt();
        IncomingFile file = session.incomingFile;
        if (file == null || !file.id.equals(id) || index != file.nextChunk) return;
        int payloadLength = frame.length - FILE_HEADER_BYTES;
        if (file.receivedBytes + payloadLength > file.expectedSize || file.receivedBytes + payloadLength > MAX_FILE_BYTES) {
            file.closeQuietly();
            session.incomingFile = null;
            messages.updateStatus(file.id, "failed");
            notifyMessages(session.peerUid);
            return;
        }
        try {
            file.output.write(frame, FILE_HEADER_BYTES, payloadLength);
            file.receivedBytes += payloadLength;
            file.nextChunk++;
        } catch (IOException e) {
            file.closeQuietly();
            session.incomingFile = null;
            messages.updateStatus(file.id, "failed");
            notifyMessages(session.peerUid);
        }
    }

    private void finishIncomingFile(PeerSession session, String id) {
        IncomingFile incoming = session.incomingFile;
        if (incoming == null || !incoming.id.equals(id)) return;
        session.incomingFile = null;
        try {
            incoming.output.flush();
            incoming.output.close();
            if (incoming.receivedBytes != incoming.expectedSize) {
                messages.updateStatus(id, "failed");
                //noinspection ResultOfMethodCallIgnored
                incoming.partial.delete();
            } else {
                String extension = extensionForMime(incoming.mime);
                File target = new File(incoming.partial.getParentFile(), "received_" + id + extension);
                if (target.exists()) //noinspection ResultOfMethodCallIgnored
                    target.delete();
                if (!incoming.partial.renameTo(target)) throw new IOException("Не удалось завершить файл");
                messages.updateFilePath(id, target, "received");
                JSONObject ack = new JSONObject().put("type", "file_ack").put("id", id);
                sendJson(session, ack);
                if (appContext instanceof AppKernel && !((AppKernel) appContext).shouldSuppressNotification(session.peerUid)) {
                    MessageNotifications.showMessage(appContext, session.peerUid, "Получен файл: " + incoming.name);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not finish received attachment", e);
            messages.updateStatus(id, "failed");
        }
        notifyMessages(session.peerUid);
    }

    private static String extensionForMime(String mime) {
        if (mime == null) return "";
        if (mime.equals("image/jpeg")) return ".jpg";
        if (mime.equals("image/png")) return ".png";
        if (mime.equals("image/webp")) return ".webp";
        if (mime.equals("video/mp4")) return ".mp4";
        if (mime.equals("audio/mp4") || mime.equals("audio/aac")) return ".m4a";
        if (mime.equals("audio/mpeg")) return ".mp3";
        if (mime.equals("application/pdf")) return ".pdf";
        return "";
    }

    private void processPendingOutbox() {
        for (String peer : messages.getContacts()) {
            if (!messages.getPending(peer, 1).isEmpty()) requestConnection(peer);
        }
    }

    private static boolean isValidUid(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{8,128}");
    }

    private static JSONObject sessionDescriptionJson(SessionDescription description) throws JSONException {
        return new JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description);
    }

    private static SessionDescription sessionDescriptionFromJson(JSONObject json) throws JSONException {
        SessionDescription.Type type = SessionDescription.Type.fromCanonicalForm(json.getString("type"));
        return new SessionDescription(type, json.getString("sdp"));
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void setStatus(String newStatus) {
        status = newStatus;
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onEngineStatus(newStatus);
        });
    }

    private void notifyIdentity(String value) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onIdentity(value);
        });
    }

    private void notifyPeerState(String peerUid, String value) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onPeerState(peerUid, value);
        });
    }

    private void notifyMessages(String peerUid) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onMessagesChanged(peerUid);
        });
    }

    private void notifyInvite(String peerUid) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onIncomingInvite(peerUid);
        });
    }

    private void notifyContactsChanged() {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onContactsChanged();
        });
    }

    private static String firebaseErrorText(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("OPERATION_NOT_ALLOWED")) {
            return "В Firebase включите Anonymous Authentication";
        }
        if (message.contains("PERMISSION_DENIED") || message.contains("HTTP 401") || message.contains("HTTP 403")) {
            return "Firebase отклонил запрос · проверьте RTDB rules";
        }
        if (message.contains("Unable to resolve host") || message.contains("No route to host")) {
            return "Нет интернета · переподключаемся автоматически";
        }
        return "Firebase: " + (message.isEmpty() ? "ошибка подключения" : message);
    }

    private static final class IncomingFile {
        final String id;
        final File partial;
        final String name;
        final String mime;
        final String kind;
        final long expectedSize;
        final FileOutputStream output;
        int nextChunk;
        long receivedBytes;

        IncomingFile(String id, File partial, String name, String mime, String kind, long expectedSize,
                     FileOutputStream output) {
            this.id = id;
            this.partial = partial;
            this.name = new File(name).getName();
            this.mime = mime;
            this.kind = kind;
            this.expectedSize = expectedSize;
            this.output = output;
        }

        void closeQuietly() {
            try { output.close(); } catch (IOException ignored) { }
        }
    }

    private final class PeerSession {
        final String id;
        final String peerUid;
        final boolean offerer;
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean published = new AtomicBoolean(false);
        final AtomicBoolean remoteDescriptionStarted = new AtomicBoolean(false);
        final AtomicBoolean remoteDescriptionReady = new AtomicBoolean(false);
        final AtomicBoolean dispatching = new AtomicBoolean(false);
        final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
        final AtomicBoolean cleanupScheduled = new AtomicBoolean(false);
        final Set<String> remoteCandidateKeys = ConcurrentHashMap.newKeySet();
        final Set<DataChannel> observedChannels = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final List<IceCandidate> pendingRemoteCandidates = Collections.synchronizedList(new ArrayList<>());
        final List<JSONObject> pendingLocalCandidates = Collections.synchronizedList(new ArrayList<>());
        volatile PeerConnection peerConnection;
        volatile DataChannel dataChannel;
        volatile FirebaseRestClient.StreamHandle callStream;
        volatile IncomingFile incomingFile;

        PeerSession(String id, String peerUid, boolean offerer) {
            this.id = id;
            this.peerUid = peerUid;
            this.offerer = offerer;
        }

        boolean isOpen() {
            DataChannel channel = dataChannel;
            return !closed.get() && channel != null && channel.state() == DataChannel.State.OPEN;
        }

        boolean isIceConnected() {
            PeerConnection pc = peerConnection;
            if (closed.get() || pc == null) return false;
            PeerConnection.IceConnectionState state = pc.iceConnectionState();
            return state == PeerConnection.IceConnectionState.CONNECTED
                    || state == PeerConnection.IceConnectionState.COMPLETED;
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            FirebaseRestClient.StreamHandle stream = callStream;
            callStream = null;
            if (stream != null) stream.close();
            IncomingFile file = incomingFile;
            incomingFile = null;
            if (file != null) {
                file.closeQuietly();
                //noinspection ResultOfMethodCallIgnored
                file.partial.delete();
                messages.updateStatus(file.id, "failed");
                notifyMessages(peerUid);
            }
            DataChannel channel = dataChannel;
            dataChannel = null;
            if (channel != null) {
                try { channel.unregisterObserver(); } catch (Exception ignored) { }
                try { channel.close(); } catch (Exception ignored) { }
                try { channel.dispose(); } catch (Exception ignored) { }
            }
            PeerConnection pc = peerConnection;
            peerConnection = null;
            if (pc != null) {
                try { pc.close(); } catch (Exception ignored) { }
                try { pc.dispose(); } catch (Exception ignored) { }
            }
        }
    }

    private abstract static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }

    public interface Listener {
        default void onIdentity(String uid) { }
        default void onEngineStatus(String status) { }
        default void onPeerState(String peerUid, String state) { }
        default void onMessagesChanged(String peerUid) { }
        default void onIncomingInvite(String peerUid) { }
        default void onContactsChanged() { }
    }
}
