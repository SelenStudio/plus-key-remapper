package com.pluskeymap.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

public class LauncherActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Install splash screen first — must be before super.onCreate / setContentView.
        // applySavedTheme was already called in PlusKeyApp.onCreate, so the correct
        // night mode is active before the splash background color is resolved.
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        boolean setupDone = SetupActivity.isSetupDone(this);
        boolean skipped   = getSharedPreferences(SetupActivity.PREFS_SETUP, MODE_PRIVATE)
                .getBoolean(SetupActivity.KEY_SKIPPED, false);

        if (setupDone || skipped) {
            startActivity(new Intent(this, MainActivity.class));
        } else {
            startActivity(new Intent(this, SetupActivity.class));
        }
        finish();
    }
}
