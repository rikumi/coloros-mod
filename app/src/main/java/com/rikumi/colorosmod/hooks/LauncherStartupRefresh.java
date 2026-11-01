package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import static com.rikumi.colorosmod.XposedInit.log;

/** Refresh once after settings and the first workspace bind are ready in each Launcher process. */
final class LauncherStartupRefresh {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();
    private static volatile boolean active = true, settingsReady;
    private static WeakReference<Context> host = new WeakReference<>(null);
    private static ClassLoader loader;
    private static boolean requested;

    static void onSettingsSnapshotPublished() {
        settingsReady = true;
        postCheck(0);
    }
    static void onBound(Object launcher, ClassLoader classLoader) {
        if (!active || !(launcher instanceof Context context)) return;
        host = new WeakReference<>(context);
        loader = classLoader;
        postCheck(0);
    }
    private static void postCheck(int attempt) {
        MAIN.postAtTime(() -> check(attempt), TOKEN, android.os.SystemClock.uptimeMillis() + (attempt == 0 ? 0 : 100));
    }
    private static void check(int attempt) {
        Context context = host.get();
        if (!active || !settingsReady || requested || context == null || loader == null) return;
        try {
            Object state = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                    "com.android.launcher3.LauncherAppState", loader), "getInstanceNoCreate");
            Object model = state == null ? null : XposedHelpers.callMethod(state, "getModel");
            if (model == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(model, "isModelLoaded"))) {
                if (attempt < 100) postCheck(attempt + 1);
                return;
            }
            requested = true;
            // Set before forceReload: its next finishBindingItems must not trigger another reload.
            LauncherHooks.refreshAfterStartup(model);
            log("launcher initial refresh completed");
        } catch (Throwable error) { log("launcher startup refresh failed: " + error); }
    }
    static void cleanup() {
        active = false;
        MAIN.removeCallbacksAndMessages(TOKEN);
        host.clear();
    }
    private LauncherStartupRefresh() {}
}
