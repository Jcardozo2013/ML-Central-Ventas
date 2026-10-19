package com.mlcentral.ventas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Sólo A ROCHA -> ENVIADOS con estado previo observado en este dispositivo.
 * No alerta al instalar ni al recibir un primer snapshot, y evita duplicados
 * cuando Firebase envía el mismo tablero por varias rutas de sincronización.
 */
public final class DispatchNotificationTracker {
    public static final String CHANNEL_ID = "mlc_dispatched_from_rocha_v1";
    private static final String SEEN_PREFIX = "dispatch_notified_v182_";

    private DispatchNotificationTracker() {}

    public static void ensureChannel(Context context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Paquetes enviados", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Aviso al pasar una venta de A Rocha a Enviados.");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 250, 100, 250});
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            // No forzar ningún sonido: Android respeta el tono elegido por el usuario.
            nm.createNotificationChannel(channel);
        } catch (Exception ignored) {}
    }

    public static void reportTransitions(Context context, JSONObject previous, JSONObject current) {
        if (previous == null || current == null || previous.length() == 0) return;
        JSONArray names = previous.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String orderId = names.optString(i, "").trim();
            if (orderId.isEmpty()) continue;
            JSONObject oldSale = previous.optJSONObject(orderId);
            JSONObject newSale = current.optJSONObject(orderId);
            reportTransition(context, oldSale, newSale);
        }
    }

    public static void reportTransition(Context context, JSONObject oldSale, JSONObject newSale) {
        if (oldSale == null || newSale == null) return;
        if (!"to_rocha".equals(oldSale.optString("stage", ""))) return;
        if (!"dispatched".equals(newSale.optString("stage", ""))) return;
        String orderId = newSale.optString("order_id", "").trim();
        if (orderId.isEmpty()) orderId = oldSale.optString("order_id", "").trim();
        if (orderId.isEmpty()) return;
        String previousId = oldSale.optString("order_id", orderId).trim();
        if (!previousId.isEmpty() && !orderId.equals(previousId)) return;
        notifyOnce(context, orderId, newSale);
    }

    private static synchronized void notifyOnce(Context context, String orderId, JSONObject sale) {
        try {
            Context app = context.getApplicationContext();
            SharedPreferences prefs = app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
            String key = SEEN_PREFIX + orderId;
            if (prefs.getBoolean(key, false)) return;
            // Persiste la deduplicación ANTES del aviso para resistir reintentos y reinicios.
            if (!prefs.edit().putBoolean(key, true).commit()) return;

            ensureChannel(app);
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String product = sale.optString("item_title",
                    sale.optString("linked_product_name", "Producto")).trim();
            if (product.isEmpty()) product = "Producto";
            String message = product + "\nOrden: " + orderId
                    + "\nPasó de A ROCHA a ENVIADOS.";
            Intent launch = new Intent(app, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent open = PendingIntent.getActivity(app, orderId.hashCode(), launch, flags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(app, CHANNEL_ID)
                    : new Notification.Builder(app);
            builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle("PAQUETE ENVIADO · ML CENTRAL")
                    .setContentText("Orden " + orderId + " pasó a ENVIADOS")
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PUBLIC);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                builder.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
            nm.notify(3000000 + Math.abs(orderId.hashCode() % 900000), builder.build());
        } catch (Exception ignored) {
            // Un error de notificaciones nunca debe romper la sincronización de ventas.
        }
    }
}
