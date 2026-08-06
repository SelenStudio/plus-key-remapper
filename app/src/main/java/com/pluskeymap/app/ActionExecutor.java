package com.pluskeymap.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;

public class ActionExecutor {

    private static final String TAG   = "PKM_Executor";
    private static final String PREFS = "pkm_prefs";
    public static final String KEY_ACTION_SINGLE         = "action_single";
    public static final String KEY_LAUNCH_PKG_SINGLE     = "launch_pkg_single";
    public static final String KEY_CUSTOM_INTENT_SINGLE  = "custom_intent_single";
    public static final String KEY_ACTION_LONG           = "action_long";
    public static final String KEY_LAUNCH_PKG_LONG       = "launch_pkg_long";
    public static final String KEY_CUSTOM_INTENT_LONG    = "custom_intent_long";
    public static final String KEY_DETECTED_KEYCODE      = "detected_keycode";
    public static final int    KEYCODE_UNSET             = -999;

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

    public void execute(int action, String launchPkg, String customIntent) {
        Log.d(TAG, "execute() action=" + action + " pkg=" + launchPkg);
        switch (action) {
            case ActionConfig.ACTION_FLASHLIGHT:   toggleFlashlight();                         break;
            case ActionConfig.ACTION_VOLUME_UP:    adjustVolume(AudioManager.ADJUST_RAISE);    break;
            case ActionConfig.ACTION_VOLUME_DOWN:  adjustVolume(AudioManager.ADJUST_LOWER);    break;
            case ActionConfig.ACTION_MEDIA_PLAY:   sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case ActionConfig.ACTION_MEDIA_NEXT:   sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);  break;
            case ActionConfig.ACTION_MEDIA_PREV:   sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case ActionConfig.ACTION_DND_TOGGLE:   toggleDnd();                                break;
            case ActionConfig.ACTION_RINGER_TOGGLE: toggleRinger();                            break;
            case ActionConfig.ACTION_CUSTOM_INTENT: fireCustomIntent(customIntent);            break;
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

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}