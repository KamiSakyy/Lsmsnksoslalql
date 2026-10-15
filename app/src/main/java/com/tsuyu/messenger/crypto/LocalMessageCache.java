package com.tsuyu.messenger.crypto;

import android.content.Context;

public final class LocalMessageCache {
    private final SecurePrefs prefs;
    private final Context context;

    public LocalMessageCache(Context context, String accountUid) {
        prefs = new SecurePrefs(context, accountUid);
        this.context = context.getApplicationContext();
    }

    public synchronized void put(String chatId, String messageId, String payload) {
        // Вложения уходят файлами в кеш, здесь остаётся короткая ссылка.
        payload = com.tsuyu.messenger.data.TsuyuMedia.spill(context, payload);
        String key = key(chatId, messageId);
        try {
            if (payload != null && payload.length() > 200_000) {
                prefs.remove(key);
                prefs.putLarge(key, payload);
            } else {
                prefs.removeLarge(key);
                // Без ожидания диска: раньше главный поток стоял на записи.
                prefs.putFast(key, payload == null ? "" : payload);
            }
        } catch (Throwable ignored) {
            // Кэш — не повод ломать отправку: текст всё равно уйдёт в сеть.
        }
    }

    public synchronized String get(String chatId, String messageId) {
        String key = key(chatId, messageId);
        try {
            String small = prefs.get(key);
            return small == null ? prefs.getLarge(key) : small;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String key(String chatId, String messageId) {
        return "localmsg." + chatId + "." + messageId;
    }
}
