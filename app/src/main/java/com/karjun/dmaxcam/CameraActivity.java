package com.karjun.dmaxcam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.Size;
import com.serenegiant.widget.AspectRatioTextureView;

import java.util.List;

/** Full-screen preview of the first USB (UVC) camera. Tap or press Back to close. */
public class CameraActivity extends Activity {
    private static final String TAG = "DmaxCam";
    /** Sent by KeyWatchService; closes the camera if it is already showing. */
    static final String EXTRA_TOGGLE = "toggle";
    /** Opens straight into guide-line adjustment (from SettingsActivity). */
    static final String EXTRA_ADJUST = "adjust";

    private static volatile boolean sResumed;

    // The launcher's camera card may still be letting go of the camera when we open it
    // (or the other way round), so a failed open is retried a few times.
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_MS = 600;
    private final android.os.Handler mRetryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int mRetries;

    private ICameraHelper mCameraHelper;
    // A TextureView (not SurfaceView) so the picture can be mirrored with setScaleX.
    private AspectRatioTextureView mPreview;
    private GuideLinesView mGuideLines;
    private TextView mStatus;
    private View mEditBar;
    private boolean mEditing;

    static boolean isShowing() {
        return sResumed;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // The guide lines share a container with the preview so they cover the picture, not the black bars.
        FrameLayout frame = new FrameLayout(this);
        mPreview = new AspectRatioTextureView(this);
        mPreview.setAspectRatio(16, 9);
        frame.addView(mPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        mGuideLines = new GuideLinesView(this);
        mGuideLines.setOnChanged(() -> Prefs.setGuidePoints(this, mGuideLines.getPoints()));
        frame.addView(mGuideLines, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(frame, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        int pad = dp(16);
        mStatus = new TextView(this);
        mStatus.setTextColor(getColor(R.color.text_primary));
        mStatus.setTextSize(20);
        mStatus.setBackgroundResource(R.drawable.status_pill);
        mStatus.setPadding(pad * 2, pad, pad * 2, pad);
        mStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_videocam_light, 0, 0, 0);
        mStatus.setCompoundDrawablePadding(pad);
        mStatus.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(mStatus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        mEditBar = buildEditBar();
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        barLp.topMargin = pad;
        root.addView(mEditBar, barLp);

        root.setOnClickListener(v -> {
            if (!mEditing) finish();
        });
        setContentView(root);

        mPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (mCameraHelper != null) mCameraHelper.addSurface(surface, false);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (mCameraHelper != null) mCameraHelper.removeSurface(surface);
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        startService(new Intent(this, KeyWatchService.class));

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_TOGGLE, false) && sResumed && !mEditing) finish();
    }

    /** "Drag the dots to line up with your bumper"  [Reset]  [Done] */
    private View buildEditBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.status_pill);
        bar.setPadding(dp(24), dp(8), dp(8), dp(8));

        TextView hint = new TextView(this);
        hint.setText("Drag each dot. Bottom dots = bumper edge");
        hint.setTextColor(getColor(R.color.text_primary));
        hint.setTextSize(16);
        bar.addView(hint);

        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setAllCaps(false);
        reset.setTextColor(getColor(R.color.accent));
        reset.setBackgroundResource(R.drawable.button_outline);
        reset.setOnClickListener(v -> {
            Prefs.resetGuidePoints(this);
            mGuideLines.setPoints(Prefs.getGuidePoints(this));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), dp(48));
        lp.setMarginStart(dp(16));
        bar.addView(reset, lp);

        Button done = new Button(this);
        done.setText("Done");
        done.setAllCaps(false);
        done.setTextColor(getColor(R.color.on_accent));
        done.setBackgroundResource(R.drawable.button_primary);
        done.setOnClickListener(v -> {
            Prefs.setGuidePoints(this, mGuideLines.getPoints());
            if (getIntent().getBooleanExtra(EXTRA_ADJUST, false)) finish();
            else setEditing(false);
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(110), dp(48));
        lp2.setMarginStart(dp(8));
        bar.addView(done, lp2);
        return bar;
    }

    private void setEditing(boolean editing) {
        mEditing = editing;
        mGuideLines.setEditing(editing);
        mEditBar.setVisibility(editing ? View.VISIBLE : View.GONE);
        mGuideLines.setVisibility(editing || Prefs.showGuideLines(this) ? View.VISIBLE : View.GONE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        showStatus("Plug in a USB camera");
        mCameraHelper = new CameraHelper();
        mCameraHelper.setStateCallback(mStateCallback);
        // Pick up a camera that was already plugged in before we started.
        List<UsbDevice> devices = mCameraHelper.getDeviceList();
        if (devices != null && !devices.isEmpty()) mCameraHelper.selectDevice(devices.get(0));
    }

    @Override
    protected void onResume() {
        super.onResume();
        sResumed = true;
        hideSystemBars();
        // Re-read every time: the settings may have changed while we were away.
        mPreview.setScaleX(Prefs.isMirror(this) ? -1f : 1f);
        mGuideLines.setPoints(Prefs.getGuidePoints(this));
        setEditing(getIntent().getBooleanExtra(EXTRA_ADJUST, false));
    }

    @Override
    protected void onPause() {
        sResumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        mRetryHandler.removeCallbacksAndMessages(null);
        mRetries = 0;
        if (mCameraHelper != null) {
            mCameraHelper.release();
            mCameraHelper = null;
        }
        super.onStop();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showStatus(String text) {
        runOnUiThread(() -> {
            mStatus.setText(text);
            mStatus.setVisibility(text == null ? View.GONE : View.VISIBLE);
        });
    }

    /** Prefer 1280x720, then 640x480: smooth on the RK3326. */
    private static Size pickSize(List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        for (int[] want : new int[][]{{1280, 720}, {640, 480}}) {
            for (Size s : sizes) {
                if (s.width == want[0] && s.height == want[1]) return s;
            }
        }
        return sizes.get(0);
    }

    private final ICameraHelper.StateCallback mStateCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            if (mCameraHelper != null) mCameraHelper.selectDevice(device);
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (mCameraHelper == null) return;
            Size size = pickSize(mCameraHelper.getSupportedSizeList());
            if (size != null) mCameraHelper.openCamera(size);
            else mCameraHelper.openCamera();
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            if (mCameraHelper == null) return;
            mRetries = 0;
            mCameraHelper.startPreview();
            Size size = mCameraHelper.getPreviewSize();
            if (size != null) {
                runOnUiThread(() -> mPreview.setAspectRatio(size.width, size.height));
            }
            SurfaceTexture texture = mPreview.getSurfaceTexture();
            if (texture != null) mCameraHelper.addSurface(texture, false);
            showStatus(null);
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            SurfaceTexture texture = mPreview.getSurfaceTexture();
            if (mCameraHelper != null && texture != null) mCameraHelper.removeSurface(texture);
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
        }

        @Override
        public void onDetach(UsbDevice device) {
            showStatus("Camera unplugged");
        }

        @Override
        public void onCancel(UsbDevice device) {
            showStatus("USB permission denied");
        }

        @Override
        public void onError(UsbDevice device, CameraException e) {
            Log.e(TAG, "camera error", e);
            if (mRetries < MAX_RETRIES) {
                mRetries++;
                showStatus("Connecting to camera…");
                mRetryHandler.postDelayed(() -> {
                    if (mCameraHelper != null) mCameraHelper.selectDevice(device);
                }, RETRY_MS);
                return;
            }
            showStatus("Camera error: " + e.getMessage());
        }
    };
}
