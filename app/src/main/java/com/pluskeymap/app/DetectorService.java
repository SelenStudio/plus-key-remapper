package com.pluskeymap.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;


public class DetectorService extends Service {

    private static final String TAG               = "PKM_Detector";
    // Single notification ID for the FGS — OxygenOS groups same-package
    // notifications and crashes its renderer when two coexist briefly during
    // a startForeground() ID swap. We always post to NOTIF_ID and swap content.
    private static final String CHANNEL_ID        = "pkm_detector";
    private static final int    NOTIF_ID          = 2001;

    private static void logd(String msg) { Log.d(TAG, msg); }

    public static final String ACTION_START = "com.pluskeymap.app.DETECTOR_START";
    public static final String ACTION_STOP  = "com.pluskeymap.app.DETECTOR_STOP";

    public static final String ACTION_KEY_DETECTED      = "com.pluskeymap.KEY_DETECTED";
    public static final String ACTION_LOGCAT_FAILED     = "com.pluskeymap.app.LOGCAT_FAILED";
    public static final String ACTION_LOGCAT_CONFIRMED  = "com.pluskeymap.app.LOGCAT_CONFIRMED";
    public static final String ACTION_LOGCAT_VERIFYING  = "com.pluskeymap.app.LOGCAT_VERIFYING";
    public static final String ACTION_LOGCAT_OEM_DENIED = "com.pluskeymap.app.LOGCAT_OEM_DENIED";
    public static final String ACTION_TOGGLE_SERVICE         = "com.pluskeymap.app.TOGGLE_SERVICE";
    public static final String ACTION_UPDATE_PERSISTENT_NOTIF = "com.pluskeymap.app.UPDATE_PERSISTENT_NOTIF";
    public static final String EXTRA_KEYCODE     = "keycode";
    public static final String EXTRA_ACTION      = "action";
    public static final String EXTRA_DETECT_MODE = "detect_mode";

    // ── State ───────────────────────────────────────────────────────────────
    private LogcatWatcher logcatWatcher;
    private Thread        logcatThread;
    private PowerManager.WakeLock wakeLock;
    private ActionExecutor executor;
    private SharedPreferences prefs;

    private BroadcastReceiver screenOffReceiver;

    private static final int  RESTART_ALARM_RC = 9902;
    private static final long RESTART_DELAY_MS = 3_000L;

    private static DetectorService instance;
    private boolean isStopping = false;
    static  boolean       detectMode      = false;
    private static volatile boolean logcatConfirmed = false;

    static final String PREFS_LOGCAT              = "pkm_logcat_state";
    static final String KEY_LOGCAT_DENIED         = "logcat_oem_denied";
    // Persisted across restarts — true once the OEM dialog has been accepted at
    // least once. Unlike logcatConfirmed (in-memory only), this survives process
    // death so the UI can distinguish "never confirmed" from "confirmed but killed".
    static final String KEY_LOGCAT_EVER_CONFIRMED = "logcat_ever_confirmed";
    // In-memory only — true once generic logcat access is established but OEM key
    // tag confirmation is still pending (user hasn't pressed the Plus Key yet).
    private static volatile boolean logcatVerifying = false;

    private long lastUpTime    = 0;
    private long lastActionTime = 0;
    private static final long ACTION_DEBOUNCE_MS    = 700;
    // Must be strictly greater than the worst-case synthetic UP delivery time for a tap.
    //
    // Architecture (LogcatWatcher + DetectorService co-design):
    //
    // OEM hardware (OnePlus/OPPO) emits ~1 logcat repeat line per ~180 ms while the
    // key is held.  LogcatWatcher reschedules the synthetic UP on every such line —
    // but ONLY while elapsed < LONG_PRESS_MS_CAP (= this value, 850 ms).  Once the
    // cap is exceeded, rescheduling stops and the UP fires ~600 ms later.
    //
    // For a TAP: the user physically releases quickly; the OEM typically emits a last
    // repeat line at ~184 ms.  Since 184 ms < 850 ms cap, the UP IS rescheduled to
    // 184 + 600 = 784 ms.  LONG_PRESS_MS = 850 ms > 784 ms, so the UP always arrives
    // first → correct single-tap dispatch.
    //
    // For a LONG PRESS: lines keep arriving every ~180 ms past the 850 ms cap.  The
    // last rescheduled UP fires ~600 ms after the cap kicks in (~1450 ms total) — well
    // after longPressRunnable fires at 850 ms → correct long-press dispatch.
    //
    // INVARIANT: LONG_PRESS_MS must equal LogcatWatcher.LONG_PRESS_MS_CAP.  Keep in sync.
    private static final long LONG_PRESS_MS         = 850;
    private static final long SINGLE_CONFIRM_MS     = 150;
    private static final long SINGLE_CONFIRM_FAST_MS = 80;
    private static final long SINGLE_COMMIT_MS      = 700;
    private static final long POST_SINGLE_GUARD_MS  = 900;
    /**
     * How long to wait after a "noise UP" (UP before SINGLE_CONFIRM_MS) before
     * concluding that the user genuinely released the key with a fast tap.
     * If a new DOWN arrives within this window the UP was hardware bounce and we
     * keep the long-press cycle alive; if no DOWN arrives we fire the single action.
     */
    private static final long NOISE_UP_CONFIRM_MS   = 200;
    private long    lastDownTime   = 0;
    private boolean longPressArmed = false;
    private boolean longPressFired  = false;
    private boolean singleFired    = false;
    private boolean singlePending  = false;

    private Runnable singleRunnable;
    private Runnable singleCommitRunnable;
    private Runnable longPressRunnable;
    private Runnable resetRunnable;
    /** Posted after a fast UP (< SINGLE_CONFIRM_MS) to confirm genuine fast tap. */
    private Runnable noiseUpRunnable;
    private Handler  handler;

    // ── Service lifecycle ───────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            KeepaliveJobService.cancel(this);
            HeartbeatReceiver.cancel(this);
            stopEverything();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_SERVICE.equals(intent != null ? intent.getAction() : null)) {
            if (isRunning()) {
                HeartbeatReceiver.cancel(this);
                stopEverything();
                stopForeground(true);
                stopSelf();
                updatePersistentNotification();
                return START_NOT_STICKY;
            }
        }

        if (ACTION_UPDATE_PERSISTENT_NOTIF.equals(intent != null ? intent.getAction() : null)) {
            updatePersistentNotification();
            return START_STICKY;
        }

        if (isStopping) return START_NOT_STICKY;

        instance = this;
        logcatConfirmed  = false;
        logcatVerifying  = false;
        getSharedPreferences(PREFS_LOGCAT, MODE_PRIVATE)
                .edit().putBoolean(KEY_LOGCAT_DENIED, false).apply();
        executor = new ActionExecutor(this);

        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());

            // singleRunnable: fires after SINGLE_CONFIRM_MS following a DOWN.
            // In single-only mode → immediately execute single action.
            // In dual mode → just mark that we have a confirmed press; the UP event
            // will decide whether it was a tap (short) or long press (long held).
            singleRunnable = () -> {
                boolean singleOnly = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                        .getBoolean(SettingsActivity.KEY_SINGLE_ONLY_MODE, true);
                if (singleOnly) {
                    logd("singleRunnable fired → single-only mode, instant commit");
                    singleFired    = true;
                    singlePending  = false;
                    lastActionTime = System.currentTimeMillis();
                    dispatchAction(ActionExecutor.KEY_ACTION_SINGLE,
                            ActionExecutor.KEY_LAUNCH_PKG_SINGLE,
                            ActionExecutor.KEY_CUSTOM_INTENT_SINGLE);
                    handler.postDelayed(resetRunnable, 400);
                } else {
                    // Dual mode: the press is confirmed real. Wait for UP to decide action type.
                    logd("singleRunnable fired → dual mode, press confirmed, waiting for UP");
                    singleFired   = true;
                    singlePending = false; // UP handler will dispatch, not resetRunnable
                }
            };

            singleCommitRunnable = () -> { /* kept for API compatibility */ };

            // longPressRunnable: fires after LONG_PRESS_MS following a DOWN in dual mode.
            // Cancels any pending single-tap and immediately fires the long-press action.
            // NOTE: longPressArmed intentionally stays TRUE here so that any repeated
            // logcat DOWN lines still arriving while the key is physically held do NOT
            // restart a new press cycle (which would re-arm singleRunnable and cause a
            // spurious single-tap dispatch when the key is finally released).
            longPressRunnable = () -> {
                logd("longPressRunnable fired → action_long");
                handler.removeCallbacks(singleRunnable);
                handler.removeCallbacks(resetRunnable);
                singlePending   = false;
                singleFired     = false;
                // Keep longPressArmed = true — cleared by UP handler once key is released.
                longPressFired  = true;
                lastActionTime  = System.currentTimeMillis();
                dispatchAction(ActionExecutor.KEY_ACTION_LONG,
                        ActionExecutor.KEY_LAUNCH_PKG_LONG,
                        ActionExecutor.KEY_CUSTOM_INTENT_LONG);
            };

            // resetRunnable: cleans up state after an action cycle completes.
            resetRunnable = () -> {
                logd("resetRunnable: cycle complete, resetting state");
                singleFired    = false;
                singlePending  = false;
                longPressArmed = false;
                longPressFired = false;
                // In single-only mode the OS delivers a real UP event long after the
                // action already fired (the watcher waits 50 ms of silence, but the
                // hardware can hold the key for 700+ ms).  That late UP sets lastUpTime,
                // and subsequent DOWNs within 150 ms are then blocked as noise even
                // though they are genuine new presses.  Clearing lastUpTime here means
                // that once the cycle is fully complete the next DOWN is always accepted
                // immediately, regardless of when the OS-delayed UP eventually arrives.
                lastUpTime = 0;
            };

            // noiseUpRunnable: posted after a fast UP (< SINGLE_CONFIRM_MS) in dual mode.
            // If no new DOWN has arrived by the time this fires, the user genuinely tapped
            // fast and we should dispatch the single action.  If a new DOWN arrives first
            // (hardware bounce), the DOWN handler cancels this runnable so we stay armed
            // for a long press instead.
            noiseUpRunnable = () -> {
                logd("noiseUpRunnable fired — no bounce DOWN, genuine fast tap → dispatch single");
                handler.removeCallbacks(longPressRunnable);
                longPressArmed = false;
                singleFired    = false;
                lastActionTime = System.currentTimeMillis();
                dispatchAction(ActionExecutor.KEY_ACTION_SINGLE,
                        ActionExecutor.KEY_LAUNCH_PKG_SINGLE,
                        ActionExecutor.KEY_CUSTOM_INTENT_SINGLE);
                handler.postDelayed(resetRunnable, 400);
            };
        }
        prefs = ActionExecutor.prefs(this);

        if (intent != null && intent.hasExtra(EXTRA_DETECT_MODE)) {
            detectMode = intent.getBooleanExtra(EXTRA_DETECT_MODE, false);
        }

        logd("Service started. single=" + prefs.getInt(ActionExecutor.KEY_ACTION_SINGLE, -1)
                + " detectMode=" + detectMode);

        acquireWakeLock();
        registerScreenOffReceiver();
        startLogcat();
        // Ensure OS-persisted job is alive (re-registers after SIGKILL wipe)
        KeepaliveJobService.schedule(this);
        // Belt-and-suspenders: 3-minute repeating alarm that lives in AlarmManagerService
        // and survives our process being SIGKILLed before onDestroy() runs.
        HeartbeatReceiver.schedule(this);

        boolean persistentEnabled = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_PERSISTENT_NOTIF, false);
        // Always use NOTIF_ID — never swap IDs mid-lifecycle (OxygenOS groups
        // same-package notifs and breaks when two IDs briefly coexist).
        startForeground(NOTIF_ID,
                persistentEnabled ? buildPersistentNotification() : buildMinimalNotification());
        return START_STICKY;
    }

    // ── Logcat callbacks ────────────────────────────────────────────────────

    public void onLogcatFailed() {
        Log.w(TAG, "onLogcatFailed: stopping service");
        getSharedPreferences(PREFS_LOGCAT, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOGCAT_DENIED, true)
                .putBoolean(KEY_LOGCAT_EVER_CONFIRMED, false)
                .apply();
        isStopping = true;
        Intent broadcast = new Intent(ACTION_LOGCAT_FAILED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
        stopEverything();
        stopForeground(true);
        stopSelf();
    }

    public void onLogcatKilledByDoze() {
        if (isStopping) return;
        Log.w(TAG, "onLogcatKilledByDoze: restarting logcat silently (broad filter)");
        // Re-acquire wakelock — Doze may have released it
        acquireWakeLock();
        // FIX: restart with startBroad=true so we skip the narrow filter.
        // After a Doze kill, OEM key tags are still suppressed → narrow filter
        // produces nothing → 8s timeout → false onLogcatFailed() → service dies.
        handler.postDelayed(this::startLogcatBroad, 500);
    }

    public void onLogcatOemDenied() {
        Log.w(TAG, "onLogcatOemDenied: OEM dialog denied mid-session");
        getSharedPreferences(PREFS_LOGCAT, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOGCAT_DENIED, true)
                .putBoolean(KEY_LOGCAT_EVER_CONFIRMED, false)
                .apply();
        isStopping = true;
        Intent broadcast = new Intent(ACTION_LOGCAT_OEM_DENIED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
        stopEverything();
        stopForeground(true);
        stopSelf();
    }

    public void handleLogcatKey(String action) {
        if (handler == null) return;
        handler.post(() -> processKeyEvent(action));
    }

    // ── Core gesture state machine ──────────────────────────────────────────
    //
    // Single-only mode:
    //   DOWN → arm singleRunnable (80 ms noise guard) → fires → dispatch single
    //
    // Dual mode (single + long press):
    //   DOWN → arm singleRunnable (150 ms) AND longPressRunnable (850 ms)
    //
    //   CRITICAL INVARIANT: LONG_PRESS_MS (850 ms) must equal LogcatWatcher.LONG_PRESS_MS_CAP.
    //
    //   LogcatWatcher reschedules the synthetic UP on every OEM repeat line, but only
    //   while elapsed < LONG_PRESS_MS_CAP (850 ms).  After the cap, rescheduling stops
    //   so the UP fires naturally ~600 ms later.
    //
    //   TAP path:  last OEM repeat line at ~184 ms → UP rescheduled → arrives at ~784 ms.
    //              LONG_PRESS_MS=850 ms > 784 ms → UP beats longPressRunnable → single ✓
    //
    //   LONG PRESS: OEM lines past 850 ms are no longer rescheduling UP. longPressRunnable
    //              fires at 850 ms first → long press ✓  UP arrives ~1450 ms (ignored).
    //
    //   If UP arrives before singleRunnable fires (fast tap < 150 ms)
    //       → post noiseUpRunnable(200 ms): if no bounce DOWN → dispatch single; else stay armed

    private void processKeyEvent(String action) {
        logd("processKeyEvent: " + action);

        if ("down".equals(action)) {
            long now           = System.currentTimeMillis();
            long upToDownGap   = now - lastUpTime;
            long downToDownGap = now - lastDownTime;

            // Ignore rapid DOWN events immediately after an UP (hardware bounce / noise).
            // 100 ms is enough to reject genuine sub-frame bounce (~5 ms) while
            // allowing a fast re-press at 148 ms that was previously blocked by the
            // old 150 ms threshold.  Dual mode uses SINGLE_CONFIRM_MS (150 ms) as
            // its own noise guard via the singleRunnable timer, so this guard only
            // needs to block the very fast hardware bounce, not full confirm windows.
            if (lastUpTime > 0 && upToDownGap < 100) {
                logd("Noise DOWN ignored (upGap=" + upToDownGap + "ms)");
                return;
            }

            // Ignore a DOWN that arrives very soon after a previous DOWN when an action
            // was already fired (protects against the repeated-logcat-line stream from
            // the OEM key being treated as a new press cycle).
            if ((singleFired || longPressFired) && lastDownTime > 0 && downToDownGap < 600) {
                logd("Noise DOWN ignored (downGap=" + downToDownGap + "ms, action-fired guard)");
                return;
            }

            lastDownTime = now;

            // A DOWN while noiseUpRunnable is pending means hardware bounce after a fast
            // release.  Cancel the "genuine fast tap" timeout and keep the long-press
            // cycle armed — the longPressRunnable is still counting down.
            if (handler.hasCallbacks(noiseUpRunnable)) {
                logd("DOWN during noise-UP window — hardware bounce, cancelling noiseUpRunnable, staying armed");
                handler.removeCallbacks(noiseUpRunnable);
                // longPressArmed stays true; longPressRunnable is still running.
                return;
            }

            int saved = prefs.getInt(ActionExecutor.KEY_DETECTED_KEYCODE, ActionExecutor.KEYCODE_UNSET);
            if (saved == ActionExecutor.KEYCODE_UNSET) {
                prefs.edit().putInt(ActionExecutor.KEY_DETECTED_KEYCODE, LogcatWatcher.PLUS_KEY_CODE).apply();
            }
            Intent broadcast = new Intent(ACTION_KEY_DETECTED);
            broadcast.putExtra(EXTRA_KEYCODE, LogcatWatcher.PLUS_KEY_CODE);
            broadcast.putExtra(EXTRA_ACTION, "down");
            broadcast.putExtra("source", "logcat");
            broadcast.setPackage(getPackageName());
            sendBroadcast(broadcast);

            if (detectMode) { logd("Detect mode — action suppressed"); return; }

            // Only start a new press cycle when no cycle is already in progress.
            if (!longPressArmed) {
                boolean singleOnly = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                        .getBoolean(SettingsActivity.KEY_SINGLE_ONLY_MODE, true);

                // Reset all state for a clean cycle.
                singleFired    = false;
                singlePending  = false;
                longPressFired = false;
                longPressArmed = true;

                if (singleOnly) {
                    // Single-only: short noise guard then immediately fire.
                    handler.postDelayed(singleRunnable, SINGLE_CONFIRM_FAST_MS);
                    logd("DOWN (single-only) — confirm timer armed (" + SINGLE_CONFIRM_FAST_MS + "ms)");
                } else {
                    // Dual mode: arm both timers. longPressRunnable wins if the key is
                    // held past LONG_PRESS_MS; otherwise UP will dispatch single.
                    handler.postDelayed(singleRunnable,    SINGLE_CONFIRM_MS);
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    logd("DOWN (dual) — confirm=" + SINGLE_CONFIRM_MS + "ms"
                            + ", longPress=" + LONG_PRESS_MS + "ms"
                            + " (cap=" + LogcatWatcher.LONG_PRESS_MS_CAP + "ms) timers armed");
                }
            } else {
                // Already armed — this is a repeated logcat line while key is held.
                // Nothing to do; timers are already running.
                logd("DOWN repeat while armed — ignored");
            }

        } else if ("up".equals(action)) {
            long now = System.currentTimeMillis();
            lastUpTime = now;

            if (!longPressArmed) {
                // No active cycle (e.g. already fired and reset, or spurious UP).
                logd("UP — no active cycle, ignored");
                return;
            }

            long heldMs = now - lastDownTime;
            logd("UP — heldMs=" + heldMs + " singleFired=" + singleFired
                    + " longPressFired=" + longPressFired);

            boolean singleOnly = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                    .getBoolean(SettingsActivity.KEY_SINGLE_ONLY_MODE, true);

            if (singleOnly) {
                // Single-only mode: singleRunnable already fired or will fire shortly.
                // Just clean up the armed flag; singleRunnable handles dispatch.
                longPressArmed = false;
                return;
            }

            // ── Dual mode: UP determines which action to fire ──────────────────────
            //
            // IMPORTANT: we must check for noise BEFORE cancelling longPressRunnable.
            // OxygenOS hardware emits a logcat UP line ~50 ms after every DOWN line even
            // during a genuine long press (because the OS only logs key-state changes, not
            // continuous held events).  With RELEASE_PAUSE_DUAL_MS=600 ms the watcher
            // waits 600 ms before synthesising an UP, so this branch is only reached when
            // the user genuinely releases quickly (< SINGLE_CONFIRM_MS) — in which case
            // we keep the long-press timer alive in case the user is still holding the key
            // and the watcher just delivered an early UP due to a transient logging gap.

            if (longPressFired) {
                // longPressRunnable already fired before UP arrived — nothing more to do.
                logd("UP — long press already fired, resetting");
                handler.removeCallbacks(singleRunnable);
                handler.removeCallbacks(longPressRunnable);
                handler.removeCallbacks(resetRunnable);
                longPressArmed = false;
                handler.postDelayed(resetRunnable, 200);
                return;
            }

            if (!singleFired && heldMs < SINGLE_CONFIRM_MS) {
                // UP arrived before the confirm timer fired (fast tap < SINGLE_CONFIRM_MS).
                //
                // We cannot tell yet whether this is:
                //  (a) a genuine fast tap — the user released the key and hardware bounce
                //      produced a spurious second DOWN/UP pair shortly after, OR
                //  (b) an early UP from a logging gap mid-long-press — the user is still
                //      holding the key and the watcher briefly lost the stream.
                //
                // Strategy: cancel singleRunnable (it hasn't fired yet), keep
                // longPressRunnable alive (still counting toward LONG_PRESS_MS), and post
                // noiseUpRunnable after NOISE_UP_CONFIRM_MS.
                //
                // • If a bounce DOWN arrives within NOISE_UP_CONFIRM_MS → the DOWN handler
                //   cancels noiseUpRunnable and we stay armed for long press (case b / bounce).
                // • If no DOWN arrives → noiseUpRunnable fires, confirms genuine fast tap,
                //   cancels longPressRunnable and dispatches single (case a).
                logd("UP before confirm (" + heldMs + "ms) — posting noiseUpRunnable (" + NOISE_UP_CONFIRM_MS + "ms) to disambiguate tap vs bounce");
                singleFired    = false;
                longPressFired = false;
                handler.removeCallbacks(singleRunnable);
                handler.removeCallbacks(resetRunnable);
                handler.removeCallbacks(noiseUpRunnable); // clear any stale one
                handler.postDelayed(noiseUpRunnable, NOISE_UP_CONFIRM_MS);
                // DO NOT cancel longPressRunnable, DO NOT set longPressArmed = false.
                return;
            }

            // Real UP: cancel pending timers and decide the action.
            // NOTE: longPressRunnable is cancelled here but may be re-armed below if we
            // cannot yet confirm whether the user genuinely released before the long-press
            // deadline (see the singleFired + heldMs check below).
            handler.removeCallbacks(singleRunnable);
            handler.removeCallbacks(resetRunnable);

            if (singleFired) {
                // Press was confirmed past SINGLE_CONFIRM_MS.  Now determine whether the
                // UP is genuine (user released before LONG_PRESS_MS → single tap) or
                // spurious (watcher timeout fired during an ongoing hold → long press).
                //
                // The watcher synthesises UP after RELEASE_PAUSE_DUAL_MS (600 ms) of
                // logcat silence after the DOWN line.  If the OEM emits only one logcat
                // line (at DOWN time) the UP always arrives at downTime+600ms — before
                // LONG_PRESS_MS (850 ms).  We cannot distinguish this from a genuine
                // single tap release purely from heldMs.
                //
                // Strategy: if heldMs < LONG_PRESS_MS, defer dispatch via noiseUpRunnable
                // (same window used for sub-150ms fast taps).  If the user is still
                // holding, a new DOWN will arrive within NOISE_UP_CONFIRM_MS and cancel
                // the single dispatch, keeping longPressRunnable counting.  If no DOWN
                // arrives the user genuinely released early → dispatch single.
                if (heldMs < LONG_PRESS_MS) {
                    logd("UP after confirm (held " + heldMs + "ms) — deferring single via noiseUpRunnable"
                            + " (" + NOISE_UP_CONFIRM_MS + "ms) to rule out spurious watcher UP");
                    singleFired    = false;
                    longPressFired = false;
                    handler.removeCallbacks(noiseUpRunnable);
                    // Keep longPressRunnable alive — it is still the arbiter if the user
                    // is actually holding past LONG_PRESS_MS.  Cancel it only if
                    // noiseUpRunnable concludes it was a genuine tap.
                    handler.postDelayed(noiseUpRunnable, NOISE_UP_CONFIRM_MS);
                    // DO NOT cancel longPressRunnable, DO NOT clear longPressArmed.
                    return;
                }
                // heldMs >= LONG_PRESS_MS: the UP arrived after the long-press deadline.
                // longPressRunnable should have fired first; if we are here it lost the
                // race.  Cancel it and dispatch long press directly.
                handler.removeCallbacks(longPressRunnable);
                longPressArmed = false;
                logd("UP after confirm but heldMs=" + heldMs + " >= LONG_PRESS_MS=" + LONG_PRESS_MS
                        + " — dispatching long press (longPressRunnable lost race)");
                singleFired    = false;
                lastActionTime = now;
                longPressFired = true;
                dispatchAction(ActionExecutor.KEY_ACTION_LONG,
                        ActionExecutor.KEY_LAUNCH_PKG_LONG,
                        ActionExecutor.KEY_CUSTOM_INTENT_LONG);
                handler.postDelayed(resetRunnable, 400);
            } else {
                handler.removeCallbacks(longPressRunnable);
                longPressArmed = false;
                // singleFired=false but heldMs >= SINGLE_CONFIRM_MS — shouldn't normally
                // happen; treat conservatively as a short tap with no action.
                logd("UP — heldMs=" + heldMs + " no action state, ignoring");
                singleFired    = false;
                longPressFired = false;
            }
        }
    }

    // ── Action dispatch ─────────────────────────────────────────────────────

    private void dispatchAction(String prefKey, String pkgKey, String intentKey) {
        prefs = ActionExecutor.prefs(this);
        int action = prefs.getInt(prefKey, ActionConfig.ACTION_NONE);
        logd("dispatchAction prefKey=" + prefKey + " action=" + action);
        if (action == ActionConfig.ACTION_NONE) { logd("Action is NONE"); return; }
        String launchPkg    = prefs.getString(pkgKey, "");
        String customIntent = prefs.getString(intentKey, "");
        logd("Executing action=" + action + " pkg=" + launchPkg);

        // Single-tap passes through executeForSingleTap so the camera shutter
        // override can intercept it when a camera app is in the foreground.
        boolean isSingleTap = ActionExecutor.KEY_ACTION_SINGLE.equals(prefKey);
        if (isSingleTap) {
            executor.executeForSingleTap(action, launchPkg, customIntent);
        } else {
            executor.execute(action, launchPkg, customIntent);
        }

        // Re-signal foreground liveness to OxygenOS after every action.
        // OxygenOS uses the camera-resource release (e.g. torch off) as a
        // kill trigger -- refreshing the foreground notification resets its
        // idle timer and prevents the ~44s post-action kill.
        refreshForegroundNotification();
    }

    /**
     * Re-calls startForeground() to reset OxygenOS's post-action idle timer.
     *
     * OxygenOS watches for resource-release events (camera torch off, audio
     * focus release, etc.) and kills foreground services that appear "done"
     * ~30-60s after. Calling startForeground() again pokes the OS and resets
     * whatever internal idle counter it maintains for this service.
     */
    public void refreshForegroundNotification() {
        try {
            boolean persistent = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                    .getBoolean(SettingsActivity.KEY_PERSISTENT_NOTIF, false);
            startForeground(NOTIF_ID,
                    persistent ? buildPersistentNotification() : buildMinimalNotification());
        } catch (Exception e) {
            Log.w(TAG, "refreshForegroundNotification failed: " + e.getMessage());
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private void startLogcat() {
        startLogcatInternal(false);
    }

    /** Used after Doze kills — skips narrow filter to avoid false-deny timeout. */
    private void startLogcatBroad() {
        startLogcatInternal(true);
    }

    private void startLogcatInternal(boolean broad) {
        if (logcatWatcher != null) logcatWatcher.stop();
        if (logcatThread != null && logcatThread.isAlive()) {
            logcatThread.interrupt();
            try { logcatThread.join(300); } catch (InterruptedException ignored) {}
        }
        logcatWatcher = new LogcatWatcher(this, broad);
        // Propagate the current button-behaviour mode so the watcher uses the
        // correct release-pause duration from the very first key event.
        boolean dualMode = !getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_SINGLE_ONLY_MODE, true);
        logcatWatcher.setDualMode(dualMode);
        logcatThread  = new Thread(logcatWatcher, "pkm-logcat");
        logcatThread.setDaemon(true);
        logcatThread.start();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PlusKeyMapper::LogcatWakeLock");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    /**
     * Registers a receiver for ACTION_SCREEN_OFF to re-acquire the wakelock
     * and verify logcat process liveness (not just thread liveness).
     *
     * Also handles ACTION_USER_PRESENT (screen unlocked after being off):
     * immediately checks READ_LOGS permission so the re-auth notification
     * appears as soon as the user unlocks, rather than waiting for the next
     * JobService/heartbeat cycle (up to 3 minutes later).
     *
     * FIX: Uses logcatWatcher.isProcessAlive() instead of logcatThread.isAlive().
     * The thread can remain alive (blocked on readLine()) even after OxygenOS
     * kills the child logcat process — isAlive() check was missing real kills.
     */
    private void registerScreenOffReceiver() {
        if (screenOffReceiver != null) return;
        screenOffReceiver = new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context ctx, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    logd("Screen off — re-acquiring wakelock and verifying logcat process");
                    acquireWakeLock();
                    // FIX: check real process liveness, not thread liveness
                    boolean processAlive = logcatWatcher != null && logcatWatcher.isProcessAlive();
                    if (!processAlive) {
                        Log.w(TAG, "Logcat process dead at screen-off — restarting");
                        startLogcat();
                    }
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    // Screen just unlocked — immediately check if READ_LOGS was revoked
                    // while screen was off. This surfaces the re-auth notification the
                    // moment the user can act on it rather than waiting for the next
                    // heartbeat alarm (up to 3 minutes).
                    boolean hasLogPerm = checkSelfPermission("android.permission.READ_LOGS")
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    if (!hasLogPerm) {
                        Log.w(TAG, "USER_PRESENT: READ_LOGS gone — notifying user");
                        KeepaliveJobService.postPermissionLostNotificationStatic(ctx);
                    } else {
                        // Perm still good — verify logcat process is alive
                        boolean processAlive = logcatWatcher != null && logcatWatcher.isProcessAlive();
                        if (!processAlive) {
                            Log.w(TAG, "USER_PRESENT: logcat process dead — restarting");
                            startLogcat();
                        }
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenOffReceiver, f);
    }

    /**
     * Arms a one-shot alarm to restart DetectorService.
     * Used from onDestroy so the service revives itself on OxygenOS devices
     * that ignore START_STICKY when battery saver is active.
     *
     * Uses setAndAllowWhileIdle() — does not require SCHEDULE_EXACT_ALARM or
     * USE_EXACT_ALARM (both removed; Play Store restricts them to calendar/alarm
     * clock apps). The FGS specialUse + JobScheduler heartbeat are the primary
     * keepalive; this alarm is a last-resort fallback where a few minutes of
     * Doze deferral is acceptable.
     */
    private void scheduleRestartAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(this, DetectorService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DETECT_MODE, detectMode);
        PendingIntent pi = PendingIntent.getService(this, RESTART_ALARM_RC, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        logd("Restart alarm scheduled in " + RESTART_DELAY_MS + " ms (inexact)");
    }

    private void stopEverything() {
        if (handler != null) {
            handler.removeCallbacks(longPressRunnable);
            handler.removeCallbacks(singleRunnable);
            handler.removeCallbacks(singleCommitRunnable);
            handler.removeCallbacks(resetRunnable);
            handler.removeCallbacks(noiseUpRunnable);
        }
        longPressArmed = false;
        longPressFired = false;
        singleFired    = false;
        singlePending  = false;
        if (screenOffReceiver != null) {
            try { unregisterReceiver(screenOffReceiver); } catch (Exception ignored) {}
            screenOffReceiver = null;
        }
        if (logcatWatcher != null) logcatWatcher.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private Notification buildMinimalNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Plus Key Listener", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Required system notification. Can be hidden in notification settings.");
            ch.setSound(null, null);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Plus Key Remapper")
                .setContentText("Running in background")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build();
    }

    private Notification buildPersistentNotification() {
        // Reuse CHANNEL_ID — same channel as minimal notif so there is only ever
        // one notification ID (NOTIF_ID) on one channel. OxygenOS groups
        // same-package notifications; a second channel/ID causes renderer crash.
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Plus Key Listener", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Required system notification. Can be hidden in notification settings.");
            ch.setSound(null, null);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        boolean running = isRunning();

        Intent toggleIntent = new Intent(this, DetectorService.class).setAction(ACTION_TOGGLE_SERVICE);
        PendingIntent togglePi = PendingIntent.getService(this, 1, toggleIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String actionLabel = running ? "Pause" : "Activate";
        int    actionIcon  = running ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Plus Key Remapper")
                .setContentText(running ? "Active. Listening for Plus Key." : "Paused")
                .setSmallIcon(running ? android.R.drawable.ic_menu_compass : android.R.drawable.ic_media_pause)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(openPi)
                .addAction(actionIcon, actionLabel, togglePi)
                .build();

        notif.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        return notif;
    }

    void updatePersistentNotification() {
        boolean enabled = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_PERSISTENT_NOTIF, false);
        // Always update NOTIF_ID in-place — never cancel+repost under a different ID.
        // OxygenOS groups same-package notifications: two IDs briefly coexisting
        // causes NotificationChildrenContainer to crash (single line view is null)
        // and both notifications vanish. Updating content via startForeground()
        // on the same ID is atomic from the OS perspective — no flicker, no grouping.
        Notification notif = enabled ? buildPersistentNotification() : buildMinimalNotification();
        startForeground(NOTIF_ID, notif);
    }

    static boolean isRunning() { return instance != null; }
    static boolean isLogcatConfirmed() { return logcatConfirmed; }

    /**
     * True if the OEM system dialog was accepted in any previous session.
     * Works even when the service is not running — reads from SharedPreferences.
     * Use this for UI state — it persists across process deaths.
     */
    static boolean isLogcatEverConfirmed(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS_LOGCAT, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LOGCAT_EVER_CONFIRMED, false);
    }

    /**
     * True if the OEM dialog was explicitly denied or logcat timed out.
     * Works even when the service is not running — reads from SharedPreferences.
     * Returns false (not denied) when service is dead and no denial was recorded,
     * unlike the old instance-gated version which also returned false when dead.
     */
    static boolean isLogcatDenied(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS_LOGCAT, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LOGCAT_DENIED, false);
    }

    static boolean isLogcatVerifying() { return logcatVerifying; }

    /**
     * Called by LogcatWatcher when generic logcat output is received — confirms
     * the process started and ADB permission is fine, but does NOT mean the OEM
     * system dialog was accepted. UI should move to "verifying" state and prompt
     * the user to press the Plus Key once.
     */
    public void onLogcatVerifying() {
        logcatVerifying  = true;
        logcatConfirmed  = false;
        Intent broadcast = new Intent(ACTION_LOGCAT_VERIFYING);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    public void onLogcatConfirmed() {
        logcatVerifying = false;
        logcatConfirmed = true;
        // Persist so UI can show correct state even after process is killed and restarted.
        getSharedPreferences(PREFS_LOGCAT, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOGCAT_EVER_CONFIRMED, true)
                .putBoolean(KEY_LOGCAT_DENIED, false)
                .apply();
        // Dismiss the "permission revoked" notification if it was shown.
        KeepaliveJobService.dismissPermNotification(this);
        Intent broadcast = new Intent(ACTION_LOGCAT_CONFIRMED);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    static void setDetectMode(boolean active) { detectMode = active; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (!isStopping) {
            scheduleRestartAlarm();
        }
        instance = null;
        stopEverything();
        updatePersistentNotification();
        super.onDestroy();
    }
}
