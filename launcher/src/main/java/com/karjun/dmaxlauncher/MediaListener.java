package com.karjun.dmaxlauncher;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose. Android only lets apps with an enabled notification
 * listener read other apps' media sessions, which is what the now-playing
 * card needs.
 */
public class MediaListener extends NotificationListenerService {
}
