package com.tsuyu.messenger.ui.dialogs;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.tsuyu.messenger.R;

/**
 * Менеджер модального диалога кастомизации (dialog_customization).
 */
public final class CustomizationDialogManager {

    public interface CustomizationCallback {
        void onSave(int textSize, int iconSize, int mediaScale, boolean bold, boolean italic, boolean sendDark);
        void onOpenWallpaperPicker();
    }

    public static void show(Context context, int currentTextSize, int currentIconSize, int currentMediaScale,
                            boolean currentBold, boolean currentItalic, boolean currentSendDark,
                            CustomizationCallback callback) {
        View body = LayoutInflater.from(context).inflate(R.layout.dialog_customization, null, false);
        Slider size = body.findViewById(R.id.customization_text_size);
        size.setValue(Math.max(12, Math.min(22, currentTextSize)));

        Slider iconSize = body.findViewById(R.id.customization_icon_size);
        iconSize.setValue(Math.max(40, Math.min(64, currentIconSize)));

        Slider mediaSize = body.findViewById(R.id.customization_media_size);
        mediaSize.setValue(Math.max(60, Math.min(100, currentMediaScale)));

        MaterialCheckBox bold = body.findViewById(R.id.customization_bold);
        MaterialCheckBox italic = body.findViewById(R.id.customization_italic);
        bold.setChecked(currentBold);
        italic.setChecked(currentItalic);

        MaterialSwitch sendDark = body.findViewById(R.id.customization_send_dark);
        sendDark.setChecked(currentSendDark);

        TextView textLabel = body.findViewById(R.id.customization_text_label);
        TextView iconLabel = body.findViewById(R.id.customization_icon_label);
        TextView mediaLabel = body.findViewById(R.id.customization_media_label);

        size.addOnChangeListener((slider, value, fromUser) ->
                textLabel.setText(context.getString(R.string.customization_text_size) + ": " + Math.round(value)));
        iconSize.addOnChangeListener((slider, value, fromUser) ->
                iconLabel.setText(context.getString(R.string.customization_icon_size) + ": " + Math.round(value) + " dp"));
        mediaSize.addOnChangeListener((slider, value, fromUser) ->
                mediaLabel.setText(context.getString(R.string.customization_media_size) + ": " + Math.round(value) + "%"));

        textLabel.setText(context.getString(R.string.customization_text_size) + ": " + Math.round(size.getValue()));
        iconLabel.setText(context.getString(R.string.customization_icon_size) + ": " + Math.round(iconSize.getValue()) + " dp");
        mediaLabel.setText(context.getString(R.string.customization_media_size) + ": " + Math.round(mediaSize.getValue()) + "%");

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Кастомизация")
                .setView(body)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, which) -> {
                    if (callback != null) {
                        callback.onSave(
                                Math.round(size.getValue()),
                                Math.round(iconSize.getValue()),
                                Math.round(mediaSize.getValue()),
                                bold.isChecked(),
                                italic.isChecked(),
                                sendDark.isChecked()
                        );
                    }
                })
                .create();

        View wallpaperRow = body.findViewById(R.id.customization_wallpaper);
        if (wallpaperRow != null) {
            wallpaperRow.setOnClickListener(v -> {
                dialog.dismiss();
                if (callback != null) callback.onOpenWallpaperPicker();
            });
        }
        dialog.show();
    }
}
