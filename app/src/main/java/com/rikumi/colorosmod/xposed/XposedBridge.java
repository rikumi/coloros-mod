package com.rikumi.colorosmod.xposed;

import android.util.Log;

import java.lang.reflect.Member;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * 兼容层入口: 持有框架注入的 XposedInterface, 并提供旧版 XposedBridge 的
 * log / hookMethod / hookAllConstructors 三个能力。
 *
 * 新版(libxposed)API 只有 OkHttp 风格的拦截器链, 不再提供 XposedHelpers 之类的便利接口;
 * 本包用最小代价把旧语义还原出来, 使 hooks 下的业务代码保持原样。
 */
public final class XposedBridge {

    private static volatile XposedInterface sFramework;

    private XposedBridge() {
    }

    /**
     * 由模块入口 (XposedInit) 在 onModuleLoaded 中调用。
     * 在此之前不能做任何 hook —— 框架尚未把 XposedInterface 挂到模块上。
     */
    public static void attachFramework(XposedInterface framework) {
        sFramework = framework;
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
}
