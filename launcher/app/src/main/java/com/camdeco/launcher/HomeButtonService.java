package com.camdeco.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional Home button override. Google TV keeps itself as the Home app on
 * Chromecast, so when its home screen appears this opens Deco Launcher on top.
 * It only listens to Google TV's windows (see res/xml/home_button_service.xml)
 * and reads no screen content.
 */
public class HomeButtonService extends AccessibilityService {
    static final String GOOGLE_TV = "com.google.android.apps.tv.launcherx";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !GOOGLE_TV.contentEquals(pkg)) return;
        Intent home = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (RuntimeException ignored) {
            // Never crash the service; Google TV simply stays on screen.
        }
    }

    @Override
    public void onInterrupt() {
    }
}
