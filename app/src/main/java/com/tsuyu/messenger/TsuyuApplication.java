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
        TsuyuLogs.init(this);
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
     * Приложение больше не закрывается из-за сбоя — вообще ни в одном потоке.
     *
     * <p>Любая ошибка (в главном потоке или в фоне) записывается в журнал целиком, а
     * приложение продолжает работу: если экран «сломался», через пару секунд оно само
     * возвращается к списку чатов. Причину видно в настройках — кнопка «Логи и сбои»,
     * оттуда её можно скопировать или отправить.
     */
    private void installCrashGuard() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            TsuyuLogs.crash(this, "поток " + (thread == null ? "?" : thread.getName()), error);
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
            // Фоновые потоки тоже не имеют права ронять приложение: журнал уже записан,
            // а испорченный поток просто заканчивается.
        });
    }
}
