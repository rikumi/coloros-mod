package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.ArrayList;

final class RecentsHideFreeformHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> grouped = XposedHelpers.findClassIfExists("com.android.wm.shell.shared.GroupedTaskInfo", lp.classLoader);
            if (grouped == null) grouped = XposedHelpers.findClass("com.android.wm.shell.util.GroupedRecentTaskInfo", lp.classLoader);
            Class<?> filter = XposedHelpers.findClassIfExists("com.oplus.quickstep.data.OplusRecentTasksFilter", lp.classLoader);
            if (filter == null) filter = XposedHelpers.findClass("ko.a", lp.classLoader); // Launcher 17.3.12
            final Method first = taskGetter(grouped, "getTaskInfo1", "e");
            final Method second = taskGetter(grouped, "getTaskInfo2", "f");
            boolean matched = false;
            for (Method method : filter.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                // filterTaskInfo / d: do not hook filterTask(GroupTask), whose
                // boolean result has a different role in the model pipeline.
                if (method.getReturnType() != boolean.class || args.length != 4
                        || args[0] != int.class || args[1] != int.class
                        || args[2] != grouped || args[3] != ArrayList.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!readBool(KEY_RECENTS_HIDE_FREEFORM_ENABLED, false) || p.args[2] == null) return;
                        try {
                            // Preserve native split/mixed cards.
                            if (second.invoke(p.args[2]) != null) return;
                            Object task = first.invoke(p.args[2]);
                            if (task != null && LauncherHooks.isFlexibleFloatingWindow(lp.classLoader, task)) p.setResult(true);
                        } catch (Throwable error) { log("recents floating task filter failed: " + error); }
                    }
                });
                matched = true;
            }
            if (!matched) log("recents floating task filter: no supported signature");
        } catch (Throwable error) { log("recents floating task filter unavailable: " + error); }
    }

    private static Method taskGetter(Class<?> grouped, String original, String renamed) throws NoSuchMethodException {
        Method method;
        try { method = grouped.getDeclaredMethod(original); }
        catch (NoSuchMethodException missing) { method = grouped.getDeclaredMethod(renamed); }
        if (!android.app.TaskInfo.class.isAssignableFrom(method.getReturnType())) throw new NoSuchMethodException(original);
        method.setAccessible(true);
        return method;
    }

    private RecentsHideFreeformHooks() {}
}
