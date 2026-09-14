package com.mlcentral.ventas;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;

public final class StateSync {
    public static final String STATE_REQUEST_TITLE = "MLC_STATE_REQUEST_V1";
    public static final String STATE_CHANGE_TITLE = "MLC_STATE_CHANGE_V1";
    public static final String STATE_SNAPSHOT_BEGIN_TITLE = "MLC_STATE_SNAPSHOT_BEGIN_V1";
    public static final String STATE_SNAPSHOT_CHUNK_TITLE = "MLC_STATE_SNAPSHOT_CHUNK_V1";
    public static final String STATE_SNAPSHOT_END_TITLE = "MLC_STATE_SNAPSHOT_END_V1";
    public static final String STATE_CHANGE_RESULT_TITLE = "MLC_STATE_CHANGE_RESULT_V1";

    private static final String PENDING_KEY = "pending_state_commands_v1";
    private static final long REQUEST_MIN_MS = 120000L;
    private static final int REQUEST_ATTEMPTS = 3;
    private static final long REQUEST_RETRY_MS = 1800L;
    private static volatile boolean requesting = false;
    private static volatile boolean flushing = false;

    // v1.28: además del stream histórico de bloques, escuchamos una copia completa
    // y durable del tablero en Firebase. Así un teléfono no puede quedar clavado en
    // "recibiendo 36/102" si perdió uno de los eventos intermedios.
    private static volatile boolean durableListening = false;
    private static DatabaseReference durableRef;
    private static ValueEventListener durableListener;
    private static volatile String lastDurableSalesJson = "";
    private static volatile int lastDurableTotal = 0;
    private static volatile long lastDurableRemoteAtMs = 0L;

    private StateSync() {}

    private static final class PostResult {
        final boolean ok;
        final String detail;
        PostResult(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail == null ? "" : detail.trim();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
    }

    private static String topic() { return ReadSync.topic(); }

    private static void notifyUi(Context context) {
        try {
            Context app = context.getApplicationContext();
            app.sendBroadcast(new Intent("com.mlcentral.ventas.SALE_RECEIVED").setPackage(app.getPackageName()));
        } catch (Exception ignored) {}
    }

    private static void setStatus(Context context, String text) {
        StateStore.setStatus(context, text);
        notifyUi(context);
    }

    private static String exceptionDetail(Exception e) {
        String name = e == null ? "Error" : e.getClass().getSimpleName();
        String msg = e == null || e.getMessage() == null ? "" : e.getMessage().trim();
        if (msg.length() > 100) msg = msg.substring(0, 100);
        return msg.isEmpty() ? name : name + " · " + msg;
    }

    private static PostResult post(Context context, String title, JSONObject body) {
        FirebaseTransport.Result result = FirebaseTransport.publish(context, topic(), title, body, 1);
        return new PostResult(result.ok, result.detail);
    }

    @SuppressWarnings("unchecked")
    private static void applyDurableSnapshot(Context context, DataSnapshot snapshot) {
        Context app = context.getApplicationContext();
        try {
            DataSnapshot salesNode = snapshot.child("sales");
            Object raw = salesNode.getValue();
            if (!(raw instanceof Map)) return;
            JSONObject sales = new JSONObject((Map<String, Object>) raw);
            int received = sales.length();
            int declared = 0;
            try {
                Long t = snapshot.child("total").getValue(Long.class);
                if (t != null) declared = t.intValue();
            } catch (Exception ignored) {}
            int total = declared > 0 ? declared : received;
            if (received <= 0 && total > 0) return;

            long remoteAtMs = 0L;
            try {
                Long at = snapshot.child("updated_at").getValue(Long.class);
                if (at != null && at > 0) remoteAtMs = at * 1000L;
            } catch (Exception ignored) {}
            if (remoteAtMs <= 0) remoteAtMs = System.currentTimeMillis();

            SharedPreferences p = prefs(app);
            p.edit()
                    .putString("state_sales_v1", sales.toString())
                    .putString("state_snapshot_active_v1", "")
                    .putLong("state_last_sync_at_v1", remoteAtMs)
                    .putString("state_last_status_v1", "")
                    .putLong("state_last_status_at_v1", remoteAtMs)
                    .putBoolean("state_durable_v2_seen", true)
                    .apply();

            lastDurableSalesJson = sales.toString();
            lastDurableTotal = total;
            lastDurableRemoteAtMs = remoteAtMs;
            notifyUi(app);

            // El protocolo viejo todavía se conserva para compatibilidad con v1.27.
            // Puede llegar DESPUÉS de esta copia y dejar un "recibiendo X/Y" si el
            // teléfono pierde un bloque. A los 15 s restauramos la copia durable si
            // quedó un snapshot heredado abierto.
            Thread repair = new Thread(() -> {
                try { Thread.sleep(15000L); } catch (InterruptedException ignored) { return; }
                try {
                    SharedPreferences rp = prefs(app);
                    String active = rp.getString("state_snapshot_active_v1", "");
                    if (active == null || active.trim().isEmpty()) return;
                    String cached = lastDurableSalesJson;
                    if (cached == null || cached.trim().isEmpty()) return;
                    JSONObject check = new JSONObject(cached);
                    int count = check.length();
                    if (count <= 0 && lastDurableTotal > 0) return;
                    long at = lastDurableRemoteAtMs > 0 ? lastDurableRemoteAtMs : System.currentTimeMillis();
                    rp.edit()
                            .putString("state_sales_v1", cached)
                            .putString("state_snapshot_active_v1", "")
                            .putLong("state_last_sync_at_v1", at)
                            .putString("state_last_status_v1", "")
                            .putLong("state_last_status_at_v1", at)
                            .putBoolean("state_durable_v2_seen", true)
                            .apply();
                    notifyUi(app);
                } catch (Exception ignored) {}
            }, "MLCentralDurableStateRepair");
            repair.setDaemon(true);
            repair.start();
        } catch (Exception ignored) {}
    }

    private static void ensureDurableListener(Context context) {
        Context app = context.getApplicationContext();
        synchronized (StateSync.class) {
            if (durableListening) return;
            durableListening = true;
        }
        try {
            durableRef = FirebaseDatabase.getInstance().getReference("state/current");
            durableListener = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    applyDurableSnapshot(app, snapshot);
                }

                @Override public void onCancelled(DatabaseError error) {
                    // Si ya tenemos un tablero reciente, no ensuciar la pantalla con
                    // un error transitorio de escritura/lectura. El listener se
                    // reconecta solo cuando vuelva la red.
                    if (StateStore.isStale(app, 2 * 60 * 1000L)) {
                        setStatus(app, "Estados PC: reconectando Firebase…");
                    }
                }
            };
            durableRef.addValueEventListener(durableListener);
        } catch (Exception e) {
            synchronized (StateSync.class) { durableListening = false; }
            if (StateStore.isStale(app, 2 * 60 * 1000L)) {
                setStatus(app, "Estados PC: reconectando Firebase…");
            }
        }
    }

    public static void requestSnapshotAsync(Context context, boolean force) {
        Context app = context.getApplicationContext();
        ensureDurableListener(app);
        SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        long last = p.getLong("state_request_last_at_v1", 0L);
        if (!force && last > 0 && now - last < REQUEST_MIN_MS && !StateStore.isStale(app, 5 * 60 * 1000L)) return;
        synchronized (StateSync.class) {
            if (requesting) return;
            requesting = true;
        }

        final long requestStartedAt = System.currentTimeMillis();
        if (StateStore.isStale(app, 60 * 1000L)) setStatus(app, "Estados PC: enviando solicitud…");
        Thread t = new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("type", "state_request_v1");
                body.put("request_id", UUID.randomUUID().toString());
                body.put("at", System.currentTimeMillis() / 1000L);

                PostResult lastResult = new PostResult(false, "sin respuesta");
                for (int attempt = 1; attempt <= REQUEST_ATTEMPTS; attempt++) {
                    lastResult = post(app, STATE_REQUEST_TITLE, body);
                    if (lastResult.ok) break;
                    if (attempt < REQUEST_ATTEMPTS) {
                        if (StateStore.isStale(app, 60 * 1000L)) {
                            setStatus(app, "Estados PC: reconectando " + (attempt + 1) + "/" + REQUEST_ATTEMPTS + "…");
                        }
                        try { Thread.sleep(REQUEST_RETRY_MS); } catch (InterruptedException ignored) {}
                    }
                }

                if (lastResult.ok) {
                    p.edit().putLong("state_request_last_at_v1", System.currentTimeMillis()).apply();
                    // Si mientras enviábamos la petición ya llegó state/current,
                    // conservar el estado bueno en pantalla en vez de reemplazarlo
                    // por "esperando Windows".
                    if (StateStore.lastSyncAt(app) < requestStartedAt) {
                        setStatus(app, "Estados PC: solicitud enviada · esperando Windows…");
                    } else {
                        notifyUi(app);
                    }
                } else {
                    // Un timeout al PUBLICAR la solicitud no invalida un listener
                    // Firebase que sigue recibiendo state/current correctamente.
                    if (StateStore.isStale(app, 60 * 1000L)) {
                        setStatus(app, "Estados PC: reconectando Firebase…");
                    } else {
                        notifyUi(app);
                    }
                }
            } catch (Exception e) {
                if (StateStore.isStale(app, 60 * 1000L)) {
                    setStatus(app, "Estados PC: reconectando Firebase…");
                } else {
                    notifyUi(app);
                }
            } finally {
                requesting = false;
            }
        }, "MLCentralStateRequest");
        t.setDaemon(true);
        t.start();
    }

    private static JSONArray pending(Context c) {
        try { return new JSONArray(prefs(c).getString(PENDING_KEY, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static synchronized String queueCommand(Context context, String orderId, String action, String expectedStage, String eventId) {
        Context app = context.getApplicationContext();
        String oid = orderId == null ? "" : orderId.trim();
        String act = action == null ? "" : action.trim();
        if (oid.isEmpty() || act.isEmpty()) return "";
        JSONArray old = pending(app);
        for (int i = 0; i < old.length(); i++) {
            JSONObject x = old.optJSONObject(i);
            if (x != null && oid.equals(x.optString("order_id", ""))) return x.optString("command_id", "");
        }
        String commandId = UUID.randomUUID().toString();
        JSONObject cmd = new JSONObject();
        try {
            cmd.put("type", "state_change_v1");
            cmd.put("command_id", commandId);
            cmd.put("order_id", oid);
            cmd.put("action", act);
            cmd.put("expected_stage", expectedStage == null ? "" : expectedStage.trim());
            if (eventId != null && !eventId.trim().isEmpty()) cmd.put("event_id", eventId.trim());
            cmd.put("at", System.currentTimeMillis() / 1000L);
            old.put(cmd);
        } catch (Exception ignored) {}
        prefs(app).edit().putString(PENDING_KEY, old.toString()).apply();
        StateStore.setStatus(app, "Cambio pendiente de confirmar en la PC");
        flushPendingAsync(app);
        return commandId;
    }

    public static synchronized String queueAction(Context context, String orderId, String action, String expectedStage) {
        return queueCommand(context, orderId, action, expectedStage, "");
    }

    public static synchronized String queueUndo(Context context, String eventId, String orderId, String expectedStage) {
        return queueCommand(context, orderId, "undo_state_change", expectedStage, eventId);
    }

    public static void flushPendingAsync(Context context) {
        Context app = context.getApplicationContext();
        ensureDurableListener(app);
        synchronized (StateSync.class) {
            if (flushing) return;
            flushing = true;
        }
        Thread t = new Thread(() -> {
            try {
                JSONArray arr = pending(app);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject cmd = arr.optJSONObject(i);
                    if (cmd == null) continue;
                    post(app, STATE_CHANGE_TITLE, cmd);
                    try { Thread.sleep(150L); } catch (InterruptedException ignored) {}
                }
            } finally {
                flushing = false;
            }
        }, "MLCentralStateCommandSender");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized int pendingCount(Context context) {
        return pending(context).length();
    }

    public static synchronized boolean hasPendingForOrder(Context context, String orderId) {
        String oid = orderId == null ? "" : orderId.trim();
        JSONArray arr = pending(context);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x != null && oid.equals(x.optString("order_id", ""))) return true;
        }
        return false;
    }

    private static boolean success(JSONObject body) {
        if (body == null) return false;
        Object raw = body.opt("success");
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() == 1;
        String s = raw == null ? "" : String.valueOf(raw).trim().toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s);
    }

    public static synchronized void handleResult(Context context, JSONObject body) {
        if (body == null || !"state_change_result_v1".equals(body.optString("type", ""))) return;
        Context app = context.getApplicationContext();
        String commandId = body.optString("command_id", "").trim();
        JSONArray old = pending(app);
        JSONArray out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject x = old.optJSONObject(i);
            if (x == null) continue;
            if (!commandId.equals(x.optString("command_id", ""))) out.put(x);
        }
        prefs(app).edit().putString(PENDING_KEY, out.toString()).apply();
        JSONObject state = body.optJSONObject("state");
        if (state != null) StateStore.upsertState(app, state);
        boolean ok = success(body);
        String message = body.optString("message", ok ? "Cambio confirmado" : "Cambio rechazado");
        StateStore.setStatus(app, (ok ? "PC confirmó: " : "PC rechazó: ") + message);
        notifyUi(app);
    }
}
