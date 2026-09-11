package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class StateStore {
    private static final String FINAL_KEY = "state_sales_v1";
    private static final String ACTIVE_KEY = "state_snapshot_active_v1";
    private static final String LAST_SYNC_KEY = "state_last_sync_at_v1";
    private static final String LAST_STATUS_KEY = "state_last_status_v1";

    private StateStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject object(String raw) {
        try { return new JSONObject(raw == null || raw.trim().isEmpty() ? "{}" : raw); }
        catch (Exception e) { return new JSONObject(); }
    }

    public static synchronized void beginSnapshot(Context c, String batchId, int total) {
        String bid = batchId == null ? "" : batchId.trim();
        if (bid.isEmpty()) return;
        SharedPreferences p = prefs(c);
        JSONObject active = object(p.getString(ACTIVE_KEY, "{}"));
        if (bid.equals(active.optString("batch_id", ""))) return;
        JSONObject next = new JSONObject();
        try {
            next.put("batch_id", bid);
            next.put("total", Math.max(0, total));
            next.put("sales", new JSONObject());
            next.put("started_at", System.currentTimeMillis());
        } catch (Exception ignored) {}
        p.edit()
                .putString(ACTIVE_KEY, next.toString())
                .putString(LAST_STATUS_KEY, "Recibiendo estados 0/" + Math.max(0, total))
                .apply();
    }

    public static synchronized int mergeChunk(Context c, String batchId, JSONArray rows) {
        if (rows == null) return 0;
        SharedPreferences p = prefs(c);
        JSONObject active = object(p.getString(ACTIVE_KEY, "{}"));
        String bid = batchId == null ? "" : batchId.trim();
        if (bid.isEmpty() || !bid.equals(active.optString("batch_id", ""))) return 0;
        JSONObject sales = active.optJSONObject("sales");
        if (sales == null) sales = new JSONObject();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String oid = row.optString("order_id", "").trim();
            if (oid.isEmpty()) continue;
            try { sales.put(oid, row); } catch (Exception ignored) {}
        }
        try { active.put("sales", sales); } catch (Exception ignored) {}
        int received = sales.length();
        int total = active.optInt("total", 0);
        p.edit()
                .putString(ACTIVE_KEY, active.toString())
                .putString(LAST_STATUS_KEY, "Recibiendo estados " + received + "/" + total)
                .apply();
        return received;
    }

    public static synchronized boolean completeSnapshot(Context c, String batchId, int total) {
        SharedPreferences p = prefs(c);
        JSONObject active = object(p.getString(ACTIVE_KEY, "{}"));
        String bid = batchId == null ? "" : batchId.trim();
        if (bid.isEmpty() || !bid.equals(active.optString("batch_id", ""))) return false;
        JSONObject sales = active.optJSONObject("sales");
        if (sales == null) sales = new JSONObject();
        int expected = Math.max(0, total > 0 ? total : active.optInt("total", 0));
        int received = sales.length();
        if (received < expected) {
            p.edit().putString(LAST_STATUS_KEY,
                    "Estados incompletos: " + received + "/" + expected + " · se reintentará").apply();
            return false;
        }
        long now = System.currentTimeMillis();
        p.edit()
                .putString(FINAL_KEY, sales.toString())
                .putString(ACTIVE_KEY, "")
                .putLong(LAST_SYNC_KEY, now)
                .putString(LAST_STATUS_KEY, "Estados sincronizados: " + received)
                .apply();
        return true;
    }

    public static synchronized void upsertState(Context c, JSONObject state) {
        if (state == null) return;
        String oid = state.optString("order_id", "").trim();
        if (oid.isEmpty()) return;
        SharedPreferences p = prefs(c);
        JSONObject all = object(p.getString(FINAL_KEY, "{}"));
        try { all.put(oid, state); } catch (Exception ignored) {}
        p.edit().putString(FINAL_KEY, all.toString()).putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply();
    }

    public static synchronized List<JSONObject> allSales(Context c) {
        JSONObject all = object(prefs(c).getString(FINAL_KEY, "{}"));
        ArrayList<JSONObject> out = new ArrayList<>();
        JSONArray names = all.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                JSONObject row = all.optJSONObject(names.optString(i));
                if (row != null) out.add(row);
            }
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                long ta = a == null ? 0L : a.optLong("sale_unix", 0L);
                long tb = b == null ? 0L : b.optLong("sale_unix", 0L);
                if (ta != tb) return Long.compare(tb, ta);
                String oa = a == null ? "" : a.optString("order_id", "");
                String ob = b == null ? "" : b.optString("order_id", "");
                return ob.compareTo(oa);
            }
        });
        return out;
    }

    public static synchronized List<JSONObject> salesForStage(Context c, String stage) {
        String wanted = stage == null ? "" : stage.trim();
        ArrayList<JSONObject> out = new ArrayList<>();
        for (JSONObject row : allSales(c)) {
            if (wanted.isEmpty() || "all".equals(wanted) || wanted.equals(row.optString("stage", ""))) out.add(row);
        }
        return out;
    }

    public static synchronized int countStage(Context c, String stage) {
        int n = 0;
        String wanted = stage == null ? "" : stage.trim();
        for (JSONObject row : allSales(c)) if (wanted.equals(row.optString("stage", ""))) n++;
        return n;
    }

    public static synchronized int total(Context c) {
        return object(prefs(c).getString(FINAL_KEY, "{}")).length();
    }

    public static long lastSyncAt(Context c) {
        return prefs(c).getLong(LAST_SYNC_KEY, 0L);
    }

    public static boolean isStale(Context c, long maxAgeMs) {
        long at = lastSyncAt(c);
        return at <= 0 || System.currentTimeMillis() - at > Math.max(1000L, maxAgeMs);
    }

    public static String statusText(Context c) {
        SharedPreferences p = prefs(c);
        String status = p.getString(LAST_STATUS_KEY, "");
        long at = p.getLong(LAST_SYNC_KEY, 0L);
        if (at > 0) {
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(at));
            return "Estados PC: " + total(c) + " ventas · " + time;
        }
        return status == null || status.trim().isEmpty() ? "Estados PC: esperando sincronización…" : status;
    }

    public static void setStatus(Context c, String text) {
        prefs(c).edit().putString(LAST_STATUS_KEY, text == null ? "" : text).apply();
    }
}
