package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class StatusActivity extends Activity {
    private static final String[] STAGES = {
            "purchase_pending", "purchase_bought", "stock_local_sale", "receive_pending", "br_arrived",
            "pending_rocha", "to_rocha", "dispatched", "delivered"
    };
    private static final String[] LABELS = {
            "Pendientes compra BR", "Comprados", "Ventas en stock", "En camino BR", "Llegaron BR",
            "Pendiente Rocha", "A Rocha", "Enviados", "Entregadas"
    };

    private LinearLayout cards;
    private LinearLayout list;
    private TextView syncStatus;
    private TextView listTitle;
    private String filter = "all";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String incoming = getIntent() == null ? "" : getIntent().getStringExtra("stage");
        if (incoming != null && !incoming.trim().isEmpty()) filter = incoming.trim();
        buildUi();
        StateSync.requestSnapshotAsync(this, false);
        StateSync.flushPendingAsync(this);
        refresh();
    }

    private void buildUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.BG);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 22), UiKit.dp(this, 18), UiKit.dp(this, 24));
        scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = UiKit.text(this, "Estados", 28, UiKit.TEXT, true);
        root.addView(title);
        TextView subtitle = UiKit.text(this, "El mismo flujo operativo de la PC, también desde el celular.", 14, UiKit.MUTED, false);
        subtitle.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 12));
        root.addView(subtitle);

        LinearLayout statusCard = UiKit.card(this);
        syncStatus = UiKit.text(this, "Estados PC: esperando sincronización…", 14, UiKit.TEXT, true);
        statusCard.addView(syncStatus);
        TextView note = UiKit.text(this,
                "La PC es la fuente central. Si está apagada, los cambios quedan pendientes y se envían cuando vuelva.",
                13, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 7), 0, 0);
        statusCard.addView(note);
        Button sync = UiKit.button(this, "Actualizar estados ahora");
        sync.setOnClickListener(v -> {
            StateSync.requestSnapshotAsync(this, true);
            StateSync.flushPendingAsync(this);
            StateStore.setStatus(this, "Estados PC: actualización solicitada…");
            Toast.makeText(this, "Solicitud enviada a ML Central Windows", Toast.LENGTH_SHORT).show();
            refresh();
        });
        statusCard.addView(sync, UiKit.fullWidth(this, 12, 0));
        Button movements = UiKit.button(this, "Movimientos / Deshacer");
        movements.setOnClickListener(v -> startActivity(new Intent(this, MovementHistoryActivity.class)));
        statusCard.addView(movements, UiKit.fullWidth(this, 8, 0));
        root.addView(statusCard, UiKit.fullWidth(this, 0, 16));

        TextView stageTitle = UiKit.text(this, "Flujo de ventas", 20, UiKit.TEXT, true);
        root.addView(stageTitle, UiKit.fullWidth(this, 0, 9));
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        root.addView(cards);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        listTitle = UiKit.text(this, "Todas las ventas", 20, UiKit.TEXT, true);
        heading.addView(listTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button all = UiKit.button(this, "Ver todas");
        all.setTextSize(13);
        all.setOnClickListener(v -> { filter = "all"; refresh(); });
        heading.addView(all, new LinearLayout.LayoutParams(UiKit.dp(this, 105), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(heading, UiKit.fullWidth(this, 10, 9));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        shell.addView(UiKit.bottomNav(this, 1), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(shell);
    }

    private void refresh() {
        renderCards();
        renderList();
        int pending = StateSync.pendingCount(this);
        String text = StateStore.statusText(this);
        if (pending > 0) text += " · " + pending + (pending == 1 ? " cambio pendiente" : " cambios pendientes");
        syncStatus.setText(text);
    }

    private void renderCards() {
        cards.removeAllViews();
        for (int i = 0; i < STAGES.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(stageButton(i), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (i + 1 < STAGES.length) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                p.setMargins(UiKit.dp(this, 8), 0, 0, 0);
                row.addView(stageButton(i + 1), p);
            } else {
                row.addView(new TextView(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            cards.addView(row, UiKit.fullWidth(this, 0, 8));
        }
    }

    private Button stageButton(int index) {
        String stage = STAGES[index];
        int count = StateStore.countStage(this, stage);
        Button b = UiKit.button(this, LABELS[index] + "\n" + count);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(null, stage.equals(filter) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        if (stage.equals(filter)) {
            b.setTextColor(Color.WHITE);
            b.setBackground(UiKit.rounded(UiKit.ACCENT, 14, this));
        }
        b.setOnClickListener(v -> { filter = stage; refresh(); });
        return b;
    }

    private void renderList() {
        list.removeAllViews();
        String label = "Todas las ventas";
        for (int i = 0; i < STAGES.length; i++) if (STAGES[i].equals(filter)) label = LABELS[i];
        List<JSONObject> rows = StateStore.salesForStage(this, filter);
        listTitle.setText(label + " · " + rows.size());
        if (rows.isEmpty()) {
            LinearLayout empty = UiKit.card(this);
            TextView t = UiKit.text(this,
                    StateStore.total(this) == 0 ? "Esperando el primer tablero de Windows…" : "No hay ventas en este estado.",
                    15, UiKit.MUTED, false);
            t.setGravity(Gravity.CENTER);
            empty.addView(t);
            list.addView(empty);
            return;
        }
        for (JSONObject row : rows) list.addView(saleCard(row), UiKit.fullWidth(this, 0, 9));
    }

    private LinearLayout saleCard(JSONObject row) {
        LinearLayout card = UiKit.card(this);
        String stage = row.optString("stage", "other");
        String orderId = row.optString("order_id", "");

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView stagePill = pillForStage(stage, row.optString("stage_label", stage));
        top.addView(stagePill);
        String when = row.optLong("sale_unix", 0L) > 0
                ? new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(row.optLong("sale_unix") * 1000L))
                : "";
        TextView date = UiKit.text(this, when, 12, UiKit.MUTED, false);
        date.setGravity(Gravity.END);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dp.setMargins(UiKit.dp(this, 8), 0, 0, 0);
        top.addView(date, dp);
        card.addView(top);

        TextView product = UiKit.text(this, row.optString("product", "Producto"), 17, UiKit.TEXT, true);
        product.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 7));
        card.addView(product);

        StringBuilder info = new StringBuilder("Orden ").append(orderId);
        int qty = Math.max(1, row.optInt("quantity", 1));
        info.append(" · ").append(qty).append(qty == 1 ? " unidad" : " unidades");
        if (row.has("sale_amount") && !row.isNull("sale_amount")) info.append("\nVenta: ").append(SaleStore.formatMoney(row.optDouble("sale_amount", 0.0)));
        if (row.has("profit") && !row.isNull("profit")) info.append(" · Ganancia: ").append(SaleStore.formatMoney(row.optDouble("profit", 0.0)));
        TextView detail = UiKit.text(this, info.toString(), 13, UiKit.MUTED, false);
        card.addView(detail);

        boolean pending = StateSync.hasPendingForOrder(this, orderId);
        String action = actionForStage(stage);
        if (pending) {
            TextView p = UiKit.pill(this, "PENDIENTE DE SINCRONIZAR", UiKit.ORANGE, UiKit.ORANGE_SOFT);
            p.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10), UiKit.dp(this, 6));
            card.addView(p, UiKit.fullWidth(this, 12, 0));
        } else if (!action.isEmpty()) {
            Button b = UiKit.button(this, actionLabel(action));
            b.setTextColor(Color.WHITE);
            b.setTypeface(null, android.graphics.Typeface.BOLD);
            b.setBackground(UiKit.rounded(UiKit.ACCENT, 12, this));
            b.setOnClickListener(v -> confirmAction(row, action));
            card.addView(b, UiKit.fullWidth(this, 12, 0));
        }
        return card;
    }

    private TextView pillForStage(String stage, String label) {
        if ("delivered".equals(stage)) return UiKit.pill(this, label, UiKit.GREEN, UiKit.GREEN_SOFT);
        if ("purchase_pending".equals(stage) || "br_arrived".equals(stage)) return UiKit.pill(this, label, UiKit.ORANGE, UiKit.ORANGE_SOFT);
        return UiKit.pill(this, label, UiKit.ACCENT, UiKit.ACCENT_SOFT);
    }

    private String actionForStage(String stage) {
        if ("purchase_pending".equals(stage)) return "mark_bought";
        if ("purchase_bought".equals(stage)) return "mark_in_transit";
        if ("stock_local_sale".equals(stage) || "receive_pending".equals(stage) || "br_arrived".equals(stage)) return "mark_received";
        if ("pending_rocha".equals(stage)) return "send_to_rocha";
        return "";
    }

    private String actionLabel(String action) {
        if ("mark_bought".equals(action)) return "Marcar comprado";
        if ("mark_in_transit".equals(action)) return "Brasil ya lo despachó";
        if ("mark_received".equals(action)) return "Marcar recibida";
        if ("send_to_rocha".equals(action)) return "Enviar a Rocha";
        return "Actualizar";
    }

    private void confirmAction(JSONObject row, String action) {
        String product = row.optString("product", "Producto");
        String oid = row.optString("order_id", "");
        String stage = row.optString("stage", "");
        String label = actionLabel(action);
        String extra = "send_to_rocha".equals(action)
                ? "\n\nEsto solo marca el traslado interno a Rocha. Enviado y Entregado siguen dependiendo del escaneo de Mercado Libre."
                : "";
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setMessage(product + "\nOrden " + oid + extra)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Confirmar", (d, w) -> {
                    StateSync.queueAction(this, oid, action, stage);
                    Toast.makeText(this, "Cambio guardado · esperando confirmación de la PC", Toast.LENGTH_LONG).show();
                    refresh();
                })
                .show();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter("com.mlcentral.ventas.SALE_RECEIVED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, f);
    }

    @Override protected void onResume() {
        super.onResume();
        StateSync.requestSnapshotAsync(this, false);
        StateSync.flushPendingAsync(this);
        refresh();
    }

    @Override protected void onStop() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onStop();
    }
}
