package com.tsuyu.messenger.ui.controllers;

import android.view.View;
import android.widget.TextView;
import com.tsuyu.messenger.R;

/**
 * Контроллер плашки закреплённого сообщения (chat_pinned_bar).
 */
public final class PinnedBarController {

    public interface PinnedBarListener {
        void onPinnedMessageClicked();
        void onUnpinClicked();
    }

    private final View barView;
    private final PinnedBarListener listener;

    private TextView messageText;
    private View closeButton;

    public PinnedBarController(View barView, PinnedBarListener listener) {
        this.barView = barView;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        if (barView == null) return;
        messageText = barView.findViewById(R.id.pinned_message_text);
        closeButton = barView.findViewById(R.id.pinned_message_close);

        barView.setOnClickListener(v -> {
            if (listener != null) listener.onPinnedMessageClicked();
        });

        if (closeButton != null) {
            closeButton.setOnClickListener(v -> {
                if (listener != null) listener.onUnpinClicked();
            });
        }
    }

    public void showPinned(String preview) {
        if (barView == null) return;
        if (messageText != null) messageText.setText(preview);
        barView.setVisibility(View.VISIBLE);
    }

    public void hide() {
        if (barView != null) barView.setVisibility(View.GONE);
    }
}
