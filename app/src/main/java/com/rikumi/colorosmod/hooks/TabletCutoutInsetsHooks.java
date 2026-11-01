package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Preserve the real cutout data before ColorOS strips it for tablet apps. */
public final class TabletCutoutInsetsHooks {
    private TabletCutoutInsetsHooks() {}

    public static void hook(ClassLoader loader, XposedInit module) {
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
}
