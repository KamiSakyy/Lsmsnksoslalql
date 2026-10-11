package com.tsuyu.messenger.call;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
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
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P2P-звонки без сервера: WebRTC (M144) + сигналинг через Realtime Database.
 *
 * <p>Обмен SDP и ICE идёт по уже разрешённому узлу {@code chats/{chatId}/calls/{callId}} —
 * никакого стороннего signalling-сервера. Медиа шифруется самим WebRTC (DTLS-SRTP),
 * то есть голос и видео идут от устройства к устройству, а релеи используются только
 * как «туннель» там, где прямой путь невозможен.
 */
public final class CallEngine {

    public interface Listener {
        /** state: ringing | connecting | active | declined | ended | failed */
        void onCallState(String state, String detail);
    }

    private static final String FIELD_STATE = "state";
    private static final String FIELD_OFFER = "offer";
    private static final String FIELD_ANSWER = "answer";

    private static CallEngine instance;

    private final Handler main = new Handler(Looper.getMainLooper());

    private PeerConnectionFactory factory;
    private EglBase eglBase;
    private PeerConnection peerConnection;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private CameraVideoCapturer capturer;
    private SurfaceViewRenderer localRenderer;
    private SurfaceViewRenderer remoteRenderer;

    private DatabaseReference callRef;
    private DatabaseReference iceRef;
    private ValueEventListener answerListener;
    private ValueEventListener offerListener;
    private ValueEventListener stateListener;
    private com.google.firebase.database.ChildEventListener iceListener;

    private Context context;
    private final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private String chatId;
    private String callId;
    private String myUid;
    private String peerUid;
    private boolean videoCall;
    private boolean incoming;
    private boolean answered;
    private boolean finished;
    private boolean appliedRemoteDescription;
    private boolean iceRestarted;
    private boolean muted;
    private boolean speaker;
    private boolean pendingActive;
    private long startedAt;
    private Runnable ringTimeout;

    private CallEngine() { }

    public static synchronized CallEngine get() {
        if (instance == null) instance = new CallEngine();
        return instance;
    }

    public static synchronized boolean busy() {
        return instance != null && instance.peerConnection != null && !instance.finished;
    }

    public int relayCount() {
        return IceServers.count();
    }

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

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    /** Готовит фабрику один раз за процесс: тяжёлые нативные объекты переиспользуются. */
    private void ensureFactory(Context context) {
        if (factory != null) return;
        this.context = context.getApplicationContext();
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(this.context)
                .setEnableInternalTracer(false)
                .createInitializationOptions());
        eglBase = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setOptions(new PeerConnectionFactory.Options())
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    public void attachRenderers(SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        localRenderer = local;
        remoteRenderer = remote;
        if (eglBase == null) return;
        if (localRenderer != null) {
            localRenderer.init(eglBase.getEglBaseContext(), null);
            localRenderer.setEnableHardwareScaler(true);
            localRenderer.setMirror(true);
        }
        if (remoteRenderer != null) {
            remoteRenderer.init(eglBase.getEglBaseContext(), null);
            remoteRenderer.setEnableHardwareScaler(true);
        }
        if (videoTrack != null && localRenderer != null) videoTrack.addSink(localRenderer);
    }

    public void startOutgoing(Context context, String chatId, String myUid, String peerUid, boolean video) {
        reset();
        ensureFactory(context);
        this.chatId = chatId;
        this.myUid = myUid;
        this.peerUid = peerUid;
        this.videoCall = video;
        this.incoming = false;
        this.finished = false;
        callRef = FirebaseDatabase.getInstance().getReference("chats").child(chatId).child("calls").push();
        callId = callRef.getKey();
        Map<String, Object> call = new HashMap<>();
        call.put("from", myUid);
        call.put("to", peerUid);
        call.put("video", video);
        call.put(FIELD_STATE, "ringing");
        call.put("createdAt", ServerValue.TIMESTAMP);
        callRef.updateChildren(call);
        iceRef = callRef.child("ice").child(myUid);
        notifyState("ringing", null);
        CallService.start(this.context);
        CallSignal.announce(this.context, chatId, callId, myUid, peerUid, video);
        armRingTimeout();
        createPeerConnection();
        listenToAnswer();
        listenToRemoteIce();
        // Предложение уходит сразу: у принимающей стороны не будет задержки на «дозвон».
        createOffer();
    }

    public void startIncoming(Context context, String chatId, String myUid, String peerUid,
                              String callId, boolean video) {
        reset();
        ensureFactory(context);
        this.chatId = chatId;
        this.myUid = myUid;
        this.peerUid = peerUid;
        this.videoCall = video;
        this.incoming = true;
        this.finished = false;
        this.callId = callId;
        callRef = FirebaseDatabase.getInstance().getReference("chats").child(chatId).child("calls").child(callId);
        iceRef = callRef.child("ice").child(myUid);
        listenToCall();
        notifyState("ringing", null);
        CallService.start(this.context);
        createPeerConnection();
        listenToRemoteIce();
        listenToOffer();
    }

    /** Абонент нажал «Ответить»: забираем оффер и отправляем ответ. */
    public void accept() {
        if (peerConnection == null || answered) return;
        answered = true;
        callRef.child(FIELD_STATE).setValue("connecting");
        notifyState("connecting", null);
        CallSignal.answer(this.context, chatId, callId, myUid, peerUid);
        // Оффер мог прийти до нажатия «Ответить»: тогда сразу шлём ответ, иначе ждём оффер.
        if (appliedRemoteDescription) {
            createAnswer();
        } else {
            listenToOffer();
        }
    }

    public void decline() {
        if (callRef != null) {
            Map<String, Object> update = new HashMap<>();
            update.put(FIELD_STATE, "declined");
            update.put("endedBy", myUid);
            callRef.updateChildren(update);
        }
        notifyState("declined", null);
        CallSignal.cancel(this.context, chatId, callId, myUid, peerUid, "declined");
        finish();
    }

    public void hangup() {
        if (callRef != null && !finished) {
            Map<String, Object> update = new HashMap<>();
            update.put(FIELD_STATE, "ended");
            update.put("endedBy", myUid);
            update.put("endedAt", ServerValue.TIMESTAMP);
            callRef.updateChildren(update);
        }
        notifyState("ended", null);
        CallSignal.cancel(this.context, chatId, callId, myUid, peerUid, "ended");
        finish();
    }

    /** Страховка от «вечного» дозвона: через 45 секунд звонок закрывается как пропущенный. */
    private void armRingTimeout() {
        cancelRingTimeout();
        ringTimeout = () -> {
            ringTimeout = null;
            if (pendingActive || finished) return;
            if (callRef != null) {
                Map<String, Object> update = new HashMap<>();
                update.put(FIELD_STATE, "missed");
                update.put("endedBy", myUid);
                update.put("endedAt", ServerValue.TIMESTAMP);
                callRef.updateChildren(update);
            }
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

    /** Время разговора для счётчика на экране. */
    public long elapsedMillis() {
        return startedAt <= 0L ? 0L : System.currentTimeMillis() - startedAt;
    }

    public void finish() {
        if (finished) return;
        finished = true;
        cancelRingTimeout();
        detachListeners();
        if (capturer != null) {
            try { capturer.stopCapture(); } catch (Exception ignored) { }
            capturer.dispose();
            capturer = null;
        }
        if (videoTrack != null) {
            if (localRenderer != null) videoTrack.removeSink(localRenderer);
            if (remoteRenderer != null) videoTrack.removeSink(remoteRenderer);
            videoTrack.dispose();
            videoTrack = null;
        }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (audioTrack != null) { audioTrack.dispose(); audioTrack = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (peerConnection != null) { peerConnection.close(); peerConnection.dispose(); peerConnection = null; }
        answered = false;
        appliedRemoteDescription = false;
        iceRestarted = false;
        pendingActive = false;
        startedAt = 0L;
        releaseAudioFocus();
        if (localRenderer != null) { localRenderer.release(); localRenderer = null; }
        if (remoteRenderer != null) { remoteRenderer.release(); remoteRenderer = null; }
        if (eglBase != null) { eglBase.release(); eglBase = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        CallNotifier.clear(context);
        CallService.stop(context);
    }

    private void reset() {
        if (peerConnection != null) finish();
        finished = false;
        answered = false;
        appliedRemoteDescription = false;
        iceRestarted = false;
    }

    // ------------------------------------------------------------------
    // PeerConnection
    // ------------------------------------------------------------------

    private void createPeerConnection() {
        PeerConnection.RTCConfiguration configuration =
                new PeerConnection.RTCConfiguration(IceServers.list());
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        configuration.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        configuration.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        configuration.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        configuration.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        configuration.iceCandidatePoolSize = 2;
        peerConnection = factory.createPeerConnection(configuration, observer);

        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("tsuyu_audio", audioSource);
        audioTrack.setEnabled(true);
        List<String> streams = new ArrayList<>();
        streams.add("tsuyu_stream");
        peerConnection.addTrack(audioTrack, streams);

        if (videoCall) {
            videoSource = factory.createVideoSource(false);
            videoTrack = factory.createVideoTrack("tsuyu_video", videoSource);
            peerConnection.addTrack(videoTrack, streams);
            startCapture();
        }
        setAudioRoute();
    }

    private void startCapture() {
        if (videoSource == null || capturer != null) return;
        CameraEnumerator enumerator = new Camera2Enumerator(context);
        String front = null;
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) { front = name; break; }
        }
        String target = front != null ? front : (enumerator.getDeviceNames().length > 0
                ? enumerator.getDeviceNames()[0] : null);
        if (target == null) return;
        capturer = enumerator.createCapturer(target, null);
        SurfaceTextureHelper helper = SurfaceTextureHelper.create("TsuyuCapture", eglBase.getEglBaseContext());
        capturer.initialize(helper, context, videoSource.getCapturerObserver());
        try {
            capturer.startCapture(720, 1280, 30);
        } catch (Exception ignored) {
            try { capturer.startCapture(640, 480, 24); } catch (Exception ignoredAgain) { }
        }
    }

    private void createOffer() {
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription description) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() {
                        Map<String, Object> update = new HashMap<>();
                        update.put(FIELD_OFFER, description.description);
                        update.put(FIELD_STATE, "connecting");
                        callRef.updateChildren(update);
                        notifyState("connecting", null);
                    }
                }, description);
            }
        }, offerConstraints());
    }

    private MediaConstraints offerConstraints() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("offerToReceiveVideo", videoCall ? "true" : "false"));
        return constraints;
    }

    private void listenToOffer() {
        if (offerListener != null) return;
        offerListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                String offer = snapshot.getValue(String.class);
                if (offer == null || !incoming) return;
                peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() {
                        appliedRemoteDescription = true;
                        if (!answered) {
                            notifyState("ringing", null);
                            return;
                        }
                        createAnswer();
                    }
                }, new SessionDescription(SessionDescription.Type.OFFER, offer));
            }

            @Override public void onCancelled(DatabaseError error) { }
        };
        callRef.child(FIELD_OFFER).addValueEventListener(offerListener);
    }

    private void createAnswer() {
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription description) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() {
                        Map<String, Object> update = new HashMap<>();
                        update.put(FIELD_ANSWER, description.description);
                        update.put(FIELD_STATE, "active");
                        callRef.updateChildren(update);
                        notifyState("active", null);
                    }
                }, description);
            }
        }, offerConstraints());
    }

    private void listenToCall() {
        if (stateListener != null) return;
        stateListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                String state = snapshot.child(FIELD_STATE).getValue(String.class);
                String endedBy = snapshot.child("endedBy").getValue(String.class);
                if (state == null) return;
                if ("ended".equals(state) || "declined".equals(state)) {
                    boolean mine = myUid != null && myUid.equals(endedBy);
                    if (!finished) {
                        notifyState(state, mine ? null : "peer");
                        main.post(() -> { if (!finished) finish(); });
                    }
                    return;
                }
                if ("connecting".equals(state) && incoming && !answered) {
                    // Собеседник уже поднял трубку со своей стороны.
                    return;
                }
                if ("active".equals(state)) {
                    String answer = snapshot.child(FIELD_ANSWER).getValue(String.class);
                    if (answer != null && !appliedRemoteDescription) applyRemoteAnswer(answer);
                    if (!answered && incoming) {
                        answered = true;
                        notifyState("active", null);
                    }
                }
            }

            @Override public void onCancelled(DatabaseError error) { }
        };
        callRef.addValueEventListener(stateListener);
    }

    private void applyRemoteAnswer(String answer) {
        if (peerConnection == null || answer == null) return;
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override public void onSetSuccess() {
                appliedRemoteDescription = true;
                notifyState("active", null);
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, answer));
    }

    private void listenToAnswer() {
        if (answerListener != null) return;
        answerListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                String answer = snapshot.getValue(String.class);
                if (answer == null || appliedRemoteDescription) return;
                applyRemoteAnswer(answer);
            }

            @Override public void onCancelled(DatabaseError error) { }
        };
        callRef.child(FIELD_ANSWER).addValueEventListener(answerListener);
    }

    private void listenToRemoteIce() {
        if (iceListener != null) return;
        iceListener = new com.google.firebase.database.ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot snapshot, String previous) {
                String sdp = snapshot.child("sdp").getValue(String.class);
                String mid = snapshot.child("mid").getValue(String.class);
                Integer index = snapshot.child("index").getValue(Integer.class);
                if (sdp == null) return;
                try {
                    peerConnection.addIceCandidate(new IceCandidate(mid, index == null ? 0 : index, sdp));
                } catch (Exception ignored) { }
            }

            @Override public void onChildChanged(DataSnapshot snapshot, String previous) { }
            @Override public void onChildRemoved(DataSnapshot snapshot) { }
            @Override public void onChildMoved(DataSnapshot snapshot, String previous) { }
            @Override public void onCancelled(DatabaseError error) { }
        };
        callRef.child("ice").child(peerUid).addChildEventListener(iceListener);
    }

    private void detachListeners() {
        if (callRef == null) return;
        if (answerListener != null) { callRef.child(FIELD_ANSWER).removeEventListener(answerListener); answerListener = null; }
        if (offerListener != null) { callRef.child(FIELD_OFFER).removeEventListener(offerListener); offerListener = null; }
        if (stateListener != null) { callRef.removeEventListener(stateListener); stateListener = null; }
        if (iceListener != null) { callRef.child("ice").child(peerUid).removeEventListener(iceListener); iceListener = null; }
    }

    private final PeerConnection.Observer observer = new PeerConnection.Observer() {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }

        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            if (state == PeerConnection.IceConnectionState.CONNECTED
                    || state == PeerConnection.IceConnectionState.COMPLETED) {
                if (!pendingActive) {
                    pendingActive = true;
                    startedAt = System.currentTimeMillis();
                    cancelRingTimeout();
                }
                notifyState("active", null);
            } else if (state == PeerConnection.IceConnectionState.FAILED) {
                // Один раз пробуем перезапустить ICE (сменилась сеть, просел Wi-Fi) — это
                // спасает звонок вместо разрыва.
                if (!iceRestarted && peerConnection != null) {
                    iceRestarted = true;
                    try { peerConnection.restartIce(); } catch (Exception ignored) { }
                    notifyState("connecting", "restart");
                } else {
                    notifyState("failed", null);
                    main.post(() -> { if (!finished) finish(); });
                }
            } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                notifyState("connecting", "weak");
            }
        }

        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }

        @Override public void onIceCandidate(IceCandidate candidate) {
            if (iceRef == null || candidate == null) return;
            Map<String, Object> value = new HashMap<>();
            value.put("sdp", candidate.sdp);
            value.put("mid", candidate.sdpMid);
            value.put("index", candidate.sdpMLineIndex);
            iceRef.push().setValue(value);
        }

        @Override public void onAddStream(MediaStream stream) { }

        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            MediaStreamTrack track = receiver.track();
            if (track instanceof VideoTrack && remoteRenderer != null) {
                ((VideoTrack) track).addSink(remoteRenderer);
            }
        }

        @Override public void onRemoveTrack(RtpReceiver receiver) { }
        @Override public void onDataChannel(DataChannel channel) { }
        @Override public void onRenegotiationNeeded() {
            if (incoming || peerConnection == null) return;
            // Пересогласование (например, после перезапуска ICE) — отправляем свежий оффер.
            peerConnection.createOffer(new SimpleSdpObserver() {
                @Override public void onCreateSuccess(SessionDescription description) {
                    peerConnection.setLocalDescription(new SimpleSdpObserver() {
                        @Override public void onSetSuccess() {
                            callRef.child(FIELD_OFFER).setValue(description.description);
                        }
                    }, description);
                }
            }, offerConstraints());
        }
    };

    // ------------------------------------------------------------------
    // Управление во время звонка
    // ------------------------------------------------------------------

    public void setMuted(boolean value) {
        muted = value;
        if (audioTrack != null) audioTrack.setEnabled(!value);
    }

    public void setSpeaker(boolean value) {
        speaker = value;
        setAudioRoute();
    }

    public void setVideoEnabled(boolean enabled) {
        if (videoTrack != null) videoTrack.setEnabled(enabled);
        if (capturer != null) {
            try {
                if (enabled) capturer.startCapture(720, 1280, 30);
                else capturer.stopCapture();
            } catch (Exception ignored) { }
        }
    }

    public void switchCamera() {
        if (capturer != null) {
            try { capturer.switchCamera(null); } catch (Exception ignored) { }
        }
    }

    private void setAudioRoute() {
        if (context == null) return;
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        manager.setSpeakerphoneOn(speaker || videoCall);
    }

    private void releaseAudioFocus() {
        if (context == null) return;
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager != null) {
            manager.setSpeakerphoneOn(false);
            manager.setMode(AudioManager.MODE_NORMAL);
        }
    }

    private void notifyState(String state, String detail) {
        if (listeners.isEmpty()) return;
        main.post(() -> {
            for (Listener target : listeners) target.onCallState(state, detail);
        });
    }

    /** Путь звонка в RTDB — по нему принимающая сторона поднимает CallActivity. */
    public static DatabaseReference callNode(String chatId, String callId) {
        return FirebaseDatabase.getInstance().getReference("chats").child(chatId).child("calls").child(callId);
    }

    public static String formatDuration(long millis) {
        long total = Math.max(0L, millis / 1000L);
        return String.format(Locale.getDefault(), "%02d:%02d", total / 60L, total % 60L);
    }

    public String getCallId() {
        return callId;
    }

    public String getChatId() {
        return chatId;
    }

    public String getPeerUid() {
        return peerUid;
    }

    /** True, когда разговор начал собеседник — запись в истории делает только вызывающий. */
    public boolean wasIncoming() {
        return incoming;
    }

    /** Минимальный SdpObserver: обработку ошибок держим в одном месте. */
    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }
}
