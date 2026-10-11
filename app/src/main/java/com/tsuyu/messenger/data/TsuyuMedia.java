package com.tsuyu.messenger.data;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

/**
 * Кеш вложений на диске — как в Telegram.
 *
 * <p>Фото, кружочки, видео, голосовые и стикеры лежат обычными файлами в папке приложения,
 * а в базе и в памяти остаётся только короткая ссылка вида {@code file:8f3c....bin}.
 * Поэтому переписка открывается мгновенно, повторно вложение не расшифровывается и не
 * занимает память: телефон держит в работе лишь те картинки, которые видно на экране.
 */
public final class TsuyuMedia {

    private static final String PREFIX = "file:";
    /** Всё, что больше этого размера, уходит файлом на диск. */
    private static final int MIN_SPILL = 192;
    private static final String DIR_NAME = "tsuyu-media";

    private TsuyuMedia() { }

    /** Папка кеша вложений. */
    public static File dir(Context context) {
        File target = new File(context.getFilesDir(), DIR_NAME);
        if (!target.exists()) target.mkdirs();
        return target;
    }

    /**
     * Убирает большие вложения из текста сообщения в файлы. Возвращает короткий вариант:
     * его и хранить, и показывать дешево. Если вложение уже файлом — ничего не меняем.
     */
    public static String spill(Context context, String payload) {
        if (context == null || payload == null || payload.length() < MIN_SPILL) return payload;
        if (!payload.contains("\"data\"")) return payload;
        try {
            JSONObject json = new JSONObject(payload);
            boolean changed = spillField(context, json, "data");
            JSONArray items = json.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null && spillField(context, item, "data")) changed = true;
                }
            }
            return changed ? json.toString() : payload;
        } catch (Throwable ignored) {
            return payload;
        }
    }

    private static boolean spillField(Context context, JSONObject json, String field) {
        String value = json.optString(field, null);
        if (value == null || value.length() < MIN_SPILL || value.startsWith(PREFIX)) return false;
        try {
            byte[] bytes = Base64.decode(value, Base64.DEFAULT);
            if (bytes == null || bytes.length == 0) return false;
            File target = new File(dir(context), name(bytes));
            if (!target.exists() || target.length() != bytes.length) {
                try (FileOutputStream output = new FileOutputStream(target, false)) {
                    output.write(bytes);
                    output.flush();
                }
            }
            json.put(field, PREFIX + target.getName());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Одно значение (например аватар) — файлом в кеш, обратно короткая ссылка. */
    public static String spillValue(Context context, String value) {
        if (context == null || value == null || value.length() < MIN_SPILL || value.startsWith(PREFIX)) {
            return value;
        }
        File target = file(context, value);
        return target == null ? value : PREFIX + target.getName();
    }

    /** Данные вложения: сначала файл из кеша, иначе — то, что пришло из сети. */
    public static byte[] bytes(Context context, String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            if (value.startsWith(PREFIX)) {
                File target = new File(dir(context), value.substring(PREFIX.length()));
                if (!target.exists()) return null;
                try (RandomAccessFile reader = new RandomAccessFile(target, "r")) {
                    byte[] raw = new byte[(int) reader.length()];
                    reader.readFully(raw);
                    return raw;
                }
            }
            return Base64.decode(value, Base64.DEFAULT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Вложение как файл: если оно пришло из сети — один раз кладём в кеш. */
    public static File file(Context context, String value) {
        if (context == null || value == null || value.isEmpty()) return null;
        try {
            if (value.startsWith(PREFIX)) {
                File target = new File(dir(context), value.substring(PREFIX.length()));
                return target.exists() && target.length() > 0 ? target : null;
            }
            byte[] raw = Base64.decode(value, Base64.DEFAULT);
            if (raw == null || raw.length == 0) return null;
            File target = new File(dir(context), name(raw));
            if (!target.exists() || target.length() != raw.length) {
                try (FileOutputStream output = new FileOutputStream(target, false)) {
                    output.write(raw);
                    output.flush();
                }
            }
            return target;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Сколько места занимает кеш вложений. */
    public static long size(Context context) {
        long total = 0L;
        try {
            File[] files = dir(context).listFiles();
            if (files != null) for (File item : files) total += item.length();
        } catch (Throwable ignored) { }
        return total;
    }

    /** Полная очистка кеша вложений. */
    public static void clear(Context context) {
        try {
            File[] files = dir(context).listFiles();
            if (files != null) for (File item : files) item.delete();
        } catch (Throwable ignored) { }
    }

    private static String name(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (int i = 0; i < 12 && i < hash.length; i++) {
                out.append(String.format(java.util.Locale.ROOT, "%02x", hash[i]));
            }
            out.append('.').append(bytes.length).append(".bin");
            return out.toString();
        } catch (Throwable ignored) {
            return "media-" + bytes.length + "-" + System.nanoTime() + ".bin";
        }
    }
}
