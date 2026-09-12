package com.mlcentral.ventas;

/**
 * Nombre conservado para no cambiar el componente Android existente.
 * Desde v1.21 usa el servicio Firebase robusto para celulares lentos.
 */
public class SaleListenerService extends SafeFirebaseListenerService {
    public static final String SALES_CHANNEL = "mlc_sales_urgent";
    public static final String DELIVERY_CHANNEL = "mlc_delivery_status_v1";
}
