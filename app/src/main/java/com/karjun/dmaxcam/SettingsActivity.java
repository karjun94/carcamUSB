package com.karjun.dmaxcam;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Choose which steering-wheel button opens the camera, or learn one by pressing it. */
public class SettingsActivity extends Activity {
    /** Keys the D-Max steering wheel was seen sending on this head unit. */
    private static final List<String> KNOWN_KEYS = Arrays.asList(
            "KEYCODE_CALL", "KEYCODE_ENDCALL", "KEYCODE_VOICE", "KEYCODE_MEDIA_MODE",
            "KEYCODE_MEDIA_NEXT", "KEYCODE_MEDIA_PREVIOUS", "KEYCODE_VOLUME_MUTE");

    private RadioGroup mKeys;
    private TextView mStatus;
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
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Steering button that opens the camera");
        title.setTextSize(22);
        root.addView(title);

        mStatus = new TextView(this);
        mStatus.setTextSize(16);
        mStatus.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(mStatus);

        mLearn = new Button(this);
        mLearn.setOnClickListener(v -> {
            boolean learning = !Prefs.isLearning(this);
            Prefs.setLearning(this, learning);
            refresh();
        });
        root.addView(mLearn);

        mKeys = new RadioGroup(this);
        mKeys.setPadding(0, pad / 2, 0, 0);
        root.addView(mKeys);

        TextView display = new TextView(this);
        display.setText("Picture");
        display.setTextSize(22);
        display.setPadding(0, pad, 0, 0);
        root.addView(display);

        Switch guideLines = new Switch(this);
        guideLines.setText("Show parking guide lines");
        guideLines.setTextSize(18);
        guideLines.setChecked(Prefs.showGuideLines(this));
        guideLines.setOnCheckedChangeListener((b, on) -> Prefs.setGuideLines(this, on));
        root.addView(guideLines);

        Switch mirror = new Switch(this);
        mirror.setText("Mirror the picture (left ↔ right)");
        mirror.setTextSize(18);
        mirror.setChecked(Prefs.isMirror(this));
        mirror.setOnCheckedChangeListener((b, on) -> Prefs.setMirror(this, on));
        root.addView(mirror);

        Button open = new Button(this);
        open.setText("Open camera");
        open.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));
        root.addView(open);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

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
        mStatus.setText(learning
                ? "Press the steering button you want to use…"
                : "Current button: " + Prefs.label(current) + "  (" + current + ")");
        mLearn.setText(learning ? "Cancel" : "Learn: press a steering button");

        List<String> keys = new ArrayList<>(KNOWN_KEYS);
        if (!keys.contains(current)) keys.add(current);
        mKeys.setOnCheckedChangeListener(null);
        mKeys.removeAllViews();
        for (int i = 0; i < keys.size(); i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(i + 1);
            rb.setText(Prefs.label(keys.get(i)));
            rb.setTextSize(18);
            rb.setTag(keys.get(i));
            mKeys.addView(rb);
            if (keys.get(i).equals(current)) rb.setChecked(true);
        }
        mKeys.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb != null) {
                Prefs.setTriggerKey(this, (String) rb.getTag());
                refresh();
            }
        });
    }
}
