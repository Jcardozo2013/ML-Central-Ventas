package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MovementHistoryActivity extends Activity {
    private LinearLayout list;
    private TextView status;
    private Query query;
    private ValueEventListener listener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        load();
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

        TextView title = UiKit.text(this, "Movimientos", 28, UiKit.TEXT, true);
        root.addView(title);
        TextView subtitle = UiKit.text(this,
                "Cambios hechos desde la PC, celulares o automáticamente. Deshacer queda disponible 5 minutos cuando es seguro.",
                14, UiKit.MUTED, false);
        subtitle.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 12));
        root.addView(subtitle);

        LinearLayout head = UiKit.card(this);
        status = UiKit.text(this, "Cargando movimientos…", 13, UiKit.MUTED, false);
        head.addView(status);
        Button refresh = UiKit.button(this, "Actualizar");
        refresh.setOnClickListener(v -> loadOnce());
        head.addView(refresh, UiKit.fullWidth(this, 10, 0));
        root.addView(head, UiKit.fullWidth(this, 0, 14));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        Button close = UiKit.button(this, "Volver a Estados");
        close.setOnClickListener(v -> finish());
        root.addView(close, UiKit.fullWidth(this, 12, 0));

        setContentView(shell);
    }

    private void load() {
        query = FirebaseDatabase.getInstance().getReference("movements")
                .orderByChild("created_at_unix").limitToLast(100);
        listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) { render(snapshot); }
            @Override public void onCancelled(DatabaseError error) {
                status.setText("Firebase: " + error.getMessage());
            }
        };
        query.addValueEventListener(listener);
    }

    private void loadOnce() {
        if (query == null) return;
        status.setText("Actualizando…");
        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) { render(snapshot); }
            @Override public void onCancelled(DatabaseError error) { status.setText("Firebase: " + error.getMessage()); }
        });
    }

    @SuppressWarnings("unchecked")
    private void render(DataSnapshot snapshot) {
        List<JSONObject> rows = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) {
            try {
                Object raw = child.getValue();
                if (!(raw instanceof Map)) continue;
                JSONObject o = new JSONObject((Map<String, Object>) raw);
                if (!o.has("event_id") && child.getKey() != null) o.put("event_id", child.getKey());
                rows.add(o);
            } catch (Exception ignored) {}
        }
        Collections.sort(rows, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("created_at_unix", 0L), a.optLong("created_at_unix", 0L));
            }
        });
        list.removeAllViews();
        status.setText(rows.isEmpty() ? "Todavía no hay movimientos registrados." : rows.size() + " movimiento(s) recientes");
        for (JSONObject row : rows) list.addView(card(row), UiKit.fullWidth(this, 0, 9));
    }

    private LinearLayout card(JSONObject row) {
        LinearLayout card = UiKit.card(this);
        long created = row.optLong("created_at_unix", 0L);
        String when = created > 0
                ? new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(new Date(created * 1000L))
                : row.optString("created_at", "");
        String source = row.optString("source", "PC");
        TextView top = UiKit.text(this, when + " · " + source, 12, UiKit.MUTED, true);
        card.addView(top);

        String product = row.optString("product", "Producto");
        String order = row.optString("order_id", "");
        TextView name = UiKit.text(this, product, 16, UiKit.TEXT, true);
        name.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(name);
        TextView oid = UiKit.text(this, "Orden " + order, 12, UiKit.MUTED, false);
        card.addView(oid);

        String before = row.optString("before_label", row.optString("before_stage", ""));
        String after = row.optString("after_label", row.optString("after_stage", ""));
        TextView move = UiKit.text(this, before + "  →  " + after, 14, UiKit.ACCENT, true);
        move.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(move);

        String action = actionLabel(row.optString("action", ""));
        if (!action.isEmpty()) {
            TextView a = UiKit.text(this, action, 12, UiKit.MUTED, false);
            a.setPadding(0, UiKit.dp(this, 4), 0, 0);
            card.addView(a);
        }

        boolean undone = !row.optString("undone_at", "").trim().isEmpty();
        boolean undoable = row.optBoolean("undoable", false);
        long until = row.optLong("undo_until_unix", 0L);
        long now = System.currentTimeMillis() / 1000L;
        if (undone) {
            TextView pill = UiKit.pill(this, "DESHECHO", UiKit.GREEN, UiKit.GREEN_SOFT);
            card.addView(pill, UiKit.fullWidth(this, 10, 0));
        } else if (undoable && until > now) {
            Button undo = UiKit.button(this, "Deshacer · quedan " + remaining(until - now));
            undo.setOnClickListener(v -> confirmUndo(row, undo));
            card.addView(undo, UiKit.fullWidth(this, 10, 0));
        } else if (undoable) {
            TextView expired = UiKit.text(this, "Ventana de Deshacer vencida", 12, UiKit.MUTED, false);
            expired.setPadding(0, UiKit.dp(this, 8), 0, 0);
            card.addView(expired);
        }
        return card;
    }

    private String remaining(long seconds) {
        long s = Math.max(0, seconds);
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private String actionLabel(String action) {
        String a = action == null ? "" : action;
        if (a.startsWith("undo:")) return "Deshacer " + actionLabel(a.substring(5));
        if ("mark_bought".equals(a)) return "Marcar comprado";
        if ("mark_in_transit".equals(a)) return "Brasil ya lo despachó";
        if ("mark_received".equals(a)) return "Marcar recibida";
        if ("send_to_rocha".equals(a)) return "Enviar a Rocha";
        if ("reopen_purchase".equals(a)) return "Volver a pendiente";
        if (a.startsWith("sync:")) return "Actualización automática";
        return a;
    }

    private void confirmUndo(JSONObject row, Button button) {
        String eventId = row.optString("event_id", "").trim();
        String orderId = row.optString("order_id", "").trim();
        String expected = row.optString("after_stage", "").trim();
        if (eventId.isEmpty() || orderId.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Deshacer movimiento")
                .setMessage("¿Volver la orden " + orderId + " al estado anterior?\n\nLa PC valida que todavía sea seguro antes de hacerlo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Deshacer", (d, w) -> {
                    button.setEnabled(false);
                    StateSync.queueUndo(this, eventId, orderId, expected);
                    Toast.makeText(this, "Deshacer enviado a ML Central Windows", Toast.LENGTH_LONG).show();
                    status.setText("Esperando confirmación de la PC…");
                })
                .show();
    }

    @Override protected void onDestroy() {
        try { if (query != null && listener != null) query.removeEventListener(listener); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
