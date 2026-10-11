package com.mlcentral.ventas;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class StateSync {
    public static final String STATE_REQUEST_TITLE = "MLC_STATE_REQUEST_V1";
    public static final String STATE_CHANGE_TITLE = "MLC_STATE_CHANGE_V1";
    public static final String STATE_SNAPSHOT_BEGIN_TITLE = "MLC_STATE_SNAPSHOT_BEGIN_V1";
    public static final String STATE_SNAPSHOT_CHUNK_TITLE = "MLC_STATE_SNAPSHOT_CHUNK_V1";
    public static final String STATE_SNAPSHOT_END_TITLE = "MLC_STATE_SNAPSHOT_END_V1";
    public static final String STATE_CHANGE_RESULT_TITLE = "MLC_STATE_CHANGE_RESULT_V1";

    private static final String PENDING_KEY = "pending_state_commands_v1";
    private static final long REQUEST_MIN_MS = 300000L;
    private static final int REQUEST_ATTEMPTS = 3;
    private static final long REQUEST_RETRY_MS = 1800L;
    private static volatile boolean requesting = false;
    private static volatile boolean flushing = false;
    private static volatile boolean flushRequested = false;

    private StateSync() {}

    private static final class PostResult {
        final boolean ok;
        final String detail;
        PostResult(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail == null ? "" : detail.trim();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
    }

    private static String topic() { return ReadSync.topic(); }

    private static void notifyUi(Context context) {
        try {
            Context app = context.getApplicationContext();
            app.sendBroadcast(new Intent("com.mlcentral.ventas.SALE_RECEIVED").setPackage(app.getPackageName()));
        } catch (Exception ignored) {}
    }

    private static void setStatus(Context context, String text) {
        StateStore.setStatus(context, text);
        notifyUi(context);
    }

    private static String exceptionDetail(Exception e) {
        String name = e == null ? "Error" : e.getClass().getSimpleName();
        String msg = e == null || e.getMessage() == null ? "" : e.getMessage().trim();
        if (msg.length() > 100) msg = msg.substring(0, 100);
        return msg.isEmpty() ? name : name + " · " + msg;
    }

    private static PostResult post(Context context, String title, JSONObject body) {
        FirebaseTransport.Result result = FirebaseTransport.publish(context, topic(), title, body, 1);
        return new PostResult(result.ok, result.detail);
    }

    public static void requestSnapshotAsync(Context context, boolean force) {
        Context app = context.getApplicationContext();
        SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        long last = p.getLong("state_request_last_at_v1", 0L);
        if (!force && last > 0 && now - last < REQUEST_MIN_MS && !StateStore.isStale(app, 5 * 60 * 1000L)) return;
        synchronized (StateSync.class) {
            if (requesting) return;
            requesting = true;
        }

        setStatus(app, "Estados PC: enviando solicitud…");
        Thread t = new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("type", "state_request_v1");
                body.put("request_id", UUID.randomUUID().toString());
                body.put("at", System.currentTimeMillis() / 1000L);
                // v1.54: Windows v21.01 puede responder por state/current con un
                // único PUT atómico, sin mandar 100+ ventas en bloques.
                body.put("durable_v2", true);
                body.put("client_version", BuildConfig.VERSION_NAME);

                PostResult lastResult = new PostResult(false, "sin respuesta");
                for (int attempt = 1; attempt <= REQUEST_ATTEMPTS; attempt++) {
                    lastResult = post(app, STATE_REQUEST_TITLE, body);
                    if (lastResult.ok) break;
                    if (attempt < REQUEST_ATTEMPTS) {
                        setStatus(app, "Estados PC: reintentando envío " + (attempt + 1) + "/" + REQUEST_ATTEMPTS + "…");
                        try { Thread.sleep(REQUEST_RETRY_MS); } catch (InterruptedException ignored) {}
                    }
                }

                if (lastResult.ok) {
                    p.edit().putLong("state_request_last_at_v1", System.currentTimeMillis()).apply();
                    setStatus(app, "Estados PC: solicitud enviada · esperando Windows…");
                } else {
                    setStatus(app, "Estados PC: fallo de envío · " + lastResult.detail);
                }
            } catch (Exception e) {
                setStatus(app, "Estados PC: error · " + exceptionDetail(e));
            } finally {
                requesting = false;
            }
        }, "MLCentralStateRequest");
        t.setDaemon(true);
        t.start();
    }

    private static JSONArray pending(Context c) {
        try { return new JSONArray(prefs(c).getString(PENDING_KEY, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static synchronized String queueCommand(Context context, String orderId, String action, String expectedStage, String eventId) {
        Context app = context.getApplicationContext();
        String oid = orderId == null ? "" : orderId.trim();
        String act = action == null ? "" : action.trim();
        if (oid.isEmpty() || act.isEmpty()) return "";
        JSONArray old = pending(app);
        for (int i = 0; i < old.length(); i++) {
            JSONObject x = old.optJSONObject(i);
            if (x != null && oid.equals(x.optString("order_id", ""))) return x.optString("command_id", "");
        }
        String commandId = UUID.randomUUID().toString();
        JSONObject cmd = new JSONObject();
        try {
            cmd.put("type", "state_change_v1");
            cmd.put("command_id", commandId);
            cmd.put("order_id", oid);
            cmd.put("action", act);
            cmd.put("expected_stage", expectedStage == null ? "" : expectedStage.trim());
            if (eventId != null && !eventId.trim().isEmpty()) cmd.put("event_id", eventId.trim());
            cmd.put("at", System.currentTimeMillis() / 1000L);
            old.put(cmd);
        } catch (Exception ignored) {}
        prefs(app).edit().putString(PENDING_KEY, old.toString()).apply();
        StateStore.setStatus(app, "Cambio pendiente de confirmar en la PC");
        flushPendingAsync(app);
        return commandId;
    }

    public static synchronized String queueAction(Context context, String orderId, String action, String expectedStage) {
        return queueCommand(context, orderId, action, expectedStage, "");
    }


    /** Los únicos dos pasos permitidos desde el lector de etiquetas. */
    public static final class ScanCommand {
        public final String orderId;
        public final String action;
        public final String expectedStage;

        public ScanCommand(String orderId, String action, String expectedStage) {
            this.orderId = orderId == null ? "" : orderId.trim();
            this.action = action == null ? "" : action.trim();
            this.expectedStage = expectedStage == null ? "" : expectedStage.trim();
        }
    }

    public static final class BatchResult {
        public final int queued;
        public final List<String> rejectedOrderIds;

        BatchResult(int queued, List<String> rejectedOrderIds) {
            this.queued = queued;
            this.rejectedOrderIds = rejectedOrderIds;
        }
    }

    /**
     * Encolar una tanda de QR con una sola escritura persistente.
     * El lector NO actualiza estados locales: espera confirmación de Windows.
     * No interfiere con queueAction/queueUndo de las pantallas existentes.
     */
    public static synchronized BatchResult queueScanBatch(Context context, List<ScanCommand> commands) {
        Context app = context.getApplicationContext();
        List<String> rejected = new ArrayList<>();
        if (commands == null || commands.isEmpty()) return new BatchResult(0, rejected);
        JSONArray commandsBefore = pending(app);
        Set<String> pendingOrders = new HashSet<>();
        for (int i = 0; i < commandsBefore.length(); i++) {
            JSONObject cmd = commandsBefore.optJSONObject(i);
            if (cmd != null) pendingOrders.add(cmd.optString("order_id", "").trim());
        }
        JSONObject current = StateStore.snapshotSales(app);
        int queued = 0;
        for (ScanCommand entry : commands) {
            if (entry == null) continue;
            String order = entry.orderId;
            JSONObject actual = current.optJSONObject(order);
            boolean allowed = ("br_arrived".equals(entry.expectedStage)
                    && "mark_received".equals(entry.action))
                    || ("pending_rocha".equals(entry.expectedStage)
                    && "send_to_rocha".equals(entry.action));
            if (order.isEmpty() || !allowed || actual == null
                    || !entry.expectedStage.equals(actual.optString("stage", ""))
                    || pendingOrders.contains(order)) {
                rejected.add(order);
                continue;
            }
            JSONObject command = new JSONObject();
            try {
                command.put("type", "state_change_v1");
                command.put("command_id", UUID.randomUUID().toString());
                command.put("order_id", order);
                command.put("action", entry.action);
                command.put("expected_stage", entry.expectedStage);
                command.put("at", System.currentTimeMillis() / 1000L);
                commandsBefore.put(command);
                pendingOrders.add(order);
                queued++;
            } catch (Exception ex) {
                rejected.add(order);
            }
        }
        if (queued > 0) {
            // Garantizar guardado completo antes de iniciar el envío; una orden
            // no se pierde si el usuario cierra la cámara inmediatamente.
            boolean saved = prefs(app).edit()
                    .putString(PENDING_KEY, commandsBefore.toString()).commit();
            if (!saved) {
                rejected.clear();
                for (ScanCommand cmd : commands) if (cmd != null) rejected.add(cmd.orderId);
                return new BatchResult(0, rejected);
            }
            StateStore.setStatus(app, queued + " cambios de etiqueta esperando PC");
            flushPendingAsync(app);
        }
        return new BatchResult(queued, rejected);
    }

    public static synchronized String queueUndo(Context context, String eventId, String orderId, String expectedStage) {
        return queueCommand(context, orderId, "undo_state_change", expectedStage, eventId);
    }

    public static void flushPendingAsync(Context context) {
        Context app = context.getApplicationContext();
        synchronized (StateSync.class) {
            if (flushing) {
                // Un lote nuevo puede entrar mientras se envían órdenes anteriores.
                // Pedir otra pasada evita dejarlo en cola hasta el próximo reinicio.
                flushRequested = true;
                return;
            }
            flushing = true;
            flushRequested = false;
        }
        Thread t = new Thread(() -> {
            boolean released = false;
            try {
                boolean more;
                do {
                    synchronized (StateSync.class) { flushRequested = false; }
                    JSONArray arr = pending(app);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject cmd = arr.optJSONObject(i);
                        if (cmd == null) continue;
                        PostResult sent = post(app, STATE_CHANGE_TITLE, cmd);
                        if (!sent.ok) {
                            setStatus(app, "Cambio pendiente · se reintentará cuando haya conexión");
                        }
                        try { Thread.sleep(80L); } catch (InterruptedException ignored) {}
                    }
                    synchronized (StateSync.class) {
                        more = flushRequested;
                        if (!more) {
                            flushing = false;
                            released = true;
                        }
                    }
                } while (more);
            } finally {
                if (!released) {
                    synchronized (StateSync.class) { flushing = false; }
                }
            }
        }, "MLCentralStateCommandSender");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized int pendingCount(Context context) {
        return pending(context).length();
    }

    public static synchronized boolean hasPendingForOrder(Context context, String orderId) {
        String oid = orderId == null ? "" : orderId.trim();
        JSONArray arr = pending(context);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x != null && oid.equals(x.optString("order_id", ""))) return true;
        }
        return false;
    }

    private static boolean success(JSONObject body) {
        if (body == null) return false;
        Object raw = body.opt("success");
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() == 1;
        String s = raw == null ? "" : String.valueOf(raw).trim().toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s);
    }

    public static synchronized void handleResult(Context context, JSONObject body) {
        if (body == null || !"state_change_result_v1".equals(body.optString("type", ""))) return;
        Context app = context.getApplicationContext();
        String commandId = body.optString("command_id", "").trim();
        JSONArray old = pending(app);
        JSONArray out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject x = old.optJSONObject(i);
            if (x == null) continue;
            if (!commandId.equals(x.optString("command_id", ""))) out.put(x);
        }
        prefs(app).edit().putString(PENDING_KEY, out.toString()).apply();
        JSONObject state = body.optJSONObject("state");
        if (state != null) StateStore.upsertState(app, state);
        boolean ok = success(body);
        String message = body.optString("message", ok ? "Cambio confirmado" : "Cambio rechazado");
        StateStore.setStatus(app, (ok ? "PC confirmó: " : "PC rechazó: ") + message);
        notifyUi(app);
    }
}
