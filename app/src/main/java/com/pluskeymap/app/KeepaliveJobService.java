package com.pluskeymap.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * JobScheduler-based keepalive that survives SIGKILL.
 *
 * Fires every ~60 seconds. Three cases:
 *  1. Service alive          → do nothing.
 *  2. Service dead, has perm → restart it silently.
 *  3. Service dead, no perm  → OxygenOS revoked READ_LOGS after process kill.
 *                              Post a high-priority notification so user taps
 *                              to open app and re-accept the OEM dialog.
 */
public class KeepaliveJobService extends JobService {

    private static final String TAG             = "PKM_JobKeepalive";
    static final int            JOB_ID          = 9903;
    private static final String CHANNEL_ALERT   = "pkm_perm_alert";
    private static final int    NOTIF_PERM_LOST = 2003;

    @Override
    public boolean onStartJob(JobParameters params) {
        Context ctx = getApplicationContext();

        boolean wasRunning = ctx.getSharedPreferences(
                SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);

        if (!wasRunning) {
            Log.d(TAG, "onStartJob: service was not running — skip");
            jobFinished(params, false);
            return false;
        }

        boolean hasLogPerm = ctx.checkSelfPermission("android.permission.READ_LOGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

        if (!hasLogPerm) {
            // OxygenOS revoked READ_LOGS when it killed the process.
            // User must open app and accept the OEM system dialog again.
            Log.w(TAG, "onStartJob: READ_LOGS revoked — notifying user");
            postPermissionLostNotificationStatic(ctx);
            jobFinished(params, false);
            return false;
        }

        if (!DetectorService.isRunning()) {
            Log.w(TAG, "onStartJob: DetectorService dead — restarting");
            Intent svc = new Intent(ctx, DetectorService.class)
                    .setAction(DetectorService.ACTION_START);
            ContextCompat.startForegroundService(ctx, svc);
        } else {
            Log.d(TAG, "onStartJob: DetectorService alive — ok");
        }

        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if OS stopped us early
    }

    // ── Notification ─────────────────────────────────────────────────────────

    static void postPermissionLostNotificationStatic(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ALERT,
                    "Plus Key Permission Alert",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alerts when system log permission needs re-granting");
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        // Open MainActivity — it will detect !hasLogPerm and trigger the OEM dialog flow.
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("reauth_logperm", true);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(ctx, CHANNEL_ALERT)
                .setContentTitle("Plus Key stopped — tap to re-enable")
                .setContentText("System log permission was revoked. Tap to re-accept the system dialog.")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("OxygenOS revoked the system log permission when the app was restarted. "
                                + "Tap to open the app and accept the system dialog to re-enable Plus Key detection."))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        nm.notify(NOTIF_PERM_LOST, notif);
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;

        if (js.getPendingJob(JOB_ID) != null) {
            Log.d(TAG, "Job already scheduled — skip");
            return;
        }

        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, KeepaliveJobService.class))
                .setPeriodic(60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .build();

        int result = js.schedule(job);
        Log.d(TAG, "Job scheduled: " + (result == JobScheduler.RESULT_SUCCESS ? "ok" : "FAILED"));
    }

    /** Dismiss the permission-lost notification (call after perm re-granted). */
    static void dismissPermNotification(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_PERM_LOST);
    }

    static void cancel(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }
}
