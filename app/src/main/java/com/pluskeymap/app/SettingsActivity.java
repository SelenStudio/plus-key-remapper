package com.pluskeymap.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.WindowCompat;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity {

    static final String PREFS_SETTINGS = "pkm_settings";
    private static final int REQUEST_NOTIF_PERMISSION = 101;
    static final String KEY_SERVICE_WAS_RUNNING = "service_was_running";
    static final String KEY_THEME               = "theme_mode";
    static final String KEY_SINGLE_ONLY_MODE    = "single_only_mode";
    static final String KEY_PERSISTENT_NOTIF    = "persistent_notif";
    static final String KEY_HAPTIC_FEEDBACK     = "haptic_feedback";

    private MaterialCardView cardSystem, cardLight, cardDark;
    private MaterialCardView cardSingleOnlyMode;
    private SwitchMaterial   switchSingleOnlyMode;
    private MaterialCardView cardPersistentNotif;
    private SwitchMaterial   switchPersistentNotif;
    private MaterialCardView cardHapticFeedback;
    private SwitchMaterial   switchHapticFeedback;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);

        prefs      = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        cardSystem = findViewById(R.id.cardThemeSystem);
        cardLight  = findViewById(R.id.cardThemeLight);
        cardDark   = findViewById(R.id.cardThemeDark);

        updateSelection(prefs.getInt(KEY_THEME, 0));

        cardSingleOnlyMode  = findViewById(R.id.cardLongPressProtection);
        switchSingleOnlyMode = findViewById(R.id.switchLongPressProtection);
        switchSingleOnlyMode.setChecked(prefs.getBoolean(KEY_SINGLE_ONLY_MODE, true));
        cardSingleOnlyMode.setOnClickListener(v -> {
            boolean enabled = !switchSingleOnlyMode.isChecked();
            switchSingleOnlyMode.setChecked(enabled);
            prefs.edit().putBoolean(KEY_SINGLE_ONLY_MODE, enabled).apply();
        });

        cardPersistentNotif  = findViewById(R.id.cardPersistentNotif);
        switchPersistentNotif = findViewById(R.id.switchPersistentNotif);
        switchPersistentNotif.setChecked(prefs.getBoolean(KEY_PERSISTENT_NOTIF, false));
        cardPersistentNotif.setOnClickListener(v -> {
            boolean enabling = !switchPersistentNotif.isChecked();
            if (enabling && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{ android.Manifest.permission.POST_NOTIFICATIONS },
                            REQUEST_NOTIF_PERMISSION);
                    return;
                }
            }
            applyPersistentNotifSetting(enabling);
        });

        cardHapticFeedback  = findViewById(R.id.cardHapticFeedback);
        switchHapticFeedback = findViewById(R.id.switchHapticFeedback);
        switchHapticFeedback.setChecked(prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true));
        cardHapticFeedback.setOnClickListener(v -> {
            boolean enabled = !switchHapticFeedback.isChecked();
            switchHapticFeedback.setChecked(enabled);
            prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, enabled).apply();
        });

        cardSystem.setOnClickListener(v -> applyTheme(0));
        cardLight.setOnClickListener(v  -> applyTheme(1));
        cardDark.setOnClickListener(v   -> applyTheme(2));

        // FIX: OxygenOS background permission shortcut
        // Tapping this card opens OxygenOS "App Launch" settings so the user
        // can set the app to Manual and enable all background toggles.
        // Open app battery settings so user can set "Allow background activity".
        MaterialCardView cardBatteryUsage = findViewById(R.id.cardBatteryUsage);
        if (cardBatteryUsage != null) {
            cardBatteryUsage.setOnClickListener(v -> openBatteryUsageSettings());
        }
    }

    private void applyTheme(int mode) {
        prefs.edit().putInt(KEY_THEME, mode).apply();
        updateSelection(mode);
        switch (mode) {
            case 1: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);  break;
            case 2: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); break;
            default: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
        recreate();
    }

    private void updateSelection(int selected) {
        int colorSelected   = resolveAttr(com.google.android.material.R.attr.colorPrimaryContainer);
        int colorUnselected = resolveAttr(com.google.android.material.R.attr.colorSecondaryContainer);
        cardSystem.setCardBackgroundColor(selected == 0 ? colorSelected : colorUnselected);
        cardLight.setCardBackgroundColor(selected == 1  ? colorSelected : colorUnselected);
        cardDark.setCardBackgroundColor(selected == 2   ? colorSelected : colorUnselected);
        cardSystem.setStrokeWidth(0);
        cardLight.setStrokeWidth(0);
        cardDark.setStrokeWidth(0);
    }

    private int resolveAttr(int attrRes) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.data;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIF_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                applyPersistentNotifSetting(true);
            } else {
                switchPersistentNotif.setChecked(false);
                com.google.android.material.snackbar.Snackbar.make(
                        findViewById(android.R.id.content),
                        "Notification permission is required for this feature.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
            }
        }
    }

    private void applyPersistentNotifSetting(boolean enabled) {
        switchPersistentNotif.setChecked(enabled);
        // commit() instead of apply() — pref must be flushed before the service
        // reads it in onStartCommand, which can race on fast devices.
        prefs.edit().putBoolean(KEY_PERSISTENT_NOTIF, enabled).commit();
        if (DetectorService.isRunning()) {
            android.content.Intent i = new android.content.Intent(this, DetectorService.class)
                    .setAction(DetectorService.ACTION_UPDATE_PERSISTENT_NOTIF);
            // startForegroundService() required — plain startService() is silently
            // dropped on Android 12+ (targetSdk=35) when the activity is losing
            // foreground state (e.g. after a touch that causes focus loss).
            ContextCompat.startForegroundService(this, i);
        }
    }

    static void applySavedTheme(android.content.Context ctx) {
        int mode = ctx.getSharedPreferences(PREFS_SETTINGS, android.content.Context.MODE_PRIVATE)
                .getInt(KEY_THEME, 0);
        switch (mode) {
            case 1: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);  break;
            case 2: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); break;
            default: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    /**
     * Opens the per-app battery usage settings page directly.
     *
     * On OxygenOS/Android 12+ we try the ACTION_APP_BATTERY_USAGE_SETTINGS intent
     * (which goes straight to the per-app battery page) first. This is an OEM
     * extension on OxygenOS 12+ and some stock Android builds.
     * Fallback 1: ACTION_APPLICATION_DETAILS_SETTINGS — lands on the app info
     * page where the user can tap "Battery" themselves (one extra tap).
     * Fallback 2: General battery settings page.
     */
    private void openBatteryUsageSettings() {
        String pkg = getPackageName();

        // OxygenOS / Android 12+ direct per-app battery page
        try {
            Intent i = new Intent("android.settings.APP_BATTERY_USAGE_SETTINGS");
            i.setData(android.net.Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {}

        // Stock Android / OxygenOS fallback — App Info page (has Battery row)
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {}

        // Last resort — general battery settings
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }
}
