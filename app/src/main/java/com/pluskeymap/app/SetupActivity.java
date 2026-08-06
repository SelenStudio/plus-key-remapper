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

import rikka.shizuku.Shizuku;

public class SetupActivity extends AppCompatActivity {

    public static final String PREFS_SETUP = "pkm_setup";
    public static final String KEY_SKIPPED = "setup_skipped";

    // Fallback manual ADB command (no PC required if using wireless ADB)
    private static final String ADB_COMMAND =
            "adb shell \"pm grant com.pluskeymap.app android.permission.READ_LOGS && appops set com.pluskeymap.app SYSTEM_ALERT_WINDOW allow\"";

    private TextView      tvPermStatus;
    private MaterialButton btnShizukuGrant;
    private MaterialButton btnShizukuInstall;
    private View          shizukuGrantSection;
    private View          shizukuNotInstalledSection;

    private final Handler  handler           = new Handler(Looper.getMainLooper());
    private boolean        alreadyProceeding = false;

    // Shizuku permission result listener
    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    runShizukuGrant();
                } else {
                    showStatus("Shizuku permission denied. Use the manual ADB command below.", false);
                }
            };

    private final Runnable permissionPoller = new Runnable() {
        @Override public void run() {
            updateUi();
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

        tvPermStatus             = findViewById(R.id.tvPermStatus);
        btnShizukuGrant          = findViewById(R.id.btnShizukuGrant);
        btnShizukuInstall        = findViewById(R.id.btnShizukuInstall);
        shizukuGrantSection      = findViewById(R.id.shizukuGrantSection);
        shizukuNotInstalledSection = findViewById(R.id.shizukuNotInstalledSection);

        // Register Shizuku permission callback
        Shizuku.addRequestPermissionResultListener(shizukuPermListener);

        // Developer options button
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

        // Shizuku grant button
        btnShizukuGrant.setOnClickListener(v -> {
            if (!ShizukuHelper.isShizukuRunning()) {
                Snackbar.make(findViewById(android.R.id.content),
                        "Shizuku is not running. Open Shizuku and start the service first.",
                        Snackbar.LENGTH_LONG).show();
                return;
            }
            if (ShizukuHelper.hasShizukuPermission()) {
                runShizukuGrant();
            } else {
                ShizukuHelper.requestPermission();
            }
        });

        // Shizuku install button — opens Play Store
        btnShizukuInstall.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=moe.shizuku.privileged.api")));
            } catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")));
            }
        });

        // Copy ADB command (manual fallback)
        MaterialButton btnCopy = findViewById(R.id.btnCopyCommand);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb command", ADB_COMMAND));
            btnCopy.setText("Copied!");
            btnCopy.postDelayed(() -> btnCopy.setText("Copy Command"), 2_000);
        });

        TextView tvCommand = findViewById(R.id.tvAdbCommand);
        tvCommand.setText(ADB_COMMAND);

        // Skip
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
        updateUi();
        handler.postDelayed(permissionPoller, 1_500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(permissionPoller);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
    }

    private void updateUi() {
        boolean logGranted     = isLogPermissionGranted();
        boolean overlayGranted = isOverlayPermissionGranted();
        boolean allGranted     = logGranted && overlayGranted;

        // Show/hide Shizuku sections based on install state
        boolean shizukuInstalled = ShizukuHelper.isShizukuInstalled(this);
        shizukuGrantSection.setVisibility(shizukuInstalled ? View.VISIBLE : View.GONE);
        shizukuNotInstalledSection.setVisibility(shizukuInstalled ? View.GONE : View.VISIBLE);

        // Update grant button label based on Shizuku running state
        if (shizukuInstalled) {
            boolean running = ShizukuHelper.isShizukuRunning();
            btnShizukuGrant.setEnabled(running);
            btnShizukuGrant.setText(running ? "Grant via Shizuku" : "Open Shizuku first");
        }

        if (allGranted) {
            showStatus("All permissions granted. Starting...", true);
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
            showStatus(sb.toString().trim(), false);
        }
    }

    private void runShizukuGrant() {
        btnShizukuGrant.setEnabled(false);
        btnShizukuGrant.setText("Granting...");
        showStatus("Granting permissions via Shizuku...", true);

        ShizukuHelper.grantReadLogs(this, handler, new ShizukuHelper.Callback() {
            @Override
            public void onGrantSuccess() {
                showStatus("Granted! Starting...", true);
                handler.postDelayed(() -> updateUi(), 600);
            }

            @Override
            public void onGrantFailure(String reason) {
                btnShizukuGrant.setEnabled(true);
                btnShizukuGrant.setText("Grant via Shizuku");
                showStatus("Grant failed: " + reason + "\nTry the manual ADB command below.", false);
            }
        });
    }

    private void showStatus(String msg, boolean success) {
        tvPermStatus.setText(msg);
        tvPermStatus.setTextColor(success
                ? getColor(android.R.color.holo_green_dark)
                : getColor(android.R.color.holo_red_dark));
        tvPermStatus.setVisibility(View.VISIBLE);
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

    public static void openOxygenOsAppLaunchSettings(android.content.Context ctx) {
        String[] candidates = {
            "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter/com.oplus.safecenter.permission.startup.StartupAppListActivity",
        };
        for (String candidate : candidates) {
            String[] parts = candidate.split("/");
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(parts[0], parts[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        try {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + ctx.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(fallback);
        } catch (Exception ignored) {}
    }
}
