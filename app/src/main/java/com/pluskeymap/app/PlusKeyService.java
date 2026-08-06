package com.pluskeymap.app;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AccessibilityService with two responsibilities:
 *
 *   1. Screenshot via performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT).
 *
 *   2. Camera shutter by clicking the shutter button node in the camera UI.
 *      InputManager.injectInputEvent() is permanently blocked on OxygenOS 15
 *      (Android 15 / API 35) even from an a11y process — SecurityException is
 *      thrown regardless. The correct unblocked path is node-based interaction:
 *      find the shutter button in the accessibility tree and call
 *      performAction(ACTION_CLICK). This is exactly what TalkBack does and
 *      requires no special permission beyond canRetrieveWindowContent.
 *
 * Shutter is triggered via a local broadcast (ACTION_INJECT_SHUTTER) to avoid
 * process-restart timing races with static field access.
 */
public class PlusKeyService extends AccessibilityService {

    private static final String TAG = "PKM_A11yService";

    /** Sent by ActionExecutor to request a camera shutter click. */
    public static final String ACTION_INJECT_SHUTTER = "com.pluskeymap.app.INJECT_SHUTTER";

    public static final String ACTION_KEY_DETECTED = "com.pluskeymap.KEY_DETECTED";
    public static final String EXTRA_KEYCODE       = "keycode";
    public static final String EXTRA_ACTION        = "action";

    /**
     * Kept for screenshot action only — never used for shutter injection.
     * Use the broadcast path to avoid process-restart timing races.
     */
    public static volatile PlusKeyService instance = null;

    /**
     * Resource-id fragments and content-description fragments that identify the
     * shutter button in the OxygenOS / OPPO camera UI.
     *
     * Strategy: walk every window's node tree and click the first node whose
     * resource-id or content-description matches any of these strings.
     * Add new strings here as OEM variants are discovered.
     */
    private static final Set<String> SHUTTER_ID_FRAGMENTS = new HashSet<>(Arrays.asList(
            // OxygenOS / ColorOS / OPPO camera
            "shutter",
            "btn_shutter",
            "capture",
            "take_photo",
            "takephoto",
            "camera_button",
            "shoot_button",
            "photo_button"
    ));

    private static final Set<String> SHUTTER_DESC_FRAGMENTS = new HashSet<>(Arrays.asList(
            "shutter",
            "take photo",
            "take picture",
            "capture",
            "shoot"
    ));

    private final BroadcastReceiver shutterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_INJECT_SHUTTER.equals(intent.getAction())) {
                Log.d(TAG, "shutterReceiver: received ACTION_INJECT_SHUTTER");
                clickShutterButton();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;

        IntentFilter filter = new IntentFilter(ACTION_INJECT_SHUTTER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(shutterReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(shutterReceiver, filter);
        }
        Log.d(TAG, "onServiceConnected: a11y service ready — shutter via node click");
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

    // ─── Shutter click via accessibility node tree ────────────────────────────

    /**
     * Walks every accessible window and clicks the first node that looks like
     * a camera shutter button. No injection, no hidden APIs, no permissions
     * beyond canRetrieveWindowContent (already declared in the a11y config).
     *
     * Returns true if a node was found and clicked.
     */
    private boolean clickShutterButton() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) {
            Log.w(TAG, "clickShutter: no accessible windows");
            // Fall back to global KEYCODE_VOLUME_DOWN via performGlobalAction
            return performVolumeDownGlobal();
        }

        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                AccessibilityNodeInfo shutter = findShutterNode(root);
                if (shutter != null) {
                    boolean clicked = shutter.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "clickShutter: performAction(CLICK) on ["
                            + shutter.getViewIdResourceName() + "] / ["
                            + shutter.getContentDescription() + "] -> " + clicked);
                    shutter.recycle();
                    root.recycle();
                    return clicked;
                }
            } finally {
                root.recycle();
            }
        }

        Log.w(TAG, "clickShutter: no shutter node found in any window — fallback to volume-down");
        return performVolumeDownGlobal();
    }

    /**
     * Recursively searches the node tree for a shutter button.
     * Caller is responsible for recycling the returned node.
     */
    private AccessibilityNodeInfo findShutterNode(AccessibilityNodeInfo node) {
        if (node == null) return null;

        if (isShutterNode(node)) {
            return AccessibilityNodeInfo.obtain(node);
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findShutterNode(child);
            child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    /**
     * Returns true if this node looks like a camera shutter button.
     * Checks resource-id and content-description against known fragments.
     */
    private boolean isShutterNode(AccessibilityNodeInfo node) {
        if (!node.isClickable()) return false;

        String resId = node.getViewIdResourceName();
        if (resId != null) {
            String lower = resId.toLowerCase();
            for (String fragment : SHUTTER_ID_FRAGMENTS) {
                if (lower.contains(fragment)) {
                    Log.d(TAG, "isShutterNode: id match [" + fragment + "] on " + resId);
                    return true;
                }
            }
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String lower = desc.toString().toLowerCase();
            for (String fragment : SHUTTER_DESC_FRAGMENTS) {
                if (lower.contains(fragment)) {
                    Log.d(TAG, "isShutterNode: desc match [" + fragment + "] on " + desc);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Last-resort fallback: if the window tree yields no shutter node,
     * try GLOBAL_ACTION_KEYCODE_HEADSETHOOK (some camera apps handle it)
     * or log clearly that manual ADB grant is needed.
     *
     * Note: GLOBAL_ACTION_TAKE_SCREENSHOT is intentionally NOT used here —
     * it would take a screenshot instead of triggering the shutter.
     */
    private boolean performVolumeDownGlobal() {
        // Log node tree for debugging so we can identify the correct view id.
        dumpWindowTree();
        Log.w(TAG, "performVolumeDownGlobal: no shutter node — check dump above to find "
                + "the correct shutter button id and add it to SHUTTER_ID_FRAGMENTS");
        return false;
    }

    /**
     * Dumps the first camera window's accessibility tree to logcat so the
     * correct shutter button resource-id can be identified and added above.
     */
    private void dumpWindowTree() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return;
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    Log.d(TAG, "=== WINDOW DUMP (pkg=" + root.getPackageName() + ") ===");
                    dumpNode(root, 0);
                } finally {
                    root.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "dumpWindowTree: " + e.getMessage());
        }
    }

    private void dumpNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return;
        String indent = new String(new char[depth * 2]).replace('\0', ' ');
        Log.d(TAG, indent
                + "cls=" + node.getClassName()
                + " id=" + node.getViewIdResourceName()
                + " desc=" + node.getContentDescription()
                + " click=" + node.isClickable()
                + " vis=" + node.isVisibleToUser());
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            dumpNode(child, depth + 1);
            child.recycle();
        }
    }
}
