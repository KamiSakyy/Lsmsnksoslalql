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
    private AppKernel app;

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
        startAsForeground(MessageNotifications.connectionNotification(this, app.p2p().getStatus()));
        app.p2p().start();
        return START_STICKY;
    }

    private void startAsForeground(android.app.Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, MessageNotifications.CONNECTION_NOTIFICATION_ID,
                    notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            ServiceCompat.startForeground(this, MessageNotifications.CONNECTION_NOTIFICATION_ID,
                    notification, 0);
        }
    }

    @Override
    public void onEngineStatus(String status) {
        if (Build.VERSION.SDK_INT >= 24) {
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) {
                try {
                    manager.notify(MessageNotifications.CONNECTION_NOTIFICATION_ID,
                            MessageNotifications.connectionNotification(this, status));
                } catch (SecurityException ignored) {
                    // Foreground-service notification remains owned by Android when notifications are denied.
                }
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
