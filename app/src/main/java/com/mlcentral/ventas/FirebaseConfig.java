package com.mlcentral.ventas;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.FirebaseDatabase;

public final class FirebaseConfig {
    private FirebaseConfig() {}

    public static final String PROJECT_ID = "ml-central-2a48f";
    public static final String APP_ID = "1:383494225969:android:e76420239cdddf38dfd510";
    public static final String API_KEY = "AIzaSyD_LIRTX3TxkBkn3xPPG0X86DdUvpqV5b0";
    public static final String DATABASE_URL = "https://ml-central-2a48f-default-rtdb.firebaseio.com";
    public static final String EXPECTED_UID = "q2LacnYyb0bIzRo8molBV9vYJop2";

    private static boolean databasePrepared = false;

    public static synchronized void ensureInitialized(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try {
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setProjectId(PROJECT_ID)
                        .setApplicationId(APP_ID)
                        .setApiKey(API_KEY)
                        .setDatabaseUrl(DATABASE_URL)
                        .build();
                FirebaseApp.initializeApp(app, options);
            }

            if (!databasePrepared) {
                FirebaseDatabase db = FirebaseDatabase.getInstance();

                // v1.31: no usar la cola/caché persistente de RTDB en disco.
                // La APK ya tiene su propia cola durable en StateSync/ReadSync y
                // guardar una segunda cola en Firebase estaba provocando reenvíos,
                // carga excesiva y cierres en algunos teléfonos.
                try { db.setPersistenceEnabled(false); } catch (Throwable ignored) {}

                // Migración única desde v1.29/v1.30: descartar escrituras antiguas
                // que hayan quedado pendientes dentro de la cola interna de Firebase.
                // Los cambios reales pendientes siguen guardados por StateSync/ReadSync
                // y se reintentan de forma controlada.
                SharedPreferences p = app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE);
                if (!p.getBoolean("firebase_queue_migrated_v131", false)) {
                    try { db.purgeOutstandingWrites(); } catch (Throwable ignored) {}
                    p.edit().putBoolean("firebase_queue_migrated_v131", true).apply();
                }

                try { db.goOnline(); } catch (Throwable ignored) {}
                databasePrepared = true;
            }
        } catch (Throwable error) {
            // Firebase nunca debe impedir que la interfaz de la APK abra.
            try {
                app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString("firebase_init_error", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()))
                        .apply();
            } catch (Throwable ignored) {}
        }
    }
}
