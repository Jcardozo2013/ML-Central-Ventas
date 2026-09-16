package com.mlcentral.ventas;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

/**
 * Complemento liviano de v1.41. No toca la conexión Firebase del servicio.
 * Observa el tablero ya sincronizado para registrar transiciones a ENTREGADA,
 * refuerza el aviso visual y agrega la tarjeta ENTREGADAS HOY en Inicio.
 */
public class DeliveryApp extends Application implements Application.ActivityLifecycleCallbacks {
    private static final String CHANNEL = "ml_delivery_visual_v141";
    private static final String CARD_TAG = "mlc_delivered_today_card_v141";
    private static final String VALUE_TAG = "mlc_delivered_today_value_v141";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<MainActivity> currentMain = new WeakReference<>(null);

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            reconcileAsync();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createDeliveryChannel();
        registerActivityLifecycleCallbacks(this);
        IntentFilter filter = new IntentFilter("com.mlcentral.ventas.SALE_RECEIVED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(refreshReceiver, filter);
    }

    private void reconcileAsync() {
        new Thread(() -> {
            List<DeliveryStore.Delivery> fresh = Collections.emptyList();
            try {
                if (StateStore.total(this) > 0) fresh = DeliveryStore.reconcile(this);
            } catch (Exception ignored) {}
            final List<DeliveryStore.Delivery> ready = fresh;
            main.post(() -> {
                for (DeliveryStore.Delivery d : ready) showDeliveryNotification(d);
                MainActivity a = currentMain.get();
                if (a != null && !a.isFinishing()) installOrRefreshCard(a);
            });
        }, "MLC-Delivery-Reconcile").start();
    }

    private void createDeliveryChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Paquetes entregados", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Aviso visible cuando una venta pasa a entregada.");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 220, 120, 220});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    private void showDeliveryNotification(DeliveryStore.Delivery d) {
        if (d == null || d.id == null || d.id.trim().isEmpty()) return;
        String id = d.id.trim();
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("📦 PAQUETE ENTREGADO — ML CENTRAL")
                .setContentText(d.product == null || d.product.trim().isEmpty() ? "Venta entregada" : d.product.trim())
                .setStyle(new Notification.BigTextStyle().bigText(d.detail()))
                .setContentIntent(openAppIntent(("delivery:" + id).hashCode()))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setWhen(d.time > 0L ? d.time * 1000L : System.currentTimeMillis())
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        }
        int notificationId = 2000000 + Math.abs(id.hashCode() % 900000);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(notificationId, b.build());
    }

    private PendingIntent openAppIntent(int requestCode) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, requestCode, i, flags);
    }

    private void installOrRefreshCard(MainActivity a) {
        if (a == null || a.isFinishing()) return;
        View content = a.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        View existing = findByTag(content, CARD_TAG);
        if (existing == null) {
            TextView todayHeading = findText(content, "Ventas de hoy");
            if (todayHeading == null || !(todayHeading.getParent() instanceof View)) return;
            View headingRow = (View) todayHeading.getParent();
            if (!(headingRow.getParent() instanceof LinearLayout)) return;
            LinearLayout root = (LinearLayout) headingRow.getParent();
            int position = root.indexOfChild(headingRow);
            if (position < 0) return;

            LinearLayout card = UiKit.card(a);
            card.setTag(CARD_TAG);
            TextView label = UiKit.text(a, "ENTREGADAS HOY", 12, UiKit.MUTED, true);
            label.setLetterSpacing(0.05f);
            card.addView(label);

            TextView value = UiKit.text(a, "0", 25, UiKit.GREEN, true);
            value.setTag(VALUE_TAG);
            value.setPadding(0, UiKit.dp(a, 6), 0, 0);
            card.addView(value);

            TextView hint = UiKit.text(a, "Tocá para ver cuáles se entregaron hoy", 12, UiKit.MUTED, false);
            hint.setPadding(0, UiKit.dp(a, 5), 0, 0);
            card.addView(hint);

            card.setOnClickListener(v -> showTodayDialog(a));
            root.addView(card, position, UiKit.fullWidth(a, 0, 18));
            existing = card;
        }

        View valueView = findByTag(existing, VALUE_TAG);
        if (valueView instanceof TextView) ((TextView) valueView).setText(String.valueOf(DeliveryStore.todayCount(a)));
    }

    private void showTodayDialog(MainActivity a) {
        int count = DeliveryStore.todayCount(a);
        new AlertDialog.Builder(a)
                .setTitle(count == 1 ? "1 entrega hoy" : count + " entregas hoy")
                .setMessage(DeliveryStore.todayText(a))
                .setPositiveButton("Cerrar", null)
                .show();
    }

    private View findByTag(View root, String tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findByTag(g.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private TextView findText(View root, String exact) {
        if (root instanceof TextView && exact.equals(((TextView) root).getText().toString())) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = findText(g.getChildAt(i), exact);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Override public void onActivityResumed(Activity activity) {
        if (activity instanceof MainActivity) {
            MainActivity a = (MainActivity) activity;
            currentMain = new WeakReference<>(a);
            if (StateStore.total(this) > 0) reconcileAsync();
            else main.post(() -> installOrRefreshCard(a));
        }
    }

    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {
        MainActivity a = currentMain.get();
        if (a == activity) currentMain = new WeakReference<>(null);
    }
}
