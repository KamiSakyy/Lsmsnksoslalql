package com.tsuyu.messenger.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.tsuyu.messenger.R;

/**
 * Менеджер диалога блокировки и ввода PIN-кода (dialog_pin).
 */
public final class PinDialogManager {

    public interface PinCallback {
        void onPinEntered(String pin, Dialog dialog, TextView errorView);
    }

    public static void show(Context context, String title, String subtitle, boolean cancellable, PinCallback callback) {
        Dialog dialog = new Dialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_pin, null, false);
        dialog.setContentView(view);
        dialog.setCancelable(cancellable);

        TextView titleView = view.findViewById(R.id.pin_title);
        TextView subtitleView = view.findViewById(R.id.pin_subtitle);
        EditText pinInput = view.findViewById(R.id.pin_input);
        TextView errorView = view.findViewById(R.id.pin_error);
        Button cancelBtn = view.findViewById(R.id.pin_cancel);
        Button confirmBtn = view.findViewById(R.id.pin_confirm);

        if (title != null && titleView != null) titleView.setText(title);
        if (subtitle != null && subtitleView != null) subtitleView.setText(subtitle);

        if (cancelBtn != null) {
            cancelBtn.setVisibility(cancellable ? View.VISIBLE : View.GONE);
            cancelBtn.setOnClickListener(v -> dialog.dismiss());
        }

        if (confirmBtn != null) {
            confirmBtn.setOnClickListener(v -> {
                String pin = pinInput != null ? pinInput.getText().toString().trim() : "";
                if (TextUtils.isEmpty(pin)) {
                    if (errorView != null) {
                        errorView.setText(R.string.pin_wrong);
                        errorView.setVisibility(View.VISIBLE);
                    }
                    return;
                }
                if (callback != null) {
                    callback.onPinEntered(pin, dialog, errorView);
                }
            });
        }

        dialog.show();
    }
}
