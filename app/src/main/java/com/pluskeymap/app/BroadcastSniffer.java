package com.pluskeymap.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Sniffs for any broadcast OxygenOS might send when the Plus Key is pressed.
 * Registered dynamically with wildcard-style actions.
 */
public class BroadcastSniffer extends BroadcastReceiver {

    private static final String TAG = "PKM_Sniffer";

    // Known OxygenOS / Oplus broadcast actions to try
    public static final String[] ACTIONS = {
        "com.oplus.action.PLUS_KEY",
        "com.oplus.intent.action.PLUS_KEY",
        "com.oplus.action.HARDWARE_KEY",
        "com.oneplus.action.PLUS_KEY",
        "com.oneplus.intent.action.KEY_EVENT",
        "android.intent.action.OPLUS_KEY",
        "com.oplus.action.KEY_DOWN",
        "com.oplus.action.CUSTOM_KEY",
        "com.oplus.systemui.action.PLUS_KEY",
        "android.hardware.action.PLUS_KEY",
        "com.oplus.action.ALERT_SLIDER_CHANGE",
        "com.oplus.action.SIDE_KEY",
        "com.oneplus.action.SIDE_KEY",
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Broadcast received: " + action);

        Intent broadcast = new Intent(PlusKeyService.ACTION_KEY_DETECTED);
        broadcast.putExtra(PlusKeyService.EXTRA_KEYCODE, -4);
        broadcast.putExtra(PlusKeyService.EXTRA_ACTION, "broadcast");
        broadcast.putExtra("source", "sniffer");
        broadcast.putExtra("raw_line", "Broadcast: " + action);
        context.sendBroadcast(broadcast);
    }
}
