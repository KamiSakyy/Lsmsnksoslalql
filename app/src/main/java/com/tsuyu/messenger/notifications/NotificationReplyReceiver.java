package com.tsuyu.messenger.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.core.app.RemoteInput;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatRepository;

public final class NotificationReplyReceiver extends BroadcastReceiver {
    public static final String KEY_TEXT = "tsuyu_reply_text";
    public static final String ACTION_MARK_READ = "com.tsuyu.messenger.MARK_READ";

    @Override
    public void onReceive(Context context, Intent intent) {
        String chatId = intent.getStringExtra("chatId");
        String peerUid = intent.getStringExtra("peerUid");
        if (chatId == null) return;
        if (ACTION_MARK_READ.equals(intent.getAction())) {
            markRead(context, chatId);
            return;
        }
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;
        CharSequence reply = results.getCharSequence(KEY_TEXT);
        if (reply == null || reply.toString().trim().isEmpty() || peerUid == null) return;
        PendingResult pending = goAsync();
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            String uid = FirebaseAuth.getInstance(app).getCurrentUser() == null
                    ? null : FirebaseAuth.getInstance(app).getCurrentUser().getUid();
            if (uid == null) {
                pending.finish();
                return;
            }
            SignalE2ee crypto = new SignalE2ee(context, uid);
            ChatRepository repository = new ChatRepository(FirebaseDatabase.getInstance(app));
            repository.sendText(chatId, uid, peerUid, crypto, reply.toString(), null,
                    new ChatRepository.Result<String>() {
                        @Override public void success(String value) { pending.finish(); }
                        @Override public void error(String message) { pending.finish(); }
                    });
        } catch (Exception e) {
            pending.finish();
        }
    }

    /** Marks the chat read straight from the notification without opening the app. */
    private void markRead(Context context, String chatId) {
        NotificationCenter.clear(context, chatId);
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            String uid = FirebaseAuth.getInstance(app).getCurrentUser() == null
                    ? null : FirebaseAuth.getInstance(app).getCurrentUser().getUid();
            if (uid == null) return;
            FirebaseDatabase.getInstance(app).getReference("chats").child(chatId)
                    .child("receipts").child(uid).setValue(ServerValue.TIMESTAMP);
        } catch (Exception ignored) { }
    }
}
