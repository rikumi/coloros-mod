package com.rikumi.colorosmod.hooks;

import android.graphics.Rect;
import android.view.DisplayCutout;
import com.rikumi.colorosmod.TabletDevice;
import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;

/** Preserve the real cutout data before ColorOS strips it for tablet apps. */
public final class TabletCutoutInsetsHooks {
    private TabletCutoutInsetsHooks() {}

    public static void hook(ClassLoader loader, XposedInit module) {
        // QQ's organizer path also needs safe bounds when the manager's legacy
        // tablet stripping path is disabled by the ROM.
        if (TabletDevice.isTablet()) hookQqDualPaneBounds(loader, module);
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.android.server.wm.OplusFullScreenDisplayManager", loader);
            // Use the manager's own tablet flag; never install this hook on phones,
            // even if a previously saved preference is enabled.
            if (!Boolean.TRUE.equals(XposedHelpers.getStaticObjectField(manager, "sIsTablet"))) return;
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", loader);
            Class<?> state = XposedHelpers.findClass("android.view.InsetsState", loader);
            XposedHelpers.findAndHookMethod(manager, "removeTabletCutoutIfNeed",
                    window, state, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (XposedInit.readBoolCached(
                                    XposedInit.KEY_TABLET_LANDSCAPE_CUTOUT_INSETS_ENABLED, false)) {
                                // This method only strips tablet landscape cutouts. Keep the
                                // original DisplayCutout and all displayCutout InsetsSources.
                                // Do not alter app bounds, cutout layout mode or process flags.
                                param.setResult(null);
                            }
                        }
                    });
            // The private stripping method may have been inlined into this caller.
            module.deoptimize(manager.getDeclaredMethod("constructSpecificInsetsStateForCutout",
                    window, state));
            XposedInit.log("HOOK OK tablet landscape cutout insets");
        } catch (Throwable t) {
            XposedInit.log("HOOK FAIL tablet landscape cutout insets: " + t);
        }
    }

    private static void hookQqDualPaneBounds(ClassLoader loader, XposedInit module) {
        try {
            Class<?> calculator = XposedHelpers.findClass(
                    "com.android.server.wm.CompactWindowBoundsCalculator", loader);
            Class<?> task = XposedHelpers.findClass("com.android.server.wm.Task", loader);
            XposedHelpers.findAndHookMethod(calculator, "calculateBoundsInPosition", task,
                    int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!(p.getResult() instanceof Rect original)) return;
                            try {
                                Object owner = p.args[0];
                                if (!"com.tencent.mobileqq".equals(XposedHelpers.callMethod(owner,
                                        "getBasePackageName"))) return;
                                Object display = XposedHelpers.callMethod(owner, "getDisplayContent");
                                if (display == null || (int) XposedHelpers.callMethod(display, "getDisplayId") != 0) return;
                                Object info = XposedHelpers.callMethod(display, "getDisplayInfo");
                                int width = XposedHelpers.getIntField(info, "logicalWidth");
                                int height = XposedHelpers.getIntField(info, "logicalHeight");
                                if (width <= height) return;
                                Rect parent = (Rect) XposedHelpers.callMethod(owner, "getBounds");
                                int position = (int) p.args[1];
                                if (position == 3) return;
                                Rect safe = new Rect(parent);
                                DisplayCutout cutout = (DisplayCutout) XposedHelpers.getObjectField(info, "displayCutout");
                                if (cutout != null && XposedInit.readBoolCached(
                                        XposedInit.KEY_TABLET_LANDSCAPE_CUTOUT_INSETS_ENABLED, false)) {
                                    safe.left = Math.max(safe.left, cutout.getSafeInsetLeft());
                                    safe.right = Math.min(safe.right, width - cutout.getSafeInsetRight());
                                }
                                // Splash remains a full-width native QQ tablet layout. Secondary
                                // Activities overlay only its right-hand 70% content pane.
                                Rect adjusted = new Rect(safe);
                                if (position == 1) adjusted.left += Math.round(safe.width() * 0.3f);
                                adjusted.offset(-parent.left, -parent.top);
                                if (!adjusted.isEmpty()) p.setResult(adjusted);
                            } catch (Throwable error) {
                                XposedInit.log("QQ tablet cutout bounds unavailable: " + error);
                            }
                        }
                    });
            Class<?> builder = XposedHelpers.findClass(
                    "com.android.server.wm.OplusCompactWindowOrganizer$TaskFragmentBuilder", loader);
            for (Method method : builder.getDeclaredMethods()) {
                if (method.getName().equals("rebuildTaskFragmentInner")
                        || method.getName().equals("resizeTaskFragment")) module.deoptimize(method);
            }
            XposedInit.log("HOOK OK QQ tablet cutout bounds");
        } catch (Throwable error) {
            XposedInit.log("HOOK FAIL QQ tablet cutout bounds: " + error);
        }
    }
}
