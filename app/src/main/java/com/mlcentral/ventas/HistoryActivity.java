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
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormatSymbols;
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
    private static final int PAGE_SIZE = 40;
    private static final long SEARCH_DEBOUNCE_MS = 220L;
    private static final long REFRESH_DEBOUNCE_MS = 350L;

    private LinearLayout list;
    private TextView count;
    private TextView monthlySold;
    private TextView monthlyProfit;
    private TextView monthlyCount;
    private TextView monthlyHint;
    private EditText search;
    private Button allBtn, todayBtn, weekBtn, pickedMonthBtn;
    private Button monthPickerBtn;
    private String filter = "ALL";
    private int selectedYear;
    private int selectedMonth;
    private int visibleLimit = PAGE_SIZE;
    private final List<Item> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable searchRender = () -> {
        visibleLimit = PAGE_SIZE;
        render();
    };
    private final Runnable delayedRefresh = this::refresh;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            scheduleRefresh();
        }
    };

    private static final class Item {
        String saleId;
        String title;
        String message;
        long time;
        boolean read;
        int number;

        String searchText;
        String product;
        String sale;
        String profit;
        String qty;
        String dayKey;
        String dayLabel;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Calendar now = Calendar.getInstance();
        selectedYear = now.get(Calendar.YEAR);
        selectedMonth = now.get(Calendar.MONTH);
        buildUi();
    }

    private void buildUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 22), UiKit.dp(this, 18), UiKit.dp(this, 24));
        scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView eyebrow = UiKit.text(this, "VENTAS", 12, UiKit.ACCENT, true);
        eyebrow.setLetterSpacing(0.08f);
        root.addView(eyebrow);

        TextView title = UiKit.text(this, "Historial", 30, UiKit.TEXT, true);
        title.setPadding(0, UiKit.dp(this, 3), 0, 0);
        root.addView(title);
        TextView subtitle = UiKit.text(this, "Buscá una venta o revisá cuánto vendiste en cualquier mes.", 14, UiKit.MUTED, false);
        subtitle.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 16));
        root.addView(subtitle);

        root.addView(buildMonthlyCard(), UiKit.fullWidth(this, 0, 16));

        search = new EditText(this);
        search.setHint("Buscar producto o número de orden");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(UiKit.TEXT);
        search.setHintTextColor(UiKit.MUTED);
        search.setPadding(UiKit.dp(this, 15), UiKit.dp(this, 11), UiKit.dp(this, 15), UiKit.dp(this, 11));
        search.setBackground(UiKit.roundedStroke(Color.WHITE, 16, UiKit.BORDER, this));
        search.setElevation(UiKit.dp(this, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(searchRender);
                handler.postDelayed(searchRender, SEARCH_DEBOUNCE_MS);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(search, UiKit.fullWidth(this, 0, 10));

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        allBtn = filterButton("Todas", "ALL");
        todayBtn = filterButton("Hoy", "TODAY");
        weekBtn = filterButton("7 días", "WEEK");
        pickedMonthBtn = filterButton("Mes elegido", "PICKED_MONTH");
        addFilter(filters, allBtn, false);
        addFilter(filters, todayBtn, true);
        addFilter(filters, weekBtn, true);
        addFilter(filters, pickedMonthBtn, true);
        root.addView(filters, UiKit.fullWidth(this, 0, 12));

        count = UiKit.text(this, "0 ventas", 13, UiKit.MUTED, true);
        count.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 2), 0, UiKit.dp(this, 8));
        root.addView(count);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        shell.addView(UiKit.bottomNav(this, 2),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(shell);
        updateFilterButtons();
        updateMonthlySummary();
    }

    private View buildMonthlyCard() {
        LinearLayout card = UiKit.card(this);

        TextView label = UiKit.text(this, "RESUMEN MENSUAL", 12, UiKit.ACCENT, true);
        label.setLetterSpacing(0.08f);
        card.addView(label);

        LinearLayout selector = new LinearLayout(this);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setGravity(Gravity.CENTER_VERTICAL);

        Button previous = UiKit.button(this, "‹");
        previous.setTextSize(24);
        previous.setOnClickListener(v -> shiftMonth(-1));
        selector.addView(previous, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 46)));

        monthPickerBtn = UiKit.button(this, "");
        monthPickerBtn.setTextSize(16);
        monthPickerBtn.setTextColor(UiKit.ACCENT);
        monthPickerBtn.setOnClickListener(v -> showMonthPicker());
        LinearLayout.LayoutParams center = new LinearLayout.LayoutParams(0, UiKit.dp(this, 46), 1f);
        center.setMargins(UiKit.dp(this, 8), 0, UiKit.dp(this, 8), 0);
        selector.addView(monthPickerBtn, center);

        Button next = UiKit.button(this, "›");
        next.setTextSize(24);
        next.setOnClickListener(v -> shiftMonth(1));
        selector.addView(next, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 46)));

        card.addView(selector, UiKit.fullWidth(this, 10, 12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout soldCard = smallMetricCard("TOTAL VENDIDO");
        monthlySold = UiKit.text(this, "$0,00", 22, UiKit.TEXT, true);
        monthlySold.setPadding(0, UiKit.dp(this, 5), 0, 0);
        soldCard.addView(monthlySold);
        row.addView(soldCard, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout profitCard = smallMetricCard("GANANCIA");
        monthlyProfit = UiKit.text(this, "$0,00", 22, UiKit.GREEN, true);
        monthlyProfit.setPadding(0, UiKit.dp(this, 5), 0, 0);
        profitCard.addView(monthlyProfit);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p2.setMargins(UiKit.dp(this, 9), 0, 0, 0);
        row.addView(profitCard, p2);

        card.addView(row);

        monthlyCount = UiKit.text(this, "0 ventas", 13, UiKit.ACCENT, true);
        monthlyCount.setPadding(0, UiKit.dp(this, 11), 0, 0);
        card.addView(monthlyCount);

        monthlyHint = UiKit.text(this, "El resumen excluye cancelaciones cuando Windows ya sincronizó los estados.", 12, UiKit.MUTED, false);
        monthlyHint.setPadding(0, UiKit.dp(this, 5), 0, 0);
        card.addView(monthlyHint);

        Button searchMonth = UiKit.primaryButton(this, "Buscar otro mes");
        searchMonth.setOnClickListener(v -> showMonthPicker());
        card.addView(searchMonth, UiKit.fullWidth(this, 12, 0));

        return card;
    }

    private LinearLayout smallMetricCard(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 12), UiKit.dp(this, 13), UiKit.dp(this, 12));
        box.setBackground(UiKit.rounded(UiKit.BG, 16, this));
        TextView l = UiKit.text(this, label, 11, UiKit.MUTED, true);
        l.setLetterSpacing(0.05f);
        box.addView(l);
        return box;
    }

    private Button filterButton(String label, String value) {
        Button b = UiKit.button(this, label);
        b.setTextSize(12);
        b.setMinHeight(UiKit.dp(this, 40));
        b.setOnClickListener(v -> {
            filter = value;
            visibleLimit = PAGE_SIZE;
            updateFilterButtons();
            render();
        });
        return b;
    }

    private void addFilter(LinearLayout row, Button b, boolean margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (margin) p.setMargins(UiKit.dp(this, 5), 0, 0, 0);
        row.addView(b, p);
    }

    private void updateFilterButtons() {
        UiKit.setSelected(allBtn, this, "ALL".equals(filter));
        UiKit.setSelected(todayBtn, this, "TODAY".equals(filter));
        UiKit.setSelected(weekBtn, this, "WEEK".equals(filter));
        UiKit.setSelected(pickedMonthBtn, this, "PICKED_MONTH".equals(filter));
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(delayedRefresh);
        handler.postDelayed(delayedRefresh, REFRESH_DEBOUNCE_MS);
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
                prepareItem(x);
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

        updateMonthlySummary();
        render();
    }

    private void prepareItem(Item x) {
        x.product = valueFor(x.message, "Producto:");
        if (x.product.isEmpty()) x.product = firstUsefulLine(x.message);
        if (x.product.isEmpty()) x.product = "Venta Mercado Libre";
        x.sale = valueFor(x.message, "Venta:");
        x.profit = valueFor(x.message, "Ganancia:");
        x.qty = valueFor(x.message, "Cantidad:");
        x.searchText = (x.saleId + " " + x.title + " " + x.product + " " + x.message).toLowerCase(Locale.ROOT);
        x.dayKey = dayKey(x.time);
        x.dayLabel = dayLabel(x.time);
    }

    private void updateMonthlySummary() {
        if (monthPickerBtn == null) return;
        MonthlyStats.Stats stats = MonthlyStats.get(this, selectedYear, selectedMonth);
        monthPickerBtn.setText(MonthlyStats.monthLabel(selectedYear, selectedMonth) + "  ▾");
        monthlySold.setText(SaleStore.formatMoney(stats.soldTotal));
        monthlyProfit.setText(SaleStore.formatMoney(stats.profitTotal));
        monthlyCount.setText(stats.salesCount + (stats.salesCount == 1 ? " venta" : " ventas"));

        if (stats.salesCount == 0) {
            monthlyHint.setText("No hay ventas registradas para este mes.");
            monthlyHint.setTextColor(UiKit.MUTED);
            return;
        }

        ArrayList<String> warnings = new ArrayList<>();
        if (stats.missingSaleAmount > 0) {
            warnings.add(stats.missingSaleAmount + (stats.missingSaleAmount == 1
                    ? " venta sin importe histórico" : " ventas sin importe histórico"));
        }
        if (stats.missingProfit > 0) {
            warnings.add(stats.missingProfit + (stats.missingProfit == 1
                    ? " venta sin ganancia confirmada" : " ventas sin ganancia confirmada"));
        }

        if (warnings.isEmpty()) {
            monthlyHint.setText(StateStore.lastSyncAt(this) > 0L
                    ? "Ventas válidas según Windows · canceladas no suman."
                    : "Mes provisional hasta sincronizar estados con Windows.");
            monthlyHint.setTextColor(UiKit.GREEN);
        } else {
            monthlyHint.setText("⚠ " + join(warnings, " · "));
            monthlyHint.setTextColor(UiKit.ORANGE);
        }
    }

    private String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    private void shiftMonth(int amount) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(selectedYear, selectedMonth, 1);
        c.add(Calendar.MONTH, amount);
        selectedYear = c.get(Calendar.YEAR);
        selectedMonth = c.get(Calendar.MONTH);
        filter = "PICKED_MONTH";
        visibleLimit = PAGE_SIZE;
        updateFilterButtons();
        updateMonthlySummary();
        render();
    }

    private void showMonthPicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 8), UiKit.dp(this, 18), 0);

        NumberPicker month = new NumberPicker(this);
        month.setMinValue(1);
        month.setMaxValue(12);
        month.setDisplayedValues(monthNames());
        month.setValue(selectedMonth + 1);
        month.setWrapSelectorWheel(true);

        NumberPicker year = new NumberPicker(this);
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        int earliest = Math.min(MonthlyStats.earliestYear(this), currentYear);
        int minYear = Math.min(Math.max(2000, earliest - 1), selectedYear);
        int maxYear = Math.max(currentYear + 1, selectedYear);
        year.setMinValue(minYear);
        year.setMaxValue(maxYear);
        year.setValue(selectedYear);
        year.setWrapSelectorWheel(false);

        box.addView(month, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(year, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        new AlertDialog.Builder(this)
                .setTitle("Buscar mes")
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ver mes", (dialog, which) -> {
                    selectedMonth = month.getValue() - 1;
                    selectedYear = year.getValue();
                    filter = "PICKED_MONTH";
                    visibleLimit = PAGE_SIZE;
                    updateFilterButtons();
                    updateMonthlySummary();
                    render();
                })
                .show();
    }

    private String[] monthNames() {
        String[] source = new DateFormatSymbols(Locale.getDefault()).getMonths();
        String[] out = new String[12];
        for (int i = 0; i < 12; i++) {
            String name = source[i] == null ? "" : source[i].trim();
            if (name.isEmpty()) name = String.format(Locale.getDefault(), "%02d", i + 1);
            else name = name.substring(0, 1).toUpperCase(Locale.getDefault()) + name.substring(1);
            out[i] = name;
        }
        return out;
    }

    private void render() {
        if (list == null || count == null) return;
        list.removeAllViews();
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Item> visible = new ArrayList<>();
        for (Item x : items) {
            if (!matchesDate(x.time)) continue;
            if (!q.isEmpty() && (x.searchText == null || !x.searchText.contains(q))) continue;
            visible.add(x);
        }

        String scope = "PICKED_MONTH".equals(filter)
                ? " · " + MonthlyStats.monthLabel(selectedYear, selectedMonth)
                : "";
        count.setText(visible.size() + (visible.size() == 1 ? " venta" : " ventas") + scope + " · historial " + items.size());

        if (visible.isEmpty()) {
            LinearLayout empty = UiKit.card(this);
            TextView t = UiKit.text(this, "No encontramos ventas con ese filtro.", 15, UiKit.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 12));
            empty.addView(t);
            list.addView(empty, UiKit.fullWidth(this, 4, 0));
            return;
        }

        int limit = Math.min(visibleLimit, visible.size());
        String lastGroup = "";
        for (int i = 0; i < limit; i++) {
            Item x = visible.get(i);
            String group = x.dayKey;
            if (!group.equals(lastGroup)) {
                TextView section = UiKit.text(this, x.dayLabel, 12, UiKit.MUTED, true);
                section.setLetterSpacing(0.06f);
                section.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 10), 0, UiKit.dp(this, 7));
                list.addView(section);
                lastGroup = group;
            }
            list.addView(saleCard(x), UiKit.fullWidth(this, 0, 10));
        }

        if (limit < visible.size()) {
            int remaining = visible.size() - limit;
            int next = Math.min(PAGE_SIZE, remaining);
            Button more = UiKit.button(this, "Mostrar " + next + " más · quedan " + remaining);
            more.setOnClickListener(v -> {
                visibleLimit += PAGE_SIZE;
                render();
            });
            list.addView(more, UiKit.fullWidth(this, 4, 10));
        }
    }

    private View saleCard(Item x) {
        LinearLayout card = UiKit.card(this);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String when = x.time > 0
                ? new SimpleDateFormat("dd/MM · HH:mm", Locale.getDefault()).format(new Date(x.time * 1000L))
                : "sin fecha";
        TextView number = UiKit.text(this, "#" + x.number + "   " + when, 13, UiKit.ACCENT, true);
        top.addView(number, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(statusPill(x));
        card.addView(top);

        TextView productView = UiKit.text(this, x.product, 17, UiKit.TEXT, true);
        productView.setPadding(0, UiKit.dp(this, 11), 0, UiKit.dp(this, 9));
        card.addView(productView);

        LinearLayout numbers = new LinearLayout(this);
        numbers.setOrientation(LinearLayout.HORIZONTAL);

        if (!x.sale.isEmpty()) {
            LinearLayout sold = miniValue("VENTA", x.sale, UiKit.TEXT);
            numbers.addView(sold, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (!x.profit.isEmpty()) {
            LinearLayout profit = miniValue("GANANCIA", x.profit, UiKit.GREEN);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (numbers.getChildCount() > 0) pp.setMargins(UiKit.dp(this, 8), 0, 0, 0);
            numbers.addView(profit, pp);
        }
        if (numbers.getChildCount() > 0) card.addView(numbers);

        StringBuilder secondary = new StringBuilder();
        if (!x.qty.isEmpty()) secondary.append("Cantidad: ").append(x.qty);
        if (secondary.length() > 0) {
            TextView s = UiKit.text(this, secondary.toString(), 13, UiKit.MUTED, false);
            s.setPadding(0, UiKit.dp(this, 9), 0, 0);
            card.addView(s);
        }

        TextView order = UiKit.text(this, "Orden " + x.saleId, 12, UiKit.MUTED, false);
        order.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(order);

        TextView details = UiKit.text(this, x.message, 13, UiKit.MUTED, false);
        details.setPadding(0, UiKit.dp(this, 12), 0, 0);
        details.setTextIsSelectable(true);
        details.setVisibility(View.GONE);
        card.addView(details);

        TextView hint = UiKit.text(this, "Ver detalle  ›", 12, UiKit.ACCENT, true);
        hint.setPadding(0, UiKit.dp(this, 10), 0, 0);
        card.addView(hint);
        card.setOnClickListener(v -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            hint.setText(show ? "Cerrar detalle  ⌃" : "Ver detalle  ›");
        });
        return card;
    }

    private LinearLayout miniValue(String label, String value, int valueColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 9), UiKit.dp(this, 11), UiKit.dp(this, 9));
        box.setBackground(UiKit.rounded(UiKit.BG, 14, this));
        TextView l = UiKit.text(this, label, 10, UiKit.MUTED, true);
        l.setLetterSpacing(0.06f);
        box.addView(l);
        TextView v = UiKit.text(this, value, 15, valueColor, true);
        v.setPadding(0, UiKit.dp(this, 3), 0, 0);
        box.addView(v);
        return box;
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
        if ("PICKED_MONTH".equals(filter)) {
            return selectedYear == d.get(Calendar.YEAR) && selectedMonth == d.get(Calendar.MONTH);
        }
        if ("WEEK".equals(filter)) {
            return System.currentTimeMillis() - seconds * 1000L <= 7L * 24L * 60L * 60L * 1000L;
        }
        return true;
    }

    private boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
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
        return new SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(new Date(seconds * 1000L))
                .toUpperCase(Locale.getDefault());
    }

    private String valueFor(String message, String prefix) {
        if (message == null) return "";
        String wanted = prefix.toLowerCase(Locale.ROOT);
        for (String line : message.split("\\r?\\n")) {
            String t = line.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith(wanted)) return t.substring(prefix.length()).trim();
        }
        return "";
    }

    private String firstUsefulLine(String message) {
        if (message == null) return "";
        for (String line : message.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (low.startsWith("venta:") || low.startsWith("ganancia:")
                    || low.startsWith("cantidad:") || low.startsWith("orden:")) continue;
            return t;
        }
        return "";
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter("com.mlcentral.ventas.SALE_RECEIVED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(delayedRefresh);
        handler.removeCallbacks(searchRender);
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        visibleLimit = PAGE_SIZE;
        refresh();
    }
}
