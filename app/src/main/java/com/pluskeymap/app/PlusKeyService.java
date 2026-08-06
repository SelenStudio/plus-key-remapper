package com.pluskeymap.app;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.lang.reflect.Method;

/**
 * Accessibility service with two responsibilities:
 *
 *   1. Screenshot via performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT).
 *
 *   2. Camera shutter injection: InputManager.injectInputEvent() works from an
 *      AccessibilityService process without INJECT_EVENTS because Android grants
 *      input injection rights to a11y services automatically. A regular
 *      ForegroundService gets SecurityException for the exact same call.
 *
 * Injection is triggered via a local broadcast (ACTION_INJECT_SHUTTER) rather
 * than a direct static-field call, which eliminates timing races where the a11y
 * service hasn't yet re-bound after a process restart.
 */
public class PlusKeyService extends AccessibilityService {

    private static final String TAG = "PKM_A11yService";

    /** Sent by ActionExecutor to request a KEYCODE_VOLUME_DOWN injection. */
    public static final String ACTION_INJECT_SHUTTER = "com.pluskeymap.app.INJECT_SHUTTER";

    public static final String ACTION_KEY_DETECTED = "com.pluskeymap.KEY_DETECTED";
    public static final String EXTRA_KEYCODE       = "keycode";
    public static final String EXTRA_ACTION        = "action";

    /**
     * Kept for screenshot action only. Never used for shutter injection —
     * use the broadcast path instead to avoid process-restart timing races.
     */
    public static volatile PlusKeyService instance = null;

    /** Cached reflected injectInputEvent method — resolved once at connect time. */
    private Method injectMethod = null;
    private Object inputManager = null;

    private final BroadcastReceiver shutterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_INJECT_SHUTTER.equals(intent.getAction())) {
                Log.d(TAG, "shutterReceiver: received ACTION_INJECT_SHUTTER");
                injectVolumeDown();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        resolveInjectMethod();

        IntentFilter filter = new IntentFilter(ACTION_INJECT_SHUTTER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(shutterReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(shutterReceiver, filter);
        }
        Log.d(TAG, "onServiceConnected: a11y service ready, injectMethod="
                + (injectMethod != null ? "found" : "NOT FOUND"));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        try { unregisterReceiver(shutterReceiver); } catch (Exception ignored) {}
        Log.d(TAG, "onDestroy: a11y service stopped");
        super.onDestroy();
    }

    /**
     * Resolve and cache InputManager.injectInputEvent() at connect time so
     * every shutter press doesn't repeat the hierarchy walk.
     */
    private void resolveInjectMethod() {
        try {
            Object im = getSystemService(INPUT_SERVICE);
            if (im == null) {
                Log.w(TAG, "resolveInjectMethod: INPUT_SERVICE null");
                return;
            }
            Class<?> cls = im.getClass();
            while (cls != null) {
                try {
                    Method m = cls.getDeclaredMethod("injectInputEvent",
                            android.view.InputEvent.class, int.class);
                    m.setAccessible(true);
                    injectMethod = m;
                    inputManager = im;
                    Log.d(TAG, "resolveInjectMethod: found on " + cls.getName());
                    return;
                } catch (NoSuchMethodException ignored) {
                    cls = cls.getSuperclass();
                }
            }
            Log.w(TAG, "resolveInjectMethod: injectInputEvent not found in hierarchy");
        } catch (Exception e) {
            Log.w(TAG, "resolveInjectMethod: " + e.getMessage());
        }
    }

    /**
     * Injects KEYCODE_VOLUME_DOWN into the input dispatcher.
     * OxygenOS camera maps volume-down to shutter when it holds window focus.
     */
    private void injectVolumeDown() {
        if (injectMethod == null || inputManager == null) {
            Log.w(TAG, "injectVolumeDown: method not resolved — retrying");
            resolveInjectMethod();
            if (injectMethod == null) {
                Log.w(TAG, "injectVolumeDown: still no method, giving up");
                return;
            }
        }
        try {
            long t = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(t, t,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0,
                    KeyEvent.KEYCODE_UNKNOWN, 0,
                    KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(t, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 0, 0,
                    KeyEvent.KEYCODE_UNKNOWN, 0,
                    KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);

            // INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 2
            boolean downOk = (Boolean) injectMethod.invoke(inputManager, down, 2);
            boolean upOk   = (Boolean) injectMethod.invoke(inputManager, up,   2);
            Log.d(TAG, "injectVolumeDown: down=" + downOk + " up=" + upOk);
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException)
                    ? e.getCause() : e;
            Log.w(TAG, "injectVolumeDown: failed ("
                    + (cause != null ? cause.getClass().getSimpleName()
                                    : e.getClass().getSimpleName())
                    + "): " + (cause != null ? cause.getMessage() : e.getMessage()));
            // Method may have become stale — clear cache so next call re-resolves.
            injectMethod = null;
            inputManager = null;
        }
    }
}
