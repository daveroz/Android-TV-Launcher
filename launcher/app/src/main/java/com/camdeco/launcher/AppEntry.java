package com.camdeco.launcher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** One launchable app, as shown on a tile. */
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

    AppEntry(ComponentName component, String label, Drawable banner, Drawable icon, boolean leanback) {
        this.packageName = component.getPackageName();
        this.component = component;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.leanback = leanback;
    }
}
