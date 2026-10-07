package com.tsuyu.messenger.data;

public class ChatMessage {
    public String id;
    public String senderUid;
    public String cipher;
    public int signalType;
    public long sentAt;
    public boolean edited;

    public ChatMessage() {}
}
