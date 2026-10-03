package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class MonthlyStats {
    private static final String KEY = "monthly_stats_v167";

    public static final class Stats {
        public final int year;
        public final int month;
        public final int salesCount;
        public final double soldTotal;
        public final double profitTotal;
        public final int missingSaleAmount;
        public final int missingProfit;

        Stats(int year, int month, int salesCount, double soldTotal, double profitTotal,
              int missingSaleAmount, int missingProfit) {
            this.year = year;
            this.month = month;
            this.salesCount = salesCount;
            this.soldTotal = soldTotal;
            this.profitTotal = profitTotal;
            this.missingSaleAmount = missingSaleAmount;
            this.missingProfit = missingProfit;
        }
    }

    private MonthlyStats() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
    }

    public static String keyFor(int year, int month) {
        return String.format(Locale.US, "%04d-%02d", year, month + 1);
    }

    public static synchronized void rebuild(Context context) {
        if (context == null) return;
        JSONObject grouped = new JSONObject();
        try {
            JSONArray history = new JSONArray(prefs(context).getString("history", "[]"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject row = history.optJSONObject(i);
                if (row == null) continue;
                long seconds = row.optLong("time", 0L);
                if (seconds <= 0L) continue;

                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(seconds * 1000L);
                int year = c.get(Calendar.YEAR);
                int month = c.get(Calendar.MONTH);
                String key = keyFor(year, month);

                JSONObject acc = grouped.optJSONObject(key);
                if (acc == null) {
                    acc = new JSONObject();
                    acc.put("year", year);
                    acc.put("month", month);
                    acc.put("sales_count", 0);
                    acc.put("sold_total", 0.0);
                    acc.put("profit_total", 0.0);
                    acc.put("missing_sale_amount", 0);
                    acc.put("missing_profit", 0);
                    grouped.put(key, acc);
                }

                acc.put("sales_count", acc.optInt("sales_count", 0) + 1);
                String message = row.optString("message", "");

                Double sold = moneyFromLine(message, "Venta:");
                if (sold == null) {
                    acc.put("missing_sale_amount", acc.optInt("missing_sale_amount", 0) + 1);
                } else {
                    acc.put("sold_total", acc.optDouble("sold_total", 0.0) + sold);
                }

                Double profit = moneyFromLine(message, "Ganancia:");
                if (profit == null) {
                    acc.put("missing_profit", acc.optInt("missing_profit", 0) + 1);
                } else {
                    acc.put("profit_total", acc.optDouble("profit_total", 0.0) + profit);
                }
            }
        } catch (Exception ignored) {}

        prefs(context).edit()
                .putString(KEY, grouped.toString())
                .putLong("monthly_stats_rebuilt_at_v167", System.currentTimeMillis())
                .apply();
    }

    public static synchronized Stats get(Context context, int year, int month) {
        ensureBuilt(context);
        try {
            JSONObject root = new JSONObject(prefs(context).getString(KEY, "{}"));
            JSONObject o = root.optJSONObject(keyFor(year, month));
            if (o == null) return new Stats(year, month, 0, 0.0, 0.0, 0, 0);
            return new Stats(
                    year,
                    month,
                    o.optInt("sales_count", 0),
                    o.optDouble("sold_total", 0.0),
                    o.optDouble("profit_total", 0.0),
                    o.optInt("missing_sale_amount", 0),
                    o.optInt("missing_profit", 0)
            );
        } catch (Exception e) {
            return new Stats(year, month, 0, 0.0, 0.0, 0, 0);
        }
    }

    public static synchronized List<String> availableKeys(Context context) {
        ensureBuilt(context);
        ArrayList<String> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(prefs(context).getString(KEY, "{}"));
            Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (key != null && key.matches("\\d{4}-\\d{2}")) out.add(key);
            }
        } catch (Exception ignored) {}
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    public static int earliestYear(Context context) {
        int earliest = Calendar.getInstance().get(Calendar.YEAR);
        for (String key : availableKeys(context)) {
            try {
                earliest = Math.min(earliest, Integer.parseInt(key.substring(0, 4)));
            } catch (Exception ignored) {}
        }
        return earliest;
    }

    public static String monthLabel(int year, int month) {
        String[] months = new DateFormatSymbols(Locale.getDefault()).getMonths();
        String name = month >= 0 && month < 12 ? months[month] : "";
        if (name == null || name.trim().isEmpty()) name = String.format(Locale.getDefault(), "%02d", month + 1);
        if (!name.isEmpty()) name = name.substring(0, 1).toUpperCase(Locale.getDefault()) + name.substring(1);
        return name + " " + year;
    }

    private static void ensureBuilt(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY)) rebuild(context);
    }

    private static Double moneyFromLine(String message, String prefix) {
        if (message == null) return null;
        String wanted = prefix.toLowerCase(Locale.ROOT);
        for (String line : message.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.toLowerCase(Locale.ROOT).startsWith(wanted)) continue;
            String raw = t.substring(prefix.length()).trim();
            raw = raw.replace("$", "").replace("UYU", "").replace(" ", "");
            raw = raw.replaceAll("[^0-9,.-]", "");
            if (raw.isEmpty()) return null;
            try {
                if (raw.contains(",")) raw = raw.replace(".", "").replace(",", ".");
                return Double.parseDouble(raw);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
}
