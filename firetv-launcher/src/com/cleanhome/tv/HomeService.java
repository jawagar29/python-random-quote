package com.cleanhome.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS does not let the user choose a default launcher, so the Home button always opens
 * Amazon's home screen. This service brings Clean Home forward whenever that screen appears.
 * It never consumes keys and never reads window content.
 */
public class HomeService extends AccessibilityService {

    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";

    /** Set by the "Fire TV Home" tile so the Amazon home screen can be used on purpose. */
    static volatile boolean bypass = false;

    private boolean amazonHomeInFront = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        String pkg = String.valueOf(event.getPackageName());
        String cls = String.valueOf(event.getClassName());

        if (AMAZON_LAUNCHER.equals(pkg)) {
            // Only the home screen itself is redirected; search results and other screens
            // from the same package are left alone.
            if (cls.contains("HomeActivity")) {
                amazonHomeInFront = true;
                if (!bypass) {
                    openCleanHome();
                }
            }
            return;
        }
        if (cls.startsWith("android.widget.") || cls.startsWith("android.view.")
                || cls.startsWith("android.app.")) {
            return; // toasts, dialogs and popups, not a new screen
        }
        if ("com.android.systemui".equals(pkg)) {
            return;
        }
        amazonHomeInFront = false;
        if (!getPackageName().equals(pkg)) {
            bypass = false; // another app was opened, so the visit to Fire TV Home is over
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_UP) {
            if (bypass && amazonHomeInFront) {
                bypass = false;
                openCleanHome();
            }
        }
        return false; // observe only, so long-press Home and everything else keep working
    }

    private void openCleanHome() {
        amazonHomeInFront = false;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
