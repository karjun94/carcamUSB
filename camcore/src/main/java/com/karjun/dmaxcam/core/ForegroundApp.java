package com.karjun.dmaxcam.core;

import android.content.ComponentName;
import android.os.SystemClock;

/**
 * The app that was last in front, other than us, so the camera can hand the screen
 * back to it (e.g. CarPlay) when it closes. Fed by KeyWatchService from the iMotor
 * log line "IMPowerManagerService: onTopActivityChanged packageName = P,
 * className = C, foreground = true".
 */
public final class ForegroundApp {
    /** Our own "came to front" line may arrive just before or after the intent that caused it. */
    private static final long JUST_CAME_TO_FRONT_MS = 2000;

    private static volatile ComponentName sLast;
    private static volatile boolean sOwnOnTop;
    private static volatile long sOwnOnTopSince;

    private ForegroundApp() {
    }

    static void onTop(String ownPackage, String pkg, String cls) {
        if (pkg.equals(ownPackage)) {
            if (!sOwnOnTop) sOwnOnTopSince = SystemClock.elapsedRealtime();
            sOwnOnTop = true;
            return;
        }
        // The reverse camera overlay comes and goes by itself; don't return to it.
        if (pkg.equals("com.imotor.backcar")) return;
        sOwnOnTop = false;
        sLast = new ComponentName(pkg, cls.startsWith(".") ? pkg + cls : cls);
    }

    /**
     * The app we were just brought in front of, or null if we were already in front
     * (or it is unknown).
     */
    public static ComponentName openedOver() {
        boolean wasInFront = sOwnOnTop
                && SystemClock.elapsedRealtime() - sOwnOnTopSince > JUST_CAME_TO_FRONT_MS;
        return wasInFront ? null : sLast;
    }
}
