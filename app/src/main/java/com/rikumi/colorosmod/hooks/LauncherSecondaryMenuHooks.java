package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Promote the launcher's already-authorized More items into the main icon menu. */
final class LauncherSecondaryMenuHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> more = XposedHelpers.findClass(
                    "com.android.launcher3.popup.OplusBaseSystemShortcut$MordFunctions", lp.classLoader);
            Class<?> data = XposedHelpers.findClassIfExists(
                    "com.android.launcher3.popup.model.PopupMenuData", lp.classLoader);
            if (data != null) {
                // Launcher 17.3.12 replaced OplusPopupContainerWithArrow with
                // PopupMenuData -> PopupMenuPresenter -> SystemShortcutEntry.
                XposedHelpers.findAndHookMethod(data, "getSystemShortcutsIn", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable() || !enabled() || !(p.getResult() instanceof List<?> source)) return;
                        p.setResult(flatten(source, more));
                    }
                });
                log("HOOK OK launcher secondary menu PopupMenuData#getSystemShortcutsIn");
            } else {
                XposedHelpers.findAndHookMethod(
                        "com.android.launcher3.popup.OplusPopupContainerWithArrow", lp.classLoader,
                        "populateAndShow", View.class, List.class, int.class, List.class, List.class, List.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (enabled() && p.args[4] instanceof List<?> source)
                                    p.args[4] = flatten(source, more);
                            }
                        });
                log("HOOK OK launcher secondary menu OplusPopupContainerWithArrow#populateAndShow");
            }
        } catch (Throwable error) {
            log("launcher secondary menu hook failed: " + error);
        }
    }

    private static boolean enabled() {
        return readBoolCached(KEY_DISABLE_LAUNCHER_SECONDARY_MENU_ENABLED, false);
    }

    private static List<?> flatten(List<?> source, Class<?> more) {
        boolean wrapped = false;
        HashSet<Class<?>> enabled = new HashSet<>();
        for (Object item : source) {
            if (more.isInstance(item)) wrapped = true;
            else if (Boolean.TRUE.equals(XposedHelpers.callMethod(item, "isEnabled")))
                enabled.add(item.getClass());
        }
        if (!wrapped) return source;
        ArrayList<Object> result = new ArrayList<>();
        for (Object item : source) {
            if (!more.isInstance(item)) {
                result.add(item);
                continue;
            }
            List<?> children = (List<?>) XposedHelpers.getObjectField(item, "mMoreSystemShortcutList");
            for (Object child : children) {
                // Preserve native permission checks, click targets and mMoreShortcut.
                if (enabled.add(child.getClass())) result.add(child);
            }
        }
        return result;
    }

    private LauncherSecondaryMenuHooks() {}
}
