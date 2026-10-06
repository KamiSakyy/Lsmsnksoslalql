package com.noir.p2pchat.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public final class Ui {
    public static final int BACKGROUND = Color.rgb(9, 10, 14);
    public static final int SURFACE = Color.rgb(18, 20, 27);
    public static final int SURFACE_ALT = Color.rgb(25, 28, 37);
    public static final int STROKE = Color.rgb(41, 45, 57);
    public static final int TEXT = Color.rgb(244, 245, 249);
    public static final int MUTED = Color.rgb(143, 149, 165);
    public static final int ACCENT = Color.rgb(159, 135, 255);
    public static final int GREEN = Color.rgb(109, 226, 174);

    private Ui() { }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable rounded(int color, float radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp);
        if (strokeColor != Color.TRANSPARENT) drawable.setStroke(1, strokeColor);
        return drawable;
    }

    public static TextView text(Context context, String value, float sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    public static TextView label(Context context, String value) {
        TextView view = text(context, value, 11, MUTED);
        view.setLetterSpacing(0.12f);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    public static Button button(Context context, String value, boolean primary) {
        Button button = new Button(context);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? Color.rgb(18, 16, 28) : TEXT);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        button.setBackground(rounded(primary ? ACCENT : SURFACE_ALT, 16, primary ? Color.TRANSPARENT : STROKE));
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        return button;
    }

    public static void margins(View view, int left, int top, int right, int bottom) {
        if (view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams();
            params.setMargins(left, top, right, bottom);
            view.setLayoutParams(params);
        }
    }
}
