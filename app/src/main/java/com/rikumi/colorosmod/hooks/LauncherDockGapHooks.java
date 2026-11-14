package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Rect;
import com.rikumi.colorosmod.xposed.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class LauncherDockGapHooks {
    private LauncherDockGapHooks() {}

    static void hook(ClassLoader loader, float density) throws ReflectiveOperationException {
        // Resolve mixed parameter classes through the stable DeviceProfile fields.
        Class<?> profile = XposedHelpers.findClass("com.android.launcher3.DeviceProfile", loader);
        Class<?> workspace = profile.getDeclaredField("mWorkspaceParam").getType();
        Class<?> hotseat = profile.getDeclaredField("mHotseatParam").getType();
        Class<?> indicator = profile.getDeclaredField("mPageIndicatorParam").getType();
        Class<?> cells = profile.getDeclaredField("mCellLayoutParam").getType();
        Method update = null;
        for (Method method : workspace.getDeclaredMethods()) {
            if (method.getReturnType() == void.class && java.util.Arrays.equals(
                    method.getParameterTypes(), new Class<?>[]{hotseat, indicator, cells})) {
                if (update != null) throw new NoSuchMethodException("ambiguous workspace update");
                update = method;
            }
        }
        if (update == null) throw new NoSuchMethodException("workspace update");
        // 17.3.12 WorkspaceParam.b holds its ViewParam padding; d caches bottom padding.
        Field padding = workspace.getDeclaredField("b");
        Field rect = padding.getType().getDeclaredField("a");
        Field bottom = workspace.getDeclaredField("d");
        if (rect.getType() != Rect.class || bottom.getType() != int.class)
            throw new NoSuchFieldException("workspace padding contract");
        padding.setAccessible(true); rect.setAccessible(true); bottom.setAccessible(true);
        Method recalculate = update;
        XposedBridge.hookMethod(update, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (p.hasThrowable() || !readBool(KEY_INDICATOR_ENABLED, false)) return;
                Rect value = (Rect) rect.get(padding.get(p.thisObject));
                int requested = Math.round(Math.max(0, Math.min(32,
                        readInt(KEY_INDICATOR_DP, INDICATOR_REDUCE_DP))) * density);
                int delta = shift(value, requested, bottom.getInt(p.thisObject));
                bottom.setInt(p.thisObject, bottom.getInt(p.thisObject) - delta);
            }
        });
        XposedBridge.hookAllConstructors(profile, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (p.hasThrowable() || !readBool(KEY_INDICATOR_ENABLED, false)) return;
                // AOT may have inlined the final update into the native constructor.
                // Recompute through the hooked method after all profile fields exist.
                XposedHelpers.callMethod(profile.getDeclaredField("mWorkspaceParam").get(p.thisObject),
                        recalculate.getName(),
                        profile.getDeclaredField("mHotseatParam").get(p.thisObject),
                        profile.getDeclaredField("mPageIndicatorParam").get(p.thisObject),
                        profile.getDeclaredField("mCellLayoutParam").get(p.thisObject));
            }
        });
        XposedBridge.deoptimize(update);
        // Native constructors/updateInsets directly call the final workspace method.
        for (Method method : profile.getDeclaredMethods())
            if (method.getName().equals("updateInsets")) XposedBridge.deoptimize(method);
        log("HOOK OK workspace layout (page-to-Dock gap, ColorOS 17)");
    }

    static int shift(Rect padding, int requested, int bottom) {
        int delta = Math.max(0, Math.min(requested, Math.min(padding.bottom, bottom)));
        padding.top += delta;
        padding.bottom -= delta;
        return delta;
    }
}
