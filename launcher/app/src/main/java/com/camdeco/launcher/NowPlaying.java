package com.camdeco.launcher;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.List;

/**
 * Follows whatever media is playing (Spotify, YouTube Music, a podcast app...) through Android's
 * media sessions. Seeing other apps' sessions needs notification access, see MediaListenerService.
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

        /**
         * Which song this is, whatever its picture: app, title, artist and album. Players often send
         * the same song's cover again as a new picture, or for a moment without one, so the picture
         * can't tell one song from the next.
         */
        String song() {
            return packageName + '\n' + title + '\n' + artist + '\n' + album;
        }

        /** Where playback is now, moving on from the last reported position while playing. */
        long positionAt(long elapsedRealtime) {
            long now = playing && updatedAt > 0
                    ? position + (long) ((elapsedRealtime - updatedAt) * speed) : position;
            return duration > 0 ? Math.max(0, Math.min(now, duration)) : Math.max(0, now);
        }
    }

    private final Context context;
    private final MediaSessionManager sessions;
    private final ComponentName component;
    private final Handler handler;
    private final Listener listener;
    private MediaController current;
    private boolean listening;

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

    /** Whether notification access is on, which is what lets the launcher see other apps' media. */
    boolean hasAccess() {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null
                && (enabled.contains(component.flattenToString()) || enabled.contains(component.flattenToShortString()));
    }

    /** Starts following media sessions. Does nothing without notification access. */
    void start() {
        if (listening || sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(this, component, handler);
            listening = true;
            onActiveSessionsChanged(sessions.getActiveSessions(component));
        } catch (SecurityException e) {
            listening = false; // notification access is off
            setCurrent(null);
        }
    }

    /** Stops listening while the launcher is in the background; the last track is kept. */
    void stop() {
        if (!listening) return;
        sessions.removeOnActiveSessionsChangedListener(this);
        if (current != null) current.unregisterCallback(callback);
        listening = false;
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        MediaController best = null;
        MediaController sameAsCurrent = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (current != null && c.getSessionToken().equals(current.getSessionToken())) sameAsCurrent = c;
                if (!worthShowing(c)) continue;
                if (isPlaying(c)) {
                    best = c;
                    break;
                }
                if (best == null) best = c;
            }
        }
        // Nothing worth showing right now, but the last player is still around (stopped for a
        // moment between songs, say): keep following it so it's heard the moment it plays again.
        // track() is null meanwhile.
        setCurrent(best != null ? best : sameAsCurrent);
    }

    private void refresh() {
        if (!listening) {
            listener.onNowPlayingChanged();
            return;
        }
        try {
            onActiveSessionsChanged(sessions.getActiveSessions(component));
        } catch (SecurityException e) {
            setCurrent(null);
        }
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
        return snapshot(context, c);
    }

    /** A session's current track whatever its state (for the history), or null without a title. */
    static Track snapshot(Context context, MediaController c) {
        MediaMetadata m = c.getMetadata();
        PlaybackState s = c.getPlaybackState();
        String title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null) return null;
        String artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String album = text(m, MediaMetadata.METADATA_KEY_ALBUM);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        float speed = s == null || s.getPlaybackSpeed() == 0 ? 1f : s.getPlaybackSpeed();
        return new Track(c.getPackageName(), appLabel(context, c.getPackageName()), title, artist, album, art,
                isPlaying(c), s == null ? 0 : s.getPosition(), duration,
                s == null ? 0 : s.getLastPositionUpdateTime(), speed);
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

    private static String appLabel(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return packageName;
        }
    }
}
