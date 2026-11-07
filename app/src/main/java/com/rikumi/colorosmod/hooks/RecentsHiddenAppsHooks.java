package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;

final class RecentsHiddenAppsHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> privacy = XposedHelpers.findClassIfExists(
                    "com.oplus.quickstep.privacy.OplusPrivacyManager", lp.classLoader);
            Method hidden;
            if (privacy != null) {
                hidden = privacy.getDeclaredMethod("isHiddenPkg", String.class, int.class);
            } else {
                // Launcher 17.3.12: OplusPrivacyManager, userId precedes packageName.
                privacy = XposedHelpers.findClass("qp.c", lp.classLoader);
                hidden = privacy.getDeclaredMethod("g", int.class, String.class);
            }
            if (hidden.getReturnType() != boolean.class) throw new NoSuchMethodException("isHiddenPkg");
            XposedBridge.hookMethod(hidden, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (readBool(KEY_RECENTS_SHOW_HIDDEN_ENABLED, false)
                            && inRecentsPath(new Throwable().getStackTrace())) p.setResult(false);
                }
            });
        } catch (Throwable error) { log("recents hidden apps hook unavailable: " + error); }
    }

    static boolean inRecentsPath(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String name = frame.getClassName();
            // Retain app-lock and payment/content-protection decisions.
            if (name.startsWith("com.oplus.quickstep.applock.")
                    || name.startsWith("com.oplus.quickstep.locksetting.")) return false;
        }
        for (StackTraceElement frame : stack) {
            String name = frame.getClassName();
            if ((name.startsWith("com.android.quickstep.")
                    && !name.toLowerCase(java.util.Locale.ROOT).contains("lock"))
                    || name.startsWith("com.oplus.quickstep.gesture.")
                    || name.startsWith("com.oplus.quickstep.rapidreaction.")
                    || name.equals("com.oplus.quickstep.data.OplusRecentTasksFilter")
                    || name.equals("ko.a") // OplusRecentTasksFilter
                    || name.equals("ha.s")) // PanoramaTaskViewInterruptTouchController callback
                return true;
        }
        return false;
    }

    private RecentsHiddenAppsHooks() {}
}
