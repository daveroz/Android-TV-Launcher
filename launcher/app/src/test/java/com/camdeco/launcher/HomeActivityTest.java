package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.provider.Settings;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowInstrumentation;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowVisualizer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Drives the launcher the way a remote would, on a simulated 1080p TV. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w960dp-h540dp-land-television-xhdpi")
public class HomeActivityTest {
    private static final String NETFLIX = "com.netflix.ninja";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String PRIME = "com.amazon.amazonvideo.livingroom";
    private static final String VLC = "org.videolan.vlc";

    private ShadowPackageManager packages;

    @Before
    public void installApps() throws Exception {
        // A TV driven by a remote is never in touch mode, so D-pad focus is visible.
        ShadowInstrumentation.getInstrumentation().setInTouchMode(false);
        MediaHistory.resetCache();
        packages = shadowOf(RuntimeEnvironment.getApplication().getPackageManager());
        installApp(NETFLIX, "Netflix", Intent.CATEGORY_LEANBACK_LAUNCHER);
        installApp(YOUTUBE, "YouTube", Intent.CATEGORY_LEANBACK_LAUNCHER);
        installApp(PRIME, "Prime Video", Intent.CATEGORY_LEANBACK_LAUNCHER);
        installApp(VLC, "VLC", Intent.CATEGORY_LAUNCHER); // phone-style app
    }

    private void installApp(String pkg, String label, String category) throws Exception {
        ComponentName component = new ComponentName(pkg, pkg + ".Main");
        ActivityInfo info = packages.addActivityIfNotPresent(component);
        info.nonLocalizedLabel = label;
        info.applicationInfo.nonLocalizedLabel = label;
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(category);
        packages.addIntentFilterForActivity(component, filter);
    }

    @Test
    public void homeGroupsAppsIntoTvAndOtherRows() throws Exception {
        HomeActivity home = start().get();
        assertEquals(List.of("TV apps: [" + NETFLIX + ", " + PRIME + ", " + YOUTUBE + "]",
                "Other apps: [" + VLC + "]"), rows(home));
        // The first card has focus and fills the hero: its name, and its wall of lamps.
        assertEquals(card(home, 0, 0), focus(home));
        assertEquals(NETFLIX, home.heroApp.packageName);
        assertEquals("TV app", home.hero.eyebrowText());
        assertEquals("Netflix", text(home, R.id.hero_title));
        assertTrue(home.hero.showsLightShow());
        assertEquals(View.GONE, home.findViewById(R.id.hero_version).getVisibility());
        assertEquals(View.GONE, home.findViewById(R.id.hero_desc).getVisibility());
        // Cards keep the 16:9 banner shape.
        View banner = card(home, 0, 0).findViewById(R.id.card);
        assertTrue(banner.getWidth() > 0);
        assertEquals(banner.getWidth() * 9 / 16, banner.getHeight());
    }

    @Test
    public void heroFollowsTheFocusedCard() throws Exception {
        HomeActivity home = start().get();
        card(home, 0, 2).requestFocus();
        idle();
        assertEquals(YOUTUBE, home.heroApp.packageName);
        card(home, 1, 0).requestFocus();
        idle();
        assertEquals(VLC, home.heroApp.packageName);
    }

    @Test
    public void heroFollowsWhenTheHighlightedCardChangesApp() throws Exception {
        HomeActivity home = start().get(); // Netflix highlighted
        card(home, 0, 0).performLongClick();
        choose("Hide");
        idle();
        // The same spot now holds Prime Video; the hero must follow it.
        AppEntry highlighted = (AppEntry) focus(home).getTag(R.id.tag_app);
        assertEquals(PRIME, highlighted.packageName);
        assertEquals(PRIME, home.heroApp.packageName);
    }

    @Test
    public void appsTabMovesCardsAndTheOrderIsUsedEverywhere() throws Exception {
        ActivityController<HomeActivity> controller = start();
        HomeActivity home = controller.get();
        home.findViewById(R.id.tab_apps).requestFocus();
        idle();
        assertEquals(View.VISIBLE, home.findViewById(R.id.apps_page).getVisibility());
        assertEquals(View.GONE, home.findViewById(R.id.rows).getVisibility());
        assertEquals(0f, home.findViewById(R.id.hero).getAlpha(), 0.01f);
        assertEquals(List.of(NETFLIX, PRIME, VLC, YOUTUBE), grid(home));

        gridCard(home, 0).requestFocus();
        idle();
        gridCard(home, 0).performLongClick();
        choose("Move");
        press(home, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(home, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(NETFLIX, grid(home).get(2));
        press(home, KeyEvent.KEYCODE_DPAD_LEFT);
        press(home, KeyEvent.KEYCODE_DPAD_CENTER); // drop it
        assertEquals(List.of(PRIME, NETFLIX, VLC, YOUTUBE), grid(home));
        assertEquals("TV apps: [" + PRIME + ", " + NETFLIX + ", " + YOUTUBE + "]", rows(home).get(0));

        // After the drop, arrows move focus again instead of the card.
        press(home, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(List.of(PRIME, NETFLIX, VLC, YOUTUBE), grid(home));

        // The order survives the launcher being restarted.
        controller.pause().stop().destroy();
        assertEquals(List.of(PRIME, NETFLIX, VLC, YOUTUBE), grid(start().get()));
    }

    @Test
    public void sheetsOpenWithTheFirstChoiceHighlighted() throws Exception {
        HomeActivity home = start().get();
        card(home, 0, 0).performLongClick();
        idle();
        Dialog sheet = ShadowDialog.getLatestDialog();
        LinearLayout items = sheet.findViewById(R.id.sheet_rows);
        assertEquals(items.getChildAt(0), sheet.getWindow().getDecorView().findFocus());
    }

    @Test
    public void theCardMenuIsSimple() throws Exception {
        HomeActivity home = start().get();
        card(home, 0, 0).performLongClick();
        // No Favourites, and Move is only offered on the Apps page.
        assertEquals(List.of("Hide", "App info", "Uninstall"), sheetItems());
    }

    @Test
    @Config(qualifiers = "+es")
    public void followsTheTvLanguage() throws Exception {
        HomeActivity home = start().get(); // the TV is set to Spanish
        assertEquals("Inicio", text(home, R.id.tab_home));
        assertEquals(List.of("Apps de TV: [" + NETFLIX + ", " + PRIME + ", " + YOUTUBE + "]",
                "Otras apps: [" + VLC + "]"), rows(home));
        assertEquals("App de TV", home.hero.eyebrowText());

        card(home, 0, 0).performLongClick();
        assertEquals(List.of("Ocultar", "Información de la app", "Desinstalar"), sheetItems());
    }

    @Test
    public void playStoreSitsInTheTopBarBeforeSettings() throws Exception {
        HomeActivity home = start().get();
        View store = home.findViewById(R.id.tab_store);
        View settings = home.findViewById(R.id.tab_settings);
        assertEquals(View.GONE, store.getVisibility()); // not installed

        installApp(HomeActivity.PLAY_STORE, "Google Play Store", Intent.CATEGORY_LEANBACK_LAUNCHER);
        RuntimeEnvironment.getApplication().sendBroadcast(new Intent(Intent.ACTION_PACKAGE_ADDED,
                Uri.parse("package:" + HomeActivity.PLAY_STORE)));
        waitFor(() -> store.getVisibility() == View.VISIBLE);
        LinearLayout tabs = home.findViewById(R.id.tabs);
        assertEquals(tabs.indexOfChild(settings) - 1, tabs.indexOfChild(store));

        // Left of settings on the remote; OK opens the Play Store.
        settings.requestFocus();
        assertEquals(store, settings.focusSearch(View.FOCUS_LEFT));
        store.requestFocus();
        press(home, KeyEvent.KEYCODE_DPAD_CENTER);
        Intent started = shadowOf(home).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(HomeActivity.PLAY_STORE, started.getComponent().getPackageName());
    }

    @Test
    public void hiddenAppsDisappearAndCanBeShownAgain() throws Exception {
        HomeActivity home = start().get();
        card(home, 0, 0).performLongClick();
        choose("Hide");
        assertEquals("TV apps: [" + PRIME + ", " + YOUTUBE + "]", rows(home).get(0));
        assertFalse(grid(home).contains(NETFLIX));

        home.findViewById(R.id.tab_settings).performClick();
        idle();
        choose("Hidden apps (1)");
        choose("Netflix"); // tick it
        choose("Show again");
        assertEquals("TV apps: [" + NETFLIX + ", " + PRIME + ", " + YOUTUBE + "]", rows(home).get(0));
    }

    @Test
    public void backGoesUpToTheTabsThenFromAppsToHome() throws Exception {
        HomeActivity home = start().get();
        View tabHome = home.findViewById(R.id.tab_home);
        View tabApps = home.findViewById(R.id.tab_apps);

        press(home, KeyEvent.KEYCODE_BACK);
        assertEquals(tabHome, focus(home));
        press(home, KeyEvent.KEYCODE_BACK);
        assertEquals(tabHome, focus(home));
        assertFalse(home.isFinishing());

        tabApps.requestFocus();
        idle();
        gridCard(home, 1).requestFocus();
        idle();
        press(home, KeyEvent.KEYCODE_BACK);
        assertEquals(tabApps, focus(home));
        press(home, KeyEvent.KEYCODE_BACK);
        assertEquals(tabHome, focus(home));
        assertEquals(View.VISIBLE, home.findViewById(R.id.rows).getVisibility());
    }

    @Test
    public void upFromTheTopRowGoesToTheTabs() throws Exception {
        HomeActivity home = start().get();
        // What the system does when Up is pressed: ask the focused view for the next focus upward.
        assertEquals(home.findViewById(R.id.tab_home), card(home, 0, 0).focusSearch(View.FOCUS_UP));
        assertEquals(home.findViewById(R.id.tab_home), card(home, 0, 2).focusSearch(View.FOCUS_UP));

        home.findViewById(R.id.tab_apps).requestFocus();
        idle();
        gridCard(home, 1).requestFocus();
        idle();
        assertEquals(home.findViewById(R.id.tab_apps), gridCard(home, 1).focusSearch(View.FOCUS_UP));
    }

    @Test
    public void focusComingUpFromTheCardsLandsOnTheCurrentTab() throws Exception {
        HomeActivity home = start().get();
        TabBar tabs = home.findViewById(R.id.tabs);
        ArrayList<View> targets = new ArrayList<>();
        tabs.addFocusables(targets, View.FOCUS_UP);
        assertEquals(List.of(home.findViewById(R.id.tab_home)), targets);

        // Once inside the bar, every tab and button can be reached.
        home.findViewById(R.id.tab_home).requestFocus();
        targets.clear();
        tabs.addFocusables(targets, View.FOCUS_RIGHT);
        assertEquals(List.of(home.findViewById(R.id.tab_home), home.findViewById(R.id.tab_apps),
                home.findViewById(R.id.tab_settings)), targets); // the Play Store isn't installed
    }

    @Test
    public void openingACardLaunchesTheTvActivity() throws Exception {
        HomeActivity home = start().get();
        card(home, 0, 0).performClick();
        Intent started = shadowOf(home).getNextStartedActivity();
        assertEquals(new ComponentName(NETFLIX, NETFLIX + ".Main"), started.getComponent());
        assertTrue(started.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER));
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void menuButtonOpensTheFocusedCardsMenu() throws Exception {
        HomeActivity home = start().get();
        press(home, KeyEvent.KEYCODE_MENU);
        Dialog sheet = ShadowDialog.getLatestDialog();
        assertNotNull(sheet);
        assertTrue(sheet.isShowing());
        assertEquals("Netflix", ((TextView) sheet.findViewById(R.id.sheet_title)).getText().toString());
    }

    @Test
    public void newlyInstalledAppsAppearWithoutRestarting() throws Exception {
        HomeActivity home = start().get();
        installApp("com.plexapp.android", "Plex", Intent.CATEGORY_LEANBACK_LAUNCHER);
        home.sendBroadcast(new Intent(Intent.ACTION_PACKAGE_ADDED,
                Uri.fromParts("package", "com.plexapp.android", null)));
        for (int i = 0; i < 100 && !grid(home).contains("com.plexapp.android"); i++) {
            idle();
            Thread.sleep(20);
        }
        assertTrue(rows(home).get(0).contains("com.plexapp.android"));
    }

    @Test
    public void streamingAppsGlowInTheirOwnLightWithNoPreviews() throws Exception {
        android.graphics.Bitmap red = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888);
        red.eraseColor(0xFFE50914); // Netflix red
        ActivityInfo info = packages.addActivityIfNotPresent(new ComponentName(NETFLIX, NETFLIX + ".Main"));
        info.banner = 0x7f990001;
        packages.addDrawableResolution(NETFLIX, info.banner,
                new android.graphics.drawable.BitmapDrawable(RuntimeEnvironment.getApplication().getResources(), red));
        HomeActivity home = start().get(); // Netflix is the first card and has focus
        waitFor(() -> hue(home.scene().key) < 10 || hue(home.scene().key) > 350);
        // No shows from the internet: its name, and a light show in its own red.
        assertEquals("Netflix", text(home, R.id.hero_title));
        assertTrue(home.hero.showsLightShow());
        assertFalse(home.hero.showsPhoto());
        assertFalse(home.hero.showsRecord());
        assertEquals(home.scene().key, home.hero.showColour());
        // Its card: Netflix's own red, with its logo in white.
        View card = card(home, 0, 0).findViewById(R.id.card);
        assertEquals(0xFFE50914, ((android.graphics.drawable.GradientDrawable) card.getBackground()).getColor().getDefaultColor());
        android.widget.ImageView logo = card.findViewById(R.id.logo);
        assertNotNull(logo.getDrawable());
        assertEquals(android.graphics.Color.WHITE, logo.getImageTintList().getDefaultColor());
        // The launcher doesn't use the internet at all.
        String[] permissions = RuntimeEnvironment.getApplication().getPackageManager()
                .getPackageInfo(RuntimeEnvironment.getApplication().getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions;
        assertFalse(java.util.Arrays.asList(permissions).contains(android.Manifest.permission.INTERNET));
    }

    @Test
    public void whatsPlayingShowsAsASmallPlayerInTheTopBar() throws Exception {
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        MediaController spotify = play("com.spotify.tv.android", "Midnight City", "M83", null);

        // A mini player in the top bar; the rows and focus stay as they were.
        assertEquals(View.VISIBLE, home.findViewById(R.id.np_bar).getVisibility());
        assertEquals("Midnight City · M83", text(home, R.id.np_title));
        assertTrue(rows(home).get(0).startsWith("TV apps"));
        assertEquals(card(home, 0, 0), focus(home));

        // On the mini player, the hero shows the track.
        home.findViewById(R.id.np_open).requestFocus();
        idle();
        assertEquals("Midnight City", text(home, R.id.hero_title));
        assertEquals("M83", text(home, R.id.hero_kind));

        // Spotify's own card shows what's playing too; other apps keep their banner.
        card(home, 0, 2).requestFocus(); // Netflix, Prime Video, Spotify, YouTube
        idle();
        assertEquals("Midnight City", text(home, R.id.hero_title));
        card(home, 0, 3).requestFocus();
        idle();
        assertEquals("YouTube", text(home, R.id.hero_title));
        assertTrue(home.hero.showsLightShow());

        // Paused: still there. Stopped: gone.
        shadowOf(spotify).setPlaybackState(state(PlaybackState.STATE_PAUSED));
        shadowOf(spotify).executeOnPlaybackStateChanged(spotify.getPlaybackState());
        idle();
        assertEquals(View.VISIBLE, home.findViewById(R.id.np_bar).getVisibility());
        shadowOf(spotify).setPlaybackState(state(PlaybackState.STATE_STOPPED));
        shadowOf(spotify).executeOnPlaybackStateChanged(spotify.getPlaybackState());
        afterTheGrace();
        assertEquals(View.GONE, home.findViewById(R.id.np_bar).getVisibility());
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void squareCoversBecomeAnLpLitInTheirOwnColours() throws Exception {
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        play("com.spotify.tv.android", "Midnight City", "M83", cover());
        card(home, 0, 2).requestFocus(); // Spotify
        waitFor(() -> home.hero.showsRecord() && hue(home.scene().key) > 300);
        assertFalse(home.hero.showsPhoto());
        assertEquals("Now playing · Spotify", home.hero.eyebrowText());
        // The pink cover's primary colour is the only light.
        assertEquals(330f, hue(home.scene().key), 8f);
        StageView stage = home.findViewById(R.id.stage);
        assertTrue(home.scene().sameAs(stage.scene()));
    }

    @Test
    public void aSongShowsHowFarThroughItIs() throws Exception {
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        play("com.spotify.tv.android", "Midnight City", "M83", null); // a minute into four
        card(home, 0, 2).requestFocus();
        idle();
        assertEquals("4:00", text(home, R.id.hero_duration));
        assertTrue(home.hero.elapsedText(), home.hero.elapsedText().startsWith("1:0"));
        ProgressLineView bar = home.findViewById(R.id.hero_bar);
        assertTrue("about a quarter: " + bar.progress(), bar.progress() > 0.24f && bar.progress() < 0.3f);
        // The song's progress takes the place of the remote's hints.
        assertEquals(View.GONE, home.findViewById(R.id.hero_hints).getVisibility());

        // It keeps counting while it plays.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertTrue(home.hero.elapsedText(), home.hero.elapsedText().startsWith("1:1"));

        // An app's card: the hints are back, the progress gone.
        card(home, 0, 0).requestFocus();
        idle();
        assertEquals(View.VISIBLE, home.findViewById(R.id.hero_hints).getVisibility());
        assertNull(home.hero.elapsedText());
    }

    @Test
    public void everyCardIsOneColourWithAMatchingLogo() throws Exception {
        HomeActivity home = start().get();
        idle();
        // YouTube has a brand logo: its red, with a white glyph.
        View youtube = card(home, 0, 2).findViewById(R.id.card);
        assertEquals(0xFFFF0000, ((android.graphics.drawable.GradientDrawable) youtube.getBackground()).getColor().getDefaultColor());
        assertNotNull(((android.widget.ImageView) youtube.findViewById(R.id.logo)).getImageTintList());
        // Prime Video has none here and no colour of its own in this test: a slate card with its icon.
        View prime = card(home, 0, 1).findViewById(R.id.card);
        android.graphics.drawable.GradientDrawable shape = (android.graphics.drawable.GradientDrawable) prime.getBackground();
        assertEquals(CardAdapter.cardColour(AppColors.NONE), shape.getColor().getDefaultColor());
        // Every brand logo is a drawable that loads.
        for (String pkg : new String[]{NETFLIX, YOUTUBE, VLC, "com.spotify.tv.android", "com.android.vending", "com.plexapp.android"}) {
            assertNotNull(pkg, home.getDrawable(Brands.of(pkg).logo));
        }
        // A light brand colour gets a dark logo, so it still reads.
        assertTrue(Scene.luminance(CardAdapter.cardColour(0xFF1ED760)) > 0.55f); // Spotify green
    }

    @Test
    public void theAppsPageKeepsHomesLight() throws Exception {
        HomeActivity home = start().get();
        StageView stage = home.findViewById(R.id.stage);
        idle();
        Scene before = stage.scene();
        home.findViewById(R.id.tab_apps).requestFocus();
        idle();
        assertTrue("the same background", before.sameAs(stage.scene()));
        assertEquals("4 apps", text(home, R.id.apps_count));
    }

    @Test
    public void theFocusedCardIsTheBrightest() throws Exception {
        HomeActivity home = start().get();
        idle();
        View focused = card(home, 0, 0), other = card(home, 0, 1);
        assertEquals(1f, focused.getAlpha(), 0.01f);
        assertTrue("others step back: " + other.getAlpha(), other.getAlpha() < 0.8f);
        assertTrue(((TileView) focused.findViewById(R.id.card)).showsFocus());
        assertFalse(((TileView) other.findViewById(R.id.card)).showsFocus());
        // The row's title lights up in the scene's colour.
        TextView title = ((View) focused.getParent().getParent()).findViewById(R.id.row_title);
        assertEquals(home.scene().label, title.getCurrentTextColor());
    }

    @Test
    public void theCogOpensSettingsWithTheTvsOwnFirst() throws Exception {
        HomeActivity home = start().get();
        home.findViewById(R.id.tab_settings).performClick();
        List<String> items = sheetItems();
        assertEquals("TV settings", items.get(0));
        assertTrue(items.contains("Hidden apps (0)"));
        choose("TV settings");
        Intent started = shadowOf(home).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Settings.ACTION_SETTINGS, started.getAction());
    }

    private static float hue(int colour) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(colour, hsv);
        return hsv[0];
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void playingMusicShowsAnLpThatSpinsAndStopsOnPause() throws Exception {
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        MediaController spotify = play("com.spotify.tv.android", "Midnight City", "M83", cover());
        card(home, 0, 2).requestFocus(); // Spotify
        idle();
        assertTrue("the cover is an LP", home.hero.showsRecord());
        RecordView record = home.hero.recordForTests();
        assertTrue("its record comes out and spins", record.isPlaying());

        shadowOf(spotify).setPlaybackState(state(PlaybackState.STATE_PAUSED));
        shadowOf(spotify).executeOnPlaybackStateChanged(spotify.getPlaybackState());
        idle();
        assertFalse("paused: the record stops", record.isPlaying());
        assertTrue(home.hero.showsRecord());

        card(home, 0, 0).requestFocus(); // Netflix: its own card, no LP
        idle();
        assertFalse(home.hero.showsRecord());
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aBlipFromThePlayerDoesNotLookLikeANewSong() throws Exception {
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        MediaController spotify = play("com.spotify.tv.android", "Speed", "Bad Colours", cover());
        card(home, 0, 2).requestFocus(); // Spotify
        idle();
        RecordView record = home.hero.recordForTests();
        android.widget.ImageView miniCover = home.findViewById(R.id.np_art);
        assertTrue(home.hero.showsRecord());

        // The player resends the song for a moment without its cover, then with a new copy of it.
        resend(spotify, "Speed", "Bad Colours", null);
        assertFalse("not a new song", record.songChanging());
        assertTrue("the mini player keeps the cover", miniCover.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
        resend(spotify, "Speed", "Bad Colours", cover());
        assertFalse("still not a new song", record.songChanging());

        // It drops its session for half a second, then comes back.
        shadowOf(spotify).setPlaybackState(state(PlaybackState.STATE_STOPPED));
        shadowOf(spotify).executeOnPlaybackStateChanged(spotify.getPlaybackState());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals("nothing changes during the blip", View.VISIBLE, home.findViewById(R.id.np_bar).getVisibility());
        assertTrue(home.hero.showsRecord());
        shadowOf(spotify).setPlaybackState(state(PlaybackState.STATE_PLAYING));
        shadowOf(spotify).executeOnPlaybackStateChanged(spotify.getPlaybackState());
        afterTheGrace();
        assertEquals(View.VISIBLE, home.findViewById(R.id.np_bar).getVisibility());
        assertTrue(home.hero.showsRecord());
        assertFalse(record.songChanging());
        assertTrue(record.isPlaying());

        // A real next song does change the record.
        android.graphics.Bitmap other = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888);
        other.eraseColor(0xFF1E40AF);
        resend(spotify, "Promise", "Bad Colours", other);
        assertTrue("a new song", record.songChanging());
    }

    /** The player sends the song's details again, as players do. */
    private static void resend(MediaController controller, String title, String artist, android.graphics.Bitmap art) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 240000);
        if (art != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        shadowOf(controller).setMetadata(metadata.build());
        shadowOf(controller).executeOnMetadataChanged(controller.getMetadata());
        idle();
    }

    /** Past the moment the launcher waits before deciding a song has really stopped. */
    private static void afterTheGrace() {
        idle();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeActivity.TRACK_GONE_MS + 100));
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void appsWithNothingToShowGlowWithTheirOwnColours() throws Exception {
        android.graphics.Bitmap orange = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
        orange.eraseColor(0xFFFF8800); // VLC's cone
        packages.setUnbadgedApplicationIcon(VLC, new android.graphics.drawable.BitmapDrawable(
                RuntimeEnvironment.getApplication().getResources(), orange));
        HomeActivity home = start().get();

        card(home, 1, 0).requestFocus(); // VLC: no shows, nothing playing
        waitFor(() -> VLC.equals(home.heroApp.packageName) && home.hero.showsLightShow());
        assertEquals("App", home.hero.eyebrowText()); // not a TV app
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(home.hero.showColour(), hsv);
        assertTrue("glows orange, not " + hsv[0], hsv[0] > 20 && hsv[0] < 45 && hsv[1] > 0.5f);

        // Something playing in it: the LP, lit by the cover's colours, replaces the wall.
        play(VLC, "Midnight City", "M83", cover());
        waitFor(() -> hue(home.scene().key) > 300);
        assertFalse(home.hero.showsLightShow());
        assertTrue(home.hero.showsRecord());
    }

    @Test
    public void theLightShowSitsBetweenTheTopBarAndTheRowsAtTheScreenMargin() throws Exception {
        HomeActivity home = start().get(); // Netflix, the first row in place
        float dp = home.getResources().getDisplayMetrics().density;
        LightShowView wall = home.findViewById(R.id.hero_show);
        View topBar = (View) home.findViewById(R.id.tabs).getParent();
        View title = home.findViewById(R.id.hero_text);
        RecyclerView rows = home.findViewById(R.id.rows);
        int screenWidth = home.findViewById(R.id.root).getWidth();

        int[] at = new int[2];
        wall.getLocationInWindow(at);
        float[] bounds = wall.showBounds();
        float wallLeft = at[0] + bounds[0], wallTop = at[1] + bounds[1];
        float wallRight = at[0] + bounds[2], wallBottom = at[1] + bounds[3];
        topBar.getLocationInWindow(at);
        int barBottom = at[1] + topBar.getHeight();
        title.getLocationInWindow(at);
        int titleTop = at[1], titleRight = at[0] + title.getWidth();
        rows.getChildAt(0).getLocationInWindow(at);
        int rowsTop = at[1];

        assertTrue("clear of the top bar", wallTop - barBottom >= 6 * dp);
        assertTrue("starts above the text's top", wallTop <= titleTop);
        assertEquals("ends at the screen margin", screenWidth - 48 * dp, wallRight, 1f);
        assertTrue("ends above the rows", rowsTop - wallBottom >= 16 * dp);
        assertEquals("from the left margin", 48 * dp, wallLeft, 17.6f * dp);
        assertTrue("right across, behind the text too", wallLeft < titleRight);
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void mediaAppsShowWhatWasRecentlyPlayedWhenIdle() throws Exception {
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        Context app = RuntimeEnvironment.getApplication();
        MediaController spotify = play("com.spotify.tv.android", "Midnight City", "M83", cover());
        waitFor(() -> !MediaHistory.withCovers(app, "com.spotify.tv.android").isEmpty()); // saved in the background

        shadowOf(spotify).setPlaybackState(state(PlaybackState.STATE_STOPPED));
        shadowOf(spotify).executeOnPlaybackStateChanged(spotify.getPlaybackState());
        afterTheGrace();
        assertEquals(View.GONE, home.findViewById(R.id.np_bar).getVisibility());

        // Nothing playing: Spotify's card shows what was played, with its cover, instead of the logo.
        card(home, 0, 2).requestFocus();
        waitFor(() -> "Midnight City".equals(text(home, R.id.hero_title)) && home.hero.showsRecord());
        assertEquals("Recently played · Spotify", home.hero.eyebrowText());
        assertEquals("M83", text(home, R.id.hero_kind));
        assertEquals(View.GONE, home.findViewById(R.id.hero_desc).getVisibility());

        // Apps with no history keep their wall of lamps.
        card(home, 0, 0).requestFocus(); // Netflix
        idle();
        assertEquals("Netflix", text(home, R.id.hero_title));
        assertTrue(home.hero.showsLightShow());
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aVideosThumbnailIsNotShownOnceItStops() throws Exception {
        HomeActivity home = start().get();
        Context app = RuntimeEnvironment.getApplication();
        android.graphics.Bitmap thumbnail = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888);
        thumbnail.eraseColor(0xFF2266AA);
        MediaController youtube = play(YOUTUBE, "Lofi Hip Hop Radio", "Lofi Girl", thumbnail);
        waitFor(() -> !MediaHistory.withCovers(app, YOUTUBE).isEmpty());
        shadowOf(youtube).setPlaybackState(state(PlaybackState.STATE_STOPPED));
        shadowOf(youtube).executeOnPlaybackStateChanged(youtube.getPlaybackState());
        afterTheGrace();

        // No previews: YouTube's card shows its name and wall, not the video it played.
        card(home, 0, 2).requestFocus();
        idle();
        assertEquals("YouTube", text(home, R.id.hero_title));
        assertNull(home.hero.currentFeature());
        assertTrue(home.hero.showsLightShow());
        assertFalse(home.hero.showsPhoto());
    }

    private static android.graphics.Bitmap cover() {
        android.graphics.Bitmap cover = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888);
        cover.eraseColor(0xFFDB2777);
        return cover;
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void theSoundWaveFollowsTheMusicUnderTheSong() throws Exception {
        allowListening();
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        play("com.spotify.tv.android", "Midnight City", "M83", cover());
        card(home, 0, 2).requestFocus(); // Spotify: the hero shows the song, with the wave under it
        idle();
        assertTrue(home.hero.showsWave());

        // The TV shares its sound: the panel follows it. A bassy song lights the bass columns...
        android.media.audiofx.Visualizer visualizer = home.soundWave.visualizerForTests();
        assertNotNull(visualizer);
        ShadowVisualizer audio = org.robolectric.shadow.api.Shadow.extract(visualizer);
        float[] levels = new float[SoundWave.COLUMNS];
        audio.setSource(spectrum(60, 1, 1));
        audio.triggerDataCapture();
        assertEquals(SoundWave.Status.FOLLOWING, home.soundWave.status());
        home.soundWave.levels(1.0, levels);
        assertTrue("bass lit", average(levels, 40, 250) > 0.7f);
        assertTrue("little voice or treble", average(levels, 250, 4000) < 0.35f && average(levels, 4000, 16000) < 0.35f);

        // ...a sung part the voice columns...
        audio.setSource(spectrum(2, 30, 1));
        audio.triggerDataCapture();
        home.soundWave.levels(1.0, levels);
        assertTrue("voice lit", average(levels, 250, 4000) > 0.5f);
        assertTrue(average(levels, 40, 250) < 0.3f && average(levels, 4000, 16000) < 0.3f);

        // ...and cymbals the treble, even though they carry less raw energy than the kick under them.
        audio.setSource(spectrum(6, 3, 3));
        audio.triggerDataCapture();
        home.soundWave.levels(1.0, levels);
        float bass = average(levels, 40, 250), voice = average(levels, 250, 4000), treble = average(levels, 4000, 16000);
        assertTrue("treble leads: " + bass + " " + voice + " " + treble, treble > bass && treble > voice);

        // The menu says so.
        home.findViewById(R.id.tab_settings).performClick();
        choose("Audio visualizer: On");
        assertTrue(sheetMessage().contains("it follows the music."));
        choose("Close");

        // Other apps' cards don't get a wave.
        card(home, 0, 0).requestFocus(); // Netflix
        idle();
        assertFalse(home.hero.showsWave());
        assertNull(home.soundWave.visualizerForTests()); // and Deco stops listening
    }

    @Test
    public void aTvThatKeepsItsSoundPrivateStillGetsAMovingLine() throws Exception {
        allowListening();
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        MediaController spotify = play("com.spotify.tv.android", "Midnight City", "M83", null);
        card(home, 0, 2).requestFocus();
        idle();
        ShadowVisualizer audio = org.robolectric.shadow.api.Shadow.extract(home.soundWave.visualizerForTests());
        audio.setSource(music(0)); // only silence, however long it plays
        for (int i = 0; i < 7; i++) {
            audio.triggerDataCapture();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        }
        assertEquals(SoundWave.Status.TV_SILENT, home.soundWave.status());
        float[] levels = new float[SoundWave.COLUMNS];
        home.soundWave.levels(1.3, levels);
        assertTrue("it moves on its own while playing", max(levels) > 0.2f);

        // Paused: the line goes flat.
        shadowOf(spotify).setPlaybackState(state(PlaybackState.STATE_PAUSED));
        shadowOf(spotify).executeOnPlaybackStateChanged(spotify.getPlaybackState());
        idle();
        home.soundWave.levels(1.3, levels);
        assertEquals(0f, max(levels), 0f);
        assertTrue(home.hero.showsWave()); // still there, just still
    }

    @Test
    public void withoutPermissionTheWaveMovesOnItsOwnAndCanBeTurnedOff() throws Exception {
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        play("com.spotify.tv.android", "Midnight City", "M83", null);
        home.findViewById(R.id.np_open).requestFocus(); // the mini player: the hero shows the song
        idle();
        assertTrue(home.hero.showsWave());
        assertNull(home.soundWave.visualizerForTests()); // not allowed to listen
        assertEquals(SoundWave.Status.NO_PERMISSION, home.soundWave.status());
        float[] levels = new float[SoundWave.COLUMNS];
        home.soundWave.levels(2.0, levels);
        assertTrue(max(levels) > 0.2f);

        // The menu offers to allow it, and to turn the wave off.
        home.findViewById(R.id.tab_settings).performClick();
        choose("Audio visualizer: On");
        assertTrue(sheetMessage().contains("Deco isn't allowed to hear the TV's sound"));
        assertEquals(List.of("Let Deco hear the TV's sound", "Turn off the visualizer", "Close"), sheetItems());
        choose("Turn off the visualizer");
        assertFalse(home.hero.showsWave());
        home.findViewById(R.id.tab_settings).performClick();
        assertTrue(sheetItems().contains("Audio visualizer: Off"));
    }

    private static void allowListening() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
    }

    /** The TV's sound as the visualizer sees it: the same loudness in every frequency. */
    private static ShadowVisualizer.VisualizerSource music(int loudness) {
        return new ShadowVisualizer.VisualizerSource() {
            @Override
            public int getSamplingRate() {
                return 44100000; // in millihertz
            }

            @Override
            public int getFft(byte[] fft) {
                java.util.Arrays.fill(fft, (byte) loudness);
                return android.media.audiofx.Visualizer.SUCCESS;
            }
        };
    }

    /** Music as the visualizer sees it, with this much bass (to 250 Hz), voice (to 4 kHz) and treble. */
    private static ShadowVisualizer.VisualizerSource spectrum(int bass, int voice, int treble) {
        return new ShadowVisualizer.VisualizerSource() {
            @Override
            public int getSamplingRate() {
                return 44100000; // in millihertz
            }

            @Override
            public int getFft(byte[] fft) {
                double binHz = 44100.0 / fft.length;
                for (int k = 1; k < fft.length / 2; k++) {
                    double hz = k * binHz;
                    byte v = (byte) (hz <= 250 ? bass : hz <= 4000 ? voice : treble);
                    fft[2 * k] = v;
                    fft[2 * k + 1] = v;
                }
                return android.media.audiofx.Visualizer.SUCCESS;
            }
        };
    }

    /** The average level of the panel's columns between these frequencies. */
    private static float average(float[] levels, double fromHz, double toHz) {
        int from = SoundWave.columnOf(fromHz * 1.01), to = SoundWave.columnOf(toHz * 0.99);
        float sum = 0;
        for (int c = from; c <= to; c++) sum += levels[c];
        return sum / (to - from + 1);
    }

    private static float max(float[] values) {
        float m = 0;
        for (float v : values) m = Math.max(m, v);
        return m;
    }

    private static String sheetMessage() {
        return ((TextView) ShadowDialog.getLatestDialog().findViewById(R.id.sheet_message)).getText().toString();
    }

    @Test
    public void youTubeShowsTheVideoThumbnailFullScreen() throws Exception {
        HomeActivity home = start().get();
        android.graphics.Bitmap thumbnail = android.graphics.Bitmap.createBitmap(1280, 720, android.graphics.Bitmap.Config.ARGB_8888);
        play(YOUTUBE, "Lofi Hip Hop Radio", "Lofi Girl", thumbnail);
        card(home, 0, 2).requestFocus(); // YouTube
        idle();
        assertEquals("Lofi Hip Hop Radio", text(home, R.id.hero_title));
        android.view.ViewGroup.LayoutParams art = home.findViewById(R.id.hero_art).getLayoutParams();
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, art.width);
    }

    @Test
    public void withoutNotificationAccessThereIsNoPlayerRow() throws Exception {
        HomeActivity home = start().get();
        home.findViewById(R.id.tab_settings).performClick();
        idle();
        assertTrue(sheetItems().contains("Now playing: Off"));
        assertEquals(View.GONE, home.findViewById(R.id.np_bar).getVisibility());
    }

    @Test
    public void homeButtonServiceOnlyReactsToGoogleTv() {
        HomeButtonService service = Robolectric.setupService(HomeButtonService.class);

        AccessibilityEvent other = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        other.setPackageName(YOUTUBE);
        service.onAccessibilityEvent(other);
        assertNull(shadowOf(service).getNextStartedActivity());

        AccessibilityEvent googleTv = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        googleTv.setPackageName(HomeButtonService.GOOGLE_TV);
        service.onAccessibilityEvent(googleTv);
        Intent started = shadowOf(service).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(HomeActivity.class.getName(), started.getComponent().getClassName());
    }

    @Test
    public void manifestDeclaresTheHomeEntry() {
        PackageManager pm = RuntimeEnvironment.getApplication().getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        boolean found = false;
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            if (ri.activityInfo.name.equals(HomeActivity.class.getName())) found = true;
        }
        assertTrue("HomeActivity must answer the HOME intent", found);
    }

    // ---- helpers ----

    private ActivityController<HomeActivity> start() throws Exception {
        ActivityController<HomeActivity> controller = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity home = controller.get();
        for (int i = 0; i < 250; i++) {
            idle();
            View focused = focus(home);
            if (focused != null && focused.getTag(R.id.tag_app) != null) return controller;
            Thread.sleep(20);
        }
        throw new AssertionError("the launcher never showed its apps");
    }

    private interface Check {
        boolean ok();
    }

    /** Waits for background work (TVmaze, images) to show up on screen. */
    private static void waitFor(Check check) throws Exception {
        for (int i = 0; i < 150; i++) {
            idle();
            if (check.ok()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting");
    }

    private static PlaybackState state(int state) {
        return new PlaybackState.Builder().setState(state, 60000, state == PlaybackState.STATE_PLAYING ? 1f : 0f).build();
    }

    /** Something starts playing in an app, with notification access on. */
    private static MediaController play(String pkg, String title, String artist, android.graphics.Bitmap art) {
        Context app = RuntimeEnvironment.getApplication();
        Settings.Secure.putString(app.getContentResolver(), "enabled_notification_listeners",
                new ComponentName(app, MediaListenerService.class).flattenToString());
        MediaController controller = new MediaSession(app, pkg).getController();
        shadowOf(controller).setPackageName(pkg);
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 240000);
        if (art != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        shadowOf(controller).setMetadata(metadata.build());
        shadowOf(controller).setPlaybackState(state(PlaybackState.STATE_PLAYING));
        shadowOf(app.getSystemService(MediaSessionManager.class)).addController(controller);
        idle();
        return controller;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
    }

    private static View focus(HomeActivity home) {
        return home.getWindow().getDecorView().findFocus();
    }

    private static String text(HomeActivity home, int id) {
        return ((TextView) home.findViewById(id)).getText().toString();
    }

    private static void press(HomeActivity home, int keyCode) {
        home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        idle();
    }

    /** Each Home row as "Title: [packages]". */
    private static List<String> rows(HomeActivity home) {
        RecyclerView rows = home.findViewById(R.id.rows);
        List<String> result = new ArrayList<>();
        for (RowsAdapter.Row row : ((RowsAdapter) rows.getAdapter()).rows()) {
            List<String> packages = new ArrayList<>();
            for (AppEntry app : row.apps) packages.add(app.packageName);
            result.add(row.title + ": " + packages);
        }
        return result;
    }

    private static List<String> grid(HomeActivity home) {
        RecyclerView grid = home.findViewById(R.id.apps_grid);
        List<String> result = new ArrayList<>();
        for (AppEntry app : ((CardAdapter) grid.getAdapter()).items()) result.add(app.packageName);
        return result;
    }

    private static View card(HomeActivity home, int row, int column) {
        RecyclerView rows = home.findViewById(R.id.rows);
        RowsAdapter.Holder holder = (RowsAdapter.Holder) rows.findViewHolderForAdapterPosition(row);
        assertNotNull("row " + row + " not laid out", holder);
        RecyclerView.ViewHolder card = holder.grid.findViewHolderForAdapterPosition(column);
        assertNotNull("card " + column + " not laid out", card);
        return card.itemView;
    }

    private static View gridCard(HomeActivity home, int position) {
        RecyclerView grid = home.findViewById(R.id.apps_grid);
        RecyclerView.ViewHolder card = grid.findViewHolderForAdapterPosition(position);
        assertNotNull("grid card " + position + " not laid out", card);
        return card.itemView;
    }

    private static List<String> sheetItems() {
        idle();
        Dialog sheet = ShadowDialog.getLatestDialog();
        assertNotNull("no sheet open", sheet);
        LinearLayout items = sheet.findViewById(R.id.sheet_rows);
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < items.getChildCount(); i++) {
            if (items.getChildAt(i) instanceof TextView) {
                labels.add(((TextView) items.getChildAt(i)).getText().toString());
            }
        }
        return labels;
    }

    /** Presses OK on the sheet row with this label. */
    private static void choose(String label) {
        List<String> labels = sheetItems();
        LinearLayout items = ShadowDialog.getLatestDialog().findViewById(R.id.sheet_rows);
        for (int i = 0; i < items.getChildCount(); i++) {
            View row = items.getChildAt(i);
            if (row instanceof TextView && ((TextView) row).getText().toString().equals(label)) {
                row.performClick();
                idle();
                return;
            }
        }
        throw new AssertionError("no \"" + label + "\" in " + labels);
    }
}
