package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Exposes only Android's lock-screen action; no events or window content requested. */
public class StandbyService extends AccessibilityService {
    private static StandbyService connected;

    @Override protected void onServiceConnected() { connected = this; }
    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    static boolean sleep() {
        return connected != null && connected.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }
}
