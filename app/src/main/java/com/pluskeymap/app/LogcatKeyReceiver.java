package com.pluskeymap.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class LogcatKeyReceiver extends BroadcastReceiver {

    private static final String TAG = "PKM_Receiver";

    public DetectorService service;

    @Override
    public void onReceive(Context context, Intent intent) {
        int keyCode   = intent.getIntExtra(PlusKeyService.EXTRA_KEYCODE, -1);
        String action = intent.getStringExtra(PlusKeyService.EXTRA_ACTION);
        String source = intent.getStringExtra("source");

        Log.d(TAG, "onReceive keyCode=" + keyCode + " action=" + action
                + " source=" + source + " service=" + (service != null ? "set" : "NULL"));

        if (keyCode != LogcatWatcher.PLUS_KEY_CODE) return;
        if (!"logcat".equals(source)) return;
        if (action == null) return;
        if (service == null) {
            Log.e(TAG, "service is null — cannot dispatch!");
            return;
        }

        service.handleLogcatKey(action);
    }
}
