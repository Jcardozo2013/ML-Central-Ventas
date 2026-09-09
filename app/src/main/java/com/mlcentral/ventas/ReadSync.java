package com.mlcentral.ventas;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ReadSync {
    public static final String TITLE = "MLC_READ_SYNC_V1";
    private static volatile boolean sending = false;

    private ReadSync() {}

    public static void acknowledgeAsync(Context context, Collection<String> ids) {
        Context app = context.getApplicationContext();
        SaleStore.queueReadSync(app, ids);
        flushPendingAsync(app);
    }

    public static void flushPendingAsync(Context context) {
        Context app = context.getApplicationContext();
        synchronized (ReadSync.class) {
            if (sending) return;
            sending = true;
        }
        Thread t = new Thread(() -> {
            try {
                List<String> ids = SaleStore.pendingReadSync(app);
                if (ids.isEmpty()) return;
                if (publish(ids)) SaleStore.clearPendingReadSync(app, ids);
            } finally {
                sending = false;
            }
        }, "MLCentralReadSync");
        t.setDaemon(true);
        t.start();
    }

    private static boolean publish(List<String> ids) {
        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (String id : ids) if (id != null && !id.trim().isEmpty()) arr.put(id.trim());
            if (arr.length() == 0) return true;

            JSONObject body = new JSONObject();
            body.put("type", "read_sync_v1");
            body.put("ids", arr);
            body.put("at", System.currentTimeMillis() / 1000L);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(AppConfig.BASE_URL + AppConfig.TOPIC);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setRequestProperty("Title", TITLE);
            conn.setRequestProperty("Priority", "min");
            conn.setRequestProperty("User-Agent", "MLCentralVentas/1.2 Android");
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean isReadSync(JSONObject message) {
        return message != null && TITLE.equals(message.optString("title", ""));
    }

    public static List<String> idsFromMessage(JSONObject message) {
        ArrayList<String> ids = new ArrayList<>();
        if (message == null) return ids;
        try {
            JSONObject body = new JSONObject(message.optString("message", "{}"));
            if (!"read_sync_v1".equals(body.optString("type", ""))) return ids;
            JSONArray arr = body.optJSONArray("ids");
            if (arr == null) return ids;
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "").trim();
                if (!id.isEmpty()) ids.add(id);
            }
        } catch (Exception ignored) {}
        return ids;
    }
}
