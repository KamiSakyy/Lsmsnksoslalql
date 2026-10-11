package com.tsuyu.messenger.p2p;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Потоки звонка в одном месте.
 *
 * <p>Раньше каждая задача звонка (поиск адреса, пересборка канала, отправка) заводила
 * собственный поток: во время разговора их могли плодиться десятки, и телефон грелся.
 * Теперь у звонка небольшой постоянный набор потоков.
 */
public final class P2pThreads {

    /** Работа звонка: поиск адресов, пересборка канала, служебные задачи. */
    private static final ExecutorService WORK = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "tsuyu-call");
        thread.setDaemon(true);
        return thread;
    });

    /** Планировщик: задержки и повторы внутри звонка. */
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "tsuyu-call-timer");
        thread.setDaemon(true);
        return thread;
    });

    private P2pThreads() { }

    /**
     * Отдельный поток для непрерывного цикла (приём пакетов, запись звука, разбор видео).
     * Поток именованный и живёт ровно столько, сколько канал: закрытие канала гасит его.
     */
    public static ExecutorService loop(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Гасит поток цикла вместе с каналом. */
    public static void shutdown(ExecutorService executor) {
        if (executor == null) return;
        try {
            executor.shutdownNow();
        } catch (Throwable ignored) { }
    }

    public static void work(Runnable task) {
        if (task == null) return;
        try {
            WORK.execute(task);
        } catch (Throwable ignored) {
            // Пул выключен — задачу просто не выполняем.
        }
    }

    /** Отложенная задача; возвращённое можно отменить. */
    public static ScheduledFuture<?> later(Runnable task, long millis) {
        if (task == null) return null;
        try {
            return TIMER.schedule(task, Math.max(0L, millis), TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Повторяющаяся задача; возвращённое можно отменить. */
    public static ScheduledFuture<?> every(Runnable task, long millis) {
        if (task == null) return null;
        try {
            return TIMER.scheduleWithFixedDelay(task, millis, millis, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void cancel(ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }
}
