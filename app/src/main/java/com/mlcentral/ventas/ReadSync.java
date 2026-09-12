package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

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

    public static String topic() { return AppConfig.TOPIC + "-rs"; }
    public static String streamUrl() { return FirebaseConfig.DATABASE_URL + "/channels/rs"; }

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
                if (publishRead(app, ids)) SaleStore.clearPendingReadSync(app, ids);
            } finally {
                sending = false;
            }
        }, "MLCentralReadSyncSender");
        t.setDaemon(true);
        t.start();
    }

    private static void migrateHistoryLoopFix(SharedPreferences p) {
        if (p.getBoolean("history_loop_fix_v117_migrated", false)) return;
        p.edit()
                .remove("full_history_active_request_v3")
                .remove("full_history_started_at_v3")
                .remove("full_history_received_ids_v3")
                .putInt("full_history_expected_v3", 0)
                .putInt("full_history_received_v3", 0)
                .putBoolean("full_history_restore_done_v3", false)
                .putLong("full_history_request_last_at_v3", 0L)
                .putString("full_history_request_id_v3", "")
                .putBoolean("history_loop_fix_v117_migrated", true)
                .apply();
    }

    public static void requestHistoryOnceAsync(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
        migrateHistoryLoopFix(p);
        if (!FirebaseTransport.signedIn(app)) return;
        if (p.getBoolean("full_history_restore_done_v3", false)) return;

        String active = p.getString("full_history_active_request_v3", "");
        if (active != null && !active.trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        long last = p.getLong("full_history_request_last_at_v3", 0L);
        if (last > 0 && now - last < HISTORY_RETRY_MS) return;

        synchronized (ReadSync.class) {
            if (requestingHistory) return;
            requestingHistory = true;
        }

        Thread t = new Thread(() -> {
            try {
                String requestId = UUID.randomUUID().toString();
                p.edit()
                        .putString("full_history_request_id_v3", requestId)
                        .putBoolean("full_history_restore_done_v3", false)
                        .apply();
                if (publishFullHistoryRequest(app, requestId)) {
                    p.edit().putLong("full_history_request_last_at_v3", System.currentTimeMillis()).apply();
                }
            } finally {
                requestingHistory = false;
            }
        }, "MLCentralFullHistoryRequest");
        t.setDaemon(true);
        t.start();
    }

    private static boolean publishRead(Context context, List<String> ids) {
        try {
            JSONArray arr = new JSONArray();
            for (String id : ids) if (id != null && !id.trim().isEmpty()) arr.put(id.trim());
            if (arr.length() == 0) return true;
            JSONObject body = new JSONObject();
            body.put("type", "read_sync_v1");
            body.put("ids", arr);
            body.put("at", System.currentTimeMillis() / 1000L);
            return FirebaseTransport.publish(context, topic(), TITLE, body, 1).ok;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean publishFullHistoryRequest(Context context, String requestId) {
        try {
            JSONObject body = new JSONObject();
            body.put("type", "full_history_request_v2");
            body.put("request_id", requestId == null ? "" : requestId.trim());
            body.put("at", System.currentTimeMillis() / 1000L);
            return FirebaseTransport.publish(context, topic(), FULL_HISTORY_REQUEST_TITLE, body, 1).ok;
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
