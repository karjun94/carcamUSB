package com.karjun.dmaxlauncher;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Runs the "when the car starts" actions. Head units usually sleep when the
 * ignition goes off instead of shutting down, so a start is either a fresh boot
 * or the screen coming back on after being off for a while.
 */
public class CarHomeApp extends Application {
    private static final String TAG = "CarHome";
    /** Screen off for at least this long, then on again = the car was switched off and on. */
    private static final long MIN_OFF_MS = 60_000;
    /** The process starting this soon after boot counts as a start. */
    private static final long BOOT_WINDOW_MS = 5 * 60_000;
    /** Give the audio system and Bluetooth a moment before resuming. */
    private static final long RESUME_DELAY_MS = 3_000;
    private static final long OPEN_APP_DELAY_MS = 6_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && firstStartThisBoot()) {
            onCarStarted();
        } else if (getSystemService(android.os.PowerManager.class).isInteractive()) {
            // Android may kill us while the screen is off; the off time is saved, so a
            // restart after a long-enough "off" still counts as the car starting.
            checkScreenCameOn();
        }

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    HomePrefs.get(context).edit().putLong("screen_off_at", SystemClock.elapsedRealtime()).apply();
                } else {
                    checkScreenCameOn();
                }
            }
        }, filter);
    }

    /** Screen back on: was it off long enough to count as the car being switched off? */
    private void checkScreenCameOn() {
        long offAt = HomePrefs.get(this).getLong("screen_off_at", 0);
        HomePrefs.get(this).edit().remove("screen_off_at").apply();
        // elapsedRealtime restarts at boot; a value from a previous boot is ignored here
        // (the boot path above handles that case).
        long offFor = SystemClock.elapsedRealtime() - offAt;
        if (offAt > 0 && offFor >= MIN_OFF_MS) onCarStarted();
    }

    /** Only once per boot, even if Android restarts our process during the first minutes. */
    private boolean firstStartThisBoot() {
        // Wall-clock boot time; allow a little slack for clock adjustments.
        long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        long seen = HomePrefs.get(this).getLong("started_boot", 0);
        if (Math.abs(seen - bootAt) < 30_000) return false;
        HomePrefs.get(this).edit().putLong("started_boot", bootAt).apply();
        return true;
    }

    private void onCarStarted() {
        Log.i(TAG, "car started");
        mHandler.removeCallbacksAndMessages(null);
        if (HomePrefs.resumeMusic(this)) mHandler.postDelayed(this::resumeMusic, RESUME_DELAY_MS);
        String app = HomePrefs.startApp(this);
        if (app != null) mHandler.postDelayed(() -> openApp(app), OPEN_APP_DELAY_MS);
    }

    /**
     * Resume the last music app, the way Android Auto does:
     * 1. its media session, if it still has one;
     * 2. otherwise connect to its media browser service and press play (works
     *    for YouTube Music, Spotify and most players even when not running);
     * 3. otherwise a plain "play" media key, which Android routes to the last
     *    player it remembers (often none right after a reboot).
     */
    private void resumeMusic() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio.isMusicActive()) {
            Log.i(TAG, "music already playing");
            return;
        }
        String last = HomePrefs.get(this).getString("last_music_pkg", null);
        Log.i(TAG, "resuming " + last);
        if (playExistingSession(last)) return;
        if (last != null && playViaBrowser(last)) return;
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
    }

    private boolean playExistingSession(String preferred) {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            java.util.List<android.media.session.MediaController> sessions =
                    msm.getActiveSessions(new android.content.ComponentName(this, MediaListener.class));
            android.media.session.MediaController pick = null;
            for (android.media.session.MediaController c : sessions) {
                if (c.getPackageName().equals(preferred)) pick = c;
            }
            if (pick == null && preferred == null && !sessions.isEmpty()) pick = sessions.get(0);
            if (pick == null) return false;
            pick.getTransportControls().play();
            return true;
        } catch (SecurityException e) {
            return false; // no notification access
        }
    }

    /** Connects to the app's MediaBrowserService (what car systems use) and sends play. */
    private boolean playViaBrowser(String pkg) {
        Intent query = new Intent("android.media.browse.MediaBrowserService").setPackage(pkg);
        java.util.List<android.content.pm.ResolveInfo> services = getPackageManager().queryIntentServices(query, 0);
        if (services.isEmpty()) return false;
        android.content.ComponentName service = new android.content.ComponentName(
                pkg, services.get(0).serviceInfo.name);
        android.media.browse.MediaBrowser[] holder = new android.media.browse.MediaBrowser[1];
        holder[0] = new android.media.browse.MediaBrowser(this, service, new android.media.browse.MediaBrowser.ConnectionCallback() {
            @Override
            public void onConnected() {
                new android.media.session.MediaController(CarHomeApp.this, holder[0].getSessionToken())
                        .getTransportControls().play();
                // Give the player a moment to take over before letting go.
                mHandler.postDelayed(holder[0]::disconnect, 5_000);
            }

            @Override
            public void onConnectionFailed() {
                Log.w(TAG, "could not connect to " + pkg);
            }
        }, null);
        holder[0].connect();
        return true;
    }

    private void openApp(String pkg) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
