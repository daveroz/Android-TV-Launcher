package com.camdeco.launcher;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Being an enabled notification listener is what lets the launcher see which media is playing
 * (switched on with: adb shell cmd notification allow_listener
 * com.camdeco.launcher/com.camdeco.launcher.MediaListenerService). While connected, it also notes
 * each track or video that plays, even when the launcher isn't on screen, so Spotify's and
 * YouTube's cards can show "Recently played". It reads no notifications.
 */
public class MediaListenerService extends NotificationListenerService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, MediaController> watched = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> callbacks = new HashMap<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions = this::watch;

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        try {
            ComponentName me = new ComponentName(this, MediaListenerService.class);
            sessions.addOnActiveSessionsChangedListener(onSessions, me, handler);
            watch(sessions.getActiveSessions(me));
        } catch (SecurityException ignored) {
            // Access was turned off again.
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(onSessions);
        for (Map.Entry<MediaSession.Token, MediaController> e : watched.entrySet()) {
            e.getValue().unregisterCallback(callbacks.get(e.getKey()));
        }
        watched.clear();
        callbacks.clear();
    }

    private void watch(List<MediaController> controllers) {
        if (controllers == null) return;
        Map<MediaSession.Token, MediaController> now = new HashMap<>();
        for (MediaController c : controllers) now.put(c.getSessionToken(), c);
        // Stop following sessions that ended.
        for (MediaSession.Token token : new HashMap<>(watched).keySet()) {
            if (!now.containsKey(token)) {
                watched.remove(token).unregisterCallback(callbacks.remove(token));
            }
        }
        // Follow new ones, and note what they're playing now.
        for (Map.Entry<MediaSession.Token, MediaController> e : now.entrySet()) {
            if (watched.containsKey(e.getKey())) continue;
            MediaController c = e.getValue();
            MediaController.Callback callback = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    note(c);
                }
            };
            c.registerCallback(callback, handler);
            watched.put(e.getKey(), c);
            callbacks.put(e.getKey(), callback);
            note(c);
        }
    }

    private void note(MediaController c) {
        MediaHistory.record(this, NowPlaying.snapshot(this, c));
    }
}
