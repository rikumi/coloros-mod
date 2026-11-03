package com.rikumi.colorosmod.hooks;

import android.os.Build;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import static com.rikumi.colorosmod.XposedInit.*;

/** ColorOS 17 Launcher 17.3.12: use the same NONE branch as its RUS rotation switch. */
final class LandscapeAppRotationHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT < 37 || !"com.android.launcher".equals(lp.processName)) return;
        try {
            Class<?> utility = XposedHelpers.findClass(
                    "com.oplus.animation.core.util.RotationAnimUtil", lp.classLoader);
            Class<?> direction = XposedHelpers.findClass(
                    utility.getName() + "$RotationDirection", lp.classLoader);
            Object none = null;
            Object[] constants = direction.getEnumConstants();
            if (constants != null) {
                for (Object value : constants) {
                    if (value instanceof Enum<?> item && "NONE".equals(item.name())) none = value;
                }
            }
            if (none == null) throw new IllegalStateException("RotationDirection.NONE missing");

            // b(AppRotationDirectionParams) in 17.3.12. Resolve the signature rather
            // than the obfuscated method/parameter names; exclude f(RotationDirection),
            // which reverses an existing direction during predictive-back cancellation.
            Method target = null;
            for (Method method : utility.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != direction
                        || args.length != 1 || args[0] == direction
                        || !args[0].getName().startsWith(utility.getName() + "$")) continue;
                if (target != null) throw new IllegalStateException("Ambiguous rotation direction method");
                target = method;
            }
            if (target == null) throw new NoSuchMethodException("getAppRotationDirection");
            final Object noRotation = none;
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args[0] != null && readBool(KEY_DISABLE_LANDSCAPE_APP_ROTATION, false)) {
                        // Keep the native launch/close animation, surface transforms,
                        // finish callbacks and screen-orientation handling intact.
                        p.setResult(noRotation);
                    }
                }
            });
        } catch (Throwable error) { log("landscape app rotation hook unavailable: " + error); }
    }

    private LandscapeAppRotationHooks() {}
}
