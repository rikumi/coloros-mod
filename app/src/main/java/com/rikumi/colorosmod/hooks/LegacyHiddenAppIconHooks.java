package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import com.rikumi.colorosmod.xposed.*;
import static com.rikumi.colorosmod.XposedInit.*;

/** Hidden-folder items survive LauncherModel reloads and need their own bitmap refresh. */
final class LegacyHiddenAppIconHooks {
    private static final String MANAGER = "com.android.launcher.filter.DeepProtectedAppsManager";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Object, Boolean> pending = new WeakHashMap<>();
    private static volatile boolean active = true;

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT != 35) return;
        try {
            XposedHelpers.findAndHookMethod(MANAGER, lp.classLoader, "showVirtualFolderIfNeeded",
                    XposedHelpers.findClass("com.android.launcher.Launcher", lp.classLoader), new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (readBoolCached("launcher_icon_pack_fallback_enabled", false)) refresh((Context) p.args[0]);
                        }
                    });
        } catch (Throwable error) { log("hidden-folder icon refresh hook failed: " + error); }
    }

    static void refresh(Context context) {
        if (!active || Build.VERSION.SDK_INT != 35) return;
        try {
            ClassLoader loader = context.getClassLoader();
            Object manager = XposedHelpers.callStaticMethod(XposedHelpers.findClass(MANAGER, loader), "getInstance", context);
            Object folder = XposedHelpers.callMethod(manager, "getFolderInfo");
            if (folder == null) return;
            Object contents = XposedHelpers.getObjectField(folder, "contents");
            if (!(contents instanceof java.util.Collection<?> items) || items.isEmpty()) return;
            ArrayList<?> snapshot = new ArrayList<>(items);
            Object app = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.android.launcher3.LauncherAppState", loader),
                    "getInstance", context);
            Object icons = XposedHelpers.callMethod(app, "getIconCache");
            Handler worker = (Handler) XposedHelpers.getObjectField(icons, "mWorkerHandler");
            synchronized (pending) { if (pending.put(manager, true) != null) return; }
            if (!worker.post(() -> {
                try {
                    Executor ui = action -> main.post(() -> { if (active) action.run(); });
                    for (Object item : snapshot) {
                        if (!active) return;
                        Object component = XposedHelpers.callMethod(item, "getTargetComponent");
                        if (component == null) continue;
                        Object user = XposedHelpers.getObjectField(item, "user");
                        XposedHelpers.callMethod(icons, "remove", component, user);
                        XposedHelpers.callMethod(icons, "getTitleAndIcon", item, false);
                        XposedHelpers.callMethod(manager, "updateItemInUiExecutor", ui, item);
                    }
                } catch (Throwable error) { log("hidden-folder icon refresh failed: " + error); }
                finally { synchronized (pending) { pending.remove(manager); } }
            })) {
                synchronized (pending) { pending.remove(manager); }
                throw new IllegalStateException("Launcher icon worker is stopped");
            }
        } catch (Throwable error) { log("hidden-folder icon cache unavailable: " + error); }
    }

    static void cleanup() { active = false; synchronized (pending) { pending.clear(); } }
}
