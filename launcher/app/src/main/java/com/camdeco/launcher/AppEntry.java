package com.camdeco.launcher;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;

/** One launchable app, as shown on a card and in the hero. */
final class AppEntry {
    /** What the card shows: a matching brand glyph, the app's one-colour icon, or its own icon. */
    static final int LOGO_BRAND = 0, LOGO_MONOCHROME = 1, LOGO_ICON = 2;

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
    /** The app's primary colour (its brand's, or its art's), or {@link AppColors#NONE}. */
    final int colour;
    /** The logo on its card, and which kind it is. */
    final Drawable logo;
    final int logoKind;

    AppEntry(ComponentName component, String label, Drawable banner, Drawable icon, boolean leanback,
             ApplicationInfo appInfo, int artRes, String versionName, long updated,
             int colour, Drawable logo, int logoKind) {
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
        this.colour = colour;
        this.logo = logo;
        this.logoKind = logoKind;
    }
}
