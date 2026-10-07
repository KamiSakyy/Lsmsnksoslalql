package com.tsuyu.messenger.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;

import com.tsuyu.messenger.R;

public final class NotificationSounds {
    private static final String PREFS = "tsuyu_notification_settings";
    private static final String ENABLED = "enabled";
    private static final String SOUND = "sound_uri";
    private NotificationSounds() {}

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply();
        ensureChannel(context);
    }

    public static synchronized boolean claimNotification(Context context, String chatId, String messageId) {
        if (chatId == null || messageId == null) return true;
        android.content.SharedPreferences prefs = context.getSharedPreferences("tsuyu_notification_dedupe", Context.MODE_PRIVATE);
        String key = "last_notified_" + chatId;
        if (messageId.equals(prefs.getString(key, ""))) return false;
        prefs.edit().putString(key, messageId).commit();
        return true;
    }

    public static Uri getSound(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SOUND, null);
        if (value == null || value.isEmpty()) return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return Uri.parse(value);
    }

    public static void setSound(Context context, Uri uri) {
        if (uri == null) return;
        context.getContentResolver().takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SOUND, uri.toString()).apply();
        ensureChannel(context);
    }

    public static String channelId(Context context) {
        String material = (isEnabled(context) ? "on:" : "off:") + String.valueOf(getSound(context));
        return "tsuyu_messages_" + Integer.toHexString(material.hashCode());
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String id = channelId(context);
        if (manager.getNotificationChannel(id) != null) return;
        NotificationChannel channel = new NotificationChannel(id,
                context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.channel_messages_description));
        channel.enableVibration(true);
        Uri sound = isEnabled(context) ? getSound(context) : null;
        channel.setSound(sound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        manager.createNotificationChannel(channel);
    }
}
