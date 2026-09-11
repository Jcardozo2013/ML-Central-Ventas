package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public final class SyncDiagnostics {
    private static final String PING_TITLE = "MLC_STATE_DIAG_PING_V1";
    private static final String PONG_TITLE = "MLC_STATE_DIAG_PONG_V1";

    private SyncDiagnostics() {}

    private static String shortTopic(String topic) {
        if (topic == null) return "—";
        String t = topic.trim();
        if (t.length() <= 10) return t;
        return t.substring(0, 4) + "…" + t.substring(t.length() - 6);
    }

    private static String errorText(Throwable e) {
        if (e == null) return "error desconocido";
        String n = e.getClass().getSimpleName();
        String m = e.getMessage();
        String out = (n == null ? "Error" : n) + (m == null || m.trim().isEmpty() ? "" : ": " + m.trim());
        return out.length() > 300 ? out.substring(0, 300) : out;
    }

    private static int postPing(String pingId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("type", "state_diag_ping_v1");
        body.put("ping_id", pingId);
        body.put("version", BuildConfig.VERSION_NAME);
        body.put("at", System.currentTimeMillis() / 1000L);

        JSONObject envelope = new JSONObject();
        envelope.put("topic", ReadSync.topic());
        envelope.put("title", PING_TITLE);
        envelope.put("message", body.toString());
        envelope.put("priority", 1);

        byte[] data = envelope.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(AppConfig.BASE_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "MLCentralVentas/" + BuildConfig.VERSION_NAME + " Diagnostic");
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            return code;
        } finally {
            conn.disconnect();
        }
    }

    private static String waitForPong(String pingId) throws Exception {
        long deadline = System.currentTimeMillis() + 12000L;
        String url = AppConfig.BASE_URL + AppConfig.TOPIC + "/json?poll=1&since=2m";
        while (System.currentTimeMillis() < deadline) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/x-ndjson");
                conn.setRequestProperty("User-Agent", "MLCentralVentas/" + BuildConfig.VERSION_NAME + " Diagnostic");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    JSONObject msg;
                    try { msg = new JSONObject(line); } catch (Exception ignored) { continue; }
                    if (!"message".equals(msg.optString("event", ""))) continue;
                    if (!PONG_TITLE.equals(msg.optString("title", ""))) continue;
                    JSONObject body;
                    try { body = new JSONObject(msg.optString("message", "{}")); } catch (Exception ignored) { continue; }
                    if (pingId.equals(body.optString("ping_id", ""))) {
                        return body.optString("windows_version", "Windows respondió");
                    }
                }
            } finally {
                conn.disconnect();
            }
            try { Thread.sleep(1200L); } catch (InterruptedException ignored) {}
        }
        return "";
    }

    public static void run(Activity activity) {
        if (activity == null) return;
        final long started = System.currentTimeMillis();
        new Thread(() -> {
            SharedPreferences p = activity.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
            String pingId = UUID.randomUUID().toString();
            StringBuilder report = new StringBuilder();
            report.append("ML CENTRAL VENTAS — DIAGNÓSTICO ESTADOS\n");
            report.append("Versión APK: ").append(BuildConfig.VERSION_NAME).append("\n");
            report.append("Hora: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");
            report.append("Modo conexión: ").append(p.getString("connection_mode", "—")).append("\n");
            report.append("Último error conexión: ").append(p.getString("connection_last_error", "—")).append("\n");
            report.append("Canal principal: ").append(shortTopic(AppConfig.TOPIC)).append(" (len=").append(AppConfig.TOPIC.length()).append(")\n");
            report.append("Canal estados: ").append(shortTopic(ReadSync.topic())).append(" (len=").append(ReadSync.topic().length()).append(")\n");
            report.append("Último estado PC: ").append(StateStore.statusText(activity)).append("\n\n");
            report.append("Prueba PING: ");
            try {
                int code = postPing(pingId);
                report.append("enviado HTTP ").append(code).append("\n");
                report.append("Esperando PONG de Windows...\n");
                String pong = waitForPong(pingId);
                if (pong == null || pong.trim().isEmpty()) {
                    report.append("RESULTADO: PING salió del celular, pero NO llegó PONG desde Windows en 12 s.\n");
                    report.append("Lectura: revisar si Windows está leyendo el canal de Estados o si falla su respuesta.\n");
                } else {
                    report.append("RESULTADO: OK — Windows respondió: ").append(pong).append("\n");
                    report.append("Lectura: el enlace ida/vuelta funciona. Si Estados no cargan, el fallo está en el armado/recepción del snapshot.\n");
                }
            } catch (Exception e) {
                report.append("ERROR\n");
                report.append("RESULTADO: ").append(errorText(e)).append("\n");
                report.append("Lectura: el celular no pudo completar la prueba de red/canal.\n");
            }
            report.append("\nDuración: ").append((System.currentTimeMillis() - started) / 1000.0).append(" s\n");
            String text = report.toString();
            p.edit().putString("last_sync_diagnostic", text).apply();
            activity.runOnUiThread(() -> show(activity, text));
        }, "MLCentralSyncDiagnostic").start();
    }

    private static void show(Activity activity, String report) {
        AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle("Diagnóstico APK ↔ Windows")
                .setMessage(report)
                .setPositiveButton("Copiar", null)
                .setNegativeButton("Cerrar", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico ML Central", report));
            } catch (Exception ignored) {}
        }));
        dlg.show();
    }
}
