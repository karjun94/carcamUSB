package com.karjun.dmaxlauncher;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.List;

/**
 * Notification access is what lets us read other apps' media sessions (now
 * playing). We also use it for the navigation card: while Waze or Google Maps
 * is navigating, their ongoing notification carries the next turn.
 */
public class MediaListener extends NotificationListenerService {
    private static final List<String> NAV_APPS = Arrays.asList(
            "com.waze", "com.google.android.apps.maps", "com.google.android.apps.mapslite");

    /** Latest turn instruction, or null when nothing is navigating. */
    static final class Nav {
        final String pkg;
        final CharSequence distance;   // e.g. "300 m"
        final CharSequence instruction; // e.g. "Turn right onto Jalan Ampang"
        final CharSequence trip;       // e.g. "13 min · 6.6 km · 11:32 am ETA", may be null
        final Drawable arrow;          // turn arrow from the app, may be null

        Nav(String pkg, CharSequence distance, CharSequence instruction, CharSequence trip, Drawable arrow) {
            this.pkg = pkg;
            this.distance = distance;
            this.instruction = instruction;
            this.trip = trip;
            this.arrow = arrow;
        }
    }

    interface NavListener {
        void onNavChanged(Nav nav);
    }

    private static volatile Nav sNav;
    private static NavListener sListener;

    static Nav currentNav() {
        return sNav;
    }

    /** Called on the main thread; pass null to stop listening. */
    static void setNavListener(NavListener listener) {
        sListener = listener;
    }

    @Override
    public void onListenerConnected() {
        // Pick up a navigation that was already running.
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) onNotificationPosted(sbn);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!NAV_APPS.contains(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        // Only the ongoing turn-by-turn notification, not "Drive to work?" suggestions.
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;
        Bundle extras = n.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;
        CharSequence sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        publish(new Nav(sbn.getPackageName(), title, text, sub, largeIcon(n)));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        Nav nav = sNav;
        if (nav != null && nav.pkg.equals(sbn.getPackageName())
                && (sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            publish(null);
        }
    }

    private Drawable largeIcon(Notification n) {
        Icon icon = n.getLargeIcon();
        if (icon != null) {
            Drawable d = icon.loadDrawable(this);
            if (d != null) return d;
        }
        Object bitmap = n.extras.get(Notification.EXTRA_LARGE_ICON);
        if (bitmap instanceof Bitmap) return new android.graphics.drawable.BitmapDrawable(getResources(), (Bitmap) bitmap);
        return null;
    }

    private static void publish(Nav nav) {
        sNav = nav;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            NavListener l = sListener;
            if (l != null) l.onNavChanged(sNav);
        });
    }
}
