package com.pluskeymap.app;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.color.DynamicColors;

import java.util.concurrent.TimeUnit;

public class PlusKeyApp extends Application {

    private static final String TAG              = "PKM_App";
    private static final String KEEPALIVE_WORK   = "pkm_keepalive";

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        SettingsActivity.applySavedTheme(this);

        // FIX: Schedule WorkManager periodic keepalive.
        // Fires every 15 minutes — OxygenOS respects WorkManager jobs better
        // than AlarmManager for apps in background. If DetectorService was killed,
        // KeepaliveWorker restarts it. Uses KEEP so only one job ever exists.
        scheduleKeepalive();
        // JobScheduler keepalive: fires every 60s, persisted in OS, survives SIGKILL.
        // WorkManager alone has 15-min minimum — too long after OxygenOS hard-kills.
        KeepaliveJobService.schedule(this);
        // Heartbeat: 3-minute repeating alarm living in AlarmManagerService —
        // survives SIGKILL even when onDestroy() never runs (process hard-killed).
        // Only arm if the service was actively running — avoids spurious restarts.
        boolean wasRunning = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);
        if (wasRunning) {
            HeartbeatReceiver.schedule(this);
        }
    }

    private void scheduleKeepalive() {
        try {
            PeriodicWorkRequest keepalive = new PeriodicWorkRequest.Builder(
                    KeepaliveWorker.class, 15, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    KEEPALIVE_WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    keepalive);
            Log.d(TAG, "Keepalive WorkManager job scheduled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule keepalive: " + e.getMessage());
        }
    }
}
