package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HistoryRestore {
    private static final int MAX_HISTORY = 5000;
    private static final long ACTIVE_STALE_MS = 10 * 60 * 1000L;

    // v1.22: durante una restauración completa juntamos las ventas en memoria
    // y escribimos el historial una sola vez al final. Esto evita reconstruir
    // y guardar 90+ veces el mismo JSON en celulares lentos.
    private static final Map<String, JSONObject> fullBuffer = new LinkedHashMap<>();
    private static String fullBufferRequestId = "";

    private HistoryRestore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject makeItem(String orderId, String display, long saleUnix) {
        JSONObject item = new JSONObject();
        try {
            item.put("saleId", orderId == null ? "" : orderId.trim());
            item.put("title", "🛒 VENTA — ML CENTRAL");
            item.put("message", display == null ? "" : display);
            item.put("time", saleUnix > 0 ? saleUnix : System.currentTimeMillis() / 1000L);
            item.put("read", true);
            item.put("updated", true);
        } catch (Exception ignored) {}
        return item;
    }

    private static synchronized void upsertInternal(Context context, String orderId, String display, long saleUnix) {
        if (orderId == null || orderId.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        SharedPreferences p = prefs(app);
        JSONArray old;
        try { old = new JSONArray(p.getString("history", "[]")); }
        catch (Exception e) { old = new JSONArray(); }

        Map<String, JSONObject> byId = new LinkedHashMap<>();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            String sid = o.optString("saleId", "").trim();
            if (!sid.isEmpty() && !byId.containsKey(sid)) byId.put(sid, o);
        }
        String wanted = orderId.trim();
        byId.put(wanted, makeItem(wanted, display, saleUnix));

        writeSortedHistory(p, byId);
        SaleStore.markSeen(app, wanted);
    }

    private static void writeSortedHistory(SharedPreferences p, Map<String, JSONObject> byId) {
        List<JSONObject> items = new ArrayList<>(byId.values());
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                long ta = a == null ? 0L : a.optLong("time", 0L);
                long tb = b == null ? 0L : b.optLong("time", 0L);
                return Long.compare(tb, ta);
            }
        });

        JSONArray out = new JSONArray();
        for (int i = 0; i < items.size() && i < MAX_HISTORY; i++) out.put(items.get(i));
        p.edit().putString("history", out.toString()).apply();
    }

    public static void upsert(Context context, String orderId, String display, long saleUnix) {
        upsertInternal(context, orderId, display, saleUnix);
        prefs(context).edit().putBoolean("history_restore_done_v1", true).apply();
    }

    public static synchronized void beginFullRestore(Context context, String requestId, int expected) {
        SharedPreferences p = prefs(context);
        String rid = requestId == null ? "" : requestId.trim();
        if (rid.isEmpty()) return;

        String wanted = p.getString("full_history_request_id_v3", "");
        if (wanted != null && !wanted.trim().isEmpty() && !wanted.trim().equals(rid)) {
            return;
        }

        if (p.getBoolean("full_history_restore_done_v3", false)) {
            return;
        }

        String active = p.getString("full_history_active_request_v3", "");
        long startedAt = p.getLong("full_history_started_at_v3", 0L);
        long now = System.currentTimeMillis();

        if (active != null && !active.trim().isEmpty()) {
            if (active.trim().equals(rid)) {
                return;
            }
            if (startedAt > 0L && now - startedAt < ACTIVE_STALE_MS) {
                return;
            }
        }

        fullBuffer.clear();
        fullBufferRequestId = rid;

        p.edit()
                .putString("full_history_active_request_v3", rid)
                .putLong("full_history_started_at_v3", now)
                .putInt("full_history_expected_v3", Math.max(0, expected))
                .putInt("full_history_received_v3", 0)
                .putBoolean("full_history_restore_done_v3", false)
                .apply();
    }

    public static synchronized void upsertFull(Context context, String requestId, String orderId, String display, long saleUnix) {
        SharedPreferences p = prefs(context);
        String active = p.getString("full_history_active_request_v3", "");
        String rid = requestId == null ? "" : requestId.trim();
        if (active != null && !active.trim().isEmpty() && !active.trim().equals(rid)) return;

        String oid = orderId == null ? "" : orderId.trim();
        if (oid.isEmpty()) return;

        if (!rid.equals(fullBufferRequestId)) {
            fullBuffer.clear();
            fullBufferRequestId = rid;
        }

        fullBuffer.put(oid, makeItem(oid, display, saleUnix));
        p.edit().putInt("full_history_received_v3", fullBuffer.size()).apply();
    }

    public static synchronized void completeFullRestore(Context context, String requestId, int total) {
        Context app = context.getApplicationContext();
        SharedPreferences p = prefs(app);
        String active = p.getString("full_history_active_request_v3", "");
        String rid = requestId == null ? "" : requestId.trim();
        if (active != null && !active.trim().isEmpty() && !active.trim().equals(rid)) return;

        Map<String, JSONObject> byId = new LinkedHashMap<>();
        try {
            JSONArray old = new JSONArray(p.getString("history", "[]"));
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                String sid = o.optString("saleId", "").trim();
                if (!sid.isEmpty() && !byId.containsKey(sid)) byId.put(sid, o);
            }
        } catch (Exception ignored) {}

        if (rid.equals(fullBufferRequestId)) {
            byId.putAll(fullBuffer);
        }
        writeSortedHistory(p, byId);

        int expected = Math.max(0, total);
        int received = rid.equals(fullBufferRequestId) ? fullBuffer.size() : p.getInt("full_history_received_v3", 0);
        boolean complete = expected == 0 || received >= expected;

        SharedPreferences.Editor e = p.edit()
                .putInt("full_history_expected_v3", expected)
                .putInt("full_history_received_v3", received)
                .putBoolean("full_history_restore_done_v3", complete)
                .putLong("full_history_completed_at_v3", complete ? System.currentTimeMillis() : 0L);
        if (complete) {
            e.putString("full_history_active_request_v3", "")
                    .putLong("full_history_started_at_v3", 0L);
        }
        e.apply();

        if (complete) {
            fullBuffer.clear();
            fullBufferRequestId = "";
        }
    }

    private static int historyCount(Context context) {
        try { return new JSONArray(prefs(context).getString("history", "[]")).length(); }
        catch (Exception ignored) { return 0; }
    }

    public static String statusText(Context context) {
        SharedPreferences p = prefs(context);
        boolean done = p.getBoolean("full_history_restore_done_v3", false);
        int expected = p.getInt("full_history_expected_v3", 0);
        int received = p.getInt("full_history_received_v3", 0);
        String active = p.getString("full_history_active_request_v3", "");
        if (done) {
            int count = expected > 0 ? Math.max(received, expected) : historyCount(context);
            return "Historial completo: " + count + " ventas";
        }
        if (active != null && !active.trim().isEmpty()) {
            if (expected > 0) return "Restaurando historial: " + received + "/" + expected;
            return "Restaurando historial…";
        }
        return "Historial: esperando sincronización con Windows…";
    }
}
