package com.tsuyu.messenger.data;

import com.tsuyu.messenger.Profile;

public final class ChatSummary {
    public String chatId;
    public String peerUid;
    public Profile peer;
    public long lastAt;
    public String encryptedPreview;
    public boolean typing;
    public boolean online;
    public ChatMessage lastMessage;

    public ChatSummary(String chatId, String peerUid, Profile peer, long lastAt, String encryptedPreview) {
        this.chatId = chatId;
        this.peerUid = peerUid;
        this.peer = peer;
        this.lastAt = lastAt;
        this.encryptedPreview = encryptedPreview;
    }
}
