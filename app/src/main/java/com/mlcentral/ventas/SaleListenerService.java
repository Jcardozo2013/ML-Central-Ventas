package com.mlcentral.ventas;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio de ventas protegido. Desde v1.35 no reutiliza cursores antiguos al
 * arrancar: Firebase se posiciona primero en el final del canal y después la
 * app solicita historial/estados por el protocolo normal. Esto evita que un
 * teléfono intente reproducir miles de eventos acumulados de una sola vez.
 *
 * v1.44 mantiene esa protección, pero recupera únicamente ventas recientes
 * que hayan entrado mientras Android/Xiaomi tenía el servicio detenido.
 *
 * v1.45 recupera ventas recientes perdidas por Android/Xiaomi.
 *
 * v1.46 separa "vista/sincronizada" de "notificada". Un snapshot de Estados o
 * Historial nunca puede consumir el sonido de una venta nueva, incluido STOCK LOCAL.
 */
public class SaleListenerService extends FirebaseListenerService {
    public static final String CRASH_FILE = "mlcentral_service_crash_v135.txt";
    public static final String EVENT_FILE = "mlcentral_service_event_v135.txt";

    private static final long WATCHDOG_MS = 5 * 60 * 1000L;
    private static final long RECOVERY_WINDOW_MS = 2 * 60 * 60 * 1000L;
    private static final int RECOVERY_LIMIT = 50;
    private static final int MAX_RECOVERY_HISTORY = 5000;

    private Thread.UncaughtExceptionHandler previousHandler;
    private volatile boolean recoveryStarted = false;

    @Override public void onCreate() {
        installCrashCapture();
        writeEvent("onCreate: entrando");
        // Al actualizar desde <=1.45, tomamos lo ya visto como baseline para no
        // reproducir de golpe ventas antiguas. A partir de aquí se separan.
        SaleStore.ensureNotificationBaseline(this);

        // IMPORTANTE: no iniciar desde un cursor viejo. El listener base, al no
        // encontrar cursor, consulta solamente el último elemento y se engancha
        // desde allí. Luego pide historial y estados por sus canales dedicados.
        try {
            SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
            p.edit()
                    .remove("firebase_main_cursor_v120")
                    .remove("firebase_rs_cursor_v120")
                    .commit();
            writeEvent("cursores antiguos descartados");
        } catch (Throwable error) {
            writeEvent("no se pudieron limpiar cursores: " + error.getClass().getSimpleName());
        }

        super.onCreate();
        ServiceWatchdogReceiver.schedule(this, WATCHDOG_MS);
        writeEvent("onCreate: OK");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        writeEvent("onStartCommand: entrando");
        ServiceWatchdogReceiver.schedule(this, WATCHDOG_MS);
        try {
            int result = super.onStartCommand(intent, flags, startId);
            if (!recoveryStarted) {
                recoveryStarted = true;
                recoverRecentMissedSales();
            }
            writeEvent("onStartCommand: OK result=" + result);
            return result;
        } catch (Throwable error) {
            writeCrash("onStartCommand", error);
            writeEvent("onStartCommand: ERROR capturado " + error.getClass().getName());
            try { stopSelf(startId); } catch (Throwable ignored) {}
            ServiceWatchdogReceiver.schedule(this, 60_000L);
            return START_NOT_STICKY;
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        writeEvent("onTaskRemoved: la app salió de recientes; el listener sigue con respaldo");
        ServiceWatchdogReceiver.schedule(this, 60_000L);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        writeEvent("onDestroy");
        ServiceWatchdogReceiver.schedule(this, 60_000L);
        try {
            super.onDestroy();
        } catch (Throwable error) {
            writeCrash("onDestroy", error);
        }
    }

    /**
     * Recupera solo ventas recientes no avisadas en este dispositivo. No
     * reproduce snapshots de Estados/Historial y limita la consulta a los
     * últimos mensajes del canal.
     */
    @SuppressWarnings("unchecked")
    private void recoverRecentMissedSales() {
        try {
            FirebaseDatabase.getInstance()
                    .getReference("channels/main")
                    .orderByKey()
                    .limitToLast(RECOVERY_LIMIT)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snapshot) {
                            int recovered = 0;
                            long now = System.currentTimeMillis();
                            for (DataSnapshot child : snapshot.getChildren()) {
                                try {
                                    Object raw = child.getValue();
                                    if (!(raw instanceof Map)) continue;
                                    JSONObject o = new JSONObject((Map<String, Object>) raw);
                                    if (!o.has("id") && child.getKey() != null) o.put("id", child.getKey());

                                    String title = o.optString("title", "");
                                    String upper = title.toUpperCase(Locale.ROOT);
                                    if (title.startsWith("MLC_")) continue;
                                    if (!upper.contains("VENTA") && !upper.contains("ML CENTRAL")) continue;
                                    if (upper.contains("ACTUALIZACIÓN VENTA") || upper.contains("ACTUALIZACION VENTA")) continue;
                                    if (upper.contains("PAQUETE ENTREGADO")) continue;

                                    long seconds = o.optLong("time", 0L);
                                    if (seconds <= 0L) continue;
                                    long eventMs = seconds * 1000L;
                                    long age = now - eventMs;
                                    if (age < -5 * 60 * 1000L || age > RECOVERY_WINDOW_MS) continue;

                                    String message = o.optString("message", "Venta nueva");
                                    String saleId = recoverySaleId(o, title, message);
                                    if (saleId.isEmpty()) continue;

                                    // v1.46: no usamos "seen" para decidir el sonido. Una venta
                                    // puede haber llegado por Estados/Historial sin haber sonado.
                                    if (SaleStore.isNotified(SaleListenerService.this, saleId)) continue;

                                    if (SaleStore.isAcknowledged(SaleListenerService.this, saleId)) {
                                        SaleStore.markNotified(SaleListenerService.this, saleId);
                                        continue;
                                    }

                                    SaleStore.markSeen(SaleListenerService.this, saleId);
                                    forceUnreadHistory(saleId,
                                            title == null || title.trim().isEmpty() ? "🛒 NUEVA VENTA — ML CENTRAL" : title,
                                            message, seconds);
                                    showRecoveredSaleNotification(saleId, title, message);
                                    SaleStore.markNotified(SaleListenerService.this, saleId);
                                    recovered++;
                                } catch (Throwable ignored) {}
                            }
                            if (recovered > 0) {
                                writeEvent("recuperación reciente: " + recovered + " venta(s) no avisada(s)");
                                try {
                                    sendBroadcast(new Intent("com.mlcentral.ventas.SALE_RECEIVED").setPackage(getPackageName()));
                                } catch (Throwable ignored) {}
                            }
                        }

                        @Override public void onCancelled(DatabaseError error) {
                            writeEvent("recuperación reciente cancelada: " + (error == null ? "?" : error.getMessage()));
                        }
                    });
        } catch (Throwable error) {
            writeEvent("recuperación reciente error: " + error.getClass().getSimpleName());
        }
    }

    private boolean historyIsUnread(String saleId) {
        if (saleId == null || saleId.trim().isEmpty()) return false;
        try {
            JSONArray arr = new JSONArray(getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).getString("history", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                if (saleId.trim().equals(item.optString("saleId", "").trim())) {
                    return !item.optBoolean("read", false);
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Fuerza únicamente esta venta reciente a estado NUEVA en este dispositivo. */
    private void forceUnreadHistory(String saleId, String title, String message, long seconds) {
        try {
            SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
            JSONArray old;
            try { old = new JSONArray(p.getString("history", "[]")); }
            catch (Throwable ignored) { old = new JSONArray(); }

            JSONArray out = new JSONArray();
            boolean found = false;
            int unread = 0;

            for (int i = 0; i < old.length() && out.length() < MAX_RECOVERY_HISTORY; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("saleId", "").trim();
                if (saleId.equals(id)) {
                    found = true;
                    item.put("read", false);
                    if (title != null && !title.trim().isEmpty()) item.put("title", title);
                    if (message != null && !message.trim().isEmpty()) item.put("message", message);
                    if (item.optLong("time", 0L) <= 0L && seconds > 0L) item.put("time", seconds);
                }
                if (!item.optBoolean("read", false)) unread++;
                out.put(item);
            }

            if (!found) {
                JSONObject item = new JSONObject();
                item.put("saleId", saleId);
                item.put("title", title == null || title.trim().isEmpty() ? "🛒 NUEVA VENTA — ML CENTRAL" : title);
                item.put("message", message == null ? "" : message);
                item.put("time", seconds > 0L ? seconds : System.currentTimeMillis() / 1000L);
                item.put("read", false);
                item.put("updated", false);

                JSONArray withNew = new JSONArray();
                withNew.put(item);
                unread++;
                for (int i = 0; i < out.length() && withNew.length() < MAX_RECOVERY_HISTORY; i++) withNew.put(out.opt(i));
                out = withNew;
            }

            p.edit().putString("history", out.toString()).putInt("unread", unread).apply();
        } catch (Throwable ignored) {}
    }

    private String recoverySaleId(JSONObject o, String title, String message) {
        String source = o.optString("source_id", "").trim();
        if (!source.isEmpty()) return source;
        String sequence = o.optString("sequence_id", "").trim();
        if (!sequence.isEmpty()) return sequence;
        String combined = (title == null ? "" : title) + "\n" + (message == null ? "" : message);
        try {
            Matcher m = Pattern.compile("(?:orden|order|venta)[^0-9]{0,20}(20[0-9]{10,})",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(combined);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return o.optString("id", "").trim();
    }

    private void showRecoveredSaleNotification(String saleId, String title, String message) {
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, SALES_CHANNEL)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title == null || title.trim().isEmpty() ? "🛒 NUEVA VENTA — ML CENTRAL" : title)
                    .setContentText(firstLine(message))
                    .setStyle(new Notification.BigTextStyle().bigText(message == null ? "Venta nueva" : message))
                    .setContentIntent(openAppIntent(saleId.hashCode()))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setCategory(Notification.CATEGORY_EVENT)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setNumber(SaleStore.unread(this));
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                b.setPriority(Notification.PRIORITY_MAX).setDefaults(Notification.DEFAULT_ALL);
            }
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(1000 + Math.abs(saleId.hashCode() % 900000), b.build());
        } catch (Throwable ignored) {}
    }

    private PendingIntent openAppIntent(int request) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, request, i, flags);
    }

    private String firstLine(String s) {
        if (s == null || s.trim().isEmpty()) return "Venta nueva";
        int n = s.indexOf('\n');
        return n > 0 ? s.substring(0, n) : s;
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
            out.append("ML Central servicio v1.46\n");
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
