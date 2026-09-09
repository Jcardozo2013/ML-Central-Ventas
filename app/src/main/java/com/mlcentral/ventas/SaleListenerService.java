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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SaleListenerService extends Service {
    public static final String SERVICE_CHANNEL = "mlc_service";
    public static final String SALES_CHANNEL = "mlc_sales_urgent";
    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(7, serviceNotification("Esperando ventas…"));
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
            worker = new Thread(this::listenLoop, "MLCentralVentasListener");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    private void listenLoop() {
        int backoff = 2;
        while (running) {
            HttpURLConnection conn = null;
            try {
                String last = SaleStore.getLastMessageId(this);
                String url = AppConfig.BASE_URL + AppConfig.TOPIC + "/json";
                if (last != null && !last.isEmpty()) {
                    url += "?since=" + URLEncoder.encode(last, StandardCharsets.UTF_8.name());
                }
                conn = (HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(0);
                conn.setRequestProperty("Accept", "application/x-ndjson");
                conn.setRequestProperty("User-Agent", "MLCentralVentas/1.2 Android");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                SaleStore.setConnected(this, true);
                ReadSync.flushPendingAsync(this);
                updateServiceNotification("Conectado · esperando ventas");
                backoff = 2;

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    JSONObject o;
                    try { o = new JSONObject(line); }
                    catch (Exception ignored) { continue; }
                    String event = o.optString("event", "");
                    String ntfyId = o.optString("id", "");
                    if ("open".equals(event) || "keepalive".equals(event)) {
                        if (!ntfyId.isEmpty()) SaleStore.setConnected(this, true);
                        continue;
                    }
                    if (!"message".equals(event)) continue;
                    handleMessage(o);
                    if (!ntfyId.isEmpty()) SaleStore.setLastMessageId(this, ntfyId);
                }
            } catch (Exception e) {
                SaleStore.setConnected(this, false);
                updateServiceNotification("Reconectando…");
                try { Thread.sleep(backoff * 1000L); } catch (InterruptedException ignored) {}
                backoff = Math.min(30, backoff * 2);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private void handleMessage(JSONObject o) {
        if (ReadSync.isReadSync(o)) {
            List<String> ids = ReadSync.idsFromMessage(o);
            if (!ids.isEmpty()) {
                SaleStore.markRead(this, ids);
                cancelSaleNotifications(ids);
                sendBroadcast(new Intent("com.mlcentral.ventas.SALE_RECEIVED").setPackage(getPackageName()));
            }
            return;
        }

        String saleId = o.optString("sequence_id", "").trim();
        if (saleId.isEmpty()) saleId = o.optString("id", "").trim();
        if (saleId.isEmpty()) return;
        if (SaleStore.isSeen(this, saleId)) return;

        String title = o.optString("title", "🛒 NUEVA VENTA — ML CENTRAL");
        String message = o.optString("message", "Venta nueva");
        long when = o.optLong("time", System.currentTimeMillis() / 1000L);
        boolean alreadyConfirmed = SaleStore.isAcknowledged(this, saleId);

        SaleStore.markSeen(this, saleId);
        SaleStore.addHistory(this, saleId, title, message, when);
        if (!alreadyConfirmed) showSaleNotification(saleId, title, message);
        sendBroadcast(new Intent("com.mlcentral.ventas.SALE_RECEIVED").setPackage(getPackageName()));
    }

    private void cancelSaleNotifications(List<String> ids) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            nm.cancel(1000 + Math.abs(id.hashCode() % 900000));
        }
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
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(7, serviceNotification(text));
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MAX).setDefaults(Notification.DEFAULT_ALL);
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1000 + Math.abs(saleId.hashCode() % 900000), b.build());
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
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
