package com.camdeco.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.List;

/** Drives the launcher the way a remote would, on a simulated 1080p TV. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w960dp-h540dp-land-television-xhdpi")
public class HomeActivityTest {

    private ShadowPackageManager packages;

    @Before
    public void installApps() throws Exception {
        // A TV driven by a remote is never in touch mode, so D-pad focus is visible.
        org.robolectric.shadows.ShadowInstrumentation.getInstrumentation().setInTouchMode(false);
        packages = shadowOf(RuntimeEnvironment.getApplication().getPackageManager());
        installApp("com.netflix.ninja", "Netflix", Intent.CATEGORY_LEANBACK_LAUNCHER);
        installApp("com.google.android.youtube.tv", "YouTube", Intent.CATEGORY_LEANBACK_LAUNCHER);
        installApp("com.amazon.amazonvideo.livingroom", "Prime Video", Intent.CATEGORY_LEANBACK_LAUNCHER);
        installApp("org.videolan.vlc", "VLC", Intent.CATEGORY_LAUNCHER); // phone-style app
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
    public void showsEveryAppExceptItselfInAlphabeticalOrder() throws Exception {
        HomeActivity home = start().get();
        assertEquals(List.of("com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
                "org.videolan.vlc", "com.google.android.youtube.tv"), shownPackages(home));
        // Every tile is laid out, 16:9, and the first one has focus.
        RecyclerView grid = home.findViewById(R.id.grid);
        View first = grid.findViewHolderForAdapterPosition(0).itemView;
        assertEquals(first, home.getWindow().getDecorView().findFocus());
        assertEquals(first.getWidth() * 9 / 16, first.getHeight());
        assertTrue(first.getWidth() > 0);
    }

    @Test
    public void phoneAppsShowTheirIconAndLabel() throws Exception {
        HomeActivity home = start().get();
        RecyclerView grid = home.findViewById(R.id.grid);
        View vlc = grid.findViewHolderForAdapterPosition(2).itemView;
        assertEquals(View.VISIBLE, vlc.findViewById(R.id.icon_group).getVisibility());
        assertEquals("VLC", ((android.widget.TextView) vlc.findViewById(R.id.label)).getText().toString());
    }

    @Test
    public void moveModeReordersWithTheRemoteAndRemembersIt() throws Exception {
        ActivityController<HomeActivity> controller = start();
        HomeActivity home = controller.get();
        openTileMenu(home, 0);
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0); // Move
        idle();

        press(home, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(home, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("com.netflix.ninja", shownPackages(home).get(2));
        press(home, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("com.netflix.ninja", shownPackages(home).get(1));
        press(home, KeyEvent.KEYCODE_DPAD_CENTER); // drop it
        assertFalse(home.isFinishing());

        // After the drop, arrows move focus again instead of the tile.
        press(home, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("com.netflix.ninja", shownPackages(home).get(1));

        // The new order survives the launcher being restarted.
        controller.pause().stop().destroy();
        HomeActivity again = start().get();
        assertEquals(List.of("com.amazon.amazonvideo.livingroom", "com.netflix.ninja",
                "org.videolan.vlc", "com.google.android.youtube.tv"), shownPackages(again));
    }

    @Test
    public void hiddenAppsDisappearAndCanBeShownAgain() throws Exception {
        HomeActivity home = start().get();
        openTileMenu(home, 0);
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1); // Hide
        idle();
        assertFalse(shownPackages(home).contains("com.netflix.ninja"));
        assertEquals(3, shownPackages(home).size());

        home.findViewById(R.id.options).performClick();
        AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Hidden apps (1)", menu.getListView().getAdapter().getItem(0).toString());
        shadowOf(menu).clickOnItem(0);
        idle();
        AlertDialog unhide = ShadowAlertDialog.getLatestAlertDialog();
        unhide.getListView().performItemClick(null, 0, 0); // tick Netflix
        unhide.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(shownPackages(home).contains("com.netflix.ninja"));
    }

    @Test
    public void menuButtonOpensTheFocusedTilesMenu() throws Exception {
        HomeActivity home = start().get();
        press(home, KeyEvent.KEYCODE_MENU);
        AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(menu);
        assertTrue(menu.isShowing());
    }

    @Test
    public void backButtonStaysOnTheHomeScreen() throws Exception {
        HomeActivity home = start().get();
        press(home, KeyEvent.KEYCODE_BACK);
        assertFalse(home.isFinishing());
    }

    @Test
    public void openingATileLaunchesTheTvActivity() throws Exception {
        HomeActivity home = start().get();
        RecyclerView grid = home.findViewById(R.id.grid);
        grid.findViewHolderForAdapterPosition(0).itemView.performClick();
        Intent started = shadowOf(home).getNextStartedActivity();
        assertEquals(new ComponentName("com.netflix.ninja", "com.netflix.ninja.Main"), started.getComponent());
        assertTrue(started.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER));
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void newlyInstalledAppsAppearWithoutRestarting() throws Exception {
        HomeActivity home = start().get();
        installApp("com.plexapp.android", "Plex", Intent.CATEGORY_LEANBACK_LAUNCHER);
        home.sendBroadcast(new Intent(Intent.ACTION_PACKAGE_ADDED,
                Uri.fromParts("package", "com.plexapp.android", null)));
        waitForApps(home, 5);
        assertTrue(shownPackages(home).contains("com.plexapp.android"));
    }

    @Test
    public void homeButtonServiceOnlyReactsToGoogleTv() {
        HomeButtonService service = Robolectric.setupService(HomeButtonService.class);

        AccessibilityEvent other = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        other.setPackageName("com.google.android.youtube.tv");
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
    public void manifestDeclaresHomeAndTvLauncherEntries() {
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
        waitForApps(controller.get(), 1);
        return controller;
    }

    /** The app list loads on a background thread; wait for it to reach the grid. */
    private void waitForApps(HomeActivity home, int atLeast) throws Exception {
        RecyclerView grid = home.findViewById(R.id.grid);
        for (int i = 0; i < 250; i++) {
            idle();
            if (grid.getAdapter().getItemCount() >= atLeast
                    && grid.findViewHolderForAdapterPosition(0) != null) {
                idle();
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("apps never appeared");
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));
    }

    private static void openTileMenu(HomeActivity home, int position) {
        RecyclerView grid = home.findViewById(R.id.grid);
        grid.findViewHolderForAdapterPosition(position).itemView.performLongClick();
        idle();
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    private static void press(HomeActivity home, int keyCode) {
        home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        idle();
    }

    private static List<String> shownPackages(HomeActivity home) {
        RecyclerView grid = home.findViewById(R.id.grid);
        List<String> result = new ArrayList<>();
        for (AppEntry app : ((AppAdapter) grid.getAdapter()).items()) result.add(app.packageName);
        return result;
    }
}
