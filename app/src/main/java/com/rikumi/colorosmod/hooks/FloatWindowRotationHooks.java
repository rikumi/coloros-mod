package com.rikumi.colorosmod.hooks;

import android.os.Build;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.util.List;
import static com.rikumi.colorosmod.XposedInit.*;

/** ColorOS 17: complete only floating-window springs belonging to a display rotation. */
final class FloatWindowRotationHooks {
    private static final String SPRING = "com.oplus.flexibletask.animation.MultiSpringAnimation";
    private static final String ROTATION = "com.android.wm.shell.transition.OplusFlexibleRotationAnimation";
    private static final String FIX_ROTATION = "com.oplus.flexibletask.OplusFlexibleRotationSpringAnimation";
    private static final String MARKER = "colorosmod_float_window_screen_rotation";

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT < 37) return;
        try {
            Class<?> spring = XposedHelpers.findClass(SPRING, lp.classLoader);
            Class<?> listener = XposedHelpers.findClass(SPRING + "$OnMultiAnimationEndListener", lp.classLoader);
            XposedHelpers.findAndHookMethod(spring, "addEndListener", listener, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    Object callback = p.args[0];
                    if (callback == null) return;
                    // Both native rotation paths capture their owner in the end
                    // listener. This also identifies the snapshot alpha spring,
                    // without depending on R8's synthetic class/method numbers.
                    for (Field field : callback.getClass().getDeclaredFields()) {
                        String type = field.getType().getName();
                        if (!ROTATION.equals(type) && !FIX_ROTATION.equals(type)) continue;
                        field.setAccessible(true);
                        Object owner = field.get(callback);
                        if (owner != null && isScreenRotation(owner)) {
                            XposedHelpers.setAdditionalInstanceField(p.thisObject, MARKER, Boolean.TRUE);
                            break;
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(spring, "start", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !readBoolCached(KEY_FLOAT_WINDOW_DISABLE_ROTATION_ANIMATION, false)
                            || !Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(p.thisObject, MARKER))) return;
                    // Native skipToEnd requests the final values on the next
                    // animation frame and runs all normal update/end callbacks.
                    XposedHelpers.callMethod(p.thisObject, "skipToEnd");
                }
            });
        } catch (Throwable error) { log("floating-window screen rotation hook unavailable: " + error); }
    }

    static boolean isScreenRotation(Object owner) {
        Object info = XposedHelpers.getObjectField(owner, "mInfo");
        Object task = XposedHelpers.getObjectField(owner, "mChange");
        if (info == null || task == null) return false;
        int display = ((Number) XposedHelpers.callMethod(task, "getEndDisplayId")).intValue();
        Object changes = XposedHelpers.callMethod(info, "getChanges");
        if (!(changes instanceof List<?>)) return false;
        for (Object change : (List<?>) changes) {
            if (change == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(change, "hasFlags", 32))) continue;
            int start = ((Number) XposedHelpers.callMethod(change, "getStartRotation")).intValue();
            int end = ((Number) XposedHelpers.callMethod(change, "getEndRotation")).intValue();
            int target = ((Number) XposedHelpers.callMethod(change, "getEndDisplayId")).intValue();
            if (start >= 0 && end >= 0 && start != end && target == display) return true;
        }
        return false;
    }

    private FloatWindowRotationHooks() {}
}
