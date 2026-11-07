package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.app.TaskInfo;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.ArrayList;

final class RecentsHideNotRunningHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> grouped = XposedHelpers.findClassIfExists("com.android.wm.shell.shared.GroupedTaskInfo", lp.classLoader);
            if (grouped == null) grouped = XposedHelpers.findClass("com.android.wm.shell.util.GroupedRecentTaskInfo", lp.classLoader);
            Class<?> filter = XposedHelpers.findClassIfExists("com.oplus.quickstep.data.OplusRecentTasksFilter", lp.classLoader);
            if (filter == null) filter = XposedHelpers.findClass("ko.a", lp.classLoader);
            Method first = RecentsHideFreeformHooks.taskGetter(grouped, "getTaskInfo1", "e");
            Method second = RecentsHideFreeformHooks.taskGetter(grouped, "getTaskInfo2", "f");
            boolean matched = false;
            for (Method method : filter.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (method.getReturnType() != boolean.class || args.length != 4
                        || args[0] != int.class || args[1] != int.class
                        || args[2] != grouped || args[3] != ArrayList.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!readBool(KEY_RECENTS_HIDE_NOT_RUNNING_ENABLED, false) || p.args[2] == null) return;
                        try {
                            TaskInfo a = (TaskInfo) first.invoke(p.args[2]);
                            TaskInfo b = (TaskInfo) second.invoke(p.args[2]);
                            if (a != null && !XposedHelpers.getBooleanField(a, "isRunning")
                                    && (b == null || !XposedHelpers.getBooleanField(b, "isRunning"))) p.setResult(true);
                        } catch (Throwable ignored) {
                            // Unknown/mixed task shapes retain the native decision.
                        }
                    }
                });
                matched = true;
            }
            if (!matched) log("recents not-running filter: no supported signature");
        } catch (Throwable error) { log("recents not-running filter unavailable: " + error); }
    }

    private RecentsHideNotRunningHooks() {}
}
