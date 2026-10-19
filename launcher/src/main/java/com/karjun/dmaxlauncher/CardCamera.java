package com.karjun.dmaxlauncher;

import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.view.TextureView;
import android.widget.TextView;

import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.Size;
import com.serenegiant.widget.AspectRatioTextureView;

import java.util.List;

/**
 * Small live USB (UVC) camera preview for a dashboard card. Same approach as
 * D-Max Cam's full-screen view, but at 640x480 to keep the home screen light.
 */
final class CardCamera {
    private final AspectRatioTextureView mView;
    private final TextView mStatus;
    private ICameraHelper mHelper;

    // The D-Max Cam app may still be letting go of the camera when we open it
    // (when the camera is handed back to the dashboard), so a failed open is retried a few times.
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_MS = 600;
    private final android.os.Handler mRetryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int mRetries;

    CardCamera(AspectRatioTextureView view, TextView status) {
        mView = view;
        mStatus = status;
        mView.setAspectRatio(16, 9);
        mView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                if (mHelper != null && mHelper.isCameraOpened()) mHelper.addSurface(surface, false);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (mHelper != null) mHelper.removeSurface(surface);
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
    }

    void start() {
        if (mHelper != null) return;
        status("Plug in a USB camera");
        mHelper = new CameraHelper();
        mHelper.setStateCallback(mCallback);
        List<UsbDevice> devices = mHelper.getDeviceList();
        if (devices != null && !devices.isEmpty()) mHelper.selectDevice(devices.get(0));
    }

    void stop() {
        mRetryHandler.removeCallbacksAndMessages(null);
        mRetries = 0;
        if (mHelper == null) return;
        mHelper.release();
        mHelper = null;
    }

    private void status(String text) {
        mStatus.post(() -> {
            mStatus.setText(text);
            mStatus.setVisibility(text == null ? android.view.View.GONE : android.view.View.VISIBLE);
        });
    }

    private static Size pickSize(List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        for (int[] want : new int[][]{{640, 480}, {640, 360}, {1280, 720}}) {
            for (Size s : sizes) {
                if (s.width == want[0] && s.height == want[1]) return s;
            }
        }
        return sizes.get(0);
    }

    private final ICameraHelper.StateCallback mCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            if (mHelper != null) mHelper.selectDevice(device);
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (mHelper == null) return;
            Size size = pickSize(mHelper.getSupportedSizeList());
            if (size != null) mHelper.openCamera(size);
            else mHelper.openCamera();
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            if (mHelper == null) return;
            mRetries = 0;
            mHelper.startPreview();
            Size size = mHelper.getPreviewSize();
            if (size != null) mView.post(() -> mView.setAspectRatio(size.width, size.height));
            SurfaceTexture texture = mView.getSurfaceTexture();
            if (texture != null) mHelper.addSurface(texture, false);
            status(null);
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            SurfaceTexture texture = mView.getSurfaceTexture();
            if (mHelper != null && texture != null) mHelper.removeSurface(texture);
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
        }

        @Override
        public void onDetach(UsbDevice device) {
            status("Camera unplugged");
        }

        @Override
        public void onCancel(UsbDevice device) {
            status("USB permission denied");
        }

        @Override
        public void onError(UsbDevice device, CameraException e) {
            if (mRetries < MAX_RETRIES) {
                mRetries++;
                status("Connecting to camera…");
                mRetryHandler.postDelayed(() -> {
                    if (mHelper != null) mHelper.selectDevice(device);
                }, RETRY_MS);
                return;
            }
            status("Camera error");
        }
    };
}
