package com.karjun.dmaxlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

/**
 * What is playing over CarPlay / Android Auto. On the iMotor head unit both run
 * in the GLink apps (com.glink.carplay, com.glink.androidauto), which announce
 * the phone's track with open broadcasts:
 *
 *   glink.intent.action.CP_MEDIA / AA_MEDIA       title, artist, album (strings)
 *   glink.intent.action.CP_PLAYBACK / AA_PLAYBACK state (int)
 *   glink.intent.action.CP_STATUS / AA_STATUS     btAddress (empty = disconnected)
 *
 * Registered from the Application so no update is missed while CarHome is in
 * the background. Elsewhere the broadcasts never arrive and nothing shows.
 */
final class ProjectionSource {
    private static final String TAG = "CarHomeProjection";
    private static final String CP = "glink.intent.action.CP_";
    private static final String AA = "glink.intent.action.AA_";

    /** Latest track from the phone. */
    static final class Track {
        final String source;  // "CarPlay" / "Android Auto"
        final String pkg;     // app to open on tap
        final String title, artist, album;
        final int state;      // raw GLink playback state (-1 = unknown, 2 = playing)

        Track(String source, String pkg, String title, String artist, String album, int state) {
            this.source = source;
            this.pkg = pkg;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.state = state;
        }

        boolean playing() {
            return state == STATE_PLAYING;
        }
    }

    /** GLink playback state for "playing" (firmware GlinkAdapter.isCarplayPlaying). */
    static final int STATE_PLAYING = 2;
    /**
     * Commands for glink.intent.action.LK_EVENT (int extra "keyCode"), as the firmware's
     * GlinkAdapter sends them for the steering-wheel media keys; both GLink apps listen.
     */
    static final int KEY_PREVIOUS = 13, KEY_NEXT = 14, KEY_PLAY = 15, KEY_PAUSE = 16;

    static void sendKey(Context context, int code) {
        Log.d(TAG, "LK_EVENT keyCode=" + code);
        context.sendBroadcast(new Intent("glink.intent.action.LK_EVENT").putExtra("keyCode", code));
    }

    interface Listener {
        void onTrack(Track track);
    }

    private static Track sTrack;
    private static Listener sListener;
    private static boolean sStarted;

    private ProjectionSource() {
    }

    static void start(Context context) {
        if (sStarted) return;
        sStarted = true;
        IntentFilter filter = new IntentFilter();
        for (String p : new String[]{CP, AA}) {
            filter.addAction(p + "MEDIA");
            filter.addAction(p + "PLAYBACK");
            filter.addAction(p + "STATUS");
        }
        context.getApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                handle(intent);
            }
        }, filter);
    }

    static Track current() {
        return sTrack;
    }

    /** Main thread; null to stop listening. */
    static void setListener(Listener listener) {
        sListener = listener;
    }

    private static void handle(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Log.d(TAG, action + " " + describe(intent.getExtras()));
        boolean carplay = action.startsWith(CP);
        String source = carplay ? "CarPlay" : "Android Auto";
        String pkg = carplay ? "com.imotor.phoneconnect/.Carplay" : "com.imotor.phoneconnect/.AndroidAuto";
        String kind = action.substring(CP.length());
        Track old = sTrack != null && sTrack.source.equals(source) ? sTrack : null;
        switch (kind) {
            case "MEDIA":
                sTrack = new Track(source, pkg, clean(intent.getStringExtra("title")),
                        clean(intent.getStringExtra("artist")), clean(intent.getStringExtra("album")),
                        old != null ? old.state : -1);
                break;
            case "PLAYBACK":
                int state = intent.getIntExtra("state", -1);
                sTrack = old != null
                        ? new Track(source, pkg, old.title, old.artist, old.album, state)
                        : new Track(source, pkg, "", "", "", state);
                break;
            case "STATUS":
                String bt = intent.getStringExtra("btAddress");
                // Disconnected: forget the phone's track.
                if (bt == null || bt.isEmpty()) {
                    if (old != null) sTrack = null;
                } else if (sTrack == null) {
                    sTrack = new Track(source, pkg, "", "", "", -1);
                }
                break;
            default:
                return;
        }
        Listener l = sListener;
        if (l != null) l.onTrack(sTrack);
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String describe(Bundle b) {
        if (b == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (String k : b.keySet()) sb.append(k).append('=').append(b.get(k)).append(' ');
        return sb.append('}').toString();
    }
}
