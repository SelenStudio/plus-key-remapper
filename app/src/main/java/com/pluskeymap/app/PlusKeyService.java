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
 *
 * InputManager.injectInputEvent() is permanently blocked on OxygenOS 15
 * (Android 15 / API 35) even from an a11y process. The correct unblocked
 * path is node-based interaction: find the shutter button in the
 * accessibility tree via content-description or resource-id and call
 * performAction(ACTION_CLICK). This is the same mechanism TalkBack uses
 * and requires no permissions beyond canRetrieveWindowContent.
 *
 * Confirmed working on OxygenOS 15: the OxygenOS camera shutter button
 * has content-description = "Shutter" button (no resource-id).
 *
 * Shutter is triggered via a local broadcast (ACTION_INJECT_SHUTTER) sent
 * by ActionExecutor, avoiding process-restart timing races.
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
     * Resource-id fragments that identify the shutter button.
     * Matched case-insensitively via String.contains().
     */
    private static final Set<String> SHUTTER_ID_FRAGMENTS = new HashSet<>(Arrays.asList(
            "shutter",
            "btn_shutter",
            "capture",
            "take_photo",
            "takephoto",
            "camera_button",
            "shoot_button",
            "photo_button"
    ));

    /**
     * Content-description fragments that identify the shutter button.
     * Matched case-insensitively via String.contains().
     *
     * OxygenOS 15 camera uses content-description = "Shutter" button
     * (no resource-id on the node), confirmed in production.
     */
    private static final Set<String> SHUTTER_DESC_FRAGMENTS = new HashSet<>(Arrays.asList(
            "shutter",
            "take photo",
            "take picture",
            "capture",
            "shoot",
            "video recording button",
            "record"
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

    /**
     * System packages that fire TYPE_WINDOW_STATE_CHANGED constantly as overlays
     * or infrastructure layers, but are never the user-visible foreground app.
     *
     * Critically: com.android.systemui fires on EVERY screen interaction —
     * status bar updates, toasts, volume panel, etc. — making it dominate the
     * TYPE_WINDOW_STATE_CHANGED stream.  If we let it through, sForegroundPackage
     * gets overwritten to "systemui" every few seconds, which makes T1's timestamp
     * appear 5+ seconds old at the next key press, causing it to fall through to
     * T2-UsageStats (which still sees the camera as "most recently used").
     */
    private static final Set<String> SYSTEM_OVERLAY_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "android",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.sohu.inputmethod.sogou",
            "com.baidu.input",
            "com.iflytek.inputmethod"
    ));

    /**
     * Tracks the foreground package via AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED.
     *
     * This fires instantly when any app comes to the foreground — including when the
     * user presses Back or Home to leave the camera. It is guaranteed to fire before
     * the user can press the Plus Key again, making it a perfect zero-latency source
     * of truth for "what app is in the foreground right now".
     *
     * This completely supersedes the logcat-based foreground tracking for the purpose
     * of camera detection: the logcat parser sees the camera launch (via cmp= lines)
     * but does NOT reliably see the camera exit, because the launcher/home screen
     * does not emit cmp=/pkg= logcat lines when it resumes. The a11y event always fires.
     *
     * We write directly into LogcatWatcher.sForegroundPackage via the public setter
     * so that ActionExecutor.isCameraAppInForeground() Tier-1 sees the correct value
     * without any architectural changes to the detection pipeline.
     *
     * Filtering strategy: only accept events from TYPE_APPLICATION windows and skip
     * known system overlay packages (systemui chief among them).  systemui fires
     * TYPE_WINDOW_STATE_CHANGED on every status-bar update, toast, volume panel etc.
     * — if we let those through they overwrite the real foreground package every few
     * seconds, making T1's timestamp always appear stale and causing unnecessary
     * fallthrough to T2-UsageStats.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgCs = event.getPackageName();
            if (pkgCs == null) return;
            String pkg = pkgCs.toString();
            if (pkg.isEmpty()) return;

            // Skip our own app.
            if (pkg.equals(getPackageName())) return;

            // Skip system overlays that are never the real user-visible foreground.
            if (SYSTEM_OVERLAY_PACKAGES.contains(pkg)) return;

            // Only accept events whose source window is a real application window,
            // not a system overlay, input method, or accessibility overlay.
            // getSource() returns the node that generated the event; its window type
            // tells us whether this is a full-screen app or just a system layer.
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                AccessibilityWindowInfo window = source.getWindow();
                if (window != null) {
                    int windowType = window.getType();
                    window.recycle();
                    // Only TYPE_APPLICATION (1) and TYPE_SPLIT_SCREEN_DIVIDER (5) represent
                    // real user-launched app windows.  TYPE_SYSTEM (3) covers systemui,
                    // input methods, and overlays — those we skip.
                    if (windowType != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        source.recycle();
                        return;
                    }
                }
                source.recycle();
            }

            LogcatWatcher.setForegroundPackage(pkg);
        }
    }

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
     * Walks every accessible window and clicks the first node that matches
     * a known camera shutter button descriptor. No injection, no hidden APIs,
     * no permissions beyond canRetrieveWindowContent.
     *
     * OxygenOS 15: shutter button has no resource-id; matched via
     * content-description "Shutter" button (desc fragment: "shutter").
     */
    private void clickShutterButton() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) {
            Log.w(TAG, "clickShutter: no accessible windows — is the a11y service enabled?");
            return;
        }

        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                AccessibilityNodeInfo shutter = findShutterNode(root);
                if (shutter != null) {
                    boolean clicked = shutter.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "clickShutter: performAction(CLICK)"
                            + " id=[" + shutter.getViewIdResourceName() + "]"
                            + " desc=[" + shutter.getContentDescription() + "]"
                            + " -> " + clicked);
                    shutter.recycle();
                    return;
                }
            } finally {
                root.recycle();
            }
        }

        // No shutter node found — dump the window tree once so we can identify
        // the correct descriptor and add it to the fragment sets above.
        Log.w(TAG, "clickShutter: no shutter node found — dumping window tree for diagnosis");
        dumpWindowTree();
    }

    /**
     * Recursively DFS-searches the node tree for a shutter button node.
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
     * Checks clickability, then resource-id and content-description against
     * known OEM fragment sets.
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
     * Dumps the full accessibility window tree to logcat.
     * Only called when no shutter node was found, to aid diagnosis.
     * Look for clickable nodes near the bottom of the camera UI.
     */
    private void dumpWindowTree() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return;
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    Log.d(TAG, "=== WINDOW DUMP pkg=" + root.getPackageName() + " ===");
                    dumpNode(root, 0);
                    Log.d(TAG, "=== END WINDOW DUMP ===");
                } finally {
                    root.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "dumpWindowTree: " + e.getMessage());
        }
    }

    private void dumpNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 10) return;
        String indent = depth > 0 ? new String(new char[depth * 2]).replace('\0', ' ') : "";
        if (node.isClickable() || node.getContentDescription() != null) {
            Log.d(TAG, indent
                    + "cls=" + node.getClassName()
                    + " id=" + node.getViewIdResourceName()
                    + " desc=[" + node.getContentDescription() + "]"
                    + " click=" + node.isClickable()
                    + " vis=" + node.isVisibleToUser());
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            dumpNode(child, depth + 1);
            child.recycle();
        }
    }
}
