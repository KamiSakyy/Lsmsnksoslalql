package com.tsuyu.messenger.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Настройки приложения с памятью на чтение.
 *
 * <p>Раньше настройки читались из файла в горячих местах (отрисовка строки, звук, сеть) —
 * каждое такое чтение заново разбирает файл настроек и тормозит экран. Теперь значения
 * держатся в памяти и обновляются сами, когда что-то сохраняется.
 */
public final class Settings {

    public static final String PREFS = "tsuyu_settings";

    private static final Map<String, String> STRINGS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FLAGS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> NUMBERS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LONGS = new ConcurrentHashMap<>();
    private static SharedPreferences prefs;
    private static String loadedAccount = null;

    private Settings() { }

    /** Подключает файл настроек этого аккаунта и один раз читает его значения. */
    public static synchronized void init(Context context, String accountUid) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadedAccount = accountUid;
        reload();
    }

    /**
     * Подключает файл настроек, если он ещё не подключён. Нужен там, где экран открывается
     * до входа в аккаунт: иначе первое же чтение настроек пошло бы в файл из главного потока.
     */
    public static synchronized void ensure(Context context) {
        if (prefs != null) return;
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        reload();
    }

    /**
     * Перечитывает значения из файла. Нужен после редких записей, сделанных прямо в файл
     * (наборы строк, групповые изменения): память снова совпадает с файлом.
     */
    public static synchronized void reload() {
        if (prefs == null) return;
        STRINGS.clear();
        FLAGS.clear();
        NUMBERS.clear();
        LONGS.clear();
        try {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) STRINGS.put(entry.getKey(), (String) value);
                else if (value instanceof Boolean) FLAGS.put(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) NUMBERS.put(entry.getKey(), (Integer) value);
                else if (value instanceof Long) LONGS.put(entry.getKey(), (Long) value);
            }
        } catch (Throwable ignored) { }
    }

    public static String account() {
        return loadedAccount;
    }

    public static boolean has(String key) {
        return STRINGS.containsKey(key) || FLAGS.containsKey(key)
                || NUMBERS.containsKey(key) || LONGS.containsKey(key);
    }

    public static String getString(String key, String fallback) {
        String cached = STRINGS.get(key);
        if (cached != null) return cached;
        if (prefs == null) return fallback;
        String value = prefs.getString(key, null);
        if (value == null) return fallback;
        STRINGS.put(key, value);
        return value;
    }

    public static boolean getBoolean(String key, boolean fallback) {
        Boolean cached = FLAGS.get(key);
        if (cached != null) return cached;
        if (prefs == null) return fallback;
        boolean value = prefs.getBoolean(key, fallback);
        FLAGS.put(key, value);
        return value;
    }

    public static int getInt(String key, int fallback) {
        Integer cached = NUMBERS.get(key);
        if (cached != null) return cached;
        if (prefs == null) return fallback;
        int value = prefs.getInt(key, fallback);
        NUMBERS.put(key, value);
        return value;
    }

    public static long getLong(String key, long fallback) {
        Long cached = LONGS.get(key);
        if (cached != null) return cached;
        if (prefs == null) return fallback;
        long value = prefs.getLong(key, fallback);
        LONGS.put(key, value);
        return value;
    }

    public static void putString(String key, String value) {
        STRINGS.put(key, value == null ? "" : value);
        if (prefs != null) prefs.edit().putString(key, value).apply();
    }

    public static void putBoolean(String key, boolean value) {
        FLAGS.put(key, value);
        if (prefs != null) prefs.edit().putBoolean(key, value).apply();
    }

    public static void putInt(String key, int value) {
        NUMBERS.put(key, value);
        if (prefs != null) prefs.edit().putInt(key, value).apply();
    }

    public static void putLong(String key, long value) {
        LONGS.put(key, value);
        if (prefs != null) prefs.edit().putLong(key, value).apply();
    }

    public static void remove(String key) {
        STRINGS.remove(key);
        FLAGS.remove(key);
        NUMBERS.remove(key);
        LONGS.remove(key);
        if (prefs != null) prefs.edit().remove(key).apply();
    }

    /** Доступ к самому файлу для редких случаев (например, перечисление ключей). */
    public static SharedPreferences raw() {
        return prefs;
    }
}
