package com.mlcentral.ventas;

import android.content.Context;

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
        if (FirebaseApp.getApps(app).isEmpty()) {
            FirebaseOptions options = new FirebaseOptions.Builder()
                    .setProjectId(PROJECT_ID)
                    .setApplicationId(APP_ID)
                    .setApiKey(API_KEY)
                    .setDatabaseUrl(DATABASE_URL)
                    .build();
            FirebaseApp.initializeApp(app, options);
        }

        // v1.29: RTDB conserva escrituras pendientes en disco. Si un teléfono puede
        // leer Firebase pero tarda en confirmar una escritura, el cambio no se pierde:
        // queda en cola y Firebase lo vuelve a enviar cuando la conexión responde.
        if (!databasePrepared) {
            try {
                FirebaseDatabase.getInstance().setPersistenceEnabled(true);
            } catch (Exception ignored) {
                // Puede ocurrir si RTDB ya fue usado en este proceso. No impedir el arranque.
            }
            databasePrepared = true;
        }
    }
}
