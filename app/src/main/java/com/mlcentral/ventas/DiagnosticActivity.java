package com.mlcentral.ventas;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

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
        title.setText("ML Central · Diagnóstico v1.34");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, full(0, 14));

        TextView help = new TextView(this);
        help.setText("El servicio de ventas corre separado de esta pantalla. Si falla, la pantalla debe seguir abierta y abajo aparecerá el stack Java exacto del servicio.");
        help.setTextSize(15);
        root.addView(help, full(0, 14));

        reportView = new TextView(this);
        reportView.setTextSize(13);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(reportView, full(0, 12));

        Button refresh = button("Actualizar informe");
        refresh.setOnClickListener(v -> refreshReport());
        root.addView(refresh, full(0, 8));

        Button copy = button("Copiar informe completo");
        copy.setOnClickListener(v -> copyReport());
        root.addView(copy, full(0, 8));

        Button firebase = button("1 · Probar Firebase solamente");
        firebase.setOnClickListener(v -> testFirebase());
        root.addView(firebase, full(0, 8));

        Button service = button("2 · Probar servicio y capturar error");
        service.setOnClickListener(v -> testService());
        root.addView(service, full(0, 8));

        Button stop = button("Detener servicio");
        stop.setOnClickListener(v -> {
            try { stopService(new Intent(this, SaleListenerService.class)); } catch (Throwable ignored) {}
            Toast.makeText(this, "Servicio detenido", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::refreshReport, 500L);
        });
        root.addView(stop, full(0, 8));

        Button normal = button("3 · Abrir ML Central normal");
        normal.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
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
        out.append("Dispositivo: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Paquete: ").append(getPackageName()).append("\n\n");

        String crash = readInternalFile(SaleListenerService.CRASH_FILE);
        String events = readInternalFile(SaleListenerService.EVENT_FILE);

        out.append("ÚLTIMO STACK DEL SERVICIO\n");
        out.append("=========================\n");
        if (crash.trim().isEmpty()) out.append("Todavía no se capturó una excepción del servicio.\n");
        else out.append(crash.trim()).append('\n');

        out.append("\nEVENTOS DEL SERVICIO\n");
        out.append("====================\n");
        if (events.trim().isEmpty()) out.append("Sin eventos de esta prueba.\n");
        else out.append(trimTail(events, 9000)).append('\n');

        out.append("\nÚLTIMOS CIERRES REGISTRADOS POR ANDROID\n");
        out.append("=====================================\n");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            out.append("Este Android no permite leer ApplicationExitInfo (requiere Android 11 o superior).\n");
        } else {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> rows = am.getHistoricalProcessExitReasons(getPackageName(), 0, 12);
                if (rows == null || rows.isEmpty()) {
                    out.append("No hay cierres históricos registrados todavía.\n");
                } else {
                    int i = 1;
                    SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
                    for (ApplicationExitInfo x : rows) {
                        out.append('\n').append(i++).append(") ")
                                .append(df.format(new Date(x.getTimestamp()))).append('\n');
                        out.append("   proceso: ").append(x.getProcessName()).append('\n');
                        out.append("   motivo: ").append(reasonName(x.getReason())).append(" [").append(x.getReason()).append("]\n");
                        out.append("   status/señal: ").append(x.getStatus()).append('\n');
                        out.append("   PSS: ").append(x.getPss()).append(" KB · RSS: ").append(x.getRss()).append(" KB\n");
                        String d = x.getDescription();
                        if (d != null && !d.trim().isEmpty()) out.append("   detalle: ").append(d.trim()).append('\n');
                    }
                }
            } catch (Throwable e) {
                out.append("No se pudo leer el historial: ")
                        .append(e.getClass().getSimpleName()).append(": ").append(String.valueOf(e.getMessage())).append('\n');
            }
        }

        lastReport = out.toString();
        reportView.setText(lastReport);
    }

    private String readInternalFile(String name) {
        try {
            File file = new File(getFilesDir(), name);
            if (!file.exists()) return "";
            try (FileInputStream fis = new FileInputStream(file);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) > 0 && bos.size() < 50000) bos.write(buf, 0, n);
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
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED · Android/Linux terminó el proceso con una señal";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY · falta de memoria";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH · excepción Java no controlada";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE · fallo de código nativo";
            case ApplicationExitInfo.REASON_ANR: return "ANR · la app quedó sin responder";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE · fallo al iniciar";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE · cambio de permisos";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE · uso excesivo de recursos";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED · cierre solicitado por usuario/sistema";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED · murió una dependencia";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER · otro motivo del sistema";
            default: return "UNKNOWN";
        }
    }

    private void copyReport() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ML Central diagnóstico v1.34", lastReport));
            Toast.makeText(this, "Informe copiado", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "No se pudo copiar", Toast.LENGTH_SHORT).show();
        }
    }

    private void testFirebase() {
        try {
            FirebaseConfig.ensureInitialized(this);
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            String who = user == null ? "sin sesión" : "UID " + user.getUid();
            Toast.makeText(this, "Firebase inició: " + who, Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(this, "ERROR Firebase: " + e.getClass().getSimpleName() + " · " + String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void testService() {
        try {
            new File(getFilesDir(), SaleListenerService.CRASH_FILE).delete();
            new File(getFilesDir(), SaleListenerService.EVENT_FILE).delete();
        } catch (Throwable ignored) {}

        Toast.makeText(this, "Iniciando servicio aislado. Esta pantalla debería permanecer abierta.", Toast.LENGTH_LONG).show();
        try {
            Intent i = new Intent(this, SaleListenerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Throwable e) {
            lastReport = "ERROR al pedir inicio del servicio: " + e.getClass().getName() + " · " + String.valueOf(e.getMessage());
            reportView.setText(lastReport);
            return;
        }

        handler.postDelayed(this::refreshReport, 1200L);
        handler.postDelayed(this::refreshReport, 3000L);
        handler.postDelayed(this::refreshReport, 6000L);
    }
}
