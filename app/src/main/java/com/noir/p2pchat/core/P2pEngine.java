package com.noir.p2pchat.core;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
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
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * P2P messaging engine. Firebase Realtime Database carries authenticated WebRTC signaling and public
 * Signal prekeys; all chat text, file metadata and media chunks are Signal-encrypted before DataChannel send.
 */
public final class P2pEngine {
    private static final String TAG = "NoirP2P";
    private static volatile boolean webRtcLibraryInitialized;
    private static final int MAX_FILE_BYTES = FileTransferProtocol.MAX_FILE_BYTES;
    private static final int FILE_CHUNK_BYTES = FileTransferProtocol.CHUNK_BYTES;
    private static final long MAX_BUFFERED_BYTES = 512 * 1024;
    private static final long TRANSFER_ACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);
    private static final int MAX_PARALLEL_FILE_TRANSFERS_PER_PEER = 2;
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
    private final ExecutorService transferExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService protocolExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Map<String, PeerSession> sessionsByPeer = new ConcurrentHashMap<>();
    private final Map<String, PeerSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, MediaCallSession> mediaCallsById = new ConcurrentHashMap<>();
    private final Map<String, MediaCallSession> mediaCallsByPeer = new ConcurrentHashMap<>();
    private final Map<String, Object> signalLocks = new ConcurrentHashMap<>();
    private final Set<String> signalReadyPeers = ConcurrentHashMap.newKeySet();
    private final Set<String> activeFileTransfers = ConcurrentHashMap.newKeySet();
    private final Map<String, TransferWaiter> transferWaiters = new ConcurrentHashMap<>();
    private final Set<String> pendingInboundTransferResumes = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> pendingInvites = new ConcurrentHashMap<>();
    private final Object pendingTextLock = new Object();
    private final ConcurrentLinkedQueue<QueuedText> pendingTexts = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingTextCount = new AtomicInteger();
    private final Set<String> pendingInviteDeclines = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingMediaCallAccepts = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingMediaCallDeclines = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> reconnectAttempts = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean manuallyStopped = new AtomicBoolean(false);
    private final AtomicBoolean inboxReadRunning = new AtomicBoolean(false);
    private final AtomicBoolean inboxReadRequested = new AtomicBoolean(false);
    private volatile PeerConnectionFactory dataFactory;
    private volatile PeerConnectionFactory factory;
    private volatile EglBase eglBase;
    private volatile EncryptedSignalProtocolStore signalStore;
    private volatile boolean signalProfilePublished;
    private volatile long signalProfilePublishedAt;
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
        for (MediaCallSession call : new ArrayList<>(mediaCallsById.values())) {
            if (!call.closed.get()) {
                mainHandler.post(() -> listener.onMediaCallState(call.id, call.peerUid, call.video, call.state));
            }
        }
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

    public String startMediaCall(String peerUid, boolean video) {
        if (!isValidUid(peerUid) || peerUid.equals(uid) || uid == null) {
            setStatus("Подождите, пока приложение подключится к Firebase");
            return null;
        }
        if (!messages.isContact(peerUid)) {
            setStatus("Добавьте собеседника в контакты перед звонком");
            return null;
        }
        if (!mediaCallsById.isEmpty()) {
            setStatus("Завершите текущий звонок, прежде чем начинать новый");
            return null;
        }
        MediaCallSession call = new MediaCallSession(UUID.randomUUID().toString().replace("-", ""),
                peerUid, video, true);
        mediaCallsById.put(call.id, call);
        mediaCallsByPeer.put(peerUid, call);
        notifyMediaCallState(call, "calling");
        ioExecutor.execute(() -> startOutgoingMediaCall(call));
        scheduler.schedule(() -> {
            if (!call.closed.get() && ("calling".equals(call.state) || "ringing".equals(call.state))) {
                finishMediaCall(call, "no_answer", true);
            }
        }, 60, TimeUnit.SECONDS);
        return call.id;
    }

    private void scheduleMediaConnectionTimeout(MediaCallSession call) {
        scheduler.schedule(() -> {
            if (!call.closed.get() && "connecting".equals(call.state)) {
                failMediaCall(call, "Не удалось установить соединение звонка за отведённое время");
            }
        }, 45, TimeUnit.SECONDS);
    }

    public void acceptMediaCall(String callId) {
        if (callId == null || !callId.matches("[A-Za-z0-9_-]{1,128}")) return;
        pendingMediaCallDeclines.remove(callId);
        MediaCallSession call = mediaCallsById.get(callId);
        if (call == null) {
            if (pendingMediaCallAccepts.add(callId)) {
                scheduler.schedule(() -> pendingMediaCallAccepts.remove(callId), 90, TimeUnit.SECONDS);
            }
            start();
            readInbox();
            return;
        }
        if (call.outgoing || call.closed.get() || !"ringing".equals(call.state)) return;
        pendingMediaCallAccepts.remove(callId);
        notifyMediaCallState(call, "connecting");
        scheduleMediaConnectionTimeout(call);
        MessageNotifications.cancelIncomingCall(appContext, call.id);
        ioExecutor.execute(() -> answerIncomingMediaCall(call));
    }

    public void declineMediaCall(String callId) {
        if (callId == null || !callId.matches("[A-Za-z0-9_-]{1,128}")) return;
        pendingMediaCallAccepts.remove(callId);
        MediaCallSession call = mediaCallsById.get(callId);
        if (call == null) {
            if (pendingMediaCallDeclines.add(callId)) {
                scheduler.schedule(() -> pendingMediaCallDeclines.remove(callId), 2, TimeUnit.MINUTES);
            }
            if (uid == null) start();
            else deleteMediaCallRecord(callId);
            return;
        }
        if (call.outgoing || call.closed.get()) return;
        finishMediaCall(call, "declined", true);
    }

    private void deleteMediaCallRecord(String callId) {
        if (uid == null) {
            pendingMediaCallDeclines.add(callId);
            start();
            return;
        }
        String localUid = uid;
        ioExecutor.execute(() -> {
            try { firebase.delete("inbox/" + localUid + "/" + callId); }
            catch (Exception e) { Log.w(TAG, "Could not remove declined call inbox entry", e); }
            try { firebase.delete("calls/" + callId); }
            catch (Exception e) { Log.w(TAG, "Could not reject call signaling record", e); }
        });
        MessageNotifications.cancelIncomingCall(appContext, callId);
    }

    public void endMediaCall(String callId) {
        MediaCallSession call = mediaCallsById.get(callId);
        if (call != null) finishMediaCall(call, "ended", true);
    }

    public String getMediaCallState(String callId) {
        MediaCallSession call = mediaCallsById.get(callId);
        return call == null ? "unknown" : call.state;
    }

    public String getMediaCallPeerUid(String callId) {
        MediaCallSession call = mediaCallsById.get(callId);
        return call == null ? null : call.peerUid;
    }

    public boolean isVideoCall(String callId) {
        MediaCallSession call = mediaCallsById.get(callId);
        return call != null && call.video;
    }

    public boolean isOutgoingMediaCall(String callId) {
        MediaCallSession call = mediaCallsById.get(callId);
        return call != null && call.outgoing;
    }

    public VideoTrack getLocalVideoTrack(String callId) {
        MediaCallSession call = mediaCallsById.get(callId);
        return call == null ? null : call.localVideoTrack;
    }

    public VideoTrack getRemoteVideoTrack(String callId) {
        MediaCallSession call = mediaCallsById.get(callId);
        return call == null ? null : call.remoteVideoTrack;
    }

    public EglBase.Context getEglBaseContext() {
        EglBase current = eglBase;
        return current == null ? null : current.getEglBaseContext();
    }

    public void setMediaCallMuted(String callId, boolean muted) {
        MediaCallSession call = mediaCallsById.get(callId);
        if (call != null) {
            call.muted = muted;
            if (call.localAudioTrack != null) call.localAudioTrack.setEnabled(!muted);
        }
    }

    public void setMediaCallVideoEnabled(String callId, boolean enabled) {
        MediaCallSession call = mediaCallsById.get(callId);
        if (call == null || !call.video) return;
        call.videoEnabled = enabled;
        if (call.localVideoTrack != null) call.localVideoTrack.setEnabled(enabled);
        CameraVideoCapturer capturer = call.cameraCapturer;
        if (capturer != null) {
            ioExecutor.execute(() -> {
                if (call.closed.get() || call.cameraCapturer != capturer) return;
                try {
                    if (enabled) capturer.startCapture(640, 480, 24);
                    else capturer.stopCapture();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.w(TAG, "Could not change local camera capture state", e);
                }
            });
        }
    }

    public void setMediaCallSpeakerEnabled(String callId, boolean enabled) {
        MediaCallSession call = mediaCallsById.get(callId);
        AudioManager audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (call == null || audioManager == null) return;
        call.speakerEnabled = enabled;
        call.speakerRouteSet = true;
        if (!call.audioRouteChanged) return;
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(enabled);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not change call audio route", e);
        }
    }

    public void switchMediaCallCamera(String callId) {
        MediaCallSession call = mediaCallsById.get(callId);
        CameraVideoCapturer capturer = call == null ? null : call.cameraCapturer;
        if (capturer == null) return;
        capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override public void onCameraSwitchDone(boolean isFrontCamera) {
                notifyMediaCallState(call, call.state);
            }
            @Override public void onCameraSwitchError(String errorDescription) {
                Log.w(TAG, "Camera switch failed: " + errorDescription);
            }
        });
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
                String authenticatedUid = firebase.currentUid();
                if (authenticatedUid == null) throw new IOException("Firebase не вернул идентификатор пользователя");
                synchronized (pendingTextLock) {
                    uid = authenticatedUid;
                }
                setStatus("Firebase авторизован · запускаем приложение…");
                publishBasicProfile();
                if (!started.get()) return;
                notifyIdentity(uid);
                setStatus("Сигналинг Firebase активен");
                startInboxStream();
                processPendingOutbox();
                drainPendingTexts();
                processPendingInviteDeclines();
                processPendingMediaCallActions();
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
            } catch (LinkageError e) {
                // Native crypto may fail with UnsatisfiedLinkError/Error rather than Exception.
                // Keep the app open and expose the actual failing component instead of crashing silently.
                Log.e(TAG, "Native Signal crypto component failed during startup", e);
                started.set(false);
                setStatus("Ошибка загрузки Signal · " + safeError(e));
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
        for (MediaCallSession call : mediaCallsById.values()) finishMediaCall(call, "ended", true);
        sessionsByPeer.clear();
        sessionsById.clear();
        signalReadyPeers.clear();
        reconnectAttempts.clear();
        setStatus("P2P-сервис остановлен");
    }

    private volatile FirebaseRestClient.StreamHandle inboxStream;

    private void initializeWebRtcLibrary() throws IOException {
        if (webRtcLibraryInitialized) return;
        synchronized (P2pEngine.class) {
            if (webRtcLibraryInitialized) return;
            try {
                PeerConnectionFactory.InitializationOptions options =
                        PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions();
                PeerConnectionFactory.initialize(options);
                webRtcLibraryInitialized = true;
            } catch (LinkageError e) {
                throw new IOException("Не загрузилась нативная библиотека WebRTC: " + safeError(e), e);
            }
        }
    }

    /** Data-channel chat needs no EGL context or hardware video codecs. */
    private void initializeDataWebRtc() throws IOException {
        if (dataFactory != null) return;
        synchronized (P2pEngine.class) {
            if (dataFactory != null) return;
            initializeWebRtcLibrary();
            try {
                PeerConnectionFactory initializedFactory = PeerConnectionFactory.builder()
                        .createPeerConnectionFactory();
                if (initializedFactory == null) throw new IOException("WebRTC не создал DataChannel factory");
                dataFactory = initializedFactory;
            } catch (LinkageError e) {
                throw new IOException("Не загрузился WebRTC DataChannel: " + safeError(e), e);
            }
        }
    }

    /** Package-private smoke-test hook for exercising the actual direct-chat factory on Android. */
    PeerConnectionFactory dataFactoryForSmokeTest() throws IOException {
        initializeDataWebRtc();
        return dataFactory;
    }

    /** Package-private smoke-test hook for exercising the production direct-chat ICE configuration. */
    List<PeerConnection.IceServer> iceServersForSmokeTest() {
        return iceServers();
    }

    /** Audio/video codecs and EGL are loaded only when the user starts or answers a call. */
    private void initializeMediaWebRtc() throws IOException {
        if (factory != null) return;
        synchronized (P2pEngine.class) {
            if (factory != null) return;
            initializeWebRtcLibrary();
            try {
                if (eglBase == null) eglBase = EglBase.create();
                EglBase.Context eglContext = eglBase.getEglBaseContext();
                PeerConnectionFactory initializedFactory = PeerConnectionFactory.builder()
                        .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglContext, true, true))
                        .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglContext))
                        .createPeerConnectionFactory();
                if (initializedFactory == null) throw new IOException("WebRTC не создал медиа-фабрику");
                factory = initializedFactory;
            } catch (LinkageError e) {
                EglBase failedEglBase = eglBase;
                eglBase = null;
                if (failedEglBase != null) {
                    try { failedEglBase.release(); }
                    catch (RuntimeException | LinkageError cleanupError) {
                        Log.w(TAG, "Could not release failed WebRTC EGL context", cleanupError);
                    }
                }
                throw new IOException("Не загрузился медиа-компонент WebRTC: " + safeError(e), e);
            }
        }
    }

    private void publishBasicProfile() throws IOException, JSONException {
        JSONObject profile = new JSONObject();
        profile.put("app", "NoirP2P");
        profile.put("protocol", 1);
        profile.put("updatedAt", System.currentTimeMillis());
        // Preserve any existing Signal bundle; native key generation is deferred until P2P is used.
        firebase.patch("profiles/" + uid, profile);
    }

    private synchronized EncryptedSignalProtocolStore ensureSignalReady() throws IOException, JSONException {
        if (uid == null) throw new IOException("Firebase identity is not initialized");
        if (signalStore == null) signalStore = new EncryptedSignalProtocolStore(appContext);
        long now = System.currentTimeMillis();
        long publishedAt = signalProfilePublishedAt;
        long keyRefreshWindow = TimeUnit.DAYS.toMillis(6);
        if (signalProfilePublished && now >= publishedAt && now - publishedAt < keyRefreshWindow) return signalStore;
        try {
            JSONObject profile = new JSONObject();
            profile.put("app", "NoirP2P");
            profile.put("protocol", 1);
            profile.put("updatedAt", now);
            profile.put("signalVersion", 1);
            profile.put("signal", signalStore.publicBundle());
            firebase.patch("profiles/" + uid, profile);
            signalProfilePublishedAt = now;
            signalProfilePublished = true;
            return signalStore;
        } catch (LinkageError e) {
            throw new IOException("Не загрузился нативный Signal-компонент: " + safeError(e), e);
        }
    }

    private PreKeyBundle parsePreKeyBundle(JSONObject json) throws Exception {
        if (json == null || json.optInt("version", -1) != 1) {
            throw new IOException("Собеседник не опубликовал Signal prekey bundle v1");
        }
        int deviceId = json.optInt("deviceId", -1);
        int registrationId = json.optInt("registrationId", -1);
        int signedId = json.optInt("signedPreKeyId", -1);
        int kyberId = json.optInt("kyberPreKeyId", -1);
        if (deviceId < 1 || deviceId > 127 || registrationId < 1 || registrationId > 16380
                || signedId < 1 || kyberId < 1) {
            throw new IOException("Некорректные идентификаторы Signal prekey bundle");
        }
        byte[] identityBytes = EncryptedSignalProtocolStore.decodePublicKey(json.optString("identityKey", null));
        byte[] signedBytes = EncryptedSignalProtocolStore.decodePublicKey(json.optString("signedPreKey", null));
        byte[] signedSignature = EncryptedSignalProtocolStore.decodePublicKey(json.optString("signedPreKeySignature", null));
        byte[] kyberBytes = EncryptedSignalProtocolStore.decodePublicKey(json.optString("kyberPreKey", null));
        byte[] kyberSignature = EncryptedSignalProtocolStore.decodePublicKey(json.optString("kyberPreKeySignature", null));
        if (signedSignature.length == 0 || kyberSignature.length == 0) {
            throw new IOException("Пустая подпись в Signal prekey bundle");
        }
        IdentityKey identityKey = new IdentityKey(identityBytes);
        ECPublicKey signedPublic = new ECPublicKey(signedBytes);
        KEMPublicKey kyberPublic = new KEMPublicKey(kyberBytes);
        return new PreKeyBundle(registrationId, deviceId, PreKeyBundle.NULL_PRE_KEY_ID, null,
                signedId, signedPublic, signedSignature, identityKey,
                kyberId, kyberPublic, kyberSignature);
    }

    private void ensureSignalSession(String peerUid) throws Exception {
        String localUid = uid;
        EncryptedSignalProtocolStore store = ensureSignalReady();
        if (localUid == null) throw new IOException("Signal identity is not initialized");
        Object lock = signalLocks.computeIfAbsent(peerUid, ignored -> new Object());
        synchronized (lock) {
            SignalProtocolAddress localAddress = new SignalProtocolAddress(localUid, store.getLocalDeviceId());
            SignalProtocolAddress remoteAddress = new SignalProtocolAddress(peerUid, store.getLocalDeviceId());
            if (store.containsSession(remoteAddress)) return;
            JSONObject profile = firebase.get("profiles/" + peerUid);
            JSONObject bundleJson = profile == null ? null : profile.optJSONObject("signal");
            if (bundleJson == null) throw new IOException("Собеседнику нужно обновить Noir для Signal E2E");
            PreKeyBundle bundle = parsePreKeyBundle(bundleJson);
            new SessionBuilder(store, remoteAddress, localAddress).process(bundle);
        }
    }

    private void startSignalHandshake(PeerSession session) {
        if (!session.signalHandshakeStarted.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            try {
                setStatus("P2P-канал открыт · готовим Signal E2E…");
                EncryptedSignalProtocolStore store = ensureSignalReady();
                if (store.hasSession(session.peerUid)) {
                    sendSignalInit(session);
                } else if (uid.compareTo(session.peerUid) < 0) {
                    ensureSignalSession(session.peerUid);
                    sendSignalInit(session);
                } else {
                    notifyPeerState(session.peerUid, "Устанавливаем Signal E2E-сессию…");
                }
            } catch (UntrustedIdentityException untrusted) {
                Log.w(TAG, "Signal peer identity changed; refusing silent trust replacement", untrusted);
                notifyPeerState(session.peerUid, "Signal identity изменился · сверка ключа обязательна");
            } catch (Exception e) {
                Log.w(TAG, "Signal session setup failed", e);
                notifyPeerState(session.peerUid, "Signal E2E не готов · проверяем профиль и соединение");
                session.signalHandshakeStarted.set(false);
                scheduler.schedule(() -> {
                    if (session.isOpen() && sessionsByPeer.get(session.peerUid) == session) {
                        startSignalHandshake(session);
                    }
                }, 8, TimeUnit.SECONDS);
            }
        });
    }

    private void sendSignalInit(PeerSession session) throws JSONException, IOException {
        JSONObject hello = new JSONObject();
        hello.put("type", "signal_init");
        hello.put("protocol", 1);
        if (!sendJson(session, hello)) throw new IOException("Не удалось отправить Signal handshake");
    }

    public String getPeerFingerprint(String peerUid) {
        EncryptedSignalProtocolStore store = signalStore;
        return store == null ? null : store.fingerprint(peerUid);
    }

    public boolean isPeerIdentityVerified(String peerUid) {
        EncryptedSignalProtocolStore store = signalStore;
        return store != null && store.isVerified(peerUid);
    }

    public void markPeerIdentityVerified(String peerUid) {
        EncryptedSignalProtocolStore store = signalStore;
        if (store != null && isValidUid(peerUid)) store.markVerified(peerUid);
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
                if ("media".equals(call.optString("mode", ""))) {
                    if (messages.isContact(caller)) receiveIncomingMediaCall(sessionId, caller, call);
                    else rejectUnknownMediaCall(sessionId, caller);
                    return;
                }
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

    private void receiveIncomingMediaCall(String callId, String callerUid, JSONObject callJson) {
        MediaCallSession existing = mediaCallsById.get(callId);
        if (existing != null) return;
        if (!mediaCallsById.isEmpty()) {
            rejectUnknownMediaCall(callId, callerUid);
            return;
        }
        long createdAt = callJson.optLong("createdAt", 0L);
        if (createdAt <= 0L || System.currentTimeMillis() - createdAt > TimeUnit.SECONDS.toMillis(60)
                || createdAt > System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2)) {
            rejectUnknownMediaCall(callId, callerUid);
            return;
        }
        JSONObject offer = callJson.optJSONObject("offer");
        String encryptedOffer = offer == null ? "" : offer.optString("sdp", "");
        boolean video = "video".equals(callJson.optString("media", "audio"));
        if (offer == null || !"offer".equals(offer.optString("type", ""))
                || encryptedOffer.isEmpty() || encryptedOffer.length() > 24_000) {
            rejectUnknownMediaCall(callId, callerUid);
            return;
        }
        MediaCallSession incoming = new MediaCallSession(callId, callerUid, video, false);
        incoming.encryptedOffer = encryptedOffer;
        incoming.createdAt = createdAt;
        mediaCallsById.put(callId, incoming);
        mediaCallsByPeer.put(callerUid, incoming);
        notifyMediaCallState(incoming, "ringing");
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onIncomingMediaCall(callId, callerUid, video);
        });
        MessageNotifications.showIncomingCall(appContext, callerUid, callId, video);
        watchMediaSignaling(incoming);
        if (pendingMediaCallDeclines.remove(callId)) {
            declineMediaCall(callId);
            return;
        }
        if (pendingMediaCallAccepts.contains(callId)) {
            acceptMediaCall(callId);
            return;
        }
        long remainingRingMs = Math.max(1_000L,
                TimeUnit.SECONDS.toMillis(60) - Math.max(0L, System.currentTimeMillis() - createdAt));
        scheduler.schedule(() -> {
            if (!incoming.closed.get() && "ringing".equals(incoming.state)) finishMediaCall(incoming, "missed", true);
        }, remainingRingMs, TimeUnit.MILLISECONDS);
    }

    private void rejectUnknownMediaCall(String callId, String callerUid) {
        if (uid == null) return;
        String localUid = uid;
        ioExecutor.execute(() -> {
            try { firebase.delete("inbox/" + localUid + "/" + callId); }
            catch (Exception e) { Log.w(TAG, "Could not remove an untrusted media-call invite", e); }
            try { firebase.delete("calls/" + callId); }
            catch (Exception e) { Log.w(TAG, "Could not reject an untrusted media call", e); }
        });
    }

    private void startOutgoingMediaCall(MediaCallSession call) {
        try {
            ensureSignalSession(call.peerUid);
            if (call.closed.get()) return;
            initializeMediaWebRtc();
            createMediaPeerConnection(call);
            addLocalCallTracks(call);
            call.peerConnection.createOffer(new SdpObserverAdapter() {
                @Override public void onCreateSuccess(SessionDescription offer) {
                    if (call.closed.get()) return;
                    PeerConnection pc = call.peerConnection;
                    if (pc == null) return;
                    pc.setLocalDescription(new SdpObserverAdapter() {
                        @Override public void onSetSuccess() {
                            if (!call.closed.get()) publishMediaOffer(call, offer);
                        }
                        @Override public void onSetFailure(String error) {
                            if (!call.closed.get()) failMediaCall(call, "Не удалось установить SDP звонка: " + error);
                        }
                    }, offer);
                }
                @Override public void onCreateFailure(String error) {
                    if (!call.closed.get()) failMediaCall(call, "Не удалось создать SDP звонка: " + error);
                }
            }, mediaCallConstraints(call));
        } catch (Exception e) {
            Log.e(TAG, "Could not start a WebRTC media call", e);
            failMediaCall(call, "Не удалось начать звонок: " + safeError(e));
        }
    }

    private void answerIncomingMediaCall(MediaCallSession call) {
        try {
            ensureSignalReady();
            JSONObject offerJson = new JSONObject(new String(decryptCallSignal(call.peerUid, call.encryptedOffer),
                    StandardCharsets.UTF_8));
            SessionDescription offer = sessionDescriptionFromJson(offerJson);
            if (offer.type != SessionDescription.Type.OFFER) throw new IOException("Некорректное Signal SDP-предложение");
            call.signalReady = true;
            initializeMediaWebRtc();
            createMediaPeerConnection(call);
            addLocalCallTracks(call);
            call.remoteDescriptionStarted.set(true);
            call.peerConnection.setRemoteDescription(new SdpObserverAdapter() {
                @Override public void onSetSuccess() {
                    if (call.closed.get()) return;
                    markMediaRemoteDescriptionReady(call);
                    PeerConnection pc = call.peerConnection;
                    if (pc == null) return;
                    pc.createAnswer(new SdpObserverAdapter() {
                        @Override public void onCreateSuccess(SessionDescription answer) {
                            if (call.closed.get()) return;
                            PeerConnection current = call.peerConnection;
                            if (current == null) return;
                            current.setLocalDescription(new SdpObserverAdapter() {
                                @Override public void onSetSuccess() {
                                    if (!call.closed.get()) publishMediaAnswer(call, answer);
                                }
                                @Override public void onSetFailure(String error) {
                                    if (!call.closed.get()) failMediaCall(call, "Не удалось установить ответ звонка: " + error);
                                }
                            }, answer);
                        }
                        @Override public void onCreateFailure(String error) {
                            if (!call.closed.get()) failMediaCall(call, "Не удалось создать ответ звонка: " + error);
                        }
                    }, mediaCallConstraints(call));
                }
                @Override public void onSetFailure(String error) {
                    if (!call.closed.get()) failMediaCall(call, "Не удалось принять Signal SDP: " + error);
                }
            }, offer);
            refreshMediaCall(call);
        } catch (Exception e) {
            Log.e(TAG, "Could not accept a WebRTC media call", e);
            failMediaCall(call, "Не удалось принять звонок: " + safeError(e));
        }
    }

    private void createMediaPeerConnection(MediaCallSession call) throws IOException {
        initializeMediaWebRtc();
        PeerConnectionFactory currentFactory = factory;
        if (currentFactory == null) throw new IOException("WebRTC медиа-фабрика не инициализирована");
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServers());
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        call.peerConnection = currentFactory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (call.closed.get()) return;
                if (state == PeerConnection.IceConnectionState.CONNECTED
                        || state == PeerConnection.IceConnectionState.COMPLETED) {
                    notifyMediaCallState(call, "connected");
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    notifyMediaCallState(call, "reconnecting");
                    scheduler.schedule(() -> {
                        PeerConnection current = call.peerConnection;
                        if (!call.closed.get() && current != null
                                && current.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) {
                            failMediaCall(call, "Связь звонка потеряна");
                        }
                    }, 15, TimeUnit.SECONDS);
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    failMediaCall(call, "Не удалось установить медиа-соединение");
                }
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
            @Override public void onIceCandidate(IceCandidate candidate) { publishMediaCandidate(call, candidate); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(MediaStream stream) {
                for (VideoTrack track : stream.videoTracks) setRemoteVideoTrack(call, track);
            }
            @Override public void onRemoveStream(MediaStream stream) {
                for (VideoTrack track : stream.videoTracks) {
                    track.setEnabled(false);
                    if (call.remoteVideoTrack == track) call.remoteVideoTrack = null;
                    notifyCallTracksChanged(call);
                }
            }
            @Override public void onDataChannel(DataChannel channel) { channel.close(); channel.dispose(); }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                setRemoteMediaTrack(call, receiver.track());
            }
            @Override public void onTrack(RtpTransceiver transceiver) {
                setRemoteMediaTrack(call, transceiver.getReceiver().track());
            }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                if (state == PeerConnection.PeerConnectionState.FAILED) failMediaCall(call, "WebRTC-соединение звонка завершилось с ошибкой");
            }
            @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState state) { }
        });
        if (call.peerConnection == null) throw new IOException("WebRTC не создал медиа-соединение");
    }

    private void activateMediaAudioRoute(MediaCallSession call) {
        AudioManager audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        synchronized (call) {
            if (call.audioRouteChanged) return;
            call.previousAudioMode = audioManager.getMode();
            call.previousSpeakerphone = audioManager.isSpeakerphoneOn();
            call.audioRouteChanged = true;
        }
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (!call.speakerRouteSet) call.speakerEnabled = call.video;
            audioManager.setSpeakerphoneOn(call.speakerEnabled);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not activate call audio route", e);
        }
    }

    private void restoreMediaAudioRoute(MediaCallSession call) {
        if (!call.audioRouteChanged) return;
        AudioManager audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        synchronized (call) {
            if (!call.audioRouteChanged) return;
            call.audioRouteChanged = false;
        }
        try {
            audioManager.setSpeakerphoneOn(call.previousSpeakerphone);
            audioManager.setMode(call.previousAudioMode);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not restore audio route after call", e);
        }
    }

    private void addLocalCallTracks(MediaCallSession call) throws IOException {
        if (appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("Нет разрешения на микрофон");
        }
        activateMediaAudioRoute(call);
        PeerConnection pc = call.peerConnection;
        if (pc == null) throw new IOException("Медиа-соединение не готово");
        call.localAudioSource = factory.createAudioSource(new MediaConstraints());
        call.localAudioTrack = factory.createAudioTrack("noir-audio-" + call.id, call.localAudioSource);
        call.localAudioTrack.setEnabled(!call.muted);
        pc.addTrack(call.localAudioTrack, Collections.singletonList("noir-call-" + call.id));
        if (call.video) {
            if (appContext.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                throw new IOException("Нет разрешения на камеру");
            }
            CameraVideoCapturer capturer = createCameraCapturer();
            if (capturer == null) throw new IOException("Камера не найдена");
            EglBase currentEgl = eglBase;
            if (currentEgl == null) throw new IOException("WebRTC EGL не инициализирован");
            call.cameraCapturer = capturer;
            call.surfaceTextureHelper = SurfaceTextureHelper.create("NoirCallCapture-" + call.id,
                    currentEgl.getEglBaseContext());
            call.localVideoSource = factory.createVideoSource(capturer.isScreencast());
            capturer.initialize(call.surfaceTextureHelper, appContext, call.localVideoSource.getCapturerObserver());
            if (call.videoEnabled) capturer.startCapture(640, 480, 24);
            call.localVideoTrack = factory.createVideoTrack("noir-video-" + call.id, call.localVideoSource);
            call.localVideoTrack.setEnabled(call.videoEnabled);
            pc.addTrack(call.localVideoTrack, Collections.singletonList("noir-call-" + call.id));
        }
        notifyCallTracksChanged(call);
    }

    private CameraVideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(appContext)
                ? new Camera2Enumerator(appContext) : new Camera1Enumerator(true);
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(name, null);
                if (capturer != null) return capturer;
            }
        }
        for (String name : enumerator.getDeviceNames()) {
            CameraVideoCapturer capturer = enumerator.createCapturer(name, null);
            if (capturer != null) return capturer;
        }
        return null;
    }

    private static MediaConstraints mediaCallConstraints(MediaCallSession call) {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", call.video ? "true" : "false"));
        return constraints;
    }

    private void setRemoteMediaTrack(MediaCallSession call, MediaStreamTrack track) {
        if (track instanceof VideoTrack) setRemoteVideoTrack(call, (VideoTrack) track);
    }

    private void setRemoteVideoTrack(MediaCallSession call, VideoTrack track) {
        if (call.closed.get() || track == null) return;
        call.remoteVideoTrack = track;
        track.setEnabled(true);
        notifyCallTracksChanged(call);
    }

    private void publishMediaOffer(MediaCallSession call, SessionDescription offer) {
        if (call.closed.get()) return;
        ioExecutor.execute(() -> {
            if (call.closed.get()) return;
            try {
                JSONObject record = new JSONObject()
                        .put("caller", uid)
                        .put("callee", call.peerUid)
                        .put("createdAt", call.createdAt)
                        .put("mode", "media")
                        .put("media", call.video ? "video" : "audio");
                byte[] clearSignal = sessionDescriptionJson(offer).toString().getBytes(StandardCharsets.UTF_8);
                encryptAndPublishSignal(call.peerUid, clearSignal, encrypted -> {
                    record.put("offer", new JSONObject().put("type", "offer").put("sdp", encrypted));
                    firebase.put("calls/" + call.id, record);
                    firebase.put("inbox/" + call.peerUid + "/" + call.id,
                            new JSONObject().put("from", uid).put("createdAt", call.createdAt)
                                    .put("callType", call.video ? "video" : "audio"));
                });
                call.signalReady = true;
                call.published.set(true);
                watchMediaSignaling(call);
                notifyMediaCallState(call, "ringing");
                flushMediaLocalCandidates(call);
                refreshMediaCall(call);
            } catch (Exception e) {
                Log.e(TAG, "Could not publish Signal-encrypted media call offer", e);
                failMediaCall(call, "Не удалось отправить приглашение на звонок: " + safeError(e));
            }
        });
    }

    private void publishMediaAnswer(MediaCallSession call, SessionDescription answer) {
        if (call.closed.get()) return;
        ioExecutor.execute(() -> {
            if (call.closed.get()) return;
            try {
                byte[] clearSignal = sessionDescriptionJson(answer).toString().getBytes(StandardCharsets.UTF_8);
                encryptAndPublishSignal(call.peerUid, clearSignal, encrypted ->
                        firebase.patch("calls/" + call.id,
                                new JSONObject().put("answer", new JSONObject().put("type", "answer").put("sdp", encrypted))));
                call.published.set(true);
                flushMediaLocalCandidates(call);
                try { firebase.delete("inbox/" + uid + "/" + call.id); }
                catch (Exception e) { Log.w(TAG, "Could not clear the answered media-call invite", e); }
                refreshMediaCall(call);
            } catch (Exception e) {
                Log.e(TAG, "Could not publish Signal-encrypted media call answer", e);
                failMediaCall(call, "Не удалось принять звонок: " + safeError(e));
            }
        });
    }

    private void watchMediaSignaling(MediaCallSession call) {
        if (call.callStream != null || call.closed.get()) return;
        call.callStream = firebase.stream("calls/" + call.id, new FirebaseRestClient.StreamListener() {
            @Override public void onEvent(String event, JSONObject payload) {
                refreshMediaCall(call);
            }
            @Override public void onStreamError(Exception error) {
                Log.w(TAG, "Media-call signaling stream reconnecting", error);
            }
        });
    }

    private void refreshMediaCall(MediaCallSession call) {
        if (call.closed.get()) return;
        ioExecutor.execute(() -> {
            try {
                JSONObject record = firebase.get("calls/" + call.id);
                if (record == null) {
                    finishMediaCall(call, call.outgoing ? "declined" : "ended", false);
                    return;
                }
                handleMediaCallSnapshot(call, record);
            } catch (Exception e) {
                Log.w(TAG, "Could not refresh media-call signaling", e);
            }
        });
    }

    private void handleMediaCallSnapshot(MediaCallSession call, JSONObject record) throws Exception {
        if (call.closed.get()) return;
        if (call.outgoing) {
            JSONObject answer = record.optJSONObject("answer");
            if (answer != null && call.remoteDescriptionStarted.compareAndSet(false, true)) {
                String encrypted = answer.optString("sdp", "");
                JSONObject clear = new JSONObject(new String(decryptCallSignal(call.peerUid, encrypted), StandardCharsets.UTF_8));
                SessionDescription remoteAnswer = sessionDescriptionFromJson(clear);
                if (remoteAnswer.type != SessionDescription.Type.ANSWER) throw new IOException("Invalid encrypted media answer");
                notifyMediaCallState(call, "connecting");
                scheduleMediaConnectionTimeout(call);
                PeerConnection pc = call.peerConnection;
                if (pc == null) return;
                pc.setRemoteDescription(new SdpObserverAdapter() {
                    @Override public void onSetSuccess() { markMediaRemoteDescriptionReady(call); }
                    @Override public void onSetFailure(String error) { failMediaCall(call, "Не удалось принять ответ звонка: " + error); }
                }, remoteAnswer);
            }
        }
        if (!call.signalReady) return;
        JSONObject allCandidates = record.optJSONObject("candidates");
        JSONObject remoteCandidates = allCandidates == null ? null : allCandidates.optJSONObject(call.peerUid);
        if (remoteCandidates == null) return;
        ArrayList<String> keys = new ArrayList<>();
        Iterator<String> iterator = remoteCandidates.keys();
        while (iterator.hasNext() && keys.size() < 512) keys.add(iterator.next());
        Collections.sort(keys);
        for (String key : keys) {
            if (call.remoteCandidateKeys.contains(key)) continue;
            JSONObject encryptedCandidate = remoteCandidates.optJSONObject(key);
            if (encryptedCandidate == null) continue;
            String wire = encryptedCandidate.optString("candidate", "");
            if (wire.isEmpty()) continue;
            JSONObject clearCandidate = new JSONObject(new String(decryptCallSignal(call.peerUid, wire), StandardCharsets.UTF_8));
            String candidateText = clearCandidate.optString("candidate", "");
            if (candidateText.isEmpty() || candidateText.length() > 2048) continue;
            IceCandidate candidate = new IceCandidate(
                    clearCandidate.isNull("sdpMid") ? null : clearCandidate.optString("sdpMid", null),
                    clearCandidate.optInt("sdpMLineIndex", 0), candidateText);
            call.remoteCandidateKeys.add(key);
            if (call.remoteDescriptionReady.get()) call.peerConnection.addIceCandidate(candidate);
            else call.pendingRemoteCandidates.add(candidate);
        }
    }

    private void markMediaRemoteDescriptionReady(MediaCallSession call) {
        call.remoteDescriptionReady.set(true);
        synchronized (call.pendingRemoteCandidates) {
            PeerConnection pc = call.peerConnection;
            if (pc != null) {
                for (IceCandidate candidate : call.pendingRemoteCandidates) pc.addIceCandidate(candidate);
            }
            call.pendingRemoteCandidates.clear();
        }
        refreshMediaCall(call);
    }

    private void publishMediaCandidate(MediaCallSession call, IceCandidate candidate) {
        if (call.closed.get()) return;
        synchronized (call.pendingLocalCandidates) {
            if (!call.published.get()) {
                call.pendingLocalCandidates.add(candidate);
                return;
            }
        }
        ioExecutor.execute(() -> writeMediaCandidate(call, candidate));
    }

    private void flushMediaLocalCandidates(MediaCallSession call) {
        ArrayList<IceCandidate> pending;
        synchronized (call.pendingLocalCandidates) {
            pending = new ArrayList<>(call.pendingLocalCandidates);
            call.pendingLocalCandidates.clear();
        }
        for (IceCandidate candidate : pending) writeMediaCandidate(call, candidate);
    }

    private void writeMediaCandidate(MediaCallSession call, IceCandidate candidate) {
        if (call.closed.get() || uid == null) return;
        try {
            JSONObject clear = new JSONObject()
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp);
            encryptAndPublishSignal(call.peerUid, clear.toString().getBytes(StandardCharsets.UTF_8), encrypted ->
                    firebase.post("calls/" + call.id + "/candidates/" + uid,
                            new JSONObject().put("sdpMLineIndex", 0).put("candidate", encrypted)));
        } catch (Exception e) {
            Log.w(TAG, "Could not publish an encrypted media ICE candidate", e);
        }
    }

    private void failMediaCall(MediaCallSession call, String error) {
        Log.e(TAG, error);
        finishMediaCall(call, "failed", true);
    }

    private void finishMediaCall(MediaCallSession call, String terminalState, boolean deleteRemote) {
        if (!mediaCallsById.remove(call.id, call)) return;
        mediaCallsByPeer.remove(call.peerUid, call);
        call.state = terminalState;
        call.close();
        restoreMediaAudioRoute(call);
        MessageNotifications.cancelIncomingCall(appContext, call.id);
        notifyMediaCallState(call, terminalState);
        if (deleteRemote && uid != null) {
            String calleeUid = call.outgoing ? call.peerUid : uid;
            ioExecutor.execute(() -> {
                try { firebase.delete("inbox/" + calleeUid + "/" + call.id); }
                catch (Exception e) { Log.w(TAG, "Could not clean up the media-call inbox", e); }
                try { firebase.delete("calls/" + call.id); }
                catch (Exception e) { Log.w(TAG, "Could not clean up media-call signaling", e); }
            });
        }
    }

    private void notifyMediaCallState(MediaCallSession call, String state) {
        call.state = state;
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onMediaCallState(call.id, call.peerUid, call.video, state);
        });
    }

    private void notifyCallTracksChanged(MediaCallSession call) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onCallTracksChanged(call.id);
        });
    }

    private static String safeError(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public void addContact(String peerUid) {
        String clean = peerUid == null ? "" : peerUid.trim();
        if (!isValidUid(clean) || clean.equals(uid)) {
            setStatus("Введите корректный ID собеседника");
            return;
        }
        messages.addContact(clean);
        pendingInvites.remove(clean);
        MessageNotifications.cancelIncoming(appContext, clean);
        notifyContactsChanged();
        readInbox();
        requestConnection(clean);
    }

    public void declineInvite(String peerUid) {
        if (!isValidUid(peerUid) || peerUid.equals(uid)) return;
        if (uid == null) {
            pendingInviteDeclines.add(peerUid);
            start();
            return;
        }
        String currentUid = uid;
        pendingInvites.remove(peerUid);
        MessageNotifications.cancelIncoming(appContext, peerUid);
        notifyInvite(peerUid);
        ioExecutor.execute(() -> {
            try {
                JSONObject inbox = firebase.get("inbox/" + currentUid);
                if (inbox == null) return;
                Iterator<String> keys = inbox.keys();
                while (keys.hasNext()) {
                    String sessionId = keys.next();
                    JSONObject invite = inbox.optJSONObject(sessionId);
                    if (invite == null || !peerUid.equals(invite.optString("from", ""))) continue;
                    firebase.delete("inbox/" + currentUid + "/" + sessionId);
                    try { firebase.delete("calls/" + sessionId); }
                    catch (IOException ignored) { }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not decline incoming invite", e);
            }
        });
    }

    private void processPendingInviteDeclines() {
        for (String peerUid : new ArrayList<>(pendingInviteDeclines)) {
            if (pendingInviteDeclines.remove(peerUid)) declineInvite(peerUid);
        }
    }

    private void processPendingMediaCallActions() {
        for (String callId : new ArrayList<>(pendingMediaCallDeclines)) {
            MediaCallSession call = mediaCallsById.get(callId);
            if (call != null) declineMediaCall(callId);
            else deleteMediaCallRecord(callId);
        }
        for (String callId : new ArrayList<>(pendingMediaCallAccepts)) {
            MediaCallSession call = mediaCallsById.get(callId);
            if (call != null && pendingMediaCallAccepts.remove(callId)) acceptMediaCall(callId);
        }
    }

    public void requestConnection(String peerUid) {
        if (!isValidUid(peerUid) || peerUid.equals(uid)) return;
        if (!started.get()) start();
        // Native Signal/WebRTC setup and Firebase work must never run on the UI thread.
        ioExecutor.execute(() -> {
            if (!started.get() || uid == null) return;
            PeerSession current = sessionsByPeer.get(peerUid);
            if (current != null && !current.closed.get()) {
                if (current.isOpen()) dispatchPending(peerUid);
                return;
            }
            // Deterministic offerer avoids simultaneous-offer glare: the lexicographically smaller UID calls.
            if (uid.compareTo(peerUid) < 0) beginOutgoing(peerUid);
            else notifyPeerState(peerUid, "Ожидаем приглашение от собеседника");
        });
    }

    private synchronized void beginOutgoing(String peerUid) {
        if (!started.get() || uid == null || uid.compareTo(peerUid) >= 0) return;
        PeerSession existing = sessionsByPeer.get(peerUid);
        if (existing != null && !existing.closed.get()) return;
        signalReadyPeers.remove(peerUid);
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        PeerSession session = new PeerSession(sessionId, peerUid, true);
        PeerSession previous = sessionsByPeer.put(peerUid, session);
        if (previous != null) previous.close();
        sessionsById.put(sessionId, session);
        notifyPeerState(peerUid, "Создаём P2P-канал…");
        setStatus("Открываем прямой канал передачи данных…");
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
        signalReadyPeers.remove(peerUid);
        PeerSession session = new PeerSession(sessionId, peerUid, false);
        PeerSession previous = sessionsByPeer.put(peerUid, session);
        if (previous != null) previous.close();
        sessionsById.put(sessionId, session);
        pendingInvites.remove(peerUid);
        notifyPeerState(peerUid, "Принимаем P2P-подключение…");
        setStatus("Принимаем прямой канал передачи данных…");
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
        initializeDataWebRtc();
        PeerConnectionFactory currentFactory = dataFactory;
        if (currentFactory == null) throw new IOException("WebRTC DataChannel не инициализирован");
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
                    notifyPeerState(session.peerUid, "Канал WebRTC открыт · устанавливаем Signal E2E");
                    setStatus("P2P-канал активен · устанавливаем Signal E2E");
                    scheduleSignalingCleanup(session);
                    startSignalHandshake(session);
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
        if (channel.state() == DataChannel.State.OPEN) startSignalHandshake(session);
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
            signalReadyPeers.remove(session.peerUid);
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
        setStatus(error);
        notifyPeerState(session.peerUid, error);
        closeSession(session);
    }

    public void sendText(String peerUid, String text) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty() || !isValidUid(peerUid)) return;
        if (body.length() > 12_000) {
            setStatus("Сообщение слишком длинное (лимит 12 000 символов)");
            return;
        }
        boolean queued = false;
        synchronized (pendingTextLock) {
            if (uid == null) {
                if (pendingTextCount.incrementAndGet() > 16) {
                    pendingTextCount.decrementAndGet();
                    setStatus("Очередь ответов из уведомлений заполнена");
                    return;
                }
                pendingTexts.add(new QueuedText(peerUid, body));
                queued = true;
            }
        }
        if (queued) {
            start();
            return;
        }
        storeAndDispatchText(peerUid, body);
    }

    private void drainPendingTexts() {
        ArrayList<QueuedText> queued = new ArrayList<>();
        synchronized (pendingTextLock) {
            QueuedText item;
            while ((item = pendingTexts.poll()) != null) {
                pendingTextCount.decrementAndGet();
                queued.add(item);
            }
        }
        for (QueuedText item : queued) storeAndDispatchText(item.peerUid, item.body);
    }

    private void storeAndDispatchText(String peerUid, String body) {
        String senderUid = uid;
        if (senderUid == null) return;
        long now = System.currentTimeMillis();
        Message message = new Message(UUID.randomUUID().toString(), peerUid, senderUid, "text", body,
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

    public void pauseTransfer(String messageId) {
        Message message = messages.getMessage(messageId);
        if (message == null || message.transferSize <= 0L) return;
        messages.updateStatus(messageId, "paused");
        if (message.outgoing) {
            TransferWaiter waiter = transferWaiters.get(messageId);
            if (waiter != null) waiter.pause();
        } else {
            PeerSession session = sessionsByPeer.get(message.peerUid);
            if (session != null) pauseIncomingFile(session, messageId);
        }
        PeerSession session = sessionsByPeer.get(message.peerUid);
        if (session != null && session.isOpen()) {
            ioExecutor.execute(() -> sendJsonWaiting(session,
                    transferControl("file_pause", messageId, message.transferOffset, false)));
        }
        notifyMessages(message.peerUid);
    }

    public void resumeTransfer(String peerUid, String messageId) {
        if (!isValidUid(peerUid)) return;
        Message message = messages.getMessage(messageId);
        if (message == null || !peerUid.equals(message.peerUid) || message.transferSize <= 0L) return;
        if (message.outgoing) {
            File file = message.filePath == null ? null : new File(message.filePath);
            if (file == null || !file.isFile() || file.length() != message.transferSize) {
                messages.updateStatus(messageId, "failed");
                notifyMessages(peerUid);
                setStatus("Исходный файл больше недоступен · выберите его заново");
                return;
            }
            messages.updateStatus(messageId, "pending");
            notifyMessages(peerUid);
            requestConnection(peerUid);
            dispatchPending(peerUid);
            return;
        }
        pendingInboundTransferResumes.add(messageId);
        messages.updateStatus(messageId, "paused");
        notifyMessages(peerUid);
        if (signalReadyPeers.contains(peerUid)) {
            dispatchPendingTransferResumeRequests(peerUid);
        } else {
            requestConnection(peerUid);
        }
    }

    private void pauseIncomingFile(PeerSession session, String messageId) {
        IncomingFile transfer = session.incomingFiles.remove(messageId);
        if (transfer == null) return;
        transfer.syncAndClose();
        messages.updateTransferOffset(messageId, transfer.receivedBytes);
        messages.updateStatus(messageId, "paused");
    }

    private void dispatchPendingTransferResumeRequests(String peerUid) {
        PeerSession session = sessionsByPeer.get(peerUid);
        if (session == null || !session.isOpen() || !signalReadyPeers.contains(peerUid)) return;
        for (String id : new ArrayList<>(pendingInboundTransferResumes)) {
            Message message = messages.getMessage(id);
            if (message == null || message.outgoing || !peerUid.equals(message.peerUid)) continue;
            JSONObject request = transferControl("file_resume_request", id, message.transferOffset, false);
            ioExecutor.execute(() -> {
                if (sessionsByPeer.get(peerUid) == session && sendJsonWaiting(session, request)) {
                    pendingInboundTransferResumes.remove(id);
                }
            });
        }
    }

    private static JSONObject transferControl(String type, String id, long offset, boolean complete) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", type);
            json.put("id", id);
            json.put("offset", offset);
            if (complete) json.put("complete", true);
        } catch (JSONException ignored) { }
        return json;
    }

    private void dispatchPending(String peerUid) {
        PeerSession session = sessionsByPeer.get(peerUid);
        if (session == null || !session.isOpen() || !signalReadyPeers.contains(peerUid)
                || !session.dispatching.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            try {
                for (Message message : messages.getPending(peerUid, 100)) {
                    if (!started.get() || sessionsByPeer.get(peerUid) != session || !session.isOpen()) break;
                    if ("text".equals(message.kind)) {
                        if (!sendTextFrame(session, message)) break;
                        messages.markSent(message.id);
                        notifyMessages(peerUid);
                    } else if (activeFileTransfers.add(message.id)) {
                        transferExecutor.execute(() -> runFileTransfer(session, message));
                    }
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not read/decrypt a queued local message; refusing to send substitute text", e);
                setStatus("Локальное содержимое недоступно · сообщение не отправлено");
            } finally {
                session.dispatching.set(false);
                schedulePendingDispatchIfNeeded(session);
            }
        });
    }

    private void runFileTransfer(PeerSession session, Message message) {
        boolean acquired = false;
        try {
            session.transferPermits.acquire();
            acquired = true;
            if (!started.get() || sessionsByPeer.get(message.peerUid) != session || !session.isOpen()
                    || !isMessagePending(message.peerUid, message.id)) return;
            if (sendFileFrames(session, message)) {
                messages.markSent(message.id);
                notifyMessages(message.peerUid);
            } else if (session.isOpen() && isMessagePending(message.peerUid, message.id)) {
                session.transferRetryAfterMs = System.currentTimeMillis() + 2_000L;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not send queued file", e);
            setStatus("Не удалось продолжить передачу файла");
        } finally {
            if (acquired) session.transferPermits.release();
            activeFileTransfers.remove(message.id);
            notifyMessages(message.peerUid);
            schedulePendingDispatchIfNeeded(session);
        }
    }

    private boolean isMessagePending(String peerUid, String messageId) {
        for (Message pending : messages.getPending(peerUid, 100)) {
            if (messageId.equals(pending.id)) return true;
        }
        return false;
    }

    private void schedulePendingDispatchIfNeeded(PeerSession session) {
        try {
            if (!session.isOpen() || !signalReadyPeers.contains(session.peerUid)) return;
            boolean dispatchable = false;
            for (Message message : messages.getPending(session.peerUid, 100)) {
                if ("text".equals(message.kind) || !activeFileTransfers.contains(message.id)) {
                    dispatchable = true;
                    break;
                }
            }
            if (!dispatchable) return;
            long delay = Math.max(500L, session.transferRetryAfterMs - System.currentTimeMillis());
            scheduler.schedule(() -> dispatchPending(session.peerUid), delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not inspect the pending message queue", e);
        }
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
        if (file == null || !file.isFile() || file.length() <= 0L || file.length() > MAX_FILE_BYTES) {
            messages.updateStatus(message.id, "failed");
            notifyMessages(message.peerUid);
            return false;
        }
        long fileSize = file.length();
        long requestedOffset;
        try {
            requestedOffset = FileTransferProtocol.normalizeResumeOffset(message.transferOffset, fileSize);
        } catch (IllegalArgumentException e) {
            messages.updateStatus(message.id, "failed");
            return false;
        }
        TransferWaiter waiter = new TransferWaiter();
        if (transferWaiters.putIfAbsent(message.id, waiter) != null) return false;
        try {
            JSONObject start = new JSONObject();
            start.put("type", "file_start");
            start.put("id", message.id);
            start.put("name", message.body);
            start.put("mime", message.mime == null ? "application/octet-stream" : message.mime);
            start.put("kind", message.kind);
            start.put("size", fileSize);
            start.put("offset", requestedOffset);
            if (!sendJsonWaiting(session, start) || !waiter.awaitResume(session, TRANSFER_ACK_TIMEOUT_MS)) return false;

            long offset = waiter.resumeOffset;
            if (!FileTransferProtocol.isValidResumeOffset(offset, fileSize)) {
                Log.w(TAG, "Peer provided an invalid file resume offset");
                return false;
            }
            messages.updateTransferOffset(message.id, offset);
            notifyMessages(message.peerUid);
            try (FileInputStream input = new FileInputStream(file)) {
                input.getChannel().position(offset);
                byte[] payload = new byte[FILE_CHUNK_BYTES];
                long sentOffset = offset;
                int index = (int) (offset / FILE_CHUNK_BYTES);
                int chunksSinceAck = 0;
                while (sentOffset < fileSize) {
                    if (!session.isOpen() || !started.get() || waiter.isPaused()) return false;
                    int requestedBytes = (int) Math.min(payload.length, fileSize - sentOffset);
                    int read = input.read(payload, 0, requestedBytes);
                    if (read < 0) throw new IOException("Исходный файл неожиданно закончился");
                    if (read == 0) continue;
                    byte[] frame = FileTransferProtocol.encodeChunk(message.id, index++, payload, 0, read);
                    if (!sendBinaryWaiting(session, frame)) return false;
                    sentOffset += read;
                    chunksSinceAck++;
                    if (chunksSinceAck >= FileTransferProtocol.ACK_WINDOW_CHUNKS || sentOffset == fileSize) {
                        if (!waiter.awaitAcknowledgement(session, sentOffset, TRANSFER_ACK_TIMEOUT_MS)) return false;
                        chunksSinceAck = 0;
                    }
                }
            }
            if (waiter.isPaused() || !session.isOpen()) return false;
            JSONObject end = new JSONObject().put("type", "file_end").put("id", message.id);
            if (!sendJsonWaiting(session, end)) return false;
            return waiter.awaitComplete(session, fileSize, TRANSFER_ACK_TIMEOUT_MS);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Attachment transfer failed; keeping the last acknowledged offset", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            transferWaiters.remove(message.id, waiter);
        }
    }

    private boolean sendJsonWaiting(PeerSession session, JSONObject json) {
        return sendEncryptedWaiting(session, json.toString().getBytes(StandardCharsets.UTF_8),
                TimeUnit.MINUTES.toMillis(2));
    }

    private boolean sendBinaryWaiting(PeerSession session, byte[] bytes) {
        return sendEncryptedWaiting(session, bytes, TimeUnit.MINUTES.toMillis(2));
    }

    private boolean sendJson(PeerSession session, JSONObject json) {
        return sendEncryptedWaiting(session, json.toString().getBytes(StandardCharsets.UTF_8),
                TimeUnit.SECONDS.toMillis(10));
    }

    private boolean sendEncryptedWaiting(PeerSession session, byte[] cleartext, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (session.isOpen() && started.get() && System.currentTimeMillis() < deadline) {
            Object lock = signalLocks.computeIfAbsent(session.peerUid, ignored -> new Object());
            synchronized (lock) {
                if (session.isOpen() && session.dataChannel.bufferedAmount() < MAX_BUFFERED_BYTES) {
                    EncryptedSignalProtocolStore store = signalStore;
                    String localUid = uid;
                    if (store == null || localUid == null) return false;
                    SignalProtocolAddress localAddress = new SignalProtocolAddress(localUid, store.getLocalDeviceId());
                    SignalProtocolAddress remoteAddress = new SignalProtocolAddress(session.peerUid, store.getLocalDeviceId());
                    org.signal.libsignal.protocol.state.SessionRecord previous = store.loadSession(remoteAddress);
                    byte[] previousState = previous == null ? null : previous.serialize();
                    try {
                        SessionCipher cipher = new SessionCipher(store, localAddress, remoteAddress);
                        CiphertextMessage ciphertext = cipher.encrypt(cleartext);
                        byte[] frame = SignalEnvelope.encode(ciphertext.getType(), ciphertext.serialize());
                        boolean accepted = session.dataChannel.send(
                                new DataChannel.Buffer(ByteBuffer.wrap(frame), true));
                        if (accepted) return true;
                        restoreSignalSession(store, remoteAddress, previousState);
                        return false;
                    } catch (Exception e) {
                        restoreSignalSession(store, remoteAddress, previousState);
                        Log.w(TAG, "Could not encrypt/send a Signal frame", e);
                        return false;
                    }
                }
            }
            sleepQuietly(30);
        }
        return false;
    }

    private static void restoreSignalSession(EncryptedSignalProtocolStore store,
                                             SignalProtocolAddress remoteAddress,
                                             byte[] previousState) {
        try {
            if (previousState == null) {
                store.deleteSession(remoteAddress);
            } else {
                store.storeSession(remoteAddress,
                        new org.signal.libsignal.protocol.state.SessionRecord(previousState));
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not roll back an unsent Signal ratchet step", e);
        }
    }

    private void processChannelData(PeerSession session, boolean binary, byte[] bytes) {
        if (session.closed.get()) return;
        if (!binary) {
            Log.w(TAG, "Rejected a non-binary DataChannel frame; plaintext is not accepted");
            return;
        }
        SignalEnvelope.Envelope envelope = SignalEnvelope.decode(bytes);
        if (envelope == null) {
            Log.w(TAG, "Rejected malformed or non-Signal DataChannel frame");
            return;
        }
        final byte[] cleartext;
        try {
            cleartext = decryptSignalFrame(session, envelope);
        } catch (Exception e) {
            Log.w(TAG, "Signal E2E message authentication/decryption failed", e);
            notifyPeerState(session.peerUid, "Signal E2E ошибка · ключи не совпадают или сообщение повреждено");
            return;
        }
        if (cleartext.length > 0 && cleartext[0] == (byte) 'F') {
            receiveFileChunk(session, cleartext);
            return;
        }
        processClearJson(session, cleartext);
    }

    private byte[] decryptSignalFrame(PeerSession session, SignalEnvelope.Envelope envelope) throws Exception {
        return decryptSignalFrame(session.peerUid, envelope);
    }

    private byte[] decryptCallSignal(String peerUid, String wire) throws Exception {
        if (wire == null || wire.isEmpty() || wire.length() > 32_000) throw new IOException("Invalid encrypted call signal");
        final byte[] frame;
        try { frame = Base64.getDecoder().decode(wire); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid call signal encoding", e); }
        SignalEnvelope.Envelope envelope = SignalEnvelope.decode(frame);
        if (envelope == null) throw new IOException("Malformed encrypted call signal");
        return decryptSignalFrame(peerUid, envelope);
    }

    private byte[] decryptSignalFrame(String peerUid, SignalEnvelope.Envelope envelope) throws Exception {
        EncryptedSignalProtocolStore store = signalStore;
        String localUid = uid;
        if (store == null || localUid == null) throw new IOException("Signal identity is not initialized");
        Object lock = signalLocks.computeIfAbsent(peerUid, ignored -> new Object());
        synchronized (lock) {
            SignalProtocolAddress localAddress = new SignalProtocolAddress(localUid, store.getLocalDeviceId());
            SignalProtocolAddress remoteAddress = new SignalProtocolAddress(peerUid, store.getLocalDeviceId());
            SessionCipher cipher = new SessionCipher(store, localAddress, remoteAddress);
            if (envelope.type == CiphertextMessage.PREKEY_TYPE) {
                return cipher.decrypt(new PreKeySignalMessage(envelope.ciphertext));
            }
            if (envelope.type == CiphertextMessage.WHISPER_TYPE) {
                return cipher.decrypt(new SignalMessage(envelope.ciphertext));
            }
            throw new IOException("Unsupported Signal message type");
        }
    }

    private void encryptAndPublishSignal(String peerUid, byte[] cleartext, EncryptedSignalPublisher publisher)
            throws Exception {
        EncryptedSignalProtocolStore store = signalStore;
        String localUid = uid;
        if (store == null || localUid == null) throw new IOException("Signal identity is not initialized");
        Object lock = signalLocks.computeIfAbsent(peerUid, ignored -> new Object());
        synchronized (lock) {
            SignalProtocolAddress localAddress = new SignalProtocolAddress(localUid, store.getLocalDeviceId());
            SignalProtocolAddress remoteAddress = new SignalProtocolAddress(peerUid, store.getLocalDeviceId());
            org.signal.libsignal.protocol.state.SessionRecord previous = store.loadSession(remoteAddress);
            byte[] previousState = previous == null ? null : previous.serialize();
            try {
                CiphertextMessage ciphertext = new SessionCipher(store, localAddress, remoteAddress).encrypt(cleartext);
                byte[] frame = SignalEnvelope.encode(ciphertext.getType(), ciphertext.serialize());
                publisher.publish(Base64.getEncoder().encodeToString(frame));
            } catch (Exception e) {
                restoreSignalSession(store, remoteAddress, previousState);
                throw e;
            }
        }
    }

    private void processClearJson(PeerSession session, byte[] cleartext) {
        try {
            JSONObject json = new JSONObject(new String(cleartext, StandardCharsets.UTF_8));
            String type = json.optString("type", "");
            if ("signal_init".equals(type) && json.optInt("protocol", -1) == 1) {
                JSONObject ready = new JSONObject().put("type", "signal_ready").put("protocol", 1);
                if (!sendJson(session, ready)) Log.w(TAG, "Could not send Signal handshake acknowledgment");
            } else if ("signal_ready".equals(type) && json.optInt("protocol", -1) == 1) {
                signalReadyPeers.add(session.peerUid);
                EncryptedSignalProtocolStore store = signalStore;
                String verification = store != null && store.isVerified(session.peerUid)
                        ? "Signal E2E активно · ключ подтверждён"
                        : "Signal E2E активно · сравните отпечаток ключа";
                notifyPeerState(session.peerUid, verification);
                setStatus("Соединение защищено Signal Protocol");
                dispatchPendingTransferResumeRequests(session.peerUid);
                dispatchPending(session.peerUid);
            } else if ("text".equals(type)) {
                String id = json.optString("id", "");
                String body = json.optString("body", "");
                if (!FileTransferProtocol.isValidMessageId(id) || body.length() > 12_000) return;
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
            } else if ("file_resume".equals(type)) {
                String id = json.optString("id", "");
                TransferWaiter waiter = transferWaiters.get(id);
                if (FileTransferProtocol.isValidMessageId(id) && waiter != null) {
                    waiter.acceptResume(json.optLong("offset", -1L));
                }
            } else if ("file_ack".equals(type)) {
                handleFileAck(session, json);
            } else if ("text_ack".equals(type)) {
                String id = json.optString("id", "");
                Message message = messages.getMessage(id);
                if (FileTransferProtocol.isValidMessageId(id) && message != null && message.outgoing
                        && session.peerUid.equals(message.peerUid)) {
                    messages.updateStatus(id, "delivered");
                    notifyMessages(session.peerUid);
                }
            } else if ("file_pause".equals(type)) {
                handleRemoteTransferPause(session, json.optString("id", ""));
            } else if ("file_resume_request".equals(type)) {
                String id = json.optString("id", "");
                Message message = messages.getMessage(id);
                if (FileTransferProtocol.isValidMessageId(id) && message != null && message.outgoing
                        && session.peerUid.equals(message.peerUid)) resumeTransfer(session.peerUid, id);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Ignored invalid decrypted Signal application message", e);
        }
    }

    private void handleFileAck(PeerSession session, JSONObject json) {
        String id = json.optString("id", "");
        if (!FileTransferProtocol.isValidMessageId(id)) return;
        Message message = messages.getMessage(id);
        if (message == null || !message.outgoing || !session.peerUid.equals(message.peerUid)) return;
        long offset = json.optLong("offset", -1L);
        boolean complete = json.optBoolean("complete", false);
        TransferWaiter waiter = transferWaiters.get(id);
        if (FileTransferProtocol.isValidResumeOffset(offset, message.transferSize)) {
            messages.updateTransferOffset(id, offset);
            if (waiter != null) waiter.acceptAcknowledgement(offset, complete);
        }
        if (complete || offset < 0L) {
            messages.updateTransferOffset(id, message.transferSize);
            messages.updateStatus(id, "delivered");
        }
        notifyMessages(session.peerUid);
    }

    private void handleRemoteTransferPause(PeerSession session, String id) {
        if (!FileTransferProtocol.isValidMessageId(id)) return;
        Message message = messages.getMessage(id);
        if (message == null || !session.peerUid.equals(message.peerUid)) return;
        if (message.outgoing) {
            TransferWaiter waiter = transferWaiters.get(id);
            if (waiter != null) waiter.pause();
        } else {
            pauseIncomingFile(session, id);
        }
        messages.updateStatus(id, "paused");
        notifyMessages(session.peerUid);
    }

    private void beginIncomingFile(PeerSession session, JSONObject json) {
        String id = json.optString("id", "");
        String name = json.optString("name", "Вложение");
        String mime = json.optString("mime", "application/octet-stream");
        String kind = json.optString("kind", kindFromMime(mime));
        long size = json.optLong("size", -1L);
        long requestedOffset = json.optLong("offset", 0L);
        if (!FileTransferProtocol.isValidMessageId(id) || size <= 0L || size > MAX_FILE_BYTES) return;
        if (name.length() > 120) name = name.substring(0, 120);
        name = new File(name).getName().replaceAll("[\\r\\n]", "_");
        if (mime.length() > 200) mime = "application/octet-stream";
        if (!kind.matches("image|video|video_note|audio|voice|file")) kind = kindFromMime(mime);
        try {
            Message existing = messages.getMessage(id);
            if (existing != null && (existing.outgoing || !session.peerUid.equals(existing.peerUid)
                    || existing.transferSize != size)) return;
            if (existing != null && "received".equals(existing.status)
                    && existing.filePath != null && new File(existing.filePath).isFile()
                    && new File(existing.filePath).length() == size) {
                sendJson(session, transferControl("file_resume", id, size, false));
                return;
            }
            File directory = new File(appContext.getFilesDir(), "media");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Не удалось создать каталог медиа");
            File partial = new File(directory, "incoming_" + id + ".part");
            IncomingFile old = session.incomingFiles.remove(id);
            if (old != null) old.syncAndClose();
            RandomAccessFile output = new RandomAccessFile(partial, "rw");
            long safeOffset = FileTransferProtocol.normalizeResumeOffset(
                    Math.min(requestedOffset, output.length()), size);
            output.setLength(safeOffset);
            output.seek(safeOffset);
            IncomingFile incoming = new IncomingFile(id, partial, name, mime, kind, size, output, safeOffset);
            session.incomingFiles.put(id, incoming);
            if (existing == null) {
                messages.insertMessage(new Message(id, session.peerUid, session.peerUid, kind, name,
                        mime, partial.getAbsolutePath(), System.currentTimeMillis(), false, "receiving", size,
                        safeOffset));
            }
            messages.updateFilePath(id, partial, "receiving");
            messages.updateTransferOffset(id, safeOffset);
            sendJson(session, transferControl("file_resume", id, safeOffset, false));
            notifyMessages(session.peerUid);
        } catch (Exception e) {
            Log.w(TAG, "Could not prepare resumable incoming attachment", e);
            sendJson(session, transferControl("file_resume", id, 0L, false));
        }
    }

    private void receiveFileChunk(PeerSession session, byte[] frame) {
        FileTransferProtocol.Chunk chunk = FileTransferProtocol.decodeChunk(frame);
        if (chunk == null) return;
        IncomingFile file = session.incomingFiles.get(chunk.id);
        if (file == null) return;
        if (chunk.index < file.nextChunk) return;
        if (chunk.index != file.nextChunk) {
            sendJson(session, transferControl("file_ack", file.id, file.receivedBytes, false));
            return;
        }
        int payloadLength = chunk.payload.length;
        if (file.receivedBytes + payloadLength > file.expectedSize || file.receivedBytes + payloadLength > MAX_FILE_BYTES
                || (file.receivedBytes + payloadLength < file.expectedSize && payloadLength != FILE_CHUNK_BYTES)) {
            session.incomingFiles.remove(file.id, file);
            file.syncAndClose();
            messages.updateTransferOffset(file.id, file.receivedBytes);
            messages.updateStatus(file.id, "failed");
            notifyMessages(session.peerUid);
            return;
        }
        try {
            file.output.write(chunk.payload);
            file.receivedBytes += payloadLength;
            file.nextChunk++;
            if (file.nextChunk % FileTransferProtocol.ACK_WINDOW_CHUNKS == 0
                    || file.receivedBytes == file.expectedSize) {
                file.sync();
                messages.updateTransferOffset(file.id, file.receivedBytes);
                sendJsonWaiting(session, transferControl("file_ack", file.id, file.receivedBytes, false));
                notifyMessages(session.peerUid);
            }
        } catch (IOException e) {
            session.incomingFiles.remove(file.id, file);
            file.syncAndClose();
            messages.updateTransferOffset(file.id, file.receivedBytes);
            messages.updateStatus(file.id, "paused");
            notifyMessages(session.peerUid);
        }
    }

    private void finishIncomingFile(PeerSession session, String id) {
        if (!FileTransferProtocol.isValidMessageId(id)) return;
        IncomingFile incoming = session.incomingFiles.remove(id);
        if (incoming == null) {
            Message existing = messages.getMessage(id);
            if (existing != null && !existing.outgoing && session.peerUid.equals(existing.peerUid)
                    && "received".equals(existing.status)) {
                sendJson(session, transferControl("file_ack", id, existing.transferSize, true));
            }
            return;
        }
        try {
            incoming.syncAndClose();
            if (incoming.receivedBytes != incoming.expectedSize) {
                messages.updateTransferOffset(id, incoming.receivedBytes);
                messages.updateStatus(id, "paused");
                sendJson(session, transferControl("file_ack", id, incoming.receivedBytes, false));
            } else {
                String extension = extensionForMime(incoming.mime);
                File target = new File(incoming.partial.getParentFile(), "received_" + id + extension);
                if (target.exists()) //noinspection ResultOfMethodCallIgnored
                    target.delete();
                if (!incoming.partial.renameTo(target)) throw new IOException("Не удалось завершить файл");
                messages.updateFilePath(id, target, "received");
                messages.updateTransferOffset(id, incoming.expectedSize);
                sendJson(session, transferControl("file_ack", id, incoming.expectedSize, true));
                if (appContext instanceof AppKernel && !((AppKernel) appContext).shouldSuppressNotification(session.peerUid)) {
                    MessageNotifications.showMessage(appContext, session.peerUid, "Получен файл: " + incoming.name);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not finish received attachment", e);
            messages.updateTransferOffset(id, incoming.receivedBytes);
            messages.updateStatus(id, "paused");
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

    private interface EncryptedSignalPublisher {
        void publish(String wire) throws Exception;
    }

    private static final class QueuedText {
        final String peerUid;
        final String body;

        QueuedText(String peerUid, String body) {
            this.peerUid = peerUid;
            this.body = body;
        }
    }

    private static final class MediaCallSession {
        final String id;
        final String peerUid;
        final boolean video;
        final boolean outgoing;
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean published = new AtomicBoolean(false);
        final AtomicBoolean remoteDescriptionStarted = new AtomicBoolean(false);
        final AtomicBoolean answerStarted = new AtomicBoolean(false);
        final AtomicBoolean remoteDescriptionReady = new AtomicBoolean(false);
        final Set<String> remoteCandidateKeys = ConcurrentHashMap.newKeySet();
        final List<IceCandidate> pendingLocalCandidates = Collections.synchronizedList(new ArrayList<>());
        final List<IceCandidate> pendingRemoteCandidates = Collections.synchronizedList(new ArrayList<>());
        volatile String state = "new";
        volatile String encryptedOffer;
        volatile long createdAt = System.currentTimeMillis();
        volatile boolean signalReady;
        volatile PeerConnection peerConnection;
        volatile FirebaseRestClient.StreamHandle callStream;
        volatile AudioSource localAudioSource;
        volatile AudioTrack localAudioTrack;
        volatile VideoSource localVideoSource;
        volatile VideoTrack localVideoTrack;
        volatile VideoTrack remoteVideoTrack;
        volatile CameraVideoCapturer cameraCapturer;
        volatile SurfaceTextureHelper surfaceTextureHelper;
        volatile int previousAudioMode = AudioManager.MODE_NORMAL;
        volatile boolean previousSpeakerphone;
        volatile boolean audioRouteChanged;
        volatile boolean speakerEnabled;
        volatile boolean speakerRouteSet;
        volatile boolean muted;
        volatile boolean videoEnabled = true;

        MediaCallSession(String id, String peerUid, boolean video, boolean outgoing) {
            this.id = id;
            this.peerUid = peerUid;
            this.video = video;
            this.outgoing = outgoing;
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            FirebaseRestClient.StreamHandle stream = callStream;
            callStream = null;
            if (stream != null) stream.close();
            CameraVideoCapturer capturer = cameraCapturer;
            cameraCapturer = null;
            if (capturer != null) {
                try { capturer.stopCapture(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception ignored) { }
                try { capturer.dispose(); } catch (Exception ignored) { }
            }
            PeerConnection pc = peerConnection;
            peerConnection = null;
            if (pc != null) {
                try { pc.close(); } catch (Exception ignored) { }
                try { pc.dispose(); } catch (Exception ignored) { }
            }
            VideoTrack remote = remoteVideoTrack;
            remoteVideoTrack = null;
            if (remote != null) try { remote.setEnabled(false); } catch (Exception ignored) { }
            VideoTrack localVideo = localVideoTrack;
            localVideoTrack = null;
            if (localVideo != null) {
                try { localVideo.setEnabled(false); } catch (Exception ignored) { }
                try { localVideo.dispose(); } catch (Exception ignored) { }
            }
            AudioTrack audio = localAudioTrack;
            localAudioTrack = null;
            if (audio != null) {
                try { audio.setEnabled(false); } catch (Exception ignored) { }
                try { audio.dispose(); } catch (Exception ignored) { }
            }
            VideoSource videoSource = localVideoSource;
            localVideoSource = null;
            if (videoSource != null) try { videoSource.dispose(); } catch (Exception ignored) { }
            AudioSource audioSource = localAudioSource;
            localAudioSource = null;
            if (audioSource != null) try { audioSource.dispose(); } catch (Exception ignored) { }
            SurfaceTextureHelper helper = surfaceTextureHelper;
            surfaceTextureHelper = null;
            if (helper != null) try { helper.dispose(); } catch (Exception ignored) { }
        }
    }

    private static final class IncomingFile {
        final String id;
        final File partial;
        final String name;
        final String mime;
        final String kind;
        final long expectedSize;
        final RandomAccessFile output;
        volatile int nextChunk;
        volatile long receivedBytes;
        private boolean closed;

        IncomingFile(String id, File partial, String name, String mime, String kind, long expectedSize,
                     RandomAccessFile output, long resumeOffset) {
            this.id = id;
            this.partial = partial;
            this.name = new File(name).getName();
            this.mime = mime;
            this.kind = kind;
            this.expectedSize = expectedSize;
            this.output = output;
            this.receivedBytes = resumeOffset;
            this.nextChunk = (int) (resumeOffset / FILE_CHUNK_BYTES);
        }

        synchronized void writeChunk(byte[] bytes) throws IOException {
            if (closed) throw new IOException("Transfer file is closed");
            output.write(bytes);
            receivedBytes += bytes.length;
            nextChunk++;
        }

        synchronized void sync() throws IOException {
            if (closed) throw new IOException("Transfer file is closed");
            output.getFD().sync();
        }

        synchronized void syncAndClose() {
            if (closed) return;
            try { output.getFD().sync(); } catch (IOException ignored) { }
            try { output.close(); } catch (IOException ignored) { }
            closed = true;
        }
    }

    private static final class TransferWaiter {
        private long resumeOffset = -1L;
        private long acknowledgedOffset = -1L;
        private boolean resumeReceived;
        private boolean complete;
        private boolean paused;

        synchronized void acceptResume(long offset) {
            if (offset < 0L) return;
            resumeOffset = offset;
            resumeReceived = true;
            notifyAll();
        }

        synchronized void acceptAcknowledgement(long offset, boolean isComplete) {
            if (offset >= 0L) acknowledgedOffset = Math.max(acknowledgedOffset, offset);
            complete |= isComplete;
            notifyAll();
        }

        synchronized void pause() {
            paused = true;
            notifyAll();
        }

        synchronized boolean isPaused() {
            return paused;
        }

        synchronized boolean awaitResume(PeerSession session, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!resumeReceived && !paused && session.isOpen()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) return false;
                wait(Math.min(remaining, 250L));
            }
            return resumeReceived && !paused && session.isOpen();
        }

        synchronized boolean awaitAcknowledgement(PeerSession session, long targetOffset, long timeoutMs)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (acknowledgedOffset < targetOffset && !paused && session.isOpen()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) return false;
                wait(Math.min(remaining, 250L));
            }
            return acknowledgedOffset >= targetOffset && !paused && session.isOpen();
        }

        synchronized boolean awaitComplete(PeerSession session, long fileSize, long timeoutMs)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while ((!complete || acknowledgedOffset < fileSize) && !paused && session.isOpen()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) return false;
                wait(Math.min(remaining, 250L));
            }
            return complete && acknowledgedOffset >= fileSize && !paused && session.isOpen();
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
        final AtomicBoolean signalHandshakeStarted = new AtomicBoolean(false);
        final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
        final AtomicBoolean cleanupScheduled = new AtomicBoolean(false);
        final Set<String> remoteCandidateKeys = ConcurrentHashMap.newKeySet();
        final Set<DataChannel> observedChannels = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final List<IceCandidate> pendingRemoteCandidates = Collections.synchronizedList(new ArrayList<>());
        final List<JSONObject> pendingLocalCandidates = Collections.synchronizedList(new ArrayList<>());
        volatile PeerConnection peerConnection;
        volatile DataChannel dataChannel;
        volatile FirebaseRestClient.StreamHandle callStream;
        final Map<String, IncomingFile> incomingFiles = new ConcurrentHashMap<>();
        final Semaphore transferPermits = new Semaphore(MAX_PARALLEL_FILE_TRANSFERS_PER_PEER);
        volatile long transferRetryAfterMs;

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
            for (IncomingFile file : new ArrayList<>(incomingFiles.values())) {
                if (!incomingFiles.remove(file.id, file)) continue;
                file.syncAndClose();
                messages.updateTransferOffset(file.id, file.receivedBytes);
                messages.updateStatus(file.id, "paused");
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
        default void onIncomingMediaCall(String callId, String peerUid, boolean video) { }
        default void onMediaCallState(String callId, String peerUid, boolean video, String state) { }
        default void onCallTracksChanged(String callId) { }
        default void onContactsChanged() { }
    }
}
