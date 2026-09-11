package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public final class HistoryRestore {
    private static final int MAX_HISTORY = 500;
    private HistoryRestore() {}

    public static synchronized void upsert(Context context, String orderId, String display, long saleUnix) {
        if (orderId == null || orderId.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
        JSONArray old;
        try { old = new JSONArray(p.getString("history", "[]")); }
        catch (Exception e) { old = new JSONArray(); }

        String wanted = orderId.trim();
        JSONObject item = new JSONObject();
        try {
            item.put("saleId", wanted);
            item.put("title", "🛒 VENTA — ML CENTRAL");
            item.put("message", display == null ? "" : display);
            item.put("time", saleUnix > 0 ? saleUnix : System.currentTimeMillis() / 1000L);
            item.put("read", true);
            item.put("updated", true);
        } catch (Exception ignored) {}

        int existing = -1;
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && wanted.equals(o.optString("saleId", "").trim())) { existing = i; break; }
        }

        JSONArray out = new JSONArray();
        if (existing < 0) {
            out.put(item);
            for (int i = 0; i < old.length() && out.length() < MAX_HISTORY; i++) out.put(old.opt(i));
        } else {
            for (int i = 0; i < old.length() && out.length() < MAX_HISTORY; i++) out.put(i == existing ? item : old.opt(i));
        }
        p.edit().putString("history", out.toString()).apply();
        SaleStore.markSeen(app, wanted);
    }
}
