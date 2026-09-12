package com.mlcentral.ventas;

import android.content.Context;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class FirebaseTransport {
    private FirebaseTransport() {}

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

            DatabaseReference child = FirebaseDatabase.getInstance()
                    .getReference(pathForTopic(topic))
                    .push();
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
            AtomicReference<String> detail = new AtomicReference<>("Firebase: timeout");
            child.setValue(value).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    ok.set(true);
                    detail.set("Firebase OK");
                } else {
                    Exception e = task.getException();
                    detail.set("Firebase: " + (e == null ? "error" : String.valueOf(e.getMessage())));
                }
                latch.countDown();
            });
            if (!latch.await(12, TimeUnit.SECONDS)) return new Result(false, "Firebase: timeout", key);
            return new Result(ok.get(), detail.get(), key);
        } catch (Exception e) {
            return new Result(false, "Firebase: " + e.getClass().getSimpleName() + " · " + String.valueOf(e.getMessage()), "");
        }
    }
}
