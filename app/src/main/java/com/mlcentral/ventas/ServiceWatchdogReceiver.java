package com.mlcentral.ventas;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/** Respaldo liviano: intenta volver a levantar el listener si Android/Xiaomi lo mata. */
public class ServiceWatchdogReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.mlcentral.ventas.KEEP_LISTENER_ALIVE";
    private static final long DEFAULT_DELAY_MS = 2 * 60 * 1000L;

    @Override public void onReceive(Context context, Intent intent) {
        startListener(context);
        schedule(context, DEFAULT_DELAY_MS);
    }

    public static void startListener(Context context) {
        if (context == null) return;
        Intent service = new Intent(context.getApplicationContext(), SaleListenerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.getApplicationContext().startForegroundService(service);
            else context.getApplicationContext().startService(service);
        } catch (Throwable ignored) {}
    }

    public static void schedule(Context context, long delayMs) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(app, ServiceWatchdogReceiver.class).setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(app, 1440, i, flags);
            long when = SystemClock.elapsedRealtime() + Math.max(60_000L, delayMs);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            }
        } catch (Throwable ignored) {}
    }
}
