package com.tsuyu.messenger.ui.dialogs;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.tsuyu.Lsmsnksoslalql.R;

/**
 * Менеджер диалога настроек конфиденциальности (dialog_privacy).
 */
public final class PrivacyDialogManager {

    public interface PrivacyCallback {
        void onSave(boolean ghostMode, int messagesRule, int lastSeenRule, int photoRule, int aboutRule, int nameRule);
    }

    public static void show(Context context, boolean currentGhost,
                            int curMessages, int curLastSeen, int curPhoto, int curAbout, int curName,
                            PrivacyCallback callback) {
        View body = LayoutInflater.from(context).inflate(R.layout.dialog_privacy, null, false);
        MaterialSwitch ghostSwitch = body.findViewById(R.id.privacy_ghost);
        if (ghostSwitch != null) ghostSwitch.setChecked(currentGhost);

        String[] options = new String[]{"Все", "Только контакты", "Никто"};

        Spinner spMessages = setupSpinner(context, body, R.id.privacy_messages, options, curMessages);
        Spinner spLastSeen = setupSpinner(context, body, R.id.privacy_last_seen, options, curLastSeen);
        Spinner spPhoto = setupSpinner(context, body, R.id.privacy_photo, options, curPhoto);
        Spinner spAbout = setupSpinner(context, body, R.id.privacy_about, options, curAbout);
        Spinner spName = setupSpinner(context, body, R.id.privacy_display_name, options, curName);

        new AlertDialog.Builder(context)
                .setTitle("Конфиденциальность")
                .setView(body)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    if (callback != null) {
                        callback.onSave(
                                ghostSwitch != null && ghostSwitch.isChecked(),
                                spMessages != null ? spMessages.getSelectedItemPosition() : 0,
                                spLastSeen != null ? spLastSeen.getSelectedItemPosition() : 0,
                                spPhoto != null ? spPhoto.getSelectedItemPosition() : 0,
                                spAbout != null ? spAbout.getSelectedItemPosition() : 0,
                                spName != null ? spName.getSelectedItemPosition() : 0
                        );
                    }
                })
                .show();
    }

    private static Spinner setupSpinner(Context context, View root, int id, String[] items, int selectedPos) {
        Spinner spinner = root.findViewById(id);
        if (spinner == null) return null;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.row_spinner_item, items);
        adapter.setDropDownViewResource(R.layout.row_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (selectedPos >= 0 && selectedPos < items.length) {
            spinner.setSelection(selectedPos);
        }
        return spinner;
    }
}
