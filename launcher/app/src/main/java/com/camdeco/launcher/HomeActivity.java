package com.camdeco.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen. Layout after the Apple TV app: the focused app fills the screen as the hero,
 * a pill of tabs sits on top, and rows of cards run along the bottom. Streaming apps fill the hero
 * with their shows, and whatever is playing gets its own row. The Apps tab shows every app in a
 * grid, where the order can be changed.
 */
public class HomeActivity extends Activity implements CardAdapter.Listener, RowsAdapter.NowPlayingListener {
    static final int COLUMNS = 5;
    private static final int PAGE_HOME = 0;
    private static final int PAGE_APPS = 1;
    /** Wait for the remote to settle before swapping the big hero art. */
    private static final long HERO_DELAY_MS = 120;

    /** Tests swap the network out; null means the real one. */
    static ShowArt.Fetcher fetcherForTests;

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService imageLoader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Drawable> artCache = new LruCache<>(4);
    private final Set<String> updatingChannels = new HashSet<>();

    private AppRepository repo;
    private ShowArt showArt;
    private ImageCache images;
    private NowPlaying nowPlaying;
    Hero hero;
    private RowsAdapter rowsAdapter;
    private CardAdapter appsAdapter;
    private VerticalGridView rows;
    private VerticalGridView appsGrid;
    private View appsPage;
    private View empty;
    private TextView hint;
    private TabBar tabs;
    private View tabHome;
    private View tabApps;

    private List<AppEntry> allApps = new ArrayList<>();
    private int page = PAGE_HOME;
    private boolean loaded;
    /** Package of the card being moved, or null when not moving. */
    private String movingPackage;
    AppEntry heroApp;
    private AppEntry pendingHero;
    private boolean heroShowsTrack;

    private final Runnable heroUpdate = () -> {
        if (pendingHero != null) showHero(pendingHero);
    };
    private final Runnable hideHint = () -> hint.animate().alpha(0f).setDuration(300).start();
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            RecyclerView.ViewHolder holder = rows.findViewHolderForAdapterPosition(0);
            if (holder instanceof RowsAdapter.NowPlayingHolder) {
                ((RowsAdapter.NowPlayingHolder) holder).showProgress(NowPlaying.now());
            }
            main.postDelayed(this, 1000);
        }
    };

    private final Hero.Images heroImages = new Hero.Images() {
        @Override
        public Bitmap cached(String url) {
            return images.cached(url);
        }

        @Override
        public void load(String url, Consumer<Bitmap> done) {
            if (imageLoader.isShutdown()) return;
            imageLoader.execute(() -> {
                Bitmap bitmap = images.load(url);
                main.post(() -> {
                    if (bitmap != null && !isDestroyed()) done.accept(bitmap);
                });
            });
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        repo = new AppRepository(this);
        ShowArt.Fetcher fetcher = fetcherForTests != null ? fetcherForTests : ImageCache::httpOpen;
        showArt = new ShowArt(this, fetcher);
        images = new ImageCache(this, fetcher);
        nowPlaying = new NowPlaying(this, main, this::onNowPlayingChanged);
        hero = new Hero(this, main);

        appsPage = findViewById(R.id.apps_page);
        empty = findViewById(R.id.empty);
        hint = findViewById(R.id.hint);
        tabs = findViewById(R.id.tabs);
        tabHome = findViewById(R.id.tab_home);
        tabApps = findViewById(R.id.tab_apps);

        ImageView heroAmbient = findViewById(R.id.hero_ambient);
        if (Build.VERSION.SDK_INT >= 31) {
            float blur = 48 * getResources().getDisplayMetrics().density;
            heroAmbient.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        } else {
            heroAmbient.setVisibility(View.GONE); // no cheap blur before Android 12
        }

        // Home: the focused row always sits just under the hero's text; rows above it scroll up
        // over the hero, which fades away.
        rows = findViewById(R.id.rows);
        rows.setWindowAlignment(BaseGridView.WINDOW_ALIGN_BOTH_EDGE);
        rows.setWindowAlignmentOffsetPercent(BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
        rows.setWindowAlignmentOffset(getResources().getDimensionPixelSize(R.dimen.rows_top));
        rows.setItemAlignmentOffsetPercent(0);
        rowsAdapter = new RowsAdapter(this, this);
        rows.setAdapter(rowsAdapter);
        rows.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView view, int dx, int dy) {
                updateHeroFade();
            }
        });

        // Apps: every app in a grid.
        appsGrid = findViewById(R.id.apps_grid);
        appsGrid.setNumColumns(COLUMNS);
        appsGrid.setHorizontalSpacing(getResources().getDimensionPixelSize(R.dimen.card_gap));
        appsAdapter = new CardAdapter(this, true);
        appsGrid.setAdapter(appsAdapter);

        tabs.setSelectedTab(tabHome);
        tabHome.setOnFocusChangeListener((v, focused) -> {
            if (focused) showPage(PAGE_HOME);
        });
        tabApps.setOnFocusChangeListener((v, focused) -> {
            if (focused) showPage(PAGE_APPS);
        });
        tabHome.setOnClickListener(v -> focusContent());
        tabApps.setOnClickListener(v -> focusContent());
        findViewById(R.id.tab_settings).setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        findViewById(R.id.tab_options).setOnClickListener(v -> showLauncherOptions());

        // Refresh when apps are installed, removed, updated, enabled or disabled.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            // These are protected broadcasts: only the system can send them.
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        nowPlaying.start(); // also picks up notification access granted while we were away
        hero.resume();
        main.removeCallbacks(progressTick);
        main.post(progressTick);
    }

    @Override
    protected void onPause() {
        if (movingPackage != null) endMove();
        nowPlaying.stop();
        hero.pause();
        main.removeCallbacks(progressTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        loader.shutdownNow();
        network.shutdownNow();
        imageLoader.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the home screen: back to the top of Home.
        boolean alreadyHome = hasWindowFocus()
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0;
        if (alreadyHome) {
            if (movingPackage != null) endMove();
            showPage(PAGE_HOME);
            focusTop();
        }
    }

    // ---- Loading and showing apps ----

    private void reload() {
        if (loader.isShutdown()) return;
        loader.execute(() -> {
            List<AppEntry> apps = repo.loadAll();
            main.post(() -> {
                if (isDestroyed()) return;
                if (movingPackage != null) endMove();
                repo.sort(apps);
                allApps = apps;
                artCache.evictAll(); // an update may have brought new art
                showApps(true);
            });
        });
    }

    /** Rebuilds the rows and the grid; refocus keeps focus on the same app. */
    private void showApps(boolean refocus) {
        Set<String> hidden = repo.getHidden();
        Set<String> favourites = repo.getFavourites();
        List<AppEntry> visible = new ArrayList<>();
        List<AppEntry> favouriteApps = new ArrayList<>();
        List<AppEntry> tvApps = new ArrayList<>();
        List<AppEntry> otherApps = new ArrayList<>();
        for (AppEntry app : allApps) {
            if (hidden.contains(app.packageName)) continue;
            visible.add(app);
            if (favourites.contains(app.packageName)) favouriteApps.add(app);
            else if (app.leanback) tvApps.add(app);
            else otherApps.add(app);
        }
        List<RowsAdapter.Row> list = new ArrayList<>();
        if (nowPlaying.track() != null) list.add(RowsAdapter.Row.nowPlaying(getString(R.string.row_now_playing)));
        if (!favouriteApps.isEmpty()) {
            list.add(new RowsAdapter.Row("favourites", getString(R.string.row_favourites), favouriteApps));
        }
        if (!tvApps.isEmpty()) list.add(new RowsAdapter.Row("tv", getString(R.string.row_tv), tvApps));
        if (!otherApps.isEmpty()) list.add(new RowsAdapter.Row("other", getString(R.string.row_other), otherApps));

        String focusedPackage = focusedPackage();
        rowsAdapter.setTrack(nowPlaying.track());
        rowsAdapter.submit(list, favourites);
        appsAdapter.submit(visible, favourites);
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);

        if (!loaded) {
            loaded = true;
            focusTop();
        } else if (refocus && focusedPackage != null) {
            focusApp(focusedPackage);
        }
    }

    /** The app whose card has focus, or null when focus is elsewhere. */
    private String focusedPackage() {
        View focused = getWindow().getDecorView().findFocus();
        Object app = focused == null ? null : focused.getTag(R.id.tag_app);
        return app instanceof AppEntry ? ((AppEntry) app).packageName : null;
    }

    // ---- Pages and focus ----

    private void showPage(int newPage) {
        if (page == newPage) return;
        if (movingPackage != null) endMove();
        page = newPage;
        boolean home = newPage == PAGE_HOME;
        tabs.setSelectedTab(home ? tabHome : tabApps);
        rows.setVisibility(home ? View.VISIBLE : View.GONE);
        appsPage.setVisibility(home ? View.GONE : View.VISIBLE);
        hero.setVisible(home);
    }

    /** Fades the hero away as the rows scroll up over it. */
    private void updateHeroFade() {
        RecyclerView.ViewHolder first = rows.findViewHolderForAdapterPosition(0);
        float top = getResources().getDimensionPixelSize(R.dimen.rows_top);
        float range = getResources().getDimensionPixelSize(R.dimen.hero_fade_range);
        float scrolled = first == null ? range : Math.max(0f, top - first.itemView.getTop());
        hero.setScrollFraction(Math.min(1f, scrolled / range));
    }

    /** Called by a tab's OK: move focus down into that page. */
    private void focusContent() {
        if (page == PAGE_HOME) rows.requestFocus();
        else appsGrid.requestFocus();
    }

    /** The top of Home: what's playing if anything is, else the first app. */
    private void focusTop() {
        NowPlaying.Track track = nowPlaying.track();
        if (track != null) {
            focusNowPlaying();
            showTrackHero(track);
        } else {
            int row = firstCardRow();
            if (row < rowsAdapter.getItemCount()) {
                showHero(rowsAdapter.rows().get(row).apps.get(0));
                focusRowCard(row, 0);
            }
        }
    }

    private int firstCardRow() {
        return rowsAdapter.hasNowPlaying() ? 1 : 0;
    }

    private void focusApp(String packageName) {
        if (page == PAGE_HOME) {
            int[] at = rowsAdapter.find(packageName);
            if (at != null) focusRowCard(at[0], at[1]);
        } else {
            focusGridCard(appsAdapter.indexOf(packageName));
        }
    }

    private void focusRowCard(int row, int column) {
        int rowCount = rowsAdapter.getItemCount();
        if (rowCount == 0) return;
        int r = Math.max(0, Math.min(row, rowCount - 1));
        rows.setSelectedPosition(r, holder -> {
            if (holder instanceof RowsAdapter.NowPlayingHolder) {
                retryFocus(() -> ((RowsAdapter.NowPlayingHolder) holder).open.requestFocus(), 10);
                return;
            }
            RowsAdapter.Holder rowHolder = (RowsAdapter.Holder) holder;
            int c = Math.max(0, Math.min(column, rowHolder.cards.getItemCount() - 1));
            rowHolder.grid.setSelectedPosition(c);
            retryFocus(() -> {
                RecyclerView.ViewHolder card = rowHolder.grid.findViewHolderForAdapterPosition(c);
                return card != null && card.itemView.requestFocus();
            }, 10);
        });
    }

    private void focusNowPlaying() {
        if (rowsAdapter.hasNowPlaying()) focusRowCard(0, 0);
    }

    private void focusGridCard(int position) {
        int count = appsAdapter.getItemCount();
        if (count == 0) return;
        int p = Math.max(0, Math.min(position, count - 1));
        appsGrid.setSelectedPosition(p);
        retryFocus(() -> {
            RecyclerView.ViewHolder card = appsGrid.findViewHolderForAdapterPosition(p);
            return card != null && card.itemView.requestFocus();
        }, 10);
    }

    private interface Attempt {
        boolean tryNow();
    }

    /** Lists lay out after a data change; try again shortly if the view isn't there yet. */
    private void retryFocus(Attempt attempt, int attemptsLeft) {
        main.post(() -> {
            if (!attempt.tryNow() && attemptsLeft > 0) {
                main.postDelayed(() -> retryFocus(attempt, attemptsLeft - 1), 30);
            }
        });
    }

    // ---- Hero ----

    @Override
    public void onCardFocused(View card, AppEntry app, boolean focused) {
        // The row title turns amber while one of its cards has focus.
        for (View v = card; v != null && v.getParent() instanceof View; v = (View) v.getParent()) {
            if (v.getId() == R.id.row_root) {
                v.findViewById(R.id.row_title).setActivated(focused);
                break;
            }
        }
        if (focused && page == PAGE_HOME && (app != heroApp || heroShowsTrack)) {
            pendingHero = app;
            main.removeCallbacks(heroUpdate);
            main.postDelayed(heroUpdate, HERO_DELAY_MS);
        }
    }

    private void showHero(AppEntry app) {
        heroApp = app;
        pendingHero = null;
        heroShowsTrack = false;
        String kind = getString(app.leanback ? R.string.kind_tv : R.string.kind_phone);
        String chip = app.versionName != null && !app.versionName.isEmpty()
                ? getString(R.string.version, app.versionName) : null;
        String desc = null;
        if (app.updated > 0) {
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(app.updated));
            boolean favourite = repo.getFavourites().contains(app.packageName);
            desc = getString(favourite ? R.string.favourite_updated : R.string.updated, date);
        }
        hero.showApp(app, kind, chip, desc);

        Drawable cached = artCache.get(app.packageName);
        if (cached != null) {
            hero.setAppArt(cached, app.banner != null);
        } else if (!loader.isShutdown()) {
            loader.execute(() -> {
                Drawable art = repo.loadHeroArt(app);
                main.post(() -> {
                    if (isDestroyed() || art == null) return;
                    artCache.put(app.packageName, art);
                    // Shows may have taken over the hero meanwhile; they win.
                    if (heroApp == app && hero.currentFeature() == null && !heroShowsTrack) {
                        hero.setAppArt(art, app.banner != null);
                    }
                });
            });
        }

        // Streaming apps: their shows take over the hero, like the Apple TV home screen.
        String channel = repo.showArtEnabled() ? ShowArt.channelFor(app) : null;
        if (channel != null) {
            List<ShowArt.Feature> ready = showArt.ready(channel);
            if (!ready.isEmpty()) hero.showFeatures(ready, heroImages);
            updateShowArt(app, channel);
        }
    }

    /** Fetches or refreshes a service's shows in the background, then shows them if still relevant. */
    private void updateShowArt(AppEntry app, String channel) {
        if (network.isShutdown() || !updatingChannels.add(channel)) return;
        network.execute(() -> {
            showArt.update(channel, System.currentTimeMillis());
            List<ShowArt.Feature> ready = showArt.ready(channel);
            if (!ready.isEmpty()) images.load(ready.get(0).background); // first picture ready to go
            main.post(() -> {
                updatingChannels.remove(channel);
                if (isDestroyed() || ready.isEmpty() || !repo.showArtEnabled()) return;
                if (heroApp == app && !heroShowsTrack && page == PAGE_HOME) hero.showFeatures(ready, heroImages);
            });
        });
    }

    private void showTrackHero(NowPlaying.Track track) {
        heroApp = null;
        pendingHero = null;
        main.removeCallbacks(heroUpdate);
        heroShowsTrack = true;
        hero.showTrack(track, appIcon(track.packageName));
    }

    // ---- Now playing ----

    private void onNowPlayingChanged() {
        NowPlaying.Track track = nowPlaying.track();
        if (!loaded) return;
        boolean want = track != null;
        if (want != rowsAdapter.hasNowPlaying()) {
            View focused = getWindow().getDecorView().findFocus();
            boolean focusInNowPlaying = isInNowPlaying(focused);
            boolean atTop = rows.getSelectedPosition() <= 0;
            showApps(!(want && atTop));
            if (want && atTop && page == PAGE_HOME) focusTop(); // it appears at the top: take focus there
            else if (!want && focusInNowPlaying) focusTop();
        } else {
            rowsAdapter.setTrack(track);
        }
        if (want && heroShowsTrack) hero.showTrack(track, appIcon(track.packageName));
    }

    private boolean isInNowPlaying(View view) {
        for (View v = view; v != null && v.getParent() instanceof View; v = (View) v.getParent()) {
            if (v.getParent() == rows) return rows.getChildViewHolder(v) instanceof RowsAdapter.NowPlayingHolder;
        }
        return false;
    }

    @Override
    public void onNowPlayingAction(int action) {
        switch (action) {
            case OPEN:
                openPlayer();
                break;
            case PREVIOUS:
                nowPlaying.previous();
                break;
            case PLAY_PAUSE:
                nowPlaying.playPause();
                break;
            case NEXT:
                nowPlaying.next();
                break;
            default:
                break;
        }
    }

    @Override
    public void onNowPlayingFocused(View view, boolean focused) {
        NowPlaying.Track track = rowsAdapter.track();
        if (focused && page == PAGE_HOME && track != null && !heroShowsTrack) showTrackHero(track);
    }

    @Override
    public Drawable appIcon(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Opens the player's own screen for what's playing, or else just the app. */
    private void openPlayer() {
        PendingIntent session = nowPlaying.sessionActivity();
        if (session != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    // We're on screen, so let the player come to the front.
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    session.send(this, 0, null, null, null, null, options.toBundle());
                } else {
                    session.send();
                }
                return;
            } catch (PendingIntent.CanceledException ignored) {
                // Fall back to launching the app.
            }
        }
        String packageName = nowPlaying.packageName();
        if (packageName == null) return;
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (launch == null) launch = pm.getLaunchIntentForPackage(packageName);
        if (launch != null) open(launch);
        else showHint(R.string.cannot_open);
    }

    // ---- Cards ----

    @Override
    public void onOpen(AppEntry app) {
        open(new Intent(Intent.ACTION_MAIN)
                .addCategory(app.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onOptions(AppEntry app) {
        boolean favourite = repo.getFavourites().contains(app.packageName);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(favourite ? R.string.option_remove_favourite : R.string.option_add_favourite));
        actions.add(() -> toggleFavourite(app));
        if (page == PAGE_APPS) {
            labels.add(getString(R.string.option_move));
            actions.add(() -> startMove(app));
        }
        labels.add(getString(R.string.option_hide));
        actions.add(() -> hide(app));
        labels.add(getString(R.string.option_app_info));
        actions.add(() -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(app))));
        labels.add(getString(R.string.option_uninstall));
        actions.add(() -> open(new Intent(Intent.ACTION_DELETE, packageUri(app))));
        Sheet.pick(this, app.label, null, labels, which -> actions.get(which).run());
    }

    private void toggleFavourite(AppEntry app) {
        Set<String> favourites = repo.getFavourites();
        boolean adding = favourites.add(app.packageName);
        if (!adding) favourites.remove(app.packageName);
        repo.setFavourites(favourites);
        showApps(false);
        focusApp(app.packageName); // follow the card into (or out of) the Favourites row
        if (heroApp == app) showHero(app);
        showHint(adding ? R.string.favourite_added : R.string.favourite_removed);
    }

    private void hide(AppEntry app) {
        int[] at = rowsAdapter.find(app.packageName);
        int gridPosition = appsAdapter.indexOf(app.packageName);
        Set<String> hidden = repo.getHidden();
        hidden.add(app.packageName);
        repo.setHidden(hidden);
        showApps(false);
        if (page == PAGE_HOME && at != null) focusRowCard(at[0], at[1]);
        else if (page == PAGE_APPS) focusGridCard(gridPosition);
        showHint(R.string.hidden_hint);
    }

    // ---- Moving cards (Apps page) ----

    private void startMove(AppEntry app) {
        movingPackage = app.packageName;
        appsAdapter.setMoving(movingPackage);
        focusGridCard(appsAdapter.indexOf(movingPackage));
        showHint(R.string.move_hint);
    }

    private void moveBy(int delta) {
        int from = appsAdapter.indexOf(movingPackage);
        if (from < 0) {
            endMove();
            return;
        }
        int to = Math.max(0, Math.min(appsAdapter.getItemCount() - 1, from + delta));
        if (to == from) return;
        appsAdapter.move(from, to);
        focusGridCard(to);
    }

    private void endMove() {
        movingPackage = null;
        appsAdapter.setMoving(null);
        repo.saveOrder(appsAdapter.items());
        repo.sort(allApps);
        showApps(true); // the Home rows follow the new order
    }

    // ---- Launcher menu ----

    private void showLauncherOptions() {
        List<AppEntry> hiddenApps = hiddenApps();
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.option_hidden_apps, hiddenApps.size()));
        items.add(getString(R.string.option_now_playing, getString(nowPlaying.hasAccess() ? R.string.on : R.string.off)));
        items.add(getString(R.string.option_show_art, getString(repo.showArtEnabled() ? R.string.on : R.string.off)));
        items.add(getString(R.string.option_home_setup));
        items.add(getString(R.string.option_tv_settings));
        Sheet.pick(this, getString(R.string.app_name), null, items, which -> {
            switch (which) {
                case 0:
                    showHiddenApps(hiddenApps);
                    break;
                case 1:
                    showNowPlayingSetup();
                    break;
                case 2:
                    toggleShowArt();
                    break;
                case 3:
                    showHomeSetup();
                    break;
                default:
                    open(new Intent(Settings.ACTION_SETTINGS));
                    break;
            }
        });
    }

    private void toggleShowArt() {
        boolean on = !repo.showArtEnabled();
        repo.setShowArtEnabled(on);
        if (heroApp != null) showHero(heroApp); // swap between shows and the plain banner now
        showHint(on ? R.string.show_art_on : R.string.show_art_off);
    }

    private void showNowPlayingSetup() {
        String message = getString(R.string.now_playing_message,
                getString(nowPlaying.hasAccess() ? R.string.on : R.string.off));
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.open_notification_access));
        items.add(getString(R.string.close));
        Sheet.pick(this, getString(R.string.now_playing_title), message, items, which -> {
            if (which == 0) open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });
    }

    private List<AppEntry> hiddenApps() {
        Set<String> hidden = repo.getHidden();
        List<AppEntry> result = new ArrayList<>();
        for (AppEntry app : allApps) {
            if (hidden.contains(app.packageName)) result.add(app);
        }
        return result;
    }

    private void showHiddenApps(List<AppEntry> hiddenApps) {
        if (hiddenApps.isEmpty()) {
            showHint(R.string.no_hidden_apps);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (AppEntry app : hiddenApps) labels.add(app.label);
        Sheet.checklist(this, getString(R.string.unhide_title), labels, getString(R.string.unhide), checked -> {
            Set<String> hidden = repo.getHidden();
            for (int i = 0; i < checked.length; i++) {
                if (checked[i]) hidden.remove(hiddenApps.get(i).packageName);
            }
            repo.setHidden(hidden);
            showApps(true);
        });
    }

    private void showHomeSetup() {
        String message = getString(R.string.home_setup_message,
                getString(isDefaultHome() ? R.string.yes : R.string.no),
                getString(isOverrideOn() ? R.string.on : R.string.off));
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.open_accessibility));
        items.add(getString(R.string.close));
        Sheet.pick(this, getString(R.string.option_home_setup), message, items, which -> {
            if (which == 0) open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null
                && getPackageName().equals(info.activityInfo.packageName);
    }

    private boolean isOverrideOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName service = new ComponentName(this, HomeButtonService.class);
        return enabled.contains(service.flattenToString()) || enabled.contains(service.flattenToShortString());
    }

    // ---- Remote buttons ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (movingPackage != null && isMoveKey(code)) {
            if (down) {
                int sideways = appsGrid.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        moveBy(-sideways);
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        moveBy(sideways);
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        moveBy(-COLUMNS);
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        moveBy(COLUMNS);
                        break;
                    default:
                        endMove(); // OK or Back drops the card where it is
                        break;
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (down && event.getRepeatCount() == 0) goBack();
            return true; // the home screen itself has nowhere to go back to
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            View focused = getWindow().getDecorView().findFocus();
            if (focused != null && focused.getTag(R.id.tag_app) != null) {
                if (event.getAction() == KeyEvent.ACTION_UP) focused.performLongClick();
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && down && nowPlaying.track() != null) {
            nowPlaying.playPause(); // the remote's play button controls what's playing
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Back, as on Apple TV: from the cards up to the tabs, then from Apps to Home. */
    private void goBack() {
        View focused = getWindow().getDecorView().findFocus();
        boolean inTabs = false;
        for (View v = focused; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == tabs) {
                inTabs = true;
                break;
            }
        }
        if (!inTabs) {
            (page == PAGE_HOME ? tabHome : tabApps).requestFocus();
        } else if (page == PAGE_APPS) {
            tabHome.requestFocus(); // its focus listener switches to Home
        }
    }

    private static boolean isMoveKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    // ---- Helpers ----

    private void open(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            showHint(R.string.cannot_open);
        }
    }

    private static Uri packageUri(AppEntry app) {
        return Uri.fromParts("package", app.packageName, null);
    }

    /** A short message in a Worldly pill at the bottom of the screen. */
    private void showHint(int message) {
        hint.setText(message);
        hint.animate().cancel();
        hint.animate().alpha(1f).setDuration(150).start();
        main.removeCallbacks(hideHint);
        main.postDelayed(hideHint, 3500);
    }
}
