package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class ReadSync {
    public static final String TITLE = "MLC_READ_SYNC_V1";
    public static final String HISTORY_REQUEST_TITLE = "MLC_HISTORY_REQUEST_V1";
    public static final String FULL_HISTORY_REQUEST_TITLE = "MLC_FULL_HISTORY_REQUEST_V2";
    private static final long HISTORY_RETRY_MS = 120000L;
    private static volatile boolean sending = false;
    private static volatile boolean requestingHistory = false;

    private ReadSync() {}

    // El topic base actual ya mide 57 caracteres. ntfy admite hasta 64.
    // Usar "-readsync" lo llevaba a 66 y ntfy devolvía HTTP 400 topic invalid.
    // "-rs" mantiene el canal privado derivado y queda dentro del límite.
    public static String topic() { return AppConfig.TOPIC + "-rs"; }
    public static String streamUrl() { return AppConfig.BASE_URL + topic() + "/json"; }

    public static void acknowledgeAsync(Context context, Collection<String> ids) {
        Context app = context.getApplicationContext();
        SaleStore.queueReadSync(app, ids);
        flushPendingAsync(app);
    }

    public static void flushPendingAsync(Context context) {
        Context app = context.getApplicationContext();
        synchronized (ReadSync.class) {
            if (sending) return;
            sending = true;
        }
        Thread t = new Thread(() -> {
            try {
                List<String> ids = SaleStore.pendingReadSync(app);
                if (ids.isEmpty()) return;
                if (publishRead(ids)) SaleStore.clearPendingReadSync(app, ids);
            } finally {
                sending = false;
            }
        }, "MLCentralReadSyncSender");
        t.setDaemon(true);
        t.start();
    }

    public static void requestHistoryOnceAsync(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("full_history_restore_done_v3", false)) return;

        long now = System.currentTimeMillis();
        long last = p.getLong("full_history_request_last_at_v3", 0L);
        if (last > 0 && now - last < HISTORY_RETRY_MS) return;

        synchronized (ReadSync.class) {
            if (requestingHistory) return;
            requestingHistory = true;
        }

        Thread t = new Thread(() -> {
            try {
                // Cada reintento usa un request_id NUEVO. Así, si el BEGIN/FIN anterior
                // se perdió en la red, Windows no lo considera ya atendido y vuelve a
                // enviar el historial completo.
                String requestId = UUID.randomUUID().toString();
                p.edit()
                        .putString("full_history_request_id_v3", requestId)
                        .putBoolean("full_history_restore_done_v3", false)
                        .apply();
                if (publishFullHistoryRequest(requestId)) {
                    p.edit().putLong("full_history_request_last_at_v3", System.currentTimeMillis()).apply();
                }
            } finally {
                requestingHistory = false;
            }
        }, "MLCentralFullHistoryRequest");
        t.setDaemon(true);
        t.start();
    }

    private static boolean post(String title, byte[] data, String userAgent) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(AppConfig.BASE_URL + topic());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setRequestProperty("Title", title);
            conn.setRequestProperty("Priority", "min");
            conn.setRequestProperty("User-Agent", userAgent);
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

    private static boolean publishRead(List<String> ids) {
        try {
            JSONArray arr = new JSONArray();
            for (String id : ids) if (id != null && !id.trim().isEmpty()) arr.put(id.trim());
            if (arr.length() == 0) return true;
            JSONObject body = new JSONObject();
            body.put("type", "read_sync_v1");
            body.put("ids", arr);
            body.put("at", System.currentTimeMillis() / 1000L);
            return post(TITLE, body.toString().getBytes(StandardCharsets.UTF_8), "MLCentralVentas/1.14 Android");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean publishFullHistoryRequest(String requestId) {
        try {
            JSONObject body = new JSONObject();
            body.put("type", "full_history_request_v2");
            body.put("request_id", requestId == null ? "" : requestId.trim());
            body.put("at", System.currentTimeMillis() / 1000L);
            return post(FULL_HISTORY_REQUEST_TITLE, body.toString().getBytes(StandardCharsets.UTF_8), "MLCentralVentas/1.14 Android");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isReadSync(JSONObject message) {
        return message != null && TITLE.equals(message.optString("title", ""));
    }

    public static List<String> idsFromMessage(JSONObject message) {
        ArrayList<String> ids = new ArrayList<>();
        if (message == null) return ids;
        try {
            JSONObject body = new JSONObject(message.optString("message", "{}"));
            if (!"read_sync_v1".equals(body.optString("type", ""))) return ids;
            JSONArray arr = body.optJSONArray("ids");
            if (arr == null) return ids;
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "").trim();
                if (!id.isEmpty()) ids.add(id);
            }
        } catch (Exception ignored) {}
        return ids;
    }
}
