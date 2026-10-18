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
import android.widget.FrameLayout;
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

    private static volatile boolean sResumed;

    private ICameraHelper mCameraHelper;
    // A TextureView (not SurfaceView) so the picture can be mirrored with setScaleX.
    private AspectRatioTextureView mPreview;
    private GuideLinesView mGuideLines;
    private TextView mStatus;

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
        frame.addView(mPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        mGuideLines = new GuideLinesView(this);
        frame.addView(mGuideLines, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(frame, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        mStatus = new TextView(this);
        mStatus.setTextColor(Color.WHITE);
        mStatus.setTextSize(22);
        mStatus.setGravity(Gravity.CENTER);
        root.addView(mStatus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnClickListener(v -> finish());
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
        if (intent.getBooleanExtra(EXTRA_TOGGLE, false) && sResumed) finish();
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
        mGuideLines.setVisibility(Prefs.showGuideLines(this) ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onPause() {
        sResumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
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
            showStatus("Camera error: " + e.getMessage());
        }
    };
}
