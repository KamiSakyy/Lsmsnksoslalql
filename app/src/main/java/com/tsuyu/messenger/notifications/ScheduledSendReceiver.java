package com.tsuyu.messenger.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatRepository;
import com.tsuyu.messenger.data.ScheduledMessages;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Отправляет отложенные сообщения, когда сработал будильник: шифрует тем же
 * Signal-протоколом и пишет в базу, как обычная отправка из приложения.
 */
public final class ScheduledSendReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        // Отправка идёт в общем фоновом пуле: свои потоки на каждое срабатывание не нужны.
        com.tsuyu.messenger.util.Background.io().execute(() -> flush(app));
    }

    /** Отправляет всё, что уже пора, и переносит будильник на следующее сообщение. */
    public static void flush(Context context) {
        if (context == null) return;
        try {
            FirebaseApp.getInstance();
        } catch (IllegalStateException notReady) {
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        List<ScheduledMessages.Item> queue = ScheduledMessages.pending(context);
        if (queue.isEmpty()) {
            ScheduledMessages.armNext(context);
            return;
        }
        SignalE2ee crypto = new SignalE2ee(context, user.getUid());
        ChatRepository repository = new ChatRepository(FirebaseDatabase.getInstance());
        long now = System.currentTimeMillis();
        List<ScheduledMessages.Item> remaining = new ArrayList<>();
        boolean sentAny = false;
        for (ScheduledMessages.Item item : queue) {
            if (item.at > now) {
                remaining.add(item);
                continue;
            }
            try {
                repository.sendPayload(item.chatId, user.getUid(), item.peerUid, crypto,
                        item.payload.getBytes(StandardCharsets.UTF_8), null,
                        new ChatRepository.Result<String>() {
                            @Override public void success(String value) { }
                            @Override public void error(String message) { }
                        });
                sentAny = true;
            } catch (Exception ignored) {
                remaining.add(item);
            }
        }
        ScheduledMessages.save(context, remaining);
        ScheduledMessages.armNext(context);
        if (sentAny) {
            NotificationCenter.post(context, "tsuyu_scheduled", null,
                    context.getString(com.tsuyu.messenger.R.string.app_name), null,
                    context.getString(com.tsuyu.messenger.R.string.schedule_sent),
                    System.currentTimeMillis(), null, false);
        }
    }
}
