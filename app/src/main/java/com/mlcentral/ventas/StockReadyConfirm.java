package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Confirmación explícita de UNA orden. Nunca se inicia desde sync ni descarga. */
public final class StockReadyConfirm {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MLStockReadyExplicit"); t.setDaemon(true); return t;
    });
    private StockReadyConfirm() {}

    public static void addButton(Activity activity, LinearLayout card, JSONObject row) {
        if (row == null || !"to_rocha".equals(row.optString("stage", ""))) return;
        String order = row.optString("order_id", "").trim();
        String shipment = row.optString("shipment_id", "").trim();
        if (!order.matches("[0-9]{8,32}") || !shipment.matches("[0-9]{8,32}")) return;
        if (row.optBoolean("ml_stock_ready", false) || row.optBoolean("ml_label_printed", false)) return;
        String product = row.optString("product", "Producto");
        Button btn = UiKit.button(activity, "Ya tengo el producto (confirmar en ML)");
        btn.setOnClickListener(v -> ask(activity, btn, order, shipment, product));
        card.addView(btn, UiKit.fullWidth(activity, 9, 0));
    }

    private static void ask(Activity a, Button btn, String oid, String sid, String product) {
        JSONObject fresh = StateStore.snapshotSales(a).optJSONObject(oid);
        if (fresh == null || !"to_rocha".equals(fresh.optString("stage", "")) ||
                !sid.equals(fresh.optString("shipment_id", ""))) {
            warn(a, "La orden cambió. Actualizá Estados y volvé a entrar."); return;
        }
        SharedPreferences pref = a.getSharedPreferences(AppConfig.PREFS, Activity.MODE_PRIVATE);
        long last = pref.getLong("ready_stock_attempt:" + oid, 0L);
        if (last > 0L && System.currentTimeMillis() - last < 600000L) {
            warn(a, "Ya solicitaste confirmar esta orden hace menos de 10 minutos. "
                    + "Comprobá primero su estado en Mercado Libre."); return;
        }
        EditText digits = new EditText(a);
        digits.setInputType(InputType.TYPE_CLASS_NUMBER);
        digits.setHint("Últimos 6 dígitos");
        digits.setSingleLine(true);
        digits.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        LinearLayout wrap = new LinearLayout(a);
        wrap.setPadding(UiKit.dp(a,18),0,UiKit.dp(a,18),0);
        wrap.addView(digits);
        new AlertDialog.Builder(a).setTitle("Ya tengo el producto — Mercado Libre")
            .setMessage("ATENCIÓN: esto informa a Mercado Libre que YA TENÉS el producto. "
              + "No es un estado interno.\n\n" + product + "\nOrden: " + oid +
              "\nEnvío: " + sid + "\n\nEscribí los últimos 6 dígitos de la orden:")
            .setView(wrap)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar", (d,w) -> {
                if (!oid.substring(oid.length()-6).equals(digits.getText().toString().trim())) {
                    warn(a, "Los 6 dígitos no coinciden. No se envió nada."); return;
                }
                new AlertDialog.Builder(a).setTitle("Última confirmación")
                    .setMessage("¿TENÉS FÍSICAMENTE el producto de la orden " + oid
                      + " y autorizás a informar YA TENGO EL PRODUCTO en Mercado Libre?"
                      + "\n\nÚnicamente ESTA orden. Sin descargar etiqueta automáticamente.")
                    .setNegativeButton("NO, cancelar", null)
                    .setPositiveButton("SÍ, confirmar esta venta", (d2,w2) -> send(a, btn, oid, sid))
                    .show();
            }).show();
    }

    private static void send(Activity a, Button btn, String oid, String sid) {
        JSONObject current = StateStore.snapshotSales(a).optJSONObject(oid);
        if (current == null || !"to_rocha".equals(current.optString("stage", ""))
                || !sid.equals(current.optString("shipment_id", ""))) {
            warn(a, "El pedido cambió antes de enviar. No se envió nada."); return;
        }
        btn.setEnabled(false); btn.setText("Esperando confirmación de Windows…");
        EXEC.execute(() -> {
            String status = "error", msg = "";
            boolean posted = false;
            try {
                String rid = UUID.randomUUID().toString();
                JSONObject request = new JSONObject();
                request.put("type", "stock_ready_confirm_v1");
                request.put("request_id", rid);
                request.put("order_id", oid);
                request.put("shipment_id", sid);
                request.put("explicit_confirmation", true);
                request.put("confirmation_suffix", oid.substring(oid.length()-6));
                request.put("at", System.currentTimeMillis()/1000L);
                FirebaseTransport.Result result = FirebaseTransport.publish(
                    a.getApplicationContext(), ReadSync.topic(),
                    "MLC_STOCK_READY_CONFIRM_V1", request, 1);
                if (!result.ok) throw new Exception("No se pudo enviar a Windows: "+result.detail);
                posted = true;
                a.getSharedPreferences(AppConfig.PREFS, Activity.MODE_PRIVATE).edit()
                    .putLong("ready_stock_attempt:" + oid, System.currentTimeMillis()).apply();
                long deadline = System.currentTimeMillis()+115000L;
                boolean answered = false;
                while (System.currentTimeMillis()<deadline) {
                    FirebaseTransport.JsonResult answer = FirebaseTransport.readJsonRest(
                        a.getApplicationContext(), "stock_ready_results/"+rid, 0);
                    if (answer.ok && answer.data != null && answer.data.has("status")) {
                        JSONObject response = answer.data;
                        if (!rid.equals(response.optString("request_id", ""))
                                || !oid.equals(response.optString("order_id", ""))
                                || !sid.equals(response.optString("shipment_id", "")))
                            throw new Exception("La PC respondió sobre otra orden. Se descartó.");
                        if (response.optLong("expires_at",0L)*1000L < System.currentTimeMillis())
                            throw new Exception("La respuesta de Windows venció.");
                        status = response.optString("status", "error");
                        msg = response.optString("message", "Sin detalle");
                        answered = true; break;
                    }
                    Thread.sleep(2700L);
                }
                if (!answered) throw new Exception("Windows no respondió a tiempo. "
                  + "No repitas la confirmación sin verificar primero en Mercado Libre.");
            } catch(Exception ex) {
                status = "error";
                msg = ex.getMessage()==null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            final String endStatus=status, endMsg=msg;
            final boolean wasPosted=posted;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || (Build.VERSION.SDK_INT>=17 && a.isDestroyed())) return;
                btn.setEnabled(true);
                btn.setText("Ya tengo el producto (confirmar en ML)");
                boolean verified = "ready".equals(endStatus) || "already".equals(endStatus);
                new AlertDialog.Builder(a).setTitle(verified ? "Mercado Libre confirmó"
                        : "Verificar la orden en Mercado Libre")
                    .setMessage(endMsg + (verified
                       ? "\n\nLa etiqueta debería estar habilitada. Tocá Compartir etiqueta ML cuando quieras."
                       : "\n\nNo repitas la confirmación sin comprobar el estado real de esta orden."))
                    .setPositiveButton("Aceptar",null).show();
                if(wasPosted) Toast.makeText(a, "Solicitud individual registrada",Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static void warn(Activity a, String msg) {
        new AlertDialog.Builder(a).setTitle("Operación detenida por seguridad")
            .setMessage(msg).setPositiveButton("Aceptar",null).show();
    }
}
