package com.pluskeymap.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads logcat for Plus Key events from OplusKeyEventUtil lines.
 *
 * OxygenOS 15 permanently suppresses OEM logcat tags for third-party apps.
 * Always uses broad filter. Watchdog detects actual process death rather than
 * silence — OxygenOS throttles logcat output in ~32s bursts so silence alone
 * is NOT an indicator of a kill.
 *
 * Also tracks the foreground package by parsing ActivityManager "Displayed"
 * and "START" lines from logcat. This is the only reliable zero-permission
 * method to identify the foreground app on Android 10+ (getRunningAppProcesses
 * only returns the caller's own process since Android 10).
 */
public class LogcatWatcher implements Runnable {

    private static final String TAG              = "PKM_Logcat";
    private static final long   DEBOUNCE_MS      = 30;
    private static final long   RELEASE_PAUSE_MS = 50;
    // Max wait for first line before declaring permission denied.
    private static final long   FIRST_LINE_TIMEOUT_MS  = 20_000;
    // Max wait for an OEM key tag line after generic logcat access is confirmed.
    // If the user doesn't press the Plus Key within this window, declare denied.
    private static final long   OEM_VERIFY_TIMEOUT_MS  = 60_000;
    private static final long   WATCHDOG_INTERVAL_MS   = 2_000;

    public static final int PLUS_KEY_CODE = 9999;

    /**
     * Last foreground package seen in ActivityManager logcat lines.
     * Updated whenever the OS logs "Displayed pkg/Activity" or
     * "START u0 {... pkg=...}" lines, which happen on every activity launch.
     * Volatile so ActionExecutor can read it from a different thread safely.
     */
    private static volatile String sForegroundPackage = null;

    /**
     * Wall-clock timestamp (System.currentTimeMillis()) of the last
     * sForegroundPackage update. Used by ActionExecutor to decide whether
     * the cached foreground package is fresh enough to be authoritative.
     */
    private static volatile long sForegroundPackageTimestamp = 0;

    /**
     * True when sForegroundPackage was last written by the AccessibilityService
     * (higher-fidelity source) rather than the logcat parser.
     *
     * When this flag is set AND the current package is NOT a camera app, the
     * logcat parser is blocked from overwriting the field with a camera package.
     * This prevents the race condition where trailing logcat lines from the
     * camera session (e.g. "pkg=com.oplus.camera" from SurfaceControl teardown)
     * re-poison the foreground state after the a11y has already correctly
     * reported that the user returned to the launcher.
     *
     * The flag is cleared whenever logcat writes a non-camera package so that
     * logcat can still update the field normally in all other scenarios.
     */
    private static volatile boolean sLastWriteByA11y = false;

    /** Returns the last known foreground package, or null if not yet seen. */
    public static String getForegroundPackage() {
        return sForegroundPackage;
    }

    /**
     * Returns how many milliseconds have passed since the foreground package
     * was last updated, or Long.MAX_VALUE if it has never been set.
     */
    public static long getForegroundPackageAgeMs() {
        long ts = sForegroundPackageTimestamp;
        return ts == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - ts;
    }

    /**
     * Externally sets the foreground package from a higher-fidelity source.
     *
     * Called by PlusKeyService (the AccessibilityService) whenever it receives
     * a TYPE_WINDOW_STATE_CHANGED event, which fires instantly when any app
     * comes to the foreground — including when the camera is dismissed via Back
     * or Home. This is the authoritative signal that replaces logcat tracking
     * for the exit-detection case: the logcat parser reliably sees camera *launch*
     * (via cmp=/SurfaceView lines) but does NOT see camera *exit*, because the
     * launcher/home screen does not emit those logcat lines when it resumes.
     *
     * Thread-safe: all fields are volatile.
     */
    public static void setForegroundPackage(String pkg) {
        if (pkg == null || pkg.equals(sForegroundPackage)) return;
        Log.d(TAG, "setForegroundPackage [a11y]: " + pkg);
        sForegroundPackage = pkg;
        sForegroundPackageTimestamp = System.currentTimeMillis();
        sLastWriteByA11y = true;
    }

    private static final String[] TAG_PATTERNS = {
        "KEYLOG_OplusKeyEventUtil",
        "OplusKeyEventUtil",
        "KeyEventUtil",
    };

    private static final String[] MSG_PATTERNS = {
        "should not notify undefined keys in restrict listen mode",
        "undefined keys in restrict",
        "restrict listen mode",
        "notifyUndefinedKeysInRestrictMode",
        "notifyUndefinedKey",
    };

    private final DetectorService service;
    private volatile boolean running = true;

    volatile Process logcatProcess;

    private volatile boolean firstLineReceived = false;
    private volatile boolean oemKeyConfirmed   = false;

    private long             lastEventTime = 0;
    private volatile boolean isDown        = false;
    private final Handler    mainHandler   = new Handler(Looper.getMainLooper());
    private Runnable         upRunnable;

    public LogcatWatcher(DetectorService service) {
        this.service = service;
    }

    /** Legacy compat -- startBroad param ignored, always broad now. */
    public LogcatWatcher(DetectorService service, boolean ignored) {
        this.service = service;
    }

    public void stop() {
        running = false;
        if (logcatProcess != null) logcatProcess.destroy();
        mainHandler.removeCallbacks(upRunnable != null ? upRunnable : () -> {});
    }

    /** True only if the underlying OS process is alive. */
    public boolean isProcessAlive() {
        if (logcatProcess == null) return false;
        try {
            logcatProcess.exitValue();
            return false; // exitValue() succeeds only when process has ended
        } catch (IllegalThreadStateException e) {
            return true;  // still running
        }
    }

    @Override
    public void run() {
        Log.d(TAG, "Logcat watcher started (broad filter, OxygenOS 15 mode)");
        runLogcat();
    }

    private void runLogcat() {
        try {
            logcatProcess = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-v", "tag", "-T", "1"});

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(logcatProcess.getErrorStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (running) Log.w(TAG, "logcat stderr: " + l);
                    }
                } catch (Exception ignored) {}
            }, "pkm-logcat-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(logcatProcess.getInputStream(), StandardCharsets.UTF_8),
                    8192);

            Thread watchdog = new Thread(() -> {
                long firstLineDeadline = System.currentTimeMillis() + FIRST_LINE_TIMEOUT_MS;
                long oemVerifyDeadline = 0; // set once firstLineReceived flips
                while (running) {
                    try { Thread.sleep(WATCHDOG_INTERVAL_MS); } catch (InterruptedException e) { break; }
                    if (!running) break;

                    boolean alive = isProcessAlive();

                    if (!firstLineReceived) {
                        // Process dead before first line = genuine permission denial.
                        if (!alive) {
                            Log.w(TAG, "Logcat process exited before first line -- permission denied");
                            running = false;
                            mainHandler.post(() -> service.onLogcatFailed());
                            return;
                        }
                        // Process alive but no output yet -- check deadline.
                        if (System.currentTimeMillis() > firstLineDeadline) {
                            Log.w(TAG, "Logcat timeout " + FIRST_LINE_TIMEOUT_MS
                                    + " ms with no output -- permission denied");
                            running = false;
                            if (logcatProcess != null) logcatProcess.destroy();
                            mainHandler.post(() -> service.onLogcatFailed());
                            return;
                        }
                    } else if (!oemKeyConfirmed) {
                        // Generic logcat access confirmed, waiting for OEM key tag.
                        // Arm the OEM verify deadline on the first tick after firstLineReceived.
                        if (oemVerifyDeadline == 0) {
                            oemVerifyDeadline = System.currentTimeMillis() + OEM_VERIFY_TIMEOUT_MS;
                        }
                        if (!alive) {
                            Log.w(TAG, "Logcat process died during OEM verify -- Doze kill, restarting");
                            running = false;
                            mainHandler.post(() -> service.onLogcatKilledByDoze());
                            return;
                        }
                        if (System.currentTimeMillis() > oemVerifyDeadline) {
                            Log.w(TAG, "OEM key tag timeout " + OEM_VERIFY_TIMEOUT_MS
                                    + " ms -- system dialog was likely denied");
                            running = false;
                            if (logcatProcess != null) logcatProcess.destroy();
                            mainHandler.post(() -> service.onLogcatFailed());
                            return;
                        }
                    } else {
                        // OEM key confirmed. OxygenOS throttles output in ~32s bursts --
                        // silence is NORMAL. Only restart when process actually dies.
                        if (!alive) {
                            Log.w(TAG, "Logcat process died -- Doze kill, restarting");
                            running = false;
                            mainHandler.post(() -> service.onLogcatKilledByDoze());
                            return;
                        }
                    }
                }
            }, "pkm-logcat-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            String line;
            while (running && (line = reader.readLine()) != null) {
                if (!firstLineReceived) {
                    firstLineReceived = true;
                    Log.d(TAG, "Logcat access confirmed -- waiting for OEM key tag to verify system dialog");
                    mainHandler.post(() -> service.onLogcatVerifying());
                }
                // Track foreground package from ActivityManager lines.
                // Parsed BEFORE the OEM key check so it works from the first logcat line.
                parseForegroundPackage(line);

                if (matchesTagPattern(line) && matchesMsgPattern(line)) {
                    if (!oemKeyConfirmed) {
                        oemKeyConfirmed = true;
                        Log.d(TAG, "OEM key tag received -- system dialog was accepted, logcat confirmed");
                        mainHandler.post(() -> service.onLogcatConfirmed());
                    }
                    handleKeyLine();
                }
            }

            watchdog.interrupt();

        } catch (Exception e) {
            if (running) Log.e(TAG, "Logcat watcher error: " + e.getMessage());
        } finally {
            if (running) {
                if (firstLineReceived) {
                    Log.w(TAG, "Logcat process killed (Doze) -- restarting silently");
                    mainHandler.post(() -> service.onLogcatKilledByDoze());
                } else {
                    Log.w(TAG, "Logcat exited before first line -- permission denied");
                    mainHandler.post(() -> service.onLogcatFailed());
                }
            }
        }
    }

    private boolean matchesTagPattern(String line) {
        for (String p : TAG_PATTERNS) { if (line.contains(p)) return true; }
        return false;
    }

    private boolean matchesMsgPattern(String line) {
        for (String p : MSG_PATTERNS) { if (line.contains(p)) return true; }
        return false;
    }

    /**
     * Parses logcat lines to track the current foreground package.
     *
     * OxygenOS 15 does NOT emit standard ActivityManager "Displayed" or
     * "START pkg=" lines to third-party logcat readers. Instead, camera
     * launches appear under AIUnit/SurfaceControl tags with these patterns:
     *
     *   1. "Displayed pkg/Activity"          — AOSP ActivityManager standard
     *   2. "cmp=pkg/.Activity"               — OxygenOS ActivityTaskManager
     *   3. "pkg=com.example.app"             — START intent dump
     *   4. "callPackageName=com.oplus.camera" — AIUnit-PluginUnit (OxygenOS)
     *   5. "SurfaceView[com.oplus.camera/…]" — SurfaceControl (OxygenOS)
     *
     * IMPORTANT: We must skip lines that originate from our own process (TAG
     * prefix = "PKM_") to prevent a logcat self-feedback loop where our DIAG
     * log lines are read back and re-logged infinitely, burning OxygenOS's
     * per-process 300-line quota and causing LOG_FLOWCTRL DROPPED.
     */
    private void parseForegroundPackage(String line) {
        // Skip our own log output to prevent self-referencing feedback loops.
        // OxygenOS logcat format: "D/PKM_Logcat: ..." — the tag always starts PKM_.
        if (line.contains("PKM_")) return;

        // Pattern 1: "Displayed pkg/Activity" — AOSP standard, any tag
        int dispIdx = line.indexOf("Displayed ");
        if (dispIdx >= 0) {
            String rest = line.substring(dispIdx + "Displayed ".length()).trim();
            int slashIdx = rest.indexOf('/');
            if (slashIdx > 0) {
                String pkg = rest.substring(0, slashIdx);
                if (isValidPackageName(pkg)) {
                    updateForegroundPackage(pkg, "Displayed");
                    return;
                }
            }
        }

        // Pattern 2: "cmp=pkg/.Activity" — OxygenOS ActivityTaskManager format
        int cmpIdx = line.indexOf("cmp=");
        if (cmpIdx >= 0) {
            String rest = line.substring(cmpIdx + 4);
            int slashIdx = rest.indexOf('/');
            if (slashIdx > 0) {
                String pkg = rest.substring(0, slashIdx);
                if (isValidPackageName(pkg)) {
                    updateForegroundPackage(pkg, "cmp=");
                    return;
                }
            }
        }

        // Pattern 3: "pkg=com.example.app" in START intent dumps
        int pkgIdx = line.indexOf("pkg=");
        if (pkgIdx >= 0) {
            String rest = line.substring(pkgIdx + 4);
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == ' ' || c == '}' || c == '\t' || c == '\n' || c == ',') {
                    end = i;
                    break;
                }
            }
            String pkg = rest.substring(0, end).trim();
            if (isValidPackageName(pkg)) {
                updateForegroundPackage(pkg, "pkg=");
                return;
            }
        }

        // Pattern 4: "callPackageName=com.oplus.camera)" — AIUnit-PluginUnit (OxygenOS 15)
        // NOTE: the log line appends a closing ")" after the package name, so we must
        // use a strict allowlist of valid package name chars (alphanumeric, dot, underscore)
        // rather than a denylist of separators — otherwise the ")" is included.
        int callPkgIdx = line.indexOf("callPackageName=");
        if (callPkgIdx >= 0) {
            String rest = line.substring(callPkgIdx + "callPackageName=".length());
            int end = 0;
            while (end < rest.length() && isPackageNameChar(rest.charAt(end))) end++;
            String pkg = rest.substring(0, end);
            if (isValidPackageName(pkg)) {
                updateForegroundPackage(pkg, "callPackageName=");
                return;
            }
        }

        // Pattern 5: "SurfaceView[com.oplus.camera/com.oplus.camera.Camera]"
        // Seen in SurfaceControl logs when camera surface is created/focused.
        int svIdx = line.indexOf("SurfaceView[");
        if (svIdx >= 0) {
            String rest = line.substring(svIdx + "SurfaceView[".length());
            int slashIdx = rest.indexOf('/');
            int closeIdx = rest.indexOf(']');
            if (slashIdx > 0 && (closeIdx < 0 || slashIdx < closeIdx)) {
                String pkg = rest.substring(0, slashIdx);
                if (isValidPackageName(pkg)) {
                    updateForegroundPackage(pkg, "SurfaceView");
                    return;
                }
            }
        }
    }

    /** Returns true if the string looks like a valid Android package name. */
    private boolean isValidPackageName(String s) {
        return s != null && !s.isEmpty() && s.contains(".") && !s.contains(" ")
                && !s.contains("/") && s.length() < 128;
    }

    /**
     * Returns true if the character is valid inside an Android package name.
     * Package names are dot-separated identifiers: [a-zA-Z0-9_] segments joined by '.'.
     * Used to strip trailing punctuation (e.g. the ')' OxygenOS appends after
     * callPackageName=com.oplus.camera) in AIUnit-PluginUnit log lines).
     */
    private boolean isPackageNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '.';
    }

    /**
     * Packages whose logcat noise we must ignore entirely — they appear in
     * log lines constantly from background services or system overlays, never
     * because the user explicitly brought them to the foreground.
     *
     * com.pluskeymap.app      — our own process: emits pkg= lines on every key
     *                           dispatch, immediately after a camera shutter fire.
     * com.paget96.batteryguru — battery overlay: floods pkg= every ~5 s from
     *                           its notification/tile update service.
     * com.android.systemui    — fires pkg=/cmp= in logcat on every status-bar
     *                           update, volume panel, toast, etc. The a11y service
     *                           already handles systemui filtering via
     *                           SYSTEM_OVERLAY_PACKAGES; letting it through logcat
     *                           too would re-write sForegroundPackage to systemui
     *                           and reset the timestamp, making T1 appear stale.
     */
    private static final java.util.Set<String> BACKGROUND_NOISE_PACKAGES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "com.pluskeymap.app",
                    "com.paget96.batteryguru",
                    "com.android.systemui"
            ));

    /**
     * Helper: returns true if the package name belongs to a known camera app.
     * Mirrors the logic in ActionExecutor.isCameraPackage() but kept local to
     * avoid a cross-class dependency from a static method.
     */
    private static boolean isCameraPackageLogcat(String pkg) {
        if (pkg == null) return false;
        // Known camera packages (keep in sync with ActionExecutor.KNOWN_CAMERA_PACKAGES)
        switch (pkg) {
            case "com.oneplus.camera":
            case "com.oppo.camera":
            case "com.oplus.camera":
            case "com.google.android.GoogleCamera":
            case "com.google.android.GoogleCameraEng":
            case "com.google.android.GoogleCameraGo":
            case "com.sec.android.app.camera":
            case "com.android.camera":
            case "com.sonyericsson.android.camera":
            case "org.codeaurora.snapcam":
            case "net.sourceforge.opencamera":
                return true;
            default:
                return pkg.toLowerCase().contains("camera");
        }
    }

    private void updateForegroundPackage(String pkg, String source) {
        if (pkg.equals(sForegroundPackage)) return; // no change

        // Block known background-noise packages from overwriting a real foreground
        // package. These appear in logcat constantly from services and overlays but
        // are never actually foregrounded by the user.
        if (BACKGROUND_NOISE_PACKAGES.contains(pkg)) {
            // Exception: allow them through if we have NO package yet (startup).
            if (sForegroundPackage != null) return;
        }

        // A11y-priority guard: if the last update was from the AccessibilityService
        // (higher-fidelity, fires on every window focus change) AND it set a
        // non-camera package, do NOT allow the logcat parser to overwrite it back
        // with a camera package.
        //
        // This prevents the race condition seen in production:
        //   14:06:10.576  a11y → com.android.launcher   (user left camera)
        //   14:06:10.877  logcat → com.oplus.camera      (trailing SurfaceControl log)
        // Without this guard, the logcat line re-poisons the field back to the
        // camera package, and the next key press incorrectly fires the shutter.
        //
        // The guard is only active when a11y last wrote a non-camera package.
        // If a11y wrote a camera package (user opened camera), logcat is free to
        // update normally — there is no harmful case there.
        if (sLastWriteByA11y && isCameraPackageLogcat(pkg)) {
            String currentPkg = sForegroundPackage;
            if (currentPkg != null && !isCameraPackageLogcat(currentPkg)) {
                // A11y said we left the camera; logcat is trying to re-set a camera
                // package from a trailing log line. Reject it.
                Log.d(TAG, "parseForegroundPackage [" + source + "]: SUPPRESSED camera pkg="
                        + pkg + " (a11y already set non-camera pkg=" + currentPkg + ")");
                return;
            }
        }

        Log.d(TAG, "parseForegroundPackage [" + source + "]: " + pkg);
        sForegroundPackage = pkg;
        sForegroundPackageTimestamp = System.currentTimeMillis();
        // logcat wrote this update — clear the a11y-priority flag so future
        // logcat updates are not suppressed unnecessarily.
        sLastWriteByA11y = false;
    }

    private void handleKeyLine() {
        long now = System.currentTimeMillis();
        long gap = now - lastEventTime;
        if (!isDown) {
            if (gap < DEBOUNCE_MS) return;
            isDown = true;
            lastEventTime = now;
            Log.d(TAG, "Plus Key DOWN");
            service.handleLogcatKey("down");
        } else {
            lastEventTime = now;
        }
        if (upRunnable != null) mainHandler.removeCallbacks(upRunnable);
        upRunnable = () -> {
            isDown = false;
            Log.d(TAG, "Plus Key UP");
            service.handleLogcatKey("up");
        };
        mainHandler.postDelayed(upRunnable, RELEASE_PAUSE_MS);
    }
}
