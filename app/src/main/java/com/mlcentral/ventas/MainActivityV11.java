package com.mlcentral.ventas;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public class MainActivityV11 extends MainActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addToneButton();
    }

    private void addToneButton() {
        try {
            View content = findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            View first = ((ViewGroup) content).getChildAt(0);
            if (!(first instanceof ScrollView)) return;
            View inner = ((ScrollView) first).getChildAt(0);
            if (!(inner instanceof LinearLayout)) return;

            LinearLayout root = (LinearLayout) inner;
            Button tone = new Button(this);
            tone.setText("Elegir tono de notificación");
            tone.setOnClickListener(v -> openToneSettings());
            int index = Math.min(6, root.getChildCount());
            root.addView(tone, index, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } catch (Exception ignored) {}
    }

    private void openToneSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            i.putExtra(Settings.EXTRA_CHANNEL_ID, SaleListenerService.SALES_CHANNEL);
            startActivity(i);
        } catch (Exception e) {
            Intent fallback = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            fallback.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(fallback);
        }
    }
}
