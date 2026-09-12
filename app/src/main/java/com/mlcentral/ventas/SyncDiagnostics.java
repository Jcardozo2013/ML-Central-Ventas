package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @SuppressWarnings("unchecked")
    private static JSONObject snapshotJson(DataSnapshot snapshot) {
        try {
            Object raw = snapshot.getValue();
            if (!(raw instanceof Map)) return null;
            JSONObject out = new JSONObject((Map<String, Object>) raw);
            if (!out.has("id") && snapshot.getKey() != null) out.put("id", snapshot.getKey());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static void run(Activity activity) {
        if (activity == null) return;
        final long started = System.currentTimeMillis();
        new Thread(() -> {
            FirebaseConfig.ensureInitialized(activity);
            SharedPreferences p = activity.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
            String pingId = UUID.randomUUID().toString();
            StringBuilder report = new StringBuilder();
            report.append("ML CENTRAL VENTAS — DIAGNÓSTICO ESTADOS\n");
            report.append("Versión APK: ").append(BuildConfig.VERSION_NAME).append("\n");
            report.append("Hora: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");
            report.append("Transporte: Firebase Realtime Database\n");
            report.append("Modo conexión: ").append(p.getString("connection_mode", "—")).append("\n");
            report.append("Último error conexión: ").append(p.getString("connection_last_error", "—")).append("\n");
            report.append("Canal principal: ").append(shortTopic(AppConfig.TOPIC)).append("\n");
            report.append("Canal estados: ").append(shortTopic(ReadSync.topic())).append("\n");
            report.append("Último estado PC: ").append(StateStore.statusText(activity)).append("\n\n");
            report.append("Prueba PING Firebase: ");

            if (!FirebaseTransport.signedIn(activity)) {
                report.append("ERROR\nRESULTADO: no hay sesión Firebase iniciada.\n");
            } else {
                DatabaseReference main = FirebaseDatabase.getInstance().getReference("channels/main");
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<String> pong = new AtomicReference<>("");
                AtomicReference<ChildEventListener> holder = new AtomicReference<>();
                ChildEventListener listener = new ChildEventListener() {
                    @Override public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                        JSONObject msg = snapshotJson(snapshot);
                        if (msg == null || !PONG_TITLE.equals(msg.optString("title", ""))) return;
                        try {
                            JSONObject body = new JSONObject(msg.optString("message", "{}"));
                            if (pingId.equals(body.optString("ping_id", ""))) {
                                pong.set(body.optString("windows_version", "Windows respondió"));
                                latch.countDown();
                            }
                        } catch (Exception ignored) {}
                    }
                    @Override public void onChildChanged(DataSnapshot snapshot, String previousChildName) {}
                    @Override public void onChildRemoved(DataSnapshot snapshot) {}
                    @Override public void onChildMoved(DataSnapshot snapshot, String previousChildName) {}
                    @Override public void onCancelled(DatabaseError error) { latch.countDown(); }
                };
                holder.set(listener);
                main.limitToLast(200).addChildEventListener(listener);
                try {
                    JSONObject body = new JSONObject();
                    body.put("type", "state_diag_ping_v1");
                    body.put("ping_id", pingId);
                    body.put("version", BuildConfig.VERSION_NAME);
                    body.put("at", System.currentTimeMillis() / 1000L);
                    FirebaseTransport.Result sent = FirebaseTransport.publish(activity, ReadSync.topic(), PING_TITLE, body, 1);
                    if (!sent.ok) {
                        report.append("ERROR\nRESULTADO: ").append(sent.detail).append("\n");
                    } else {
                        report.append("enviado\nEsperando PONG de Windows...\n");
                        latch.await(15, TimeUnit.SECONDS);
                        if (pong.get().trim().isEmpty()) {
                            report.append("RESULTADO: PING salió del celular, pero NO llegó PONG desde Windows en 15 s.\n");
                            report.append("Lectura: revisar la configuración Firebase de Windows.\n");
                        } else {
                            report.append("RESULTADO: OK — Windows respondió: ").append(pong.get()).append("\n");
                            report.append("Lectura: Firebase ida/vuelta funciona correctamente.\n");
                        }
                    }
                } catch (Exception e) {
                    report.append("ERROR\nRESULTADO: ").append(errorText(e)).append("\n");
                } finally {
                    try { main.removeEventListener(holder.get()); } catch (Exception ignored) {}
                }
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
