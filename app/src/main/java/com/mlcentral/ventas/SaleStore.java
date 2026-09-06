package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SaleStore {
    private static final int MAX_SEEN = 400;
    private static final int MAX_HISTORY = 100;

    private SaleStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized boolean isSeen(Context c, String saleId) {
        if (saleId == null || saleId.trim().isEmpty()) return false;
        Set<String> set = new HashSet<>(prefs(c).getStringSet("seen_sales", new HashSet<>()));
        return set.contains(saleId);
    }

    public static synchronized void markSeen(Context c, String saleId) {
        if (saleId == null || saleId.trim().isEmpty()) return;
        SharedPreferences p = prefs(c);
        Set<String> set = new HashSet<>(p.getStringSet("seen_sales", new HashSet<>()));
        set.add(saleId);
        if (set.size() > MAX_SEEN) {
            // Conserva los IDs que todavía aparecen en el historial visible.
            Set<String> keep = new HashSet<>();
            try {
                JSONArray arr = new JSONArray(p.getString("history", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optJSONObject(i) != null ? arr.optJSONObject(i).optString("saleId", "") : "";
                    if (!id.isEmpty()) keep.add(id);
                }
            } catch (Exception ignored) {}
            set = keep;
            set.add(saleId);
        }
        p.edit().putStringSet("seen_sales", set).apply();
    }

    public static synchronized void addHistory(Context c, String saleId, String title, String message, long unixTime) {
        SharedPreferences p = prefs(c);
        JSONArray old;
        try { old = new JSONArray(p.getString("history", "[]")); }
        catch (Exception e) { old = new JSONArray(); }

        JSONArray out = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("saleId", saleId == null ? "" : saleId);
            item.put("title", title == null ? "NUEVA VENTA" : title);
            item.put("message", message == null ? "" : message);
            item.put("time", unixTime > 0 ? unixTime : System.currentTimeMillis() / 1000L);
            item.put("read", false);
        } catch (Exception ignored) {}
        out.put(item);
        for (int i = 0; i < old.length() && out.length() < MAX_HISTORY; i++) out.put(old.opt(i));
        p.edit().putString("history", out.toString()).apply();
        p.edit().putInt("unread", p.getInt("unread", 0) + 1).apply();
    }

    public static int unread(Context c) { return prefs(c).getInt("unread", 0); }

    public static void markAllRead(Context c) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray arr = new JSONArray(p.getString("history", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) o.put("read", true);
            }
            p.edit().putString("history", arr.toString()).putInt("unread", 0).apply();
        } catch (Exception e) {
            p.edit().putInt("unread", 0).apply();
        }
    }

    public static String historyText(Context c) {
        SharedPreferences p = prefs(c);
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(p.getString("history", "[]"));
            if (arr.length() == 0) return "Todavía no hay ventas recibidas en este celular.";
            SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long t = o.optLong("time", 0L) * 1000L;
                String when = t > 0 ? fmt.format(new Date(t)) : "";
                sb.append(i == 0 ? "" : "\n\n────────────────────────\n\n");
                sb.append(when).append("\n");
                sb.append(o.optString("title", "NUEVA VENTA")).append("\n");
                sb.append(o.optString("message", ""));
            }
        } catch (Exception e) {
            return "No se pudo leer el historial.";
        }
        return sb.toString();
    }

    public static void setLastMessageId(Context c, String id) {
        if (id != null && !id.isEmpty()) prefs(c).edit().putString("last_ntfy_id", id).apply();
    }

    public static String getLastMessageId(Context c) { return prefs(c).getString("last_ntfy_id", ""); }

    public static void setConnected(Context c, boolean value) {
        prefs(c).edit().putBoolean("connected", value).putLong("connected_at", System.currentTimeMillis()).apply();
    }

    public static boolean connected(Context c) { return prefs(c).getBoolean("connected", false); }
    public static long connectedAt(Context c) { return prefs(c).getLong("connected_at", 0L); }
}
