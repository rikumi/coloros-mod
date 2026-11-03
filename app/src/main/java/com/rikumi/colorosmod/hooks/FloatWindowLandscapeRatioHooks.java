package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Keep the native fullscreen aspect ratio and native size calculation for landscape apps. */
final class FloatWindowLandscapeRatioHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> controller = XposedHelpers.findClass("com.android.server.wm.FlexibleTaskController", lp.classLoader);
            Class<?> zoom = XposedHelpers.findClass("com.android.server.wm.OplusZoomDisplay", lp.classLoader);
            // ColorOS 17 adds DisplayContent as the sixth argument. Match both
            // known signatures without allowing a missing overload to abort setup.
            int matched = 0;
            for (Method method : controller.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!method.getName().equals("fillFlexibleTaskInfo") || (args.length != 5 && args.length != 6)
                        || !args[0].getName().equals("com.android.server.wm.FlexibleTaskInfo$Builder")
                        || !args[1].getName().equals("android.graphics.Rect")
                        || !args[2].getName().equals("android.content.Intent")
                        || !args[3].getName().equals("android.content.pm.ActivityInfo")
                        || args[4] != boolean.class
                        || (args.length == 6 && !args[5].getName().equals("com.android.server.wm.DisplayContent"))) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable() || !Boolean.TRUE.equals(p.args[4])
                                || !readBoolCached(KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED, false)) return;
                        try {
                            Object info = p.getResult();
                            if (info == null) return;
                            Object display = XposedHelpers.callStaticMethod(zoom, "getInstance");
                            float ratio = landscapeRatio(p.thisObject, display);
                            if (!(ratio > 0f && ratio < 1f)) return;
                            float scale = ((Number) XposedHelpers.callMethod(display, "getDefaultFlexibleScale", ratio)).floatValue();
                            if (!(scale > 0f) || !Float.isFinite(scale)) return;
                            XposedHelpers.callMethod(info, "setRatio", ratio);
                            XposedHelpers.callMethod(info, "setScale", scale);
                            // The caller computes launch bounds from ratio/scale.
                            // Do not widen the rectangle using an unrelated scale.
                        } catch (Throwable error) { log("landscape ratio initialization failed: " + error); }
                    }
                });
                matched++;
            }
            if (matched == 0) log("landscape ratio: no supported fillFlexibleTaskInfo signature");
            XposedHelpers.findAndHookMethod(controller, "getFlexibleTaskAvailableRatioByActivity",
                    "com.android.server.wm.ActivityRecord", String.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !readBoolCached(KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED, false)) return;
                    try {
                        if (!(p.getResult() instanceof List<?>)) return;
                        List<?> nativeRatios = (List<?>) p.getResult();
                        // Native orientation filtering has already run. Only add
                        // landscape ratios when landscape is allowed for this app.
                        boolean landscape = false;
                        for (Object value : nativeRatios) {
                            if (value instanceof Number && ((Number) value).floatValue() > 0f
                                    && ((Number) value).floatValue() < 1f) landscape = true;
                        }
                        if (!landscape) return;
                        Object display = XposedHelpers.callStaticMethod(zoom, "getInstance");
                        float ratio = landscapeRatio(p.thisObject, display);
                        if (!(ratio > 0f && ratio < 1f)) return;
                        for (Object value : nativeRatios) {
                            if (value instanceof Number && Math.abs(((Number) value).floatValue() - ratio) < 0.001f) return;
                        }
                        List<Object> ratios = new ArrayList<>(nativeRatios);
                        ratios.add(ratio);
                        p.setResult(ratios);
                    } catch (Throwable error) { log("landscape ratio choices failed: " + error); }
                }
            });
        } catch (Throwable error) { log("landscape ratio hook unavailable: " + error); }
    }

    private static float landscapeRatio(Object controller, Object display) {
        int width = ((Number) XposedHelpers.callMethod(display, "getScreenWidth")).intValue();
        int height = ((Number) XposedHelpers.callMethod(display, "getScreenHeight")).intValue();
        if (width <= 0 || height <= 0) return 0f;
        float portraitRatio = ((Number) XposedHelpers.callMethod(controller,
                "getFlexibleTaskFullScreenRatio", width, height)).floatValue();
        return portraitRatio > 0f ? 1f / portraitRatio : 0f;
    }

    private FloatWindowLandscapeRatioHooks() {}
}
