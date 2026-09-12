package com.mlcentral.ventas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FirebaseListenerService extends Service {
    public static final String SERVICE_CHANNEL = "mlc_service";
    public static final String SALES_CHANNEL = "mlc_sales_urgent";
    public static final String DELIVERY_CHANNEL = "mlc_delivery_status_v1";
    public static final String HISTORY_SALE_TITLE = "MLC_HISTORY_SALE_V1";
    public static final String FULL_HISTORY_BEGIN_TITLE = "MLC_FULL_HISTORY_BEGIN_V2";
    public static final String FULL_HISTORY_SALE_TITLE = "MLC_FULL_HISTORY_SALE_V2";
    public static final String FULL_HISTORY_END_TITLE = "MLC_FULL_HISTORY_END_V2";

    private static final String MAIN_CURSOR = "firebase_main_cursor_v120";
    private static final String RS_CURSOR = "firebase_rs_cursor_v120";

    private volatile boolean running = false;
    private Thread stateWorker;
    private Query mainQuery;
    private Query rsQuery;
    private ChildEventListener mainListener;
    private ChildEventListener rsListener;
    private DatabaseReference connectedRef;
    private ValueEventListener connectedListener;

    @Override public void onCreate() {
        super.onCreate();
        FirebaseConfig.ensureInitialized(this);
        createChannels();
        startForeground(7, serviceNotification("Conectando con Firebase…"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        FirebaseConfig.ensureInitialized(this);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !FirebaseConfig.EXPECTED_UID.equals(user.getUid())) {
            setConnectionInfo(false, "Firebase: iniciá sesión");
            updateServiceNotification("Abrí ML Central Ventas para iniciar sesión");
            return START_STICKY;
        }
        if (!running) {
            running = true;
            watchConnection();
            attachChannel("channels/main", MAIN_CURSOR, true);
            attachChannel("channels/rs", RS_CURSOR, false);
            stateWorker = new Thread(this::stateMaintenanceLoop, "MLCentralFirebaseState");
            stateWorker.setDaemon(true);
            stateWorker.start();
        }
        return START_STICKY;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
    }

    private void watchConnection() {
        connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectedListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                Boolean value = snapshot.getValue(Boolean.class);
                boolean ok = Boolean.TRUE.equals(value);
                setConnectionInfo(ok, ok ? "" : "Firebase sin conexión");
                updateServiceNotification(ok ? "Conectado · Firebase" : "Reconectando con Firebase…");
            }
            @Override public void onCancelled(DatabaseError error) {
                setConnectionInfo(false, "Firebase: " + error.getMessage());
            }
        };
        connectedRef.addValueEventListener(connectedListener);
    }

    private void attachChannel(String path, String cursorKey, boolean main) {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference(path);
        String cursor = prefs().getString(cursorKey, "");
        if (cursor == null) cursor = "";
        final String initialCursor = cursor.trim();
        if (!initialCursor.isEmpty()) {
            attachFrom(ref, cursorKey, initialCursor, main);
            return;
        }

        ref.orderByKey().limitToLast(1).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                String newest = "";
                for (DataSnapshot child : snapshot.getChildren()) {
                    if (child.getKey() != null) newest = child.getKey();
                }
                if (!newest.isEmpty()) prefs().edit().putString(cursorKey, newest).apply();
                attachFrom(ref, cursorKey, newest, main);
                if (main) {
                    ReadSync.requestHistoryOnceAsync(FirebaseListenerService.this);
                    StateSync.requestSnapshotAsync(FirebaseListenerService.this, true);
                    StateSync.flushPendingAsync(FirebaseListenerService.this);
                } else {
                    ReadSync.flushPendingAsync(FirebaseListenerService.this);
                }
            }
            @Override public void onCancelled(DatabaseError error) {
                setConnectionInfo(false, "Firebase: " + error.getMessage());
            }
        });
    }

    private void attachFrom(DatabaseReference ref, String cursorKey, String cursor, boolean main) {
        Query query = ref.orderByKey();
        if (cursor != null && !cursor.trim().isEmpty()) query = query.startAt(cursor.trim());

        ChildEventListener listener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                String key = snapshot.getKey();
                if (key == null || key.trim().isEmpty()) return;
                String saved = prefs().getString(cursorKey, "");
                if (key.equals(saved)) return;
                JSONObject msg = snapshotToJson(snapshot);
                if (msg != null) {
                    if (main) handleMessage(msg);
                    else if (ReadSync.isReadSync(msg)) handleReadSync(msg);
                }
                prefs().edit().putString(cursorKey, key).apply();
            }
            @Override public void onChildChanged(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onChildRemoved(DataSnapshot snapshot) {}
            @Override public void onChildMoved(DataSnapshot snapshot, String previousChildName) {}
            @Override public void onCancelled(DatabaseError error) {
                setConnectionInfo(false, "Firebase: " + error.getMessage());
            }
        };
        query.addChildEventListener(listener);
        if (main) { mainQuery = query; mainListener = listener; }
        else { rsQuery = query; rsListener = listener; }
    }

    @SuppressWarnings("unchecked")
    private JSONObject snapshotToJson(DataSnapshot snapshot) {
        try {
            Object raw = snapshot.getValue();
            if (!(raw instanceof Map)) return null;
            JSONObject out = new JSONObject((Map<String, Object>) raw);
            if (!out.has("id") && snapshot.getKey() != null) out.put("id", snapshot.getKey());
            if (!out.has("event")) out.put("event", "message");
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void stateMaintenanceLoop() {
        while (running) {
            try {
                StateSync.flushPendingAsync(this);
                if (StateStore.isStale(this, 5 * 60 * 1000L)) StateSync.requestSnapshotAsync(this, false);
                Thread.sleep(120000L);
            } catch (InterruptedException ignored) {
            } catch (Exception ignored) {
                try { Thread.sleep(15000L); } catch (InterruptedException ignored2) {}
            }
        }
    }

    private void setConnectionInfo(boolean connected, String error) {
        SaleStore.setConnected(this, connected);
        prefs().edit()
                .putString("connection_mode", connected ? "firebase" : "reconnecting")
                .putString("connection_last_error", error == null ? "" : error)
                .apply();
        broadcastRefresh();
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

    private String firstMatching(String text, String regex) {
        if (text == null) return "";
        try {
            Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
            return m.find() ? m.group(1) : "";
        } catch (Exception ignored) { return ""; }
    }

    private String semanticId(JSONObject o, String title, String message) {
        String source = o.optString("source_id", "").trim();
        if (!source.isEmpty()) return source;
        String sequence = o.optString("sequence_id", "").trim();
        if (!sequence.isEmpty()) return sequence;
        String combined = (title == null ? "" : title) + "\n" + (message == null ? "" : message);
        String order = firstMatching(combined, "(?:orden|order|venta)[^0-9]{0,20}(20[0-9]{10,})");
        if (!order.isEmpty()) return order;
        String shipment = firstMatching(combined, "(?:shipment|env[ií]o)[^0-9]{0,20}([0-9]{8,})");
        if (!shipment.isEmpty()) return "delivery:" + shipment;
        return o.optString("id", "").trim();
    }

    private void handleMessage(JSONObject o) {
        if (handleStateMessage(o)) return;
        if (handleHistoryMessage(o)) return;
        String title = o.optString("title", "");
        if (title.startsWith("MLC_")) return;
        String message = o.optString("message", "Venta nueva");
        String saleId = semanticId(o, title, message);
        if (saleId.isEmpty()) return;
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
        String upper = title.toUpperCase(Locale.ROOT);
        if (!upper.contains("VENTA") && !upper.contains("ML CENTRAL")) return;
        if (SaleStore.isSeen(this, saleId)) return;
        boolean alreadyConfirmed = SaleStore.isAcknowledged(this, saleId);
        SaleStore.markSeen(this, saleId);
        SaleStore.addHistory(this, saleId, title.isEmpty() ? "🛒 NUEVA VENTA — ML CENTRAL" : title, message, when);
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
                .setContentTitle(title == null || title.trim().isEmpty() ? "🛒 NUEVA VENTA — ML CENTRAL" : title)
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
        if (stateWorker != null) stateWorker.interrupt();
        try { if (mainQuery != null && mainListener != null) mainQuery.removeEventListener(mainListener); } catch (Exception ignored) {}
        try { if (rsQuery != null && rsListener != null) rsQuery.removeEventListener(rsListener); } catch (Exception ignored) {}
        try { if (connectedRef != null && connectedListener != null) connectedRef.removeEventListener(connectedListener); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
