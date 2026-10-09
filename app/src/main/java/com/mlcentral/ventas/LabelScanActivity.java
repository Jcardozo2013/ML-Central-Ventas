package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lectura local de etiquetas. Nunca cambia un estado por leer un QR:
 * sólo el botón Confirmar encola una orden al protocolo StateSync existente.
 */
public final class LabelScanActivity extends Activity {
    private TextView resultText;
    private Button confirmButton;
    private String selectedOrder = "";
    private String selectedStage = "";
    private String selectedAction = "";
    private String selectedProduct = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.BG);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 24),
                UiKit.dp(this, 18), UiKit.dp(this, 24));
        scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(UiKit.text(this, "Escanear etiqueta", 27, UiKit.TEXT, true));
        root.addView(UiKit.text(this,
                "El mismo QR se usa en cada etapa. Escanear no modifica nada: tenés que confirmar.",
                14, UiKit.MUTED, false), UiKit.fullWidth(this, 7, 16));

        Button scan = UiKit.primaryButton(this, "Abrir cámara y escanear");
        scan.setOnClickListener(v -> startScan());
        root.addView(scan, UiKit.fullWidth(this, 0, 12));

        LinearLayout card = UiKit.card(this);
        resultText = UiKit.text(this,
                "Escaneá la etiqueta interna de ML Central (QR ORDEN).",
                15, UiKit.TEXT, false);
        card.addView(resultText);
        confirmButton = UiKit.primaryButton(this, "Confirmar cambio de estado");
        confirmButton.setEnabled(false);
        confirmButton.setOnClickListener(v -> confirm());
        card.addView(confirmButton, UiKit.fullWidth(this, 12, 0));
        root.addView(card, UiKit.fullWidth(this, 0, 12));

        TextView info = UiKit.text(this,
                "1.er escaneo: Llegaron BR → Pendiente Rocha.\n"
                + "2.º escaneo: Pendiente Rocha → A Rocha.\n"
                + "Para el segundo escaneo, primero debe llegar la confirmación de Windows.",
                13, UiKit.MUTED, false);
        root.addView(info, UiKit.fullWidth(this, 6, 14));
        Button statuses = UiKit.button(this, "Ver Estados y sincronización");
        statuses.setOnClickListener(v -> startActivity(new Intent(this, StatusActivity.class)));
        root.addView(statuses);
        shell.addView(UiKit.bottomNav(this, -1),
                new LinearLayout.LayoutParams(-1, -2));
        setContentView(shell);
    }

    private void startScan() {
        clearSelection("Abriendo cámara…");
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
                .setPrompt("Apuntá al QR ORDEN de ML Central")
                .setBeepEnabled(true)
                .setOrientationLocked(false)
                .initiateScan();
    }

    private void clearSelection(String text) {
        selectedOrder = "";
        selectedStage = "";
        selectedAction = "";
        selectedProduct = "";
        if (resultText != null) resultText.setText(text);
        if (confirmButton != null) confirmButton.setEnabled(false);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() == null) {
                clearSelection("Escaneo cancelado; no se cambió ningún estado.");
            } else {
                inspect(result.getContents().trim());
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** Sólo aceptamos identificadores numéricos, sin URLs ni órdenes arbitrarias. */
    private void inspect(String encoded) {
        clearSelection("Buscando venta…");
        String id = encoded.startsWith("MLC1:") ? encoded.substring(5) : encoded;
        if (!id.matches("[0-9]{5,32}")) {
            clearSelection("Código no reconocido. Escaneá el QR ORDEN de una etiqueta interna ML Central.");
            return;
        }

        JSONObject sales = StateStore.snapshotSales(this);
        JSONArray keys = sales.names();
        List<JSONObject> matches = new ArrayList<>();
        if (keys != null) for (int i = 0; i < keys.length(); i++) {
            JSONObject row = sales.optJSONObject(keys.optString(i));
            if (row == null) continue;
            String order = row.optString("order_id", "").trim();
            String pack = row.optString("pack_id", "").trim();
            if (id.equals(order) || (encoded.equals(id) && id.equals(pack))) {
                matches.add(row);
            }
        }
        if (matches.isEmpty()) {
            clearSelection("Orden " + id + " no aparece en los estados sincronizados. "
                    + "Actualizá Estados y volvé a escanear. Nada cambió.");
            return;
        }
        if (matches.size() != 1) {
            clearSelection("El número " + id + " corresponde a varias órdenes del mismo pack. "
                    + "Imprimí la etiqueta con QR ORDEN de la orden exacta. Nada cambió.");
            return;
        }

        JSONObject row = matches.get(0);
        String order = row.optString("order_id", "").trim();
        String stage = row.optString("stage", "");
        String product = row.optString("product", "Producto");
        if (StateSync.hasPendingForOrder(this, order)) {
            clearSelection("Orden " + order + "\n" + product
                    + "\n\nYa tiene un cambio pendiente. Esperá la confirmación de Windows; no se envió otro.");
            return;
        }

        // Bloqueo persistente de doble escaneo si un resultado quedó pendiente,
        // incluso si la aplicación se cerró entre medio.
        android.content.SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        if (order.equals(p.getString("scan_last_order_v175", ""))
                && stage.equals(p.getString("scan_last_stage_v175", ""))) {
            clearSelection("Orden " + order + "\n" + product
                    + "\n\nYa confirmaste el cambio desde este estado. "
                    + "Esperá que Windows confirme el siguiente estado. Nada cambió.");
            return;
        }

        String action, before, after;
        if ("br_arrived".equals(stage)) {
            action = "mark_received";
            before = "Llegaron BR";
            after = "Pendiente Rocha";
        } else if ("pending_rocha".equals(stage)) {
            action = "send_to_rocha";
            before = "Pendiente Rocha";
            after = "A Rocha";
        } else {
            clearSelection("Orden " + order + "\n" + product + "\n\n"
                    + "Estado actual: " + row.optString("stage_label", stage)
                    + "\nNo corresponde cambiar con este escaneo. Nada cambió.");
            return;
        }

        selectedOrder = order;
        selectedStage = stage;
        selectedAction = action;
        selectedProduct = product;
        resultText.setText(product + "\nOrden " + order
                + "\n\nESTADO ACTUAL: " + before
                + "\nCAMBIO PROPUESTO: " + after
                + "\n\nTodavía NO se cambió nada.");
        confirmButton.setEnabled(true);
    }

    private void confirm() {
        if (selectedOrder.isEmpty() || selectedAction.isEmpty()) return;
        final String order = selectedOrder, stage = selectedStage;
        final String action = selectedAction, product = selectedProduct;
        if (StateSync.hasPendingForOrder(this, order)) {
            clearSelection("Esa orden ya tiene un cambio pendiente. Esperá la PC.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("¿Confirmar cambio de estado?")
                .setMessage(product + "\nOrden " + order + "\n\n"
                        + ("mark_received".equals(action)
                        ? "Llegaron BR → Pendiente Rocha" : "Pendiente Rocha → A Rocha")
                        + "\n\nWindows debe confirmar el cambio. Esta operación no despacha por Mercado Libre.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Confirmar", (d,w) -> {
                    // El estado puede haber cambiado mientras mirábamos el diálogo.
                    if (StateSync.hasPendingForOrder(this, order)) {
                        clearSelection("La orden ya tiene un cambio pendiente. No se duplicó.");
                        return;
                    }
                    JSONObject latest = StateStore.snapshotSales(this).optJSONObject(order);
                    if (latest == null || !stage.equals(latest.optString("stage", ""))) {
                        clearSelection("El estado de la orden cambió. Volvé a escanear para verificarlo.");
                        return;
                    }
                    String commandId = StateSync.queueAction(this, order, action, stage);
                    if (commandId == null || commandId.isEmpty()) {
                        clearSelection("No se pudo guardar el cambio. No se envió.");
                        return;
                    }
                    getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
                            .putString("scan_last_order_v175", order)
                            .putString("scan_last_stage_v175", stage).apply();
                    clearSelection("Cambio enviado para confirmación de Windows.\n"
                            + product + "\nOrden " + order
                            + "\n\nNo vuelvas a escanear hasta ver el estado nuevo confirmado.");
                    Toast.makeText(this, "Cambio en cola · esperando confirmación de la PC",
                            Toast.LENGTH_LONG).show();
                }).show();
    }
}
