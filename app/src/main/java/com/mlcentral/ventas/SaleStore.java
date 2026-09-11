package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SaleStore {
    private static final int MAX_SEEN = 1000;
    private static final int MAX_ACKED = 1000;
    private static final int MAX_HISTORY = 500;

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
            Set<String> keep = new HashSet<>();
            try {
                JSONArray arr = new JSONArray(p.getString("history", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    String id = o != null ? o.optString("saleId", "") : "";
                    if (!id.isEmpty()) keep.add(id);
                }
            } catch (Exception ignored) {}
            set = keep;
            set.add(saleId);
        }
        p.edit().putStringSet("seen_sales", set).apply();
    }

    public static synchronized boolean isAcknowledged(Context c, String saleId) {
        if (saleId == null || saleId.trim().isEmpty()) return false;
        return prefs(c).getStringSet("acknowledged_sales", new HashSet<>()).contains(saleId);
    }

    public static synchronized void addHistory(Context c, String saleId, String title, String message, long unixTime) {
        upsertHistory(c, saleId, title, message, unixTime, false);
    }

    public static synchronized void updateHistory(Context c, String saleId, String title, String message, long unixTime) {
        upsertHistory(c, saleId, title, message, unixTime, true);
    }

    private static void upsertHistory(Context c, String saleId, String title, String message, long unixTime, boolean isUpdate) {
        SharedPreferences p = prefs(c);
        JSONArray old;
        try { old = new JSONArray(p.getString("history", "[]")); }
        catch (Exception e) { old = new JSONArray(); }

        String wantedId = saleId == null ? "" : saleId.trim();
        JSONObject existing = null;
        int existingIndex = -1;
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && wantedId.equals(o.optString("saleId", "").trim())) {
                existing = o;
                existingIndex = i;
                break;
            }
        }

        boolean alreadyRead = existing != null ? existing.optBoolean("read", false) : isAcknowledged(c, wantedId);
        if (isUpdate && existing == null) alreadyRead = true;
        long effectiveTime = existing != null ? existing.optLong("time", 0L) : 0L;
        if (effectiveTime <= 0) effectiveTime = unixTime > 0 ? unixTime : System.currentTimeMillis() / 1000L;
        String effectiveTitle;
        if (existing != null) effectiveTitle = existing.optString("title", "🛒 NUEVA VENTA — ML CENTRAL");
        else effectiveTitle = isUpdate ? "🛒 VENTA — ML CENTRAL" : (title == null ? "🛒 NUEVA VENTA — ML CENTRAL" : title);

        JSONObject item = new JSONObject();
        try {
            item.put("saleId", wantedId);
            item.put("title", effectiveTitle);
            item.put("message", message == null ? "" : message);
            item.put("time", effectiveTime);
            item.put("read", alreadyRead);
            item.put("updated", isUpdate || (existing != null && existing.optBoolean("updated", false)));
        } catch (Exception ignored) {}

        JSONArray out = new JSONArray();
        if (existingIndex < 0) {
            out.put(item);
            for (int i = 0; i < old.length() && out.length() < MAX_HISTORY; i++) out.put(old.opt(i));
        } else {
            for (int i = 0; i < old.length() && out.length() < MAX_HISTORY; i++) out.put(i == existingIndex ? item : old.opt(i));
        }
        p.edit().putString("history", out.toString()).putInt("unread", countUnread(out)).apply();
    }

    private static int countUnread(JSONArray arr) {
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !o.optBoolean("read", false)) n++;
        }
        return n;
    }

    public static synchronized int unread(Context c) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("history", "[]"));
            return countUnread(arr);
        } catch (Exception e) {
            return prefs(c).getInt("unread", 0);
        }
    }

    public static synchronized List<String> unreadIds(Context c) {
        ArrayList<String> ids = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("history", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optBoolean("read", false)) continue;
                String id = o.optString("saleId", "").trim();
                if (!id.isEmpty()) ids.add(id);
            }
        } catch (Exception ignored) {}
        return ids;
    }

    public static synchronized void markRead(Context c, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        SharedPreferences p = prefs(c);
        Set<String> wanted = new HashSet<>();
        for (String id : ids) if (id != null && !id.trim().isEmpty()) wanted.add(id.trim());
        if (wanted.isEmpty()) return;

        Set<String> acked = new HashSet<>(p.getStringSet("acknowledged_sales", new HashSet<>()));
        acked.addAll(wanted);

        try {
            JSONArray arr = new JSONArray(p.getString("history", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && wanted.contains(o.optString("saleId", "").trim())) o.put("read", true);
            }
            if (acked.size() > MAX_ACKED) acked = acknowledgedFromHistory(arr, acked);
            p.edit().putString("history", arr.toString()).putStringSet("acknowledged_sales", acked).putInt("unread", countUnread(arr)).apply();
        } catch (Exception e) {
            p.edit().putStringSet("acknowledged_sales", acked).apply();
        }
    }

    private static Set<String> acknowledgedFromHistory(JSONArray arr, Set<String> extra) {
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !o.optBoolean("read", false)) continue;
            String id = o.optString("saleId", "").trim();
            if (!id.isEmpty()) keep.add(id);
        }
        for (String id : extra) {
            if (keep.size() >= MAX_ACKED) break;
            if (id != null && !id.trim().isEmpty()) keep.add(id.trim());
        }
        return keep;
    }

    public static synchronized void markAllRead(Context c) { markRead(c, unreadIds(c)); }
    public static synchronized String unreadHistoryText(Context c) { return formatHistory(c, true, false); }
    public static synchronized String historyText(Context c) { return formatHistory(c, false, false); }
    public static synchronized String todayHistoryText(Context c) { return formatHistory(c, false, true); }

    private static String formatHistory(Context c, boolean unreadOnly, boolean todayOnly) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("history", "[]"));
            SimpleDateFormat fmtToday = new SimpleDateFormat("HH:mm", Locale.getDefault());
            SimpleDateFormat fmtAll = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                boolean read = o.optBoolean("read", false);
                if (unreadOnly && read) continue;
                long seconds = o.optLong("time", 0L);
                if (todayOnly && !isToday(seconds)) continue;
                long t = seconds * 1000L;
                String when = t > 0 ? (todayOnly ? fmtToday.format(new Date(t)) : fmtAll.format(new Date(t))) : "";
                if (sb.length() > 0) sb.append("\n\n────────────────────────\n\n");
                sb.append(when).append("\n");
                sb.append(o.optString("title", "NUEVA VENTA")).append("\n");
                sb.append(o.optString("message", ""));
            }
        } catch (Exception e) {
            return "No se pudo leer el historial.";
        }
        if (sb.length() == 0) {
            if (unreadOnly) return "No hay ventas nuevas para confirmar.";
            if (todayOnly) return "Todavía no hay ventas recibidas hoy.";
            return "Todavía no hay ventas recibidas en este celular.";
        }
        return sb.toString();
    }

    private static boolean isToday(long unixSeconds) {
        if (unixSeconds <= 0) return false;
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(unixSeconds * 1000L);
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static boolean isThisMonth(long unixSeconds) {
        if (unixSeconds <= 0) return false;
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(unixSeconds * 1000L);
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.MONTH) == b.get(Calendar.MONTH);
    }

    private static Double moneyFromLine(String message, String prefix) {
        if (message == null) return null;
        String wanted = prefix.toLowerCase(Locale.ROOT);
        String[] lines = message.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase(Locale.ROOT).startsWith(wanted)) continue;
            String raw = trimmed.substring(prefix.length()).trim().replace("$", "").replace(" ", "");
            try {
                if (raw.contains(",")) raw = raw.replace(".", "").replace(",", ".");
                return Double.parseDouble(raw);
            } catch (Exception ignored) { return null; }
        }
        return null;
    }

    private static JSONArray historyArray(Context c) {
        try { return new JSONArray(prefs(c).getString("history", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public static synchronized int todaySaleCount(Context c) {
        int count = 0;
        JSONArray arr = historyArray(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && isToday(o.optLong("time", 0L))) count++;
        }
        return count;
    }

    public static synchronized double todayProfit(Context c) {
        double total = 0.0;
        JSONArray arr = historyArray(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !isToday(o.optLong("time", 0L))) continue;
            Double p = moneyFromLine(o.optString("message", ""), "Ganancia:");
            if (p != null) total += p;
        }
        return total;
    }

    public static synchronized int todayPendingProfitCount(Context c) {
        int count = 0;
        JSONArray arr = historyArray(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !isToday(o.optLong("time", 0L))) continue;
            if (moneyFromLine(o.optString("message", ""), "Ganancia:") == null) count++;
        }
        return count;
    }

    public static synchronized double monthProfit(Context c) {
        double total = 0.0;
        JSONArray arr = historyArray(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !isThisMonth(o.optLong("time", 0L))) continue;
            Double p = moneyFromLine(o.optString("message", ""), "Ganancia:");
            if (p != null) total += p;
        }
        return total;
    }

    public static synchronized int monthPendingProfitCount(Context c) {
        int count = 0;
        JSONArray arr = historyArray(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !isThisMonth(o.optLong("time", 0L))) continue;
            if (moneyFromLine(o.optString("message", ""), "Ganancia:") == null) count++;
        }
        return count;
    }

    public static String formatMoney(double value) {
        String s = String.format(Locale.US, "%,.2f", value).replace(",", "X").replace(".", ",").replace("X", ".");
        return "$" + s;
    }

    public static synchronized void queueReadSync(Context c, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        SharedPreferences p = prefs(c);
        Set<String> pending = new HashSet<>(p.getStringSet("pending_read_sync", new HashSet<>()));
        for (String id : ids) if (id != null && !id.trim().isEmpty()) pending.add(id.trim());
        p.edit().putStringSet("pending_read_sync", pending).apply();
    }

    public static synchronized List<String> pendingReadSync(Context c) { return new ArrayList<>(prefs(c).getStringSet("pending_read_sync", new HashSet<>())); }

    public static synchronized void clearPendingReadSync(Context c, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        SharedPreferences p = prefs(c);
        Set<String> pending = new HashSet<>(p.getStringSet("pending_read_sync", new HashSet<>()));
        pending.removeAll(new HashSet<>(ids));
        p.edit().putStringSet("pending_read_sync", pending).apply();
    }

    public static void setLastMessageId(Context c, String id) { if (id != null && !id.isEmpty()) prefs(c).edit().putString("last_ntfy_id", id).apply(); }
    public static String getLastMessageId(Context c) { return prefs(c).getString("last_ntfy_id", ""); }
    public static void setConnected(Context c, boolean value) { prefs(c).edit().putBoolean("connected", value).putLong("connected_at", System.currentTimeMillis()).apply(); }
    public static boolean connected(Context c) { return prefs(c).getBoolean("connected", false); }
    public static long connectedAt(Context c) { return prefs(c).getLong("connected_at", 0L); }
}
