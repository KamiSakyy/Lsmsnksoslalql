package com.tsuyu.messenger;

import android.app.Application;

import com.google.firebase.FirebaseApp;
import com.tsuyu.messenger.call.NetworkPriority;
import com.tsuyu.messenger.data.ChatRepository;
import com.tsuyu.messenger.notifications.FcmPushSender;
import com.tsuyu.messenger.notifications.FirebaseMessagingSetup;

public final class TsuyuApplication extends Application {
    /**
     * Запоминаем причину любого сбоя на устройстве: даже если приложение закрылось,
     * в следующий запуск видно, что именно случилось (файл tsuyu_crash/last).
     */
    private void recordCrashes() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                java.io.StringWriter writer = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(writer));
                String trace = writer.toString();
                if (trace.length() > 3500) trace = trace.substring(0, 3500);
                getSharedPreferences("tsuyu_crash", MODE_PRIVATE).edit()
                        .putString("last", trace)
                        .putLong("at", System.currentTimeMillis()).apply();
            } catch (Throwable ignored) {
                // Само сохранение причины не имеет права ничего ломать.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        recordCrashes();
        FirebaseApp app = FirebaseBootstrap.initialize(this);
        FirebaseMessagingSetup.registerTokenListener(app);
        // Приоритет мобильного трафика: обычный мобильный интернет вместо Wi-Fi.
        NetworkPriority.refresh(this);
        // Every committed message pushes straight to the peer's devices: phone to phone
        // through FCM HTTP v1, with no relay server in the middle.
        ChatRepository.setPushDispatcher((chatId, senderUid, recipientUid, messageId) ->
                FcmPushSender.pushMessage(this, chatId, senderUid, recipientUid, messageId));
    }
}
