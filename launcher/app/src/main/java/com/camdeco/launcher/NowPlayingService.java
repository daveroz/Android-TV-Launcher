package com.camdeco.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityEvent;

/**
 * Finds what's playing without notification access, which low-memory TVs (like the Chromecast
 * HD) refuse. When a player such as Spotify starts, it posts a media notification that carries
 * its media session; this picks up only that session so the launcher can show and control it.
 * It reads nothing on screen and ignores every other notification.
 */
public class NowPlayingService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;
        Parcelable data = event.getParcelableData();
        CharSequence pkg = event.getPackageName();
        if (!(data instanceof Notification) || pkg == null) return;
        MediaSession.Token token = mediaSession((Notification) data);
        if (token != null) MediaTokens.add(pkg.toString(), token);
    }

    @SuppressWarnings("deprecation")
    static MediaSession.Token mediaSession(Notification notification) {
        if (notification.extras == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
            }
            Parcelable token = notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
            return token instanceof MediaSession.Token ? (MediaSession.Token) token : null;
        } catch (RuntimeException e) {
            return null; // a player's extras we can't read
        }
    }

    @Override
    public void onInterrupt() {
    }
}
