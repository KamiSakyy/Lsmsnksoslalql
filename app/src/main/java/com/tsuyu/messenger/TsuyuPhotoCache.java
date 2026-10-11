package com.tsuyu.messenger;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Маленькие готовые кадры — только для того, что видно на экране.
 *
 * <p>Сами вложения живут файлами в кеше на диске (как в Telegram), а здесь хранятся лишь
 * уже уменьшенные картинки, и размер памяти жёстко ограничен: поэтому список чатов и
 * переписка прокручиваются без «залипаний», и память не растёт.
 */
public final class TsuyuPhotoCache {

    /** Не больше 6 МБ готовых кадров — этого хватает на экран с запасом. */
    private static final int MEDIA_BYTES = 6 * 1024 * 1024;
    /** Аватары маленькие: 96 штук занимают около трёх мегабайт. */
    private static final int AVATAR_COUNT = 96;

    private static final android.util.LruCache<String, Bitmap> MEDIA =
            new android.util.LruCache<String, Bitmap>(MEDIA_BYTES) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };

    private static final android.util.LruCache<String, Bitmap> AVATARS =
            new android.util.LruCache<String, Bitmap>(AVATAR_COUNT) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };

    private TsuyuPhotoCache() { }

    /** Готовый кадр, если он уже подготовлен: тогда показываем сразу, без всякой работы. */
    public static Bitmap peek(String value, int maxSide) {
        if (value == null || value.isEmpty()) return null;
        return MEDIA.get(key(value, maxSide));
    }

    /** Кадр вложения: берём из кеша файлом с диска и уменьшаем один раз на весь сеанс. */
    public static Bitmap media(Context context, String value, int maxSide) {
        if (value == null || value.isEmpty()) return null;
        String key = key(value, maxSide);
        Bitmap cached = MEDIA.get(key);
        if (cached != null) return cached;
        byte[] bytes = com.tsuyu.messenger.data.TsuyuMedia.bytes(context, value);
        Bitmap decoded = TsuyuPhoto.decodeScaled(bytes, maxSide, true);
        if (decoded != null) MEDIA.put(key, decoded);
        return decoded;
    }

    /** Готовый аватар, если он уже подготовлен. */
    public static Bitmap avatarPeek(String value) {
        if (value == null || value.isEmpty()) return null;
        return AVATARS.get(key(value, 160));
    }

    /** Аватар: уменьшенный кадр, чтобы прокрутка списка не спотыкалась. */
    public static Bitmap avatar(Context context, String value) {
        if (value == null || value.isEmpty()) return null;
        String key = key(value, 160);
        Bitmap cached = AVATARS.get(key);
        if (cached != null) return cached;
        byte[] bytes = com.tsuyu.messenger.data.TsuyuMedia.bytes(context, value);
        Bitmap decoded = TsuyuPhoto.decodeScaled(bytes, 160, true);
        if (decoded != null) AVATARS.put(key, decoded);
        return decoded;
    }

    public static void clear() {
        MEDIA.evictAll();
        AVATARS.evictAll();
    }

    private static String key(String value, int maxSide) {
        // Ссылка на файл в кеше уникальна сама по себе; для base64 хватает хеша со длиной.
        if (value.startsWith("file:")) return maxSide + ":" + value;
        return maxSide + ":" + Integer.toHexString(value.hashCode()) + ":" + value.length();
    }
}
