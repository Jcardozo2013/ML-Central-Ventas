package com.mlcentral.ventas;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CrashManager {
    public static final String CRASH_FILE = "mlcentral_global_crash_v160.txt";
    public static final String ANR_FILE = "mlcentral_anr_v160.txt";
    public static final String EVENT_FILE = "mlcentral_app_events_v160.txt";

    private static final AtomicBoolean installed = new AtomicBoolean(false);
    private static final AtomicLong mainBeat = new AtomicLong(0L);
    private static volatile long lastAnrAt = 0L;
    private static Context appContext;
    private static Thread.UncaughtExceptionHandler previous;

    private CrashManager() {}

    public static void install(Application app) {
        if (app == null) return;
        appContext = app.getApplicationContext();
        if (!installed.compareAndSet(false, true)) return;

        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                writeCrash(thread, error);
                log("CRASH NO CONTROLADO · thread=" + (thread == null ? "?" : thread.getName())
                        + " · " + (error == null ? "?" : error.getClass().getSimpleName()));
            } catch (Throwable ignored) {}

            if (previous != null) previous.uncaughtException(thread, error);
            else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });

        startAnrWatchdog();
        log("PROCESO INICIADO · v" + BuildConfig.VERSION_NAME
                + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE);
    }

    private static void startAnrWatchdog() {
        Handler main = new Handler(Looper.getMainLooper());
        Runnable beat = new Runnable() {
            @Override public void run() {
                mainBeat.set(SystemClock.uptimeMillis());
                main.postDelayed(this, 1000L);
            }
        };
        mainBeat.set(SystemClock.uptimeMillis());
        main.post(beat);

        Thread watcher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2500L);
                    long now = SystemClock.uptimeMillis();
                    long age = now - mainBeat.get();
                    if (age < 8000L) continue;
                    if (now - lastAnrAt < 30000L) continue;
                    lastAnrAt = now;
                    writeAnr(age);
                    log("ANR/BLOQUEO detectado · UI sin responder " + age + " ms");
                } catch (InterruptedException ignored) {
                } catch (Throwable ignored) {}
            }
        }, "MLCentral-AnrWatchdog");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static String header(String kind) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        StringBuilder out = new StringBuilder();
        out.append("ML CENTRAL VENTAS — ").append(kind).append('\n');
        out.append("Versión: ").append(BuildConfig.VERSION_NAME).append('\n');
        out.append("Fecha: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS", Locale.getDefault()).format(new Date())).append('\n');
        out.append("Dispositivo: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("PID: ").append(android.os.Process.myPid()).append('\n');
        out.append("Memoria Java usada: ").append(used / 1024 / 1024).append(" MB / máx ")
                .append(rt.maxMemory() / 1024 / 1024).append(" MB\n");
        return out.toString();
    }

    private static void writeCrash(Thread thread, Throwable error) {
        if (appContext == null) return;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (error != null) error.printStackTrace(pw);
        pw.flush();

        StringBuilder out = new StringBuilder(header("CRASH NO CONTROLADO"));
        out.append("Thread: ").append(thread == null ? "?" : thread.getName()).append('\n');
        if (error != null) {
            out.append("Tipo: ").append(error.getClass().getName()).append('\n');
            out.append("Mensaje: ").append(String.valueOf(error.getMessage())).append("\n\n");
        }
        out.append(sw);
        writeFile(CRASH_FILE, out.toString(), false);
    }

    private static void writeAnr(long ageMs) {
        if (appContext == null) return;
        StringBuilder out = new StringBuilder(header("POSIBLE ANR / APP NO RESPONDE"));
        out.append("UI sin responder aproximadamente: ").append(ageMs).append(" ms\n\n");
        Thread mainThread = Looper.getMainLooper().getThread();
        out.append("STACK DEL HILO PRINCIPAL\n========================\n");
        for (StackTraceElement e : mainThread.getStackTrace()) out.append("  at ").append(e).append('\n');
        writeFile(ANR_FILE, out.toString(), false);
    }

    public static void log(String text) {
        if (appContext == null || text == null) return;
        String line = new SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.getDefault()).format(new Date())
                + "  " + text + "\n";
        try {
            File f = new File(appContext.getFilesDir(), EVENT_FILE);
            if (f.exists() && f.length() > 250000L) {
                // Evita que el diagnóstico crezca indefinidamente.
                new FileOutputStream(f, false).close();
            }
        } catch (Throwable ignored) {}
        writeFile(EVENT_FILE, line, true);
    }

    public static void clear(Context context) {
        if (context == null) return;
        try { new File(context.getFilesDir(), CRASH_FILE).delete(); } catch (Throwable ignored) {}
        try { new File(context.getFilesDir(), ANR_FILE).delete(); } catch (Throwable ignored) {}
        try { new File(context.getFilesDir(), EVENT_FILE).delete(); } catch (Throwable ignored) {}
        try { new File(context.getFilesDir(), SaleListenerService.CRASH_FILE).delete(); } catch (Throwable ignored) {}
        try { new File(context.getFilesDir(), SaleListenerService.EVENT_FILE).delete(); } catch (Throwable ignored) {}
        log("REGISTROS ANTERIORES BORRADOS");
    }

    private static void writeFile(String name, String text, boolean append) {
        if (appContext == null) return;
        try {
            File file = new File(appContext.getFilesDir(), name);
            try (FileOutputStream fos = new FileOutputStream(file, append)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Throwable ignored) {}
    }
}
