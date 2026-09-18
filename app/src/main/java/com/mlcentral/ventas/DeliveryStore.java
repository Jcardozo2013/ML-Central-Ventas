package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
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
 * Registro local de paquetes entregados.
 *
 * v1.42:
 * - La primera sincronización crea solo la línea base y NO cuenta entregas antiguas como "hoy".
 * - La hora de entrega se toma del evento real o del momento en que se detecta una transición
 *   nueva a delivered; no se usa updated_at del tablero como fecha de entrega.
 * - Usa claves nuevas v142 para descartar los falsos positivos creados por v1.41.
 */
public final class DeliveryStore {
    private static final String HISTORY_KEY = "delivery_history_v142";
    private static final String KNOWN_KEY = "delivery_known_v142";
    private static final String BASELINE_KEY = "delivery_baseline_v142";
    private static final int MAX_HISTORY = 500;
    private static final int MAX_KNOWN = 2000;
    private static final long DIRECT_DUP_WINDOW_SECONDS = 10 * 60L;

    public static final class Delivery {
        public String id = "";
        public String shipmentId = "";
        public String orderId = "";
        public String product = "Producto";
        public String sale = "";
        public String mlDeposit = "";
        public String profit = "";
        public String origin = "";
        public long time = 0L;

        public String detail() {
            StringBuilder sb = new StringBuilder();
            sb.append(product == null || product.trim().isEmpty() ? "Producto" : product.trim());
            if (orderId != null && !orderId.trim().isEmpty()) sb.append("\nOrden: ").append(orderId.trim());
            if (sale != null && !sale.trim().isEmpty()) sb.append("\nVenta: ").append(sale.trim());
            if (mlDeposit != null && !mlDeposit.trim().isEmpty()) sb.append("\nMercado Libre deposita: ").append(mlDeposit.trim());
            if (profit != null && !profit.trim().isEmpty()) sb.append("\nGanancia: ").append(profit.trim());
            if (origin != null && !origin.trim().isEmpty()) {
                String o = origin.trim().toUpperCase(Locale.ROOT);
                sb.append("\nTipo: ").append("STOCK LOCAL".equals(o) ? "📦 STOCK LOCAL" : "🚚 POR ENCARGO BR");
            }
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

    private static Delivery fromRow(JSONObject row, long eventSeconds) {
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
        if (row != null && row.has("ml_net_deposit") && !row.isNull("ml_net_deposit")) {
            try { d.mlDeposit = SaleStore.formatMoney(row.optDouble("ml_net_deposit", 0.0)); }
            catch (Exception ignored) {}
        } else if (row != null
                && row.has("sale_amount") && !row.isNull("sale_amount")
                && row.has("commission_total") && !row.isNull("commission_total")
                && row.has("shipping_cost_total") && !row.isNull("shipping_cost_total")) {
            try {
                double net = row.optDouble("sale_amount", 0.0)
                        - row.optDouble("commission_total", 0.0)
                        - row.optDouble("shipping_cost_total", 0.0);
                d.mlDeposit = SaleStore.formatMoney(net);
            } catch (Exception ignored) {}
        }
        if (row != null && row.has("profit") && !row.isNull("profit")) {
            try { d.profit = SaleStore.formatMoney(row.optDouble("profit", 0.0)); }
            catch (Exception ignored) {}
        }
        if (row != null) d.origin = row.optString("sale_origin", "").trim();
        d.time = eventSeconds > 0L ? eventSeconds : System.currentTimeMillis() / 1000L;
        return d;
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
            o.put("ml_deposit", d.mlDeposit == null ? "" : d.mlDeposit);
            o.put("profit", d.profit == null ? "" : d.profit);
            o.put("origin", d.origin == null ? "" : d.origin);
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
        if (d.product.isEmpty()) d.product = "Producto";
        d.sale = o.optString("sale", "").trim();
        d.mlDeposit = o.optString("ml_deposit", "").trim();
        d.profit = o.optString("profit", "").trim();
        d.origin = o.optString("origin", "").trim();
        d.time = o.optLong("time", 0L);
        return d;
    }

    private static long recordedAt(Context c, String id) {
        if (id == null || id.trim().isEmpty()) return 0L;
        JSONArray arr = history(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.trim().equals(o.optString("id", "").trim())) return o.optLong("time", 0L);
        }
        return 0L;
    }

    private static JSONObject findStateRow(Context c, String id) {
        String wanted = id == null ? "" : id.trim();
        String bare = wanted.startsWith("order:") ? wanted.substring("order:".length()) : wanted;
        for (JSONObject row : StateStore.allSales(c)) {
            if (row == null) continue;
            String shipment = row.optString("shipment_id", "").trim();
            String order = row.optString("order_id", "").trim();
            if (!bare.isEmpty() && (bare.equals(shipment) || bare.equals(order))) return row;
        }
        return null;
    }

    /** Guarda el evento real de PAQUETE ENTREGADO recibido desde Firebase. */
    public static synchronized void recordDirectEvent(Context c, String eventId, String message, long unixSeconds) {
        String id = eventId == null ? "" : eventId.trim();
        if (id.isEmpty()) return;
        long when = unixSeconds > 0L ? unixSeconds : System.currentTimeMillis() / 1000L;

        JSONObject row = findStateRow(c, id);
        Delivery d;
        if (row != null) {
            d = fromRow(row, when);
            if (d.id == null || d.id.trim().isEmpty()) d.id = id;
        } else {
            d = new Delivery();
            d.id = id;
            d.time = when;
            d.product = firstUsefulLine(message);
        }
        d.time = when;
        record(c, d);
    }

    private static String firstUsefulLine(String message) {
        if (message == null || message.trim().isEmpty()) return "Paquete entregado";
        String[] lines = message.split("\\r?\\n");
        for (String line : lines) {
            String s = line == null ? "" : line.trim();
            if (s.isEmpty()) continue;
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.startsWith("orden") || lower.startsWith("order") || lower.startsWith("venta:")
                    || lower.startsWith("envío") || lower.startsWith("envio") || lower.startsWith("shipment")
                    || lower.startsWith("estado")) continue;
            return s;
        }
        return "Paquete entregado";
    }

    /**
     * Primera ejecución: solo guarda los IDs ya entregados como línea base.
     * Ejecuciones siguientes: current delivered - previous delivered = entregas nuevas.
     */
    public static synchronized List<Delivery> reconcile(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        Set<String> previous = new HashSet<>(p.getStringSet(KNOWN_KEY, new HashSet<>()));
        boolean baselineReady = p.getBoolean(BASELINE_KEY, false);
        Set<String> current = new HashSet<>();
        ArrayList<Delivery> newlyDelivered = new ArrayList<>();
        long nowSeconds = System.currentTimeMillis() / 1000L;

        for (JSONObject row : StateStore.allSales(app)) {
            if (row == null || !"delivered".equals(row.optString("stage", "").trim())) continue;
            String id = idFor(row);
            if (id.isEmpty()) continue;
            current.add(id);

            if (baselineReady && !previous.contains(id)) {
                Delivery d = fromRow(row, nowSeconds);
                long oldTime = recordedAt(app, id);
                boolean alreadyNotifiedDirectly = oldTime > 0L && Math.abs(nowSeconds - oldTime) <= DIRECT_DUP_WINDOW_SECONDS;
                record(app, d);
                if (!alreadyNotifiedDirectly) newlyDelivered.add(d);
            }
        }

        if (current.size() > MAX_KNOWN) {
            Set<String> trimmed = new HashSet<>();
            int n = 0;
            for (String id : current) {
                if (id == null || id.trim().isEmpty()) continue;
                trimmed.add(id.trim());
                if (++n >= MAX_KNOWN) break;
            }
            current = trimmed;
        }

        p.edit().putStringSet(KNOWN_KEY, current).putBoolean(BASELINE_KEY, true).apply();
        return baselineReady ? newlyDelivered : Collections.emptyList();
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
            // v1.47: una entrega registrada antes de recibir el snapshot financiero
            // se completa al abrir el detalle usando el estado actual de Windows.
            JSONObject state = findStateRow(c, d.id);
            if (state != null) {
                Delivery enriched = fromRow(state, d.time);
                if (enriched.id == null || enriched.id.trim().isEmpty()) enriched.id = d.id;
                d = enriched;
            }

            if (sb.length() > 0) sb.append("\n\n────────────────────────\n\n");
            sb.append(d.time > 0L ? fmt.format(new Date(d.time * 1000L)) : "").append("\n");
            sb.append(d.detail());
        }
        return sb.toString();
    }
}
