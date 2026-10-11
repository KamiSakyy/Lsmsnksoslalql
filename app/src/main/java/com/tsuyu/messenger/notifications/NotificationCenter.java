package com.tsuyu.messenger.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;

import com.tsuyu.messenger.MainActivity;
import com.tsuyu.messenger.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One place that builds every message notification: the sender's name, avatar and the
 * decrypted text (never ciphertext) inside a MessagingStyle conversation bubble.
 */
public final class NotificationCenter {
    private static final String PREFS = "tsuyu_notification_state";
    private static final int MAX_HISTORY = 4;

    private NotificationCenter() { }

    /** Thumbprint of a chat notification with its decrypted history. */
    private static final class Line {
        final String text;
        final long when;
        final boolean fromMe;

        Line(String text, long when, boolean fromMe) {
            this.text = text;
            this.when = when;
            this.fromMe = fromMe;
        }
    }

    public static String placeholder(Context context) {
        return context.getString(R.string.notification_encrypted);
    }

    /** Shown for the split second before the on-device decryption finishes. */
    public static String decrypting(Context context) {
        return context.getString(R.string.notification_decrypting);
    }

    /** Human wording for every payload kind the app can receive. */
    public static String describePayload(Context context, String payload) {
        if (payload == null) return context.getString(R.string.notification_new);
        try {
            JSONObject object = new JSONObject(payload);
            String kind = object.optString("kind", "text");
            switch (kind) {
                case "text":
                    String text = object.optString("text", "");
                    return text.trim().isEmpty() ? context.getString(R.string.notification_new) : text;
                case "image":
                case "collage":
                    return context.getString(R.string.notification_photo);
                case "video":
                    return context.getString(R.string.notification_video);
                case "voice":
                    return context.getString(R.string.notification_voice);
                case "audio":
                    return context.getString(R.string.notification_audio);
                case "circle":
                    return context.getString(R.string.notification_circle);
                case "sticker":
                    return context.getString(R.string.notification_sticker);
                default:
                    return context.getString(R.string.notification_new);
            }
        } catch (Exception notJson) {
            return context.getString(R.string.notification_new);
        }
    }

    /**
     * Shows or refreshes the notification of one chat.
     *
     * @param append true for a fresh incoming message, false when only the wording of the
     *               newest entry changes (for example once decryption finally succeeded).
     */
    public static void post(Context context, String chatId, String peerUid, String title,
                            Bitmap avatar, String text, long when, String messageId, boolean append) {
        if (chatId == null) return;
        if (text == null || text.trim().isEmpty()) text = context.getString(R.string.notification_new);
        if (when <= 0) when = System.currentTimeMillis();
        NotificationSounds.ensureChannel(context);

        List<Line> history = readHistory(context, chatId);
        if (append) {
            String lastId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("lastId_" + chatId, null);
            if (messageId != null && messageId.equals(lastId)) {
                if (!history.isEmpty()) history.remove(history.size() - 1);
            }
            history.add(new Line(text, when, false));
            while (history.size() > MAX_HISTORY) history.remove(0);
        } else {
            history.add(new Line(text, when, false));
            while (history.size() > MAX_HISTORY) history.remove(0);
        }
        writeHistory(context, chatId, history);
        if (messageId != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("lastId_" + chatId, messageId).apply();
        }

        Person me = new Person.Builder()
                .setName(context.getString(R.string.app_name))
                .setKey("me")
                .build();
        Person.Builder peerBuilder = new Person.Builder().setName(title == null ? "Tsuyu" : title).setKey(peerUid == null ? "peer" : peerUid);
        if (avatar != null) peerBuilder.setIcon(IconCompat.createWithBitmap(avatar));
        Person peer = peerBuilder.build();

        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(me);
        style.setConversationTitle(title);
        style.setGroupConversation(false);
        for (Line line : history) {
            style.addMessage(new NotificationCompat.MessagingStyle.Message(line.text, line.when, line.fromMe ? me : peer));
        }

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("open_chat_id", chatId);
        if (peerUid != null) open.putExtra("open_peer_uid", peerUid);
        int id = chatId.hashCode();
        int commonFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, open, commonFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationSounds.channelId(context))
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(title == null ? context.getString(R.string.app_name) : title)
                .setContentText(text)
                .setStyle(style)
                .setWhen(when)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(contentIntent);
        if (avatar != null) builder.setLargeIcon(avatar);

        if (peerUid != null) {
            Intent reply = new Intent(context, NotificationReplyReceiver.class)
                    .putExtra("chatId", chatId)
                    .putExtra("peerUid", peerUid);
            int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent replyIntent = PendingIntent.getBroadcast(context, id ^ 0x52504c59, reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | mutable);
            RemoteInput remoteInput = new RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT)
                    .setLabel(context.getString(R.string.chat_message_hint))
                    .build();
            builder.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_send_plane, context.getString(R.string.notification_reply), replyIntent)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .build());

            Intent markRead = new Intent(context, NotificationReplyReceiver.class)
                    .setAction(NotificationReplyReceiver.ACTION_MARK_READ)
                    .putExtra("chatId", chatId)
                    .putExtra("peerUid", peerUid);
            PendingIntent readIntent = PendingIntent.getBroadcast(context, id ^ 0x4d41524b, markRead,
                    PendingIntent.FLAG_UPDATE_CURRENT | commonFlags);
            builder.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_checks, context.getString(R.string.notification_mark_read), readIntent).build());
        }

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (SecurityException missingPermission) {
            // The user revokes POST_NOTIFICATIONS on Android 13+; staying silent is correct.
        }
    }

    /** Drops the notification and its stored conversation for one chat. */
    public static void clear(Context context, String chatId) {
        if (chatId == null) return;
        NotificationManagerCompat.from(context).cancel(chatId.hashCode());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("history_" + chatId).remove("lastId_" + chatId).apply();
    }

    private static List<Line> readHistory(Context context, String chatId) {
        List<Line> lines = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("history_" + chatId, null);
        if (raw == null) return lines;
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item != null) lines.add(new Line(item.optString("text", ""), item.optLong("when", 0), item.optBoolean("mine", false)));
            }
        } catch (Exception ignored) { }
        return lines;
    }

    private static void writeHistory(Context context, String chatId, List<Line> lines) {
        JSONArray array = new JSONArray();
        for (Line line : lines) {
            try {
                array.put(new JSONObject().put("text", line.text).put("when", line.when).put("mine", line.fromMe));
            } catch (Exception ignored) { }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("history_" + chatId, array.toString()).apply();
    }

    /** Chat ids with a live notification, used to refresh wording after decryption. */
    public static Map<String, String> pendingChats(Context context) {
        Map<String, String> chats = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
            if (entry.getKey().startsWith("lastId_") && entry.getValue() instanceof String) {
                chats.put(entry.getKey().substring("lastId_".length()), (String) entry.getValue());
            }
        }
        return chats;
    }
}
