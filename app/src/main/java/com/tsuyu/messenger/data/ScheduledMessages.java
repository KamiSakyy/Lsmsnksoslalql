package com.tsuyu.messenger.data;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Отложенные сообщения без сервера: очередь живёт в самом телефоне, а будильник
 * системы поднимает отправителя в назначенную минуту.
 */
public final class ScheduledMessages {

    public static final String ACTION_SEND_DUE = "com.tsuyu.messenger.SEND_DUE";
    private static final String PREFS = "tsuyu_scheduled";
    private static final String KEY = "queue";
    private static final int REQUEST = 9902;

    private ScheduledMessages() { }

    /** Одно отложенное сообщение: тот же шифрованный payload, что уходит при обычной отправке. */
    public static final class Item {
        public String chatId;
        public String peerUid;
        public String payload;
        public long at;

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("chatId", chatId);
                object.put("peerUid", peerUid);
                object.put("payload", payload);
                object.put("at", at);
            } catch (Exception ignored) { }
            return object;
        }

        public static Item fromJson(JSONObject object) {
            Item item = new Item();
            item.chatId = object.optString("chatId", null);
            item.peerUid = object.optString("peerUid", null);
            item.payload = object.optString("payload", null);
            item.at = object.optLong("at", 0L);
            return item;
        }
    }

    public static void schedule(Context context, String chatId, String peerUid, String payload, long at) {
        if (context == null || chatId == null || peerUid == null || payload == null) return;
        Item item = new Item();
        item.chatId = chatId;
        item.peerUid = peerUid;
        item.payload = payload;
        item.at = at;
        List<Item> queue = pending(context);
        queue.add(item);
        save(context, queue);
        armNext(context);
    }

    public static List<Item> pending(Context context) {
        List<Item> items = new ArrayList<>();
        if (context == null) return items;
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        if (raw == null || raw.isEmpty()) return items;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) items.add(Item.fromJson(object));
            }
        } catch (Exception ignored) { }
        return items;
    }

    public static void save(Context context, List<Item> queue) {
        if (context == null) return;
        JSONArray array = new JSONArray();
        for (Item item : queue) array.put(item.toJson());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, array.toString()).apply();
    }

    /** Будильник на ближайшее сообщение; нет очереди — будильник снимается. */
    public static void armNext(Context context) {
        if (context == null) return;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        long soonest = Long.MAX_VALUE;
        for (Item item : pending(context)) soonest = Math.min(soonest, item.at);
        PendingIntent intent = alarmIntent(context);
        if (soonest == Long.MAX_VALUE) {
            manager.cancel(intent);
            return;
        }
        long when = Math.max(soonest, System.currentTimeMillis() + 1000L);
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
        } catch (Exception ignored) { }
    }

    public static PendingIntent alarmIntent(Context context) {
        Intent intent = new Intent(context, com.tsuyu.messenger.notifications.ScheduledSendReceiver.class)
                .setAction(ACTION_SEND_DUE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST, intent, flags);
    }
}
