package com.karjun.dmaxlauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** CarHome's own options (start-up, navigation card, parking mode). Same file as the dashboard state. */
final class HomePrefs {
    private HomePrefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    // --- When the car starts ---------------------------------------------------------------

    /** Send "play" so the last music app carries on. */
    static boolean resumeMusic(Context c) {
        return get(c).getBoolean("start_resume_music", true);
    }

    /** Package to open a few seconds after start (e.g. Waze), or null. */
    static String startApp(Context c) {
        return get(c).getString("start_app", null);
    }

    // --- Navigation card --------------------------------------------------------------------

    static boolean navCard(Context c) {
        return get(c).getBoolean("nav_card", true);
    }

    // --- Parking mode -----------------------------------------------------------------------

    static boolean parkingMode(Context c) {
        return get(c).getBoolean("parking_mode", false);
    }

    /** Show the front camera when slowing below this (km/h)... */
    static int parkingShowBelow(Context c) {
        return get(c).getInt("parking_show_below", 8);
    }

    /** ...after having driven above this, and hide it again above it (km/h). */
    static int parkingHideAbove(Context c) {
        return get(c).getInt("parking_hide_above", 15);
    }
}
