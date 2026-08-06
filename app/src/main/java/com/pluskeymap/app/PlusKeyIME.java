package com.pluskeymap.app;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Minimal IME that just listens for hardware key events.
 * IMEs receive hardware key events through a completely separate channel
 * from accessibility services — OEMs that block one often leave the other open.
 */
public class PlusKeyIME extends InputMethodService {

    private static final String TAG = "PKM_IME";

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown: " + KeyEvent.keyCodeToString(keyCode) + " (" + keyCode + ")");
        broadcastKey(keyCode, KeyEvent.ACTION_DOWN);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyUp: " + KeyEvent.keyCodeToString(keyCode) + " (" + keyCode + ")");
        broadcastKey(keyCode, KeyEvent.ACTION_UP);
        return super.onKeyUp(keyCode, event);
    }

    private void broadcastKey(int keyCode, int action) {
        Intent i = new Intent(PlusKeyService.ACTION_KEY_DETECTED);
        i.putExtra(PlusKeyService.EXTRA_KEYCODE, keyCode);
        i.putExtra(PlusKeyService.EXTRA_ACTION, action == KeyEvent.ACTION_DOWN ? "down" : "up");
        i.putExtra("source", "ime");
        sendBroadcast(i);
    }
}
