package com.pluskeymap.app;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service used for two purposes:
 *
 *   1. Screenshot via performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT).
 *
 *   2. Camera shutter injection: injectInputEvent() called from within an
 *      AccessibilityService process is granted elevated privileges by Android
 *      and does NOT require the signature-level INJECT_EVENTS permission.
 *      This is the only reliable path to deliver a KEYCODE_VOLUME_DOWN event
 *      to OxygenOS camera from a third-party app without root.
 *
 * Key detection is handled by DetectorService / LogcatWatcher — this service
 * does not intercept the Plus Key itself; it only provides the injection sink.
 */
public class PlusKeyService extends AccessibilityService {

    private static final String TAG = "PKM_A11yService";

    public static final String ACTION_KEY_DETECTED = "com.pluskeymap.KEY_DETECTED";
    public static final String EXTRA_KEYCODE       = "keycode";
    public static final String EXTRA_ACTION        = "action";

    /** Live instance — non-null when the user has enabled the accessibility service. */
    public static volatile PlusKeyService instance = null;

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.d(TAG, "AccessibilityService connected — camera shutter injection available");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        Log.d(TAG, "AccessibilityService disconnected");
        super.onDestroy();
    }

    /**
     * Injects a KEYCODE_VOLUME_DOWN down+up pair into the input dispatcher.
     *
     * Called from ActionExecutor when the camera shutter action fires and a camera
     * app is in the foreground. OxygenOS camera intercepts volume-down at the
     * window input level and maps it to shutter — the same path the physical
     * volume key uses.
     *
     * Why this works from here but not from DetectorService:
     *   AccessibilityService processes run under a different UID context that Android
     *   grants injectInputEvent() access to. A regular ForegroundService gets
     *   SecurityException for the same call regardless of declared permissions.
     *
     * @return true if both down and up events were accepted by the dispatcher.
     */
    public boolean injectVolumeDown() {
        try {
            java.lang.reflect.Method inject = getClass()
                    .getMethod("injectInputEvent",
                            android.view.InputEvent.class, int.class);
            // Inherited from AccessibilityService — always accessible.
            inject.setAccessible(true);

            long t = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(
                    t, t,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0,
                    KeyEvent.KEYCODE_UNKNOWN, 0,
                    KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(
                    t, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0,
                    KeyEvent.KEYCODE_UNKNOWN, 0,
                    KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD);

            boolean downOk = (Boolean) inject.invoke(this, down, 0);
            boolean upOk   = (Boolean) inject.invoke(this, up,   0);
            Log.d(TAG, "injectVolumeDown: down=" + downOk + " up=" + upOk);
            return downOk && upOk;
        } catch (NoSuchMethodException e) {
            // AccessibilityService.injectInputEvent() is available on API 21+.
            // Fall back to the superclass lookup path.
            return injectVolumeDownViaSuper();
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException)
                    ? e.getCause() : e;
            Log.w(TAG, "injectVolumeDown: failed ("
                    + (cause != null ? cause.getClass().getSimpleName()
                                    : e.getClass().getSimpleName())
                    + "): " + (cause != null ? cause.getMessage() : e.getMessage()));
            return false;
        }
    }

    /** Secondary lookup: walk the class hierarchy to find injectInputEvent. */
    private boolean injectVolumeDownViaSuper() {
        try {
            Class<?> cls = getClass();
            java.lang.reflect.Method inject = null;
            while (cls != null && inject == null) {
                try {
                    inject = cls.getDeclaredMethod("injectInputEvent",
                            android.view.InputEvent.class, int.class);
                } catch (NoSuchMethodException ignored) {
                    cls = cls.getSuperclass();
                }
            }
            if (inject == null) {
                Log.w(TAG, "injectVolumeDownViaSuper: method not found in hierarchy");
                return false;
            }
            inject.setAccessible(true);
            long t = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0,
                    KeyEvent.KEYCODE_UNKNOWN, 0,
                    KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(t, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0,
                    KeyEvent.KEYCODE_UNKNOWN, 0,
                    KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            boolean downOk = (Boolean) inject.invoke(this, down, 0);
            boolean upOk   = (Boolean) inject.invoke(this, up,   0);
            Log.d(TAG, "injectVolumeDownViaSuper: down=" + downOk + " up=" + upOk);
            return downOk && upOk;
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException)
                    ? e.getCause() : e;
            Log.w(TAG, "injectVolumeDownViaSuper: failed: "
                    + (cause != null ? cause.getMessage() : e.getMessage()));
            return false;
        }
    }
}
