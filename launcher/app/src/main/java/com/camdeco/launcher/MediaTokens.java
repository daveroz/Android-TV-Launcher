package com.camdeco.launcher;

import android.media.session.MediaSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Media sessions found in players' notifications by NowPlayingService, newest first. This is the
 * route that works without notification access, which low-memory TVs don't allow.
 */
final class MediaTokens {
    interface Listener {
        void onTokensChanged();
    }

    /** Player package to its latest session; insertion order = oldest to newest. */
    private static final Map<String, MediaSession.Token> TOKENS = new LinkedHashMap<>();
    private static Listener listener;

    private MediaTokens() {
    }

    static void add(String packageName, MediaSession.Token token) {
        Listener l;
        synchronized (TOKENS) {
            if (token.equals(TOKENS.get(packageName))) return;
            TOKENS.remove(packageName);
            TOKENS.put(packageName, token);
            l = listener;
        }
        if (l != null) l.onTokensChanged();
    }

    /** Newest first. */
    static List<MediaSession.Token> all() {
        synchronized (TOKENS) {
            List<MediaSession.Token> list = new ArrayList<>(TOKENS.values());
            java.util.Collections.reverse(list);
            return list;
        }
    }

    static void setListener(Listener l) {
        synchronized (TOKENS) {
            listener = l;
        }
    }

    /** For tests. */
    static void clear() {
        synchronized (TOKENS) {
            TOKENS.clear();
        }
    }
}
