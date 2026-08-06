package com.pluskeymap.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager periodic worker that ensures DetectorService stays alive.
 *
 * OxygenOS respects WorkManager constraints better than raw AlarmManager
 * for background apps. This fires every 15 minutes and restarts the service
 * if it has been killed — belt-and-suspenders on top of START_STICKY + alarms.
 *
 * WorkManager is initialized in PlusKeyApp and scheduled from DetectorService
 * on first start. Uses KEEP policy so only one periodic job exists.
 */
public class KeepaliveWorker extends Worker {

    private static final String TAG = "PKM_Keepalive";

    public KeepaliveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        // Only restart if user had the service running (not explicitly stopped)
        boolean wasRunning = ctx.getSharedPreferences(SettingsActivity.PREFS_SETTINGS,
                Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);

        if (!wasRunning) {
            Log.d(TAG, "doWork: service was not running — skip");
            return Result.success();
        }

        boolean hasLogPerm = ctx.checkSelfPermission("android.permission.READ_LOGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!hasLogPerm) {
            Log.w(TAG, "doWork: READ_LOGS revoked — notifying user");
            KeepaliveJobService.postPermissionLostNotificationStatic(ctx);
            return Result.success();
        }

        if (!DetectorService.isRunning()) {
            Log.w(TAG, "doWork: DetectorService not running — restarting");
            Intent svc = new Intent(ctx, DetectorService.class)
                    .setAction(DetectorService.ACTION_START);
            ContextCompat.startForegroundService(ctx, svc);
        } else {
            Log.d(TAG, "doWork: DetectorService alive — ok");
        }

        return Result.success();
    }
}
