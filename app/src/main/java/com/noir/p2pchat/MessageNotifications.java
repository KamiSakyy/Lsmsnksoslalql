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
import androidx.core.app.RemoteInput;

import com.noir.p2pchat.service.ChatConnectionService;
import com.noir.p2pchat.ui.CallActivity;
import com.noir.p2pchat.ui.ChatActivity;
import com.noir.p2pchat.ui.MainActivity;

public final class MessageNotifications {
    public static final String CHANNEL_CONNECTION = "p2p_connection";
    private static final String CHANNEL_MESSAGES = "p2p_messages";
    private static final String CHANNEL_INVITES = "p2p_invites";
    private static final String CHANNEL_CALLS = "p2p_calls";
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
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_CALLS,
                "Входящие звонки", NotificationManager.IMPORTANCE_HIGH));
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

    public static Notification mediaCallNotification(Context context, String callId, String peerUid,
                                                     boolean video, String state) {
        Intent open = new Intent(context, CallActivity.class)
                .putExtra(CallActivity.EXTRA_CALL_ID, callId)
                .putExtra(CallActivity.EXTRA_PEER_UID, peerUid)
                .putExtra(CallActivity.EXTRA_VIDEO, video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(context, requestCode(callId, 53), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent hangup = new Intent(context, ChatConnectionService.class)
                .setAction(ChatConnectionService.ACTION_END_CALL)
                .putExtra(ChatConnectionService.EXTRA_CALL_ID, callId);
        int serviceFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent hangupPending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(context, requestCode(callId, 54), hangup, serviceFlags)
                : PendingIntent.getService(context, requestCode(callId, 54), hangup, serviceFlags);

        return new NotificationCompat.Builder(context, CHANNEL_CONNECTION)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle(video ? "Видеозвонок Noir" : "Аудиозвонок Noir")
                .setContentText(callProgressText(peerUid, state))
                .setContentIntent(openPending)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_stat_chat, "Завершить", hangupPending)
                .build();
    }

    private static String callProgressText(String peerUid, String state) {
        String peer = " · " + shortId(peerUid);
        if ("calling".equals(state)) return "Вызов" + peer;
        if ("ringing".equals(state)) return "Ожидает ответа" + peer;
        if ("connecting".equals(state)) return "Соединяем" + peer;
        if ("reconnecting".equals(state)) return "Восстанавливаем связь" + peer;
        return "На связи" + peer;
    }

    public static void showIncoming(Context context, String peerUid) {
        Intent open = new Intent(context, ChatActivity.class);
        open.putExtra(ChatActivity.EXTRA_PEER_UID, peerUid);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(context, requestCode(peerUid, 0), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_INVITES)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle("Входящее приглашение")
                .setContentText("ID собеседника: " + shortId(peerUid))
                .setContentIntent(openPending)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(R.drawable.ic_stat_chat, "Принять",
                        inviteAction(context, NotificationActionReceiver.ACTION_ACCEPT_INVITE, peerUid, 1))
                .addAction(R.drawable.ic_stat_chat, "Отклонить",
                        inviteAction(context, NotificationActionReceiver.ACTION_DECLINE_INVITE, peerUid, 2));
        notifySafely(context, notificationId(peerUid, 2200), builder.build());
    }

    public static void cancelIncoming(Context context, String peerUid) {
        NotificationManagerCompat.from(context).cancel(notificationId(peerUid, 2200));
    }

    public static void showIncomingCall(Context context, String peerUid, String callId, boolean video) {
        Intent open = new Intent(context, CallActivity.class)
                .putExtra(CallActivity.EXTRA_PEER_UID, peerUid)
                .putExtra(CallActivity.EXTRA_CALL_ID, callId)
                .putExtra(CallActivity.EXTRA_VIDEO, video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(context, requestCode(callId, 50), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent accept = new Intent(context, CallActivity.class)
                .putExtra(CallActivity.EXTRA_PEER_UID, peerUid)
                .putExtra(CallActivity.EXTRA_CALL_ID, callId)
                .putExtra(CallActivity.EXTRA_VIDEO, video)
                .putExtra(CallActivity.EXTRA_ACCEPT_ON_OPEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent acceptPending = PendingIntent.getActivity(context, requestCode(callId, 51), accept,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent decline = new Intent(context, NotificationActionReceiver.class)
                .setAction(NotificationActionReceiver.ACTION_DECLINE_CALL)
                .putExtra(NotificationActionReceiver.EXTRA_CALL_ID, callId)
                .putExtra(NotificationActionReceiver.EXTRA_PEER_UID, peerUid);
        PendingIntent declinePending = PendingIntent.getBroadcast(context, requestCode(callId, 52), decline,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle(video ? "Видеозвонок" : "Аудиозвонок")
                .setContentText("Вызов от " + shortId(peerUid))
                .setContentIntent(openPending)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(R.drawable.ic_stat_chat, "Принять", acceptPending)
                .addAction(R.drawable.ic_stat_chat, "Отклонить", declinePending)
                .build();
        notifySafely(context, callNotificationId(callId), notification);
    }

    public static void cancelIncomingCall(Context context, String callId) {
        NotificationManagerCompat.from(context).cancel(callNotificationId(callId));
    }

    public static void showMessage(Context context, String peerUid, String body) {
        Intent open = new Intent(context, ChatActivity.class);
        open.putExtra(ChatActivity.EXTRA_PEER_UID, peerUid);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, requestCode(peerUid, 3), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String preview = body == null ? "Новое сообщение" : body.replace('\n', ' ');
        if (preview.length() > 90) preview = preview.substring(0, 87) + "…";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle("Сообщение · " + shortId(peerUid))
                .setContentText(preview)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        Intent replyIntent = new Intent(context, NotificationActionReceiver.class)
                .setAction(NotificationActionReceiver.ACTION_REPLY)
                .putExtra(NotificationActionReceiver.EXTRA_PEER_UID, peerUid);
        int replyFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) replyFlags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent replyPending = PendingIntent.getBroadcast(context, requestCode(peerUid, 4),
                replyIntent, replyFlags);
        RemoteInput remoteInput = new RemoteInput.Builder(NotificationActionReceiver.EXTRA_REPLY_TEXT)
                .setLabel("Ответить")
                .build();
        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_stat_chat, "Ответить", replyPending)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build();
        builder.addAction(replyAction);
        notifySafely(context, notificationId(peerUid, 3300), builder.build());
    }

    private static PendingIntent inviteAction(Context context, String action, String peerUid, int salt) {
        Intent intent = new Intent(context, NotificationActionReceiver.class)
                .setAction(action)
                .putExtra(NotificationActionReceiver.EXTRA_PEER_UID, peerUid);
        return PendingIntent.getBroadcast(context, requestCode(peerUid, salt), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifySafely(Context context, int id, Notification notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification);
        } catch (SecurityException ignored) {
            // Android 13+: the app remains usable if the user declines POST_NOTIFICATIONS.
        }
    }

    private static int requestCode(String uid, int salt) {
        return (uid == null ? 0 : uid.hashCode()) * 31 + salt;
    }

    private static int notificationId(String uid, int base) {
        return base + (uid == null ? 0 : uid.hashCode() & 0x3fffffff);
    }

    private static int callNotificationId(String callId) {
        return 1_600_000_000 + (callId == null ? 0 : callId.hashCode() & 0x0fffffff);
    }

    private static String shortId(String uid) {
        return uid == null ? "собеседник" : uid.substring(0, Math.min(8, uid.length()));
    }
}
