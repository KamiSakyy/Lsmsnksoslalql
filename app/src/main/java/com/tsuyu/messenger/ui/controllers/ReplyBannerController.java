package com.tsuyu.messenger.ui.controllers;

import android.view.View;
import android.widget.TextView;
import com.tsuyu.messenger.R;

/**
 * Контроллер панели ответа / редактирования над композером (reply_banner).
 */
public final class ReplyBannerController {

    public interface ReplyCancelListener {
        void onCancelReply();
    }

    private final View bannerView;
    private final ReplyCancelListener listener;

    private TextView bannerText;
    private View cancelButton;

    public ReplyBannerController(View bannerView, ReplyCancelListener listener) {
        this.bannerView = bannerView;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        if (bannerView == null) return;
        bannerText = bannerView.findViewById(R.id.reply_banner_text);
        cancelButton = bannerView.findViewById(R.id.reply_cancel);

        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> {
                hide();
                if (listener != null) listener.onCancelReply();
            });
        }
    }

    public void showReply(String quoteText) {
        if (bannerView == null) return;
        if (bannerText != null) bannerText.setText(quoteText);
        bannerView.setVisibility(View.VISIBLE);
    }

    public void hide() {
        if (bannerView != null) bannerView.setVisibility(View.GONE);
    }

    public boolean isVisible() {
        return bannerView != null && bannerView.getVisibility() == View.VISIBLE;
    }
}
