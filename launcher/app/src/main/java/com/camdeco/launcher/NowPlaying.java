package com.camdeco.launcher;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.List;

/**
 * Follows whatever media is playing (Spotify, YouTube Music, a podcast app...) through Android's
 * media sessions. Two ways to see other apps' sessions: notification access (MediaListenerService),
 * which low-memory TVs refuse, or the Now playing accessibility switch (NowPlayingService), which
 * picks sessions up from players' notifications.
 */
final class NowPlaying implements MediaSessionManager.OnActiveSessionsChangedListener {

    interface Listener {
        void onNowPlayingChanged();
    }

    /** A snapshot of the playing track, for the Now playing row and the hero. */
    static final class Track {
        final String packageName;
        final String app;
        final String title;
        final String artist;
        final String album;
        final Bitmap art;
        final boolean playing;
        final long position;
        final long duration;
        final long updatedAt;
        final float speed;

        Track(String packageName, String app, String title, String artist, String album, Bitmap art,
              boolean playing, long position, long duration, long updatedAt, float speed) {
            this.packageName = packageName;
            this.app = app;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.art = art;
            this.playing = playing;
            this.position = position;
            this.duration = duration;
            this.updatedAt = updatedAt;
            this.speed = speed;
        }

        /** Where playback is now, moving on from the last reported position while playing. */
        long positionAt(long elapsedRealtime) {
            long now = playing && updatedAt > 0
                    ? position + (long) ((elapsedRealtime - updatedAt) * speed) : position;
            return duration > 0 ? Math.max(0, Math.min(now, duration)) : Math.max(0, now);
        }
    }

    /** Makes a controller for a session found in a notification; swapped out in tests. */
    static java.util.function.BiFunction<Context, MediaSession.Token, MediaController> controllerFactory =
            MediaController::new;

    private final Context context;
    private final MediaSessionManager sessions;
    private final ComponentName component;
    private final Handler handler;
    private final Listener listener;
    private final java.util.Map<MediaSession.Token, MediaController> fromNotifications = new java.util.HashMap<>();
    private MediaController current;
    private boolean listening;
    private boolean sessionAccess;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refresh();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            listener.onNowPlayingChanged();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    NowPlaying(Context context, Handler handler, Listener listener) {
        this.context = context;
        this.sessions = context.getSystemService(MediaSessionManager.class);
        this.component = new ComponentName(context, MediaListenerService.class);
        this.handler = handler;
        this.listener = listener;
    }

    /** Notification access: the full route, but low-memory TVs refuse to turn it on. */
    boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 27) {
            android.app.NotificationManager nm = context.getSystemService(android.app.NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(component);
        }
        return settingContains("enabled_notification_listeners", component);
    }

    /** The Now playing accessibility switch: the route that works on low-memory TVs too. */
    boolean helperOn() {
        return settingContains(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(context, NowPlayingService.class));
    }

    /** Low-memory TVs (Android's own label) don't allow notification access for most apps. */
    boolean lowMemoryTv() {
        android.app.ActivityManager am = context.getSystemService(android.app.ActivityManager.class);
        return am != null && am.isLowRamDevice();
    }

    /** Whether either route is on. */
    boolean available() {
        return hasAccess() || helperOn();
    }

    /** Starts following media: through notification access if allowed, and players' notifications. */
    void start() {
        if (listening) return;
        listening = true;
        MediaTokens.setListener(() -> handler.post(this::refresh));
        sessionAccess = false;
        if (sessions != null) {
            try {
                sessions.addOnActiveSessionsChangedListener(this, component, handler);
                sessionAccess = true;
            } catch (SecurityException e) {
                // No notification access: rely on players' notifications (NowPlayingService).
            }
        }
        refresh();
    }

    /** Stops listening while the launcher is in the background; the last track is kept. */
    void stop() {
        if (!listening) return;
        if (sessionAccess) sessions.removeOnActiveSessionsChangedListener(this);
        MediaTokens.setListener(null);
        if (current != null) current.unregisterCallback(callback);
        listening = false;
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> ignored) {
        refresh(); // look at both routes together
    }

    /** Picks what to show from every session we can see: playing first, then paused. */
    private void choose(List<MediaController> controllers) {
        MediaController best = null;
        for (MediaController c : controllers) {
            if (!worthShowing(c)) continue;
            if (isPlaying(c)) {
                best = c;
                break;
            }
            if (best == null) best = c;
        }
        setCurrent(best);
    }

    private void refresh() {
        if (!listening) {
            listener.onNowPlayingChanged();
            return;
        }
        List<MediaController> all = new java.util.ArrayList<>();
        if (sessionAccess) {
            try {
                all.addAll(sessions.getActiveSessions(component));
            } catch (SecurityException e) {
                sessionAccess = false; // access was turned off
            }
        }
        for (MediaSession.Token token : MediaTokens.all()) {
            MediaController c = fromNotifications.get(token);
            if (c == null) {
                try {
                    c = controllerFactory.apply(context, token);
                } catch (RuntimeException e) {
                    continue; // the player has gone away
                }
                fromNotifications.put(token, c);
            }
            if (!containsSession(all, c)) all.add(c);
        }
        choose(all);
    }

    private static boolean containsSession(List<MediaController> list, MediaController c) {
        for (MediaController other : list) {
            if (other.getSessionToken().equals(c.getSessionToken())) return true;
        }
        return false;
    }

    private boolean settingContains(String setting, ComponentName name) {
        String value = Settings.Secure.getString(context.getContentResolver(), setting);
        return value != null && (value.contains(name.flattenToString()) || value.contains(name.flattenToShortString()));
    }

    private void setCurrent(MediaController controller) {
        if (current != null) current.unregisterCallback(callback);
        current = controller;
        if (current != null) current.registerCallback(callback, handler);
        listener.onNowPlayingChanged();
    }

    /** The track to show, or null when nothing is playing or paused. */
    Track track() {
        MediaController c = current;
        if (c == null || !worthShowing(c)) return null;
        MediaMetadata m = c.getMetadata();
        PlaybackState s = c.getPlaybackState();
        String title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String album = text(m, MediaMetadata.METADATA_KEY_ALBUM);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        float speed = s.getPlaybackSpeed() == 0 ? 1f : s.getPlaybackSpeed();
        return new Track(c.getPackageName(), appLabel(c.getPackageName()), title, artist, album, art,
                isPlaying(c), s.getPosition(), duration, s.getLastPositionUpdateTime(), speed);
    }

    void playPause() {
        if (current == null) return;
        if (isPlaying(current)) current.getTransportControls().pause();
        else current.getTransportControls().play();
    }

    void next() {
        if (current != null) current.getTransportControls().skipToNext();
    }

    void previous() {
        if (current != null) current.getTransportControls().skipToPrevious();
    }

    /** The player app's own screen for this media, if it offers one. */
    PendingIntent sessionActivity() {
        return current == null ? null : current.getSessionActivity();
    }

    String packageName() {
        return current == null ? null : current.getPackageName();
    }

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    // ---- Helpers ----

    private static boolean worthShowing(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (s == null || m == null) return false;
        if (text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE) == null) return false;
        switch (s.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_PAUSED:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false; // stopped, error, none
        }
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static String text(MediaMetadata m, String... keys) {
        if (m == null) return null;
        for (String key : keys) {
            CharSequence value = m.getText(key);
            if (value != null && value.length() > 0) return value.toString();
        }
        return null;
    }

    private String appLabel(String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return packageName;
        }
    }
}
