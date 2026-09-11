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
    private HistoryRestore() {}

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
        SharedPreferences p = app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
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
        context.getApplicationContext().getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("history_restore_done_v1", true).apply();
    }

    public static void beginFullRestore(Context context, String requestId, int expected) {
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
        String rid = requestId == null ? "" : requestId.trim();
        p.edit()
                .putString("full_history_active_request_v2", rid)
                .putInt("full_history_expected_v2", Math.max(0, expected))
                .putInt("full_history_received_v2", 0)
                .putBoolean("full_history_restore_done_v2", false)
                .apply();
    }

    public static void upsertFull(Context context, String requestId, String orderId, String display, long saleUnix) {
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
        String active = p.getString("full_history_active_request_v2", "");
        String rid = requestId == null ? "" : requestId.trim();
        if (active != null && !active.trim().isEmpty() && !active.trim().equals(rid)) return;
        upsertInternal(app, orderId, display, saleUnix);
        int n = p.getInt("full_history_received_v2", 0);
        p.edit().putInt("full_history_received_v2", n + 1).apply();
    }

    public static void completeFullRestore(Context context, String requestId, int total) {
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
        String active = p.getString("full_history_active_request_v2", "");
        String rid = requestId == null ? "" : requestId.trim();
        if (active != null && !active.trim().isEmpty() && !active.trim().equals(rid)) return;
        p.edit()
                .putBoolean("full_history_restore_done_v2", true)
                .putInt("full_history_expected_v2", Math.max(0, total))
                .putString("full_history_active_request_v2", "")
                .putLong("full_history_completed_at_v2", System.currentTimeMillis())
                .apply();
    }
}
