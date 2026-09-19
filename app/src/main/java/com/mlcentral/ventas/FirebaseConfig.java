package com.mlcentral.ventas;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

public final class FirebaseConfig {
    private FirebaseConfig() {}

    public static final String PROJECT_ID = "alerta-ventas-juan";
    public static final String APP_ID = "1:751488196206:android:ae7e1e4a314c3b7251907f";
    public static final String API_KEY = "AIzaSyBYhRMzRNwkf-GNnNcEAGynImNjqh5xZpg";
    public static final String DATABASE_URL = "https://alerta-ventas-juan-default-rtdb.firebaseio.com";
    public static final String EXPECTED_UID = "wkhB4cn1YsNqorQezgqM2hW4nAK2";

    public static synchronized void ensureInitialized(Context context) {
        if (context == null) return;
        if (!FirebaseApp.getApps(context.getApplicationContext()).isEmpty()) return;
        FirebaseOptions options = new FirebaseOptions.Builder()
                .setProjectId(PROJECT_ID)
                .setApplicationId(APP_ID)
                .setApiKey(API_KEY)
                .setDatabaseUrl(DATABASE_URL)
                .build();
        FirebaseApp.initializeApp(context.getApplicationContext(), options);
    }
}
