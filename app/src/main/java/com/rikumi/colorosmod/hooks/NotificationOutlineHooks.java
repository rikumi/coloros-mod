package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.graphics.Canvas;
import android.view.View;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** 只下发系统通知描边参数，由 ViewBlurProxy / BlurDrawable 使用原生 shader 绘制。 */
public final class NotificationOutlineHooks {
    private static Class<?> adapter, backgroundClass;
    private static Object adapterInstance;
    private static boolean restoring;
    private static final WeakHashMap<Object, State> hosts = new WeakHashMap<>();

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            adapter = XposedHelpers.findClass("com.oplusos.systemui.common.util.GradientStrokeLineAdapter", pkg.classLoader);
            adapterInstance = XposedHelpers.getStaticObjectField(adapter, "INSTANCE");
            backgroundClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.NotificationBackgroundView", pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod("com.oplusos.systemui.common.blurability.ViewBlurProxy",
                    pkg.classLoader, "applyBlurConfig", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) { prepare(p.thisObject); }
                    });
            XposedHelpers.findAndHookDeclaredMethod(backgroundClass, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object ext = XposedHelpers.getObjectField(p.thisObject, "mExt");
                    Object proxy = XposedHelpers.callMethod(ext, "getViewBlurProxy");
                    if (proxy != null && prepare(proxy)) XposedHelpers.callMethod(proxy, "applyBlurConfig");
                }
            });
        } catch (Throwable t) { log("notification outline hook failed: " + t); }
    }

    public static void refresh() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (Object proxy : hosts.keySet().toArray()) {
                if (prepare(proxy)) {
                    try { XposedHelpers.callMethod(proxy, "applyBlurConfig"); }
                    catch (Throwable t) { log("notification outline refresh failed: " + t); }
                }
            }
        });
    }

    private static boolean prepare(Object proxy) {
        if (restoring) return false;
        try {
            View view = (View) XposedHelpers.callMethod(proxy, "getView");
            if (!backgroundClass.isInstance(view)) return false;
            State state = hosts.get(proxy);
            if (state == null) { state = new State(); hosts.put(proxy, state); }
            if (state.failed) return false;
            Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
            Object current = XposedHelpers.callMethod(config, "getGradientStrokeLineParam");
            if (state.config == null || state.config.get() != config || current != state.lines) {
                state.config = new WeakReference<>(config);
                state.original = current;
                state.configured = false;
            }
            if (!readBool(KEY_NOTIFICATION_OUTLINE_ENABLED, false)) {
                if (state.lines != null && current == state.lines) {
                    XposedHelpers.callMethod(config, "setGradientStrokeLineParam", state.original);
                    state.configured = false;
                    view.invalidate();
                    return true;
                }
                return false;
            }
            int width = (Integer) XposedHelpers.callMethod(view, "getActualWidth");
            int height = (Integer) XposedHelpers.callMethod(view, "getActualHeight");
            if (width <= 0 || height <= 0) return false;
            // 不改 CornerParams、pathProvider 和 radiusWeight，保留系统自然圆角曲率。
            Object corner = XposedHelpers.callMethod(config, "getGradientStrokeCornerParam");
            float radius = (Float) XposedHelpers.callMethod(corner, "getRadius");
            if (!state.configured || state.width != width || state.height != height || state.radius != radius) {
                if (state.lines == null) {
                    Object template = XposedHelpers.callStaticMethod(adapter, "getNotificationStrokeParamsTemplate", true);
                    state.lines = XposedHelpers.callMethod(template, "createParams");
                }
                XposedHelpers.callMethod(adapterInstance, "updateRatioAndSides", state.lines, width, height, radius);
                XposedHelpers.callMethod(config, "setGradientStrokeLineParam", state.lines);
                state.width = width;
                state.height = height;
                state.radius = radius;
                state.configured = true;
                return true;
            }
        } catch (Throwable t) {
            State state = hosts.get(proxy);
            if (state != null) state.failed = true;
            log("notification outline configuration failed: " + t);
        }
        return false;
    }

    /** 卸载 hook 前恢复配置，避免描边参数留在旧实例中。 */
    public static void cleanupForHotReload() {
        restoring = true;
        try {
            for (Object proxy : hosts.keySet().toArray()) {
                try {
                    State state = hosts.get(proxy);
                    Object config = state.config == null ? null : state.config.get();
                    if (config != null && state.lines != null && XposedHelpers.callMethod(config, "getGradientStrokeLineParam") == state.lines) {
                        XposedHelpers.callMethod(config, "setGradientStrokeLineParam", state.original);
                        XposedHelpers.callMethod(proxy, "applyBlurConfig");
                    }
                } catch (Throwable t) { log("notification outline cleanup failed: " + t); }
            }
        } finally { restoring = false; }
    }

    private static final class State {
        WeakReference<Object> config;
        Object original, lines;
        int width, height;
        float radius;
        boolean configured, failed;
    }
}
