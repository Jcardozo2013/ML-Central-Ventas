package com.mlcentral.ventas;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DiagnosticActivity extends Activity {
    private TextView reportView;
    private String lastReport = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshReport();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Gestor de errores y cierres");
        title.setTextSize(24);
        root.addView(title, full(0, 8));

        TextView help = new TextView(this);
        help.setText("Dejalo instalado. ML Central guarda automáticamente crashes, bloqueos tipo «no responde», cierres de Android, memoria, conexión y últimos eventos. Si vuelve a fallar, entrá acá y tocá «Copiar informe completo».");
        help.setTextSize(14);
        root.addView(help, full(0, 14));

        reportView = new TextView(this);
        reportView.setTextSize(12);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(reportView, full(0, 12));

        Button refresh = button("Actualizar informe");
        refresh.setOnClickListener(v -> refreshReport());
        root.addView(refresh, full(0, 8));

        Button copy = button("Copiar informe completo");
        copy.setOnClickListener(v -> copyReport());
        root.addView(copy, full(0, 8));

        Button clear = button("Borrar registros anteriores");
        clear.setOnClickListener(v -> {
            CrashManager.clear(this);
            Toast.makeText(this, "Registros anteriores borrados", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::refreshReport, 250L);
        });
        root.addView(clear, full(0, 8));

        Button normal = button("Volver a ML Central");
        normal.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        root.addView(normal, full(0, 8));

        setContentView(scroll);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(15);
        return b;
    }

    private LinearLayout.LayoutParams full(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(top), 0, dp(bottom));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refreshReport() {
        StringBuilder out = new StringBuilder();
        SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);

        out.append("ML CENTRAL VENTAS — GESTOR DE ERRORES\n");
        out.append("Versión APK: ").append(BuildConfig.VERSION_NAME).append('\n');
        out.append("Hora informe: ")
                .append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date()))
                .append('\n');
        out.append("Dispositivo: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Paquete: ").append(getPackageName()).append("\n\n");

        out.append("ESTADO ACTUAL\n");
        out.append("=============\n");
        out.append("Conectado: ").append(SaleStore.connected(this) ? "SÍ" : "NO").append('\n');
        out.append("Modo: ").append(p.getString("connection_mode", "—")).append('\n');
        out.append("Último error conexión: ").append(p.getString("connection_last_error", "—")).append('\n');
        out.append("SDK Firebase socket: ").append(p.getBoolean("firebase_sdk_connected_v159", false) ? "conectado" : "no conectado").append('\n');

        long restAt = p.getLong("firebase_rest_last_ok_at_v159", 0L);
        if (restAt > 0L) {
            out.append("Último REST OK: hace ").append(Math.max(0L, (System.currentTimeMillis() - restAt) / 1000L)).append(" s\n");
        } else out.append("Último REST OK: nunca\n");

        long hb = p.getLong("background_heartbeat_v153", 0L);
        if (hb > 0L) out.append("Pulso segundo plano: hace ").append(Math.max(0L, (System.currentTimeMillis() - hb) / 1000L)).append(" s\n");
        else out.append("Pulso segundo plano: sin datos\n");

        out.append("Estado segundo plano: ").append(p.getString("background_state_v153", "—")).append('\n');
        out.append("Estados PC: ").append(StateStore.statusText(this)).append('\n');
        out.append("Pendientes de cambio: ").append(StateSync.pendingCount(this)).append("\n\n");

        out.append("MEMORIA\n");
        out.append("=======\n");
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        out.append("Java usada: ").append(used / 1024 / 1024).append(" MB\n");
        out.append("Java reservada: ").append(rt.totalMemory() / 1024 / 1024).append(" MB\n");
        out.append("Java máxima: ").append(rt.maxMemory() / 1024 / 1024).append(" MB\n");
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            out.append("RAM disponible sistema: ").append(mi.availMem / 1024 / 1024).append(" MB\n");
            out.append("Sistema en memoria baja: ").append(mi.lowMemory ? "SÍ" : "NO").append("\n\n");
        } catch (Throwable ignored) {
            out.append("RAM sistema: no disponible\n\n");
        }

        appendFileSection(out, "ÚLTIMO CRASH GLOBAL", CrashManager.CRASH_FILE, 16000);
        appendFileSection(out, "ÚLTIMO BLOQUEO / ANR", CrashManager.ANR_FILE, 16000);
        appendFileSection(out, "ÚLTIMO CRASH DEL SERVICIO", SaleListenerService.CRASH_FILE, 12000);
        appendFileSection(out, "EVENTOS RECIENTES DE LA APP", CrashManager.EVENT_FILE, 12000);
        appendFileSection(out, "EVENTOS RECIENTES DEL SERVICIO", SaleListenerService.EVENT_FILE, 9000);

        out.append("\nÚLTIMOS CIERRES REGISTRADOS POR ANDROID\n");
        out.append("=======================================\n");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            out.append("Este Android no expone ApplicationExitInfo.\n");
        } else {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> rows = am.getHistoricalProcessExitReasons(getPackageName(), 0, 12);
                if (rows == null || rows.isEmpty()) {
                    out.append("No hay cierres históricos registrados.\n");
                } else {
                    int i = 1;
                    SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
                    for (ApplicationExitInfo x : rows) {
                        out.append('\n').append(i++).append(") ").append(df.format(new Date(x.getTimestamp()))).append('\n');
                        out.append("   proceso: ").append(x.getProcessName()).append('\n');
                        out.append("   motivo: ").append(reasonName(x.getReason())).append(" [").append(x.getReason()).append("]\n");
                        out.append("   status/señal: ").append(x.getStatus()).append('\n');
                        out.append("   PSS: ").append(x.getPss()).append(" KB · RSS: ").append(x.getRss()).append(" KB\n");
                        String d = x.getDescription();
                        if (d != null && !d.trim().isEmpty()) out.append("   detalle: ").append(d.trim()).append('\n');
                    }
                }
            } catch (Throwable e) {
                out.append("No se pudo leer historial de cierres: ")
                        .append(e.getClass().getSimpleName()).append(" · ").append(String.valueOf(e.getMessage())).append('\n');
            }
        }

        lastReport = out.toString();
        reportView.setText(lastReport);
    }

    private void appendFileSection(StringBuilder out, String title, String file, int max) {
        out.append("\n").append(title).append("\n");
        for (int i = 0; i < title.length(); i++) out.append('=');
        out.append('\n');
        String text = readInternalFile(file);
        if (text.trim().isEmpty()) out.append("Sin registro.\n");
        else out.append(trimTail(text, max)).append('\n');
    }

    private String readInternalFile(String name) {
        try {
            File file = new File(getFilesDir(), name);
            if (!file.exists()) return "";
            try (FileInputStream fis = new FileInputStream(file);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) > 0 && bos.size() < 120000) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } catch (Throwable e) {
            return "ERROR leyendo " + name + ": " + e.getClass().getSimpleName() + " · " + String.valueOf(e.getMessage());
        }
    }

    private String trimTail(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text.trim();
        return "...\n" + text.substring(text.length() - max).trim();
    }

    private String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF · la propia app terminó el proceso";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED · Android/Linux terminó el proceso";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY · falta de memoria";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH · excepción Java";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE · fallo nativo";
            case ApplicationExitInfo.REASON_ANR: return "ANR · la app no respondió";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED · usuario/sistema la cerró";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN";
        }
    }

    private void copyReport() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ML Central gestor de errores", lastReport));
            Toast.makeText(this, "Informe completo copiado", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "No se pudo copiar", Toast.LENGTH_SHORT).show();
        }
    }
}
