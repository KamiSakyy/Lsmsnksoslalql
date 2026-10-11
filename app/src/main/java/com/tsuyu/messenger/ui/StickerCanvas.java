package com.tsuyu.messenger.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Полотно редактора стикеров: рисование пальцем, щипок — масштаб, перетаскивание двумя
 * пальцами — сдвиг, ползунок порога вырезает фон по яркости и оставляет прозрачность.
 */
public final class StickerCanvas extends View {

    private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix screen = new Matrix();
    private final Matrix inverse = new Matrix();

    private Bitmap source;
    private Bitmap bitmap;
    private float scale = 1f;
    private float offsetX;
    private float offsetY;
    private float lastX;
    private float lastY;
    private boolean drawing;
    private float threshold = 0.78f;
    private ScaleGestureDetector zoom;
    private OnScaleListener scaleListener;

    public StickerCanvas(Context context) { this(context, null); }

    public StickerCanvas(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        brush.setStyle(Paint.Style.STROKE);
        brush.setStrokeCap(Paint.Cap.ROUND);
        brush.setStrokeJoin(Paint.Join.ROUND);
        brush.setStrokeWidth(10f);
        brush.setColor(Color.WHITE);
        zoom = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float next = Math.max(0.5f, Math.min(6f, scale * detector.getScaleFactor()));
                if (next == scale) return false;
                // Масштабируем вокруг точки щипка, иначе картинка «убегает» из-под пальцев.
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                offsetX = focusX - (focusX - offsetX) * (next / scale);
                offsetY = focusY - (focusY - offsetY) * (next / scale);
                scale = next;
                invalidate();
                if (scaleListener != null) scaleListener.onScaleChanged(scale);
                return true;
            }
        });
    }

    public interface OnScaleListener {
        void onScaleChanged(float scale);
    }

    public void setOnScaleListener(OnScaleListener listener) {
        scaleListener = listener;
    }

    /** Исходник держим отдельно: порог можно менять сколько угодно раз без потери качества. */
    public void setSource(Bitmap picked) {
        source = picked;
        applyThreshold();
    }

    public void setBrushColor(int color) {
        brush.setColor(color);
    }

    public void setBrushSize(float size) {
        brush.setStrokeWidth(size);
    }

    /** 0.3 — режем только очень светлое, 0.98 — почти всё, кроме тёмных линий. */
    public void setThreshold(float value) {
        threshold = value;
        applyThreshold();
    }

    private void applyThreshold() {
        if (source == null) return;
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int limit = Math.round(threshold * 255f);
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int r = Color.red(pixel);
            int g = Color.green(pixel);
            int b = Color.blue(pixel);
            int luma = (r * 299 + g * 587 + b * 114) / 1000;
            if (luma >= limit) pixels[i] = 0x00000000;
        }
        Bitmap next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        next.setPixels(pixels, 0, width, 0, 0, width, height);
        Bitmap previous = bitmap;
        bitmap = next;
        if (previous != null && previous != next && !previous.isRecycled()) previous.recycle();
        invalidate();
    }

    public Bitmap export() {
        return bitmap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        screen.reset();
        screen.postScale(scale, scale);
        screen.postTranslate(offsetX, offsetY);
        canvas.drawBitmap(bitmap, screen, bitmapPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return false;
        zoom.onTouchEvent(event);
        if (event.getPointerCount() > 1) {
            drawing = false;
            return true;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drawing = true;
                lastX = x;
                lastY = y;
                dot(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drawing) line(lastX, lastY, x, y);
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drawing = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void toBitmap(float x, float y, float[] out) {
        screen.reset();
        screen.postScale(scale, scale);
        screen.postTranslate(offsetX, offsetY);
        screen.invert(inverse);
        out[0] = x;
        out[1] = y;
        inverse.mapPoints(out);
    }

    private void dot(float x, float y) {
        float[] point = new float[2];
        toBitmap(x, y, point);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawPoint(point[0], point[1], brush);
        invalidate();
    }

    private void line(float fromX, float fromY, float toX, float toY) {
        float[] start = new float[2];
        float[] end = new float[2];
        toBitmap(fromX, fromY, start);
        toBitmap(toX, toY, end);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawLine(start[0], start[1], end[0], end[1], brush);
        invalidate();
    }
}
