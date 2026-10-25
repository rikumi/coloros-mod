package com.rikumi.colorosmod.hooks;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.Executor;
import com.rikumi.colorosmod.HomeControlsTileSettings;
import com.rikumi.colorosmod.HomeControlsLaunchIntents;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.*;

final class HomeControlsTileHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> expandable = XposedHelpers.findClass("com.android.systemui.animation.Expandable", lp.classLoader);
            XposedHelpers.findAndHookMethod("com.android.systemui.qs.external.CustomTile", lp.classLoader,
                    "handleClick", expandable, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!readBoolCached(HomeControlsTileSettings.KEY, false)) return;
                            ComponentName component = (ComponentName) XposedHelpers.getObjectField(p.thisObject, "mComponent");
                            if (!MODULE_PACKAGE.equals(component.getPackageName())
                                    || !HomeControlsTileSettings.SERVICE.equals(component.getClassName())) return;
                            try {
                                Context context = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                                Object graph = XposedHelpers.getObjectField(context.getApplicationContext(), "mSysUIComponent");
                                Object provider = XposedHelpers.getObjectField(graph, "controlsComponentProvider");
                                Object controls = XposedHelpers.callMethod(provider, "get");
                                Optional<?> ui = (Optional<?>) XposedHelpers.callMethod(controls, "getControlsUiController");
                                Optional<?> listing = (Optional<?>) XposedHelpers.callMethod(controls, "getControlsListingController");
                                if (ui.isEmpty() || listing.isEmpty()) return;
                                Object starter = XposedHelpers.getObjectField(p.thisObject, "mActivityStarter");
                                Handler main = (Handler) XposedHelpers.getObjectField(p.thisObject, "mUiHandler");
                                Executor background = (Executor) XposedHelpers.getObjectField(listing.get(), "backgroundExecutor");

                                background.execute(() -> {
                                    try {
                                        XposedHelpers.callMethod(listing.get(), "forceReload");
                                        main.post(() -> launch(context, controls, ui.get(), starter));
                                    } catch (Throwable error) {
                                        log("module home controls discovery failed: " + error);
                                        main.post(() -> launchApp(context, starter));
                                    }
                                });
                                p.setResult(null);
                            } catch (Throwable error) {
                                log("module home controls launch failed: " + error);
                                // Continue to TileService, which opens an installed home app.
                            }
                        }
                    });
        } catch (Throwable error) { log("module home controls hook failed: " + error); }
    }

    private static void launch(Context context, Object controls, Object ui, Object starter) {
        try {
            Class<?> destination = (Class<?>) XposedHelpers.callMethod(ui, "resolveActivity");
            Intent intent = new Intent().setComponent(new ComponentName(context, destination))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("extra_animate", true);
            boolean active = "AVAILABLE".equals(XposedHelpers.callMethod(controls, "getVisibility").toString());
            Optional<?> controller = (Optional<?>) XposedHelpers.callMethod(controls, "getControlsController");
            if (active && controller.isPresent()) {
                Object selection = XposedHelpers.callMethod(controller.get(), "getPreferredSelection");
                if (selection.getClass().getName().endsWith("SelectedItem$StructureItem")) {
                    Object structure = XposedHelpers.callMethod(selection, "getStructure");
                    active = !((Collection<?>) XposedHelpers.callMethod(structure, "getControls")).isEmpty();
                }
            }
            XposedHelpers.callMethod(starter, "startActivity", intent, true, null, active);
        } catch (Throwable error) {
            log("module home controls activity start failed: " + error);
            launchApp(context, starter);
        }
    }

    private static void launchApp(Context context, Object starter) {
        for (Intent intent : HomeControlsLaunchIntents.apps(context)) {
            try {
                XposedHelpers.callMethod(starter, "startActivity", intent, true);
                return;
            } catch (Throwable error) { log("module home app start failed: " + error); }
        }
    }
}
