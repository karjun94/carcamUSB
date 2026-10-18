package com.karjun.dmaxcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The head unit's MCU injects steering-wheel keys straight into the window
 * manager, so neither accessibility services nor key-mapper apps see them.
 * The iMotor InputPolicy does log every key to the system buffer, though, so
 * we follow that log (READ_LOGS is granted over ADB) and react to the key
 * chosen in SettingsActivity (Call by default).
 */
public class KeyWatchService extends Service {
    private static final String TAG = "DmaxCam";
    private static final String CHANNEL = "keywatch";
    // Logged once per key by the vendor policy: "interceptKeyBeforeQueueing event = KeyEvent { action=ACTION_UP, keyCode=KEYCODE_CALL, ..."
    private static final String MATCH_QUEUEING = "interceptKeyBeforeQueueing";
    private static final Pattern KEY_UP = Pattern.compile("action=ACTION_UP, keyCode=(KEYCODE_\\w+)");
    private static final long DEBOUNCE_MS = 800;
    // The key's own app (dialer, voice assistant...) opens first; launch after it so we end up on top.
    private static final long LAUNCH_DELAY_MS = 700;

    private Thread mThread;
    private volatile boolean mRunning;
    private Process mLogcat;
    private long mLastTrigger;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mThread == null) {
            mRunning = true;
            mThread = new Thread(this::watchLoop, "keywatch");
            mThread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        if (mLogcat != null) mLogcat.destroy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void watchLoop() {
        while (mRunning) {
            try {
                // -T 1: start from the newest line so old presses are not replayed.
                mLogcat = new ProcessBuilder("logcat", "-b", "system", "-v", "brief", "-T", "1",
                        "InputPolicy:D", "*:S").redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(mLogcat.getInputStream()));
                String line;
                while (mRunning && (line = reader.readLine()) != null) {
                    if (!line.contains(MATCH_QUEUEING)) continue;
                    Matcher m = KEY_UP.matcher(line);
                    if (m.find()) onKeyUp(m.group(1));
                }
            } catch (Exception e) {
                Log.w(TAG, "logcat reader stopped", e);
            } finally {
                if (mLogcat != null) mLogcat.destroy();
            }
            SystemClock.sleep(2000);
        }
    }

    private void onKeyUp(String key) {
        if (Prefs.isLearning(this)) {
            Prefs.setTriggerKey(this, key);
            sendBroadcast(new Intent(Prefs.ACTION_KEY_LEARNED).setPackage(getPackageName()));
            return;
        }
        if (key.equals(Prefs.getTriggerKey(this))) onTriggerPressed();
    }

    private void onTriggerPressed() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastTrigger < DEBOUNCE_MS) return;
        mLastTrigger = now;
        boolean close = CameraActivity.isShowing();
        if (!close) SystemClock.sleep(LAUNCH_DELAY_MS);
        Intent intent = new Intent(this, CameraActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(CameraActivity.EXTRA_TOGGLE, close);
        startActivity(intent);
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Steering button", NotificationManager.IMPORTANCE_MIN));
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("D-Max Cam")
                .setContentText("Steering button opens the camera")
                .setOngoing(true)
                .build();
    }
}
