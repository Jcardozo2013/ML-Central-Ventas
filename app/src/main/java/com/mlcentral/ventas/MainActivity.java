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

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String s, float size, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }
    private TextView statCard(String label) { TextView t = text(label, 18, Color.rgb(25, 25, 25)); t.setPadding(dp(14), dp(12), dp(14), dp(12)); t.setBackgroundColor(Color.rgb(238, 240, 242)); return t; }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(248, 249, 250));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = text("ML Central Ventas", 28, Color.rgb(25, 25, 25));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button settings = new Button(this);
        settings.setText("⚙️");
        settings.setTextSize(22);
        settings.setAllCaps(false);
        settings.setContentDescription("Configuración");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings, new LinearLayout.LayoutParams(dp(58), dp(52)));
        root.addView(header);

        TextView subtitle = text("Ventas, ganancias y entregas sincronizadas con ML Central", 15, Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button todayTab = new Button(this); todayTab.setText("HOY"); todayTab.setEnabled(false);
        Button historyTab = new Button(this); historyTab.setText("HISTORIAL"); historyTab.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        tabs.addView(todayTab, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(historyTab, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs);

        status = text("● Conectando…", 16, Color.rgb(180, 90, 0));
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setPadding(0, dp(12), 0, dp(2));
        root.addView(status);

        historyStatus = text("Historial: esperando sincronización con Windows…", 14, Color.DKGRAY);
        historyStatus.setPadding(0, dp(2), 0, dp(6));
        root.addView(historyStatus);

        unread = new Button(this);
        unread.setText("0 ventas nuevas"); unread.setTextSize(18); unread.setAllCaps(false);
        unread.setOnClickListener(v -> { if (SaleStore.unread(this) > 0) startActivity(new Intent(this, UnreadSalesActivity.class)); });
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        up.setMargins(0, dp(8), 0, dp(12));
        root.addView(unread, up);

        todaySales = statCard("Ventas hoy: 0"); root.addView(todaySales);
        todayProfit = statCard("Ganancia hoy: $0,00");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); cp.setMargins(0, dp(8), 0, 0); root.addView(todayProfit, cp);
        monthProfit = statCard("Ganancia del mes: $0,00");
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); mp.setMargins(0, dp(8), 0, dp(16)); root.addView(monthProfit, mp);

        TextView h = text("Ventas de hoy", 21, Color.rgb(30, 30, 30));
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        h.setPadding(0, dp(4), 0, dp(8));
        root.addView(h);
        todayHistory = text("Todavía no hay ventas recibidas hoy.", 15, Color.rgb(45, 45, 45));
        todayHistory.setTextIsSelectable(true);
        root.addView(todayHistory);

        setContentView(scroll);
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 500);
        }
    }

    private void startListener() {
        Intent i = new Intent(this, SaleListenerService.class);
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i); } catch (Exception ignored) {}
    }

    private String pendingSuffix(int n) { if (n <= 0) return ""; return n == 1 ? " · 1 pendiente" : " · " + n + " pendientes"; }

    private void refresh() {
        int n = SaleStore.unread(this);
        unread.setText(n == 1 ? "1 venta nueva · VER" : n + " ventas nuevas · VER");
        unread.setEnabled(n > 0);
        boolean c = SaleStore.connected(this);
        long at = SaleStore.connectedAt(this);
        String time = at > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(at)) : "";
        status.setText(c ? "● Conectado · " + time : "● Reconectando…");
        status.setTextColor(c ? Color.rgb(27, 94, 32) : Color.rgb(180, 90, 0));
        historyStatus.setText(HistoryRestore.statusText(this));
        todaySales.setText("Ventas hoy: " + SaleStore.todaySaleCount(this));
        todayProfit.setText("Ganancia hoy: " + SaleStore.formatMoney(SaleStore.todayProfit(this)) + pendingSuffix(SaleStore.todayPendingProfitCount(this)));
        monthProfit.setText("Ganancia del mes: " + SaleStore.formatMoney(SaleStore.monthProfit(this)) + pendingSuffix(SaleStore.monthPendingProfitCount(this)));
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
