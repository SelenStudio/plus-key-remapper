package com.pluskeymap.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Schedules an alarm that directly launches LauncherActivity after a short
 * delay.  Android always permits activity launches triggered by an alarm
 * PendingIntent — unlike startActivity() calls from a BroadcastReceiver,
 * which are blocked on Android 10+ by background activity start restrictions.
 *
 * Because LauncherActivity checks isSetupDone() on every launch, no extra
 * permission check is needed here.  We set a single one-shot alarm; if the
 * user somehow runs the ADB command before it fires it simply opens the app
 * a couple of seconds later.
 */
public class RestartReceiver extends BroadcastReceiver {

    private static final String TAG          = "PKM_RestartReceiver";
    private static final int    REQUEST_CODE = 9901;
    private static final long   DELAY_MS     = 4_000L;

    /** Not used — alarm fires an activity PI directly, no receiver involved. */
    @Override
    public void onReceive(Context context, Intent intent) { }

    // ── Static helpers ───────────────────────────────────────────────────────

    /**
     * Arm a one-shot alarm that reopens the app DELAY_MS from now.
     * Uses an activity PendingIntent so Android launches the activity
     * even from the background/idle state.
     */
    static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildActivityPI(context,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + DELAY_MS, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + DELAY_MS, pi);
        }
        Log.d(TAG, "Restart alarm scheduled — will fire in " + DELAY_MS + " ms");
    }

    /** Cancel the pending restart alarm (call after app opens successfully). */
    static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildActivityPI(context,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
            Log.d(TAG, "Restart alarm cancelled");
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static PendingIntent buildActivityPI(Context context, int flags) {
        Intent launch = new Intent(context, LauncherActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, REQUEST_CODE, launch, flags);
    }
}
