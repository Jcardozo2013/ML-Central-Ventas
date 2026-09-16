package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Registro local de paquetes entregados. Se alimenta desde el tablero de Estados
 * que Windows ya sincroniza con la APK, por lo que sirve como respaldo aunque el
 * aviso instantáneo de Firebase no llegue a mostrarse en Android.
 */
public final class DeliveryStore {
    private static final String HISTORY_KEY = "delivery_history_v141";
    private static final String KNOWN_KEY = "delivery_known_v141";
    private static final String BASELINE_KEY = "delivery_baseline_v141";
    private static final int MAX_HISTORY = 500;
    private static final int MAX_KNOWN = 2000;

    public static final class Delivery {
        public String id = "";
        public String shipmentId = "";
        public String orderId = "";
        public String product = "Producto";
        public String sale = "";
        public long time = 0L;

        public String detail() {
            StringBuilder sb = new StringBuilder();
            sb.append(product == null || product.trim().isEmpty() ? "Producto" : product.trim());
            if (orderId != null && !orderId.trim().isEmpty()) sb.append("\nOrden: ").append(orderId.trim());
            if (sale != null && !sale.trim().isEmpty()) sb.append("\nVenta: ").append(sale.trim());
            sb.append("\nEstado: ✅ Entregado");
            return sb.toString();
        }
    }

    private DeliveryStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
    }

    private static String idFor(JSONObject row) {
        if (row == null) return "";
        String shipment = row.optString("shipment_id", "").trim();
        if (!shipment.isEmpty()) return shipment;
        String order = row.optString("order_id", "").trim();
        return order.isEmpty() ? "" : "order:" + order;
    }

    private static Delivery fromRow(JSONObject row, long fallbackSeconds) {
        Delivery d = new Delivery();
        d.id = idFor(row);
        d.shipmentId = row == null ? "" : row.optString("shipment_id", "").trim();
        d.orderId = row == null ? "" : row.optString("order_id", "").trim();
        d.product = row == null ? "Producto" : row.optString("product", "Producto").trim();
        if (d.product.isEmpty()) d.product = "Producto";
        if (row != null && row.has("sale_amount") && !row.isNull("sale_amount")) {
            try { d.sale = SaleStore.formatMoney(row.optDouble("sale_amount", 0.0)); }
            catch (Exception ignored) {}
        }
        long parsed = parseUpdatedAt(row == null ? "" : row.optString("updated_at", ""));
        d.time = parsed > 0L ? parsed : (fallbackSeconds > 0L ? fallbackSeconds : System.currentTimeMillis() / 1000L);
        return d;
    }

    private static long parseUpdatedAt(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return 0L;
        try { return Instant.parse(s).getEpochSecond(); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(s).toEpochSecond(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toEpochSecond(); } catch (Exception ignored) {}
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS"};
        for (String pattern : patterns) {
            try {
                Date d = new SimpleDateFormat(pattern, Locale.US).parse(s);
                if (d != null) return d.getTime() / 1000L;
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    private static boolean isToday(long seconds) {
        if (seconds <= 0L) return false;
        Calendar now = Calendar.getInstance();
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(seconds * 1000L);
        return now.get(Calendar.ERA) == d.get(Calendar.ERA)
                && now.get(Calendar.YEAR) == d.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == d.get(Calendar.DAY_OF_YEAR);
    }

    private static JSONArray history(Context c) {
        try { return new JSONArray(prefs(c).getString(HISTORY_KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static synchronized void record(Context c, Delivery d) {
        if (d == null || d.id == null || d.id.trim().isEmpty()) return;
        JSONArray old = history(c);
        ArrayList<JSONObject> rows = new ArrayList<>();
        boolean replaced = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            if (d.id.equals(o.optString("id", "").trim())) {
                rows.add(toJson(d));
                replaced = true;
            } else {
                rows.add(o);
            }
        }
        if (!replaced) rows.add(toJson(d));
        Collections.sort(rows, (a, b) -> Long.compare(b.optLong("time", 0L), a.optLong("time", 0L)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < rows.size() && i < MAX_HISTORY; i++) out.put(rows.get(i));
        prefs(c).edit().putString(HISTORY_KEY, out.toString()).apply();
    }

    private static JSONObject toJson(Delivery d) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", d.id == null ? "" : d.id);
            o.put("shipment_id", d.shipmentId == null ? "" : d.shipmentId);
            o.put("order_id", d.orderId == null ? "" : d.orderId);
            o.put("product", d.product == null ? "Producto" : d.product);
            o.put("sale", d.sale == null ? "" : d.sale);
            o.put("time", d.time);
        } catch (Exception ignored) {}
        return o;
    }

    private static Delivery fromJson(JSONObject o) {
        Delivery d = new Delivery();
        if (o == null) return d;
        d.id = o.optString("id", "").trim();
        d.shipmentId = o.optString("shipment_id", "").trim();
        d.orderId = o.optString("order_id", "").trim();
        d.product = o.optString("product", "Producto").trim();
        d.sale = o.optString("sale", "").trim();
        d.time = o.optLong("time", 0L);
        return d;
    }

    /**
     * Compara el tablero actual con el último tablero conocido. La primera vez
     * solo crea una línea base para no avisar decenas de entregas antiguas, pero
     * sí guarda las que parecen haberse actualizado hoy para que la tarjeta las muestre.
     */
    public static synchronized List<Delivery> reconcile(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        Set<String> known = new HashSet<>(p.getStringSet(KNOWN_KEY, new HashSet<>()));
        boolean baselineReady = p.getBoolean(BASELINE_KEY, false);
        ArrayList<Delivery> newlyDelivered = new ArrayList<>();
        long nowSeconds = System.currentTimeMillis() / 1000L;

        for (JSONObject row : StateStore.allSales(app)) {
            if (row == null || !"delivered".equals(row.optString("stage", "").trim())) continue;
            String id = idFor(row);
            if (id.isEmpty()) continue;
            boolean wasKnown = known.contains(id);
            Delivery d = fromRow(row, nowSeconds);

            if (!baselineReady) {
                if (isToday(d.time)) record(app, d);
            } else if (!wasKnown) {
                record(app, d);
                newlyDelivered.add(d);
            }
            known.add(id);
        }

        if (known.size() > MAX_KNOWN) {
            // El tablero actual es la mejor referencia para conservar identificadores útiles.
            Set<String> trimmed = new HashSet<>();
            for (JSONObject row : StateStore.allSales(app)) {
                if (row == null || !"delivered".equals(row.optString("stage", "").trim())) continue;
                String id = idFor(row);
                if (!id.isEmpty()) trimmed.add(id);
            }
            known = trimmed;
        }

        p.edit().putStringSet(KNOWN_KEY, known).putBoolean(BASELINE_KEY, true).apply();
        return newlyDelivered;
    }

    public static synchronized int todayCount(Context c) {
        int count = 0;
        JSONArray arr = history(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && isToday(o.optLong("time", 0L))) count++;
        }
        return count;
    }

    public static synchronized String todayText(Context c) {
        ArrayList<Delivery> list = new ArrayList<>();
        JSONArray arr = history(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !isToday(o.optLong("time", 0L))) continue;
            list.add(fromJson(o));
        }
        Collections.sort(list, Comparator.comparingLong((Delivery d) -> d.time).reversed());
        if (list.isEmpty()) return "Todavía no hay paquetes registrados como entregados hoy.";

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        for (Delivery d : list) {
            if (sb.length() > 0) sb.append("\n\n────────────────────────\n\n");
            sb.append(d.time > 0L ? fmt.format(new Date(d.time * 1000L)) : "").append("\n");
            sb.append(d.detail());
        }
        return sb.toString();
    }
}
