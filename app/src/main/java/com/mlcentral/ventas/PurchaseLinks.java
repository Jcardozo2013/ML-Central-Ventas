package com.mlcentral.ventas;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** Enlaces de compra BR guardados por Windows para ESTA venta/variante. */
public final class PurchaseLinks {
    private PurchaseLinks() {}

    public static String verifiedUrl(JSONObject row) {
        if (row == null) return "";
        String raw = row.optString("br_buy_url", "").trim();
        if (raw.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return "";
            if (uri.getHost() == null || uri.getHost().trim().isEmpty()) return "";
            return raw;
        } catch (Exception ignored) { return ""; }
    }

    public static void addToCard(Activity activity, LinearLayout container, JSONObject row,
                                 boolean showMissing) {
        String url = verifiedUrl(row);
        if (url.isEmpty()) {
            if (showMissing) {
                TextView note = UiKit.text(activity,
                        "Link BR no sincronizado para esta venta/variante. Revisá en Windows.",
                        12, UiKit.ORANGE, false);
                container.addView(note, UiKit.fullWidth(activity, 8, 0));
            }
            return;
        }
        Button open = UiKit.button(activity, "Abrir link de compra Brasil ↗");
        open.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(activity, "No se encontró navegador para abrir el enlace.", Toast.LENGTH_LONG).show();
            }
        });
        container.addView(open, UiKit.fullWidth(activity, 8, 0));

        Button copy = UiKit.button(activity, "Copiar link de compra");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Compra Brasil", url));
                Toast.makeText(activity, "Enlace BR copiado", Toast.LENGTH_SHORT).show();
            }
        });
        container.addView(copy, UiKit.fullWidth(activity, 6, 0));
    }
}
