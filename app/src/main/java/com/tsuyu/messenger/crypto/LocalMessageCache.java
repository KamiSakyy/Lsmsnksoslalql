package com.tsuyu.messenger.crypto;

import android.content.Context;

public final class LocalMessageCache {
    private final SecurePrefs prefs;

    public LocalMessageCache(Context context, String accountUid) {
        prefs = new SecurePrefs(context, accountUid);
    }

    public synchronized void put(String chatId, String messageId, String payload) {
        String key = key(chatId, messageId);
        if (payload != null && payload.length() > 200_000) {
            prefs.remove(key);
            prefs.putLarge(key, payload);
        } else {
            prefs.removeLarge(key);
            prefs.put(key, payload == null ? "" : payload);
        }
    }

    public synchronized String get(String chatId, String messageId) {
        String key = key(chatId, messageId);
        String small = prefs.get(key);
        return small == null ? prefs.getLarge(key) : small;
    }

    private static String key(String chatId, String messageId) {
        return "localmsg." + chatId + "." + messageId;
    }
}
