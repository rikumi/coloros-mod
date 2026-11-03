package com.rikumi.colorosmod.hooks;

import android.os.Build;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.util.List;
import static com.rikumi.colorosmod.XposedInit.*;

/** ColorOS 17: complete the native springs for a rotating floating window. */
final class FloatWindowRotationHooks {
    private static final String SPRING = "com.oplus.flexibletask.animation.MultiSpringAnimation";
    private static final String ROTATION = "com.android.wm.shell.transition.OplusFlexibleRotationAnimation";
    private static final String FIX_ROTATION = "com.oplus.flexibletask.OplusFlexibleRotationSpringAnimation";
    private static final String MARKER = "colorosmod_float_window_screen_rotation";

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT < 37) return;
        try {
            Class<?> spring = XposedHelpers.findClass(SPRING, lp.classLoader);
            // The frame callback is dispatched through AnimationHandler's interface.
            // start/addEndListener can be inlined into precompiled Shell callers.
            XposedHelpers.findAndHookMethod(spring, "doAnimationFrame", long.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    if (!readBoolCached(KEY_FLOAT_WINDOW_DISABLE_ROTATION_ANIMATION, false)) return;
                    Object owner = XposedHelpers.getAdditionalInstanceField(p.thisObject, MARKER);
                    if (owner == null) {
                        Object listeners = XposedHelpers.getObjectField(p.thisObject, "mEndListeners");
                        if (!(listeners instanceof List<?>)) return;
                        for (Object callback : (List<?>) listeners) {
                            owner = rotationOwner(callback);
                            if (owner != null) {
                                XposedHelpers.setAdditionalInstanceField(p.thisObject, MARKER, owner);
                                break;
                            }
                        }
                    }
                    // Evaluate the current transition at playback, not registration:
                    // rotation owners and their springs can be reused/reversed.
                    if (owner == null) return;
                    boolean suppress = isScreenRotation(owner);
                    long previousFrame = XposedHelpers.getLongField(p.thisObject, "mLastFrameTime");
                    if (previousFrame == 0L) {
                        Object change = XposedHelpers.getObjectField(owner, "mChange");
                        log("floating-window rotation frame-v2: owner=" + owner.getClass().getSimpleName()
                                + " start=" + (change == null ? "null" : XposedHelpers.callMethod(change, "getStartRotation"))
                                + " end=" + (change == null ? "null" : XposedHelpers.callMethod(change, "getEndRotation"))
                                + " suppress=" + suppress);
                    }
                    if (suppress) {
                        XposedHelpers.callMethod(p.thisObject, "skipToEnd");
                        // The native first-frame branch publishes START values and
                        // returns before inspecting mEndRequest. Enter the regular
                        // frame branch so even the first displayed frame is final.
                        if (previousFrame == 0L) {
                            long frameTime = ((Number) p.args[0]).longValue();
                            XposedHelpers.setLongField(p.thisObject, "mLastFrameTime", frameTime == 1L ? -1L : frameTime - 1L);
                        }
                    }
                }
            });
            log("floating-window rotation frame-v2 hook ready");
        } catch (Throwable error) { log("floating-window screen rotation hook unavailable: " + error); }
    }

    private static Object rotationOwner(Object callback) throws IllegalAccessException {
        if (callback == null) return null;
        for (Class<?> c = callback.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object value = field.get(callback);
                if (value != null && (ROTATION.equals(value.getClass().getName())
                        || FIX_ROTATION.equals(value.getClass().getName()))) return value;
            }
        }
        return null;
    }

    static boolean isScreenRotation(Object owner) {
        Object task = XposedHelpers.getObjectField(owner, "mChange");
        if (task == null) return false;
        // Fixed-rotation and TDA transitions do not require a FLAG_IS_DISPLAY
        // node. Both native owners take their rotation from this task change.
        // This method is called only after identifying one of the two native
        // floating-window owners, so fullscreen rotation remains untouched.
        if (rotationChanges(task)) return true;
        Object info = XposedHelpers.getObjectField(owner, "mInfo");
        if (info == null) return false;
        int display = ((Number) XposedHelpers.callMethod(task, "getEndDisplayId")).intValue();
        Object changes = XposedHelpers.callMethod(info, "getChanges");
        if (!(changes instanceof List<?>)) return false;
        for (Object change : (List<?>) changes) {
            if (change == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(change, "hasFlags", 32))) continue;
            int target = ((Number) XposedHelpers.callMethod(change, "getEndDisplayId")).intValue();
            if (target == display && rotationChanges(change)) return true;
        }
        return false;
    }

    private static boolean rotationChanges(Object change) {
        int start = ((Number) XposedHelpers.callMethod(change, "getStartRotation")).intValue();
        int end = ((Number) XposedHelpers.callMethod(change, "getEndRotation")).intValue();
        return start >= 0 && end >= 0 && start != end;
    }

    private FloatWindowRotationHooks() {}
}
