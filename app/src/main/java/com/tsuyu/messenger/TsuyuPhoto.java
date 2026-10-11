package com.tsuyu.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * Фотографии в приложении показываются уменьшенными.
 *
 * <p>Большой снимок (например 4000x3000) в полном размере занимает в памяти около 48 МБ —
 * телефон не выдерживает, и приложение закрывается именно в тот момент, когда фото попадает
 * на экран: в переписке, в обоих окнах, на обоях чата. Здесь картинка уменьшается ещё до
 * создания, поэтому память остаётся в порядке, а фото видно.
 */
public final class TsuyuPhoto {

    private TsuyuPhoto() { }

    /** Уменьшает фото так, чтобы большая сторона была не больше maxSide. */
    public static Bitmap decodeScaled(byte[] bytes, int maxSide) {
        return decodeScaled(bytes, maxSide, false);
    }

    /**
     * То же, но для чистого показа на экране (списки и пузыри): кадр хранится в памяти
     * видеокарты, поэтому прокрутка не гоняет его через обычную память. Там, где кадр
     * потом сохраняют или рисуют на холсте, этот режим не используется — для таких мест
     * остаётся обычный кадр (иначе сохранение картинки невозможно).
     */
    public static Bitmap decodeScaled(byte[] bytes, int maxSide, boolean forDisplay) {
        if (bytes == null || bytes.length == 0) return null;
        int side = maxSide <= 0 ? 1280 : maxSide;
        try {
            BitmapFactory.Options size = new BitmapFactory.Options();
            size.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, size);
            int width = size.outWidth;
            int height = size.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = 1;
            while (width / (sample * 2) >= side || height / (sample * 2) >= side) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = forDisplay && android.os.Build.VERSION.SDK_INT >= 26
                    ? Bitmap.Config.HARDWARE : Bitmap.Config.RGB_565;
            try {
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            } catch (Throwable hardwareFailed) {
                if (!forDisplay) throw hardwareFailed;
                // Телефон или прошивка не отдали кадр в память видеокарты — берём обычный.
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                try {
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                } catch (OutOfMemoryError heavy) {
                // Совсем тяжёлый случай: уменьшаем ещё сильнее и пробуем снова.
                options.inSampleSize = sample * 4;
                    try {
                        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
