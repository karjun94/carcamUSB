package com.karjun.dmaxlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Full-screen rear camera ("digital mirror"). Tap or Back to close. */
public class RearViewActivity extends Activity {
    private RearCamera mRear;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView view = new SurfaceView(this);
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mStatus = new TextView(this);
        mStatus.setTextColor(Color.WHITE);
        mStatus.setTextSize(18);
        mStatus.setGravity(Gravity.CENTER);
        root.addView(mStatus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.setOnClickListener(v -> finish());
        setContentView(root);

        mRear = RearCamera.create(this);
        if (mRear == null) {
            mStatus.setText("Rear camera not available on this device");
            return;
        }
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                boolean ok = mRear.show(holder.getSurface());
                mStatus.setText(!ok ? "Could not start the rear camera"
                        : mRear.hasSignal() ? "" : "No picture from the rear camera (it may only get power in reverse)");
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                mRear.hide();
            }
        });
    }
}
