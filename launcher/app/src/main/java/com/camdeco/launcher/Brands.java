package com.camdeco.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * Logos that match each other for the apps people run on a TV: one-colour glyphs from Simple Icons
 * (CC0; each brand belongs to its owner), with each brand's own colour. Apps not listed here use
 * the one-colour icon Android 13+ apps ship for themed icons, or their own icon.
 */
final class Brands {
    /** A brand's logo (a white glyph) and its colour. */
    static final class Brand {
        final int logo;
        final int colour;

        Brand(int logo, int colour) {
            this.logo = logo;
            this.colour = colour;
        }
    }

    private static final Map<String, Brand> BY_PACKAGE = new HashMap<>();

    static {
        add("com.netflix.ninja", R.drawable.logo_netflix, 0xFFE50914); // Netflix
        add("com.netflix.mediaclient", R.drawable.logo_netflix, 0xFFE50914); // Netflix
        add("com.google.android.youtube.tv", R.drawable.logo_youtube, 0xFFFF0000); // YouTube
        add("com.google.android.youtube", R.drawable.logo_youtube, 0xFFFF0000); // YouTube
        add("com.google.android.youtube.googletv", R.drawable.logo_youtube, 0xFFFF0000); // YouTube
        add("com.google.android.youtube.tvmusic", R.drawable.logo_youtubemusic, 0xFFFF0000); // YouTube Music
        add("com.google.android.apps.youtube.music", R.drawable.logo_youtubemusic, 0xFFFF0000); // YouTube Music
        add("com.google.android.youtube.tvkids", R.drawable.logo_youtubekids, 0xFFFF0000); // YouTube Kids
        add("com.google.android.apps.youtube.kids", R.drawable.logo_youtubekids, 0xFFFF0000); // YouTube Kids
        add("com.google.android.youtube.tvunplugged", R.drawable.logo_youtubetv, 0xFFFF0000); // YouTube TV
        add("com.spotify.tv.android", R.drawable.logo_spotify, 0xFF1ED760); // Spotify
        add("com.spotify.music", R.drawable.logo_spotify, 0xFF1ED760); // Spotify
        add("com.wbd.stream", R.drawable.logo_max, 0xFF525252); // Max
        add("com.hbo.hbonow", R.drawable.logo_max, 0xFF525252); // Max
        add("com.hbo.max.android.tv", R.drawable.logo_max, 0xFF525252); // Max
        add("com.apple.atve.androidtv.appletv", R.drawable.logo_appletv, 0xFF000000); // Apple TV
        add("com.apple.atve.amazon.appletv", R.drawable.logo_appletv, 0xFF000000); // Apple TV
        add("com.apple.android.music", R.drawable.logo_applemusic, 0xFFFA243C); // Apple Music
        add("com.plexapp.android", R.drawable.logo_plex, 0xFFEBAF00); // Plex
        add("org.xbmc.kodi", R.drawable.logo_kodi, 0xFF17B2E7); // Kodi
        add("org.videolan.vlc", R.drawable.logo_vlcmediaplayer, 0xFFFF8800); // VLC media player
        add("tv.twitch.android.app", R.drawable.logo_twitch, 0xFF9146FF); // Twitch
        add("tv.twitch.android.viewer", R.drawable.logo_twitch, 0xFF9146FF); // Twitch
        add("com.crunchyroll.crunchyroid", R.drawable.logo_crunchyroll, 0xFFFF5E00); // Crunchyroll
        add("com.cbs.ott", R.drawable.logo_paramountplus, 0xFF0064FF); // Paramount+
        add("com.cbs.app", R.drawable.logo_paramountplus, 0xFF0064FF); // Paramount+
        add("com.tubitv", R.drawable.logo_tubi, 0xFF7408FF); // Tubi
        add("com.android.vending", R.drawable.logo_googleplay, 0xFF414141); // Google Play
        add("com.google.android.videos", R.drawable.logo_googletv, 0xFF4285F4); // Google TV
        add("com.stremio.one", R.drawable.logo_stremio, 0xFF685CEE); // Stremio
        add("org.jellyfin.androidtv", R.drawable.logo_jellyfin, 0xFF00A4DC); // Jellyfin
        add("org.jellyfin.mobile", R.drawable.logo_jellyfin, 0xFF00A4DC); // Jellyfin
        add("tv.emby.embyatv", R.drawable.logo_emby, 0xFF52B54B); // Emby
        add("com.mb.android", R.drawable.logo_emby, 0xFF52B54B); // Emby
        add("com.valvesoftware.steamlink", R.drawable.logo_steam, 0xFF000000); // Steam
        add("deezer.android.tv", R.drawable.logo_deezer, 0xFFA238FF); // Deezer
        add("deezer.android.app", R.drawable.logo_deezer, 0xFFA238FF); // Deezer
        add("com.aspiro.tidal", R.drawable.logo_tidal, 0xFF000000); // TIDAL
        add("com.soundcloud.android", R.drawable.logo_soundcloud, 0xFFFF5500); // SoundCloud
        add("com.pandora.android", R.drawable.logo_pandora, 0xFF224099); // Pandora
        add("com.pandora.android.atv", R.drawable.logo_pandora, 0xFF224099); // Pandora
        add("com.mubi", R.drawable.logo_mubi, 0xFF000000); // MUBI
        add("com.vimeo.android.videoapp", R.drawable.logo_vimeo, 0xFF1AB7EA); // Vimeo
        add("com.vimeo.android.tv", R.drawable.logo_vimeo, 0xFF1AB7EA); // Vimeo
        add("com.dazn", R.drawable.logo_dazn, 0xFFF8F8F5); // DAZN
        add("com.google.android.apps.photos", R.drawable.logo_googlephotos, 0xFF4285F4); // Google Photos
        add("com.google.android.katniss", R.drawable.logo_googleassistant, 0xFF4285F4); // Google Assistant
        add("org.mozilla.firefox", R.drawable.logo_firefoxbrowser, 0xFFFF7139); // Firefox Browser
        add("org.mozilla.tv.firefox", R.drawable.logo_firefoxbrowser, 0xFFFF7139); // Firefox Browser
        add("com.retroarch", R.drawable.logo_retroarch, 0xFF000000); // RetroArch
        add("com.retroarch.aarch64", R.drawable.logo_retroarch, 0xFF000000); // RetroArch
        add("com.retroarch.ra32", R.drawable.logo_retroarch, 0xFF000000); // RetroArch
        add("com.discord", R.drawable.logo_discord, 0xFF5865F2); // Discord
        add("com.audible.application", R.drawable.logo_audible, 0xFFF8991C); // Audible
        add("au.com.shiftyjelly.pocketcasts", R.drawable.logo_pocketcasts, 0xFFF43E37); // Pocket Casts
        add("com.bambuna.podcastaddict", R.drawable.logo_podcastaddict, 0xFFF4842D); // Podcast Addict
        add("com.dailymotion.dailymotion", R.drawable.logo_dailymotion, 0xFF0A0A0A); // Dailymotion
        add("com.zhiliaoapp.musically", R.drawable.logo_tiktok, 0xFF000000); // TikTok
        add("com.ss.android.ugc.trill", R.drawable.logo_tiktok, 0xFF000000); // TikTok
        add("com.android.chrome", R.drawable.logo_googlechrome, 0xFF4285F4); // Google Chrome
        add("com.bydeluxe.d3.android.program.starz", R.drawable.logo_starz, 0xFF082125); // STARZ
        add("com.zype.mobile.nebula", R.drawable.logo_nebula, 0xFF2CADFE); // Nebula
    }

    private Brands() {
    }

    private static void add(String packageName, int logo, int colour) {
        BY_PACKAGE.put(packageName, new Brand(logo, colour));
    }

    /** The brand for this app, or null if it isn't one we have a logo for. */
    static Brand of(String packageName) {
        return BY_PACKAGE.get(packageName);
    }
}
