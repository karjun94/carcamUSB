package com.karjun.dmaxlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.karjun.dmaxcam.core.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** CarHome options: what happens when the car starts, navigation card, parking mode. */
public class HomeSettingsActivity extends Activity {
    // Styles and drawables shared with the camera settings screen (from :camcore).
    private static final int STYLE_LABEL = com.karjun.dmaxcam.core.R.style.SectionLabel;
    private static final int STYLE_TITLE = com.karjun.dmaxcam.core.R.style.RowTitle;
    private static final int STYLE_HINT = com.karjun.dmaxcam.core.R.style.RowHint;

    private SharedPreferences mPrefs;
    private TextView mStartAppValue;
    private TextView mShowBelowValue, mHideAboveValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = HomePrefs.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("CarHome settings");
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(com.karjun.dmaxcam.core.R.color.text_primary));
        root.addView(title);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setBaselineAligned(false);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.topMargin = dp(20);
        root.addView(columns, colLp);

        // --- When the car starts ---
        LinearLayout start = card("When the car starts");
        switchRow(start, "Resume music", "Carry on with the last music app",
                "start_resume_music", HomePrefs.resumeMusic(this));
        mStartAppValue = valueRow(start, "Open an app", v -> chooseStartApp());
        TextView startNote = new TextView(this, null, 0, STYLE_HINT);
        startNote.setText("For example Waze. Opens a few seconds after start-up.");
        start.addView(startNote);
        columns.addView(start, column(0));

        // --- Driving ---
        LinearLayout driving = card("Driving");
        switchRow(driving, "Navigation card", "Next turn from Waze or Google Maps on the clock card",
                "nav_card", HomePrefs.navCard(this));
        switchRow(driving, "Parking mode", "Front camera opens by itself when you slow down",
                "parking_mode", HomePrefs.parkingMode(this));
        mShowBelowValue = stepperRow(driving, "Open camera below", "parking_show_below",
                HomePrefs.parkingShowBelow(this), 2, 30);
        mHideAboveValue = stepperRow(driving, "Close again above", "parking_hide_above",
                HomePrefs.parkingHideAbove(this), 5, 60);
        columns.addView(driving, column(dp(12)));

        Button camera = new Button(this);
        camera.setText("Camera settings (steering button, guide lines, mirror)");
        camera.setAllCaps(false);
        camera.setTextSize(17);
        camera.setTextColor(getColor(com.karjun.dmaxcam.core.R.color.accent));
        camera.setBackgroundResource(com.karjun.dmaxcam.core.R.drawable.button_outline);
        camera.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams camLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        camLp.topMargin = dp(16);
        root.addView(camera, camLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(com.karjun.dmaxcam.core.R.color.bg));
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    private void refresh() {
        String pkg = HomePrefs.startApp(this);
        mStartAppValue.setText(pkg == null ? "None" : appLabel(pkg));
        mShowBelowValue.setText(HomePrefs.parkingShowBelow(this) + " km/h");
        mHideAboveValue.setText(HomePrefs.parkingHideAbove(this) + " km/h");
    }

    // ---- Building blocks --------------------------------------------------------------------

    private LinearLayout card(String label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(com.karjun.dmaxcam.core.R.drawable.card_bg);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        TextView l = new TextView(this, null, 0, STYLE_LABEL);
        l.setText(label);
        card.addView(l);
        return card;
    }

    private LinearLayout.LayoutParams column(int startMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(startMargin);
        return lp;
    }

    private LinearLayout row(LinearLayout parent, String title, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this, null, 0, STYLE_TITLE);
        t.setText(title);
        texts.addView(t);
        if (hint != null) {
            TextView h = new TextView(this, null, 0, STYLE_HINT);
            h.setText(hint);
            texts.addView(h);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        return row;
    }

    private void switchRow(LinearLayout parent, String title, String hint, String key, boolean value) {
        LinearLayout row = row(parent, title, hint);
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setClickable(false);
        row.addView(sw);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(v -> {
            sw.toggle();
            mPrefs.edit().putBoolean(key, sw.isChecked()).apply();
        });
    }

    private TextView valueRow(LinearLayout parent, String title, View.OnClickListener onClick) {
        LinearLayout row = row(parent, title, null);
        TextView value = new TextView(this, null, 0, STYLE_TITLE);
        value.setTextColor(getColor(com.karjun.dmaxcam.core.R.color.accent));
        row.addView(value);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(onClick);
        return value;
    }

    /** "Title   [-]  8 km/h  [+]" */
    private TextView stepperRow(LinearLayout parent, String title, String key, int value, int min, int max) {
        LinearLayout row = row(parent, title, null);
        TextView valueView = new TextView(this, null, 0, STYLE_TITLE);
        valueView.setGravity(Gravity.CENTER);
        Button minus = stepButton("−");
        Button plus = stepButton("+");
        minus.setOnClickListener(v -> step(key, -1, min, max));
        plus.setOnClickListener(v -> step(key, +1, min, max));
        row.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(valueView, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return valueView;
    }

    private Button stepButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setPadding(0, 0, 0, 0);
        b.setTextColor(getColor(com.karjun.dmaxcam.core.R.color.accent));
        b.setBackgroundResource(com.karjun.dmaxcam.core.R.drawable.button_outline);
        return b;
    }

    private void step(String key, int delta, int min, int max) {
        int show = HomePrefs.parkingShowBelow(this);
        int hide = HomePrefs.parkingHideAbove(this);
        int value = Math.max(min, Math.min(max, mPrefs.getInt(key, key.equals("parking_show_below") ? show : hide) + delta));
        // Keep a gap between "open below" and "close above" so the camera does not flicker.
        if (key.equals("parking_show_below") && value > hide - 3) return;
        if (key.equals("parking_hide_above") && value < show + 3) return;
        mPrefs.edit().putInt(key, value).apply();
        refresh();
    }

    private void chooseStartApp() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            apps.add(new String[]{ri.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = "None";
        for (int i = 0; i < apps.size(); i++) labels[i + 1] = apps.get(i)[0];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Open when the car starts")
                .setItems(labels, (d, which) -> {
                    mPrefs.edit().putString("start_app", which == 0 ? null : apps.get(which - 1)[1]).apply();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "None";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
