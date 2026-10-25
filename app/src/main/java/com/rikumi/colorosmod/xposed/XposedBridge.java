package com.rikumi.colorosmod.xposed;

import android.util.Log;

import java.lang.reflect.Member;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

public final class XposedBridge {

    private static volatile XposedInterface sFramework;

    private XposedBridge() {
    }

    public static void attachFramework(XposedInterface framework) {
        sFramework = framework;
    }

    public static void detachFramework() {
        sFramework = null;
    }

    static XposedInterface framework() {
        XposedInterface fx = sFramework;
        if (fx == null) {
            throw new IllegalStateException("Xposed framework not attached yet");
        }
        return fx;
    }

    public static void log(String msg) {
        XposedInterface fx = sFramework;
        if (fx != null) {
            fx.log(Log.ERROR, "ColorOSMod", msg);
        } else {
            // 框架还没挂上时至少还能在 logcat 里看到, 避免静默丢日志。
            Log.e("ColorOSMod", msg);
        }
    }

    public static void log(Throwable t) {
        log(Log.getStackTraceString(t));
    }

    public static void hookMethod(Member hookMethod, XC_MethodHook callback) {
        XposedHelpers.hookMethod(hookMethod, callback);
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        return XposedHelpers.hookAllConstructors(hookClass, callback);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                            XC_MethodHook callback) {
        return XposedHelpers.hookAllMethods(hookClass, methodName, callback);
    }
}
