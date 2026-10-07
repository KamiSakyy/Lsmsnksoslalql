package com.tsuyu.messenger.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.RemoteInput;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatRepository;

public final class NotificationReplyReceiver extends BroadcastReceiver {
    public static final String KEY_TEXT = "tsuyu_reply_text";

    @Override
    public void onReceive(Context context, Intent intent) {
        RemoteInput input = RemoteInput.getResultsFromIntent(intent);
        if (input == null) return;
        CharSequence reply = input.getCharSequence(KEY_TEXT);
        String chatId = intent.getStringExtra("chatId");
        String peerUid = intent.getStringExtra("peerUid");
        if (reply == null || reply.toString().trim().isEmpty() || chatId == null || peerUid == null) return;
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
}
