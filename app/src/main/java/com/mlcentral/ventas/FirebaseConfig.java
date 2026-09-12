package com.mlcentral.ventas;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

public final class FirebaseConfig {
    private FirebaseConfig() {}

    public static final String PROJECT_ID = "ml-central-2a48f";
    public static final String APP_ID = "1:383494225969:android:e76420239cdddf38dfd510";
    public static final String API_KEY = "AIzaSyD_LIRTX3TxkBkn3xPPG0X86DdUvpqV5b0";
    public static final String DATABASE_URL = "https://ml-central-2a48f-default-rtdb.firebaseio.com";
    public static final String EXPECTED_UID = "q2LacnYyb0bIzRo8molBV9vYJop2";

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
