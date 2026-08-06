package com.pluskeymap.app;

import android.animation.ValueAnimator;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    // Status card
    private MaterialCardView cardStatus;
    private android.widget.ImageView ivStatusIcon;
    private TextView         tvStatusTitle, tvStatusSub;
    private MaterialButton   btnEnableService;

    // Skipped-setup banner
    private MaterialCardView cardSetupBanner;
    private TextView         tvBannerTitle, tvBannerBody;
    private boolean serviceRunning = false;
    // Set when OEM logcat dialog is denied mid-session. Cleared when user retries.
    private boolean oemLogcatDenied = false;

    // Polls permission state while activity is visible — catches OEM "allow read
    // logs" system overlays that do NOT trigger onPause/onResume, and detects
    // when the OEM dialog is accepted (logcatEverConfirmed flips to true).
    private final android.os.Handler permPoller =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable permCheckRunnable = new Runnable() {
        @Override public void run() {
            if (serviceRunning && (!isPermissionGranted() || DetectorService.isLogcatDenied(MainActivity.this))) {
                stopDetectorService();
                applySkippedState(false);
            }
            // Refresh status on every tick — catches logcatConfirmed flipping to true
            // mid-session (dialog accepted while UI is open) without needing a broadcast.
            refreshServiceStatus(false);
            permPoller.postDelayed(this, 1_500);
        }
    };

    // Detector card
    private MaterialCardView cardDetector;
    private TextView         tvDetectedKeycode, tvDetectedAction;
    private MaterialButton   btnDetect, btnClearKey;
    private boolean          detectMode = false;

    // Binding rows
    private TextView tvSingleLabel;
    private TextView tvLongLabel;

    // Camera shutter card
    private MaterialCardView cardCameraShutter;
    private SwitchMaterial   switchCameraShutter;

    private final BroadcastReceiver keyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DetectorService.ACTION_LOGCAT_FAILED.equals(intent.getAction())) {
                serviceRunning = false;
                applySkippedState(false);
                refreshServiceStatus(true);
                return;
            }
            if (DetectorService.ACTION_LOGCAT_OEM_DENIED.equals(intent.getAction())) {
                serviceRunning = false;
                oemLogcatDenied = true;
                applySkippedState(true);
                refreshServiceStatus(true);
                return;
            }
            if (DetectorService.ACTION_LOGCAT_CONFIRMED.equals(intent.getAction())) {
                refreshServiceStatus(true);
                return;
            }
            if (DetectorService.ACTION_LOGCAT_VERIFYING.equals(intent.getAction())) {
                refreshServiceStatus(true);
                return;
            }
            int    code   = intent.getIntExtra(DetectorService.EXTRA_KEYCODE, -1);
            String act    = intent.getStringExtra(DetectorService.EXTRA_ACTION);
            String source = intent.getStringExtra("source");
            if (!detectMode) return;
            if (code == LogcatWatcher.PLUS_KEY_CODE && "logcat".equals(source)) {
                prefs.edit().putInt(ActionExecutor.KEY_DETECTED_KEYCODE, code).apply();
                tvDetectedKeycode.setText("✓ Plus Key detected!");
                tvDetectedAction.setText("Event: " + act + "  [via KEYLOG_OplusKeyEventUtil]");
                detectMode = false;
                DetectorService.setDetectMode(false);
                btnDetect.setText("Start Detection");
                // Restore service to its pre-detect state
                if (!serviceWasRunningBeforeDetect) {
                    stopDetectorService();
                }
                refreshServiceStatus(true);
                Snackbar.make(findViewById(android.R.id.content),
                        "Plus Key confirmed! Assign actions below.",
                        Snackbar.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        prefs = ActionExecutor.prefs(this);

        bindViews();
        applySkippedState(false);
        applySingleOnlyMode();
        refreshServiceStatus(false);
        refreshBindingLabels();

        if (savedInstanceState == null) runEntranceAnimation();

        // Launched from the "permission revoked" notification — auto-start service
        // so OxygenOS shows its READ_LOGS consent dialog immediately.
        if (getIntent() != null && getIntent().getBooleanExtra("reauth_logperm", false)) {
            handleReauthIntent();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("reauth_logperm", false)) {
            handleReauthIntent();
        }
    }

    /**
     * Called when the user taps the "permission revoked" notification.
     * Starts DetectorService so OxygenOS shows the READ_LOGS consent dialog.
     * isPermissionGranted() will return false here — we skip that guard
     * intentionally because starting the service is what triggers the dialog.
     */
    private void handleReauthIntent() {
        boolean wasRunning = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false);
        if (!wasRunning) return; // user had stopped it — don't auto-restart
        // Dismiss the notification now that user has responded
        KeepaliveJobService.dismissPermNotification(this);
        // Start service — this triggers the OEM READ_LOGS consent dialog on OxygenOS
        oemLogcatDenied = false;
        serviceRunning = true;
        launchDetectorService();
        refreshServiceStatus(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sync the in-memory flag with the actual service state so that
        // returning from background never incorrectly shows "Paused".
        serviceRunning = DetectorService.isRunning();
        // If the service is running but permission was revoked/denied while we
        // were in the background (e.g. user dismissed the overlay dialog), stop
        // the service immediately so the UI never shows "Active" without perms.
        if (serviceRunning && (!isPermissionGranted() || DetectorService.isLogcatDenied(this))) {
            stopDetectorService();
        }
        applySkippedState(oemLogcatDenied);
        applySingleOnlyMode();
        refreshServiceStatus(false);
        IntentFilter filter = new IntentFilter(DetectorService.ACTION_KEY_DETECTED);
        filter.addAction(DetectorService.ACTION_LOGCAT_FAILED);
        filter.addAction(DetectorService.ACTION_LOGCAT_OEM_DENIED);
        filter.addAction(DetectorService.ACTION_LOGCAT_CONFIRMED);
        filter.addAction(DetectorService.ACTION_LOGCAT_VERIFYING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(keyReceiver, filter);
        }
        permPoller.post(permCheckRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        permPoller.removeCallbacks(permCheckRunnable);
        unregisterReceiver(keyReceiver);
    }

    // ── View wiring ─────────────────────────────────────────────────────────

    private void bindViews() {
        cardSetupBanner    = findViewById(R.id.cardSetupBanner);
        tvBannerTitle      = findViewById(R.id.tvBannerTitle);
        tvBannerBody       = findViewById(R.id.tvBannerBody);

        cardStatus       = findViewById(R.id.cardStatus);
        ivStatusIcon     = findViewById(R.id.ivStatusIcon);
        tvStatusTitle    = findViewById(R.id.tvStatusTitle);
        tvStatusSub      = findViewById(R.id.tvStatusSub);
        btnEnableService = findViewById(R.id.btnEnableService);

        cardDetector      = findViewById(R.id.cardDetector);
        tvDetectedKeycode = findViewById(R.id.tvDetectedKeycode);
        tvDetectedAction  = findViewById(R.id.tvDetectedAction);
        btnDetect         = findViewById(R.id.btnDetect);
        btnClearKey       = findViewById(R.id.btnClearKey);

        tvSingleLabel = findViewById(R.id.tvSingleLabel);
        tvLongLabel   = findViewById(R.id.tvLongLabel);

        cardCameraShutter  = findViewById(R.id.cardCameraShutter);
        switchCameraShutter = findViewById(R.id.switchCameraShutter);
        bindCameraShutterCard();

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Re-open setup from the banner
        MaterialButton btnReopenSetup = findViewById(R.id.btnReopenSetup);
        btnReopenSetup.setOnClickListener(v -> {
            boolean permGranted      = isPermissionGranted();
            boolean oemEverConfirmed = DetectorService.isLogcatEverConfirmed(this);
            boolean oemDeniedPersist = DetectorService.isLogcatDenied(this);

            if (permGranted && (oemLogcatDenied || oemDeniedPersist || !oemEverConfirmed)) {
                // ADB perm is fine — we just need the OEM system dialog to appear.
                // Starting DetectorService is what triggers the dialog. Clear all
                // denial state first so the service gets a clean attempt.
                oemLogcatDenied = false;
                getSharedPreferences(DetectorService.PREFS_LOGCAT, MODE_PRIVATE)
                        .edit()
                        .putBoolean(DetectorService.KEY_LOGCAT_DENIED, false)
                        .apply();
                applySkippedState(false);
                // If service is already running (shouldn't be, but be safe), stop it
                // first so it restarts fresh and the dialog re-appears.
                if (DetectorService.isRunning()) {
                    Intent stop = new Intent(this, DetectorService.class)
                            .setAction(DetectorService.ACTION_STOP);
                    startService(stop);
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(this::startDetectorService, 500);
                } else {
                    startDetectorService();
                }
            } else {
                startActivity(new Intent(this, SetupActivity.class));
            }
        });

        btnDetect.setOnClickListener(v -> toggleDetectMode());

        btnClearKey.setOnClickListener(v -> {
            prefs.edit().remove(ActionExecutor.KEY_DETECTED_KEYCODE).apply();
            tvDetectedKeycode.setText("Press your Plus Key…");
            tvDetectedAction.setText("");
            refreshServiceStatus(true);
        });

        // Status card "Re-run Setup" button
        btnEnableService.setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class)));

        cardStatus.setOnClickListener(v -> {
            if (!isPermissionGranted()) {
                startActivity(new Intent(this, SetupActivity.class));
                return;
            }
            if (serviceRunning) {
                stopDetectorService();
            } else {
                startDetectorService();
            }
        });

        findViewById(R.id.cardSingle).setOnClickListener(v -> showActionPicker(
                ActionExecutor.KEY_ACTION_SINGLE,
                ActionExecutor.KEY_LAUNCH_PKG_SINGLE,
                ActionExecutor.KEY_CUSTOM_INTENT_SINGLE, tvSingleLabel));

        // cardLong click listener is set in applySingleOnlyMode() so it
        // respects the enabled/disabled state without setOnClickListener
        // overriding clickable=false.
    }

    // ── Camera shutter card ──────────────────────────────────────────────────

    /**
     * Initialises the camera shutter toggle card.
     *
     * The card stores its enabled state in pkm_settings under the key
     * KEY_CAMERA_SHUTTER_ENABLED. When toggled on, single-tap presses are
     * intercepted by ActionExecutor.executeForSingleTap() and redirected to
     * a node-based accessibility click on the shutter button whenever a camera
     * app is in the foreground.
     *
     * Enabling the feature while the accessibility service is not active shows
     * a mandatory dialog directing the user to Settings → Accessibility. The
     * feature is saved as enabled so it activates automatically once the service
     * is turned on — no need to toggle again after granting access.
     */
    private void bindCameraShutterCard() {
        SharedPreferences settings = getSharedPreferences(
                SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE);
        boolean enabled = settings.getBoolean(
                ActionExecutor.KEY_CAMERA_SHUTTER_ENABLED, false);
        switchCameraShutter.setChecked(enabled);

        // Tapping anywhere on the card toggles the switch
        cardCameraShutter.setOnClickListener(v -> {
            boolean nowEnabled = !switchCameraShutter.isChecked();
            handleCameraShutterToggle(nowEnabled);
        });

        // Let the switch itself toggle without double-firing via the card click
        switchCameraShutter.setOnClickListener(v -> {
            boolean nowEnabled = switchCameraShutter.isChecked();
            handleCameraShutterToggle(nowEnabled);
        });
    }

    /**
     * Applies a camera shutter toggle change.
     *
     * If the user is enabling the feature and the accessibility service is not
     * currently connected, show a dialog explaining why it is required and offer
     * a direct link to Settings → Accessibility. The preference is still saved
     * as enabled so it works immediately once the user grants access — they
     * will not need to come back and toggle again.
     *
     * If the user is disabling the feature, apply it silently.
     */
    private void handleCameraShutterToggle(boolean nowEnabled) {
        switchCameraShutter.setChecked(nowEnabled);
        getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .edit()
                .putBoolean(ActionExecutor.KEY_CAMERA_SHUTTER_ENABLED, nowEnabled)
                .apply();

        if (nowEnabled && !isAccessibilityServiceEnabled()) {
            // Show a blocking dialog — without the a11y service the feature does nothing.
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Accessibility Access Required")
                    .setMessage(
                            "The camera shutter feature works by clicking the shutter button "
                            + "in the camera app using Android's Accessibility service.\n\n"
                            + "Please enable \"Plus Key Remapper\" in:\n"
                            + "Settings → Accessibility → Installed apps\n\n"
                            + "The feature is already saved as ON — it will activate "
                            + "automatically once you grant access.")
                    .setPositiveButton("Open Accessibility Settings", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    })
                    .setNegativeButton("Later", null)
                    .setCancelable(true)
                    .show();
            return;
        }

        Snackbar.make(
                findViewById(android.R.id.content),
                nowEnabled
                        ? "Camera shutter enabled — works inside camera apps only"
                        : "Camera shutter disabled",
                Snackbar.LENGTH_SHORT).show();
    }

    /**
     * Returns true if PlusKeyService (the AccessibilityService) is currently
     * enabled in system accessibility settings.
     *
     * Uses Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES to check whether the
     * service component is listed — this reflects the Settings toggle state
     * regardless of whether onServiceConnected has fired yet.
     */
    private boolean isAccessibilityServiceEnabled() {
        String expected = getPackageName() + "/" + PlusKeyService.class.getName();
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            // The setting is a colon-separated list of component names.
            for (String component : enabled.split(":")) {
                if (component.trim().equalsIgnoreCase(expected)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ── Single-only mode ─────────────────────────────────────────────────────

    private void applySingleOnlyMode() {
        boolean singleOnly = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SINGLE_ONLY_MODE, true);
        View cardLong = findViewById(R.id.cardLong);
        if (cardLong == null) return;
        float alpha = singleOnly ? 0.35f : 1.0f;
        cardLong.setAlpha(alpha);
        setViewTreeEnabled(cardLong, !singleOnly);
        if (singleOnly) {
            cardLong.setClickable(false);
            cardLong.setOnClickListener(null);
        } else {
            cardLong.setOnClickListener(v -> showActionPicker(
                    ActionExecutor.KEY_ACTION_LONG,
                    ActionExecutor.KEY_LAUNCH_PKG_LONG,
                    ActionExecutor.KEY_CUSTOM_INTENT_LONG, tvLongLabel));
        }
    }

    // ── Skipped-setup state ─────────────────────────────────────────────────

    /**
     * Shows the setup banner only when ADB permission is missing or setup was
     * explicitly skipped. All other states (OEM dialog, verifying, denied) are
     * handled inline by the status card — no need to block the whole UI.
     */
    private void applySkippedState(boolean oemDenied) {
        boolean skipped     = wasSetupSkipped();
        boolean permGranted = isPermissionGranted();
        boolean oemEverConfirmed = DetectorService.isLogcatEverConfirmed(this);

        // If ADB permission is now granted and OEM dialog confirmed, clear skipped flag.
        if (permGranted && oemEverConfirmed && skipped) {
            getSharedPreferences(SetupActivity.PREFS_SETUP, MODE_PRIVATE)
                    .edit().putBoolean(SetupActivity.KEY_SKIPPED, false).apply();
            skipped = false;
        }

        // Only block the UI with the banner if ADB permission is missing.
        boolean showBanner = skipped || !permGranted;
        cardSetupBanner.setVisibility(showBanner ? View.VISIBLE : View.GONE);
        cardStatus.setVisibility(showBanner ? View.GONE : View.VISIBLE);

        if (showBanner && tvBannerTitle != null && tvBannerBody != null) {
            MaterialButton btnReopen = findViewById(R.id.btnReopenSetup);
            tvBannerTitle.setText("Setup not completed");
            tvBannerBody.setText("ADB permission has not been granted. Key detection and action bindings are disabled. Complete setup to enable them.");
            if (btnReopen != null) btnReopen.setText("Complete Setup");
        }

        int[] grayIds = { R.id.cardDetector, R.id.cardBindings, R.id.cardCameraShutter };
        float alpha  = showBanner ? 0.3f : 1.0f;
        boolean enable = !showBanner;
        for (int id : grayIds) {
            View v = findViewById(id);
            if (v == null) continue;
            v.animate().alpha(alpha).setDuration(220).start();
            setViewTreeEnabled(v, enable);
        }
    }

    /**
     * Recursively enable/disable all views in a hierarchy.
     *
     * When DISABLING we also clear the clickable flag so disabled children
     * cannot swallow touch events.  When RE-ENABLING we only restore
     * setEnabled — we deliberately do NOT call setClickable(true) on every
     * descendant, because views that were never meant to be clickable (e.g.
     * TextViews and icons inside a card) would start intercepting touches and
     * prevent the parent MaterialCardView's OnClickListener from firing.
     */
    private void setViewTreeEnabled(View root, boolean enabled) {
        root.setEnabled(enabled);
        // When disabling: clear clickable so disabled children don't swallow touches.
        // When enabling: restore clickable on views that have a click listener
        // (identified by having a tag set before we disabled them).
        if (!enabled) {
            if (root.isClickable()) root.setTag(R.id.tag_was_clickable, Boolean.TRUE);
            root.setClickable(false);
        } else {
            if (Boolean.TRUE.equals(root.getTag(R.id.tag_was_clickable))) {
                root.setClickable(true);
                root.setTag(R.id.tag_was_clickable, null);
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setViewTreeEnabled(vg.getChildAt(i), enabled);
            }
        }
    }

    private boolean wasSetupSkipped() {
        return getSharedPreferences(SetupActivity.PREFS_SETUP, MODE_PRIVATE)
                .getBoolean(SetupActivity.KEY_SKIPPED, false);
    }

    // ── Entrance animation ──────────────────────────────────────────────────

    private void runEntranceAnimation() {
        int[] ids = { R.id.cardSetupBanner, R.id.cardStatus,
                R.id.cardDetector, R.id.cardBindings, R.id.cardCameraShutter };
        for (int i = 0; i < ids.length; i++) {
            View v = findViewById(ids[i]);
            if (v == null || v.getVisibility() == View.GONE) continue;
            v.setAlpha(0f);
            v.setTranslationY(80f);
            v.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(80L + i * 65L)
                    .setDuration(360)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
        }
    }

    // ── Status card ─────────────────────────────────────────────────────────

    private void refreshServiceStatus(boolean animate) {
        int     savedCode = prefs.getInt(ActionExecutor.KEY_DETECTED_KEYCODE,
                ActionExecutor.KEYCODE_UNSET);
        boolean keySet  = savedCode != ActionExecutor.KEYCODE_UNSET;
        boolean logPerm = isPermissionGranted();

        int targetColor;

        if (!logPerm) {
            // No ADB permission — setup not done.
            tvStatusTitle.setText("Setup required");
            tvStatusSub.setText("Tap to open setup and grant the required permissions.");
            ivStatusIcon.setImageResource(R.drawable.ic_status_warning);
            targetColor = resolveColor(com.google.android.material.R.attr.colorErrorContainer);
        } else if (DetectorService.isLogcatConfirmed()) {
            // Fully working.
            tvStatusTitle.setText("Active. Listening for Plus Key.");
            tvStatusSub.setText("Tap to pause.");
            ivStatusIcon.setImageResource(R.drawable.ic_status_active);
            targetColor = resolveColor(com.google.android.material.R.attr.colorPrimaryContainer);
        } else if (serviceRunning) {
            // Service is up but hasn't seen an OEM key tag yet — could be starting,
            // waiting for the system dialog, or waiting for the user to press the key.
            tvStatusTitle.setText("Press the Plus Key once");
            tvStatusSub.setText("Press it once to finish setup.");
            ivStatusIcon.setImageResource(R.drawable.ic_status_warning);
            targetColor = resolveColor(com.google.android.material.R.attr.colorSecondaryContainer);
        } else if (!keySet) {
            tvStatusTitle.setText("Ready. Tap to start.");
            tvStatusSub.setText("Detect your Plus Key first, then tap to activate.");
            ivStatusIcon.setImageResource(R.drawable.ic_status_paused);
            targetColor = resolveColor(com.google.android.material.R.attr.colorSurfaceVariant);
        } else {
            tvStatusTitle.setText("Paused");
            tvStatusSub.setText("Tap to resume listening.");
            ivStatusIcon.setImageResource(R.drawable.ic_status_paused);
            targetColor = resolveColor(com.google.android.material.R.attr.colorSurfaceVariant);
        }

        btnEnableService.setVisibility(View.GONE);

        if (animate) {
            animateCardColor(cardStatus, targetColor);
        } else {
            cardStatus.setCardBackgroundColor(targetColor);
        }
    }

    private void animateCardColor(MaterialCardView card, int toColor) {
        int fromColor = card.getCardBackgroundColor() != null
                ? card.getCardBackgroundColor().getDefaultColor()
                : toColor;
        ValueAnimator anim = ValueAnimator.ofArgb(fromColor, toColor);
        anim.setDuration(400);
        anim.addUpdateListener(a -> card.setCardBackgroundColor((int) a.getAnimatedValue()));
        anim.start();
    }

    private int resolveColor(int attrRes) {
        int[] attrs = { attrRes };
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        // Fall back to colorSurface so cards never turn invisible on either theme.
        int fallback = resolveAttrColor(com.google.android.material.R.attr.colorSurface);
        int color = ta.getColor(0, fallback);
        ta.recycle();
        return color;
    }

    private int resolveAttrColor(int attrRes) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.data;
    }

    // ── Detect mode ─────────────────────────────────────────────────────────

    private boolean serviceWasRunningBeforeDetect = false;

    private void toggleDetectMode() {
        detectMode = !detectMode;
        if (detectMode) {
            serviceWasRunningBeforeDetect = serviceRunning;
            btnDetect.setText("Stop Detection");
            tvDetectedKeycode.setText("Press your Plus Key now…");
            tvDetectedAction.setText("Listening via logcat");
            DetectorService.setDetectMode(true);
            launchDetectorService();
        } else {
            btnDetect.setText("Start Detection");
            DetectorService.setDetectMode(false);
            // If the service wasn't running before detect mode, stop it again
            if (!serviceWasRunningBeforeDetect) {
                stopDetectorService();
            }
        }
    }

    private void startDetectorService() {
        if (!isPermissionGranted()) {
            refreshServiceStatus(true);
            return;
        }
        // Clear any previously persisted denial so the service gets a fresh attempt.
        // The new session will either confirm (logcat output received) or deny again.
        getSharedPreferences(DetectorService.PREFS_LOGCAT, MODE_PRIVATE)
                .edit()
                .putBoolean(DetectorService.KEY_LOGCAT_DENIED, false)
                .apply();
        oemLogcatDenied = false;
        serviceRunning = true;
        detectMode = false;
        getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .edit().putBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, true).apply();
        launchDetectorService();
        refreshServiceStatus(true);
    }

    private void launchDetectorService() {
        Intent i = new Intent(this, DetectorService.class)
                .setAction(DetectorService.ACTION_START)
                .putExtra(DetectorService.EXTRA_DETECT_MODE, detectMode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void stopDetectorService() {
        serviceRunning = false;
        // Persist so BootReceiver does NOT auto-start after reboot
        getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .edit().putBoolean(SettingsActivity.KEY_SERVICE_WAS_RUNNING, false).apply();
        Intent i = new Intent(this, DetectorService.class)
                .setAction(DetectorService.ACTION_STOP);
        startService(i);
        refreshServiceStatus(true);
    }

    // ── Permission ───────────────────────────────────────────────────────────

    private boolean isPermissionGranted() {
        boolean logGranted = checkSelfPermission("android.permission.READ_LOGS")
                == PackageManager.PERMISSION_GRANTED;
        boolean overlayGranted = (android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.M)
                ? android.provider.Settings.canDrawOverlays(this)
                : true;
        return logGranted && overlayGranted;
    }

    // ── Binding labels ───────────────────────────────────────────────────────

    private void refreshBindingLabels() {
        tvSingleLabel.setText(getActionLabel(ActionExecutor.KEY_ACTION_SINGLE));
        tvLongLabel.setText(getActionLabel(ActionExecutor.KEY_ACTION_LONG));
    }

    private String getActionLabel(String prefKey) {
        int action = prefs.getInt(prefKey, ActionConfig.ACTION_NONE);
        if (action == ActionConfig.ACTION_CUSTOM_INTENT) {
            // Derive a friendly label from the stored intent string
            String intentKey = ActionExecutor.KEY_ACTION_SINGLE.equals(prefKey)
                    ? ActionExecutor.KEY_CUSTOM_INTENT_SINGLE
                    : ActionExecutor.KEY_CUSTOM_INTENT_LONG;
            String stored = prefs.getString(intentKey, "");
            if (!stored.isEmpty()) {
                String[] parts = stored.split("\\|", -1);
                String pkg = parts.length > 1 ? parts[1].trim() : "";
                String act = parts.length > 0 ? parts[0].trim() : "";
                if (!pkg.isEmpty()) {
                    // Try to resolve package name to app label
                    try {
                        PackageManager pm = getPackageManager();
                        return pm.getApplicationLabel(
                                pm.getApplicationInfo(pkg, 0)).toString();
                    } catch (PackageManager.NameNotFoundException ignored) {
                        return pkg;
                    }
                }
                if (!act.isEmpty()) return act;
            }
            return "Custom Intent";
        }
        if (action < 0 || action >= ActionConfig.ACTION_LABELS.length)
            return ActionConfig.ACTION_LABELS[0];
        String label = ActionConfig.ACTION_LABELS[action];
        return label != null ? label : ActionConfig.ACTION_LABELS[0];
    }

    // ── Pickers ─────────────────────────────────────────────────────────────

    private void showActionPicker(String actionKey, String pkgKey,
                                  String intentKey, TextView label) {
        // Build the standard system-action choices first
        List<String>  displayLabels = new ArrayList<>();
        List<Integer> actionIndices = new ArrayList<>();
        for (int i = 0; i < ActionConfig.ACTION_LABELS.length; i++) {
            // Skip Custom Intent — handled by the new tabbed picker below
            if (ActionConfig.ACTION_LABELS[i] != null
                    && i != ActionConfig.ACTION_CUSTOM_INTENT) {
                displayLabels.add(ActionConfig.ACTION_LABELS[i]);
                actionIndices.add(i);
            }
        }
        // Add "Open App / Custom Intent…" as the last entry, opening the full picker
        displayLabels.add("Open App / Custom Intent…");
        actionIndices.add(ActionConfig.ACTION_CUSTOM_INTENT);

        String[] items = displayLabels.toArray(new String[0]);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Choose action")
                .setItems(items, (dialog, which) -> {
                    int realAction = actionIndices.get(which);
                    if (realAction == ActionConfig.ACTION_CUSTOM_INTENT) {
                        // Open the new tabbed bottom-sheet picker
                        openAppIntentPicker(actionKey, intentKey, label);
                        return;
                    }
                    prefs.edit().putInt(actionKey, realAction).apply();
                    animateLabel(label, items[which]);
                    if (realAction == ActionConfig.ACTION_RINGER_TOGGLE) {
                        requestNotificationPolicyIfNeeded();
                    }
                })
                .show();
    }

    private void openAppIntentPicker(String actionKey, String intentKey, TextView label) {
        ActionPickerDialog picker = new ActionPickerDialog(this,
                (actionType, stored, displayLabel) -> {
                    prefs.edit()
                            .putInt(actionKey, ActionConfig.ACTION_CUSTOM_INTENT)
                            .putString(intentKey, stored)
                            .apply();
                    animateLabel(label, displayLabel);
                });
        // Pre-fill custom tab with whatever was saved
        String existing = prefs.getString(intentKey, "");
        picker.show();
        picker.prefillCustom(existing);
    }

    private void animateLabel(TextView label, String text) {
        label.setText(text);
        label.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120)
                .withEndAction(() ->
                        label.animate().scaleX(1f).scaleY(1f).setDuration(120)
                                .setInterpolator(new OvershootInterpolator()).start())
                .start();
    }

    private void requestNotificationPolicyIfNeeded() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Permission Required")
                    .setMessage("Changing the ringer mode requires Do Not Disturb access. Tap OK to open settings and grant it.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }
}