package com.noir.p2pchat.service;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.ServiceCompat;

import com.noir.p2pchat.AppKernel;
import com.noir.p2pchat.MessageNotifications;
import com.noir.p2pchat.core.P2pEngine;

/** User-visible foreground owner for Firebase SSE signaling and active P2P DataChannels. */
public final class ChatConnectionService extends Service implements P2pEngine.Listener {
    public static final String ACTION_STOP = "com.noir.p2pchat.action.STOP_CONNECTION";
    public static final String ACTION_SEND_TEXT = "com.noir.p2pchat.action.SEND_TEXT";
    public static final String ACTION_ACCEPT_INVITE = "com.noir.p2pchat.action.ACCEPT_INVITE";
    public static final String ACTION_DECLINE_INVITE = "com.noir.p2pchat.action.DECLINE_INVITE";
    public static final String ACTION_DECLINE_CALL = "com.noir.p2pchat.action.DECLINE_CALL";
    public static final String ACTION_END_CALL = "com.noir.p2pchat.action.END_CALL";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_PEER_UID = "peer_uid";
    public static final String EXTRA_MESSAGE_TEXT = "message_text";
    private AppKernel app;
    private volatile boolean mediaCallActive;
    private volatile boolean videoCallActive;
    private volatile String activeCallId;
    private volatile String activeCallPeerUid;
    private volatile String activeCallState;
    private volatile boolean activeCallIsVideo;

    @Override
    public void onCreate() {
        super.onCreate();
        app = (AppKernel) getApplication();
        MessageNotifications.createChannels(this);
        app.p2p().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            app.p2p().removeListener(this);
            app.p2p().stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startAsForeground(foregroundNotification(app.p2p().getStatus()), foregroundServiceType());
        app.p2p().start();
        if (intent != null) {
            String peerUid = intent.getStringExtra(EXTRA_PEER_UID);
            String action = intent.getAction();
            if (ACTION_ACCEPT_INVITE.equals(action) && peerUid != null) {
                app.p2p().addContact(peerUid);
            } else if (ACTION_DECLINE_INVITE.equals(action) && peerUid != null) {
                app.p2p().declineInvite(peerUid);
            } else if (ACTION_DECLINE_CALL.equals(action)) {
                app.p2p().declineMediaCall(intent.getStringExtra(EXTRA_CALL_ID));
            } else if (ACTION_END_CALL.equals(action)) {
                app.p2p().endMediaCall(intent.getStringExtra(EXTRA_CALL_ID));
            } else if (ACTION_SEND_TEXT.equals(action) && peerUid != null) {
                app.p2p().addContact(peerUid);
                app.p2p().sendText(peerUid, intent.getStringExtra(EXTRA_MESSAGE_TEXT));
            }
        }
        return START_STICKY;
    }

    private int foregroundServiceType() {
        if (Build.VERSION.SDK_INT < 34) return 0;
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
        if (mediaCallActive) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (videoCallActive) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        return type;
    }

    private android.app.Notification foregroundNotification(String fallback) {
        if (mediaCallActive && activeCallId != null && activeCallPeerUid != null) {
            return MessageNotifications.mediaCallNotification(this, activeCallId, activeCallPeerUid,
                    activeCallIsVideo, activeCallState);
        }
        return MessageNotifications.connectionNotification(this, foregroundDetails(fallback));
    }

    private String foregroundDetails(String fallback) {
        if (!mediaCallActive || activeCallPeerUid == null) return fallback;
        String peer = activeCallPeerUid.length() <= 12 ? activeCallPeerUid : activeCallPeerUid.substring(0, 12) + "…";
        String media = activeCallIsVideo ? "Видеозвонок" : "Аудиозвонок";
        if ("calling".equals(activeCallState)) return media + " · вызов " + peer;
        if ("ringing".equals(activeCallState)) return media + " · ожидает ответа " + peer;
        if ("connecting".equals(activeCallState)) return media + " · соединяем с " + peer;
        if ("reconnecting".equals(activeCallState)) return media + " · восстанавливаем связь с " + peer;
        return media + " · разговор с " + peer;
    }

    private void startAsForeground(android.app.Notification notification, int foregroundType) {
        ServiceCompat.startForeground(this, MessageNotifications.CONNECTION_NOTIFICATION_ID,
                notification, foregroundType);
    }

    @Override
    public void onEngineStatus(String status) {
        if (Build.VERSION.SDK_INT >= 24) {
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) {
                try {
                    manager.notify(MessageNotifications.CONNECTION_NOTIFICATION_ID,
                            foregroundNotification(status));
                } catch (SecurityException ignored) {
                    // Foreground-service notification remains owned by Android when notifications are denied.
                }
            }
        }
    }

    @Override
    public void onMediaCallState(String callId, String peerUid, boolean video, String state) {
        boolean outgoingRinging = "ringing".equals(state) && app != null && app.p2p().isOutgoingMediaCall(callId);
        mediaCallActive = "calling".equals(state) || outgoingRinging || "connecting".equals(state)
                || "connected".equals(state) || "reconnecting".equals(state);
        videoCallActive = mediaCallActive && video;
        activeCallId = mediaCallActive ? callId : null;
        activeCallPeerUid = mediaCallActive ? peerUid : null;
        activeCallState = mediaCallActive ? state : null;
        activeCallIsVideo = mediaCallActive && video;
        if (app != null) {
            try {
                startAsForeground(foregroundNotification(app.p2p().getStatus()), foregroundServiceType());
            } catch (IllegalStateException | SecurityException e) {
                android.util.Log.w("NoirP2P", "Could not update media-call foreground service type", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (app != null) {
            app.p2p().removeListener(this);
            app.p2p().stop();
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
