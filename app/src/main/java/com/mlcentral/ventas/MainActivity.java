package com.mlcentral.ventas;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView status;
    private TextView modeBadge;
    private TextView historyStatus;
    private TextView stateStatus;
    private Button unread;
    private TextView todaySales;
    private TextView todayProfit;
    private TextView monthProfit;
    private TextView pendingBuy;
    private TextView inTransit;
    private TextView pendingRocha;
    private TextView delivered;
    private TextView todayHistory;
    private final Handler handler = new Handler();
    private boolean loginDialogShowing = false;
    private boolean syncStarted = false;

    private final Runnable historyRetry = new Runnable() {
        @Override public void run() {
            if (FirebaseTransport.signedIn(MainActivity.this)) {
                ReadSync.requestHistoryOnceAsync(MainActivity.this);
                StateSync.requestSnapshotAsync(MainActivity.this, false);
                StateSync.flushPendingAsync(MainActivity.this);
            }
            refresh();
            handler.postDelayed(this, 120000L);
        }
    };

    private final BroadcastReceiver saleReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FirebaseConfig.ensureInitialized(this);
        buildUi();
        requestNotificationsIfNeeded();
        ensureFirebaseLogin();
        refresh();
    }

    private void ensureFirebaseLogin() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && FirebaseConfig.EXPECTED_UID.equals(user.getUid())) {
            startAfterLogin();
            return;
        }
        if (user != null) FirebaseAuth.getInstance().signOut();
        showFirebaseLogin();
    }

    private void showFirebaseLogin() {
        if (loginDialogShowing || isFinishing()) return;
        loginDialogShowing = true;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 18);
        box.setPadding(pad, UiKit.dp(this, 8), pad, 0);

        EditText email = new EditText(this);
        email.setHint("Correo de Firebase");
        email.setSingleLine(true);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).getString("firebase_login_email", ""));
        box.addView(email, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText password = new EditText(this);
        password.setHint("Contraseña de Firebase");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        box.addView(password, pp);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Conectar ML Central con Firebase")
                .setMessage("Ingresá el mismo correo y contraseña que configuraste en Firebase. Se guarda la sesión, no la contraseña.")
                .setView(box)
                .setNegativeButton("Después", null)
                .setPositiveButton("Conectar", null)
                .create();

        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String mail = email.getText().toString().trim();
            String pass = password.getText().toString();
            if (mail.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Ingresá correo y contraseña", Toast.LENGTH_SHORT).show();
                return;
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            FirebaseAuth.getInstance().signInWithEmailAndPassword(mail, pass).addOnCompleteListener(this, task -> {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if (!task.isSuccessful()) {
                    String msg = task.getException() == null ? "No se pudo iniciar sesión" : task.getException().getMessage();
                    Toast.makeText(this, "Firebase: " + msg, Toast.LENGTH_LONG).show();
                    return;
                }
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user == null || !FirebaseConfig.EXPECTED_UID.equals(user.getUid())) {
                    FirebaseAuth.getInstance().signOut();
                    Toast.makeText(this, "Ese usuario no está autorizado para ML Central", Toast.LENGTH_LONG).show();
                    return;
                }
                getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit().putString("firebase_login_email", mail).apply();
                loginDialogShowing = false;
                dlg.dismiss();
                startAfterLogin();
                refresh();
            });
        }));
        dlg.setOnDismissListener(x -> {
            loginDialogShowing = false;
            refresh();
        });
        dlg.show();
    }

    private void startAfterLogin() {
        if (!FirebaseTransport.signedIn(this)) return;
        startListener();
        if (!syncStarted) {
            syncStarted = true;
            ReadSync.requestHistoryOnceAsync(this);
            StateSync.requestSnapshotAsync(this, true);
            StateSync.flushPendingAsync(this);
        }
    }

    private void buildUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.BG);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 22), UiKit.dp(this, 18), UiKit.dp(this, 24));
        scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = UiKit.text(this, "ML Central Ventas", 28, UiKit.TEXT, true);
        root.addView(title);
        TextView subtitle = UiKit.text(this, "Ventas, ganancias y estados sincronizados con tu PC", 14, UiKit.MUTED, false);
        subtitle.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 14));
        root.addView(subtitle);

        LinearLayout connectionCard = UiKit.card(this);
        LinearLayout connectionRow = new LinearLayout(this);
        connectionRow.setOrientation(LinearLayout.HORIZONTAL);
        connectionRow.setGravity(Gravity.CENTER_VERTICAL);
        status = UiKit.text(this, "● Conectando…", 15, UiKit.ORANGE, true);
        connectionRow.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        modeBadge = UiKit.pill(this, "SIN ENLACE", UiKit.ORANGE, UiKit.ORANGE_SOFT);
        connectionRow.addView(modeBadge);
        connectionCard.addView(connectionRow);
        historyStatus = UiKit.text(this, "Historial: esperando sincronización con Windows…", 13, UiKit.MUTED, false);
        historyStatus.setPadding(0, UiKit.dp(this, 8), 0, 0);
        connectionCard.addView(historyStatus);
        stateStatus = UiKit.text(this, "Estados PC: esperando sincronización…", 13, UiKit.MUTED, false);
        stateStatus.setPadding(0, UiKit.dp(this, 4), 0, 0);
        connectionCard.addView(stateStatus);
        root.addView(connectionCard, UiKit.fullWidth(this, 0, 12));

        unread = UiKit.button(this, "0 ventas nuevas");
        unread.setTextSize(16);
        unread.setTypeface(null, android.graphics.Typeface.BOLD);
        unread.setOnClickListener(v -> {
            if (SaleStore.unread(this) > 0) startActivity(new Intent(this, UnreadSalesActivity.class));
        });
        root.addView(unread, UiKit.fullWidth(this, 0, 18));

        TextView section = UiKit.text(this, "Resumen", 20, UiKit.TEXT, true);
        root.addView(section, UiKit.fullWidth(this, 0, 10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout salesCard = metricCard("VENTAS HOY");
        todaySales = metricValue("0");
        salesCard.addView(todaySales);
        row.addView(salesCard, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout profitCard = metricCard("GANANCIA HOY");
        todayProfit = metricValue("$0,00");
        profitCard.addView(todayProfit);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        second.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        row.addView(profitCard, second);
        root.addView(row);

        LinearLayout monthCard = metricCard("GANANCIA DEL MES");
        monthProfit = metricValue("$0,00");
        monthCard.addView(monthProfit);
        root.addView(monthCard, UiKit.fullWidth(this, 10, 18));

        LinearLayout opHeading = new LinearLayout(this);
        opHeading.setOrientation(LinearLayout.HORIZONTAL);
        opHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView opTitle = UiKit.text(this, "Operativa", 20, UiKit.TEXT, true);
        opHeading.addView(opTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button seeStates = UiKit.button(this, "Ver los 9 estados");
        seeStates.setTextSize(12);
        seeStates.setOnClickListener(v -> startActivity(new Intent(this, StatusActivity.class)));
        opHeading.addView(seeStates, new LinearLayout.LayoutParams(UiKit.dp(this, 135), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(opHeading, UiKit.fullWidth(this, 0, 9));

        LinearLayout opRow1 = new LinearLayout(this);
        opRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout pendingCard = stateCard("FALTA COMPRAR", "purchase_pending");
        pendingBuy = stateValue("0");
        pendingCard.addView(pendingBuy);
        opRow1.addView(pendingCard, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout transitCard = stateCard("EN CAMINO BR", "receive_pending");
        inTransit = stateValue("0");
        transitCard.addView(inTransit);
        LinearLayout.LayoutParams op2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        op2.setMargins(UiKit.dp(this, 8), 0, 0, 0);
        opRow1.addView(transitCard, op2);
        root.addView(opRow1);

        LinearLayout opRow2 = new LinearLayout(this);
        opRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout rochaCard = stateCard("PENDIENTE ROCHA", "pending_rocha");
        pendingRocha = stateValue("0");
        rochaCard.addView(pendingRocha);
        opRow2.addView(rochaCard, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout deliveredCard = stateCard("ENTREGADAS", "delivered");
        delivered = stateValue("0");
        deliveredCard.addView(delivered);
        LinearLayout.LayoutParams op4 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        op4.setMargins(UiKit.dp(this, 8), 0, 0, 0);
        opRow2.addView(deliveredCard, op4);
        root.addView(opRow2, UiKit.fullWidth(this, 8, 18));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView recentTitle = UiKit.text(this, "Ventas de hoy", 20, UiKit.TEXT, true);
        heading.addView(recentTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button seeAll = UiKit.button(this, "Ver historial");
        seeAll.setTextSize(13);
        seeAll.setMinHeight(UiKit.dp(this, 40));
        seeAll.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        heading.addView(seeAll, new LinearLayout.LayoutParams(UiKit.dp(this, 122), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(heading, UiKit.fullWidth(this, 0, 10));

        LinearLayout recentCard = UiKit.card(this);
        todayHistory = UiKit.text(this, "Todavía no hay ventas recibidas hoy.", 14, UiKit.TEXT, false);
        todayHistory.setTextIsSelectable(true);
        recentCard.addView(todayHistory);
        root.addView(recentCard);

        shell.addView(UiKit.bottomNav(this, 0), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(shell);
    }

    private LinearLayout metricCard(String label) {
        LinearLayout card = UiKit.card(this);
        TextView l = UiKit.text(this, label, 12, UiKit.MUTED, true);
        l.setLetterSpacing(0.05f);
        card.addView(l);
        return card;
    }

    private TextView metricValue(String value) {
        TextView t = UiKit.text(this, value, 23, UiKit.TEXT, true);
        t.setPadding(0, UiKit.dp(this, 7), 0, 0);
        return t;
    }

    private LinearLayout stateCard(String label, String stage) {
        LinearLayout card = metricCard(label);
        card.setOnClickListener(v -> openStage(stage));
        return card;
    }

    private TextView stateValue(String value) {
        TextView t = UiKit.text(this, value, 25, UiKit.ACCENT, true);
        t.setPadding(0, UiKit.dp(this, 6), 0, 0);
        return t;
    }

    private void openStage(String stage) {
        Intent i = new Intent(this, StatusActivity.class);
        i.putExtra("stage", stage);
        startActivity(i);
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 500);
        }
    }

    private void startListener() {
        Intent i = new Intent(this, SaleListenerService.class);
        try {
            stopService(i);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
    }

    private String pendingSuffix(int n) {
        if (n <= 0) return "";
        return n == 1 ? "\n1 pendiente" : "\n" + n + " pendientes";
    }

    private void refresh() {
        int n = SaleStore.unread(this);
        unread.setText(n == 1 ? "1 venta nueva · VER" : n + " ventas nuevas" + (n > 0 ? " · VER" : ""));
        unread.setEnabled(n > 0);
        unread.setTextColor(n > 0 ? Color.WHITE : UiKit.MUTED);
        unread.setBackground(n > 0 ? UiKit.rounded(UiKit.ACCENT, 14, this) : UiKit.roundedStroke(Color.WHITE, 14, UiKit.BORDER, this));

        boolean signed = FirebaseTransport.signedIn(this);
        boolean c = SaleStore.connected(this);
        long at = SaleStore.connectedAt(this);
        String time = at > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(at)) : "";
        SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        String mode = p.getString("connection_mode", "");
        if (!signed) {
            status.setText("● Falta iniciar sesión en Firebase");
            status.setTextColor(UiKit.ORANGE);
            modeBadge.setText("LOGIN");
            modeBadge.setTextColor(UiKit.ORANGE);
            modeBadge.setBackground(UiKit.rounded(UiKit.ORANGE_SOFT, 99, this));
        } else if (c && "firebase".equals(mode)) {
            status.setText("● Firebase conectado · " + time);
            status.setTextColor(UiKit.GREEN);
            modeBadge.setText("FIREBASE");
            modeBadge.setTextColor(UiKit.GREEN);
            modeBadge.setBackground(UiKit.rounded(UiKit.GREEN_SOFT, 99, this));
        } else if (c) {
            status.setText("● Conectado · " + time);
            status.setTextColor(UiKit.GREEN);
            modeBadge.setText("EN VIVO");
            modeBadge.setTextColor(UiKit.GREEN);
            modeBadge.setBackground(UiKit.rounded(UiKit.GREEN_SOFT, 99, this));
        } else {
            status.setText("● Reconectando…");
            status.setTextColor(UiKit.ORANGE);
            modeBadge.setText("SIN ENLACE");
            modeBadge.setTextColor(UiKit.ORANGE);
            modeBadge.setBackground(UiKit.rounded(UiKit.ORANGE_SOFT, 99, this));
        }

        historyStatus.setText(HistoryRestore.statusText(this));
        String stateText = StateStore.statusText(this);
        int pending = StateSync.pendingCount(this);
        if (pending > 0) stateText += " · " + pending + (pending == 1 ? " cambio pendiente" : " cambios pendientes");
        stateStatus.setText(stateText);

        todaySales.setText(String.valueOf(SaleStore.todaySaleCount(this)));
        todayProfit.setText(SaleStore.formatMoney(SaleStore.todayProfit(this)) + pendingSuffix(SaleStore.todayPendingProfitCount(this)));
        monthProfit.setText(SaleStore.formatMoney(SaleStore.monthProfit(this)) + pendingSuffix(SaleStore.monthPendingProfitCount(this)));
        pendingBuy.setText(String.valueOf(StateStore.countStage(this, "purchase_pending")));
        inTransit.setText(String.valueOf(StateStore.countStage(this, "receive_pending")));
        pendingRocha.setText(String.valueOf(StateStore.countStage(this, "pending_rocha")));
        delivered.setText(String.valueOf(StateStore.countStage(this, "delivered")));
        todayHistory.setText(SaleStore.todayHistoryText(this));
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter("com.mlcentral.ventas.SALE_RECEIVED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(saleReceiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(saleReceiver, f);
        handler.removeCallbacks(historyRetry);
        handler.post(historyRetry);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(historyRetry);
        try { unregisterReceiver(saleReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (FirebaseTransport.signedIn(this)) {
            startAfterLogin();
            ReadSync.requestHistoryOnceAsync(this);
            StateSync.requestSnapshotAsync(this, false);
            StateSync.flushPendingAsync(this);
        } else {
            ensureFirebaseLogin();
        }
        refresh();
    }
}
