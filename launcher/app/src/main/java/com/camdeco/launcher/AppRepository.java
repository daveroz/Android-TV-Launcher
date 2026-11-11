package com.camdeco.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds launchable apps and remembers the order and hidden apps. */
final class AppRepository {
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_SOUND_WAVE = "sound_wave";

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
            Drawable icon = ai.loadIcon(pm);
            CharSequence label = ri.loadLabel(pm);
            // The card: one solid colour, the app's own, with a logo that matches the others.
            Brands.Brand brand = Brands.of(ai.packageName);
            int colour;
            Drawable logo;
            int logoKind;
            if (brand != null) {
                colour = brand.colour;
                logo = context.getDrawable(brand.logo);
                logoKind = AppEntry.LOGO_BRAND;
            } else {
                colour = AppColors.primary(banner != null ? banner : icon);
                Drawable mono = monochrome(icon);
                logo = mono != null ? mono : icon;
                logoKind = mono != null ? AppEntry.LOGO_MONOCHROME : AppEntry.LOGO_ICON;
            }
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
                    version, updated, colour, logo, logoKind));
        }
    }

    /** The one-colour icon Android 13+ apps ship for themed icons, or null. */
    private static Drawable monochrome(Drawable icon) {
        if (Build.VERSION.SDK_INT < 33 || !(icon instanceof AdaptiveIconDrawable)) return null;
        return ((AdaptiveIconDrawable) icon).getMonochrome();
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

    boolean soundWaveEnabled() {
        return prefs.getBoolean(KEY_SOUND_WAVE, true);
    }

    void setSoundWaveEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SOUND_WAVE, enabled).apply();
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
