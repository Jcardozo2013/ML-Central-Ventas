package com.mlcentral.ventas;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Escaneo CONTINUO por tandas: abrir cámara, leer 1-100 etiquetas sin cerrar,
 * Listo, revisar/eliminar filas y Confirmar todos UNA sola vez.
 * Ningún escaneo modifica estados; Windows tiene la última palabra.
 */
public final class LabelScanActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 1107;
    private static final int MAX_BATCH = 100;

    private static final class Item {
        final String orderId, product, stage, action, before, after;
        Item(String orderId, String product, String stage,
             String action, String before, String after) {
            this.orderId = orderId;
            this.product = product;
            this.stage = stage;
            this.action = action;
            this.before = before;
            this.after = after;
        }
    }

    private final LinkedHashMap<String, Item> selected = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MLBatchScannerWorker");
        t.setDaemon(true);
        return t;
    });
    private Map<String, JSONObject> orders = Collections.emptyMap();
    private Map<String, List<JSONObject>> packs = Collections.emptyMap();
    private boolean indexReady = false;
    private boolean inReview = false;
    private boolean sending = false;
    private boolean torchOn = false;
    private boolean cameraGranted = false;
    private int generation = 0;

    private LinearLayout page;
    private FrameLayout cameraPanel;
    private DecoratedBarcodeView camera;
    private LinearLayout reviewPanel, reviewRows;
    private TextView counter, scanNotice, reviewTitle, reviewNotice;
    private Button ready, confirm, continueScan;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        indexSalesAsync();
        // Al entrar se activa la cámara directamente, sin pantalla intermedia.
        startCameraIfAllowed();
    }

    private void buildUi() {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UiKit.BG);

        cameraPanel = new FrameLayout(this);
        cameraPanel.setBackgroundColor(Color.BLACK);
        camera = new DecoratedBarcodeView(this);
        camera.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(
                Arrays.asList(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128,
                        BarcodeFormat.CODE_39)));
        camera.setStatusText("Apuntá al QR ORDEN de la etiqueta");
        camera.decodeContinuous(new BarcodeCallback() {
            @Override public void barcodeResult(BarcodeResult result) {
                if (result == null || result.getText() == null
                        || inReview || sending) return;
                onScanned(result.getText().trim());
            }
            @Override public void possibleResultPoints(List<ResultPoint> resultPoints) {}
        });
        cameraPanel.addView(camera, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(UiKit.dp(this, 15), UiKit.dp(this, 18),
                UiKit.dp(this, 15), UiKit.dp(this, 20));
        overlay.setBackgroundColor(Color.argb(226, 14, 23, 41));

        counter = UiKit.text(this, "0 etiquetas escaneadas", 21, Color.WHITE, true);
        overlay.addView(counter);
        scanNotice = UiKit.text(this, "Abriendo cámara…", 13,
                Color.rgb(211, 229, 249), false);
        overlay.addView(scanNotice, UiKit.fullWidth(this, 4, 12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button exit = UiKit.button(this, "Salir");
        exit.setOnClickListener(v -> finish());
        actions.addView(exit, new LinearLayout.LayoutParams(0, -2, 1));

        Button torch = UiKit.button(this, "Luz");
        torch.setOnClickListener(v -> {
            torchOn = !torchOn;
            if (torchOn) camera.setTorchOn(); else camera.setTorchOff();
            torch.setText(torchOn ? "Luz ✓" : "Luz");
        });
        LinearLayout.LayoutParams torchParams = new LinearLayout.LayoutParams(0, -2, 1);
        torchParams.setMargins(UiKit.dp(this, 7), 0, 0, 0);
        actions.addView(torch, torchParams);

        ready = UiKit.primaryButton(this, "Listo (0)");
        ready.setEnabled(false);
        ready.setOnClickListener(v -> openReview());
        LinearLayout.LayoutParams readyParams = new LinearLayout.LayoutParams(0, -2, 1.5f);
        readyParams.setMargins(UiKit.dp(this, 7), 0, 0, 0);
        actions.addView(ready, readyParams);
        overlay.addView(actions);

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(-1, -2,
                Gravity.BOTTOM);
        cameraPanel.addView(overlay, overlayParams);
        page.addView(cameraPanel, new LinearLayout.LayoutParams(-1, 0, 1));

        reviewPanel = new LinearLayout(this);
        reviewPanel.setOrientation(LinearLayout.VERTICAL);
        reviewPanel.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 20),
                UiKit.dp(this, 16), UiKit.dp(this, 16));
        reviewTitle = UiKit.text(this, "Revisar etiquetas", 25, UiKit.TEXT, true);
        reviewPanel.addView(reviewTitle);
        reviewNotice = UiKit.text(this,
                "Verificá cada orden. Tocá ✕ para quitar un escaneo antes de confirmar.",
                13, UiKit.MUTED, false);
        reviewPanel.addView(reviewNotice, UiKit.fullWidth(this, 6, 9));

        ScrollView scroll = new ScrollView(this);
        reviewRows = new LinearLayout(this);
        reviewRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(reviewRows);
        reviewPanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        continueScan = UiKit.button(this, "Seguir escaneando");
        continueScan.setOnClickListener(v -> resumeBatch());
        reviewPanel.addView(continueScan, UiKit.fullWidth(this, 10, 8));

        confirm = UiKit.primaryButton(this, "Confirmar cambios");
        confirm.setOnClickListener(v -> confirmBatch());
        reviewPanel.addView(confirm);
        reviewPanel.setVisibility(View.GONE);
        page.addView(reviewPanel, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(page);
        updateCounter();
    }

    private void indexSalesAsync() {
        final int request = ++generation;
        worker.execute(() -> {
            Map<String,JSONObject> idIndex = new HashMap<>();
            Map<String,List<JSONObject>> packIndex = new HashMap<>();
            try {
                JSONObject snapshot = StateStore.snapshotSales(getApplicationContext());
                JSONArray ids = snapshot.names();
                if (ids != null) for (int i = 0; i < ids.length(); i++) {
                    JSONObject row = snapshot.optJSONObject(ids.optString(i));
                    if (row == null) continue;
                    String id = row.optString("order_id", "").trim();
                    if (!id.isEmpty()) idIndex.put(id, row);
                    String pack = row.optString("pack_id", "").trim();
                    if (!pack.isEmpty()) {
                        if (!packIndex.containsKey(pack)) packIndex.put(pack, new ArrayList<>());
                        packIndex.get(pack).add(row);
                    }
                }
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                if (generation != request || isFinishing()) return;
                orders = idIndex;
                packs = packIndex;
                indexReady = true;
                if (!inReview) scanNotice.setText("Escaneá varias etiquetas. Tocá Listo al terminar.");
            });
        });
    }

    private void startCameraIfAllowed() {
        if (Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraGranted = true;
            if (!inReview) camera.resume();
        } else {
            scanNotice.setText("Necesitamos permiso de cámara para escanear las etiquetas.");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            cameraGranted = true;
            if (!inReview) camera.resume();
        } else {
            scanNotice.setText("Cámara sin permiso. Volvé a entrar y autorizá el acceso.");
            Toast.makeText(this, "La cámara necesita permiso para escanear", Toast.LENGTH_LONG).show();
        }
    }

    private void onScanned(String encoded) {
        if (!indexReady) {
            scanNotice.setText("Cargando las órdenes guardadas…");
            return;
        }
        boolean exactQr = encoded.startsWith("MLC1:");
        String id = exactQr ? encoded.substring(5).trim() : encoded;
        if (!id.matches("[0-9]{5,32}")) {
            scanNotice.setText("QR no reconocido · usá la etiqueta interna de ML Central.");
            return;
        }
        JSONObject row = orders.get(id);
        if (row == null && !exactQr) {
            // Sólo una etiqueta vieja SIN prefijo permite pack_id como
            // respaldo. Un QR ORDEN nunca se reasigna a otro pedido.
            List<JSONObject> candidates = packs.get(id);
            if (candidates != null && candidates.size() == 1) row = candidates.get(0);
            else if (candidates != null && candidates.size() > 1) {
                scanNotice.setText("Pack " + id + " tiene varias órdenes. Escaneá QR ORDEN.");
                return;
            }
        }
        if (row == null) {
            scanNotice.setText("No se encontró orden " + id + " en estados de Windows.");
            return;
        }
        String order = row.optString("order_id", "").trim();
        if (selected.containsKey(order)) {
            // El lector puede ver el mismo QR muchos cuadros seguidos:
            // ignorar sin refrescar la UI ni agregar la venta dos veces.
            return;
        }
        if (selected.size() >= MAX_BATCH) {
            scanNotice.setText("Límite de " + MAX_BATCH + " etiquetas. Tocá Listo.");
            return;
        }
        if (StateSync.hasPendingForOrder(this, order)) {
            scanNotice.setText("Orden " + order + ": cambio anterior pendiente de la PC.");
            return;
        }

        String stage = row.optString("stage", "");
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
            scanNotice.setText("Orden " + order + " en estado "
                    + row.optString("stage_label", stage) + ": no avanza por QR.");
            return;
        }
        selected.put(order, new Item(order,
                row.optString("product", "Producto"), stage, action, before, after));
        updateCounter();
        scanNotice.setText("✓ " + row.optString("product", "Producto")
                + " · agregada " + selected.size() + "/" + MAX_BATCH);
    }

    private void updateCounter() {
        int n = selected.size();
        counter.setText(n + (n == 1 ? " etiqueta escaneada" : " etiquetas escaneadas"));
        ready.setText("Listo (" + n + ")");
        ready.setEnabled(n > 0 && !sending);
    }

    private void openReview() {
        if (selected.isEmpty() || sending) return;
        inReview = true;
        camera.pause();
        cameraPanel.setVisibility(View.GONE);
        reviewPanel.setVisibility(View.VISIBLE);
        renderReview();
    }

    private void renderReview() {
        reviewRows.removeAllViews();
        reviewTitle.setText("Revisar " + selected.size()
                + (selected.size() == 1 ? " etiqueta" : " etiquetas"));
        confirm.setText("Confirmar " + selected.size()
                + (selected.size() == 1 ? " cambio" : " cambios"));
        confirm.setEnabled(!selected.isEmpty() && !sending);
        continueScan.setEnabled(!sending);
        if (selected.isEmpty()) {
            reviewRows.addView(UiKit.text(this,
                    "No quedan etiquetas. Tocá Seguir escaneando para agregar otras.",
                    14, UiKit.MUTED, false));
        }
        for (Item item : new ArrayList<>(selected.values())) {
            LinearLayout card = UiKit.card(this);
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(UiKit.text(this, item.product, 16, UiKit.TEXT, true));
            info.addView(UiKit.text(this, "Orden " + item.orderId, 12, UiKit.MUTED, false),
                    UiKit.fullWidth(this, 4, 5));
            info.addView(UiKit.text(this, item.before + "  →  " + item.after,
                    13, UiKit.ACCENT, true));
            line.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            Button remove = UiKit.button(this, "✕");
            remove.setTextColor(UiKit.RED);
            remove.setTextSize(20);
            remove.setEnabled(!sending);
            remove.setOnClickListener(v -> {
                if (sending) return;
                selected.remove(item.orderId);
                renderReview();
            });
            LinearLayout.LayoutParams removeParams =
                    new LinearLayout.LayoutParams(UiKit.dp(this, 49), UiKit.dp(this, 49));
            removeParams.setMargins(UiKit.dp(this, 8), 0, 0, 0);
            line.addView(remove, removeParams);
            card.addView(line);
            reviewRows.addView(card, UiKit.fullWidth(this, 0, 9));
        }
    }

    private void resumeBatch() {
        if (sending) return;
        inReview = false;
        reviewPanel.setVisibility(View.GONE);
        cameraPanel.setVisibility(View.VISIBLE);
        updateCounter();
        // Recargar los últimos estados antes de una nueva tanda.
        indexReady = false;
        indexSalesAsync();
        if (cameraGranted) camera.resume();
        else startCameraIfAllowed();
        scanNotice.setText("Seguís escaneando · " + selected.size() + " en la tanda.");
    }

    private void confirmBatch() {
        if (selected.isEmpty() || sending) return;
        sending = true;
        renderReview();
        reviewNotice.setText("Guardando la tanda y enviando a Windows…");
        List<StateSync.ScanCommand> batch = new ArrayList<>();
        for (Item item : selected.values()) {
            batch.add(new StateSync.ScanCommand(item.orderId, item.action, item.stage));
        }
        final int request = generation;
        worker.execute(() -> {
            StateSync.BatchResult result = StateSync.queueScanBatch(getApplicationContext(), batch);
            runOnUiThread(() -> {
                if (request != generation || isFinishing()) return;
                sending = false;
                if (result.queued > 0) {
                    for (StateSync.ScanCommand command : batch) {
                        if (!result.rejectedOrderIds.contains(command.orderId))
                            selected.remove(command.orderId);
                    }
                }
                renderReview();
                if (result.queued > 0) {
                    reviewNotice.setText(result.queued + " cambios guardados en cola para "
                            + "enviar y confirmar en Windows. "
                            + (result.rejectedOrderIds.isEmpty()
                            ? "Ninguna orden se perdió."
                            : result.rejectedOrderIds.size()
                              + " no se enviaron; revisá el estado actual antes de reintentar.")
                            + " No se modifican estados hasta la confirmación de la PC.");
                    Toast.makeText(this, "Tanda enviada · esperando confirmación de Windows",
                            Toast.LENGTH_LONG).show();
                } else {
                    reviewNotice.setText("No se pudo encolar ningún cambio. "
                            + "Revisá que los estados sigan vigentes y que no haya envíos pendientes.");
                }
                if (selected.isEmpty()) {
                    confirm.setEnabled(false);
                    continueScan.setText("Escanear otra tanda");
                }
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (camera != null && cameraGranted && !inReview) camera.resume();
    }

    @Override protected void onPause() {
        if (camera != null) camera.pause();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (inReview && !sending) {
            resumeBatch();
        } else if (!sending) {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        generation++;
        if (camera != null) camera.pause();
        worker.shutdownNow();
        super.onDestroy();
    }
}
