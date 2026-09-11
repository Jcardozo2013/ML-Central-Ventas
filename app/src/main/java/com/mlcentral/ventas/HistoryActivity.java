package com.mlcentral.ventas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class HistoryActivity extends Activity {
    private TextView history;
    private final BroadcastReceiver receiver = new BroadcastReceiver() { @Override public void onReceive(Context context, Intent intent) { refresh(); } };
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String s, float size, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20), dp(24), dp(20), dp(30)); root.setBackgroundColor(Color.rgb(248, 249, 250)); scroll.addView(root);
        TextView title = text("ML Central Ventas", 28, Color.rgb(25, 25, 25)); title.setTypeface(null, android.graphics.Typeface.BOLD); root.addView(title);
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button todayTab = new Button(this); todayTab.setText("HOY"); todayTab.setOnClickListener(v -> finish());
        Button historyTab = new Button(this); historyTab.setText("HISTORIAL"); historyTab.setEnabled(false);
        tabs.addView(todayTab, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); tabs.addView(historyTab, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams nav = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); nav.setMargins(0, dp(12), 0, dp(16)); root.addView(tabs, nav);
        TextView h = text("Historial completo", 22, Color.rgb(30, 30, 30)); h.setTypeface(null, android.graphics.Typeface.BOLD); h.setPadding(0, 0, 0, dp(8)); root.addView(h);
        history = text("Todavía no hay ventas recibidas en este celular.", 15, Color.rgb(45, 45, 45)); history.setTextIsSelectable(true); root.addView(history);
        setContentView(scroll); refresh();
    }
    private void refresh() { history.setText(SaleStore.historyText(this)); }
    @Override protected void onStart() { super.onStart(); IntentFilter f = new IntentFilter("com.mlcentral.ventas.SALE_RECEIVED"); if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, f); }
    @Override protected void onStop() { try { unregisterReceiver(receiver); } catch (Exception ignored) {} super.onStop(); }
    @Override protected void onResume() { super.onResume(); refresh(); }
}
