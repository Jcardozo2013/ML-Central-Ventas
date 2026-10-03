package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

public final class MonthPaceStats {
    public static final class Pace {
        public final int compareDay;
        public final int currentYear;
        public final int currentMonth;
        public final int previousYear;
        public final int previousMonth;
        public final int currentSales;
        public final int previousSales;
        public final double currentProfit;
        public final double previousProfit;
        public final int currentMissingProfit;
        public final int previousMissingProfit;
        public final boolean comparable;
        public final double percentChange;

        Pace(int compareDay,
             int currentYear, int currentMonth,
             int previousYear, int previousMonth,
             int currentSales, int previousSales,
             double currentProfit, double previousProfit,
             int currentMissingProfit, int previousMissingProfit,
             boolean comparable, double percentChange) {
            this.compareDay = compareDay;
            this.currentYear = currentYear;
            this.currentMonth = currentMonth;
            this.previousYear = previousYear;
            this.previousMonth = previousMonth;
            this.currentSales = currentSales;
            this.previousSales = previousSales;
            this.currentProfit = currentProfit;
            this.previousProfit = previousProfit;
            this.currentMissingProfit = currentMissingProfit;
            this.previousMissingProfit = previousMissingProfit;
            this.comparable = comparable;
            this.percentChange = percentChange;
        }

        public boolean complete() {
            return currentMissingProfit == 0 && previousMissingProfit == 0;
        }
    }

    private MonthPaceStats() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
    }

    public static Pace calculate(Context context) {
        Calendar now = Calendar.getInstance();

        Calendar previous = (Calendar) now.clone();
        previous.set(Calendar.DAY_OF_MONTH, 1);
        previous.add(Calendar.MONTH, -1);

        int compareDay = Math.min(
                now.get(Calendar.DAY_OF_MONTH),
                previous.getActualMaximum(Calendar.DAY_OF_MONTH));

        int currentYear = now.get(Calendar.YEAR);
        int currentMonth = now.get(Calendar.MONTH);
        int previousYear = previous.get(Calendar.YEAR);
        int previousMonth = previous.get(Calendar.MONTH);

        int currentSales = 0;
        int previousSales = 0;
        int currentMissing = 0;
        int previousMissing = 0;
        double currentProfit = 0.0;
        double previousProfit = 0.0;

        try {
            JSONArray history = new JSONArray(prefs(context).getString("history", "[]"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject row = history.optJSONObject(i);
                if (row == null) continue;

                long seconds = row.optLong("time", 0L);
                if (seconds <= 0L) continue;

                Calendar saleDate = Calendar.getInstance();
                saleDate.setTimeInMillis(seconds * 1000L);
                int day = saleDate.get(Calendar.DAY_OF_MONTH);
                if (day > compareDay) continue;

                boolean inCurrent = saleDate.get(Calendar.YEAR) == currentYear
                        && saleDate.get(Calendar.MONTH) == currentMonth;
                boolean inPrevious = saleDate.get(Calendar.YEAR) == previousYear
                        && saleDate.get(Calendar.MONTH) == previousMonth;

                if (!inCurrent && !inPrevious) continue;

                Double profit = moneyFromLine(row.optString("message", ""), "Ganancia:");
                if (inCurrent) {
                    currentSales++;
                    if (profit == null) currentMissing++;
                    else currentProfit += profit;
                } else {
                    previousSales++;
                    if (profit == null) previousMissing++;
                    else previousProfit += profit;
                }
            }
        } catch (Exception ignored) {}

        boolean complete = currentMissing == 0 && previousMissing == 0;
        boolean comparable = complete && previousProfit > 0.00001;
        double percent = comparable
                ? ((currentProfit - previousProfit) / previousProfit) * 100.0
                : 0.0;

        return new Pace(
                compareDay,
                currentYear, currentMonth,
                previousYear, previousMonth,
                currentSales, previousSales,
                currentProfit, previousProfit,
                currentMissing, previousMissing,
                comparable, percent);
    }

    private static Double moneyFromLine(String message, String prefix) {
        if (message == null) return null;
        String wanted = prefix.toLowerCase(Locale.ROOT);
        for (String line : message.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.toLowerCase(Locale.ROOT).startsWith(wanted)) continue;

            String raw = t.substring(prefix.length()).trim()
                    .replace("$", "")
                    .replace("UYU", "")
                    .replace(" ", "")
                    .replaceAll("[^0-9,.-]", "");

            if (raw.isEmpty()) return null;
            try {
                if (raw.contains(",")) {
                    raw = raw.replace(".", "").replace(",", ".");
                }
                return Double.parseDouble(raw);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
}
