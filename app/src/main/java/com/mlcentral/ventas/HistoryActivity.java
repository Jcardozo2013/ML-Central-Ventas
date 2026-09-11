package com.mlcentral.ventas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HistoryActivity extends Activity {
    private LinearLayout list;
    private TextView count;
    private EditText search;
    private Button allBtn, todayBtn, weekBtn, monthBtn;
    private String filter = "ALL";
    private final List<Item> items = new ArrayList<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    private static final class Item {
        String saleId;
        String title;
        String message;
        long time;
        boolean read;
        int number;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refresh();
    }

    private void buildUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.BG);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 22), UiKit.dp(this, 18), UiKit.dp(this, 20));
        scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = UiKit.text(this, "Historial", 28, UiKit.TEXT, true);
        root.addView(title);
        TextView subtitle = UiKit.text(this, "Todas tus ventas, numeradas desde la primera.", 14, UiKit.MUTED, false);
        subtitle.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 14));
        root.addView(subtitle);

        count = UiKit.text(this, "0 ventas", 14, UiKit.ACCENT, true);
        count.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8));
        count.setBackground(UiKit.rounded(UiKit.ACCENT_SOFT, 20, this));
        root.addView(count, UiKit.fullWidth(this, 0, 12));

        search = new EditText(this);
        search.setHint("Buscar producto o número de orden");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(UiKit.TEXT);
        search.setHintTextColor(UiKit.MUTED);
        search.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 10));
        search.setBackground(UiKit.roundedStroke(Color.WHITE, 14, UiKit.BORDER, this));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(search, UiKit.fullWidth(this, 0, 10));

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        allBtn = filterButton("Todas", "ALL");
        todayBtn = filterButton("Hoy", "TODAY");
        weekBtn = filterButton("7 días", "WEEK");
        monthBtn = filterButton("Mes", "MONTH");
        addFilter(filters, allBtn, false);
        addFilter(filters, todayBtn, true);
        addFilter(filters, weekBtn, true);
        addFilter(filters, monthBtn, true);
        root.addView(filters, UiKit.fullWidth(this, 0, 16));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        shell.addView(UiKit.bottomNav(this, 1), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(shell);
        updateFilterButtons();
    }

    private Button filterButton(String label, String value) {
        Button b = UiKit.button(this, label);
        b.setTextSize(13);
        b.setMinHeight(UiKit.dp(this, 42));
        b.setOnClickListener(v -> {
            filter = value;
            updateFilterButtons();
            render();
        });
        return b;
    }

    private void addFilter(LinearLayout row, Button b, boolean margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (margin) p.setMargins(UiKit.dp(this, 6), 0, 0, 0);
        row.addView(b, p);
    }

    private void updateFilterButtons() {
        UiKit.setSelected(allBtn, this, "ALL".equals(filter));
        UiKit.setSelected(todayBtn, this, "TODAY".equals(filter));
        UiKit.setSelected(weekBtn, this, "WEEK".equals(filter));
        UiKit.setSelected(monthBtn, this, "MONTH".equals(filter));
    }

    private void refresh() {
        items.clear();
        try {
            JSONArray arr = new JSONArray(getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).getString("history", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item x = new Item();
                x.saleId = o.optString("saleId", "").trim();
                if (x.saleId.isEmpty()) continue;
                x.title = o.optString("title", "VENTA");
                x.message = o.optString("message", "");
                x.time = o.optLong("time", 0L);
                x.read = o.optBoolean("read", false);
                items.add(x);
            }
        } catch (Exception ignored) {}

        List<Item> oldest = new ArrayList<>(items);
        Collections.sort(oldest, Comparator.comparingLong((Item x) -> x.time).thenComparing(x -> x.saleId));
        Map<String, Integer> numbers = new HashMap<>();
        for (int i = 0; i < oldest.size(); i++) numbers.put(oldest.get(i).saleId, i + 1);
        for (Item x : items) x.number = numbers.containsKey(x.saleId) ? numbers.get(x.saleId) : 0;
        Collections.sort(items, (a, b) -> {
            int timeCompare = Long.compare(b.time, a.time);
            return timeCompare != 0 ? timeCompare : b.saleId.compareTo(a.saleId);
        });
        render();
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Item> visible = new ArrayList<>();
        for (Item x : items) {
            if (!matchesDate(x.time)) continue;
            String haystack = (x.saleId + " " + x.title + " " + x.message).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !haystack.contains(q)) continue;
            visible.add(x);
        }

        count.setText(visible.size() + (visible.size() == 1 ? " venta" : " ventas") + " · total " + items.size());
        if (visible.isEmpty()) {
            LinearLayout empty = UiKit.card(this);
            TextView t = UiKit.text(this, "No encontramos ventas con ese filtro.", 15, UiKit.MUTED, false);
            t.setGravity(Gravity.CENTER);
            empty.addView(t);
            list.addView(empty, UiKit.fullWidth(this, 4, 0));
            return;
        }

        String lastGroup = "";
        for (Item x : visible) {
            String group = dayKey(x.time);
            if (!group.equals(lastGroup)) {
                TextView section = UiKit.text(this, dayLabel(x.time), 13, UiKit.MUTED, true);
                section.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 8), 0, UiKit.dp(this, 7));
                list.addView(section);
                lastGroup = group;
            }
            list.addView(saleCard(x), UiKit.fullWidth(this, 0, 10));
        }
    }

    private View saleCard(Item x) {
        LinearLayout card = UiKit.card(this);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String when = x.time > 0 ? new SimpleDateFormat("dd/MM · HH:mm", Locale.getDefault()).format(new Date(x.time * 1000L)) : "sin fecha";
        TextView number = UiKit.text(this, "#" + x.number + "   " + when, 14, UiKit.ACCENT, true);
        top.addView(number, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(statusPill(x));
        card.addView(top);

        String product = valueFor(x.message, "Producto:");
        if (product.isEmpty()) product = firstUsefulLine(x.message);
        if (product.isEmpty()) product = "Venta Mercado Libre";
        TextView productView = UiKit.text(this, product, 17, UiKit.TEXT, true);
        productView.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 8));
        card.addView(productView);

        String sale = valueFor(x.message, "Venta:");
        String profit = valueFor(x.message, "Ganancia:");
        String qty = valueFor(x.message, "Cantidad:");
        StringBuilder summary = new StringBuilder();
        if (!sale.isEmpty()) summary.append("Venta: ").append(sale);
        if (!profit.isEmpty()) {
            if (summary.length() > 0) summary.append("   ·   ");
            summary.append("Ganancia: ").append(profit);
        }
        if (!qty.isEmpty()) {
            if (summary.length() > 0) summary.append("\n");
            summary.append("Cantidad: ").append(qty);
        }
        if (summary.length() > 0) {
            TextView s = UiKit.text(this, summary.toString(), 14, UiKit.TEXT, false);
            card.addView(s);
        }

        TextView order = UiKit.text(this, "Orden " + x.saleId, 13, UiKit.MUTED, false);
        order.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(order);

        TextView details = UiKit.text(this, x.message, 13, UiKit.MUTED, false);
        details.setPadding(0, UiKit.dp(this, 12), 0, 0);
        details.setTextIsSelectable(true);
        details.setVisibility(View.GONE);
        card.addView(details);

        TextView hint = UiKit.text(this, "Tocá para ver detalle", 12, UiKit.ACCENT, true);
        hint.setPadding(0, UiKit.dp(this, 9), 0, 0);
        card.addView(hint);
        card.setOnClickListener(v -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            hint.setText(show ? "Tocá para cerrar detalle" : "Tocá para ver detalle");
        });
        return card;
    }

    private TextView statusPill(Item x) {
        String upper = x.message == null ? "" : x.message.toUpperCase(Locale.ROOT);
        if (upper.contains("ENTREGADO")) return UiKit.pill(this, "ENTREGADA", UiKit.GREEN, UiKit.GREEN_SOFT);
        if (!x.read) return UiKit.pill(this, "NUEVA", UiKit.ACCENT, UiKit.ACCENT_SOFT);
        return UiKit.pill(this, "VISTA", UiKit.MUTED, Color.rgb(241, 245, 249));
    }

    private boolean matchesDate(long seconds) {
        if ("ALL".equals(filter)) return true;
        if (seconds <= 0) return false;
        Calendar now = Calendar.getInstance();
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(seconds * 1000L);
        if ("TODAY".equals(filter)) return sameDay(now, d);
        if ("MONTH".equals(filter)) return now.get(Calendar.YEAR) == d.get(Calendar.YEAR) && now.get(Calendar.MONTH) == d.get(Calendar.MONTH);
        if ("WEEK".equals(filter)) return System.currentTimeMillis() - seconds * 1000L <= 7L * 24L * 60L * 60L * 1000L;
        return true;
    }

    private boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private String dayKey(long seconds) {
        if (seconds <= 0) return "0";
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(seconds * 1000L));
    }

    private String dayLabel(long seconds) {
        if (seconds <= 0) return "SIN FECHA";
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(seconds * 1000L);
        Calendar now = Calendar.getInstance();
        if (sameDay(now, d)) return "HOY";
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(yesterday, d)) return "AYER";
        return new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(new Date(seconds * 1000L)).toUpperCase(Locale.getDefault());
    }

    private String valueFor(String message, String prefix) {
        if (message == null) return "";
        for (String line : message.split("\\r?\\n")) {
            String t = line.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) return t.substring(prefix.length()).trim();
        }
        return "";
    }

    private String firstUsefulLine(String message) {
        if (message == null) return "";
        for (String line : message.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (low.startsWith("venta:") || low.startsWith("ganancia:") || low.startsWith("cantidad:") || low.startsWith("orden:")) continue;
            return t;
        }
        return "";
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter("com.mlcentral.ventas.SALE_RECEIVED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, f);
    }

    @Override protected void onStop() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }
}
