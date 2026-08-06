package com.pluskeymap.app;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Minimal accessibility service — only needed for the screenshot action
 * (performGlobalAction). All key detection and gesture logic lives in
 * DetectorService which runs independently.
 */
public class PlusKeyService extends AccessibilityService {

    public static final String ACTION_KEY_DETECTED = "com.pluskeymap.KEY_DETECTED";
    public static final String EXTRA_KEYCODE       = "keycode";
    public static final String EXTRA_ACTION        = "action";

    // Kept so ActionExecutor can call performGlobalAction for screenshot
    public static PlusKeyService instance = null;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
