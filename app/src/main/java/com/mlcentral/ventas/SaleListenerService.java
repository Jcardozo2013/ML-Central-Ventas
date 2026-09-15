package com.mlcentral.ventas;

import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Nombre conservado para no cambiar el componente Android existente.
 * En v1.34 el servicio corre en un proceso separado de diagnóstico. Si ocurre
 * una excepción no controlada, se guarda el stack completo antes de que Android
 * termine solamente el proceso del servicio.
 */
public class SaleListenerService extends FirebaseListenerService {
    public static final String CRASH_FILE = "mlcentral_service_crash_v134.txt";
    public static final String EVENT_FILE = "mlcentral_service_event_v134.txt";

    private Thread.UncaughtExceptionHandler previousHandler;

    @Override public void onCreate() {
        installCrashCapture();
        writeEvent("onCreate: entrando");
        super.onCreate();
        writeEvent("onCreate: OK");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        writeEvent("onStartCommand: entrando");
        try {
            int result = super.onStartCommand(intent, flags, startId);
            writeEvent("onStartCommand: OK result=" + result);
            return result;
        } catch (Throwable error) {
            writeCrash("onStartCommand", error);
            writeEvent("onStartCommand: ERROR capturado " + error.getClass().getName());
            try { stopSelf(startId); } catch (Throwable ignored) {}
            return START_NOT_STICKY;
        }
    }

    @Override public void onDestroy() {
        writeEvent("onDestroy");
        try {
            super.onDestroy();
        } catch (Throwable error) {
            writeCrash("onDestroy", error);
        }
    }

    private void installCrashCapture() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                writeCrash("UNCAUGHT thread=" + (thread == null ? "?" : thread.getName()), error);
            } catch (Throwable ignored) {}

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private void writeCrash(String where, Throwable error) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            if (error != null) error.printStackTrace(pw);
            pw.flush();

            StringBuilder out = new StringBuilder();
            out.append("ML Central servicio v1.34\n");
            out.append("fecha: ")
                    .append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS", Locale.getDefault()).format(new Date()))
                    .append('\n');
            out.append("proceso: ").append(android.os.Process.myPid()).append('\n');
            out.append("punto: ").append(where == null ? "?" : where).append('\n');
            if (error != null) {
                out.append("tipo: ").append(error.getClass().getName()).append('\n');
                out.append("mensaje: ").append(String.valueOf(error.getMessage())).append("\n\n");
            }
            out.append(sw);

            File file = new File(getFilesDir(), CRASH_FILE);
            try (FileOutputStream fos = new FileOutputStream(file, false)) {
                fos.write(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Throwable ignored) {}
    }

    private void writeEvent(String text) {
        try {
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date())
                    + "  " + text + "\n";
            File file = new File(getFilesDir(), EVENT_FILE);
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Throwable ignored) {}
    }
}
