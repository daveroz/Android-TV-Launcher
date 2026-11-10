package com.camdeco.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android only lets an app see which media is playing (Spotify, YouTube
 * Music...) if it is an enabled notification listener, so this exists to be switched on:
 * adb shell cmd notification allow_listener com.camdeco.launcher/com.camdeco.launcher.MediaListenerService
 */
public class MediaListenerService extends NotificationListenerService {
}
