package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.PrintWriter;
import java.io.StringWriter;

public class MlCentralApp extends Application implements Application.ActivityLifecycleCallbacks {
    private volatile boolean uiVisible = false;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);

        // Preparar Firebase una sola vez y antes de que la pantalla o el servicio lo usen.
        // v1.31 ya no borra la caché de Android al arrancar.
        try { FirebaseConfig.ensureInitialized(this); } catch (Throwable ignored) {}

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            saveCrash(error);
            if (uiVisible) scheduleSingleRecovery();
            if (previous != null) previous.uncaughtException(thread, error);
            else System.exit(10);
        });
    }

    private void saveCrash(Throwable error) {
        try {
            StringWriter sw = new StringWriter();
            if (error != null) error.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();
            if (trace.length() > 12000) trace = trace.substring(0, 12000);
            long now = System.currentTimeMillis();
            long last = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).getLong("crash_last_at_v131", 0L);
            int burst = now - last < 30000L
                    ? getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).getInt("crash_burst_v131", 0) + 1
                    : 1;
            getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
                    .putLong("crash_last_at_v131", now)
                    .putInt("crash_burst_v131", burst)
                    .putString("crash_last_trace_v131", trace)
                    .commit();
        } catch (Throwable ignored) {}
    }

    private void scheduleSingleRecovery() {
        try {
            int burst = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).getInt("crash_burst_v131", 1);
            if (burst > 2) return; // evitar un bucle de reaperturas si hay un fallo repetitivo real

            Intent launch = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    9131,
                    launch,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarm != null) {
                alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 700L, pi);
            }
        } catch (Throwable ignored) {}
    }

    @Override public void onActivityResumed(Activity activity) { uiVisible = true; }
    @Override public void onActivityPaused(Activity activity) { uiVisible = false; }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
