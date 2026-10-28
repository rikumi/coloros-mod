package com.rikumi.colorosmod;

import android.util.Log;

/** Logging for APK components which run without the framework-only Xposed API. */
public final class AppLog {
    private AppLog() {}

    public static void log(String message) {
        Log.e("ColorOSMod", message);
    }
}
