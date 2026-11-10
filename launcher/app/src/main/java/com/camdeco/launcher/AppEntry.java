package com.camdeco.launcher;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;

/** One launchable app, as shown on a card and in the hero. */
final class AppEntry {
    final String packageName;
    final ComponentName component;
    final String label;
    /** 16:9 TV banner, or null when the app only has an icon. */
    final Drawable banner;
    /** Square icon, used when there is no banner. */
    final Drawable icon;
    /** True for TV apps (LEANBACK_LAUNCHER), false for phone-style apps. */
    final boolean leanback;
    /** For loading a sharper copy of the banner or icon for the hero. */
    final ApplicationInfo appInfo;
    final int artRes;
    /** Shown in the hero; null or 0 when unknown. */
    final String versionName;
    final long updated;

    AppEntry(ComponentName component, String label, Drawable banner, Drawable icon, boolean leanback,
             ApplicationInfo appInfo, int artRes, String versionName, long updated) {
        this.packageName = component.getPackageName();
        this.component = component;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.leanback = leanback;
        this.appInfo = appInfo;
        this.artRes = artRes;
        this.versionName = versionName;
        this.updated = updated;
    }
}
