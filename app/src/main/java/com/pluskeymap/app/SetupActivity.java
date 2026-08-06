package com.pluskeymap.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

public class SetupActivity extends AppCompatActivity {

    public static final String PREFS_SETUP = "pkm_setup";
    public static final String KEY_SKIPPED = "setup_skipped";

    private static final String ADB_COMMAND =
            "adb shell \"pm grant com.pluskeymap.app android.permission.READ_LOGS && appops set com.pluskeymap.app SYSTEM_ALERT_WINDOW allow\"";

    private TextView tvPermStatus;

    private final Handler  handler           = new Handler(Looper.getMainLooper());
    private boolean        alreadyProceeding = false;

    private final Runnable permissionPoller = new Runnable() {
        @Override public void run() {
            updatePermissionStatus();
            handler.postDelayed(this, 1_500);
        }
    };

    public static boolean isSetupDone(android.content.Context ctx) {
        return ctx.checkSelfPermission("android.permission.READ_LOGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_setup);

        tvPermStatus = findViewById(R.id.tvPermStatus);

        TextView tvCommand = findViewById(R.id.tvAdbCommand);
        tvCommand.setText(ADB_COMMAND);

        // Step 2 — Developer Options
        MaterialButton btnDevOptions = findViewById(R.id.btnOpenDevOptions);
        btnDevOptions.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception e) {
                Snackbar.make(findViewById(android.R.id.content),
                        "Open Settings → About Phone → tap Build Number 7 times first.",
                        Snackbar.LENGTH_LONG).show();
            }
        });

        // Copy ADB command + arm restart alarm
        MaterialButton btnCopy = findViewById(R.id.btnCopyCommand);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb command", ADB_COMMAND));
            btnCopy.setText("Copied!");
            btnCopy.postDelayed(() -> btnCopy.setText("Copy Command"), 2_000);
        });

        // Skip — go to MainActivity in degraded mode
        MaterialButton btnSkip = findViewById(R.id.btnSkip);
        btnSkip.setOnClickListener(v -> {
            getSharedPreferences(PREFS_SETUP, MODE_PRIVATE)
                    .edit().putBoolean(KEY_SKIPPED, true).apply();
            startActivity(new Intent(this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isPermissionGranted()) {
            RestartReceiver.schedule(this);
        }
        updatePermissionStatus();
        handler.postDelayed(permissionPoller, 1_500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(permissionPoller);
    }

    private void updatePermissionStatus() {
        boolean logGranted     = isLogPermissionGranted();
        boolean overlayGranted = isOverlayPermissionGranted();
        boolean allGranted     = logGranted && overlayGranted;

        if (allGranted) {
            tvPermStatus.setText("✓ All permissions granted. Restarting…");
            tvPermStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            tvPermStatus.setVisibility(View.VISIBLE);

            if (!alreadyProceeding) {
                alreadyProceeding = true;
                handler.removeCallbacks(permissionPoller);
                RestartReceiver.cancel(this);
                handler.postDelayed(this::markDoneAndProceed, 800);
            }
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(logGranted     ? "✓ READ_LOGS granted\n" : "✗ READ_LOGS not granted\n");
            sb.append(overlayGranted ? "✓ SYSTEM_ALERT_WINDOW granted" : "✗ SYSTEM_ALERT_WINDOW not granted");
            tvPermStatus.setText(sb.toString().trim());
            tvPermStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            tvPermStatus.setVisibility(View.VISIBLE);
        }
    }

    private boolean isPermissionGranted() {
        return isLogPermissionGranted() && isOverlayPermissionGranted();
    }

    private boolean isLogPermissionGranted() {
        return checkSelfPermission("android.permission.READ_LOGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOverlayPermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void markDoneAndProceed() {
        getSharedPreferences(PREFS_SETUP, MODE_PRIVATE)
                .edit().putBoolean(KEY_SKIPPED, false).apply();
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    /**
     * FIX: Attempts to open OxygenOS/ColorOS "App Launch" control for this app.
     * User must set it to "Manual" and enable all 3 toggles:
     *   - Auto-launch
     *   - Secondary launch (allows other apps to start this one)
     *   - Run in background
     *
     * Without this, OxygenOS kills child processes (the logcat subprocess) and
     * eventually the service itself, regardless of WakeLock and foreground status.
    /**
     * Opens OxygenOS App Launch settings for this app (or falls back to App Info).
     * Can be called from anywhere — e.g. from a settings card in SettingsActivity.
     */
    public static void openOxygenOsAppLaunchSettings(android.content.Context ctx) {
        // OxygenOS 11-14 (OnePlus) — primary path
        String[] candidates = {
            "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            // ColorOS (OPPO/Realme) variant
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            // OxygenOS 15 / OOS 14 with security center repackaged
            "com.oplus.safecenter/com.oplus.safecenter.permission.startup.StartupAppListActivity",
        };

        for (String candidate : candidates) {
            String[] parts = candidate.split("/");
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(parts[0], parts[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return; // success
            } catch (Exception ignored) {}
        }

        // Fallback: standard App Info page (works on all Android)
        try {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + ctx.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(fallback);
        } catch (Exception ignored) {}
    }

    private void openOxygenOsAppLaunch() {
        openOxygenOsAppLaunchSettings(this);
    }
}
