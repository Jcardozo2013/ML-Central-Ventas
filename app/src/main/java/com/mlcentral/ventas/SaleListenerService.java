package com.mlcentral.ventas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

/**
 * Nombre conservado para no cambiar el componente Android existente.
 * Desde v1.20 la implementación real usa Firebase Realtime Database.
 *
 * v1.24: Android exige que un servicio iniciado con startForegroundService()
 * llame startForeground() casi inmediatamente. Hacemos un bootstrap mínimo
 * antes de inicializar Firebase para evitar ForegroundServiceDidNotStartInTimeException
 * después de actualizar/reiniciar la APK.
 */
public class SaleListenerService extends FirebaseListenerService {
    private static final int FOREGROUND_ID = 7;

    @Override public void onCreate() {
        // El Service ya está adjunto al contexto cuando Android invoca onCreate().
        // Subimos a foreground ANTES de cualquier inicialización de Firebase.
        startForegroundBootstrap();
        super.onCreate();
    }

    private void startForegroundBootstrap() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null && nm.getNotificationChannel(SERVICE_CHANNEL) == null) {
                    NotificationChannel channel = new NotificationChannel(
                            SERVICE_CHANNEL,
                            "Servicio ML Central",
                            NotificationManager.IMPORTANCE_MIN
                    );
                    channel.setDescription("Mantiene la conexión para recibir ventas nuevas.");
                    channel.setShowBadge(false);
                    nm.createNotificationChannel(channel);
                }
            }

            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, FOREGROUND_ID, open, pendingFlags);

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, SERVICE_CHANNEL)
                    : new Notification.Builder(this);

            Notification notification = builder
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("ML Central Ventas activo")
                    .setContentText("Iniciando conexión…")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .build();

            startForeground(FOREGROUND_ID, notification);
        } catch (Exception ignored) {
            // El padre vuelve a intentarlo con la notificación normal.
        }
    }
}
