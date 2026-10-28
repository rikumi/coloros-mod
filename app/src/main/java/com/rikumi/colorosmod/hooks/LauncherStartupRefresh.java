package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.system.Os;
import com.rikumi.colorosmod.SettingsProvider;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import static com.rikumi.colorosmod.XposedInit.log;

/** One recovery after the first completed bind of each Zygote, retained across Launcher processes. */
final class LauncherStartupRefresh {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();
    private static volatile boolean active = true, settingsReady;
    private static WeakReference<Context> host = new WeakReference<>(null);
    private static ClassLoader loader;
    private static boolean requested;
    private static int claimAttempts;

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
            // Provider 持久保存 Zygote 身份；IPC 和磁盘提交不占用桌面主线程。
            claimAttempts++;
            new Thread(() -> claim(context.getApplicationContext(), model), "ColorOSMod-LauncherStartup").start();
        } catch (Throwable error) { log("launcher Zygote refresh readiness failed: " + error); }
    }
    private static String zygoteIdentity() {
        int parent = Os.getppid();
        String identity = Integer.toString(parent);
        // starttime 区分同一开机周期内 PID 被复用的情况；部分 ROM 仅允许读取父 PID。
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/" + parent + "/stat"))) {
            String stat = reader.readLine();
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" +");
            identity += ":" + Long.parseLong(fields[19]);
        } catch (Exception ignored) { }
        return identity;
    }
    private static void claim(Context context, Object model) {
        String identity = zygoteIdentity();
        boolean claimed = false;
        try {
            if (!active) return;
            Bundle result = context.getContentResolver().call(SettingsProvider.AUTHORITY,
                    SettingsProvider.LAUNCHER_STARTUP_CLAIM, identity, null);
            if (result == null) {
                MAIN.post(() -> {
                    if (active && claimAttempts < 10) { requested = false; postCheck(1); }
                    else log("launcher Zygote refresh provider unavailable");
                });
                return;
            }
            claimed = result.getBoolean("claimed", false);
            if (!claimed) return;
            MAIN.post(() -> {
                if (!active) { release(context, identity); return; }
                try {
                    LauncherHooks.refreshAfterZygote(model);
                    log("launcher initial refresh completed for Zygote " + identity);
                } catch (Throwable error) {
                    log("launcher initial Zygote refresh failed: " + error);
                    release(context, identity);
                }
            });
        } catch (Throwable error) {
            log("launcher Zygote refresh claim failed: " + error);
            if (claimed) release(context, identity);
        }
    }
    private static void release(Context context, String identity) {
        new Thread(() -> {
            try { context.getContentResolver().call(SettingsProvider.AUTHORITY,
                    SettingsProvider.LAUNCHER_STARTUP_RELEASE, identity, null); }
            catch (Throwable error) { log("launcher Zygote refresh release failed: " + error); }
        }, "ColorOSMod-LauncherStartupRollback").start();
    }
    static void cleanup() {
        active = false;
        MAIN.removeCallbacksAndMessages(TOKEN);
        host.clear();
    }
    private LauncherStartupRefresh() {}
}
