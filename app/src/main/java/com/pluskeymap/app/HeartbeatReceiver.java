package com.pluskeymap.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Periodic heartbeat alarm receiver that fires every 3 minutes.
 *
 * OxygenOS can kill a foreground service even with START_STICKY — the service
 * restarts via onDestroy() alarm, but if the process is SIGKILLed before
 * onDestroy() runs, that alarm never gets scheduled. This receiver is a
 * belt-and-suspenders safety net: it is scheduled via setRepeating() which
 * survives process death (the alarm entry lives in AlarmManagerService) and
 * checks every 3 minutes whether DetectorService is alive.
 *
 * If the service is dead but READ_LOGS is still granted → restart silently.
 * If READ_LOGS was revoked → post the permission-lost notification.
 * If the service was never started by the user → do nothing.
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    private static final String TAG     = "PKM_Heartbeat";
    static final String  ACTION         = "com.pluskeymap.app.HEARTBEAT";
    static final int     REQUEST_CODE   = 9905;
    static final long    INTERVAL_MS    = 3 * 60 * 1000L; // 3 minutes

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean wasRunning = context.getSharedPreferences(
                SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);

        if (!wasRunning) {
            Log.d(TAG, "onReceive: service was not running — skip");
            return;
        }

        boolean hasLogPerm = context.checkSelfPermission("android.permission.READ_LOGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

        if (!hasLogPerm) {
            Log.w(TAG, "onReceive: READ_LOGS revoked — notifying user");
            KeepaliveJobService.postPermissionLostNotificationStatic(context);
            return;
        }

        if (!DetectorService.isRunning()) {
            Log.w(TAG, "onReceive: DetectorService dead — restarting");
            Intent svc = new Intent(context, DetectorService.class)
                    .setAction(DetectorService.ACTION_START);
            ContextCompat.startForegroundService(context, svc);
        } else {
            Log.d(TAG, "onReceive: DetectorService alive — ok");
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /**
     * Schedule the repeating heartbeat alarm.
     * setRepeating() is inexact on API 19+ but survives process death —
     * the alarm entry lives in AlarmManagerService, not our process.
     * OxygenOS respects repeating RTC_WAKEUP alarms even in Doze for
     * foreground-service-associated packages.
     */
    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildPI(ctx, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long firstFire = System.currentTimeMillis() + INTERVAL_MS;
        am.setRepeating(AlarmManager.RTC_WAKEUP, firstFire, INTERVAL_MS, pi);
        Log.d(TAG, "Heartbeat alarm scheduled — every " + (INTERVAL_MS / 1000) + "s");
    }

    static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildPI(ctx, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) { am.cancel(pi); pi.cancel(); }
        Log.d(TAG, "Heartbeat alarm cancelled");
    }

    private static PendingIntent buildPI(Context ctx, int flags) {
        Intent i = new Intent(ctx, HeartbeatReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, i, flags);
    }
}
