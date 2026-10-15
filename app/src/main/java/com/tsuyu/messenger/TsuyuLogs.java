package com.tsuyu.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Журнал приложения: пишется в файл на устройстве, поэтому его видно и после сбоя,
 * и после перезапуска. Логи можно скопировать одной кнопкой или отправить, не подключая
 * телефон к компьютеру.
 */
public final class TsuyuLogs {

    private static final String FILE_NAME = "tsuyu_logs.txt";
    /** Журнал не растёт бесконечно: храним примерно последние 400 КБ. */
    private static final long MAX_BYTES = 400_000L;
    private static final long KEEP_BYTES = 220_000L;
    private static final Object LOCK = new Object();

    private static File file;

    private TsuyuLogs() { }

    private static File file(Context context) {
        if (file == null && context != null) {
            file = new File(context.getFilesDir(), FILE_NAME);
        }
        return file;
    }

    /** Самый первый вход: помечаем запуск приложения и версию сборки. */
    public static void init(Context context) {
        try {
            log(context, "APP", "Tsuyu " + version(context) + " запущено");
        } catch (Throwable ignored) { }
    }

    public static String version(Context context) {
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (сборка " + info.versionCode + ")";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** Запись события. Никогда не бросает исключение — журнал не имеет права мешать работе. */
    public static void log(Context context, String tag, String message) {
        String line = stamp() + " [" + tag + "] " + (message == null ? "" : message);
        write(context, line + "\n");
        android.util.Log.i("Tsuyu", line);
    }

    /** Полный текст сбоя: стек целиком, чтобы причину было видно без компьютера. */
    public static void crash(Context context, String where, Throwable error) {
        StringBuilder out = new StringBuilder();
        out.append(stamp()).append(" [СБОЙ] ").append(where == null ? "" : where).append('\n');
        try {
            java.io.StringWriter writer = new java.io.StringWriter();
            if (error != null) error.printStackTrace(new java.io.PrintWriter(writer));
            out.append(writer);
        } catch (Throwable ignored) { }
        out.append('\n');
        write(context, out.toString());
        try {
            android.util.Log.e("Tsuyu", out.toString());
        } catch (Throwable ignored) { }
        // Дублируем в настройки и пишем на диск сразу — иначе при мгновенном закрытии текст теряется.
        try {
            String trace = out.toString();
            if (trace.length() > 8000) trace = trace.substring(0, 8000);
            context.getSharedPreferences("tsuyu_crash", Context.MODE_PRIVATE).edit()
                    .putString("last", trace)
                    .putLong("at", System.currentTimeMillis())
                    .commit();
        } catch (Throwable ignored) { }
    }

    /** Весь журнал: последние события плюс причина сбоя, если она была. */
    public static String read(Context context) {
        StringBuilder out = new StringBuilder();
        try {
            File target = file(context);
            if (target != null && target.exists()) {
                try (RandomAccessFile reader = new RandomAccessFile(target, "r")) {
                    long length = reader.length();
                    long start = Math.max(0L, length - KEEP_BYTES * 2);
                    reader.seek(start);
                    byte[] raw = new byte[(int) (length - start)];
                    reader.readFully(raw);
                    String text = new String(raw, StandardCharsets.UTF_8);
                    if (start > 0) {
                        int firstBreak = text.indexOf('\n');
                        if (firstBreak > 0) text = text.substring(firstBreak + 1);
                    }
                    // На экран и в буфер обмена отдаём разумный объём: свежие записи целиком.
                    if (text.length() > 120_000) text = text.substring(text.length() - 120_000);
                    out.append(text);
                }
            }
        } catch (Throwable ignored) { }
        if (out.length() == 0) out.append("Журнал пуст: событий пока не было.\n");
        return out.toString();
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            try {
                File target = file(context);
                if (target != null && target.exists()) {
                    try (FileOutputStream output = new FileOutputStream(target, false)) {
                        output.write(new byte[0]);
                    }
                }
                context.getSharedPreferences("tsuyu_crash", Context.MODE_PRIVATE)
                        .edit().remove("last").remove("at").commit();
            } catch (Throwable ignored) { }
        }
    }

    /** Копирование в буфер обмена. Возвращает true, если текст реально положен. */
    public static boolean copy(Context context, String text) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return false;
            clipboard.setPrimaryClip(ClipData.newPlainText("Tsuyu logs", text == null ? "" : text));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Отправка журнала (почта, мессенджер, заметки) — работает без компьютера и без кабеля. */
    public static Intent share(String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Tsuyu — журнал приложения");
        intent.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
        return Intent.createChooser(intent, "Отправить журнал");
    }

    private static void write(Context context, String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (LOCK) {
            try {
                File target = file(context);
                if (target == null) return;
                try (FileOutputStream output = new FileOutputStream(target, true)) {
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                trim(target);
            } catch (Throwable ignored) { }
        }
    }

    /** Держим файл разумного размера: старые записи отрезаем, свежие остаются. */
    private static void trim(File target) {
        try {
            if (target.length() <= MAX_BYTES) return;
            byte[] keep;
            try (RandomAccessFile reader = new RandomAccessFile(target, "r")) {
                long start = Math.max(0L, reader.length() - KEEP_BYTES);
                reader.seek(start);
                keep = new byte[(int) (reader.length() - start)];
                reader.readFully(keep);
            }
            int firstBreak = 0;
            while (firstBreak < keep.length && keep[firstBreak] != '\n') firstBreak++;
            try (FileOutputStream output = new FileOutputStream(target, false)) {
                output.write(keep, Math.min(firstBreak + 1, keep.length), keep.length - Math.min(firstBreak + 1, keep.length));
                output.flush();
            }
        } catch (Throwable ignored) { }
    }

    private static String stamp() {
        return new SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(new Date());
    }
}
