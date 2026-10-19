package com.karjun.dmaxlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.karjun.dmaxcam.core.CameraActions;

/**
 * "Front camera after reversing": when the reverse camera closes (shifting out
 * of reverse), bring up CarHome's front camera for a few seconds, like 360
 * camera systems do when you pull out of a parking space.
 *
 * Reverse in/out comes from the shared log watcher (iMotor logs
 * "onVehicleReverseSignalChanged"); the android.backcar.action.PREPARE_START /
 * FINISH broadcasts the GLink CarPlay app listens for are also accepted, for
 * firmwares that send them.
 */
final class ReverseWatcher {
    private static final String TAG = "CarHomeReverse";
    static final String START = "android.backcar.action.PREPARE_START";
    static final String FINISH = "android.backcar.action.FINISH";
    /** Long extra on CameraActions.ACTION_OPEN: close the camera again after this many ms. */
    static final String EXTRA_AUTO_CLOSE_MS = "com.karjun.dmaxlauncher.extra.AUTO_CLOSE_MS";
    /** Let the reverse camera screen finish closing before we open ours. */
    private static final long OPEN_DELAY_MS = 800;
    /** Ignore a FINISH without a recent START (e.g. a stray one at boot). */
    private static final long MAX_REVERSE_MS = 15 * 60_000;

    private static boolean sStarted;
    private static long sReverseStartedAt;

    private ReverseWatcher() {
    }

    static void start(Context context) {
        if (sStarted) return;
        sStarted = true;
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        IntentFilter filter = new IntentFilter(START);
        filter.addAction(FINISH);
        // This firmware does not send the backcar broadcasts; the log watcher reports reverse.
        filter.addAction(CameraActions.ACTION_REVERSE_CHANGED);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                long now = android.os.SystemClock.elapsedRealtime();
                Log.d(TAG, intent.getAction() + " " + intent.getExtras());
                boolean reverseIn = START.equals(intent.getAction())
                        || (CameraActions.ACTION_REVERSE_CHANGED.equals(intent.getAction())
                        && intent.getBooleanExtra(CameraActions.EXTRA_REVERSE, false));
                if (reverseIn) {
                    sReverseStartedAt = now;
                    return;
                }
                boolean wasReversing = sReverseStartedAt != 0 && now - sReverseStartedAt < MAX_REVERSE_MS;
                sReverseStartedAt = 0;
                if (!wasReversing || !HomePrefs.frontAfterReverse(c)) return;
                long showFor = HomePrefs.frontAfterReverseSecs(c) * 1000L;
                main.removeCallbacksAndMessages(null);
                main.postDelayed(() -> c.startActivity(new Intent(CameraActions.ACTION_OPEN)
                        .setPackage(c.getPackageName())
                        .putExtra(EXTRA_AUTO_CLOSE_MS, showFor)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)), OPEN_DELAY_MS);
            }
        }, filter);
    }
}
