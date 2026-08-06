package com.pluskeymap.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * Handles Shizuku detection, permission, and READ_LOGS grant.
 *
 * Flow:
 *   1. isShizukuInstalled()  — check Shizuku APK is on device
 *   2. isShizukuRunning()    — check Shizuku service is active
 *   3. requestPermission()   — ask user to grant Shizuku API access to this app
 *   4. grantReadLogs()       — run `pm grant` via privileged shell; returns result
 *
 * The caller (SetupActivity) binds the Shizuku listener callbacks and calls
 * these methods from the UI thread. All shell execution is done on a background
 * thread internally.
 */
public class ShizukuHelper {

    private static final String TAG        = "PKM_Shizuku";
    private static final int    REQUEST_CODE = 1001;

    public interface Callback {
        void onGrantSuccess();
        void onGrantFailure(String reason);
    }

    /** True if the Shizuku app is installed on this device. */
    public static boolean isShizukuInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** True if Shizuku service is running and ready to accept API calls. */
    public static boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    /** True if this app already has Shizuku API permission. */
    public static boolean hasShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                // Pre-v11 Shizuku always grants permission implicitly
                return true;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Request Shizuku API permission. The result comes back via
     * Shizuku.addRequestPermissionResultListener() which the Activity sets up.
     */
    public static void requestPermission() {
        try {
            if (Shizuku.isPreV11()) return; // auto-granted
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "requestPermission failed: " + e.getMessage());
        }
    }

    /**
     * Grants READ_LOGS (and SYSTEM_ALERT_WINDOW) to this app via Shizuku's
     * privileged shell. Runs on a background thread; posts result to callback
     * on the calling thread via the provided handler.
     */
    public static void grantReadLogs(Context ctx, android.os.Handler uiHandler, Callback callback) {
        new Thread(() -> {
            try {
                String pkg = ctx.getPackageName();

                // Run pm grant for READ_LOGS
                int exitCode = runShizukuCommand(
                        "pm grant " + pkg + " android.permission.READ_LOGS");

                if (exitCode != 0) {
                    postFailure(uiHandler, callback, "pm grant READ_LOGS exited with code " + exitCode);
                    return;
                }

                // Also grant SYSTEM_ALERT_WINDOW via appops (same as old ADB command)
                runShizukuCommand(
                        "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");

                Log.d(TAG, "READ_LOGS granted successfully via Shizuku");
                uiHandler.post(callback::onGrantSuccess);

            } catch (Exception e) {
                Log.e(TAG, "grantReadLogs error: " + e.getMessage());
                postFailure(uiHandler, callback, e.getMessage());
            }
        }, "pkm-shizuku-grant").start();
    }

    /**
     * Runs a shell command via Shizuku's privileged UserService shell.
     * Returns the process exit code (0 = success).
     */
    private static int runShizukuCommand(String command) throws Exception {
        // Shizuku.newProcess() runs the command in the Shizuku shell (uid=2000 shell
        // or uid=0 root, depending on how Shizuku was started). Shell uid has
        // permission to run `pm grant` for signature-level permissions.
        Process process = Shizuku.newProcess(
                new String[]{"sh", "-c", command}, null, null);
        return process.waitFor();
    }

    private static void postFailure(android.os.Handler h, Callback cb, String reason) {
        h.post(() -> cb.onGrantFailure(reason));
    }
}
