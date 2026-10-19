package com.karjun.dmaxcam.core;

import android.content.Context;
import android.content.SharedPreferences;

/** Which steering-wheel key opens the camera, plus the "learn a key" flag. */
public final class Prefs {
    public static final String DEFAULT_KEY = "KEYCODE_CALL";
    public static final String ACTION_KEY_LEARNED = "com.karjun.dmaxcam.KEY_LEARNED";

    private static final String FILE = "settings";
    private static final String TRIGGER_KEY = "trigger_key";
    private static final String LEARNING = "learning";
    private static final String MIRROR = "mirror";
    private static final String GUIDE_LINES = "guide_lines";
    private static final String GUIDE_POINTS = "guide_points";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getTriggerKey(Context context) {
        return prefs(context).getString(TRIGGER_KEY, DEFAULT_KEY);
    }

    public static void setTriggerKey(Context context, String key) {
        prefs(context).edit().putString(TRIGGER_KEY, key).putBoolean(LEARNING, false).apply();
    }

    public static boolean isLearning(Context context) {
        return prefs(context).getBoolean(LEARNING, false);
    }

    public static void setLearning(Context context, boolean learning) {
        prefs(context).edit().putBoolean(LEARNING, learning).apply();
    }

    /** Guide-line endpoints as fractions of the picture; see GuideLinesView.DEFAULT_POINTS. */
    public static float[] getGuidePoints(Context context) {
        String saved = prefs(context).getString(GUIDE_POINTS, null);
        float[] defaults = GuideLinesView.DEFAULT_POINTS;
        if (saved == null) return defaults.clone();
        String[] parts = saved.split(",");
        if (parts.length != defaults.length) return defaults.clone();
        float[] points = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) points[i] = Float.parseFloat(parts[i]);
        } catch (NumberFormatException e) {
            return defaults.clone();
        }
        return points;
    }

    public static void setGuidePoints(Context context, float[] points) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(points[i]);
        }
        prefs(context).edit().putString(GUIDE_POINTS, sb.toString()).apply();
    }

    public static void resetGuidePoints(Context context) {
        prefs(context).edit().remove(GUIDE_POINTS).apply();
    }

    public static boolean isMirror(Context context) {
        return prefs(context).getBoolean(MIRROR, false);
    }

    public static void setMirror(Context context, boolean mirror) {
        prefs(context).edit().putBoolean(MIRROR, mirror).apply();
    }

    public static boolean showGuideLines(Context context) {
        return prefs(context).getBoolean(GUIDE_LINES, false);
    }

    public static void setGuideLines(Context context, boolean show) {
        prefs(context).edit().putBoolean(GUIDE_LINES, show).apply();
    }

    /** Friendly names for the D-Max steering-wheel keys; anything else is derived from the key code. */
    public static String label(String key) {
        switch (key) {
            case "KEYCODE_CALL": return "Call";
            case "KEYCODE_ENDCALL": return "Hang up";
            case "KEYCODE_VOICE": return "Voice";
            case "KEYCODE_MEDIA_MODE": return "Mode";
            case "KEYCODE_MEDIA_NEXT": return "Next";
            case "KEYCODE_MEDIA_PREVIOUS": return "Previous";
            case "KEYCODE_VOLUME_MUTE": return "Mute";
            default: return codeLabel(key);
        }
    }

    /** "KEYCODE_MEDIA_PLAY" -> "Media play". */
    private static String codeLabel(String key) {
        String name = key.startsWith("KEYCODE_") ? key.substring(8) : key;
        name = name.replace('_', ' ').toLowerCase();
        return name.isEmpty() ? key : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
