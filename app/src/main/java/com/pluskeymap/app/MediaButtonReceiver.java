package com.pluskeymap.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Legacy media button receiver.
 * Some OEMs route custom hardware buttons through the media button channel.
 */
public class MediaButtonReceiver extends BroadcastReceiver {

    private static final String TAG = "PKM_MediaBtn";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null) return;

        int code = event.getKeyCode();
        Log.d(TAG, "Media button: " + KeyEvent.keyCodeToString(code) + " (" + code + ")");

        Intent broadcast = new Intent(PlusKeyService.ACTION_KEY_DETECTED);
        broadcast.putExtra(PlusKeyService.EXTRA_KEYCODE, code);
        broadcast.putExtra(PlusKeyService.EXTRA_ACTION,
                event.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up");
        broadcast.putExtra("source", "media_button");
        context.sendBroadcast(broadcast);
    }
}
