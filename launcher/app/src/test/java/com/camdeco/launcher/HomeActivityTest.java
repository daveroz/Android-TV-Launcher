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
    private TestNet net;

    @Before
    public void installApps() throws Exception {
        // A TV driven by a remote is never in touch mode, so D-pad focus is visible.
        ShadowInstrumentation.getInstrumentation().setInTouchMode(false);
        // No real network in tests: TVmaze answers come from saved responses when a test wants them.
        net = new TestNet();
        net.offline = true;
        HomeActivity.fetcherForTests = net;
        ShowArt.requestGapMs = 0;
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
        // The first card has focus and fills the hero: just its banner, no name or version text.
        assertEquals(card(home, 0, 0), focus(home));
        assertEquals(NETFLIX, home.heroApp.packageName);
        assertEquals(View.GONE, home.findViewById(R.id.hero_title).getVisibility());
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
    public void heroMatchesTheHighlightedCardAfterAddingAFavourite() throws Exception {
        HomeActivity home = start().get();
        card(home, 0, 2).requestFocus(); // YouTube
        idle();
        card(home, 0, 2).performLongClick();
        choose("Add to Favourites");
        idle();
        AppEntry highlighted = (AppEntry) focus(home).getTag(R.id.tag_app);
        assertEquals(YOUTUBE, highlighted.packageName);
        assertEquals(highlighted, home.heroApp);

        card(home, 0, 0).performLongClick();
        choose("Remove from Favourites");
        idle();
        highlighted = (AppEntry) focus(home).getTag(R.id.tag_app);
        assertEquals(highlighted, home.heroApp);
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
    public void favouritesGetTheirOwnFirstRow() throws Exception {
        HomeActivity home = start().get();
        card(home, 0, 2).performLongClick(); // YouTube
        choose("Add to Favourites");
        assertEquals(List.of("Favourites: [" + YOUTUBE + "]", "TV apps: [" + NETFLIX + ", " + PRIME + "]",
                "Other apps: [" + VLC + "]"), rows(home));
        assertEquals(card(home, 0, 0), focus(home)); // focus followed the card

        card(home, 0, 0).performLongClick();
        choose("Remove from Favourites");
        assertEquals(List.of("TV apps: [" + NETFLIX + ", " + PRIME + ", " + YOUTUBE + "]",
                "Other apps: [" + VLC + "]"), rows(home));
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
    public void moveIsOnlyOfferedOnTheAppsPage() throws Exception {
        HomeActivity home = start().get();
        card(home, 0, 0).performLongClick();
        assertFalse(sheetItems().contains("Move"));
    }

    @Test
    public void hiddenAppsDisappearAndCanBeShownAgain() throws Exception {
        HomeActivity home = start().get();
        card(home, 0, 0).performLongClick();
        choose("Hide");
        assertEquals("TV apps: [" + PRIME + ", " + YOUTUBE + "]", rows(home).get(0));
        assertFalse(grid(home).contains(NETFLIX));

        home.findViewById(R.id.tab_options).performClick();
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
        assertEquals(4, targets.size());
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
    public void streamingAppsFillTheHeroWithTheirShows() throws Exception {
        net.offline = false;
        HomeActivity home = start().get(); // Netflix is the first card and has focus
        waitFor(() -> "Black Doves".equals(text(home, R.id.hero_title)));
        assertEquals("S9 E99", text(home, R.id.hero_version));
        assertTrue(text(home, R.id.hero_kind).endsWith("Netflix"));
        assertEquals(View.VISIBLE, home.findViewById(R.id.hero_credit).getVisibility());
        assertEquals(2, ((android.view.ViewGroup) home.findViewById(R.id.hero_dots)).getChildCount());

        // Every few seconds the next show takes over.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
        waitFor(() -> "Love Is Blind".equals(text(home, R.id.hero_title)));

        // Apps that aren't streaming services just show their banner.
        card(home, 0, 2).requestFocus(); // YouTube
        idle();
        assertEquals(YOUTUBE, home.heroApp.packageName);
        assertEquals(View.GONE, home.findViewById(R.id.hero_title).getVisibility());
        assertEquals(View.GONE, home.findViewById(R.id.hero_credit).getVisibility());

        // Turned off in the menu: Netflix shows its banner again.
        home.findViewById(R.id.tab_options).performClick();
        idle();
        choose("Show artwork: On");
        card(home, 0, 0).requestFocus();
        idle();
        assertEquals(NETFLIX, home.heroApp.packageName);
        assertEquals(View.GONE, home.findViewById(R.id.hero_title).getVisibility());
        assertEquals(View.GONE, home.findViewById(R.id.hero_credit).getVisibility());
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
        assertEquals(View.GONE, home.findViewById(R.id.hero_title).getVisibility());

        // Paused: still there. Stopped: gone.
        shadowOf(spotify).setPlaybackState(state(PlaybackState.STATE_PAUSED));
        shadowOf(spotify).executeOnPlaybackStateChanged(spotify.getPlaybackState());
        idle();
        assertEquals(View.VISIBLE, home.findViewById(R.id.np_bar).getVisibility());
        shadowOf(spotify).setPlaybackState(state(PlaybackState.STATE_STOPPED));
        shadowOf(spotify).executeOnPlaybackStateChanged(spotify.getPlaybackState());
        idle();
        assertEquals(View.GONE, home.findViewById(R.id.np_bar).getVisibility());
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void squareCoversBecomeACardWithTheirBlurBehind() throws Exception {
        installApp("com.spotify.tv.android", "Spotify", Intent.CATEGORY_LEANBACK_LAUNCHER);
        HomeActivity home = start().get();
        play("com.spotify.tv.android", "Midnight City", "M83", cover());
        card(home, 0, 2).requestFocus(); // Spotify
        idle();
        assertTrue(home.hero.showsCover());
        assertEquals(0f, home.findViewById(R.id.hero_art).getAlpha(), 0.01f);
        assertTrue(home.findViewById(R.id.hero_ambient).getAlpha() > 0.8f);
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
        idle();
        assertEquals(View.GONE, home.findViewById(R.id.np_bar).getVisibility());

        // Nothing playing: Spotify's card shows what was played, with its cover, instead of the logo.
        card(home, 0, 2).requestFocus();
        waitFor(() -> "Midnight City".equals(text(home, R.id.hero_title)) && home.hero.showsCover());
        assertEquals("Recently played on Spotify", text(home, R.id.hero_kind));
        assertEquals("M83", text(home, R.id.hero_desc));
        assertEquals(View.GONE, home.findViewById(R.id.hero_credit).getVisibility());

        // Apps with no history keep their banner.
        card(home, 0, 0).requestFocus(); // Netflix
        idle();
        assertEquals(View.GONE, home.findViewById(R.id.hero_title).getVisibility());
    }

    private static android.graphics.Bitmap cover() {
        android.graphics.Bitmap cover = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888);
        cover.eraseColor(0xFFDB2777);
        return cover;
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
        home.findViewById(R.id.tab_options).performClick();
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
