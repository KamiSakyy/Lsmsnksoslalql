package com.tsuyu.messenger.data;

public class ChatMessage {
    public String id;
    public String senderUid;
    public String cipher;
    public int signalType;
    public long sentAt;
    public boolean edited;
    /** Opaque reconciliation key; contains no message plaintext. */
    public String clientId;

    // These fields are transient: never serialize optimistic plaintext or UI state to RTDB.
    public transient boolean pending;
    public transient boolean failed;
    public transient String localChatId;
    public transient String localPayload;

    public ChatMessage() {}
}
