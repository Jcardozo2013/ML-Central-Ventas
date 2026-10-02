package com.mlcentral.ventas;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class FirebaseUsageMonitor {
    private static final String PREFS = "mlcentral_ventas";
    private static final String CHANNEL = "mlc_firebase_usage_alert_v1";
    private static final int NOTIFICATION_ID = 3000001;
    private static volatile boolean refreshing = false;

    private FirebaseUsageMonitor() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void refreshAsync(Context context, boolean notify) {
        if (context == null || refreshing) return;
        Context app = context.getApplicationContext();
        refreshing = true;
        Thread t = new Thread(() -> {
            try {
                refreshNow(app, notify);
            } finally {
                refreshing = false;
            }
        }, "MLCentralFirebaseUsage");
        t.setDaemon(true);
        t.start();
    }

    public static void refreshNow(Context context, boolean notify) {
        if (context == null || !FirebaseTransport.signedIn(context)) return;
        FirebaseTransport.JsonResult result = FirebaseTransport.readJsonRest(context, "monitor/status", 0);
        SharedPreferences p = prefs(context);
        if (!result.ok) {
            p.edit()
                    .putString("firebase_usage_error_v166", result.detail)
                    .putLong("firebase_usage_error_at_v166", System.currentTimeMillis())
                    .apply();
            return;
        }
        JSONObject o = result.data;
        if (o == null || o.length() == 0) return;

        long estimated = Math.max(0L, o.optLong("estimated_bytes", 0L));
        long limit = Math.max(1L, o.optLong("limit_bytes", 1024L * 1024L * 1024L));
        double pct = o.has("estimated_percent")
                ? o.optDouble("estimated_percent", 0.0)
                : (estimated * 100.0 / limit);
        int main = Math.max(0, o.optInt("main_count", 0));
        int rs = Math.max(0, o.optInt("rs_count", 0));
        int movements = Math.max(0, o.optInt("movements_count", 0));
        long updated = Math.max(0L, o.optLong("updated_at", 0L));
        long cleanup = Math.max(0L, o.optLong("last_cleanup_unix", 0L));
        boolean complete = o.optBoolean("measurement_complete", true);

        p.edit()
                .putLong("firebase_usage_estimated_bytes_v166", estimated)
                .putLong("firebase_usage_limit_bytes_v166", limit)
                .putFloat("firebase_usage_percent_v166", (float) pct)
                .putInt("firebase_usage_main_count_v166", main)
                .putInt("firebase_usage_rs_count_v166", rs)
                .putInt("firebase_usage_movements_count_v166", movements)
                .putLong("firebase_usage_updated_unix_v166", updated)
                .putLong("firebase_usage_cleanup_unix_v166", cleanup)
                .putBoolean("firebase_usage_complete_v166", complete)
                .putString("firebase_usage_error_v166", "")
                .apply();

        if (notify) maybeNotify(context, pct, estimated, limit, main, rs, movements);
        context.sendBroadcast(new Intent("com.mlcentral.ventas.SALE_RECEIVED").setPackage(context.getPackageName()));
    }

    public static boolean hasData(Context context) {
        return prefs(context).getLong("firebase_usage_updated_unix_v166", 0L) > 0L;
    }

    public static int level(Context context) {
        SharedPreferences p = prefs(context);
        float pct = p.getFloat("firebase_usage_percent_v166", 0f);
        if (pct >= 80f) return 2;
        if (pct >= 70f) return 1;
        return 0;
    }

    public static boolean stale(Context context) {
        long updated = prefs(context).getLong("firebase_usage_updated_unix_v166", 0L);
        if (updated <= 0L) return true;
        return (System.currentTimeMillis() / 1000L - updated) > 2L * 60L * 60L;
    }

    public static String summary(Context context) {
        SharedPreferences p = prefs(context);
        long updated = p.getLong("firebase_usage_updated_unix_v166", 0L);
        if (updated <= 0L) return "⚪ Firebase: esperando primera medición…";
        long used = p.getLong("firebase_usage_estimated_bytes_v166", 0L);
        long limit = p.getLong("firebase_usage_limit_bytes_v166", 1024L * 1024L * 1024L);
        float pct = p.getFloat("firebase_usage_percent_v166", 0f);
        String icon = pct >= 80f ? "🔴" : (pct >= 70f ? "🟡" : "🟢");
        if (stale(context)) icon = "🟠";
        return icon + " Firebase: " + formatBytes(used) + " estimados / " + formatBytes(limit)
                + " · " + String.format(Locale.getDefault(), "%.1f%%", pct);
    }

    public static String detail(Context context) {
        SharedPreferences p = prefs(context);
        if (!hasData(context)) return "Main: — · RS: — · Movimientos: —";
        String text = "Main: " + p.getInt("firebase_usage_main_count_v166", 0)
                + " · RS: " + p.getInt("firebase_usage_rs_count_v166", 0)
                + " · Movimientos: " + p.getInt("firebase_usage_movements_count_v166", 0);
        if (!p.getBoolean("firebase_usage_complete_v166", true)) text += " · ⚠ medición parcial";
        return text;
    }

    public static String cleanupText(Context context) {
        SharedPreferences p = prefs(context);
        long cleanup = p.getLong("firebase_usage_cleanup_unix_v166", 0L);
        long updated = p.getLong("firebase_usage_updated_unix_v166", 0L);
        if (cleanup <= 0L) {
            if (updated <= 0L) return "Windows todavía no publicó el estado de almacenamiento.";
            return "Medición: " + formatTime(updated) + " · esperando dato de limpieza.";
        }
        return "Última limpieza: " + formatTime(cleanup)
                + (stale(context) ? " · ⚠ monitor atrasado" : " · monitor automático activo");
    }

    private static String formatTime(long unixSeconds) {
        if (unixSeconds <= 0L) return "—";
        return new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(unixSeconds * 1000L));
    }

    private static String formatBytes(long bytes) {
        double b = Math.max(0L, bytes);
        if (b < 1000.0 * 1000.0) return String.format(Locale.getDefault(), "%.0f KB", b / 1000.0);
        if (b < 1000.0 * 1000.0 * 1000.0) return String.format(Locale.getDefault(), "%.1f MB", b / 1000000.0);
        return String.format(Locale.getDefault(), "%.2f GB", b / 1000000000.0);
    }

    private static void maybeNotify(Context context, double pct, long used, long limit,
                                    int main, int rs, int movements) {
        SharedPreferences p = prefs(context);
        if (pct < 70.0) {
            p.edit().putInt("firebase_usage_last_alert_level_v166", 0).apply();
            return;
        }
        int severity = pct >= 80.0 ? 2 : 1;
        int previous = p.getInt("firebase_usage_last_alert_level_v166", 0);
        long lastAt = p.getLong("firebase_usage_last_alert_at_v166", 0L);
        long now = System.currentTimeMillis();
        if (severity <= previous && now - lastAt < 12L * 60L * 60L * 1000L) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Alerta de espacio Firebase", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Avisa cuando el uso estimado de ML Central se acerca al límite de Firebase.");
            nm.createNotificationChannel(channel);
        }

        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, 166, i, flags);

        String title = severity >= 2 ? "🔴 Firebase cerca del límite" : "🟡 Revisar espacio de Firebase";
        String line = String.format(Locale.getDefault(), "%.1f%% estimado · %s / %s", pct, formatBytes(used), formatBytes(limit));
        String detail = line + "\nMain: " + main + " · RS: " + rs + " · Movimientos: " + movements
                + "\nAbrí ML Central Ventas para ver el estado.";

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(line)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        }
        nm.notify(NOTIFICATION_ID, b.build());
        p.edit()
                .putLong("firebase_usage_last_alert_at_v166", now)
                .putInt("firebase_usage_last_alert_level_v166", severity)
                .apply();
    }
}
