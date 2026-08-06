package com.pluskeymap.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // Only auto-start if service was running before the reboot.
        // We infer this from whether the user completed setup (READ_LOGS granted)
        // and had not explicitly stopped the service (no stored "stopped" pref).
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_SETTINGS, Context.MODE_PRIVATE);
        boolean wasRunning = prefs.getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);
        if (!wasRunning) {
            Log.d("PKM_Boot", "Service was not running before reboot — skipping auto-start");
            return;
        }

        boolean hasLogPerm = context.checkSelfPermission("android.permission.READ_LOGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!hasLogPerm) {
            Log.d("PKM_Boot", "READ_LOGS not granted — cannot auto-start");
            return;
        }

        Log.d("PKM_Boot", "Restarting DetectorService after boot");
        Intent svc = new Intent(context, DetectorService.class)
                .setAction(DetectorService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
