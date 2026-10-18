package com.karjun.dmaxcam;

import android.content.Context;
import android.content.SharedPreferences;

/** Which steering-wheel key opens the camera, plus the "learn a key" flag. */
final class Prefs {
    static final String DEFAULT_KEY = "KEYCODE_CALL";
    static final String ACTION_KEY_LEARNED = "com.karjun.dmaxcam.KEY_LEARNED";

    private static final String FILE = "settings";
    private static final String TRIGGER_KEY = "trigger_key";
    private static final String LEARNING = "learning";
    private static final String MIRROR = "mirror";
    private static final String GUIDE_LINES = "guide_lines";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String getTriggerKey(Context context) {
        return prefs(context).getString(TRIGGER_KEY, DEFAULT_KEY);
    }

    static void setTriggerKey(Context context, String key) {
        prefs(context).edit().putString(TRIGGER_KEY, key).putBoolean(LEARNING, false).apply();
    }

    static boolean isLearning(Context context) {
        return prefs(context).getBoolean(LEARNING, false);
    }

    static void setLearning(Context context, boolean learning) {
        prefs(context).edit().putBoolean(LEARNING, learning).apply();
    }

    static boolean isMirror(Context context) {
        return prefs(context).getBoolean(MIRROR, false);
    }

    static void setMirror(Context context, boolean mirror) {
        prefs(context).edit().putBoolean(MIRROR, mirror).apply();
    }

    static boolean showGuideLines(Context context) {
        return prefs(context).getBoolean(GUIDE_LINES, false);
    }

    static void setGuideLines(Context context, boolean show) {
        prefs(context).edit().putBoolean(GUIDE_LINES, show).apply();
    }

    /** "KEYCODE_MEDIA_MODE" -> "Media mode". */
    static String label(String key) {
        String name = key.startsWith("KEYCODE_") ? key.substring(8) : key;
        name = name.replace('_', ' ').toLowerCase();
        return name.isEmpty() ? key : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
