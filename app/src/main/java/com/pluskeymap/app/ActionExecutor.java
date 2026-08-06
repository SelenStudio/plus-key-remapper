package com.pluskeymap.app;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ActionExecutor {

    private static final String TAG   = "PKM_Executor";
    private static final String PREFS = "pkm_prefs";
    public static final String KEY_ACTION_SINGLE             = "action_single";
    public static final String KEY_LAUNCH_PKG_SINGLE         = "launch_pkg_single";
    public static final String KEY_CUSTOM_INTENT_SINGLE      = "custom_intent_single";
    public static final String KEY_ACTION_LONG               = "action_long";
    public static final String KEY_LAUNCH_PKG_LONG           = "launch_pkg_long";
    public static final String KEY_CUSTOM_INTENT_LONG        = "custom_intent_long";
    public static final String KEY_DETECTED_KEYCODE          = "detected_keycode";
    /** When true, single tap fires KEYCODE_CAMERA whenever a camera app is in the foreground. */
    public static final String KEY_CAMERA_SHUTTER_ENABLED   = "camera_shutter_enabled";
    public static final int    KEYCODE_UNSET                 = -999;

    /**
     * Package names of well-known camera apps that should receive the shutter action.
     * Covers OEM stock apps, Google Camera, and the most popular third-party options.
     * Detection is also extended to any app that contains "camera" in its package name
     * as a catch-all for less common apps.
     */
    private static final Set<String> KNOWN_CAMERA_PACKAGES = new HashSet<>(Arrays.asList(
            // OnePlus / OxygenOS stock camera
            "com.oneplus.camera",
            // OPPO / ColorOS stock camera
            "com.oppo.camera",
            "com.oplus.camera",
            // Google Camera (Pixel and GCam ports)
            "com.google.android.GoogleCamera",
            "com.google.android.GoogleCameraEng",
            "com.google.android.GoogleCameraGo",
            // Samsung stock camera
            "com.sec.android.app.camera",
            // MIUI / Xiaomi stock camera
            "com.android.camera",
            // Snap Camera (Sony)
            "com.sonyericsson.android.camera",
            // AOSP stock camera
            "org.codeaurora.snapcam",
            // Open Camera (popular open-source)
            "net.sourceforge.opencamera",
            // A-Cam
            "com.acapella.android.acam",
            // Halide
            "com.lux.halide",
            // ProCam
            "com.procam.procam",
            // Bacon Camera
            "com.oneplus.factorymode"
    ));

    private final Context context;
    private static boolean torchOn = false;
    private String torchCameraId = null;
    private final CameraManager cameraManager;

    public ActionExecutor(Context context) {
        this.context = context;
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cameraManager.getCameraIdList()) {
                torchCameraId = id;
                break;
            }
        } catch (Exception ignored) {}
    }

    // ─── Dispatch ─────────────────────────────────────────────────────────────

    /**
     * Variant of execute() used exclusively for single-tap actions.
     *
     * If the Camera Shutter override is enabled in prefs AND a camera app is
     * currently in the foreground, the normal single-tap action is suppressed and
     * KEYCODE_CAMERA is injected instead. In all other situations this method
     * delegates straight to execute().
     */
    public void executeForSingleTap(int action, String launchPkg, String customIntent) {
        SharedPreferences settings = context.getSharedPreferences(
                SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE);
        boolean shutterEnabled = settings.getBoolean(KEY_CAMERA_SHUTTER_ENABLED, false);

        Log.d(TAG, "executeForSingleTap: action=" + action
                + " shutterEnabled=" + shutterEnabled
                + " settingsPrefsName=" + SettingsActivity.PREFS_SETTINGS
                + " logcatForegroundPkg=" + LogcatWatcher.getForegroundPackage());

        if (!shutterEnabled) {
            Log.d(TAG, "executeForSingleTap: shutter disabled -> normal execute");
            execute(action, launchPkg, customIntent);
            return;
        }

        boolean cameraInFg = isCameraAppInForeground();
        Log.d(TAG, "executeForSingleTap: cameraInForeground=" + cameraInFg);

        if (cameraInFg) {
            Log.d(TAG, "executeForSingleTap: camera shutter override active -> injecting KEYCODE_CAMERA");
            injectKey(KeyEvent.KEYCODE_CAMERA);
            return;
        }

        Log.d(TAG, "executeForSingleTap: camera not in foreground -> normal execute action=" + action);
        execute(action, launchPkg, customIntent);
    }

    public void execute(int action, String launchPkg, String customIntent) {
        Log.d(TAG, "execute() action=" + action + " pkg=" + launchPkg);
        switch (action) {
            case ActionConfig.ACTION_FLASHLIGHT:     toggleFlashlight();                           break;
            case ActionConfig.ACTION_VOLUME_UP:      adjustVolume(AudioManager.ADJUST_RAISE);      break;
            case ActionConfig.ACTION_VOLUME_DOWN:    adjustVolume(AudioManager.ADJUST_LOWER);      break;
            case ActionConfig.ACTION_MEDIA_PLAY:     sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case ActionConfig.ACTION_MEDIA_NEXT:     sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);    break;
            case ActionConfig.ACTION_MEDIA_PREV:     sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case ActionConfig.ACTION_DND_TOGGLE:     toggleDnd();                                  break;
            case ActionConfig.ACTION_RINGER_TOGGLE:  toggleRinger();                               break;
            case ActionConfig.ACTION_CUSTOM_INTENT:  fireCustomIntent(customIntent);               break;
            case ActionConfig.ACTION_CAMERA_SHUTTER: fireCameraShutter();                          break;
        }
    }

    // ─── Core launch logic ────────────────────────────────────────────────────

    /**
     * Launches an activity from a background Foreground Service without BAL block.
     *
     * Android blocks startActivity() from background processes (BAL). The fix:
     * briefly add a 1×1px transparent TYPE_APPLICATION_OVERLAY window, which
     * counts as a "visible window" and satisfies the BAL check. The dummy view
     * is removed immediately after startActivity() returns.
     *
     * SYSTEM_ALERT_WINDOW is granted via ADB during setup:
     *   adb shell appops set com.pluskeymap.app SYSTEM_ALERT_WINDOW allow
     */
    private void startActivityFromBackground(Intent intent, String label) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            Log.w(TAG, "startActivityFromBackground: no WindowManager for '" + label + "'");
            return;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        View dummyView = new View(context);
        try {
            wm.addView(dummyView, params);
            context.startActivity(intent);
            Log.d(TAG, "startActivityFromBackground: success for '" + label + "'");
        } catch (Exception e) {
            Log.w(TAG, "startActivityFromBackground: failed for '" + label + "': " + e.getMessage());
        } finally {
            try { wm.removeView(dummyView); } catch (Exception ignored) {}
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void toggleFlashlight() {
        if (torchCameraId == null) return;
        try { torchOn = !torchOn; cameraManager.setTorchMode(torchCameraId, torchOn); }
        catch (Exception ignored) {}
    }

    private void adjustVolume(int direction) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction,
                AudioManager.FLAG_SHOW_UI);
    }

    private void sendMediaKey(int keyCode) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    private void toggleDnd() {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (!nm.isNotificationPolicyAccessGranted()) {
            android.widget.Toast.makeText(context,
                    "Do Not Disturb requires notification policy access. Enable it in Settings.",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
            else
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            hapticToggle();
        } catch (SecurityException e) {
            android.widget.Toast.makeText(context,
                    "Do Not Disturb requires notification policy access. Enable it in Settings.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static final String KEY_RINGER_STATE = "ringer_cycle_state";

    private void toggleRinger() {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null || nm == null) return;

        SharedPreferences p = prefs(context);
        int state = p.getInt(KEY_RINGER_STATE, 0);
        String label; int nextState;

        switch (state) {
            case 0: am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); label = "Vibrate"; nextState = 1; break;
            case 1:
                if (nm.isNotificationPolicyAccessGranted())
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
                label = "DND"; nextState = 2; break;
            default:
                if (nm.isNotificationPolicyAccessGranted())
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> am.setRingerMode(AudioManager.RINGER_MODE_NORMAL), 160);
                label = "Ringer"; nextState = 0; break;
        }
        p.edit().putInt(KEY_RINGER_STATE, nextState).apply();
        android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show();
        hapticToggle();
    }

    private void hapticToggle() {
        SharedPreferences settings = context.getSharedPreferences(
                SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE);
        if (!settings.getBoolean(SettingsActivity.KEY_HAPTIC_FEEDBACK, true)) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vm.getDefaultVibrator().vibrate(
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            } else {
                @SuppressWarnings("deprecation")
                Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
                    else
                        v.vibrate(40);
                }
            }
        } catch (Exception ignored) {}
    }

    private void fireCustomIntent(String stored) {
        if (stored == null || stored.isEmpty()) return;

        String[] parts   = stored.split("\\|", -1);
        String action    = parts.length > 0 ? parts[0].trim() : "";
        String pkg       = parts.length > 1 ? parts[1].trim() : "";
        String component = parts.length > 2 ? parts[2].trim() : "";
        String data      = parts.length > 3 ? parts[3].trim() : "";

        // Special case: expand notification panel (toggle not possible without system perms)
        if ("com.android.systemui.statusbar.EXPAND_NOTIFICATIONS".equals(action)) {
            try {
                Class<?> sbClass = Class.forName("android.app.StatusBarManager");
                // Try both lookup methods
                Object sbService = context.getSystemService(sbClass);
                if (sbService == null) {
                    sbService = context.getSystemService("statusbar");
                }
                if (sbService != null) {
                    sbService.getClass().getMethod("expandNotificationsPanel").invoke(sbService);
                } else {
                    Log.w(TAG, "StatusBar service not found");
                }
            } catch (Exception e) {
                Log.w(TAG, "StatusBar expand failed: " + e.getMessage());
            }
            return;
        }

        try {
            Intent intent = new Intent();
            if (!action.isEmpty())  intent.setAction(action);
            if (!data.isEmpty())    intent.setData(android.net.Uri.parse(data));

            if (!pkg.isEmpty() && !component.isEmpty()) {
                String cls = component.startsWith(".") ? pkg + component : component;
                intent.setComponent(new android.content.ComponentName(pkg, cls));
            } else if (!pkg.isEmpty()) {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    startActivityFromBackground(launch, pkg);
                    return;
                }
                intent.setPackage(pkg);
            }

            String notifLabel = !component.isEmpty() ? component
                    : (!pkg.isEmpty() ? pkg : action);

            // Try startActivity via overlay trick first; broadcast as last resort
            try {
                startActivityFromBackground(intent, notifLabel);
            } catch (Exception e) {
                Log.w(TAG, "fireCustomIntent: all launch methods failed, trying broadcast: " + e.getMessage());
                context.sendBroadcast(intent);
            }

        } catch (Exception e) {
            Log.w(TAG, "fireCustomIntent failed: " + e.getMessage());
        }
    }

    // ─── Camera shutter ───────────────────────────────────────────────────────

    /**
     * Fires a camera shutter event, but ONLY when a camera app is in the foreground.
     *
     * Strategy: inject KEYCODE_CAMERA via InputManager.injectInputEvent() using
     * reflection. This is the standard approach for foreground key injection from
     * a background service without requiring an Accessibility Service or root.
     * SYSTEM_ALERT_WINDOW is already granted (required for the overlay BAL trick),
     * which also satisfies the injectInputEvent() permission gate on OxygenOS.
     *
     * Falls back to a silent no-op with a log entry if the foreground app is not
     * a recognised camera application, so the key press is never swallowed
     * unexpectedly in non-camera contexts.
     */
    private void fireCameraShutter() {
        if (!isCameraAppInForeground()) {
            Log.d(TAG, "fireCameraShutter: foreground app is not a camera, ignoring");
            return;
        }
        Log.d(TAG, "fireCameraShutter: camera app in foreground, injecting KEYCODE_CAMERA");
        injectKey(KeyEvent.KEYCODE_CAMERA);
    }

    /**
     * Maximum age of a logcat foreground-package reading that we still treat as
     * authoritative for a NEGATIVE (non-camera) result.
     *
     * When T1 returns a non-camera package that was seen within this window we
     * trust it immediately and skip T2/T3.  This prevents T2-UsageStats from
     * re-instating "com.oplus.camera" after the user has already left the camera
     * — the usage-stats query returns the camera as "most recently used" for many
     * seconds after it was backgrounded, which is exactly what caused the ~30 s
     * shutter-override hang reported in the field.
     *
     * The window is intentionally short (4 s) so that if the foreground watcher
     * somehow missed the very first logcat line (service cold-start race), we
     * still fall through to T2 rather than giving a spurious false-negative.
     */
    private static final long T1_AUTHORITATIVE_NON_CAMERA_AGE_MS = 4_000;

    /**
     * Maximum delta between "camera app last used" and now that we still count
     * as "camera is in the foreground" when evaluating UsageStats (T2).
     *
     * UsageStats.getLastTimeUsed() is updated when an app is brought to the
     * foreground, not when it's backgrounded.  So the camera's lastTimeUsed
     * remains pinned to the instant the user opened it — even 30 s after they
     * closed it — making it the "most recently used" app until something else
     * overtakes it.  We gate on a small window here to prevent that stale value
     * from triggering a shutter action long after the camera was dismissed.
     *
     * 3 s covers the realistic "opened camera, pressed button immediately" case
     * while cutting off the long stale tail.
     */
    private static final long T2_CAMERA_FRESHNESS_MS = 3_000;

    /**
     * Returns true when a camera app is the current foreground process.
     *
     * Three-tier detection strategy (Android 10+ getRunningAppProcesses only
     * returns the caller's own process, so it is useless here):
     *
     *   Tier 1 - LogcatWatcher.getForegroundPackage():
     *     The logcat process already running for key detection also sees
     *     ActivityManager "Displayed" and "START pkg=" lines on every activity
     *     launch. LogcatWatcher parses those and stores the last foreground
     *     package as a volatile static field. No extra permission needed.
     *
     *     A fresh non-camera result from T1 is treated as AUTHORITATIVE — we
     *     return false immediately without consulting T2/T3.  This prevents
     *     UsageStats from re-instating a recently-backgrounded camera app as
     *     the apparent foreground process (the root cause of the ~30 s delay
     *     before the normal single-tap action resumed after leaving camera).
     *
     *   Tier 2 - UsageStatsManager.queryUsageStats():
     *     Only reached when T1 has no data (service cold-start) or T1's data
     *     is too old to be authoritative.  Queries a 60-second window and picks
     *     the most-recently-used app, but gates on a freshness threshold so a
     *     camera app backgrounded more than T2_CAMERA_FRESHNESS_MS ago does
     *     NOT count as "in foreground".
     *     Requires PACKAGE_USAGE_STATS (granted via ADB during setup).
     *
     *   Tier 3 - ActivityManager.getRunningAppProcesses() (legacy fallback):
     *     Only returns our own process on Android 10+, but kept as a last
     *     resort since older devices / custom ROMs may still expose all procs.
     *
     * Every tier logs its result so we can trace exactly which path fires and
     * why the override is or is not triggered.
     */
    private boolean isCameraAppInForeground() {
        Log.d(TAG, "isCameraAppInForeground: starting detection");

        // --- Tier 1: LogcatWatcher foreground package tracking ---
        String logcatPkg = LogcatWatcher.getForegroundPackage();
        long   logcatAge = LogcatWatcher.getForegroundPackageAgeMs();
        Log.d(TAG, "isCameraAppInForeground [T1-logcat]: lastForegroundPkg=" + logcatPkg
                + " ageMs=" + (logcatAge == Long.MAX_VALUE ? "never" : logcatAge));
        if (logcatPkg != null) {
            boolean isCamera = isCameraPackage(logcatPkg);
            Log.d(TAG, "isCameraAppInForeground [T1-logcat]: isCameraPackage=" + isCamera
                    + " pkg=" + logcatPkg);
            if (isCamera) {
                // Camera confirmed via logcat — shutter override active.
                return true;
            }
            // T1 says the foreground app is NOT a camera.
            // If this reading is recent enough, trust it and skip T2/T3.
            // This is the critical guard against the "stale camera in UsageStats"
            // false-positive: even though the camera was the last app used, logcat
            // has since told us another app took focus, so we must not fire the shutter.
            if (logcatAge <= T1_AUTHORITATIVE_NON_CAMERA_AGE_MS) {
                Log.d(TAG, "isCameraAppInForeground [T1-logcat]: non-camera pkg is fresh ("
                        + logcatAge + " ms) -> returning false (skip T2/T3)");
                return false;
            }
            // T1 data is stale (> T1_AUTHORITATIVE_NON_CAMERA_AGE_MS) — fall through
            // to T2 so we don't miss the camera if the service just started and T1
            // hasn't caught up yet.
            Log.d(TAG, "isCameraAppInForeground [T1-logcat]: non-camera pkg is stale ("
                    + logcatAge + " ms) -> falling through to T2");
        } else {
            Log.d(TAG, "isCameraAppInForeground [T1-logcat]: no foreground pkg seen yet"
                    + " (no ActivityManager lines captured - camera opened before service started?)");
        }

        // --- Tier 2: UsageStatsManager (60-second window, two query styles) ---
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();

                // Primary: queryAndAggregateUsageStats over the last 60 seconds.
                Map<String, UsageStats> statsMap =
                        usm.queryAndAggregateUsageStats(now - 60_000, now);
                Log.d(TAG, "isCameraAppInForeground [T2-usage]: aggregate(60s) size="
                        + statsMap.size());

                // Fallback: queryUsageStats(INTERVAL_BEST) returns data on some
                // OEM builds even without the full permission grant.
                if (statsMap.isEmpty()) {
                    List<android.app.usage.UsageStats> statsList = usm.queryUsageStats(
                            UsageStatsManager.INTERVAL_BEST, now - 60_000, now);
                    Log.d(TAG, "isCameraAppInForeground [T2-usage]: queryBest(60s) size="
                            + (statsList != null ? statsList.size() : "null"));
                    if (statsList != null) {
                        for (android.app.usage.UsageStats s : statsList) {
                            statsMap.put(s.getPackageName(), s);
                        }
                    }
                }

                if (!statsMap.isEmpty()) {
                    String topPkg  = null;
                    long   topTime = 0;
                    for (Map.Entry<String, UsageStats> e : statsMap.entrySet()) {
                        long t = e.getValue().getLastTimeUsed();
                        if (isCameraPackage(e.getKey())) {
                            Log.d(TAG, "isCameraAppInForeground [T2-usage]: CAMERA pkg="
                                    + e.getKey() + " lastUsed=" + t + " now=" + now);
                        }
                        if (t > topTime) { topTime = t; topPkg = e.getKey(); }
                    }
                    long topDelta = now - topTime;
                    Log.d(TAG, "isCameraAppInForeground [T2-usage]: topPkg=" + topPkg
                            + " delta=" + topDelta + "ms");
                    if (topPkg != null) {
                        boolean isCamera = isCameraPackage(topPkg);
                        Log.d(TAG, "isCameraAppInForeground [T2-usage]: isCameraPackage=" + isCamera);
                        // Guard: UsageStats.lastTimeUsed is set when the app was *opened*, not
                        // when it was closed.  If the camera was the most-recently-used app but
                        // it was opened more than T2_CAMERA_FRESHNESS_MS ago, it has almost
                        // certainly been backgrounded since — don't count it as foreground.
                        if (isCamera) {
                            if (topDelta <= T2_CAMERA_FRESHNESS_MS) {
                                Log.d(TAG, "isCameraAppInForeground [T2-usage]: camera fresh ("
                                        + topDelta + " ms <= " + T2_CAMERA_FRESHNESS_MS + " ms) -> true");
                                return true;
                            } else {
                                Log.d(TAG, "isCameraAppInForeground [T2-usage]: camera stale ("
                                        + topDelta + " ms > " + T2_CAMERA_FRESHNESS_MS
                                        + " ms) -> not counting as foreground");
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "isCameraAppInForeground [T2-usage]: both queries empty."
                            + " Run: adb shell appops set com.pluskeymap.app GET_USAGE_STATS allow");
                }
            } else {
                Log.w(TAG, "isCameraAppInForeground [T2-usage]: UsageStatsManager null");
            }
        } catch (Exception e) {
            Log.w(TAG, "isCameraAppInForeground [T2-usage]: exception: " + e.getMessage());
        }

        // --- Tier 3: ActivityManager.getRunningAppProcesses() (legacy / OEM) ---
        try {
            ActivityManager am = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                Log.d(TAG, "isCameraAppInForeground [T3-procs]: procCount="
                        + (procs != null ? procs.size() : "null"));
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo proc : procs) {
                        Log.d(TAG, "isCameraAppInForeground [T3-procs]: proc="
                                + proc.processName + " importance=" + proc.importance
                                + " pkgList=" + (proc.pkgList != null
                                        ? java.util.Arrays.toString(proc.pkgList) : "null"));
                        if (proc.importance
                                != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            continue;
                        }
                        if (proc.pkgList == null) continue;
                        for (String pkg : proc.pkgList) {
                            if (isCameraPackage(pkg)) {
                                Log.d(TAG, "isCameraAppInForeground [T3-procs]: matched pkg=" + pkg);
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isCameraAppInForeground [T3-procs]: exception: " + e.getMessage());
        }

        Log.d(TAG, "isCameraAppInForeground: all tiers exhausted - no camera app detected");
        return false;
    }

    /**
     * Returns true if the given package name belongs to a known camera app or
     * contains "camera" as a substring (catch-all for OEM forks and variants).
     */
    private boolean isCameraPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (KNOWN_CAMERA_PACKAGES.contains(pkg)) {
            Log.d(TAG, "isCameraPackage: known list match: " + pkg);
            return true;
        }
        if (pkg.toLowerCase().contains("camera")) {
            Log.d(TAG, "isCameraPackage: substring match: " + pkg);
            return true;
        }
        return false;
    }

    /**
     * Dispatches a key event to the foreground app.
     *
     * For KEYCODE_CAMERA: uses Intent.ACTION_CAMERA_BUTTON ordered broadcast
     * with the KeyEvent as an extra — the exact sequence Android sends from a
     * physical camera button (PhoneWindowManager.interceptKeyBeforeQueueing).
     * OxygenOS camera registers a BroadcastReceiver for this action.
     * AudioManager.dispatchMediaKeyEvent(KEYCODE_CAMERA) is silently ignored
     * because OxygenOS camera does not register a MediaSession for it.
     *
     * For all other keys: AudioManager.dispatchMediaKeyEvent() is correct and
     * routes to the active MediaSession without needing INJECT_EVENTS.
     */
    private void injectKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            dispatchCameraButton();
        } else {
            dispatchMediaKey(keyCode);
        }
    }

    /**
     * Triggers the shutter in the foreground OxygenOS camera.
     *
     * Root cause of all previous failures: injectInputEvent() called from a
     * ForegroundService process throws SecurityException regardless of declared
     * permissions, because the caller does not hold the signature-level
     * INJECT_EVENTS grant. AudioManager and broadcast paths are confirmed dead
     * on OxygenOS 15.
     *
     * The fix: delegate injection to PlusKeyService (the AccessibilityService).
     * Android grants injectInputEvent() to AccessibilityService processes without
     * requiring INJECT_EVENTS — the same mechanism TalkBack and Switch Access use
     * to inject key events. The call enters the input dispatcher via the same
     * path as a physical volume key press, so OxygenOS camera receives it.
     *
     * S1 — PlusKeyService.injectVolumeDown():
     *      Inject KEYCODE_VOLUME_DOWN from the a11y service process. OxygenOS
     *      camera maps volume-down to shutter when in the viewfinder.
     *      Requires the user to enable the service in Settings > Accessibility.
     *      Logs a clear actionable message when the service is not connected.
     *
     * S2 — AudioManager.adjustStreamVolume() dead-end fallback:
     *      Confirmed no-op on OxygenOS 15. Kept as a harmless last-resort in
     *      case some future OEM fork observes volume-change callbacks.
     */
    private void dispatchCameraButton() {
        // Send a local broadcast to PlusKeyService (AccessibilityService).
        // The a11y service registers a receiver in onServiceConnected and performs
        // InputManager.injectInputEvent() from within its elevated process context.
        //
        // Using a broadcast instead of PlusKeyService.instance avoids the race where
        // the process restarts and the a11y service hasn't re-bound yet — instance
        // would be null even though the service is enabled in Settings.
        Intent shutterIntent = new Intent(PlusKeyService.ACTION_INJECT_SHUTTER);
        shutterIntent.setPackage(context.getPackageName());
        context.sendBroadcast(shutterIntent);
        Log.d(TAG, "dispatchCameraButton: broadcast ACTION_INJECT_SHUTTER sent"
                + " (a11yConnected=" + (PlusKeyService.instance != null) + ")");
    }

    private void dispatchMediaKey(int keyCode) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) { Log.w(TAG, "dispatchMediaKey: AudioManager null"); return; }
        try {
            long downTime = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(downTime, downTime,
                    KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(downTime, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP, keyCode, 0));
            Log.d(TAG, "dispatchMediaKey: KEYCODE=" + keyCode + " dispatched");
        } catch (Exception e) {
            Log.w(TAG, "dispatchMediaKey: failed (" + e.getClass().getSimpleName()
                    + "): " + e.getMessage());
        }
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}