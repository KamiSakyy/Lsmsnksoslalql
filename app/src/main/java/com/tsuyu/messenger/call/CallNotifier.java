package com.tsuyu.messenger.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tsuyu.messenger.MainActivity;
import com.tsuyu.messenger.R;

/**
 * Уведомления звонка: «звонит» на весь экран и «идёт разговор» в шторке.
 * Канал отдельный от сообщений, поэтому звонок звучит как звонок, а не как сообщение.
 */
public final class CallNotifier {

    public static final String CHANNEL_INCOMING = "tsuyu_calls_incoming";
    private static final String CHANNEL_ACTIVE = "tsuyu_calls_active";
    private static final int ID_INCOMING = 7710;
    public static final int ID_ACTIVE = 7711;

    private static MediaPlayer ringer;
    private static Vibrator vibrator;
    private static volatile String callTitle = "Tsuyu";
    private static volatile boolean callVideo;

    private CallNotifier() { }

    /** Имя собеседника и тип звонка — их показывает уведомление активного разговора. */
    public static void setCallInfo(String title, boolean video) {
        if (title != null && !title.trim().isEmpty()) callTitle = title;
        callVideo = video;
    }

    public static String callTitle() {
        return callTitle;
    }

    public static boolean callVideo() {
        return callVideo;
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (manager.getNotificationChannel(CHANNEL_INCOMING) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_INCOMING,
                    context.getString(R.string.call_channel_incoming), NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(context.getString(R.string.call_channel_incoming_description));
            channel.enableVibration(false); // вибрацию ведёт сам звонок, иначе она дублируется
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        if (manager.getNotificationChannel(CHANNEL_ACTIVE) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ACTIVE,
                    context.getString(R.string.call_channel_active), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(context.getString(R.string.call_channel_active_description));
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
    }

    // ------------------------------------------------------------------
    // Входящий вызов
    // ------------------------------------------------------------------

    public static void incoming(Context context, String chatId, String callId, String peerUid,
                               String peerName, boolean video) {
        ensureChannels(context);
        String title = peerName == null || peerName.trim().isEmpty()
                ? context.getString(R.string.call_unknown) : peerName;
        setCallInfo(title, video);
        Intent open = CallActivity.incomingIntent(context, chatId, callId, peerUid, title, video);
        PendingIntent fullScreen = PendingIntent.getActivity(context, 7712, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        PendingIntent answer = PendingIntent.getBroadcast(context, 7713,
                CallActionReceiver.answerIntent(context, chatId, callId, peerUid, title, video),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        PendingIntent decline = PendingIntent.getBroadcast(context, 7714,
                CallActionReceiver.declineIntent(context, chatId, callId, peerUid),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_INCOMING)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(title)
                .setContentText(context.getString(video ? R.string.call_incoming_video : R.string.call_incoming_audio))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(fullScreen)
                .setFullScreenIntent(fullScreen, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(R.drawable.ic_call, context.getString(R.string.call_decline), decline)
                .addAction(R.drawable.ic_call, context.getString(R.string.call_answer), answer);
        try {
            NotificationManagerCompat.from(context).notify(ID_INCOMING, builder.build());
        } catch (SecurityException ignored) {
            // Без разрешения на уведомления экран вызова всё равно откроется активностью.
        }
        startRinging(context);
    }

    /** «Идёт разговор» — им же живёт foreground-сервис, поэтому звонок не рвётся в фоне. */
    public static Notification ongoing(Context context) {
        ensureChannels(context);
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 7715, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        PendingIntent end = PendingIntent.getBroadcast(context, 7716, CallActionReceiver.endIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return new NotificationCompat.Builder(context, CHANNEL_ACTIVE)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(callTitle)
                .setContentText(context.getString(callVideo
                        ? R.string.call_ongoing_video : R.string.call_ongoing_audio))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(R.drawable.ic_call_end, context.getString(R.string.call_end), end)
                .build();
    }

    public static void clear(Context context) {
        if (context == null) return;
        try {
            NotificationManagerCompat.from(context).cancel(ID_INCOMING);
        } catch (SecurityException ignored) { }
        stopRinging();
    }

    // ------------------------------------------------------------------
    // Звонок и вибрация
    // ------------------------------------------------------------------

    private static void startRinging(Context context) {
        stopRinging();
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (sound != null) {
                ringer = new MediaPlayer();
                ringer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                ringer.setDataSource(context, sound);
                ringer.setLooping(true);
                ringer.prepare();
                ringer.start();
            }
        } catch (Exception ignored) {
            ringer = null;
        }
        try {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0L, 700L, 700L};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) {
            vibrator = null;
        }
    }

    public static void stopRinging() {
        if (ringer != null) {
            try { ringer.stop(); } catch (Exception ignored) { }
            try { ringer.release(); } catch (Exception ignored) { }
            ringer = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) { }
            vibrator = null;
        }
    }
}
