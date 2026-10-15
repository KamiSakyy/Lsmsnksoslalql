package com.tsuyu.messenger.ui.controllers;

import android.view.View;
import android.widget.TextView;
import com.google.android.material.imageview.ShapeableImageView;
import com.tsuyu.messenger.R;

/**
 * Контроллер верхней панели открытого чата (chat_toolbar).
 */
public final class ChatHeaderController {

    public interface ChatHeaderListener {
        void onBackClicked();
        void onPeerProfileClicked();
        void onVideoCallClicked();
        void onVoiceCallClicked();
        void onMoreOptionsClicked();
    }

    private final View root;
    private final ChatHeaderListener listener;

    private View backButton;
    private View avatarFrame;
    private TextView avatarInitial;
    private ShapeableImageView avatarImage;
    private View onlineDot;
    private TextView peerNameText;
    private TextView peerStatusText;
    private View videoCallButton;
    private View voiceCallButton;
    private View moreButton;

    public ChatHeaderController(View root, ChatHeaderListener listener) {
        this.root = root;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        backButton = root.findViewById(R.id.chat_back);
        avatarFrame = root.findViewById(R.id.chat_peer_avatar_frame);
        avatarInitial = root.findViewById(R.id.chat_peer_initial);
        avatarImage = root.findViewById(R.id.chat_peer_avatar);
        onlineDot = root.findViewById(R.id.chat_peer_online_dot);
        peerNameText = root.findViewById(R.id.chat_peer_name);
        peerStatusText = root.findViewById(R.id.chat_peer_status);
        videoCallButton = root.findViewById(R.id.chat_video_call);
        voiceCallButton = root.findViewById(R.id.chat_voice_call);
        moreButton = root.findViewById(R.id.chat_more);

        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                if (listener != null) listener.onBackClicked();
            });
        }
        View peerHeader = root.findViewById(R.id.chat_peer_header_text);
        if (peerHeader != null) {
            peerHeader.setOnClickListener(v -> {
                if (listener != null) listener.onPeerProfileClicked();
            });
        }
        if (avatarFrame != null) {
            avatarFrame.setOnClickListener(v -> {
                if (listener != null) listener.onPeerProfileClicked();
            });
        }
        if (videoCallButton != null) {
            videoCallButton.setOnClickListener(v -> {
                if (listener != null) listener.onVideoCallClicked();
            });
        }
        if (voiceCallButton != null) {
            voiceCallButton.setOnClickListener(v -> {
                if (listener != null) listener.onVoiceCallClicked();
            });
        }
        if (moreButton != null) {
            moreButton.setOnClickListener(v -> {
                if (listener != null) listener.onMoreOptionsClicked();
            });
        }
    }

    public void setPeerInfo(String name, String status, String initial, boolean isOnline) {
        if (peerNameText != null) peerNameText.setText(name);
        if (peerStatusText != null) peerStatusText.setText(status);
        if (avatarInitial != null) avatarInitial.setText(initial);
        if (onlineDot != null) onlineDot.setVisibility(isOnline ? View.VISIBLE : View.GONE);
    }

    public void setTypingStatus(String status) {
        if (peerStatusText != null) peerStatusText.setText(status);
    }
}
