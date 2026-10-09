package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.widget.Button;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sólo pide PDF a ML Central Windows: OAuth ML nunca llega a Android.
 * El PDF queda en caché privado, compartido por content URI (no file://).
 */
public final class ShippingLabelShare {
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MLLabelShare");
                t.setDaemon(true);
                return t;
            });
    private static final int MAX_PDF_BYTES = 700_000;

    private ShippingLabelShare() {}

    public static void addButton(Activity activity, android.widget.LinearLayout card,
                                 JSONObject sale) {
        if (sale == null || !"to_rocha".equals(sale.optString("stage", ""))) return;
        String orderId = sale.optString("order_id", "").trim();
        if (!orderId.matches("[0-9]{5,32}")) return;
        SharedPreferences prefs = activity.getSharedPreferences(AppConfig.PREFS, Activity.MODE_PRIVATE);
        boolean already = sale.optBoolean("ml_label_printed", false)
                || prefs.getBoolean("label_shared_" + orderId, false);
        Button share = UiKit.primaryButton(activity,
                already ? "Volver a enviar etiqueta ML" : "Compartir etiqueta ML por WhatsApp");
        share.setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setTitle("Etiqueta oficial de Mercado Libre")
                .setMessage("Orden " + orderId + "\n\nLa PC consultará Mercado Libre y traerá "
                        + "la etiqueta PDF. No se marcará 'Ya tengo el producto' "
                        + "ni se cambiará ningún estado. ¿Continuar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Solicitar etiqueta", (dialog, which) ->
                        requestAndShare(activity, share, orderId))
                .show());
        card.addView(share, UiKit.fullWidth(activity, 9, 0));
    }

    private static void requestAndShare(Activity activity, Button button, String orderId) {
        button.setEnabled(false);
        button.setText("Esperando etiqueta de Windows…");
        EXECUTOR.execute(() -> {
            String error = "";
            File pdf = null;
            try {
                String rid = UUID.randomUUID().toString();
                JSONObject body = new JSONObject();
                body.put("type", "label_request_v1");
                body.put("request_id", rid);
                body.put("order_id", orderId);
                body.put("at", System.currentTimeMillis() / 1000L);
                FirebaseTransport.Result sent = FirebaseTransport.publish(
                        activity.getApplicationContext(), ReadSync.topic(),
                        "MLC_LABEL_REQUEST_V1", body, 1);
                if (!sent.ok) {
                    throw new Exception("No se pudo contactar a Windows: " + sent.detail);
                }
                long deadline = System.currentTimeMillis() + 115_000L;
                while (System.currentTimeMillis() < deadline) {
                    if (Thread.currentThread().isInterrupted()) break;
                    FirebaseTransport.JsonResult answer = FirebaseTransport.readJsonRest(
                            activity.getApplicationContext(), "label_files/" + rid, 0);
                    if (answer.ok && answer.data != null && answer.data.has("status")) {
                        JSONObject data = answer.data;
                        if (!rid.equals(data.optString("request_id", ""))
                                || !orderId.equals(data.optString("order_id", ""))) {
                            throw new Exception("La PC devolvió una etiqueta para otra orden; se descartó.");
                        }
                        if (data.optLong("expires_at", 0L) * 1000L < System.currentTimeMillis())
                            throw new Exception("La etiqueta ya venció. Volvé a solicitarla.");
                        if (!"ready".equals(data.optString("status", "")))
                            throw new Exception(data.optString("error",
                                    "Mercado Libre todavía no permite obtener esta etiqueta."));
                        byte[] bytes = Base64.decode(data.optString("data_b64", ""), Base64.DEFAULT);
                        if (bytes.length < 10 || bytes.length > MAX_PDF_BYTES
                                || bytes[0] != 37 || bytes[1] != 80 || bytes[2] != 68
                                || bytes[3] != 70 || bytes[4] != 45) {
                            throw new Exception("Los datos recibidos no son un PDF válido.");
                        }
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        StringBuilder hex = new StringBuilder();
                        for (byte b : digest.digest(bytes)) {
                            hex.append(String.format(Locale.US, "%02x", b & 0xff));
                        }
                        if (!hex.toString().equalsIgnoreCase(data.optString("sha256", "")))
                            throw new Exception("La etiqueta no superó la verificación de integridad.");
                        File dir = new File(activity.getCacheDir(), "ml_labels");
                        if (!dir.exists() && !dir.mkdirs())
                            throw new Exception("No se pudo preparar el almacenamiento temporal.");
                        // Borrar etiquetas de caché anteriores a 1 hora.
                        File[] old = dir.listFiles();
                        if (old != null) for (File x : old) {
                            if (System.currentTimeMillis() - x.lastModified() > 3600_000L)
                                x.delete();
                        }
                        pdf = new File(dir, "Etiqueta_ML_" + orderId + ".pdf");
                        try (FileOutputStream out = new FileOutputStream(pdf, false)) {
                            out.write(bytes);
                        }
                        break;
                    }
                    Thread.sleep(2700L);
                }
                if (pdf == null) throw new Exception(
                        "Windows no respondió en dos minutos. Comprobá que ML Central esté abierto y conectado.");
            } catch (Exception ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            final File resultFile = pdf;
            final String problem = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()))
                    return;
                button.setEnabled(true);
                if (resultFile == null) {
                    button.setText("Compartir etiqueta ML por WhatsApp");
                    new AlertDialog.Builder(activity)
                            .setTitle("Etiqueta no disponible")
                            .setMessage(problem + "\n\nNingún estado fue modificado.")
                            .setPositiveButton("Aceptar", null)
                            .show();
                    return;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(activity,
                            activity.getPackageName() + ".shippinglabels", resultFile);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/pdf");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    share.setClipData(android.content.ClipData.newUri(
                            activity.getContentResolver(), "Etiqueta Mercado Libre", uri));
                    activity.startActivity(Intent.createChooser(share,
                            "Compartir etiqueta PDF (WhatsApp)"));
                    // Muestra volver a compartir en la próxima entrada. Android no
                    // puede asegurar que WhatsApp haya enviado el archivo.
                    activity.getSharedPreferences(AppConfig.PREFS, Activity.MODE_PRIVATE)
                            .edit().putBoolean("label_shared_" + orderId, true).apply();
                    button.setText("Volver a enviar etiqueta ML");
                } catch (Exception ex) {
                    button.setText("Compartir etiqueta ML por WhatsApp");
                    new AlertDialog.Builder(activity).setTitle("No se pudo compartir")
                            .setMessage(String.valueOf(ex.getMessage()))
                            .setPositiveButton("Aceptar", null).show();
                }
            });
        });
    }
}
