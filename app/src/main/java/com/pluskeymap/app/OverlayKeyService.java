package com.pluskeymap.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;

/**
 * Invisible overlay window that holds key focus.
 * TYPE_APPLICATION_OVERLAY sits above all windows and can intercept hardware keys
 * through the window manager directly — different pipeline from accessibility.
 */
public class OverlayKeyService extends Service {

    private static final String TAG = "PKM_Overlay";
    public static final String ACTION_START = "com.pluskeymap.app.OVERLAY_START";
    public static final String ACTION_STOP  = "com.pluskeymap.app.OVERLAY_STOP";

    private WindowManager wm;
    private View overlayView;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }
        createOverlay();
        return START_STICKY;
    }

    private void createOverlay() {
        if (overlayView != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = new View(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                Log.d(TAG, "KEY via overlay: " + KeyEvent.keyCodeToString(code)
                        + " (" + code + ") action=" + event.getAction());
                broadcastKey(code, event.getAction());
                return true;
            }

            @Override
            public boolean onKeyDown(int keyCode, KeyEvent event) {
                Log.d(TAG, "onKeyDown via overlay: " + KeyEvent.keyCodeToString(keyCode));
                broadcastKey(keyCode, KeyEvent.ACTION_DOWN);
                return true;
            }
        };
        overlayView.setFocusable(true);
        overlayView.setFocusableInTouchMode(true);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.alpha = 0f;

        try {
            wm.addView(overlayView, params);
            overlayView.requestFocus();
            Log.d(TAG, "Overlay created and focused");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create overlay: " + e.getMessage());
        }
    }

    private void removeOverlay() {
        if (overlayView != null && wm != null) {
            try { wm.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
        }
    }

    private void broadcastKey(int keyCode, int action) {
        Intent i = new Intent(PlusKeyService.ACTION_KEY_DETECTED);
        i.putExtra(PlusKeyService.EXTRA_KEYCODE, keyCode);
        i.putExtra(PlusKeyService.EXTRA_ACTION, action == KeyEvent.ACTION_DOWN ? "down" : "up");
        i.putExtra("source", "overlay");
        sendBroadcast(i);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }
}
