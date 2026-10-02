package com.mlcentral.ventas;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

public final class FirebaseConfig {
    private FirebaseConfig() {}

    public static final String PROJECT_ID = "ml-central-2";
    public static final String APP_ID = "1:962306261615:android:0169808a926045ad48c39a";
    public static final String API_KEY = "AIzaSyAHgcANS4ky2NkeOEo3q8xmCy5106Ex_Ws";
    public static final String DATABASE_URL = "https://ml-central-2-default-rtdb.firebaseio.com";
    public static final String EXPECTED_UID = "QeJq3qPU8WTyARRp5O08moVzm993";

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
