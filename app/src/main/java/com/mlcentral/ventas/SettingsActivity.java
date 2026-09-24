package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
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

        TextView title = UiKit.text(this, "Configuración", 28, UiKit.TEXT, true);
        root.addView(title);
        TextView subtitle = UiKit.text(this, "Conexión, pruebas y sonidos", 14, UiKit.MUTED, false);
        subtitle.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 16));
        root.addView(subtitle);

        root.addView(sectionTitle("CONEXIÓN"));
        LinearLayout connection = UiKit.card(this);
        SharedPreferences prefs = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        String mode = prefs.getString("connection_mode", "");
        String lastError = prefs.getString("connection_last_error", "");
        String connectionText;
        if ("firebase".equals(mode) || "live".equals(mode)) connectionText = "Conexión Firebase en vivo activa.";
        else if ("firebase-rest".equals(mode) || "backup".equals(mode)) connectionText = "Conectado por respaldo HTTPS. La app sigue recibiendo aunque el socket Firebase del teléfono sea inestable.";
        else connectionText = "Si el estado queda en reconectando, podés reiniciar el servicio sin cerrar la app.";
        TextView connText = UiKit.text(this, connectionText, 14, UiKit.MUTED, false);
        connection.addView(connText);
        if (lastError != null && !lastError.trim().isEmpty()) {
            TextView error = UiKit.text(this, "Último error: " + lastError, 12, UiKit.ORANGE, false);
            error.setTextIsSelectable(true);
            error.setPadding(0, UiKit.dp(this, 8), 0, 0);
            connection.addView(error);
        }
        Button reconnect = primaryButton("Reconectar ahora");
        reconnect.setOnClickListener(v -> reconnect());
        connection.addView(reconnect, UiKit.fullWidth(this, 12, 0));
        Button diagSync = UiKit.button(this, "Diagnóstico enlace con Windows");
        diagSync.setOnClickListener(v -> SyncDiagnostics.run(this));
        connection.addView(diagSync, UiKit.fullWidth(this, 8, 0));
        TextView diagHint = UiKit.text(this, "Envía una prueba PING por el canal de Estados y espera la respuesta de Windows. El resultado se puede copiar.", 12, UiKit.MUTED, false);
        diagHint.setPadding(0, UiKit.dp(this, 7), 0, 0);
        connection.addView(diagHint);

        TextView bgStatus = UiKit.text(this, backgroundStatusText(), 13, UiKit.TEXT, true);
        bgStatus.setPadding(0, UiKit.dp(this, 10), 0, 0);
        connection.addView(bgStatus);

        Button background = UiKit.button(this, "1. Permitir batería sin restricciones");
        background.setOnClickListener(v -> openBackgroundPowerSettings());
        connection.addView(background, UiKit.fullWidth(this, 10, 0));

        Button autostart = UiKit.button(this, "2. Abrir Inicio automático / Autostart");
        autostart.setOnClickListener(v -> openAutostartSettings());
        connection.addView(autostart, UiKit.fullWidth(this, 8, 0));

        Button exactAlarm = UiKit.button(this, "3. Permitir reinicio de respaldo");
        exactAlarm.setOnClickListener(v -> openExactAlarmSettings());
        connection.addView(exactAlarm, UiKit.fullWidth(this, 8, 0));

        TextView bgHint = UiKit.text(this, "Para Xiaomi/Redmi/POCO/HyperOS dejá ML Central en Sin restricciones y activá Inicio automático. El tercer permiso permite que el watchdog la vuelva a levantar si Android la mata.", 12, UiKit.MUTED, false);
        bgHint.setPadding(0, UiKit.dp(this, 7), 0, 0);
        connection.addView(bgHint);

        root.addView(connection, UiKit.fullWidth(this, 7, 18));

        root.addView(sectionTitle("VENTAS"));
        LinearLayout sales = UiKit.card(this);
        TextView salesText = UiKit.text(this, "Probá el aviso y elegí el sonido que querés usar cuando entre una venta nueva.", 14, UiKit.MUTED, false);
        sales.addView(salesText);
        Button testSale = UiKit.button(this, "Probar aviso de venta");
        testSale.setOnClickListener(v -> showSaleTest());
        sales.addView(testSale, UiKit.fullWidth(this, 12, 0));
        Button toneSale = UiKit.button(this, "Elegir sonido de ventas");
        toneSale.setOnClickListener(v -> openChannelSettings(SaleListenerService.SALES_CHANNEL));
        sales.addView(toneSale, UiKit.fullWidth(this, 8, 0));
        root.addView(sales, UiKit.fullWidth(this, 7, 18));

        root.addView(sectionTitle("ENTREGAS"));
        LinearLayout deliveries = UiKit.card(this);
        TextView deliveryText = UiKit.text(this, "El aviso de paquete entregado usa un canal separado para que pueda tener otro sonido.", 14, UiKit.MUTED, false);
        deliveries.addView(deliveryText);
        Button testDelivery = UiKit.button(this, "Probar aviso de entrega");
        testDelivery.setOnClickListener(v -> showDeliveryTest());
        deliveries.addView(testDelivery, UiKit.fullWidth(this, 12, 0));
        Button toneDelivery = UiKit.button(this, "Elegir sonido de entregas");
        toneDelivery.setOnClickListener(v -> openChannelSettings(SaleListenerService.DELIVERY_CHANNEL));
        deliveries.addView(toneDelivery, UiKit.fullWidth(this, 8, 0));
        root.addView(deliveries, UiKit.fullWidth(this, 7, 18));

        root.addView(sectionTitle("DIAGNÓSTICO"));
        LinearLayout diagnostics = UiKit.card(this);
        TextView diagnosticsText = UiKit.text(this,
                "Si la app se cierra o aparece «no responde», el gestor guarda automáticamente el motivo, stack, memoria y últimos eventos.",
                14, UiKit.MUTED, false);
        diagnostics.addView(diagnosticsText);
        Button errorManager = primaryButton("Abrir gestor de errores / cierres");
        errorManager.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticActivity.class)));
        diagnostics.addView(errorManager, UiKit.fullWidth(this, 12, 0));
        root.addView(diagnostics, UiKit.fullWidth(this, 7, 18));

        LinearLayout info = UiKit.card(this);
        TextView version = UiKit.text(this, "ML Central Ventas · v" + BuildConfig.VERSION_NAME, 14, UiKit.TEXT, true);
        info.addView(version);
        TextView note = UiKit.text(this, "Los sonidos se configuran desde Android y no afectan la sincronización con Windows.", 13, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 7), 0, 0);
        info.addView(note);
        root.addView(info);

        shell.addView(UiKit.bottomNav(this, 2), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(shell);
    }

    private TextView sectionTitle(String value) {
        TextView t = UiKit.text(this, value, 12, UiKit.MUTED, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private Button primaryButton(String label) {
        Button b = UiKit.button(this, label);
        b.setTextColor(Color.WHITE);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setBackground(UiKit.rounded(UiKit.ACCENT, 12, this));
        return b;
    }

    private void reconnect() {
        getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
                .putString("connection_mode", "reconnecting")
                .putString("connection_last_error", "")
                .apply();
        stopService(new Intent(this, SaleListenerService.class));
        handler.postDelayed(() -> {
            Intent i = new Intent(this, SaleListenerService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
                else startService(i);
            } catch (Exception ignored) {}
            recreate();
        }, 700L);
    }

    private String backgroundStatusText() {
        SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        long hb = p.getLong("background_heartbeat_v153", 0L);
        long age = hb <= 0L ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - hb);
        String pulse;
        if (age < 45000L) pulse = "ACTIVO · último pulso hace " + Math.max(0L, age / 1000L) + " s";
        else if (hb <= 0L) pulse = "SIN PULSO";
        else pulse = "DETENIDO/ATRASADO · último pulso hace " + Math.max(1L, age / 60000L) + " min";

        boolean unrestricted = false;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) unrestricted = true;
            else {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }
        } catch (Exception ignored) {}

        return "Servicio segundo plano: " + pulse + "\nBatería: "
                + (unrestricted ? "SIN RESTRICCIONES" : "Android todavía puede dormir la app");
    }

    private void openBackgroundPowerSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    return;
                }
            }
        } catch (Exception ignored) {}
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void openAutostartSettings() {
        String[][] targets = new String[][]{
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"}
        };
        for (String[] target : targets) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(target[0], target[1]));
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void openExactAlarmSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null && !am.canScheduleExactAlarms()) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    return;
                }
            }
        } catch (Exception ignored) {}
        reconnect();
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
                .setContentTitle("PRUEBA · NUEVA VENTA")
                .setContentText("Prueba del sonido elegido para ventas.")
                .setStyle(new Notification.BigTextStyle().bigText("Producto de prueba\nCantidad: 1\nVenta: $1.500\nEste es el canal de VENTAS."))
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_MAX).setDefaults(Notification.DEFAULT_ALL);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(999, b.build());
    }

    private void showDeliveryTest() {
        Notification.Builder b = testBuilder(SaleListenerService.DELIVERY_CHANNEL);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("PRUEBA · PAQUETE ENTREGADO")
                .setContentText("Prueba del sonido elegido para entregas.")
                .setStyle(new Notification.BigTextStyle().bigText("Producto de prueba\nOrden: 20000...\nEstado: Entregado\nEste es el canal de ENTREGAS."))
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(998, b.build());
    }
}
