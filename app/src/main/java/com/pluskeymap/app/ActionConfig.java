package com.pluskeymap.app;

public class ActionConfig {

    public static final int ACTION_NONE           = 0;
    public static final int ACTION_FLASHLIGHT     = 1;
    public static final int ACTION_LAUNCH_APP     = 3;
    public static final int ACTION_VOLUME_UP      = 4;
    public static final int ACTION_VOLUME_DOWN    = 5;
    public static final int ACTION_MEDIA_PLAY     = 6;
    public static final int ACTION_MEDIA_NEXT     = 7;
    public static final int ACTION_MEDIA_PREV     = 8;
    public static final int ACTION_DND_TOGGLE     = 9;  // retired
    public static final int ACTION_RINGER_TOGGLE  = 10; // retired
    public static final int ACTION_CUSTOM_INTENT  = 11;
    public static final int ACTION_CAMERA_SHUTTER = 12;

    public static final String[] ACTION_LABELS = {
            "None",
            "Toggle Flashlight",
            null,           // slot 2 retired (was Screenshot) -- kept to avoid prefs remapping
            null,           // slot 3 retired (was Launch App) -- removed, background launch unreliable
            "Volume Up",
            "Volume Down",
            "Play / Pause Media",
            "Next Track",
            "Previous Track",
            null,           // slot 9 retired (was Toggle Do Not Disturb)
            "Toggle Ringer / Vibrate / DND",
            "Custom Intent",
            "Camera Shutter (in camera apps)"
    };
}