package com.camdeco.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds launchable apps and remembers the order, favourites and hidden apps. */
final class AppRepository {
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_FAVOURITES = "favourites";

    private final Context context;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.pm = context.getPackageManager();
        this.prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    /** Every launchable app except this launcher, in saved order. Slow: call off the main thread. */
    List<AppEntry> loadAll() {
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        // TV apps first, so a package that has both entries opens its TV version.
        addMatches(byPackage, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        addMatches(byPackage, Intent.CATEGORY_LAUNCHER, false);
        byPackage.remove(context.getPackageName());
        List<AppEntry> apps = new ArrayList<>(byPackage.values());
        sort(apps);
        return apps;
    }

    private void addMatches(Map<String, AppEntry> out, String category, boolean leanback) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || out.containsKey(ai.packageName)) continue;
            Drawable banner = leanback ? ai.loadBanner(pm) : null;
            Drawable icon = banner == null ? ai.loadIcon(pm) : null;
            CharSequence label = ri.loadLabel(pm);
            String version = null;
            long updated = 0;
            try {
                PackageInfo info = pm.getPackageInfo(ai.packageName, 0);
                version = info.versionName;
                updated = info.lastUpdateTime;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // Shown without version and date.
            }
            out.put(ai.packageName, new AppEntry(
                    new ComponentName(ai.packageName, ai.name),
                    label == null ? ai.packageName : label.toString(),
                    banner, icon, leanback, ai.applicationInfo,
                    banner != null ? ai.getBannerResource() : ai.getIconResource(),
                    version, updated));
        }
    }

    /**
     * A sharper copy of the app's banner (or icon) for the big hero art: the highest-density
     * version the app ships. Slow: call off the main thread.
     */
    Drawable loadHeroArt(AppEntry app) {
        if (app.artRes != 0 && app.appInfo != null) {
            try {
                Resources res = pm.getResourcesForApplication(app.appInfo);
                Drawable art = res.getDrawableForDensity(app.artRes, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (art != null) return art;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // Fall back to the card's copy below.
            }
        }
        return copy(app.banner != null ? app.banner : app.icon);
    }

    /** A separate instance of a drawable, so two views never share bounds or state. */
    static Drawable copy(Drawable drawable) {
        if (drawable == null) return null;
        Drawable.ConstantState state = drawable.getConstantState();
        return state == null ? drawable : state.newDrawable().mutate();
    }

    /** Saved order first, then any new apps alphabetically. */
    void sort(List<AppEntry> apps) {
        List<String> order = getList(KEY_ORDER);
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> {
            int ia = order.indexOf(a.packageName);
            int ib = order.indexOf(b.packageName);
            if (ia >= 0 && ib >= 0) return Integer.compare(ia, ib);
            if (ia >= 0) return -1;
            if (ib >= 0) return 1;
            return collator.compare(a.label, b.label);
        });
    }

    void saveOrder(List<AppEntry> apps) {
        List<String> order = new ArrayList<>(apps.size());
        for (AppEntry app : apps) order.add(app.packageName);
        putList(KEY_ORDER, order);
    }

    Set<String> getHidden() {
        return new LinkedHashSet<>(getList(KEY_HIDDEN));
    }

    void setHidden(Set<String> hidden) {
        putList(KEY_HIDDEN, new ArrayList<>(hidden));
    }

    Set<String> getFavourites() {
        return new LinkedHashSet<>(getList(KEY_FAVOURITES));
    }

    void setFavourites(Set<String> favourites) {
        putList(KEY_FAVOURITES, new ArrayList<>(favourites));
    }

    private List<String> getList(String key) {
        String raw = prefs.getString(key, "");
        if (TextUtils.isEmpty(raw)) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split("\n")));
    }

    private void putList(String key, List<String> values) {
        prefs.edit().putString(key, TextUtils.join("\n", values)).apply();
    }
}
