package com.tsuyu.messenger.ui.controllers;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.tsuyu.messenger.R;
import java.util.List;

/**
 * Контроллер горизонтальной строки папок и фильтров (home_filters).
 */
public final class FolderController {

    public interface FolderListener {
        void onFolderSelected(String folderKey);
        void onAddNewFolder();
        void onFolderLongClick(String folderKey);
    }

    private final LinearLayout container;
    private final FolderListener listener;
    private String selectedFolder = "all";

    public FolderController(LinearLayout container, FolderListener listener) {
        this.container = container;
        this.listener = listener;
    }

    public void renderFolders(List<String> userFolders) {
        if (container == null) return;
        container.removeAllViews();
        Context context = container.getContext();

        addChip(context, "all", context.getString(R.string.home_filter_all));
        addChip(context, "unread", context.getString(R.string.home_filter_unread));
        addChip(context, "personal", context.getString(R.string.home_filter_personal));

        if (userFolders != null) {
            for (String folder : userFolders) {
                addChip(context, folder, folder);
            }
        }

        addChip(context, "archive", context.getString(R.string.home_filter_archive));
        addChip(context, "new", "+");
    }

    private void addChip(Context context, String key, String title) {
        TextView chip = new TextView(context);
        chip.setText(title);
        chip.setTextSize(14f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setTag(key);

        int density = (int) context.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 36 * density);
        params.setMarginEnd(8 * density);
        chip.setLayoutParams(params);
        chip.setPadding(16 * density, 0, 16 * density, 0);

        boolean isSelected = key.equals(selectedFolder);
        styleChip(chip, isSelected);

        chip.setOnClickListener(v -> {
            if ("new".equals(key)) {
                if (listener != null) listener.onAddNewFolder();
                return;
            }
            selectedFolder = key;
            updateSelection();
            if (listener != null) listener.onFolderSelected(key);
        });

        if (!"all".equals(key) && !"unread".equals(key) && !"personal".equals(key)
                && !"archive".equals(key) && !"new".equals(key)) {
            chip.setOnLongClickListener(v -> {
                if (listener != null) listener.onFolderLongClick(key);
                return true;
            });
        }

        container.addView(chip);
    }

    private void updateSelection() {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof TextView) {
                String key = (String) child.getTag();
                styleChip((TextView) child, key != null && key.equals(selectedFolder));
            }
        }
    }

    private void styleChip(TextView chip, boolean isSelected) {
        Context context = chip.getContext();
        chip.setBackgroundResource(isSelected ? R.drawable.bg_filter_selected : R.drawable.bg_filter_idle);
        chip.setTextColor(context.getColor(isSelected ? R.color.tsuyu_black : R.color.tsuyu_white));
    }

    public String getSelectedFolder() {
        return selectedFolder;
    }
}
