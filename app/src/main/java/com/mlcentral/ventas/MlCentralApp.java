package com.mlcentral.ventas;

import android.app.Application;
import android.content.Context;

import java.io.File;

public class MlCentralApp extends Application {
    @Override public void onCreate() {
        super.onCreate();

        // v1.30: el teléfono afectado volvía a abrir después de "Borrar caché".
        // Limpiamos solo archivos temporales al iniciar. No toca sesión, ventas,
        // estados, preferencias ni datos persistentes de la aplicación.
        clearVolatileCache(this);

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { clearVolatileCache(MlCentralApp.this); } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void clearVolatileCache(Context context) {
        if (context == null) return;
        try {
            File dir = context.getCacheDir();
            if (dir == null || !dir.exists()) return;
            File[] children = dir.listFiles();
            if (children == null) return;
            for (File child : children) deleteRecursively(child);
        } catch (Throwable ignored) {
            // La recuperación de caché nunca debe impedir que abra la APK.
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        try { file.delete(); } catch (Throwable ignored) {}
    }
}
