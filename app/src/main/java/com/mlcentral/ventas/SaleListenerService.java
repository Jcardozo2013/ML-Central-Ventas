package com.mlcentral.ventas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class SaleListenerService extends Service {
    public static final String SERVICE_CHANNEL = "mlc_service";
    public static final String SALES_CHANNEL = "mlc_sales_urgent";
    public static final String DELIVERY_CHANNEL = "mlc_delivery_status_v1";
    public static final String HISTORY_SALE_TITLE = "MLC_HISTORY_SALE_V1";
    public static final String FULL_HISTORY_BEGIN_TITLE = "MLC_FULL_HISTORY_BEGIN_V2";
    public static final String FULL_HISTORY_SALE_TITLE = "MLC_FULL_HISTORY_SALE_V2";
    public static final String FULL_HISTORY_END_TITLE = "MLC_FULL_HISTORY_END_V2";
    private static final long LIVE_RETRY_MS = 60000L;
    private static final long BACKUP_POLL_MS = 8000L;
    private volatile boolean running = false;
    private Thread worker;
    private Thread syncWorker;
    private Thread stateWorker;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(7, serviceNotification("Esperando ventas y entregas…"));
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MLCentralVentas:listener");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            worker = new Thread(this::listenLoop, "MLCentralVentasListener"); worker.setDaemon(true); worker.start();
            syncWorker = new Thread(this::listenReadSyncLoop, "MLCentralReadSyncListener"); syncWorker.setDaemon(true); syncWorker.start();
            stateWorker = new Thread(this::stateMaintenanceLoop, "MLCentralStateMaintenance"); stateWorker.setDaemon(true); stateWorker.start();
        }
        return START_STICKY;
    }

    private void stateMaintenanceLoop() {
        while (running) {
            try {
                StateSync.flushPendingAsync(this);
                StateSync.requestSnapshotAsync(this, false);
                Thread.sleep(120000L);
            } catch (InterruptedException ignored) {
            } catch (Exception ignored) {
                try { Thread.sleep(15000L); } catch (InterruptedException ignored2) {}
            }
        }
    }

    private String errorText(Throwable e) {
        if (e == null) return "error desconocido";
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage();
        String out = (name == null || name.isEmpty() ? "Error" : name) + (msg == null || msg.trim().isEmpty() ? "" : ": " + msg.trim());
        return out.length() > 180 ? out.substring(0, 180) : out;
    }

    private void setConnectionInfo(String mode, String error) {
        getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
                .putString("connection_mode", mode == null ? "" : mode)
                .putString("connection_last_error", error == null ? "" : error)
                .apply();
        broadcastRefresh();
    }

    private boolean pollMainTopicOnce() {
        HttpURLConnection conn = null;
        try {
            String last = SaleStore.getLastMessageId(this);
            StringBuilder url = new StringBuilder(AppConfig.BASE_URL)
                    .append(AppConfig.TOPIC)
                    .append("/json?poll=1");
            if (last != null && !last.isEmpty()) {
                url.append("&since=").append(URLEncoder.encode(last, StandardCharsets.UTF_8.name()));
            }
            conn = (HttpURLConnection) new java.net.URL(url.toString()).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/x-ndjson");
            conn.setRequestProperty("User-Agent", "MLCentralVentas/" + BuildConfig.VERSION_NAME + " Android");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject o;
                try { o = new JSONObject(line); } catch (Exception ignored) { continue; }
                if (!"message".equals(o.optString("event", ""))) continue;
                handleMessage(o);
                String ntfyId = o.optString("id", "");
                if (!ntfyId.isEmpty()) SaleStore.setLastMessageId(this, ntfyId);
            }

            SaleStore.setConnected(this, true);
            getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit().putString("connection_mode", "backup").apply();
            updateServiceNotification("Conectado · modo respaldo");
            broadcastRefresh();
            return true;
        } catch (Exception e) {
            setConnectionInfo("reconnecting", "Respaldo: " + errorText(e));
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void listenLoop() {
        int backoff = 2;
        long nextLiveTry = 0L;
        while (running) {
            if (System.currentTimeMillis() < nextLiveTry) {
                boolean ok = pollMainTopicOnce();
                if (!ok) {
                    SaleStore.setConnected(this, false);
                    updateServiceNotification("Reconectando…");
                }
                try { Thread.sleep(ok ? BACKUP_POLL_MS : Math.min(30000L, backoff * 1000L)); } catch (InterruptedException ignored) {}
                if (!ok) backoff = Math.min(30, backoff * 2); else backoff = 2;
                continue;
            }

            HttpURLConnection conn = null;
            try {
                String last = SaleStore.getLastMessageId(this);
                String url = AppConfig.BASE_URL + AppConfig.TOPIC + "/json";
                if (last != null && !last.isEmpty()) url += "?since=" + URLEncoder.encode(last, StandardCharsets.UTF_8.name());
                conn = (HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(0);
                conn.setRequestProperty("Accept", "application/x-ndjson");
                conn.setRequestProperty("User-Agent", "MLCentralVentas/" + BuildConfig.VERSION_NAME + " Android");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

                SaleStore.setConnected(this, true);
                setConnectionInfo("live", "");
                ReadSync.flushPendingAsync(this);
                StateSync.flushPendingAsync(this);
                StateSync.requestSnapshotAsync(this, false);
                updateServiceNotification("Conectado · ventas y estados sincronizados");
                backoff = 2;

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    JSONObject o;
                    try { o = new JSONObject(line); } catch (Exception ignored) { continue; }
                    String event = o.optString("event", "");
                    String ntfyId = o.optString("id", "");
                    if ("open".equals(event) || "keepalive".equals(event)) {
                        SaleStore.setConnected(this, true);
                        if (!ntfyId.isEmpty()) broadcastRefresh();
                        continue;
                    }
                    if (!"message".equals(event)) continue;
                    handleMessage(o);
                    if (!ntfyId.isEmpty()) SaleStore.setLastMessageId(this, ntfyId);
                }
                throw new IllegalStateException("conexión en vivo cerrada");
            } catch (Exception e) {
                nextLiveTry = System.currentTimeMillis() + LIVE_RETRY_MS;
                String liveError = "En vivo: " + errorText(e);
                setConnectionInfo("backup", liveError);
                boolean backupOk = pollMainTopicOnce();
                if (!backupOk) {
                    SaleStore.setConnected(this, false);
                    updateServiceNotification("Reconectando…");
                }
                try { Thread.sleep(backupOk ? BACKUP_POLL_MS : backoff * 1000L); } catch (InterruptedException ignored) {}
                backoff = backupOk ? 2 : Math.min(30, backoff * 2);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private void listenReadSyncLoop() {
        int backoff = 2;
        while (running) {
            HttpURLConnection conn = null;
            try {
                String last = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).getString("last_read_sync_id", "");
                String url = ReadSync.streamUrl();
                if (last != null && !last.isEmpty()) url += "?since=" + URLEncoder.encode(last, StandardCharsets.UTF_8.name());
                conn = (HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(0);
                conn.setRequestProperty("Accept", "application/x-ndjson");
                conn.setRequestProperty("User-Agent", "MLCentralVentas/" + BuildConfig.VERSION_NAME + " Android");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                backoff = 2;
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    JSONObject o;
                    try { o = new JSONObject(line); } catch (Exception ignored) { continue; }
                    String event = o.optString("event", "");
                    String ntfyId = o.optString("id", "");
                    if ("message".equals(event) && ReadSync.isReadSync(o)) handleReadSync(o);
                    if ("message".equals(event) && !ntfyId.isEmpty()) {
                        getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit().putString("last_read_sync_id", ntfyId).apply();
                    }
                }
            } catch (Exception e) {
                try { Thread.sleep(backoff * 1000L); } catch (InterruptedException ignored) {}
                backoff = Math.min(30, backoff * 2);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private void handleReadSync(JSONObject o) {
        List<String> ids = ReadSync.idsFromMessage(o);
        if (ids.isEmpty()) return;
        SaleStore.markRead(this, ids);
        cancelSaleNotifications(ids);
        broadcastRefresh();
    }

    private boolean isSaleUpdate(String title) {
        String t = title == null ? "" : title.toUpperCase(Locale.ROOT);
        return t.contains("ACTUALIZACIÓN VENTA") || t.contains("ACTUALIZACION VENTA");
    }

    private boolean isDeliveryEvent(String title) {
        String t = title == null ? "" : title.toUpperCase(Locale.ROOT);
        return t.contains("PAQUETE ENTREGADO") && t.contains("ML CENTRAL");
    }

    private boolean handleStateMessage(JSONObject o) {
        String title = o.optString("title", "");
        if (!StateSync.STATE_SNAPSHOT_BEGIN_TITLE.equals(title)
                && !StateSync.STATE_SNAPSHOT_CHUNK_TITLE.equals(title)
                && !StateSync.STATE_SNAPSHOT_END_TITLE.equals(title)
                && !StateSync.STATE_CHANGE_RESULT_TITLE.equals(title)) return false;
        try {
            JSONObject body = new JSONObject(o.optString("message", "{}"));
            if (StateSync.STATE_SNAPSHOT_BEGIN_TITLE.equals(title)
                    && "state_snapshot_begin_v1".equals(body.optString("type", ""))) {
                StateStore.beginSnapshot(this, body.optString("batch_id", ""), body.optInt("total", 0));
            } else if (StateSync.STATE_SNAPSHOT_CHUNK_TITLE.equals(title)
                    && "state_snapshot_chunk_v1".equals(body.optString("type", ""))) {
                JSONArray sales = body.optJSONArray("sales");
                StateStore.mergeChunk(this, body.optString("batch_id", ""), sales);
            } else if (StateSync.STATE_SNAPSHOT_END_TITLE.equals(title)
                    && "state_snapshot_end_v1".equals(body.optString("type", ""))) {
                boolean complete = StateStore.completeSnapshot(this, body.optString("batch_id", ""), body.optInt("total", 0));
                if (!complete) StateSync.requestSnapshotAsync(this, true);
            } else if (StateSync.STATE_CHANGE_RESULT_TITLE.equals(title)) {
                StateSync.handleResult(this, body);
            }
            broadcastRefresh();
        } catch (Exception ignored) {}
        return true;
    }

    private boolean handleHistoryMessage(JSONObject o) {
        String title = o.optString("title", "");
        if (HISTORY_SALE_TITLE.equals(title)) {
            try {
                JSONObject body = new JSONObject(o.optString("message", "{}"));
                if (!"history_sale_v1".equals(body.optString("type", ""))) return true;
                String orderId = body.optString("order_id", "").trim();
                if (orderId.isEmpty()) return true;
                HistoryRestore.upsert(this, orderId, body.optString("display", ""), body.optLong("sale_unix", 0L));
                broadcastRefresh();
            } catch (Exception ignored) {}
            return true;
        }

        if (FULL_HISTORY_BEGIN_TITLE.equals(title)) {
            try {
                JSONObject body = new JSONObject(o.optString("message", "{}"));
                if (!"full_history_begin_v2".equals(body.optString("type", ""))) return true;
                HistoryRestore.beginFullRestore(this, body.optString("request_id", ""), body.optInt("total", 0));
                broadcastRefresh();
            } catch (Exception ignored) {}
            return true;
        }

        if (FULL_HISTORY_SALE_TITLE.equals(title)) {
            try {
                JSONObject body = new JSONObject(o.optString("message", "{}"));
                if (!"full_history_sale_v2".equals(body.optString("type", ""))) return true;
                String orderId = body.optString("order_id", "").trim();
                if (orderId.isEmpty()) return true;
                HistoryRestore.upsertFull(this, body.optString("request_id", ""), orderId,
                        body.optString("display", ""), body.optLong("sale_unix", 0L));
                broadcastRefresh();
            } catch (Exception ignored) {}
            return true;
        }

        if (FULL_HISTORY_END_TITLE.equals(title)) {
            try {
                JSONObject body = new JSONObject(o.optString("message", "{}"));
                if (!"full_history_end_v2".equals(body.optString("type", ""))) return true;
                HistoryRestore.completeFullRestore(this, body.optString("request_id", ""), body.optInt("total", 0));
                broadcastRefresh();
            } catch (Exception ignored) {}
            return true;
        }
        return false;
    }

    private void handleMessage(JSONObject o) {
        if (handleStateMessage(o)) return;
        if (handleHistoryMessage(o)) return;
        String saleId = o.optString("sequence_id", "").trim();
        if (saleId.isEmpty()) saleId = o.optString("id", "").trim();
        if (saleId.isEmpty()) return;
        String title = o.optString("title", "🛒 NUEVA VENTA — ML CENTRAL");
        String message = o.optString("message", "Venta nueva");
        long when = o.optLong("time", System.currentTimeMillis() / 1000L);

        if (isDeliveryEvent(title)) {
            String shipmentId = saleId.startsWith("delivery:") ? saleId.substring("delivery:".length()) : saleId;
            if (SaleStore.isDeliverySeen(this, shipmentId)) return;
            SaleStore.markDeliverySeen(this, shipmentId);
            showDeliveryNotification(shipmentId, title, message);
            broadcastRefresh();
            return;
        }
        if (isSaleUpdate(title)) {
            SaleStore.markSeen(this, saleId);
            SaleStore.updateHistory(this, saleId, title, message, when);
            broadcastRefresh();
            return;
        }
        if (SaleStore.isSeen(this, saleId)) return;
        boolean alreadyConfirmed = SaleStore.isAcknowledged(this, saleId);
        SaleStore.markSeen(this, saleId);
        SaleStore.addHistory(this, saleId, title, message, when);
        if (!alreadyConfirmed) showSaleNotification(saleId, title, message);
        broadcastRefresh();
    }

    private void broadcastRefresh() {
        sendBroadcast(new Intent("com.mlcentral.ventas.SALE_RECEIVED").setPackage(getPackageName()));
    }

    private void cancelSaleNotifications(List<String> ids) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        for (String id : ids) if (id != null && !id.isEmpty()) nm.cancel(1000 + Math.abs(id.hashCode() % 900000));
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(SERVICE_CHANNEL, "Servicio ML Central", NotificationManager.IMPORTANCE_MIN);
        service.setDescription("Mantiene la conexión para recibir ventas nuevas.");
        service.setShowBadge(false);
        nm.createNotificationChannel(service);

        NotificationChannel sales = new NotificationChannel(SALES_CHANNEL, "Ventas nuevas", NotificationManager.IMPORTANCE_HIGH);
        sales.setDescription("Aviso inmediato cuando ML Central detecta una venta.");
        sales.enableVibration(true);
        sales.setVibrationPattern(new long[]{0, 350, 140, 350, 140, 700});
        Uri sound = Uri.parse("android.resource://" + getPackageName() + "/" + com.mlcentral.ventas.R.raw.sale_chime);
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build();
        sales.setSound(sound, attrs);
        sales.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(sales);

        NotificationChannel delivered = new NotificationChannel(DELIVERY_CHANNEL, "Paquetes entregados", NotificationManager.IMPORTANCE_HIGH);
        delivered.setDescription("Aviso cuando Mercado Libre confirma que un paquete fue entregado.");
        delivered.enableVibration(true);
        delivered.setVibrationPattern(new long[]{0, 220, 120, 220});
        delivered.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(delivered);
    }

    private PendingIntent openAppIntent(int request) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, request, i, flags);
    }

    private Notification serviceNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, SERVICE_CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("ML Central Ventas activo")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(7))
                .build();
    }

    private void updateServiceNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(7, serviceNotification(text));
    }

    private void showSaleNotification(String saleId, String title, String message) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, SALES_CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(firstLine(message))
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(openAppIntent(saleId.hashCode()))
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setNumber(SaleStore.unread(this));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_MAX).setDefaults(Notification.DEFAULT_ALL);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1000 + Math.abs(saleId.hashCode() % 900000), b.build());
    }

    private void showDeliveryNotification(String shipmentId, String title, String message) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, DELIVERY_CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(firstLine(message))
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(openAppIntent(("delivery:" + shipmentId).hashCode()))
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(2000000 + Math.abs(shipmentId.hashCode() % 900000), b.build());
    }

    private String firstLine(String s) {
        if (s == null) return "Venta nueva";
        int n = s.indexOf('\n');
        return n > 0 ? s.substring(0, n) : s;
    }

    @Override public void onDestroy() {
        running = false;
        SaleStore.setConnected(this, false);
        if (worker != null) worker.interrupt();
        if (syncWorker != null) syncWorker.interrupt();
        if (stateWorker != null) stateWorker.interrupt();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
