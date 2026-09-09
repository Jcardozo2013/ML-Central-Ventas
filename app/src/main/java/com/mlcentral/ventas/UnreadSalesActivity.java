package com.mlcentral.ventas;

import android.app.Activity;
import android.app.NotificationManager;
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
import android.widget.Toast;

import java.util.List;

public class UnreadSalesActivity extends Activity {
    private TextView title;
    private TextView sales;
    private Button markRead;

    private final BroadcastReceiver saleReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refresh();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(18), dp(20), dp(18), dp(18));
        outer.setBackgroundColor(Color.rgb(248, 249, 250));

        title = new TextView(this);
        title.setTextSize(25);
        title.setTextColor(Color.rgb(25, 25, 25));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        outer.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Acá aparecen únicamente las ventas que todavía no confirmaste.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(5), 0, dp(12));
        outer.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        sales = new TextView(this);
        sales.setTextSize(16);
        sales.setTextColor(Color.rgb(40, 40, 40));
        sales.setTextIsSelectable(true);
        sales.setPadding(dp(4), dp(6), dp(4), dp(12));
        scroll.addView(sales);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        markRead = new Button(this);
        markRead.setText("Marcar vistas en todos los celulares");
        markRead.setAllCaps(false);
        markRead.setOnClickListener(v -> confirmCurrentSales());
        outer.addView(markRead, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button back = new Button(this);
        back.setText("Volver");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, dp(8), 0, 0);
        outer.addView(back, bp);

        setContentView(outer);
    }

    private void refresh() {
        int n = SaleStore.unread(this);
        title.setText(n == 1 ? "1 venta nueva" : n + " ventas nuevas");
        sales.setText(SaleStore.unreadHistoryText(this));
        markRead.setEnabled(n > 0);
    }

    private void confirmCurrentSales() {
        List<String> ids = SaleStore.unreadIds(this);
        if (ids.isEmpty()) {
            refresh();
            return;
        }
        SaleStore.markRead(this, ids);
        cancelNotifications(ids);
        ReadSync.acknowledgeAsync(this, ids);
        sendBroadcast(new Intent("com.mlcentral.ventas.SALE_RECEIVED").setPackage(getPackageName()));
        Toast.makeText(this, "Ventas confirmadas. Se sincronizarán con los otros celulares.", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void cancelNotifications(List<String> ids) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            nm.cancel(1000 + Math.abs(id.hashCode() % 900000));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter("com.mlcentral.ventas.SALE_RECEIVED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(saleReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(saleReceiver, f);
    }

    @Override protected void onStop() {
        try { unregisterReceiver(saleReceiver); } catch (Exception ignored) {}
        super.onStop();
    }
}
