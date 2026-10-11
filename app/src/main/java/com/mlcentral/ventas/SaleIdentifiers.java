package com.mlcentral.ventas;

import org.json.JSONObject;

/**
 * Identificadores para mostrar, sin modificar la clave técnica de cada orden.
 *
 * La etiqueta interna de ML Central imprime pack_id (Venta ML) cuando existe
 * y order_id en su defecto. El QR interno continúa codificando order_id.
 */
public final class SaleIdentifiers {
    private SaleIdentifiers() {}

    private static String digits(JSONObject sale, String key) {
        if (sale == null) return "";
        String id = sale.optString(key, "").trim();
        return id.matches("[0-9]{5,32}") ? id : "";
    }

    /** Número que coincide con el campo Venta de la etiqueta interna Windows. */
    public static String visibleSaleId(JSONObject sale) {
        String pack = digits(sale, "pack_id");
        if (!pack.isEmpty()) return pack;
        return digits(sale, "order_id");
    }

    public static boolean hasDistinctPack(JSONObject sale) {
        String pack = digits(sale, "pack_id");
        String order = digits(sale, "order_id");
        return !pack.isEmpty() && !pack.equals(order);
    }

    public static String primaryLabel(JSONObject sale) {
        String id = visibleSaleId(sale);
        return "Venta ML " + (id.isEmpty() ? "—" : id);
    }

    /** Sólo lectura: la orden API continúa siendo la clave de comandos y QR. */
    public static String technicalLabel(JSONObject sale) {
        String order = digits(sale, "order_id");
        if (!hasDistinctPack(sale) || order.isEmpty()) return "";
        return "Orden API " + order;
    }
}
