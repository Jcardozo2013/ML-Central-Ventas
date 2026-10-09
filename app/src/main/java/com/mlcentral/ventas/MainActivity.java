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
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private TextView status;
    private TextView modeBadge;
    private TextView historyStatus;
    private TextView stateStatus;
    private TextView backgroundStatus;
    private TextView firebaseUsageStatus;
    private TextView firebaseUsageDetail;
    private TextView firebaseUsageCleanup;
    private Button unread;
    private TextView todaySales;
    private TextView todayProfit;
    private TextView monthSold;
    private TextView monthProfit;
    private TextView paceTitle;
    private TextView paceCurrentPeriod;
    private TextView paceCurrentSales;
    private TextView paceCurrentProfit;
    private TextView pacePreviousPeriod;
    private TextView pacePreviousSales;
    private TextView pacePreviousProfit;
    private TextView paceSalesResult;
    private TextView paceResult;
    private TextView paceNote;
    private TextView monthlyGoalValue;
    private TextView monthlyGoalProgress;
    private TextView pendingBuy;
    private TextView inTransit;
    private TextView pendingRocha;
    private TextView delivered;
    private TextView todayHistory;
    private final Handler handler = new Handler();
    private boolean loginDialogShowing = false;
    private boolean syncStarted = false;

    private final ExecutorService dashboardExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MLCentralDashboard");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean dashboardRefreshRunning = new AtomicBoolean(false);
    private final AtomicBoolean dashboardRefreshPending = new AtomicBoolean(false);

    private final Runnable initialRefresh = new Runnable() {
        @Override public void run() {
            if (!isFinishing()) refresh();
        }
    };

    private final Runnable historyRetry = new Runnable() {
        @Override public void run() {
            if (FirebaseTransport.signedIn(MainActivity.this)) {
                ReadSync.requestHistoryOnceAsync(MainActivity.this);
                StateSync.requestSnapshotAsync(MainActivity.this, false);
                StateSync.flushPendingAsync(MainActivity.this);
                FirebaseUsageMonitor.refreshAsync(MainActivity.this, true);
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
        migrateFirebaseProjectV165();
        migrateFirebaseProjectV149();
        migrateFirebaseAppV150();
        buildUi();
        requestNotificationsIfNeeded();
        ensureFirebaseLogin();
        // La pantalla se dibuja primero. El refresco completo se agenda desde onResume
        // para no bloquear los primeros toques al abrir la app.
    }

    private void migrateFirebaseProjectV165() {
        SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        if (p.getBoolean("firebase_project_v165_migrated", false)) return;

        // v1.65: nuevo proyecto Firebase. Nunca reutilizar una sesión/token/cursor
        // del proyecto anterior; el usuario inicia sesión una sola vez en ML Central 2.
        try { FirebaseAuth.getInstance().signOut(); } catch (Exception ignored) {}
        p.edit()
                .remove("firebase_main_cursor_v120")
                .remove("firebase_rs_cursor_v120")
                .remove("firebase_rest_main_cursor_v157")
                .remove("firebase_rest_rs_cursor_v161")
                .remove("firebase_rest_state_updated_v157")
                .remove("firebase_rest_state_hash_v158")
                .remove("firebase_sdk_connected_v159")
                .putLong("state_request_last_at_v1", 0L)
                .putLong("full_history_request_last_at_v3", 0L)
                .putBoolean("firebase_project_v165_migrated", true)
                .apply();
    }

    private void migrateFirebaseProjectV149() {
        SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        if (p.getBoolean("firebase_project_v149_migrated", false)) return;

        // La base cambió de proyecto. Conservamos historial, ventas leídas,
        // preferencias y configuración local, pero forzamos una sincronización
        // limpia contra el Firebase nuevo.
        p.edit()
                .remove("firebase_main_cursor_v120")
                .remove("firebase_rs_cursor_v120")
                .remove("full_history_active_request_v3")
                .remove("full_history_started_at_v3")
                .remove("full_history_request_id_v3")
                .putInt("full_history_expected_v3", 0)
                .putInt("full_history_received_v3", 0)
                .putBoolean("full_history_restore_done_v3", false)
                .putLong("full_history_request_last_at_v3", 0L)
                .putLong("state_request_last_at_v1", 0L)
                .putBoolean("firebase_project_v149_migrated", true)
                .apply();
    }

    private void migrateFirebaseAppV150() {
        SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        if (p.getBoolean("firebase_app_v150_migrated", false)) return;
        try { FirebaseAuth.getInstance().signOut(); } catch (Exception ignored) {}
        p.edit()
                .remove("firebase_main_cursor_v120")
                .remove("firebase_rs_cursor_v120")
                .putLong("state_request_last_at_v1", 0L)
                .putLong("full_history_request_last_at_v3", 0L)
                .putBoolean("firebase_app_v150_migrated", true)
                .apply();
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
        FirebaseUsageMonitor.refreshAsync(this, true);
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
        backgroundStatus = UiKit.text(this, "Segundo plano: comprobando…", 13, UiKit.MUTED, true);
        backgroundStatus.setPadding(0, UiKit.dp(this, 5), 0, 0);
        connectionCard.addView(backgroundStatus);
        root.addView(connectionCard, UiKit.fullWidth(this, 0, 12));

        LinearLayout firebaseCard = UiKit.card(this);
        TextView firebaseTitle = UiKit.text(this, "Estado Firebase", 18, UiKit.TEXT, true);
        firebaseCard.addView(firebaseTitle);
        firebaseUsageStatus = UiKit.text(this, "⚪ Firebase: esperando primera medición…", 15, UiKit.MUTED, true);
        firebaseUsageStatus.setPadding(0, UiKit.dp(this, 8), 0, 0);
        firebaseCard.addView(firebaseUsageStatus);
        firebaseUsageDetail = UiKit.text(this, "Main: — · RS: — · Movimientos: —", 13, UiKit.MUTED, false);
        firebaseUsageDetail.setPadding(0, UiKit.dp(this, 5), 0, 0);
        firebaseCard.addView(firebaseUsageDetail);
        firebaseUsageCleanup = UiKit.text(this, "Windows todavía no publicó el estado de almacenamiento.", 12, UiKit.MUTED, false);
        firebaseUsageCleanup.setPadding(0, UiKit.dp(this, 5), 0, 0);
        firebaseCard.addView(firebaseUsageCleanup);
        TextView firebaseNote = UiKit.text(this, "Referencia automática del espacio usado por ML Central. Amarillo desde 70% · rojo desde 80%.", 11, UiKit.MUTED, false);
        firebaseNote.setPadding(0, UiKit.dp(this, 6), 0, 0);
        firebaseCard.addView(firebaseNote);
        root.addView(firebaseCard, UiKit.fullWidth(this, 0, 12));

        unread = UiKit.button(this, "0 ventas nuevas");
        unread.setTextSize(16);
        unread.setTypeface(null, android.graphics.Typeface.BOLD);
        unread.setOnClickListener(v -> {
            if (SaleStore.unread(this) > 0) startActivity(new Intent(this, UnreadSalesActivity.class));
        });
        root.addView(unread, UiKit.fullWidth(this, 0, 18));

        // Acceso opcional: no dispara consultas extra a Firebase al abrir Inicio.
        Button dailyWork = UiKit.primaryButton(this, "Mi trabajo de hoy  ›");
        dailyWork.setOnClickListener(v -> startActivity(new Intent(this, DailyWorkActivity.class)));
        root.addView(dailyWork, UiKit.fullWidth(this, 0, 12));

        Button scanLabel = UiKit.button(this, "Escanear etiqueta · Cambiar estado");
        scanLabel.setOnClickListener(v ->
                startActivity(new Intent(this, LabelScanActivity.class)));
        root.addView(scanLabel, UiKit.fullWidth(this, 0, 10));

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

        LinearLayout monthRow = new LinearLayout(this);
        monthRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout monthSoldCard = metricCard("VENDIDO ESTE MES");
        monthSold = metricValue("$0,00");
        monthSoldCard.addView(monthSold);
        monthSoldCard.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        monthRow.addView(monthSoldCard, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout monthProfitCard = metricCard("GANANCIA DEL MES");
        monthProfit = metricValue("$0,00");
        monthProfitCard.addView(monthProfit);
        monthProfitCard.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        LinearLayout.LayoutParams monthSecond = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        monthSecond.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        monthRow.addView(monthProfitCard, monthSecond);
        root.addView(monthRow, UiKit.fullWidth(this, 10, 12));

        LinearLayout paceCard = UiKit.card(this);
        paceTitle = UiKit.text(this, "RITMO DEL MES", 12, UiKit.ACCENT, true);
        paceTitle.setLetterSpacing(0.08f);
        paceCard.addView(paceTitle);

        TextView paceSubtitle = UiKit.text(this,
                "Comparación contra el mismo período del mes anterior.", 13, UiKit.MUTED, false);
        paceSubtitle.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 12));
        paceCard.addView(paceSubtitle);

        LinearLayout paceRow = new LinearLayout(this);
        paceRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout paceCurrentBox = new LinearLayout(this);
        paceCurrentBox.setOrientation(LinearLayout.VERTICAL);
        paceCurrentBox.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 12), UiKit.dp(this, 13), UiKit.dp(this, 12));
        paceCurrentBox.setBackground(UiKit.rounded(UiKit.ACCENT_SOFT, 16, this));
        paceCurrentPeriod = UiKit.text(this, "ESTE MES", 11, UiKit.ACCENT, true);
        paceCurrentPeriod.setLetterSpacing(0.05f);
        paceCurrentBox.addView(paceCurrentPeriod);
        paceCurrentSales = UiKit.text(this, "0 ventas", 18, UiKit.TEXT, true);
        paceCurrentSales.setPadding(0, UiKit.dp(this, 7), 0, 0);
        paceCurrentBox.addView(paceCurrentSales);
        paceCurrentProfit = UiKit.text(this, "$0,00 ganancia", 14, UiKit.GREEN, true);
        paceCurrentProfit.setPadding(0, UiKit.dp(this, 3), 0, 0);
        paceCurrentBox.addView(paceCurrentProfit);
        paceRow.addView(paceCurrentBox,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout pacePreviousBox = new LinearLayout(this);
        pacePreviousBox.setOrientation(LinearLayout.VERTICAL);
        pacePreviousBox.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 12), UiKit.dp(this, 13), UiKit.dp(this, 12));
        pacePreviousBox.setBackground(UiKit.rounded(UiKit.BG, 16, this));
        pacePreviousPeriod = UiKit.text(this, "MES ANTERIOR", 11, UiKit.MUTED, true);
        pacePreviousPeriod.setLetterSpacing(0.05f);
        pacePreviousBox.addView(pacePreviousPeriod);
        pacePreviousSales = UiKit.text(this, "0 ventas", 18, UiKit.TEXT, true);
        pacePreviousSales.setPadding(0, UiKit.dp(this, 7), 0, 0);
        pacePreviousBox.addView(pacePreviousSales);
        pacePreviousProfit = UiKit.text(this, "$0,00 ganancia", 14, UiKit.MUTED, true);
        pacePreviousProfit.setPadding(0, UiKit.dp(this, 3), 0, 0);
        pacePreviousBox.addView(pacePreviousProfit);
        LinearLayout.LayoutParams pacePrevParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pacePrevParams.setMargins(UiKit.dp(this, 9), 0, 0, 0);
        paceRow.addView(pacePreviousBox, pacePrevParams);
        paceCard.addView(paceRow);

        paceSalesResult = UiKit.text(this, "Calculando ventas…", 16, UiKit.TEXT, true);
        paceSalesResult.setPadding(0, UiKit.dp(this, 13), 0, 0);
        paceCard.addView(paceSalesResult);

        paceResult = UiKit.text(this, "Calculando ganancia…", 16, UiKit.TEXT, true);
        paceResult.setPadding(0, UiKit.dp(this, 6), 0, 0);
        paceCard.addView(paceResult);

        paceNote = UiKit.text(this,
                "El porcentaje se calcula únicamente sobre la ganancia.", 12, UiKit.MUTED, false);
        paceNote.setPadding(0, UiKit.dp(this, 5), 0, 0);
        paceCard.addView(paceNote);

        root.addView(paceCard, UiKit.fullWidth(this, 0, 12));

        LinearLayout goalCard = UiKit.card(this);
        TextView goalTitle = UiKit.text(this, "META MENSUAL DE GANANCIA", 12, UiKit.ACCENT, true);
        goalTitle.setLetterSpacing(0.07f);
        goalCard.addView(goalTitle);
        monthlyGoalValue = UiKit.text(this, "Meta no configurada", 20, UiKit.TEXT, true);
        monthlyGoalValue.setPadding(0, UiKit.dp(this, 8), 0, 0);
        goalCard.addView(monthlyGoalValue);
        monthlyGoalProgress = UiKit.text(this, "Tocá para definir la meta de este mes.", 13, UiKit.MUTED, false);
        monthlyGoalProgress.setPadding(0, UiKit.dp(this, 5), 0, 0);
        goalCard.addView(monthlyGoalProgress);
        goalCard.setOnClickListener(v -> showMonthlyGoalDialog());
        root.addView(goalCard, UiKit.fullWidth(this, 0, 18));

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

    private String shortMonthLabel(int year, int month) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, 1);
        String value = new SimpleDateFormat("MMM", Locale.getDefault()).format(c.getTime());
        if (value == null || value.trim().isEmpty()) return "MES";
        return value.trim().toUpperCase(Locale.getDefault());
    }

    private String salesLabel(int count) {
        return count + (count == 1 ? " venta" : " ventas");
    }

    private String monthlyGoalKey(int year, int month) {
        return "monthly_profit_goal_cents_" + year + "_" + month;
    }

    private void showMonthlyGoalDialog() {
        Calendar c = Calendar.getInstance();
        final int year = c.get(Calendar.YEAR);
        final int month = c.get(Calendar.MONTH);
        final String key = monthlyGoalKey(year, month);
        final SharedPreferences prefs = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        long currentCents = prefs.getLong(key, 0L);

        final EditText input = new EditText(this);
        input.setHint("Ej: 15000");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (currentCents > 0L) {
            input.setText(String.format(Locale.US, "%.2f", currentCents / 100.0));
            input.setSelection(input.getText().length());
        }

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Meta de ganancia · " + shortMonthLabel(year, month))
                .setMessage("Se usa únicamente para este mes. Al cambiar de mes empieza una meta nueva.")
                .setView(input)
                .setNeutralButton("Quitar meta", (d, w) -> {
                    prefs.edit().remove(key).apply();
                    refresh();
                })
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", null)
                .create();

        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText().toString().trim().replace(",", ".");
            if (raw.isEmpty()) {
                Toast.makeText(this, "Ingresá una meta", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                double value = Double.parseDouble(raw);
                if (value <= 0.0) throw new NumberFormatException();
                prefs.edit().putLong(key, Math.round(value * 100.0)).apply();
                dlg.dismiss();
                refresh();
            } catch (Exception e) {
                Toast.makeText(this, "Ingresá un valor válido, por ejemplo 15000", Toast.LENGTH_SHORT).show();
            }
        }));
        dlg.show();
    }

    private void refreshMonthlyGoal(double currentProfit) {
        Calendar c = Calendar.getInstance();
        long goalCents = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
                .getLong(monthlyGoalKey(c.get(Calendar.YEAR), c.get(Calendar.MONTH)), 0L);
        if (goalCents <= 0L) {
            monthlyGoalValue.setText("Meta no configurada");
            monthlyGoalValue.setTextColor(UiKit.TEXT);
            monthlyGoalProgress.setText("Tocá para definir la meta de ganancia de este mes.");
            monthlyGoalProgress.setTextColor(UiKit.MUTED);
            return;
        }

        double goal = goalCents / 100.0;
        double pct = goal > 0.0 ? (currentProfit / goal) * 100.0 : 0.0;
        String pctText = String.format(Locale.getDefault(), "%.1f%%", pct);
        monthlyGoalValue.setText(SaleStore.formatMoney(goal) + " · " + pctText);
        monthlyGoalProgress.setText("Llevás " + SaleStore.formatMoney(currentProfit)
                + " de " + SaleStore.formatMoney(goal) + " este mes.");
        boolean reached = pct >= 100.0;
        monthlyGoalValue.setTextColor(reached ? UiKit.GREEN : UiKit.ACCENT);
        monthlyGoalProgress.setTextColor(reached ? UiKit.GREEN : UiKit.MUTED);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
    }

    private String pendingSuffix(int n) {
        if (n <= 0) return "";
        return n == 1 ? "\n1 pendiente" : "\n" + n + " pendientes";
    }

    private static final class DashboardSnapshot {
        int unread;
        boolean signed;
        boolean connected;
        long connectedAt;
        String mode;
        String historyStatus;
        String stateText;
        int pendingStateChanges;
        long heartbeat;
        String firebaseSummary;
        String firebaseDetail;
        String firebaseCleanup;
        int firebaseLevel;
        boolean firebaseStale;
        StateStore.Summary stateSummary;
        MonthlyStats.Stats monthly;
        MonthPaceStats.Pace pace;
        String todayHistory;
    }

    private DashboardSnapshot collectDashboardSnapshot() {
        DashboardSnapshot d = new DashboardSnapshot();

        // Todo lo que puede crecer con los días (historial, estados, ritmo)
        // se calcula fuera del hilo principal.
        d.unread = SaleStore.unread(this);
        d.signed = FirebaseTransport.signedIn(this);
        d.connected = SaleStore.connected(this);
        d.connectedAt = SaleStore.connectedAt(this);

        SharedPreferences p = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE);
        d.mode = p.getString("connection_mode", "");
        d.historyStatus = HistoryRestore.statusText(this);
        d.stateText = StateStore.statusText(this);
        d.pendingStateChanges = StateSync.pendingCount(this);
        d.heartbeat = p.getLong("background_heartbeat_v153", 0L);

        d.firebaseSummary = FirebaseUsageMonitor.summary(this);
        d.firebaseLevel = FirebaseUsageMonitor.level(this);
        d.firebaseStale = FirebaseUsageMonitor.stale(this);
        d.firebaseDetail = FirebaseUsageMonitor.detail(this);
        d.firebaseCleanup = FirebaseUsageMonitor.cleanupText(this);

        if (StateStore.lastSyncAt(this) > 0L) {
            // Una sola lectura del tablero sirve para todos los contadores.
            d.stateSummary = StateStore.summary(this);
        } else {
            // Respaldo sólo para una instalación que todavía no recibió Estados.
            StateStore.Summary fallback = new StateStore.Summary();
            fallback.todaySales = SaleStore.todaySaleCount(this);
            fallback.todayProfit = SaleStore.todayProfit(this);
            fallback.todayPendingProfit = SaleStore.todayPendingProfitCount(this);
            fallback.monthProfit = SaleStore.monthProfit(this);
            fallback.monthPendingProfit = SaleStore.monthPendingProfitCount(this);
            d.stateSummary = fallback;
        }

        Calendar nowMonth = Calendar.getInstance();
        d.monthly = MonthlyStats.get(this,
                nowMonth.get(Calendar.YEAR), nowMonth.get(Calendar.MONTH));
        d.pace = MonthPaceStats.calculate(this);
        // El contador y la lista del Inicio comparten la misma fuente válida.
        // No filtrar el Historial completo: ahí se conservan las canceladas.
        d.todayHistory = StateStore.lastSyncAt(this) > 0L
                ? SaleStore.todayHistoryText(this, d.stateSummary.todayValidOrderIds)
                : SaleStore.todayHistoryText(this);
        return d;
    }

    private void refresh() {
        dashboardRefreshPending.set(true);
        if (!dashboardRefreshRunning.compareAndSet(false, true)) return;
        dashboardExecutor.execute(this::drainDashboardRefresh);
    }

    private void drainDashboardRefresh() {
        try {
            while (dashboardRefreshPending.getAndSet(false)) {
                final DashboardSnapshot snapshot = collectDashboardSnapshot();
                runOnUiThread(() -> {
                    if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    applyDashboardSnapshot(snapshot);
                });
            }
        } catch (Throwable ignored) {
        } finally {
            dashboardRefreshRunning.set(false);
            if (dashboardRefreshPending.get()
                    && dashboardRefreshRunning.compareAndSet(false, true)) {
                dashboardExecutor.execute(this::drainDashboardRefresh);
            }
        }
    }

    private void applyDashboardSnapshot(DashboardSnapshot d) {
        int n = d.unread;
        unread.setText(n == 1 ? "1 venta nueva · VER" : n + " ventas nuevas" + (n > 0 ? " · VER" : ""));
        unread.setEnabled(n > 0);
        unread.setTextColor(n > 0 ? Color.WHITE : UiKit.MUTED);
        unread.setBackground(n > 0 ? UiKit.rounded(UiKit.ACCENT, 14, this)
                : UiKit.roundedStroke(Color.WHITE, 14, UiKit.BORDER, this));

        String time = d.connectedAt > 0
                ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(d.connectedAt))
                : "";
        if (!d.signed) {
            status.setText("● Falta iniciar sesión en Firebase");
            status.setTextColor(UiKit.ORANGE);
            modeBadge.setText("LOGIN");
            modeBadge.setTextColor(UiKit.ORANGE);
            modeBadge.setBackground(UiKit.rounded(UiKit.ORANGE_SOFT, 99, this));
        } else if (d.connected && "firebase".equals(d.mode)) {
            status.setText("● Firebase conectado · " + time);
            status.setTextColor(UiKit.GREEN);
            modeBadge.setText("FIREBASE");
            modeBadge.setTextColor(UiKit.GREEN);
            modeBadge.setBackground(UiKit.rounded(UiKit.GREEN_SOFT, 99, this));
        } else if (d.connected && "firebase-rest".equals(d.mode)) {
            status.setText("● Conectado por respaldo · " + time);
            status.setTextColor(UiKit.GREEN);
            modeBadge.setText("RESPALDO");
            modeBadge.setTextColor(UiKit.GREEN);
            modeBadge.setBackground(UiKit.rounded(UiKit.GREEN_SOFT, 99, this));
        } else if (d.connected) {
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

        historyStatus.setText(d.historyStatus);
        String stateTextValue = d.stateText;
        if (d.pendingStateChanges > 0) {
            stateTextValue += " · " + d.pendingStateChanges
                    + (d.pendingStateChanges == 1 ? " cambio pendiente" : " cambios pendientes");
        }
        stateStatus.setText(stateTextValue);

        long age = d.heartbeat <= 0L
                ? Long.MAX_VALUE
                : Math.max(0L, System.currentTimeMillis() - d.heartbeat);
        if (age < 45000L) {
            backgroundStatus.setText("● Segundo plano ACTIVO · pulso hace "
                    + Math.max(0L, age / 1000L) + " s");
            backgroundStatus.setTextColor(UiKit.GREEN);
        } else {
            String ago = d.heartbeat <= 0L ? "sin pulso"
                    : "hace " + Math.max(1L, age / 60000L) + " min";
            backgroundStatus.setText("● Segundo plano DETENIDO/ATRASADO · " + ago);
            backgroundStatus.setTextColor(UiKit.ORANGE);
        }

        firebaseUsageStatus.setText(d.firebaseSummary);
        firebaseUsageStatus.setTextColor(
                d.firebaseStale ? UiKit.ORANGE
                        : (d.firebaseLevel >= 2 ? Color.rgb(185, 28, 28)
                        : (d.firebaseLevel == 1 ? UiKit.ORANGE : UiKit.GREEN)));
        firebaseUsageDetail.setText(d.firebaseDetail);
        firebaseUsageCleanup.setText(d.firebaseCleanup);

        StateStore.Summary summary = d.stateSummary == null
                ? new StateStore.Summary() : d.stateSummary;
        todaySales.setText(String.valueOf(summary.todaySales));
        todayProfit.setText(SaleStore.formatMoney(summary.todayProfit)
                + pendingSuffix(summary.todayPendingProfit));

        MonthlyStats.Stats monthly = d.monthly;
        if (monthly != null) {
            monthSold.setText(SaleStore.formatMoney(monthly.soldTotal)
                    + (monthly.missingSaleAmount > 0
                    ? "\n" + monthly.missingSaleAmount + " sin importe" : ""));
        }
        double currentMonthProfit = summary.monthProfit;
        monthProfit.setText(SaleStore.formatMoney(currentMonthProfit)
                + pendingSuffix(summary.monthPendingProfit));
        refreshMonthlyGoal(currentMonthProfit);

        MonthPaceStats.Pace pace = d.pace;
        if (pace != null) {
            paceTitle.setText("RITMO DEL MES · HASTA EL DÍA " + pace.compareDay);
            paceCurrentPeriod.setText(shortMonthLabel(pace.currentYear, pace.currentMonth));
            pacePreviousPeriod.setText(shortMonthLabel(pace.previousYear, pace.previousMonth));
            paceCurrentSales.setText(salesLabel(pace.currentSales));
            pacePreviousSales.setText(salesLabel(pace.previousSales));
            paceCurrentProfit.setText(SaleStore.formatMoney(pace.currentProfit) + " ganancia");
            pacePreviousProfit.setText(SaleStore.formatMoney(pace.previousProfit) + " ganancia");

            if (pace.previousSales > 0) {
                double salesPct = ((pace.currentSales - pace.previousSales) * 100.0) / pace.previousSales;
                String salesPctText = String.format(Locale.getDefault(), "%.1f%%", Math.abs(salesPct));
                if (salesPct > 0.05) {
                    paceSalesResult.setText("↑ Vas " + salesPctText + " mejor en ventas");
                    paceSalesResult.setTextColor(UiKit.GREEN);
                } else if (salesPct < -0.05) {
                    paceSalesResult.setText("↓ Vas " + salesPctText + " peor en ventas");
                    paceSalesResult.setTextColor(UiKit.RED);
                } else {
                    paceSalesResult.setText("≈ Vas prácticamente igual en ventas · " + salesPctText);
                    paceSalesResult.setTextColor(UiKit.MUTED);
                }
            } else if (pace.currentSales > 0) {
                paceSalesResult.setText("↑ Más ventas, pero sin % comparable");
                paceSalesResult.setTextColor(UiKit.GREEN);
            } else {
                paceSalesResult.setText("Sin ventas comparables todavía");
                paceSalesResult.setTextColor(UiKit.MUTED);
            }

            if (!pace.complete()) {
                paceResult.setText("⚠ Comparación parcial");
                paceResult.setTextColor(UiKit.ORANGE);
                int missing = pace.currentMissingProfit + pace.previousMissingProfit;
                paceNote.setText("Hay " + missing + (missing == 1
                        ? " venta sin ganancia confirmada. No muestro un % engañoso."
                        : " ventas sin ganancia confirmada. No muestro un % engañoso."));
                paceNote.setTextColor(UiKit.ORANGE);
            } else if (!pace.comparable) {
                if (pace.currentProfit > pace.previousProfit && pace.currentProfit > 0.0) {
                    paceResult.setText("↑ Vas mejor, pero sin % comparable");
                    paceResult.setTextColor(UiKit.GREEN);
                } else if (pace.currentProfit < pace.previousProfit) {
                    paceResult.setText("↓ Vas por debajo, pero sin % comparable");
                    paceResult.setTextColor(UiKit.RED);
                } else {
                    paceResult.setText("Sin variación comparable");
                    paceResult.setTextColor(UiKit.MUTED);
                }
                paceNote.setText("El mes anterior no tiene una ganancia positiva para calcular un porcentaje válido.");
                paceNote.setTextColor(UiKit.MUTED);
            } else {
                double pct = pace.percentChange;
                String pctText = String.format(Locale.getDefault(), "%.1f%%", Math.abs(pct));
                if (pct > 0.05) {
                    paceResult.setText("↑ Vas " + pctText + " mejor en ganancia");
                    paceResult.setTextColor(UiKit.GREEN);
                } else if (pct < -0.05) {
                    paceResult.setText("↓ Vas " + pctText + " peor en ganancia");
                    paceResult.setTextColor(UiKit.RED);
                } else {
                    paceResult.setText("≈ Vas prácticamente igual · " + pctText);
                    paceResult.setTextColor(UiKit.MUTED);
                }
                paceNote.setText("Compara días 1–" + pace.compareDay
                        + " · porcentaje calculado únicamente sobre la ganancia.");
                paceNote.setTextColor(UiKit.MUTED);
            }
        }

        pendingBuy.setText(String.valueOf(summary.purchasePending));
        inTransit.setText(String.valueOf(summary.receivePending));
        pendingRocha.setText(String.valueOf(summary.pendingRocha));
        delivered.setText(String.valueOf(summary.delivered));
        todayHistory.setText(d.todayHistory == null ? "" : d.todayHistory);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter("com.mlcentral.ventas.SALE_RECEIVED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(saleReceiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(saleReceiver, f);
        handler.removeCallbacks(historyRetry);
        // startAfterLogin ya hace la sincronización inicial. Evitamos repetir
        // historia/estados apenas abre y dejamos el mantenimiento para después.
        handler.postDelayed(historyRetry, 120000L);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(historyRetry);
        handler.removeCallbacks(initialRefresh);
        try { unregisterReceiver(saleReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (FirebaseTransport.signedIn(this)) {
            // startAfterLogin ya controla la sincronización inicial y evita
            // repetir historia/estados varias veces durante el arranque.
            startAfterLogin();
        } else {
            ensureFirebaseLogin();
        }
        handler.removeCallbacks(initialRefresh);
        handler.postDelayed(initialRefresh, 280L);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(historyRetry);
        handler.removeCallbacks(initialRefresh);
        try { dashboardExecutor.shutdownNow(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
