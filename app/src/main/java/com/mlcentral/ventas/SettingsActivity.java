package com.mlcentral.ventas;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    private final Handler handler = new Handler();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String s, float size, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }

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

        Button back = new Button(this);
        back.setText("←");
        back.setTextSize(22);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(58), dp(52)));

        TextView title = text("⚙️ Configuración", 27, Color.rgb(25, 25, 25));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(8), 0, 0, 0);
        header.addView(title, titleParams);
        root.addView(header);

        TextView subtitle = text("Conexión, pruebas y sonidos de ML Central Ventas", 15, Color.DKGRAY);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        TextView connectionLabel = text("CONEXIÓN", 13, Color.GRAY);
        connectionLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(connectionLabel);

        Button reconnect = new Button(this);
        reconnect.setText("Reconectar");
        reconnect.setAllCaps(false);
        reconnect.setOnClickListener(v -> reconnect());
        LinearLayout.LayoutParams reconnectParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        reconnectParams.setMargins(0, dp(6), 0, dp(18));
        root.addView(reconnect, reconnectParams);

        TextView salesLabel = text("VENTAS", 13, Color.GRAY);
        salesLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(salesLabel);

        Button testSale = new Button(this);
        testSale.setText("Probar aviso de venta");
        testSale.setAllCaps(false);
        testSale.setOnClickListener(v -> showSaleTest());
        LinearLayout.LayoutParams testSaleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testSaleParams.setMargins(0, dp(6), 0, 0);
        root.addView(testSale, testSaleParams);

        Button toneSale = new Button(this);
        toneSale.setText("🔊 Elegir sonido de ventas");
        toneSale.setAllCaps(false);
        toneSale.setOnClickListener(v -> openChannelSettings(SaleListenerService.SALES_CHANNEL));
        LinearLayout.LayoutParams toneSaleParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toneSaleParams.setMargins(0, dp(8), 0, dp(18));
        root.addView(toneSale, toneSaleParams);

        TextView deliveryLabel = text("ENTREGAS", 13, Color.GRAY);
        deliveryLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(deliveryLabel);

        Button testDelivery = new Button(this);
        testDelivery.setText("Probar aviso de entrega");
        testDelivery.setAllCaps(false);
        testDelivery.setOnClickListener(v -> showDeliveryTest());
        LinearLayout.LayoutParams testDeliveryParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testDeliveryParams.setMargins(0, dp(6), 0, 0);
        root.addView(testDelivery, testDeliveryParams);

        Button toneDelivery = new Button(this);
        toneDelivery.setText("📦 Elegir sonido de entregas");
        toneDelivery.setAllCaps(false);
        toneDelivery.setOnClickListener(v -> openChannelSettings(SaleListenerService.DELIVERY_CHANNEL));
        LinearLayout.LayoutParams toneDeliveryParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toneDeliveryParams.setMargins(0, dp(8), 0, dp(18));
        root.addView(toneDelivery, toneDeliveryParams);

        TextView note = text("Los sonidos de ventas y entregas se configuran por separado desde Android.", 13, Color.GRAY);
        root.addView(note);

        setContentView(scroll);
    }

    private void reconnect() {
        stopService(new Intent(this, SaleListenerService.class));
        handler.postDelayed(() -> {
            Intent i = new Intent(this, SaleListenerService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
                else startService(i);
            } catch (Exception ignored) {}
        }, 700L);
    }

    private void openChannelSettings(String channelId) {
        try {
            Intent i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            i.putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                fallback.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }

    private Notification.Builder testBuilder(String channelId) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);
    }

    private void showSaleTest() {
        Notification.Builder b = testBuilder(SaleListenerService.SALES_CHANNEL);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🛒 PRUEBA — NUEVA VENTA")
                .setContentText("Prueba del sonido elegido para ventas.")
                .setStyle(new Notification.BigTextStyle().bigText("Producto de prueba\nCantidad: 1\nVenta: $1.500\nEste es el canal de VENTAS."))
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_MAX).setDefaults(Notification.DEFAULT_ALL);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(999, b.build());
    }

    private void showDeliveryTest() {
        Notification.Builder b = testBuilder(SaleListenerService.DELIVERY_CHANNEL);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("📦 PRUEBA — PAQUETE ENTREGADO")
                .setContentText("Prueba del sonido elegido para entregas.")
                .setStyle(new Notification.BigTextStyle().bigText("Producto de prueba\nOrden: 20000...\nEstado: ✅ Entregado\nEste es el canal de ENTREGAS."))
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(998, b.build());
    }
}
