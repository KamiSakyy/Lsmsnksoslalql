package com.tsuyu.messenger.call;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;

/**
 * Держит разговор живым, пока экран погашен или приложение свёрнуто: обычная
 * foreground-служба с типом «звонок» + блокировка процессора и Wi-Fi на время сессии.
 * Так P2P-канал не засыпает и звонок не обрывается.
 */
public final class CallService extends Service {

    private static volatile boolean running;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, CallService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) {
            // Ниже 26-й версии Android или строгий запрет фона: звонок всё равно ведёт движок.
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        try {
            context.stopService(new Intent(context, CallService.class));
        } catch (Exception ignored) { }
    }

    /** Обновляет текст уведомления, когда изменилось имя собеседника или тип звонка. */
    public static void refresh(Context context) {
        if (!running || context == null) return;
        try {
            CallNotifier.ensureChannels(context);
            androidx.core.app.NotificationManagerCompat.from(context)
                    .notify(CallNotifier.ID_ACTIVE, CallNotifier.ongoing(context));
        } catch (SecurityException ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        CallNotifier.ensureChannels(this);
        startForeground(CallNotifier.ID_ACTIVE, CallNotifier.ongoing(this));
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private void acquireLocks() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tsuyu:call");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(6 * 60 * 60 * 1000L);
            }
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tsuyu:call-wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        wakeLock = null;
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        wifiLock = null;
    }

    @Override
    public void onDestroy() {
        running = false;
        releaseLocks();
        CallNotifier.stopRinging();
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
