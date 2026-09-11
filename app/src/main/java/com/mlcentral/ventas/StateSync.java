package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    private static volatile boolean requesting = false;
    private static volatile boolean flushing = false;

    private StateSync() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
    }

    private static String topic() { return ReadSync.topic(); }

    private static boolean post(String title, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(AppConfig.BASE_URL + topic());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setRequestProperty("Title", title);
            conn.setRequestProperty("Priority", "min");
            conn.setRequestProperty("User-Agent", "MLCentralVentas/1.9 Android");
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static void requestSnapshotAsync(Context context, boolean force) {
        Context app = context.getApplicationContext();
        SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        long last = p.getLong("state_request_last_at_v1", 0L);
        if (!force && last > 0 && now - last < REQUEST_MIN_MS && !StateStore.isStale(app, 5 * 60 * 1000L)) return;
        synchronized (StateSync.class) {
            if (requesting) return;
            requesting = true;
        }
        Thread t = new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("type", "state_request_v1");
                body.put("request_id", UUID.randomUUID().toString());
                body.put("at", System.currentTimeMillis() / 1000L);
                if (post(STATE_REQUEST_TITLE, body)) {
                    p.edit().putLong("state_request_last_at_v1", System.currentTimeMillis()).apply();
                    StateStore.setStatus(app, "Estados PC: solicitud enviada · esperando Windows…");
                }
            } catch (Exception ignored) {
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

    public static synchronized String queueAction(Context context, String orderId, String action, String expectedStage) {
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
            cmd.put("at", System.currentTimeMillis() / 1000L);
            old.put(cmd);
        } catch (Exception ignored) {}
        prefs(app).edit().putString(PENDING_KEY, old.toString()).apply();
        StateStore.setStatus(app, "Cambio pendiente de confirmar en la PC");
        flushPendingAsync(app);
        return commandId;
    }

    public static void flushPendingAsync(Context context) {
        Context app = context.getApplicationContext();
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
                    post(STATE_CHANGE_TITLE, cmd);
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
        String message = body.optString("message", body.optInt("success", 0) == 1 ? "Cambio confirmado" : "Cambio rechazado");
        StateStore.setStatus(app, (body.optInt("success", 0) == 1 ? "PC confirmó: " : "PC rechazó: ") + message);
    }
}
