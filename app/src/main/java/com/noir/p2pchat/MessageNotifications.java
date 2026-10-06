package com.noir.p2pchat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.noir.p2pchat.ui.ChatActivity;
import com.noir.p2pchat.ui.MainActivity;

public final class MessageNotifications {
    public static final String CHANNEL_CONNECTION = "p2p_connection";
    private static final String CHANNEL_MESSAGES = "p2p_messages";
    private static final String CHANNEL_INVITES = "p2p_invites";
    public static final int CONNECTION_NOTIFICATION_ID = 1101;

    private MessageNotifications() { }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_CONNECTION,
                "P2P-соединение", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_MESSAGES,
                "Сообщения", NotificationManager.IMPORTANCE_DEFAULT));
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_INVITES,
                "Запросы на подключение", NotificationManager.IMPORTANCE_DEFAULT));
    }

    public static Notification connectionNotification(Context context, String details) {
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 1101, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(context, CHANNEL_CONNECTION)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle("Noir · P2P-чат")
                .setContentText(details == null || details.isEmpty() ? "Сигналинг Firebase активен" : details)
                .setContentIntent(pending)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public static void showIncoming(Context context, String peerUid) {
        Intent open = new Intent(context, ChatActivity.class);
        open.putExtra(ChatActivity.EXTRA_PEER_UID, peerUid);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, peerUid.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_INVITES)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle("Запрос на P2P-подключение")
                .setContentText("ID собеседника: " + shortId(peerUid) + " · нажмите, чтобы принять")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        notifySafely(context, Math.abs(peerUid.hashCode()) + 2200, notification);
    }

    public static void showMessage(Context context, String peerUid, String body) {
        Intent open = new Intent(context, ChatActivity.class);
        open.putExtra(ChatActivity.EXTRA_PEER_UID, peerUid);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, peerUid.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String preview = body == null ? "Новое сообщение" : body.replace('\n', ' ');
        if (preview.length() > 90) preview = preview.substring(0, 87) + "…";
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle("Сообщение · " + shortId(peerUid))
                .setContentText(preview)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        notifySafely(context, Math.abs(peerUid.hashCode()) + 3300, notification);
    }

    private static void notifySafely(Context context, int id, Notification notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification);
        } catch (SecurityException ignored) {
            // Android 13+: the app remains usable if the user declines POST_NOTIFICATIONS.
        }
    }

    private static String shortId(String uid) {
        return uid == null ? "собеседник" : uid.substring(0, Math.min(8, uid.length()));
    }
}
