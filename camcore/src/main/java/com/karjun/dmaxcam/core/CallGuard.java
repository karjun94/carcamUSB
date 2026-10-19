package com.karjun.dmaxcam.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Tells whether a phone call is ringing, active, or just ended, so the
 * steering button does not pop up the camera when it was really pressed to
 * answer or hang up a call (e.g. Hang up as the camera button).
 *
 * Bluetooth calls switch the audio mode to ringtone / in call; CarPlay calls
 * are announced by the GLink app with glink.intent.action.CP_PHONE (int state,
 * 0 = idle). By the time we read the key from the log the call may already be
 * over, so the audio mode is sampled every second and a short grace period
 * after the last sign of a call is kept.
 */
final class CallGuard {
    private static final String TAG = "DmaxCam";
    private static final String CP_PHONE = "glink.intent.action.CP_PHONE";
    private static final long POLL_MS = 1_000;
    /** Treat the button as a call button for this long after the call was last seen. */
    private static final long GRACE_MS = 4_000;

    private final AudioManager mAudio;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;
    private volatile long mLastCallSeen;
    private volatile boolean mCarPlayCall;

    private final BroadcastReceiver mCarPlay = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            int state = intent.getIntExtra("state", 0);
            Log.d(TAG, "CP_PHONE state=" + state);
            mCarPlayCall = state != 0;
            mLastCallSeen = SystemClock.elapsedRealtime();
        }
    };

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (audioInCall()) mLastCallSeen = SystemClock.elapsedRealtime();
            mHandler.postDelayed(this, POLL_MS);
        }
    };

    CallGuard(Context context) {
        mContext = context.getApplicationContext();
        mAudio = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    void start() {
        mContext.registerReceiver(mCarPlay, new IntentFilter(CP_PHONE));
        mHandler.post(mPoll);
    }

    void stop() {
        mHandler.removeCallbacks(mPoll);
        try {
            mContext.unregisterReceiver(mCarPlay);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** True while a call rings or is active, or ended in the last few seconds. */
    boolean busy() {
        if (audioInCall() || mCarPlayCall) return true;
        return mLastCallSeen != 0 && SystemClock.elapsedRealtime() - mLastCallSeen < GRACE_MS;
    }

    private boolean audioInCall() {
        int mode = mAudio.getMode();
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
    }
}
