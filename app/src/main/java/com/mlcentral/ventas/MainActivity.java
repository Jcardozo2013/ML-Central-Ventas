package com.mlcentral.ventas;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView status;
    private TextView historyStatus;
    private Button unread;
    private TextView todaySales;
    private TextView todayProfit;
    private TextView monthProfit;
    private TextView todayHistory;
    private final Handler handler = new Handler();

    private final Runnable historyRetry = new Runnable() {
        @Override public void run() {
            ReadSync.requestHistoryOnceAsync(MainActivity.this);
            refresh();
            handler.postDelayed(this, 120000L);
        }
    };

    private final BroadcastReceiver saleReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNotificationsIfNeeded();
        startListener();
        ReadSync.requestHistoryOnceAsync(this);
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

        TextView title = UiKit.text(this, "ML Central Ventas", 28, UiKit.TEXT, true);
        root.addView(title);
        TextView subtitle = UiKit.text(this, "Tu resumen de Mercado Libre en tiempo real", 14, UiKit.MUTED, false);
        subtitle.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 14));
        root.addView(subtitle);

        LinearLayout connectionCard = UiKit.card(this);
        LinearLayout connectionRow = new LinearLayout(this);
        connectionRow.setOrientation(LinearLayout.HORIZONTAL);
        connectionRow.setGravity(Gravity.CENTER_VERTICAL);
        status = UiKit.text(this, "● Conectando…", 15, UiKit.ORANGE, true);
        connectionRow.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView live = UiKit.pill(this, "EN VIVO", UiKit.GREEN, UiKit.GREEN_SOFT);
        connectionRow.addView(live);
        connectionCard.addView(connectionRow);
        historyStatus = UiKit.text(this, "Historial: esperando sincronización con Windows…", 13, UiKit.MUTED, false);
        historyStatus.setPadding(0, UiKit.dp(this, 8), 0, 0);
        connectionCard.addView(historyStatus);
        root.addView(connectionCard, UiKit.fullWidth(this, 0, 12));

        unread = UiKit.button(this, "0 ventas nuevas");
        unread.setTextSize(16);
        unread.setTypeface(null, android.graphics.Typeface.BOLD);
        unread.setOnClickListener(v -> {
            if (SaleStore.unread(this) > 0) startActivity(new Intent(this, UnreadSalesActivity.class));
        });
        root.addView(unread, UiKit.fullWidth(this, 0, 18));

        TextView section = UiKit.text(this, "Resumen", 20, UiKit.TEXT, true);
        root.addView(section, UiKit.fullWidth(this, 0, 10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout salesCard = metricCard("VENTAS HOY");
        todaySales = metricValue("0");
        salesCard.addView(todaySales);
        row.addView(salesCard, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout profitCard = metricCard("GANANCIA HOY");
        todayProfit = metricValue("$0,00");
        profitCard.addView(todayProfit);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        second.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        row.addView(profitCard, second);
        root.addView(row);

        LinearLayout monthCard = metricCard("GANANCIA DEL MES");
        monthProfit = metricValue("$0,00");
        monthCard.addView(monthProfit);
        root.addView(monthCard, UiKit.fullWidth(this, 10, 18));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView recentTitle = UiKit.text(this, "Ventas de hoy", 20, UiKit.TEXT, true);
        heading.addView(recentTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button seeAll = UiKit.button(this, "Ver historial");
        seeAll.setTextSize(13);
        seeAll.setMinHeight(UiKit.dp(this, 40));
        seeAll.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        heading.addView(seeAll, new LinearLayout.LayoutParams(UiKit.dp(this, 122), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(heading, UiKit.fullWidth(this, 0, 10));

        LinearLayout recentCard = UiKit.card(this);
        todayHistory = UiKit.text(this, "Todavía no hay ventas recibidas hoy.", 14, UiKit.TEXT, false);
        todayHistory.setTextIsSelectable(true);
        recentCard.addView(todayHistory);
        root.addView(recentCard);

        shell.addView(UiKit.bottomNav(this, 0), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(shell);
    }

    private LinearLayout metricCard(String label) {
        LinearLayout card = UiKit.card(this);
        TextView l = UiKit.text(this, label, 12, UiKit.MUTED, true);
        l.setLetterSpacing(0.05f);
        card.addView(l);
        return card;
    }

    private TextView metricValue(String value) {
        TextView t = UiKit.text(this, value, 23, UiKit.TEXT, true);
        t.setPadding(0, UiKit.dp(this, 7), 0, 0);
        return t;
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 500);
        }
    }

    private void startListener() {
        Intent i = new Intent(this, SaleListenerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
    }

    private String pendingSuffix(int n) {
        if (n <= 0) return "";
        return n == 1 ? "\n1 pendiente" : "\n" + n + " pendientes";
    }

    private void refresh() {
        int n = SaleStore.unread(this);
        unread.setText(n == 1 ? "1 venta nueva · VER" : n + " ventas nuevas" + (n > 0 ? " · VER" : ""));
        unread.setEnabled(n > 0);
        unread.setTextColor(n > 0 ? Color.WHITE : UiKit.MUTED);
        unread.setBackground(n > 0 ? UiKit.rounded(UiKit.ACCENT, 14, this) : UiKit.roundedStroke(Color.WHITE, 14, UiKit.BORDER, this));

        boolean c = SaleStore.connected(this);
        long at = SaleStore.connectedAt(this);
        String time = at > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(at)) : "";
        status.setText(c ? "● Conectado · " + time : "● Reconectando…");
        status.setTextColor(c ? UiKit.GREEN : UiKit.ORANGE);
        historyStatus.setText(HistoryRestore.statusText(this));
        todaySales.setText(String.valueOf(SaleStore.todaySaleCount(this)));
        todayProfit.setText(SaleStore.formatMoney(SaleStore.todayProfit(this)) + pendingSuffix(SaleStore.todayPendingProfitCount(this)));
        monthProfit.setText(SaleStore.formatMoney(SaleStore.monthProfit(this)) + pendingSuffix(SaleStore.monthPendingProfitCount(this)));
        todayHistory.setText(SaleStore.todayHistoryText(this));
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter("com.mlcentral.ventas.SALE_RECEIVED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(saleReceiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(saleReceiver, f);
        handler.removeCallbacks(historyRetry);
        handler.post(historyRetry);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(historyRetry);
        try { unregisterReceiver(saleReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        ReadSync.requestHistoryOnceAsync(this);
        refresh();
    }
}
