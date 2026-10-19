package com.karjun.dmaxlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Icons, labels and launching for apps on the dashboard and app pages.
 *
 * An app is identified by a "key": either a package name, or a component
 * ("pkg/.Activity") for apps with several entry points (e.g. the head unit's
 * Phone Connect app has separate CarPlay and Android Auto icons).
 *
 * Head-unit firmware apps often have plain grey or placeholder icons; with
 * "matching icons" on, the common ones get a CarHome-style glyph, and any app
 * without an icon gets a letter tile, so the screen looks consistent.
 */
final class AppIcons {
    private static final int SIZE_PX = 192;
    private static final int TOP = 0xFF2EC4B6, BOTTOM = 0xFF1E8F85;

    /** Exact component or package -> glyph. Checked before the keyword rules. */
    private static final Map<String, Integer> KNOWN = new HashMap<>();
    /** Label / package keyword -> glyph, for built-in apps not listed above. */
    private static final String[][] KEYWORDS = {
            {"carplay", "iphone"}, {"android auto", "android"}, {"androidauto", "android"},
            {"radio", "radio"}, {"fmam", "radio"}, {"bluetooth", "bluetooth"}, {"btmusic", "bluetooth"},
            {"music", "music"}, {"video", "video"}, {"movie", "video"}, {"file", "folder"},
            {"gallery", "image"}, {"photo", "image"}, {"equaliz", "equalizer"}, {"sound", "equalizer"},
            {"setting", "settings"}, {"contact", "contacts"}, {"dial", "phone"}, {"phone", "phone"},
            {"theme", "palette"}, {"voice", "mic"}, {"assistant", "mic"}, {"link", "link"}, {"connect", "link"},
    };

    static {
        KNOWN.put("com.imotor.phoneconnect/.Carplay", R.drawable.glyph_iphone);
        KNOWN.put("com.imotor.phoneconnect/.AndroidAuto", R.drawable.glyph_android);
        KNOWN.put("com.imotor.contacts", R.drawable.glyph_phone);
        KNOWN.put("com.imotor.dialer", R.drawable.glyph_phone);
        KNOWN.put("com.imotor.fmam", R.drawable.glyph_radio);
        KNOWN.put("com.imotor.music", R.drawable.glyph_music);
        KNOWN.put("com.imotor.btmusic", R.drawable.glyph_bluetooth);
        KNOWN.put("com.imotor.video", R.drawable.glyph_video);
        KNOWN.put("com.imotor.filemanager", R.drawable.glyph_folder);
        KNOWN.put("com.imotor.theme", R.drawable.glyph_palette);
        KNOWN.put("com.android.gallery3d", R.drawable.glyph_image);
        KNOWN.put("com.android.launcher3/.SoundEqActivity", R.drawable.glyph_equalizer);
        KNOWN.put("com.android.settings", R.drawable.glyph_settings);
        KNOWN.put("com.txznet.aipal", R.drawable.glyph_mic);
        KNOWN.put("com.txznet.smartadapter", R.drawable.glyph_link);
        KNOWN.put("com.glink.connect", R.drawable.glyph_link);
    }

    private AppIcons() {
    }

    static String pkg(String key) {
        int slash = key.indexOf('/');
        return slash < 0 ? key : key.substring(0, slash);
    }

    private static ComponentName component(String key) {
        return key.indexOf('/') < 0 ? null : ComponentName.unflattenFromString(key);
    }

    static Intent launchIntent(Context c, String key) {
        ComponentName cn = component(key);
        if (cn == null) return c.getPackageManager().getLaunchIntentForPackage(key);
        try {
            c.getPackageManager().getActivityInfo(cn, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn);
    }

    static String label(Context c, String key) {
        PackageManager pm = c.getPackageManager();
        try {
            ComponentName cn = component(key);
            if (cn != null) return fixLabel(key, pm.getActivityInfo(cn, 0).loadLabel(pm).toString());
            return pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg(key);
        }
    }

    /** The icon to show for an app; never null for an installed app. */
    static Drawable icon(Context c, String key) {
        PackageManager pm = c.getPackageManager();
        String pkg = pkg(key);
        ComponentName cn = component(key);
        ApplicationInfo app;
        ActivityInfo activity = null;
        try {
            app = pm.getApplicationInfo(pkg, 0);
            if (cn != null) activity = pm.getActivityInfo(cn, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return letterTile(c, "?");
        }
        String label = activity != null ? activity.loadLabel(pm).toString() : pm.getApplicationLabel(app).toString();

        if (HomePrefs.matchingIcons(c)) {
            Integer glyph = glyphFor(key, pkg, label, app);
            if (glyph != null) return glyphTile(c, glyph);
        }
        boolean hasIcon = activity != null ? activity.getIconResource() != 0 : app.icon != 0;
        if (!hasIcon) return letterTile(c, label);
        return activity != null ? activity.loadIcon(pm) : pm.getApplicationIcon(app);
    }

    private static Integer glyphFor(String key, String pkg, String label, ApplicationInfo app) {
        // Shorten "pkg/pkg.Activity" to "pkg/.Activity" so it matches the table.
        String shortKey = key.replace(pkg + "/" + pkg + ".", pkg + "/.");
        if (KNOWN.containsKey(shortKey)) return KNOWN.get(shortKey);
        if (KNOWN.containsKey(pkg)) return KNOWN.get(pkg);
        // Keyword rules only for the unit's own (system) apps: store apps keep their branding.
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) return null;
        String hay = (label + " " + key).toLowerCase(Locale.ROOT);
        for (String[] rule : KEYWORDS) {
            if (hay.contains(rule[0])) return glyphId(rule[1]);
        }
        return null;
    }

    private static int glyphId(String name) {
        switch (name) {
            case "car": return R.drawable.glyph_car;
            case "iphone": return R.drawable.glyph_iphone;
            case "android": return R.drawable.glyph_android;
            case "radio": return R.drawable.glyph_radio;
            case "bluetooth": return R.drawable.glyph_bluetooth;
            case "music": return R.drawable.glyph_music;
            case "video": return R.drawable.glyph_video;
            case "folder": return R.drawable.glyph_folder;
            case "image": return R.drawable.glyph_image;
            case "equalizer": return R.drawable.glyph_equalizer;
            case "settings": return R.drawable.glyph_settings;
            case "contacts": return R.drawable.glyph_contacts;
            case "palette": return R.drawable.glyph_palette;
            case "mic": return R.drawable.glyph_mic;
            case "link": return R.drawable.glyph_link;
            default: return R.drawable.glyph_phone;
        }
    }

    /** Firmware labels that are misleading on a launcher. */
    private static String fixLabel(String key, String label) {
        if (key.endsWith("/.SoundEqActivity") || key.endsWith(".SoundEqActivity")) return "Equalizer";
        return label;
    }

    /** White glyph on a teal rounded square. */
    static Drawable glyphTile(Context c, int glyphRes) {
        Bitmap bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        drawSquare(canvas);
        Drawable glyph = c.getDrawable(glyphRes);
        int inset = SIZE_PX / 4;
        glyph.setBounds(inset, inset, SIZE_PX - inset, SIZE_PX - inset);
        glyph.setTint(0xFFFFFFFF);
        glyph.draw(canvas);
        return new BitmapDrawable(c.getResources(), bmp);
    }

    /** First letter of the name on the same teal square, for apps without an icon. */
    static Drawable letterTile(Context c, String label) {
        Bitmap bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        drawSquare(canvas);
        String letter = label.trim().isEmpty() ? "?" : label.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(SIZE_PX * 0.5f);
        Paint.FontMetrics fm = p.getFontMetrics();
        canvas.drawText(letter, SIZE_PX / 2f, SIZE_PX / 2f - (fm.ascent + fm.descent) / 2f, p);
        return new BitmapDrawable(c.getResources(), bmp);
    }

    private static void drawSquare(Canvas canvas) {
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, 0, SIZE_PX, TOP, BOTTOM, Shader.TileMode.CLAMP));
        float r = SIZE_PX * 0.22f;
        canvas.drawRoundRect(new RectF(0, 0, SIZE_PX, SIZE_PX), r, r, bg);
    }
}
