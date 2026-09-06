package com.mlcentral.ventas;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView status;
    private TextView unread;
    private TextView history;
    private final Handler handler = new Handler();

    private final BroadcastReceiver saleReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNotificationsIfNeeded();
        startListener();
        refresh();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(248, 249, 250));
        scroll.addView(root);

        TextView title = text("ML Central Ventas", 28, Color.rgb(25, 25, 25));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text("Avisos directos de ventas de Mercado Libre", 15, Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        status = text("● Conectando…", 17, Color.rgb(180, 90, 0));
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(status);

        unread = text("0 ventas nuevas", 23, Color.rgb(27, 94, 32));
        unread.setTypeface(null, android.graphics.Typeface.BOLD);
        unread.setPadding(0, dp(14), 0, dp(14));
        root.addView(unread);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(actions);

        Button read = new Button(this);
        read.setText("Marcar vistas");
        read.setOnClickListener(v -> { SaleStore.markAllRead(this); refresh(); });
        actions.addView(read, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button restart = new Button(this);
        restart.setText("Reconectar");
        restart.setOnClickListener(v -> { stopService(new Intent(this, SaleListenerService.class)); handler.postDelayed(this::startListener, 700); });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rp.setMargins(dp(8), 0, 0, 0);
        actions.addView(restart, rp);

        Button test = new Button(this);
        test.setText("Probar aviso y sonido");
        test.setOnClickListener(v -> showLocalTest());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.setMargins(0, dp(10), 0, dp(14));
        root.addView(test, tp);

        TextView line = new TextView(this);
        line.setBackgroundColor(Color.LTGRAY);
        root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        TextView h = text("Historial", 20, Color.rgb(30, 30, 30));
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        h.setPadding(0, dp(16), 0, dp(8));
        root.addView(h);

        history = text("Todavía no hay ventas.", 15, Color.rgb(45, 45, 45));
        history.setTextIsSelectable(true);
        root.addView(history);

        TextView note = text("\nLa app queda escuchando en segundo plano. No usa WhatsApp y una misma orden no genera dos avisos.", 13, Color.GRAY);
        root.addView(note);

        setContentView(scroll);
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

    private void refresh() {
        int n = SaleStore.unread(this);
        unread.setText(n == 1 ? "1 venta nueva" : n + " ventas nuevas");
        boolean c = SaleStore.connected(this);
        long at = SaleStore.connectedAt(this);
        String time = at > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(at)) : "";
        status.setText(c ? "● Conectado · " + time : "● Reconectando…");
        status.setTextColor(c ? Color.rgb(27, 94, 32) : Color.rgb(180, 90, 0));
        history.setText(SaleStore.historyText(this));
    }

    private void showLocalTest() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, SaleListenerService.SALES_CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🛒 PRUEBA — NUEVA VENTA")
                .setContentText("Si escuchaste este sonido, los avisos están listos.")
                .setStyle(new Notification.BigTextStyle().bigText("Producto de prueba\nCantidad: 1\nVenta: $1.500\nLa notificación de ventas está funcionando."))
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_MAX).setDefaults(Notification.DEFAULT_ALL);
        nm.notify(999, b.build());
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

    @Override protected void onResume() { super.onResume(); refresh(); }
}
