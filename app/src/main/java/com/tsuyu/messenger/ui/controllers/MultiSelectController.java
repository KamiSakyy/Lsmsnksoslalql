package com.tsuyu.messenger.ui.controllers;

import android.view.View;
import android.widget.TextView;
import com.tsuyu.messenger.R;

/**
 * Контроллер нижней панели мультивыбора сообщений (multi_select_bar).
 */
public final class MultiSelectController {

    public interface MultiSelectListener {
        void onDeleteSelected();
        void onForwardSelected();
        void onCancelSelection();
    }

    private final View barView;
    private final MultiSelectListener listener;

    private TextView countText;
    private View deleteBtn;
    private View forwardBtn;
    private View cancelBtn;

    public MultiSelectController(View barView, MultiSelectListener listener) {
        this.barView = barView;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        if (barView == null) return;
        countText = barView.findViewById(R.id.multi_select_count);
        deleteBtn = barView.findViewById(R.id.multi_select_delete);
        forwardBtn = barView.findViewById(R.id.multi_select_forward);
        cancelBtn = barView.findViewById(R.id.multi_select_cancel);

        if (deleteBtn != null) {
            deleteBtn.setOnClickListener(v -> {
                if (listener != null) listener.onDeleteSelected();
            });
        }
        if (forwardBtn != null) {
            forwardBtn.setOnClickListener(v -> {
                if (listener != null) listener.onForwardSelected();
            });
        }
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(v -> {
                if (listener != null) listener.onCancelSelection();
            });
        }
    }

    public void show(int selectedCount) {
        if (barView == null) return;
        if (countText != null) {
            countText.setText(barView.getContext().getString(R.string.multi_select_count, selectedCount));
        }
        barView.setVisibility(View.VISIBLE);
    }

    public void hide() {
        if (barView != null) barView.setVisibility(View.GONE);
    }

    public boolean isVisible() {
        return barView != null && barView.getVisibility() == View.VISIBLE;
    }
}
