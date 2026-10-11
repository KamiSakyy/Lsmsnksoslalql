package com.tsuyu.messenger.call;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.ProfileRepository;

import org.webrtc.SurfaceViewRenderer;

/**
 * Экран звонка: входящий, исходящий и идущий разговор в одном окне.
 * Экран — только лицо звонка: сам разговор живёт в {@link CallEngine} и
 * {@link CallService}, поэтому уход из активности и погашенный экран его не обрывают.
 */
public final class CallActivity extends Activity implements CallEngine.Listener {

    public static final String EXTRA_CHAT_ID = "chatId";
    public static final String EXTRA_CALL_ID = "callId";
    public static final String EXTRA_PEER_UID = "peerUid";
    public static final String EXTRA_PEER_NAME = "peerName";
    public static final String EXTRA_VIDEO = "video";
    public static final String EXTRA_INCOMING = "incoming";

    private static final int REQ_PERMISSIONS = 8801;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private View incomingActions;
    private View activeActions;
    private View remoteShade;
    private View localCard;
    private MaterialButton muteButton;
    private MaterialButton speakerButton;
    private MaterialButton cameraButton;
    private MaterialButton switchButton;
    private TextView statusView;
    private ShapeableImageView avatarView;
    private TextView initialView;

    private String chatId;
    private String callId;
    private String peerUid;
    private String peerName;
    private boolean video;
    private boolean incoming;
    private boolean answered;
    private boolean renderersReady;

    private CallEngine engine;

    public static Intent incomingIntent(Context context, String chatId, String callId, String peerUid,
                                        String peerName, boolean video) {
        Intent intent = new Intent(context, CallActivity.class);
        intent.putExtra(EXTRA_CHAT_ID, chatId);
        intent.putExtra(EXTRA_CALL_ID, callId);
        intent.putExtra(EXTRA_PEER_UID, peerUid);
        intent.putExtra(EXTRA_PEER_NAME, peerName);
        intent.putExtra(EXTRA_VIDEO, video);
        intent.putExtra(EXTRA_INCOMING, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_call);
        engine = CallEngine.get();

        Intent intent = getIntent();
        chatId = intent.getStringExtra(EXTRA_CHAT_ID);
        callId = intent.getStringExtra(EXTRA_CALL_ID);
        peerUid = intent.getStringExtra(EXTRA_PEER_UID);
        peerName = intent.getStringExtra(EXTRA_PEER_NAME);
        video = intent.getBooleanExtra(EXTRA_VIDEO, false);
        // Действие «Ответить» из уведомления приходит тем же интентом.
        answered = CallActionReceiver.ACTION_ANSWER.equals(intent.getAction());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        incomingActions = findViewById(R.id.call_incoming_actions);
        activeActions = findViewById(R.id.call_active_actions);
        remoteShade = findViewById(R.id.call_remote_shade);
        localCard = findViewById(R.id.call_local_card);
        muteButton = findViewById(R.id.call_mute);
        speakerButton = findViewById(R.id.call_speaker);
        cameraButton = findViewById(R.id.call_camera);
        switchButton = findViewById(R.id.call_switch);
        statusView = findViewById(R.id.call_status);
        avatarView = findViewById(R.id.call_avatar);
        initialView = findViewById(R.id.call_initial);

        ((TextView) findViewById(R.id.call_name)).setText(
                peerName == null || peerName.trim().isEmpty() ? getString(R.string.call_unknown) : peerName);
        initialView.setText(initialOf(peerName));
        TextView badge = findViewById(R.id.call_relay_badge);
        badge.setText(getString(R.string.call_p2p_badge, engine.relayCount()));

        findViewById(R.id.call_end).setOnClickListener(v -> hangup());
        incomingActions.findViewById(R.id.call_decline).setOnClickListener(v -> decline());
        incomingActions.findViewById(R.id.call_answer).setOnClickListener(v -> answer());
        muteButton.setOnClickListener(v -> {
            engine.setMuted(!engine.isMuted());
            updateControls();
        });
        speakerButton.setOnClickListener(v -> {
            engine.setSpeaker(!engine.isSpeaker());
            updateControls();
        });
        cameraButton.setOnClickListener(v -> {
            localCard.setVisibility(localCard.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            engine.setVideoEnabled(localCard.getVisibility() == View.VISIBLE);
        });
        switchButton.setOnClickListener(v -> engine.switchCamera());

        incoming = isIncomingCall();
        if (incoming && !answered) {
            incomingActions.setVisibility(View.VISIBLE);
            activeActions.setVisibility(View.INVISIBLE);
            statusView.setText(getString(video ? R.string.call_incoming_video : R.string.call_incoming_audio));
            localCard.setVisibility(View.GONE);
            switchButton.setVisibility(View.GONE);
            loadPeerProfile();
            requestPermissionsIfNeeded(false);
        } else {
            startCallIfNeeded();
            statusView.setText(getString(R.string.call_connecting));
            showActiveControls(false);
            requestPermissionsIfNeeded(video);
        }
        engine.addListener(this);
        CallNotifier.setCallInfo(peerName, video);
        CallService.refresh(this);
    }

    /**
     * Экран открыт по уведомлению — значит звонок уже висит в базе, движку нужно
     * подключиться к нему, а не начинать новый.
     */
    private boolean isIncomingCall() {
        return getIntent().getBooleanExtra(EXTRA_INCOMING, false) || answered;
    }

    private void startCallIfNeeded() {
        if (CallEngine.busy() && callId != null && callId.equals(engine.getCallId())) {
            // Уже ведём этот разговор (например, вернулись из фона) — только переподключаем видео.
            attachRenderersIfNeeded();
            return;
        }
        String uid = currentUid();
        if (uid == null || chatId == null || peerUid == null) {
            toast(getString(R.string.call_failed));
            finish();
            return;
        }
        if (isIncomingCall()) {
            engine.startIncoming(this, chatId, uid, peerUid, callId, video);
            // Пришли из уведомления кнопкой «Ответить» — трубку уже подняли.
            if (answered) engine.accept();
        } else {
            engine.startOutgoing(this, chatId, uid, peerUid, video);
        }
        answered = true;
        CallService.start(this);
        attachRenderersIfNeeded();
    }

    private String currentUid() {
        try {
            com.google.firebase.auth.FirebaseUser user =
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            return user == null ? null : user.getUid();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void answer() {
        if (answered) return;
        answered = true;
        statusView.setText(getString(R.string.call_connecting));
        incomingActions.setVisibility(View.GONE);
        showActiveControls(true);
        requestPermissionsIfNeeded(video);
        if (CallEngine.busy() && callId != null && callId.equals(engine.getCallId())) {
            engine.accept();
        } else {
            startCallIfNeeded();
            CallService.start(this);
        }
        CallNotifier.clear(this);
        attachRenderersIfNeeded();
    }

    private void decline() {
        CallNotifier.clear(this);
        if (CallEngine.busy()) {
            engine.decline();
        } else {
            String uid = currentUid();
            if (uid != null && chatId != null && callId != null) {
                CallSignal.declineRemote(this, chatId, callId, uid, peerUid);
            }
        }
        finish();
    }

    private void hangup() {
        if (incoming && !answered) {
            decline();
            return;
        }
        engine.hangup();
        finish();
    }

    private void showActiveControls(boolean visible) {
        activeActions.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        incomingActions.setVisibility(View.GONE);
        switchButton.setVisibility(video ? View.VISIBLE : View.GONE);
        if (localCard != null && video) localCard.setVisibility(View.VISIBLE);
        updateControls();
    }

    private void updateControls() {
        int tintOn = getResources().getColor(R.color.tsuyu_white, getTheme());
        muteButton.setAlpha(engine.isMuted() ? 0.5f : 1f);
        muteButton.setIconResource(engine.isMuted() ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        speakerButton.setAlpha(engine.isSpeaker() ? 0.5f : 1f);
        speakerButton.setIconResource(R.drawable.ic_speaker);
        cameraButton.setIconResource(R.drawable.ic_video_off);
        muteButton.setIconTint(android.content.res.ColorStateList.valueOf(tintOn));
    }

    private void attachRenderersIfNeeded() {
        if (renderersReady) return;
        SurfaceViewRenderer remote = findViewById(R.id.call_remote);
        SurfaceViewRenderer local = findViewById(R.id.call_local);
        engine.attachRenderers(local, remote);
        renderersReady = true;
        remoteShade.setVisibility(video ? View.GONE : View.VISIBLE);
    }

    private void loadPeerProfile() {
        if (peerUid == null) return;
        new ProfileRepository(com.google.firebase.database.FirebaseDatabase.getInstance())
                .getProfile(peerUid, profile -> {
                    if (profile == null) return;
                    String name = profile.displayNameOrUsername();
                    if (name != null && !name.trim().isEmpty()) {
                        peerName = name;
                        ((TextView) findViewById(R.id.call_name)).setText(name);
                        initialView.setText(initialOf(name));
                        CallNotifier.setCallInfo(name, video);
                        CallService.refresh(this);
                    }
                    Bitmap avatar = decodeAvatar(profile.avatarBase64);
                    if (avatar != null) {
                        avatarView.setImageBitmap(avatar);
                        avatarView.setVisibility(View.VISIBLE);
                        initialView.setVisibility(View.GONE);
                    }
                });
    }

    private static Bitmap decodeAvatar(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String initialOf(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String trimmed = name.trim();
        return trimmed.substring(0, 1).toUpperCase(java.util.Locale.getDefault());
    }

    private void requestPermissionsIfNeeded(boolean withCamera) {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (withCamera && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    // ------------------------------------------------------------------
    // Состояния звонка
    // ------------------------------------------------------------------

    @Override
    public void onCallState(String state, String detail) {
        if (isFinishing()) return;
        if ("ringing".equals(state)) {
            if (incoming && !answered) statusView.setText(
                    getString(video ? R.string.call_incoming_video : R.string.call_incoming_audio));
            else statusView.setText(getString(R.string.call_ringing));
            return;
        }
        if ("connecting".equals(state)) {
            statusView.setText(getString(R.string.call_connecting));
            return;
        }
        if ("active".equals(state)) {
            answered = true;
            incomingActions.setVisibility(View.GONE);
            showActiveControls(true);
            attachRenderersIfNeeded();
            remoteShade.setVisibility(video ? View.GONE : View.VISIBLE);
            engine.setSpeaker(video);
            ui.removeCallbacks(ticker);
            ui.post(ticker);
            return;
        }
        if ("declined".equals(state)) {
            toast(getString(R.string.call_declined));
            finish();
            return;
        }
        if ("missed".equals(state)) {
            toast(getString(R.string.call_missed));
            finish();
            return;
        }
        if ("failed".equals(state)) {
            toast(getString(R.string.call_failed));
            finish();
            return;
        }
        if ("ended".equals(state)) finish();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long elapsed = engine.elapsedMillis();
            if (elapsed > 0L && statusView != null) {
                statusView.setText(CallEngine.formatDuration(elapsed));
            }
            ui.postDelayed(this, 1000L);
        }
    };

    // ------------------------------------------------------------------
    // Жизненный цикл: разговор важнее экрана
    // ------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (incoming && !answered) {
            decline();
        } else {
            // Сворачиваем экран, но не разговор: звонок остаётся в уведомлении.
            toast(getString(R.string.call_running_in_background));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        engine.removeListener(this);
        super.onDestroy();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
