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
     * Called from ActionExecutor when the camera shutter fires and a camera app
     * is in the foreground. OxygenOS camera maps volume-down to shutter at the
     * window input level — the same path the physical volume key uses.
     *
     * Why this must be called from the AccessibilityService process:
     *   InputManager.injectInputEvent() requires the caller's process to hold
     *   elevated input injection rights. Android grants these automatically to
     *   AccessibilityService processes. A regular ForegroundService gets
     *   SecurityException for the same call regardless of declared permissions.
     *
     * Implementation: use InputManager.getInstance() via reflection — this is
     * the correct path on API 21+. AccessibilityService does NOT expose its own
     * injectInputEvent() as a public or even accessible hidden method on API 35;
     * it internally delegates to InputManager anyway.
     *
     * INJECT_EVENTS_SYNC (= 2) is the sync mode that waits for the event to be
     * delivered before returning. Mode 0 (INJECT_EVENTS_ASYNC) is also valid but
     * sync gives us a reliable boolean result.
     *
     * @return true if both down and up events were accepted by the dispatcher.
     */
    public boolean injectVolumeDown() {
        // Strategy A: InputManager.getInstance().injectInputEvent() via reflection.
        // Called from the a11y process — the elevated rights are on the process,
        // not on the method, so the same reflection call that throws SecurityException
        // from DetectorService succeeds here.
        try {
            android.hardware.input.InputManager im =
                    android.hardware.input.InputManager.getInstance();

            java.lang.reflect.Method inject = android.hardware.input.InputManager.class
                    .getMethod("injectInputEvent",
                            android.view.InputEvent.class, int.class);
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

            // INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 2
            boolean downOk = (Boolean) inject.invoke(im, down, 2);
            boolean upOk   = (Boolean) inject.invoke(im, up,   2);
            Log.d(TAG, "injectVolumeDown [A-InputManager]: down=" + downOk + " up=" + upOk);
            if (downOk || upOk) return downOk && upOk;
            // Both false means the camera window didn't consume it — fall through to B.
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException)
                    ? e.getCause() : e;
            Log.w(TAG, "injectVolumeDown [A-InputManager]: failed ("
                    + (cause != null ? cause.getClass().getSimpleName()
                                    : e.getClass().getSimpleName())
                    + "): " + (cause != null ? cause.getMessage() : e.getMessage()));
        }

        // Strategy B: Context.INPUT_SERVICE → InputManager binder → injectInputEvent.
        // Some OEM builds expose the service under a different instance than
        // InputManager.getInstance(). Worth trying as a zero-cost fallback.
        try {
            Object im = getSystemService(INPUT_SERVICE);
            if (im != null) {
                java.lang.reflect.Method inject = im.getClass()
                        .getMethod("injectInputEvent",
                                android.view.InputEvent.class, int.class);
                inject.setAccessible(true);
                long t = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(t, t,
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0,
                        KeyEvent.KEYCODE_UNKNOWN, 0,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                KeyEvent up = new KeyEvent(t, SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0,
                        KeyEvent.KEYCODE_UNKNOWN, 0,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                boolean downOk = (Boolean) inject.invoke(im, down, 2);
                boolean upOk   = (Boolean) inject.invoke(im, up,   2);
                Log.d(TAG, "injectVolumeDown [B-ContextService]: down=" + downOk + " up=" + upOk);
                return downOk && upOk;
            }
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException)
                    ? e.getCause() : e;
            Log.w(TAG, "injectVolumeDown [B-ContextService]: failed ("
                    + (cause != null ? cause.getClass().getSimpleName()
                                    : e.getClass().getSimpleName())
                    + "): " + (cause != null ? cause.getMessage() : e.getMessage()));
        }

        return false;
    }
}
