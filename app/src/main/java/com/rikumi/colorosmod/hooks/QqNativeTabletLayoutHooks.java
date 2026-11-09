package com.rikumi.colorosmod.hooks;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.DisplayCutout;
import com.rikumi.colorosmod.TabletDevice;
import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;

/** Keep QQ's own tablet layout behind native secondary-page task fragments. System scope only. */
public final class QqNativeTabletLayoutHooks {
    private static final String SPLASH = "com.tencent.mobileqq.activity.SplashActivity";

    public static void hook(ClassLoader loader, XposedInit module) {
        if (!TabletDevice.isTablet()) return;
        try {
            Class<?> launch = XposedHelpers.findClass("android.app.servertransaction.LaunchActivityItem", loader);
            Class<?> change = XposedHelpers.findClass("android.app.servertransaction.ActivityConfigurationChangeItem", loader);
            Class<?> record = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", loader);
            XposedBridge.hookAllConstructors(launch, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object info = XposedHelpers.getObjectField(p.thisObject, "mInfo");
                        if (info instanceof ActivityInfo activity && SPLASH.equals(activity.name))
                            adaptConfiguration(p.thisObject, "mOverrideConfig");
                    } catch (Throwable error) { XposedInit.log("QQ native launch config: " + error); }
                }
            });
            XposedBridge.hookAllConstructors(change, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object activity = XposedHelpers.callStaticMethod(record, "forTokenLocked",
                                XposedHelpers.callMethod(p.thisObject, "getActivityToken"));
                        if (activity == null) return;
                        Object info = XposedHelpers.getObjectField(activity, "info");
                        if (info instanceof ActivityInfo app && SPLASH.equals(app.name))
                            adaptConfiguration(p.thisObject, "mConfiguration");
                    } catch (Throwable error) { XposedInit.log("QQ native changed config: " + error); }
                }
            });
            // QQ opens AIO as a Fragment inside SplashActivity, with no Activity launch.
            // Preserve its native 30/70 tablet layout and overlay secondary Activities
            // on the right. Fix the host configuration before Android merges it.
            XposedBridge.hookAllMethods(record, "resolveOverrideConfiguration", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        ActivityInfo info = (ActivityInfo) XposedHelpers.getObjectField(p.thisObject, "info");
                        if (!SPLASH.equals(info.name)) return;
                        Configuration config = (Configuration) XposedHelpers.callMethod(p.thisObject, "getResolvedOverrideConfiguration");
                        Object window = XposedHelpers.getObjectField(config, "windowConfiguration");
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        if (task == null || (int) XposedHelpers.callMethod(task, "getWindowingMode") != 120
                                || (int) XposedHelpers.callMethod(p.thisObject, "getDisplayId") != 0) return;
                        Rect bounds = new Rect((Rect) XposedHelpers.callMethod(task, "getBounds"));
                        if (bounds.width() <= bounds.height()) return;
                        if (XposedInit.readBoolCached(XposedInit.KEY_TABLET_LANDSCAPE_CUTOUT_INSETS_ENABLED, false)) {
                            Object display = XposedHelpers.callMethod(p.thisObject, "getDisplayContent");
                            Object displayInfo = XposedHelpers.callMethod(display, "getDisplayInfo");
                            DisplayCutout cutout = (DisplayCutout) XposedHelpers.getObjectField(displayInfo, "displayCutout");
                            if (cutout != null) {
                                bounds.left = Math.max(bounds.left, cutout.getSafeInsetLeft());
                                bounds.right = Math.min(bounds.right, XposedHelpers.getIntField(displayInfo, "logicalWidth") - cutout.getSafeInsetRight());
                            }
                        }
                        XposedHelpers.callMethod(window, "setBounds", bounds);
                        Rect app = new Rect(bounds);
                        Rect oldApp = (Rect) XposedHelpers.callMethod(window, "getAppBounds");
                        if (oldApp != null) {
                            app.top = Math.max(app.top, oldApp.top);
                            app.bottom = Math.min(app.bottom, oldApp.bottom);
                        }
                        XposedHelpers.callMethod(window, "setAppBounds", app);
                        float density = config.densityDpi / 160f;
                        if (density <= 0) density = ((Configuration) p.args[0]).densityDpi / 160f;
                        if (density > 0) config.screenWidthDp = Math.round(app.width() / density);
                        config.orientation = Configuration.ORIENTATION_LANDSCAPE;
                    } catch (Throwable error) { XposedInit.log("QQ native resolved config: " + error); }
                }
            });
            Class<?> boundsUtils = XposedHelpers.findClass("com.android.server.wm.OplusCompactWindowManagerUtils", loader);
            for (String methodName : new String[]{"getCompactActivityBounds", "getPrimaryActivityBounds"}) {
                XposedBridge.hookAllMethods(boundsUtils, methodName, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object activity = p.args[0];
                            if (activity == null) return;
                            ActivityInfo info = (ActivityInfo) XposedHelpers.getObjectField(activity, "info");
                            if (!SPLASH.equals(info.name)) return;
                            Rect parent = (Rect) p.args[1];
                            if (parent.width() <= parent.height()) return;
                            Rect bounds = new Rect(parent);
                            if (XposedInit.readBoolCached(XposedInit.KEY_TABLET_LANDSCAPE_CUTOUT_INSETS_ENABLED, false)) {
                                Object display = XposedHelpers.callMethod(activity, "getDisplayContent");
                                Object displayInfo = XposedHelpers.callMethod(display, "getDisplayInfo");
                                DisplayCutout cutout = (DisplayCutout) XposedHelpers.getObjectField(displayInfo, "displayCutout");
                                if (cutout != null) {
                                    bounds.left = Math.max(bounds.left, cutout.getSafeInsetLeft());
                                    bounds.right = Math.min(bounds.right, parent.right - cutout.getSafeInsetRight());
                                }
                            }
                            p.setResult(bounds);
                        } catch (Throwable error) { XposedInit.log("QQ native initial bounds error: " + error); }
                    }
                });
            }
            Class<?> windowState = XposedHelpers.findClass("com.android.server.wm.WindowState", loader);
            XposedBridge.hookAllMethods(windowState, "fillClientWindowFramesAndConfiguration", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object activity = XposedHelpers.getObjectField(p.thisObject, "mActivityRecord");
                        if (activity == null) return;
                        ActivityInfo info = (ActivityInfo) XposedHelpers.getObjectField(activity, "info");
                        if (!SPLASH.equals(info.name)) return;
                        Object merged = p.args[1];
                        Configuration original = (Configuration) XposedHelpers.callMethod(merged, "getOverrideConfiguration");
                        Configuration adapted = adapt(original);
                        if (adapted != original) XposedHelpers.callMethod(merged, "setOverrideConfiguration", adapted);
                    } catch (Throwable error) { XposedInit.log("QQ native window config: " + error); }
                }
            });
            for (String name : new String[]{"com.android.server.wm.ActivityTaskSupervisor", "com.android.server.wm.ActivityRecord"}) {
                Class<?> owner = XposedHelpers.findClass(name, loader);
                for (Method method : owner.getDeclaredMethods()) {
                    if (method.getName().equals("realStartActivityLocked")
                            || method.getName().equals("ensureActivityConfiguration")
                            || method.getName().equals("scheduleConfigurationChanged")) module.deoptimize(method);
                }
            }
            XposedInit.log("HOOK OK QQ native tablet client configuration (system only)");
        } catch (Throwable error) { XposedInit.log("QQ native tablet configuration unavailable: " + error); }
    }

    private static void adaptConfiguration(Object item, String field) {
        Configuration original = (Configuration) XposedHelpers.getObjectField(item, field);
        Configuration adapted = adapt(original);
        if (adapted != original) XposedHelpers.setObjectField(item, field, adapted);
    }

    private static Configuration adapt(Configuration original) {
        if (original == null) return original;
        Object window = XposedHelpers.getObjectField(original, "windowConfiguration");
        if ((int) XposedHelpers.callMethod(window, "getWindowingMode") != 120) return original;
        Rect bounds = (Rect) XposedHelpers.callMethod(window, "getBounds");
        if (bounds.width() <= bounds.height()) return original;
        Configuration copy = new Configuration(original);
        Object target = XposedHelpers.getObjectField(copy, "windowConfiguration");
        XposedHelpers.callMethod(target, "setWindowingMode", 1);
        copy.orientation = Configuration.ORIENTATION_LANDSCAPE;
        return copy;
    }

    private QqNativeTabletLayoutHooks() {}
}
