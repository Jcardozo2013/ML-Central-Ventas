package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HistoryRestore {
    private static final int MAX_HISTORY = 5000;
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
        SaleStore.markSeen(app, wanted);
    }

    public static void upsert(Context context, String orderId, String display, long saleUnix) {
        upsertInternal(context, orderId, display, saleUnix);
        prefs(context).edit().putBoolean("history_restore_done_v1", true).apply();
    }

    public static void beginFullRestore(Context context, String requestId, int expected) {
        SharedPreferences p = prefs(context);
        String rid = requestId == null ? "" : requestId.trim();
        p.edit()
                .putString("full_history_active_request_v3", rid)
                .putInt("full_history_expected_v3", Math.max(0, expected))
                .putInt("full_history_received_v3", 0)
                .putStringSet("full_history_received_ids_v3", new HashSet<>())
                .putBoolean("full_history_restore_done_v3", false)
                .apply();
    }

    public static void upsertFull(Context context, String requestId, String orderId, String display, long saleUnix) {
        Context app = context.getApplicationContext();
        SharedPreferences p = prefs(app);
        String active = p.getString("full_history_active_request_v3", "");
        String rid = requestId == null ? "" : requestId.trim();
        if (active != null && !active.trim().isEmpty() && !active.trim().equals(rid)) return;
        String oid = orderId == null ? "" : orderId.trim();
        if (oid.isEmpty()) return;
        upsertInternal(app, oid, display, saleUnix);
        Set<String> ids = new HashSet<>(p.getStringSet("full_history_received_ids_v3", new HashSet<>()));
        ids.add(oid);
        p.edit()
                .putStringSet("full_history_received_ids_v3", ids)
                .putInt("full_history_received_v3", ids.size())
                .apply();
    }

    public static void completeFullRestore(Context context, String requestId, int total) {
        SharedPreferences p = prefs(context);
        String active = p.getString("full_history_active_request_v3", "");
        String rid = requestId == null ? "" : requestId.trim();
        if (active != null && !active.trim().isEmpty() && !active.trim().equals(rid)) return;
        int expected = Math.max(0, total);
        int received = p.getInt("full_history_received_v3", 0);
        boolean complete = expected == 0 || received >= expected;
        SharedPreferences.Editor e = p.edit()
                .putInt("full_history_expected_v3", expected)
                .putBoolean("full_history_restore_done_v3", complete)
                .putLong("full_history_completed_at_v3", complete ? System.currentTimeMillis() : 0L);
        if (complete) e.putString("full_history_active_request_v3", "");
        e.apply();
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
