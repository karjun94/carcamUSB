package com.karjun.dmaxcam.core;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Choose which steering-wheel button opens the camera, and how the picture looks. */
public class SettingsActivity extends Activity {
    /** Keys the D-Max steering wheel was seen sending on this head unit. */
    private static final List<String> KNOWN_KEYS = Arrays.asList(
            "KEYCODE_CALL", "KEYCODE_ENDCALL", "KEYCODE_VOICE", "KEYCODE_MEDIA_MODE",
            "KEYCODE_MEDIA_NEXT", "KEYCODE_MEDIA_PREVIOUS", "KEYCODE_VOLUME_MUTE");

    private TextView mCurrentKey;
    private TextView mKeyHint;
    private GridLayout mKeys;
    private Button mLearn;

    private final BroadcastReceiver mLearned = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mCurrentKey = findViewById(R.id.current_key);
        mKeyHint = findViewById(R.id.key_hint);
        mKeys = findViewById(R.id.keys);
        mLearn = findViewById(R.id.learn);

        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.version)).setText("Front camera  ·  v" + version);
        } catch (Exception ignored) {
        }

        findViewById(R.id.open_camera).setOnClickListener(
                v -> CameraActions.send(this, CameraActions.ACTION_OPEN));
        mLearn.setOnClickListener(v -> {
            Prefs.setLearning(this, !Prefs.isLearning(this));
            refresh();
        });

        Switch guides = findViewById(R.id.guides);
        guides.setChecked(Prefs.showGuideLines(this));
        findViewById(R.id.row_guides).setOnClickListener(v -> {
            guides.toggle();
            Prefs.setGuideLines(this, guides.isChecked());
        });

        Switch mirror = findViewById(R.id.mirror);
        mirror.setChecked(Prefs.isMirror(this));
        findViewById(R.id.row_mirror).setOnClickListener(v -> {
            mirror.toggle();
            Prefs.setMirror(this, mirror.isChecked());
        });

        findViewById(R.id.adjust_guides).setOnClickListener(
                v -> CameraActions.send(this, CameraActions.ACTION_ADJUST));

        // Learning needs the watcher running.
        startService(new Intent(this, KeyWatchService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mLearned, new IntentFilter(Prefs.ACTION_KEY_LEARNED));
        refresh();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(mLearned);
        // Don't leave learn mode armed, or the next button press anywhere gets captured.
        if (Prefs.isLearning(this)) Prefs.setLearning(this, false);
        super.onPause();
    }

    private void refresh() {
        String current = Prefs.getTriggerKey(this);
        boolean learning = Prefs.isLearning(this);
        mCurrentKey.setText(learning ? "Listening…" : Prefs.label(current));
        String hint = learning
                ? "Press the steering button you want to use"
                : "Press once to open the camera, again to close it";
        if (CameraActions.deferToLauncher(this)) {
            hint = "CarHome is installed and handles the steering button";
        } else if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hint = "Steering button needs log access, granted once over ADB (see README)";
        }
        mKeyHint.setText(hint);
        mLearn.setText(learning ? "Cancel" : "Learn — press a steering button");
        mLearn.setSelected(learning);

        List<String> keys = new ArrayList<>(KNOWN_KEYS);
        if (!keys.contains(current)) keys.add(current);
        mKeys.removeAllViews();
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        for (String key : keys) {
            TextView chip = new TextView(this, null, 0, R.style.KeyChip);
            chip.setText(Prefs.label(key));
            chip.setSelected(!learning && key.equals(current));
            chip.setOnClickListener(v -> {
                Prefs.setTriggerKey(this, key);
                refresh();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(0, 0, gap, gap);
            mKeys.addView(chip, lp);
        }
    }
}
