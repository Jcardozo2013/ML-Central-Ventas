package com.mlcentral.ventas;

import android.content.Context;
import android.os.Build;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class FirebaseTransport {
    private FirebaseTransport() {}

    public static boolean lowMemorySafeMode() {
        try {
            long max = Runtime.getRuntime().maxMemory();
            String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
            // Activamos el modo seguro únicamente en la familia que mostró el
            // OOM real (Xiaomi/Redmi/POCO) cuando Android limita el heap a <=192 MB.
            // El otro celular que mantiene Firebase estable conserva tiempo real.
            boolean xiaomiFamily = maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco");
            return xiaomiFamily && max > 0L && max <= 192L * 1024L * 1024L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String detail;
        public final String key;
        Result(boolean ok, String detail, String key) {
            this.ok = ok;
            this.detail = detail == null ? "" : detail;
            this.key = key == null ? "" : key;
        }
    }

    public static final class JsonResult {
        public final boolean ok;
        public final String detail;
        public final JSONObject data;
        JsonResult(boolean ok, String detail, JSONObject data) {
            this.ok = ok;
            this.detail = detail == null ? "" : detail;
            this.data = data;
        }
    }

    private static String pathForTopic(String topic) {
        String t = topic == null ? "" : topic.trim();
        return t.endsWith("-rs") ? "channels/rs" : "channels/main";
    }

    public static boolean signedIn(Context context) {
        try {
            FirebaseConfig.ensureInitialized(context);
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            return user != null && FirebaseConfig.EXPECTED_UID.equals(user.getUid());
        } catch (Exception e) {
            return false;
        }
    }

    public static Result publish(Context context, String topic, String title, JSONObject body, int priority) {
        return publishText(context, topic, title, body == null ? "{}" : body.toString(), priority, "");
    }

    public static Result publishText(Context context, String topic, String title, String message, int priority, String sequenceId) {
        try {
            FirebaseConfig.ensureInitialized(context);
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return new Result(false, "Firebase: iniciá sesión", "");
            if (!FirebaseConfig.EXPECTED_UID.equals(user.getUid())) {
                return new Result(false, "Firebase: usuario no autorizado", "");
            }

            String path = pathForTopic(topic);

            // v1.61: en teléfonos con heap Java pequeño (p.ej. 128 MB),
            // evitar completamente el socket RTDB para escrituras. Ese socket
            // fue la causa comprobada del OutOfMemoryError en Xiaomi/Android 15.
            if (lowMemorySafeMode()) {
                Map<String, Object> safeValue = new LinkedHashMap<>();
                safeValue.put("event", "message");
                safeValue.put("topic", topic == null ? "" : topic);
                safeValue.put("title", title == null ? "" : title);
                safeValue.put("message", message == null ? "" : message);
                safeValue.put("priority", priority);
                safeValue.put("time", System.currentTimeMillis() / 1000L);
                if (sequenceId != null && !sequenceId.trim().isEmpty()) safeValue.put("sequence_id", sequenceId.trim());
                return restPost(user, path, "", safeValue, false);
            }

            FirebaseDatabase db = FirebaseDatabase.getInstance();
            try { db.goOnline(); } catch (Exception ignored) {}

            DatabaseReference child = db.getReference(path).push();
            String key = child.getKey();
            if (key == null || key.trim().isEmpty()) return new Result(false, "Firebase: no se pudo crear mensaje", "");

            Map<String, Object> value = new LinkedHashMap<>();
            value.put("event", "message");
            value.put("id", key);
            value.put("topic", topic == null ? "" : topic);
            value.put("title", title == null ? "" : title);
            value.put("message", message == null ? "" : message);
            value.put("priority", priority);
            value.put("time", System.currentTimeMillis() / 1000L);
            if (sequenceId != null && !sequenceId.trim().isEmpty()) value.put("sequence_id", sequenceId.trim());

            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean ok = new AtomicBoolean(false);
            AtomicReference<String> detail = new AtomicReference<>("Firebase SDK: timeout");
            child.setValue(value).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    ok.set(true);
                    detail.set("Firebase SDK OK");
                } else {
                    Exception e = task.getException();
                    detail.set("Firebase SDK: " + (e == null ? "error" : String.valueOf(e.getMessage())));
                }
                latch.countDown();
            });

            if (latch.await(5, TimeUnit.SECONDS)) {
                if (ok.get()) return new Result(true, detail.get(), key);
                // Error explícito del SDK: intentamos la misma escritura por HTTPS.
                Result rest = restPost(user, path, key, value, false);
                if (rest.ok) return rest;
                return new Result(false, detail.get() + " · " + rest.detail, key);
            }

            // v1.55: algunos teléfonos quedan con el socket de Realtime Database
            // aparentemente conectado pero una escritura nunca recibe ACK. En ese
            // caso usamos la API REST autenticada al MISMO child key. Si el SDK
            // revive después, solo sobrescribe el mismo mensaje y no lo duplica.
            Result rest = restPost(user, path, key, value, false);
            if (rest.ok) return rest;
            return new Result(false, "Firebase SDK timeout · " + rest.detail, key);
        } catch (Exception e) {
            return new Result(false, "Firebase: " + e.getClass().getSimpleName() + " · " + String.valueOf(e.getMessage()), "");
        }
    }

    private static Result restPost(FirebaseUser user, String path, String key, Map<String, Object> value, boolean forceRefresh) {
        String token = getIdToken(user, forceRefresh);
        if (token.isEmpty()) return new Result(false, "REST: no se pudo obtener token Firebase", key);

        HttpURLConnection conn = null;
        try {
            String base = FirebaseConfig.DATABASE_URL;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            // v1.56: el fallback usa POST al canal para que Firebase genere el
            // push-id en el servidor. Con varios celulares evitamos que un ID
            // generado por un teléfono quede detrás del cursor de Windows.
            String url = base + "/" + path + ".json?auth="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8.name());
            conn = (HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] body = new JSONObject(value).toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String serverKey = key;
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder raw = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) raw.append(line);
                    JSONObject response = new JSONObject(raw.toString());
                    String generated = response.optString("name", "").trim();
                    if (!generated.isEmpty()) serverKey = generated;
                } catch (Exception ignored) {}
                return new Result(true, "Firebase REST fallback OK · ID servidor", serverKey);
            }
            if ((code == 401 || code == 403) && !forceRefresh) {
                conn.disconnect();
                return restPost(user, path, key, value, true);
            }
            return new Result(false, "REST HTTP " + code, key);
        } catch (Exception e) {
            return new Result(false, "REST " + e.getClass().getSimpleName() + " · " + String.valueOf(e.getMessage()), key);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static JsonResult readJsonRest(Context context, String path, int limitToLast) {
        try {
            FirebaseConfig.ensureInitialized(context);
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return new JsonResult(false, "REST: sin sesión Firebase", null);
            if (!FirebaseConfig.EXPECTED_UID.equals(user.getUid())) {
                return new JsonResult(false, "REST: usuario no autorizado", null);
            }
            return readJsonRest(user, path, limitToLast, false);
        } catch (Exception e) {
            return new JsonResult(false, "REST " + e.getClass().getSimpleName() + " · " + String.valueOf(e.getMessage()), null);
        }
    }

    private static JsonResult readJsonRest(FirebaseUser user, String path, int limitToLast, boolean forceRefresh) {
        String token = getIdToken(user, forceRefresh);
        if (token.isEmpty()) return new JsonResult(false, "REST: no se pudo obtener token Firebase", null);

        HttpURLConnection conn = null;
        try {
            String base = FirebaseConfig.DATABASE_URL;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            StringBuilder url = new StringBuilder(base)
                    .append("/")
                    .append(path == null ? "" : path.replaceAll("^/+|/+$", ""))
                    .append(".json?auth=")
                    .append(URLEncoder.encode(token, StandardCharsets.UTF_8.name()));
            if (limitToLast > 0) {
                url.append("&orderBy=%22%24key%22&limitToLast=").append(Math.max(1, Math.min(limitToLast, 250)));
            }

            conn = (HttpURLConnection) new java.net.URL(url.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if ((code == 401 || code == 403) && !forceRefresh) {
                conn.disconnect();
                return readJsonRest(user, path, limitToLast, true);
            }
            if (code < 200 || code >= 300) return new JsonResult(false, "REST HTTP " + code, null);

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder raw = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) raw.append(line);
            String text = raw.toString().trim();
            if (text.isEmpty() || "null".equals(text)) return new JsonResult(true, "REST OK", new JSONObject());
            return new JsonResult(true, "REST OK", new JSONObject(text));
        } catch (Exception e) {
            return new JsonResult(false, "REST " + e.getClass().getSimpleName() + " · " + String.valueOf(e.getMessage()), null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String getIdToken(FirebaseUser user, boolean forceRefresh) {
        if (user == null) return "";
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> token = new AtomicReference<>("");
            user.getIdToken(forceRefresh).addOnCompleteListener(task -> {
                try {
                    if (task.isSuccessful() && task.getResult() != null && task.getResult().getToken() != null) {
                        token.set(task.getResult().getToken());
                    }
                } catch (Exception ignored) {}
                latch.countDown();
            });
            latch.await(6, TimeUnit.SECONDS);
            return token.get() == null ? "" : token.get();
        } catch (Exception e) {
            return "";
        }
    }
}
