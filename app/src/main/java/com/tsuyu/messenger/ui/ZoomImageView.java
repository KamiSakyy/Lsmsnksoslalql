package com.tsuyu.messenger.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

public final class ZoomImageView extends AppCompatImageView {
    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private float scale = 1f;
    private float fitScale = 1f;

    public ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        setImageMatrix(matrix);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float next = Math.max(fitScale, Math.min(fitScale * 5f, scale * detector.getScaleFactor()));
                float factor = next / scale;
                scale = next;
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                setImageMatrix(matrix);
                return true;
            }
        });
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetToFit();
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetToFit);
    }

    private void resetToFit() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) return;
        float imageWidth = drawable.getIntrinsicWidth();
        float imageHeight = drawable.getIntrinsicHeight();
        if (imageWidth <= 0 || imageHeight <= 0) return;
        fitScale = Math.min((float) getWidth() / imageWidth, (float) getHeight() / imageHeight);
        scale = fitScale;
        float dx = (getWidth() - imageWidth * fitScale) / 2f;
        float dy = (getHeight() - imageHeight * fitScale) / 2f;
        matrix.setScale(fitScale, fitScale);
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX(); lastY = event.getY(); dragging = true; return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && scale > fitScale * 1.01f && event.getPointerCount() == 1) {
                    matrix.postTranslate(event.getX() - lastX, event.getY() - lastY);
                    setImageMatrix(matrix);
                    lastX = event.getX(); lastY = event.getY();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false; return true;
            default: return true;
        }
    }
}
