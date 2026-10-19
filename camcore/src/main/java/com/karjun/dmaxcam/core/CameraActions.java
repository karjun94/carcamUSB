package com.karjun.dmaxcam.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * How the shared pieces (button watcher, settings screen) talk to whichever app
 * hosts the camera: D-Max Cam's CameraActivity, or CarHome's built-in view.
 * Each app declares an activity with intent filters for these actions.
 */
public final class CameraActions {
    /** Open the camera, or close it if it is already showing. */
    public static final String ACTION_TOGGLE = "com.karjun.dmaxcam.action.TOGGLE_CAMERA";
    /** Open the camera (never closes it). */
    public static final String ACTION_OPEN = "com.karjun.dmaxcam.action.OPEN_CAMERA";
    /** Open the camera in guide-line adjust mode. */
    public static final String ACTION_ADJUST = "com.karjun.dmaxcam.action.ADJUST_GUIDES";

    /** The launcher, which has the camera built in. */
    public static final String LAUNCHER_PACKAGE = "com.karjun.dmaxlauncher";

    /** Set by the camera screen of the app it runs in. */
    private static volatile boolean sShowing;

    private CameraActions() {
    }

    public static boolean isShowing() {
        return sShowing;
    }

    public static void setShowing(boolean showing) {
        sShowing = showing;
    }

    public static void send(Context context, String action) {
        context.startActivity(new Intent(action)
                .setPackage(context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /**
     * When CarHome is installed it handles the steering button itself, so the
     * standalone D-Max Cam stays quiet; otherwise one press would open two cameras.
     */
    public static boolean deferToLauncher(Context context) {
        if (LAUNCHER_PACKAGE.equals(context.getPackageName())) return false;
        try {
            context.getPackageManager().getPackageInfo(LAUNCHER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
