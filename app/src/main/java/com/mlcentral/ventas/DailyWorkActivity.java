package com.mlcentral.ventas;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vista de SOLO LECTURA. Reutiliza los Estados locales ya sincronizados,
 * no modifica ventas, no solicita un nuevo snapshot ni toca Firebase.
 */
public final class DailyWorkActivity extends Activity {
    private static final String[] STAGES = {
            "purchase_pending", "pending_rocha", "receive_pending", "br_arrived", "to_rocha"
    };
    private static final String[] LABELS = {
            "Comprar en Brasil", "Preparar / enviar a Rocha", "En camino desde Brasil",
            "Recibidos de Brasil", "Seguimiento hacia Rocha"
    };
    private static final String[] NOTES = {
            "Revisá variante, cantidad, precio y fecha oficial antes de comprar.",
            "Prepará y controlá los productos antes de enviarlos a Rocha.",
            "Seguí el transporte y confirmá la recepción cuando corresponda.",
            "Revisá la mercadería recibida y el siguiente paso operativo.",
            "En seguimiento. Consultá el estado real del envío."
    };
    private LinearLayout content;
    private TextView status;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DailyWorkReadOnly");
        t.setDaemon(true);
        return t;
    });
    private volatile int generation = 0;

    private static final class WorkSnapshot {
        long lastSyncAt;
        final List<List<JSONObject>> groups = new ArrayList<>();
        WorkSnapshot() {
            for (int i = 0; i < STAGES.length; i++) groups.add(new ArrayList<>());
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.BG);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 22),
                UiKit.dp(this, 18), UiKit.dp(this, 24));
        scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView title = UiKit.text(this, "Mi trabajo de hoy", 27, UiKit.TEXT, true);
        root.addView(title);
        TextView subtitle = UiKit.text(this,
                "Prioridades según los últimos estados de ML Central Windows. Solo consulta: no realiza compras ni modifica pedidos.",
                13, UiKit.MUTED, false);
        root.addView(subtitle, UiKit.fullWidth(this, 5, 12));
        status = UiKit.text(this, "Cargando estados guardados…", 13, UiKit.MUTED, false);
        root.addView(status, UiKit.fullWidth(this, 0, 9));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        Button states = UiKit.button(this, "Ver todos los estados");
        states.setOnClickListener(v -> startActivity(new Intent(this, StatusActivity.class)));
        root.addView(states, UiKit.fullWidth(this, 11, 0));
        shell.addView(UiKit.bottomNav(this, 0),
                new LinearLayout.LayoutParams(-1, -2));
        setContentView(shell);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        final int request = ++generation;
        loader.execute(() -> {
            WorkSnapshot result = new WorkSnapshot();
            result.lastSyncAt = StateStore.lastSyncAt(this);
            try {
                // Una sola lectura y clasificación, fuera del hilo gráfico.
                JSONObject all = StateStore.snapshotSales(this);
                JSONArray ids = all.names();
                if (ids != null) for (int i = 0; i < ids.length(); i++) {
                    JSONObject sale = all.optJSONObject(ids.optString(i));
                    if (sale == null) continue;
                    String stage = sale.optString("stage", "");
                    for (int g = 0; g < STAGES.length; g++) {
                        if (STAGES[g].equals(stage)) {
                            result.groups.get(g).add(sale);
                            break;
                        }
                    }
                }
                for (List<JSONObject> list : result.groups) {
                    Collections.sort(list, (a,b) ->
                            Long.compare(a.optLong("sale_unix", 0L),
                                         b.optLong("sale_unix", 0L)));
                }
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                if (request != generation || isFinishing()
                        || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                render(result);
            });
        });
    }

    private void openStage(String stage) {
        Intent i = new Intent(this, StatusActivity.class);
        i.putExtra("stage", stage);
        startActivity(i);
    }

    private void render(WorkSnapshot snap) {
        content.removeAllViews();
        if (snap.lastSyncAt == 0L) {
            status.setText("Esperando los primeros estados de Windows. Abrí Estados para solicitar una actualización.");
            return;
        }
        int total = 0;
        for (List<JSONObject> group : snap.groups) total += group.size();
        String when = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                .format(new Date(snap.lastSyncAt));
        status.setText(total + " pedido(s) en seguimiento · último tablero " + when
                + "\nLas fechas límite deben verificarse en Mercado Libre; aquí no se estiman.");
        for (int g=0; g<STAGES.length;g++) {
            final int groupIndex = g;
            List<JSONObject> rows = snap.groups.get(g);
            if (rows.isEmpty()) continue;
            LinearLayout card = UiKit.card(this);
            TextView heading = UiKit.text(this, LABELS[g] + " · " + rows.size(),
                    18, UiKit.TEXT, true);
            card.addView(heading);
            TextView note = UiKit.text(this, NOTES[g], 12, UiKit.MUTED, false);
            card.addView(note, UiKit.fullWidth(this, 4, 7));
            int limit = Math.min(rows.size(), 8);
            for (int k=0;k<limit;k++) {
                JSONObject row = rows.get(k);
                String name = row.optString("product", "Producto");
                String order = row.optString("order_id", "");
                int qty = Math.max(1, row.optInt("quantity", 1));
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 6));
                item.addView(UiKit.text(this, name, 14, UiKit.TEXT, true));
                item.addView(UiKit.text(this, "Orden " + order + " · " + qty
                        + (qty == 1 ? " unidad" : " unidades"), 12, UiKit.MUTED, false));
                card.addView(item);
            }
            Button open = UiKit.button(this, "Abrir " + LABELS[g] +
                    (rows.size() > limit ? " · ver los " + rows.size() : ""));
            open.setOnClickListener(v -> openStage(STAGES[groupIndex]));
            card.addView(open, UiKit.fullWidth(this, 8, 0));
            content.addView(card, UiKit.fullWidth(this, 0, 11));
        }
        if (total == 0) {
            TextView done = UiKit.text(this, "Sin pedidos pendientes en estos estados.", 15, UiKit.GREEN, true);
            content.addView(done, UiKit.fullWidth(this, 8, 8));
        }
    }

    @Override protected void onDestroy() {
        ++generation;
        loader.shutdownNow();
        super.onDestroy();
    }
}
