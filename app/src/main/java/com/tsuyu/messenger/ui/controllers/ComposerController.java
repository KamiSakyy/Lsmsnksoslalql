package com.tsuyu.messenger.ui.controllers;

import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import com.tsuyu.messenger.R;

/**
 * Контроллер нижней панели ввода сообщений (composer_panel).
 */
public final class ComposerController {

    public interface ComposerListener {
        void onSendText(String text);
        void onAttachClicked();
        void onVoiceClicked();
        void onCircleClicked();
        void onStickerClicked();
        void onTyping();
    }

    private final View root;
    private final ComposerListener listener;

    private EditText input;
    private View attachBtn;
    private View voiceBtn;
    private View circleBtn;
    private View stickerBtn;
    private View sendBtn;

    public ComposerController(View root, ComposerListener listener) {
        this.root = root;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        input = root.findViewById(R.id.chat_composer);
        attachBtn = root.findViewById(R.id.chat_attach);
        voiceBtn = root.findViewById(R.id.chat_voice);
        circleBtn = root.findViewById(R.id.chat_circle);
        stickerBtn = root.findViewById(R.id.chat_sticker);
        sendBtn = root.findViewById(R.id.chat_send);

        if (attachBtn != null) {
            attachBtn.setOnClickListener(v -> {
                if (listener != null) listener.onAttachClicked();
            });
        }
        if (voiceBtn != null) {
            voiceBtn.setOnClickListener(v -> {
                if (listener != null) listener.onVoiceClicked();
            });
        }
        if (circleBtn != null) {
            circleBtn.setOnClickListener(v -> {
                if (listener != null) listener.onCircleClicked();
            });
        }
        if (stickerBtn != null) {
            stickerBtn.setOnClickListener(v -> {
                if (listener != null) listener.onStickerClicked();
            });
        }
        if (sendBtn != null) {
            sendBtn.setOnClickListener(v -> {
                if (input != null && listener != null) {
                    String text = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(text)) {
                        listener.onSendText(text);
                        input.setText("");
                    }
                }
            });
        }

        if (input != null) {
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    boolean hasText = s != null && s.toString().trim().length() > 0;
                    updateButtons(hasText);
                    if (hasText && listener != null) {
                        listener.onTyping();
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    private void updateButtons(boolean hasText) {
        if (sendBtn != null) {
            sendBtn.setVisibility(hasText ? View.VISIBLE : View.GONE);
            sendBtn.setEnabled(hasText);
        }
        int mediaVisibility = hasText ? View.GONE : View.VISIBLE;
        if (voiceBtn != null) voiceBtn.setVisibility(mediaVisibility);
        if (circleBtn != null) circleBtn.setVisibility(mediaVisibility);
        if (stickerBtn != null) stickerBtn.setVisibility(mediaVisibility);
    }

    public void clearInput() {
        if (input != null) input.setText("");
    }

    public void setText(String text) {
        if (input != null) {
            input.setText(text);
            if (text != null) input.setSelection(text.length());
        }
    }

    public String getText() {
        return input != null ? input.getText().toString() : "";
    }
}
