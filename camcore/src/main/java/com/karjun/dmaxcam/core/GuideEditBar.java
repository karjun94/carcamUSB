package com.karjun.dmaxcam.core;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Drag each dot…"  [Reset]  [Done] — shown over the camera while adjusting guide lines. */
public class GuideEditBar extends LinearLayout {
    public GuideEditBar(Context context, GuideLinesView guides, Runnable onDone) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.status_pill);
        setPadding(dp(24), dp(8), dp(8), dp(8));

        TextView hint = new TextView(context);
        hint.setText("Drag each dot. Bottom dots = bumper edge");
        hint.setTextColor(context.getColor(R.color.text_primary));
        hint.setTextSize(16);
        addView(hint);

        Button reset = new Button(context);
        reset.setText("Reset");
        reset.setAllCaps(false);
        reset.setTextColor(context.getColor(R.color.accent));
        reset.setBackgroundResource(R.drawable.button_outline);
        reset.setOnClickListener(v -> {
            Prefs.resetGuidePoints(context);
            guides.setPoints(Prefs.getGuidePoints(context));
        });
        LayoutParams lp = new LayoutParams(dp(110), dp(48));
        lp.setMarginStart(dp(16));
        addView(reset, lp);

        Button done = new Button(context);
        done.setText("Done");
        done.setAllCaps(false);
        done.setTextColor(context.getColor(R.color.on_accent));
        done.setBackgroundResource(R.drawable.button_primary);
        done.setOnClickListener(v -> {
            Prefs.setGuidePoints(context, guides.getPoints());
            onDone.run();
        });
        LayoutParams lp2 = new LayoutParams(dp(110), dp(48));
        lp2.setMarginStart(dp(8));
        addView(done, lp2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
