package com.rikumi.colorosmod.hooks;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import com.rikumi.colorosmod.IconCacheRefreshCommands;
import com.rikumi.colorosmod.xposed.*;
import static com.rikumi.colorosmod.XposedInit.log;

/** Clear the live native icon DB before the command restarts icon-consuming processes. */
final class LauncherIconCacheRefreshHooks {
    private static Context host;
    private static BroadcastReceiver receiver;
    private static boolean active;

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (!"com.android.launcher".equals(lp.processName)) return;
        active = true;
        Application app = (Application) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", lp.classLoader), "currentApplication");
        if (app != null) register(app, lp.classLoader);
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (active) register((Context) p.thisObject, lp.classLoader);
            }
        });
    }

    private static void register(Context context, ClassLoader loader) {
        if (!active || receiver != null) return;
        BroadcastReceiver candidate = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (!active || !IconCacheRefreshCommands.ACTION.equals(intent.getAction())) return;
                PendingResult result = goAsync();
                try {
                    Object state = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                            "com.android.launcher3.LauncherAppState", loader), "getInstance", ctx);
                    Object icons = XposedHelpers.callMethod(state, "getIconCache");
                    Handler worker = (Handler) XposedHelpers.getObjectField(icons, "mWorkerHandler");
                    if (!worker.post(() -> {
                        try {
                            XposedHelpers.callMethod(icons, "clearIconsCacheAndDb");
                            // Clarity mode uses a separate native icon DB. Clear the
                            // inactive counterpart too, so toggling it cannot restore old pixels.
                            String name = (String) XposedHelpers.getObjectField(icons, "mDbFileName");
                            String suffix = ".clarity-v3-bicubic";
                            if (name != null && !name.isEmpty() && !name.contains("/")) {
                                Context cacheContext = (Context) XposedHelpers.getObjectField(icons, "mContext");
                                String other = name.endsWith(suffix)
                                        ? name.substring(0, name.length() - suffix.length()) : name + suffix;
                                if (cacheContext.getDatabasePath(other).exists() && !cacheContext.deleteDatabase(other))
                                    throw new IllegalStateException("Inactive icon DB could not be cleared");
                            }
                            result.setResultCode(-1);
                            result.setResultData(IconCacheRefreshCommands.CLEARED);
                        } catch (Throwable error) {
                            result.setResultCode(0);
                            log("native icon cache refresh failed: " + error);
                        } finally { result.finish(); }
                    })) throw new IllegalStateException("Launcher icon worker is stopped");
                } catch (Throwable error) {
                    result.setResultCode(0);
                    result.finish();
                    log("icon cache refresh unavailable: " + error);
                }
            }
        };
        // Only root/shell or a privileged system sender can request this operation.
        IntentFilter filter = new IntentFilter(IconCacheRefreshCommands.ACTION);
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(candidate, filter, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(candidate, filter, "android.permission.DUMP", null);
        host = context;
        receiver = candidate;
    }

    static void cleanup() {
        active = false;
        if (host != null && receiver != null) {
            try { host.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) {}
        }
        host = null;
        receiver = null;
    }

    private LauncherIconCacheRefreshHooks() {}
}
