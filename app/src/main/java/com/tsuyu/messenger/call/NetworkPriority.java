package com.tsuyu.messenger.call;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

/**
 * Приоритет мобильного трафика: приложение и звонки идут через сотовую сеть,
 * даже когда телефон подключён к Wi-Fi.
 *
 * <p>Делается это системным привязыванием процесса к мобильной сети
 * ({@code bindProcessToNetwork}): после него все сокеты приложения — включая медиа-сокеты
 * WebRTC и Realtime Database — уходят через «обычный мобильный интернет». Wi-Fi остаётся
 * резервом: если мобильная сеть пропала или не подтверждена, привязка снимается сама.
 */
public final class NetworkPriority {

    public static final String PREFS = "tsuyu_settings";
    public static final String KEY = "mobile_priority";

    private static final Object LOCK = new Object();
    private static ConnectivityManager.NetworkCallback watcher;
    private static Network bound;

    private NetworkPriority() { }

    /** По умолчанию мобильный трафик в приоритете — так просил владелец приложения. */
    public static boolean enabled(Context context) {
        if (context == null) return true;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    public static void setEnabled(Context context, boolean value) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY, value).apply();
        if (value) refresh(context);
        else release(context);
    }

    /** Мобильная сеть, которой можно пользоваться: есть интернет и он подтверждён. */
    private static Network mobile(Context context) {
        ConnectivityManager manager = connectivity(context);
        if (manager == null) return null;
        try {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null) continue;
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue;
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue;
                return network;
            }
        } catch (Exception ignored) {
            // Некоторые прошивки не отдают список сетей без дополнительных прав.
        }
        return null;
    }

    private static ConnectivityManager connectivity(Context context) {
        if (context == null) return null;
        return (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Привязать процесс к мобильной сети, если она есть и приоритет включён. */
    public static boolean refresh(Context context) {
        if (context == null) return false;
        final Context app = context.getApplicationContext();
        ConnectivityManager manager = connectivity(app);
        if (manager == null) return false;
        synchronized (LOCK) {
            if (!enabled(app)) {
                releaseLocked(manager);
                return false;
            }
            Network target = mobile(app);
            if (target == null) {
                // Мобильной сети нет или она не подтверждена: остаёмся на том, что есть.
                releaseLocked(manager);
                return false;
            }
            if (bound != null && bound.equals(target)) {
                watch(app, manager);
                return true;
            }
            boolean ok;
            try {
                ok = manager.bindProcessToNetwork(target);
            } catch (Exception ignored) {
                ok = false;
            }
            if (ok) {
                bound = target;
                watch(app, manager);
            }
            return ok;
        }
    }

    /** Снять привязку: процесс возвращается к обычному выбору сети (Wi-Fi, если он есть). */
    public static void release(Context context) {
        ConnectivityManager manager = connectivity(context);
        if (manager == null) return;
        synchronized (LOCK) {
            releaseLocked(manager);
        }
    }

    private static void releaseLocked(ConnectivityManager manager) {
        try {
            manager.bindProcessToNetwork(null);
        } catch (Exception ignored) { }
        bound = null;
        if (watcher != null) {
            try {
                manager.unregisterNetworkCallback(watcher);
            } catch (Exception ignored) { }
            watcher = null;
        }
    }

    /** Следим за сетями: появился Wi-Fi, пропала сотовая — решаем заново сами. */
    private static void watch(Context context, ConnectivityManager manager) {
        if (watcher != null) return;
        final Context app = context.getApplicationContext();
        watcher = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { schedule(app); }
            @Override public void onLost(Network network) { schedule(app); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                schedule(app);
            }
        };
        try {
            manager.registerDefaultNetworkCallback(watcher);
        } catch (Exception ignored) {
            try {
                manager.registerNetworkCallback(
                        new NetworkRequest.Builder()
                                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                                .build(), watcher);
            } catch (Exception alsoIgnored) {
                watcher = null;
            }
        }
    }

    private static void schedule(final Context app) {
        // Колбэк приходит на системном потоке: уходим на свой, чтобы не ловить рекурсию.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> refresh(app));
    }

    /** True, когда процесс сейчас реально привязан к мобильной сети. */
    public static boolean usingCellular(Context context) {
        synchronized (LOCK) {
            if (bound == null) return false;
            ConnectivityManager manager = connectivity(context);
            if (manager == null) return false;
            try {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(bound);
                return capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    /** True, если в системе вообще есть сотовая сеть с интернетом (даже без привязки). */
    public static boolean mobileAvailable(Context context) {
        return mobile(context) != null;
    }

    /** Как называется сеть, через которую приложение говорит прямо сейчас. */
    public static String label(Context context) {
        ConnectivityManager manager = connectivity(context);
        if (manager == null) return "";
        if (usingCellular(context)) {
            return context.getString(com.tsuyu.messenger.R.string.net_mobile);
        }
        try {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
            if (capabilities == null) return context.getString(com.tsuyu.messenger.R.string.net_other);
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return context.getString(com.tsuyu.messenger.R.string.net_wifi);
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return context.getString(com.tsuyu.messenger.R.string.net_mobile);
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return context.getString(com.tsuyu.messenger.R.string.net_ethernet);
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return context.getString(com.tsuyu.messenger.R.string.net_vpn);
            }
        } catch (Exception ignored) { }
        return context.getString(com.tsuyu.messenger.R.string.net_other);
    }

    /** Короткая пометка для отчёта в интерфейсе. */
    public static String status(Context context) {
        if (context == null) return "";
        if (enabled(context) && usingCellular(context)) {
            return context.getString(com.tsuyu.messenger.R.string.mobile_priority_active);
        }
        if (enabled(context) && !mobileAvailable(context)) {
            return context.getString(com.tsuyu.messenger.R.string.mobile_priority_no_mobile);
        }
        return context.getString(com.tsuyu.messenger.R.string.mobile_priority_idle);
    }
}
