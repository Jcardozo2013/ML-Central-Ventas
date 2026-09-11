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

    private void buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 22), UiKit.dp(this, 18), UiKit.dp(this, 18));
        outer.setBackgroundColor(UiKit.BG);

        title = UiKit.text(this, "Ventas nuevas", 28, UiKit.TEXT, true);
        outer.addView(title);

        TextView subtitle = UiKit.text(this, "Revisá lo pendiente y confirmalo una sola vez para todos tus celulares.", 14, UiKit.MUTED, false);
        subtitle.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 14));
        outer.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout card = UiKit.card(this);
        sales = UiKit.text(this, "", 15, UiKit.TEXT, false);
        sales.setTextIsSelectable(true);
        card.addView(sales);
        scroll.addView(card);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        outer.addView(scroll, sp);

        markRead = UiKit.button(this, "Marcar vistas en todos los celulares");
        markRead.setTextColor(Color.WHITE);
        markRead.setTypeface(null, android.graphics.Typeface.BOLD);
        markRead.setBackground(UiKit.rounded(UiKit.ACCENT, 14, this));
        markRead.setOnClickListener(v -> confirmCurrentSales());
        outer.addView(markRead, UiKit.fullWidth(this, 14, 0));

        Button back = UiKit.button(this, "Volver al inicio");
        back.setOnClickListener(v -> finish());
        outer.addView(back, UiKit.fullWidth(this, 8, 0));

        setContentView(outer);
    }

    private void refresh() {
        int n = SaleStore.unread(this);
        title.setText(n == 1 ? "1 venta nueva" : n + " ventas nuevas");
        sales.setText(SaleStore.unreadHistoryText(this));
        markRead.setEnabled(n > 0);
        markRead.setAlpha(n > 0 ? 1f : 0.45f);
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
        Toast.makeText(this, "Ventas confirmadas y sincronizadas con los otros celulares.", Toast.LENGTH_LONG).show();
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
