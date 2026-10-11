package com.tsuyu.messenger.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фоновые потоки приложения в одном месте.
 *
 * <p>Раньше каждый тяжёлый шаг создавал собственный поток или ждал очереди одного
 * единственного исполнителя: при большой переписке очередь росла, и экран «залипал».
 * Теперь у каждой работы свой пул, а главный поток занят только отрисовкой:
 *
 * <ul>
 *   <li>{@link #io()} — сеть, база, диск;</li>
 *   <li>{@link #crypto()} — шифрование и расшифровка сообщений;</li>
 *   <li>{@link #images()} — подготовка картинок и аватаров;</li>
 *   <li>{@link #diff()} — сравнение списков для RecyclerView;</li>
 *   <li>{@link #compute()} — разбор данных (JSON, история чата).</li>
 * </ul>
 */
public final class Background {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final ExecutorService IO = pool("tsuyu-io", 4);
    private static final ExecutorService CRYPTO = pool("tsuyu-crypto", 2);
    private static final ExecutorService IMAGES = pool("tsuyu-image", 3);
    private static final ExecutorService DIFF = pool("tsuyu-diff", 2);
    private static final ExecutorService COMPUTE = pool("tsuyu-compute", 2);

    private Background() { }

    public static ExecutorService io() { return IO; }

    public static ExecutorService crypto() { return CRYPTO; }

    public static ExecutorService images() { return IMAGES; }

    public static ExecutorService diff() { return DIFF; }

    public static ExecutorService compute() { return COMPUTE; }

    /** Выполнить в главном потоке (если уже главный — сразу). */
    public static void main(Runnable task) {
        if (task == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else MAIN.post(task);
    }

    /** Отложить выполнение в главном потоке. */
    public static void post(Runnable task) {
        if (task != null) MAIN.post(task);
    }

    public static void postDelayed(Runnable task, long delayMillis) {
        if (task != null) MAIN.postDelayed(task, delayMillis);
    }

    public static void remove(Runnable task) {
        if (task != null) MAIN.removeCallbacks(task);
    }

    private static ExecutorService pool(final String name, int size) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
                thread.setDaemon(true);
                // Фоновая работа не спорит за процессор с отрисовкой интерфейса.
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }
        };
        return Executors.newFixedThreadPool(size, factory);
    }
}
