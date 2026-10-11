package com.tsuyu.messenger;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.firebase.FirebaseApp;
import com.tsuyu.messenger.call.NetworkPriority;
import com.tsuyu.messenger.data.ChatRepository;
import com.tsuyu.messenger.notifications.FcmPushSender;
import com.tsuyu.messenger.notifications.FirebaseMessagingSetup;

public final class TsuyuApplication extends Application {

    private static final String CRASH_PREFS = "tsuyu_crash";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashGuard();
        FirebaseApp app = FirebaseBootstrap.initialize(this);
        FirebaseMessagingSetup.registerTokenListener(app);
        // Приоритет мобильного трафика: обычный мобильный интернет вместо Wi-Fi.
        NetworkPriority.refresh(this);
        // Every committed message pushes straight to the peer's devices: phone to phone
        // through FCM HTTP v1, with no relay server in the middle.
        ChatRepository.setPushDispatcher((chatId, senderUid, recipientUid, messageId) ->
                FcmPushSender.pushMessage(this, chatId, senderUid, recipientUid, messageId));
    }

    /**
     * Приложение больше не закрывается из-за сбоя.
     *
     * <p>Любая ошибка записывается (её потом видно прямо в настройках, там же кнопка
     * «Копировать»), а главный поток продолжает работу: если экран «сломался», через
     * пару секунд приложение само возвращается к списку чатов. Так «вылетов» нет даже
     * при ошибке в отдельном сообщении или в новом оформлении чата.
     */
    private void installCrashGuard() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            saveCrash(error);
            boolean mainThread = Looper.getMainLooper() != null
                    && thread == Looper.getMainLooper().getThread();
            if (mainThread) {
                try {
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(() -> Toast.makeText(getApplicationContext(),
                            R.string.crash_swallowed, Toast.LENGTH_LONG).show());
                    handler.postDelayed(() -> {
                        // Экран мог не успеть отрисоваться: мягко возвращаемся к списку чатов.
                        if (!MainActivity.visible) {
                            Intent intent = new Intent(this, MainActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            try {
                                startActivity(intent);
                            } catch (Throwable ignored) { }
                        }
                    }, 1500L);
                } catch (Throwable ignored) { }
                // Важно: не убиваем процесс — приложение продолжает работать.
                return;
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Сбой сохраняется на устройстве: причина видна в настройках и после перезапуска. */
    private void saveCrash(Throwable error) {
        try {
            java.io.StringWriter writer = new java.io.StringWriter();
            if (error != null) error.printStackTrace(new java.io.PrintWriter(writer));
            String trace = writer.toString();
            if (trace.length() > 4000) trace = trace.substring(0, 4000);
            getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit()
                    .putString("last", trace)
                    .putLong("at", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
            // Само сохранение причины не имеет права ничего ломать.
        }
    }
}
