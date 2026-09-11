package com.mlcentral.ventas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class UiKit {
    public static final int BG = Color.rgb(245, 247, 251);
    public static final int CARD = Color.WHITE;
    public static final int TEXT = Color.rgb(31, 41, 55);
    public static final int MUTED = Color.rgb(107, 114, 128);
    public static final int ACCENT = Color.rgb(37, 99, 235);
    public static final int ACCENT_SOFT = Color.rgb(235, 242, 255);
    public static final int GREEN = Color.rgb(21, 128, 61);
    public static final int GREEN_SOFT = Color.rgb(232, 247, 237);
    public static final int ORANGE = Color.rgb(180, 83, 9);
    public static final int ORANGE_SOFT = Color.rgb(255, 247, 237);
    public static final int BORDER = Color.rgb(226, 232, 240);

    private UiKit() {}

    public static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static TextView text(Activity a, String value, float size, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    public static GradientDrawable rounded(int color, int radiusDp, Activity a) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(a, radiusDp));
        return d;
    }

    public static GradientDrawable roundedStroke(int color, int radiusDp, int strokeColor, Activity a) {
        GradientDrawable d = rounded(color, radiusDp, a);
        d.setStroke(dp(a, 1), strokeColor);
        return d;
    }

    public static LinearLayout card(Activity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(a, 16), dp(a, 15), dp(a, 16), dp(a, 15));
        c.setBackground(roundedStroke(CARD, 16, BORDER, a));
        return c;
    }

    public static Button button(Activity a, String label) {
        Button b = new Button(a);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(roundedStroke(CARD, 12, BORDER, a));
        b.setMinHeight(dp(a, 48));
        return b;
    }

    public static void setSelected(Button b, Activity a, boolean selected) {
        b.setTextColor(selected ? Color.WHITE : TEXT);
        b.setBackground(selected ? rounded(ACCENT, 12, a) : roundedStroke(CARD, 12, BORDER, a));
    }

    public static TextView pill(Activity a, String label, int textColor, int bgColor) {
        TextView t = text(a, label, 12, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 10), dp(a, 5), dp(a, 10), dp(a, 5));
        t.setBackground(rounded(bgColor, 20, a));
        return t;
    }

    public static LinearLayout bottomNav(Activity a, int selected) {
        LinearLayout nav = new LinearLayout(a);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 10));
        nav.setBackground(roundedStroke(Color.WHITE, 0, BORDER, a));

        int selectedIndex = selected;
        if (a instanceof MainActivity) selectedIndex = 0;
        else if (a instanceof StatusActivity) selectedIndex = 1;
        else if (a instanceof HistoryActivity) selectedIndex = 2;
        else if (a instanceof SettingsActivity) selectedIndex = 3;

        final int selectedFinal = selectedIndex;
        String[] labels = {"Inicio", "Estados", "Historial", "Config."};
        Class<?>[] screens = {MainActivity.class, StatusActivity.class, HistoryActivity.class, SettingsActivity.class};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button b = new Button(a);
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setMinHeight(dp(a, 48));
            b.setTextColor(i == selectedFinal ? ACCENT : MUTED);
            b.setTypeface(null, i == selectedFinal ? Typeface.BOLD : Typeface.NORMAL);
            b.setBackground(i == selectedFinal ? rounded(ACCENT_SOFT, 12, a) : rounded(Color.TRANSPARENT, 12, a));
            b.setEnabled(i != selectedFinal);
            b.setOnClickListener(v -> {
                Intent intent = new Intent(a, screens[index]);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                a.startActivity(intent);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) p.setMargins(dp(a, 4), 0, 0, 0);
            nav.addView(b, p);
        }
        return nav;
    }

    public static LinearLayout.LayoutParams fullWidth(Activity a, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(a, top), 0, dp(a, bottom));
        return p;
    }

    public static void visible(View v, boolean yes) {
        v.setVisibility(yes ? View.VISIBLE : View.GONE);
    }
}
