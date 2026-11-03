package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import static com.rikumi.colorosmod.XposedInit.log;

/** ColorOS 17's per-row foreground mode, independent of its background blur mode. */
final class HeadsUpForegroundHooks {
    private static final String MATERIAL = "com.oplus.systemui.notification.row.material.";
    private static WeakReference<Object> colorHost = new WeakReference<>(null);

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> mode = XposedHelpers.findClass(MATERIAL + "MaterialColorMode", lp.classLoader);
            Object white = XposedHelpers.getStaticObjectField(mode, "NORMAL_DARK");
            Class<?> row = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader);
            XposedHelpers.findAndHookMethod(MATERIAL + "NotificationMaterialColorModeController",
                    lp.classLoader, "getMaterialColorModeForRow", row, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !NotificationBackgroundBrightnessHooks.forceWhiteHeadsUp()) return;
                    // The explicit flag also covers binding before the row has entered HeadsUpLayout.
                    boolean headsUp = Boolean.TRUE.equals(p.args[1]) || (p.args[0] != null
                            && Boolean.TRUE.equals(XposedHelpers.callMethod(p.args[0], "isHeadsUpState")));
                    if (headsUp) p.setResult(white);
                }
            });
            Class<?> colorEx = XposedHelpers.findClass(MATERIAL + "NotificationMaterialColorExImpl", lp.classLoader);
            XC_MethodHook rememberHost = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    colorHost = new WeakReference<>(p.thisObject);
                }
            };
            XposedBridge.hookAllConstructors(colorEx, rememberHost);
            // Discover the existing host when the module was loaded after SystemUI startup.
            XposedBridge.hookAllMethods(colorEx, "updateNotificationColorMode", rememberHost);
            XposedBridge.hookAllMethods(colorEx, "applyColorModeForHeadsUp", rememberHost);
        } catch (Throwable error) { log("heads-up foreground hook unavailable: " + error); }
    }

    static void refresh(Object blurManager) {
        try {
            Object host = colorHost.get();
            if (host == null && blurManager != null) {
                Object lazy = XposedHelpers.getObjectField(blurManager, "notificationMaterialColorEx");
                host = XposedHelpers.callMethod(lazy, "get");
                colorHost = new WeakReference<>(host);
            }
            if (host == null) return;
            Object lazy = XposedHelpers.getObjectField(host, "headsUpLayoutController");
            Object controller = XposedHelpers.callMethod(lazy, "get");
            // Requests the native children/state update, including text, icons and controls.
            XposedHelpers.callMethod(controller, "notifyMaterialColorChanged");
        } catch (Throwable error) { log("heads-up foreground refresh failed: " + error); }
    }

    static void cleanup() { colorHost.clear(); }
    private HeadsUpForegroundHooks() {}
}
