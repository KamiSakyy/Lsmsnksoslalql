package com.tsuyu.messenger.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.ColorInt;

public final class Ui {
    public static final int BLACK = Color.rgb(0, 0, 0);
    public static final int HEADER = Color.rgb(13, 13, 13);
    public static final int SURFACE = Color.rgb(20, 20, 22);
    public static final int INPUT = Color.rgb(28, 28, 30);
    public static final int BORDER = Color.rgb(44, 44, 46);
    public static final int WHITE = Color.WHITE;
    public static final int SECONDARY = Color.rgb(142, 142, 147);
    public static final int BLUE = Color.rgb(10, 132, 255);
    public static final int GREEN = Color.rgb(52, 199, 89);
    public static final int RED = Color.rgb(255, 59, 48);

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable shape(@ColorInt int color, Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable bordered(@ColorInt int fill, @ColorInt int stroke, Context context,
                                            float radiusDp, float strokeDp) {
        GradientDrawable drawable = shape(fill, context, radiusDp);
        drawable.setStroke(dp(context, strokeDp), stroke);
        return drawable;
    }

    public static TextView text(Context context, String value, float sizeSp, @ColorInt int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    public static TextView button(Context context, String label, @ColorInt int fill, @ColorInt int foreground,
                                  float radiusDp) {
        TextView view = text(context, label, 15, foreground, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12));
        view.setBackground(shape(fill, context, radiusDp));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) v.setAlpha(.72f);
            if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
        return view;
    }

    public static TextView iconButton(Context context, String icon, @ColorInt int color) {
        TextView view = text(context, icon, 24, color, false);
        view.setGravity(Gravity.CENTER);
        view.setBackground(shape(0x14ffffff, context, 24));
        view.setClickable(true);
        view.setFocusable(true);
        int side = dp(context, 40);
        view.setLayoutParams(new android.widget.LinearLayout.LayoutParams(side, side));
        return view;
    }

    public static EditText input(Context context, String hint, boolean password) {
        EditText edit = new EditText(context);
        edit.setSingleLine(!hint.toLowerCase(java.util.Locale.ROOT).contains("описание"));
        edit.setHint(hint);
        edit.setTextColor(WHITE);
        edit.setHintTextColor(0xff666666);
        edit.setTextSize(15);
        edit.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        edit.setBackground(bordered(0xff161616, 0xff2c2c2c, context, 14, 1));
        edit.setSelectAllOnFocus(false);
        if (password) edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return edit;
    }

    public static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(0xff1c1c1c);
        line.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, dp(context, 1)));
        return line;
    }
}
