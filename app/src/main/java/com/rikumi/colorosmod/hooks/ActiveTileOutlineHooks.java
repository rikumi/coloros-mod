package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.view.View;
import android.graphics.drawable.Drawable;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.WeakHashMap;

/** 使用系统当前有效的质感描边参数，在激活态色块之后绘制轮廓光。 */
public final class ActiveTileOutlineHooks {
    private static Class<?> strokeUniforms;
    private static int activeAttr, inoperableAttr;
    private static final WeakHashMap<Drawable, Overlay> overlays = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> views = new WeakHashMap<>();
    private static final WeakHashMap<Drawable, Boolean> tracked = new WeakHashMap<>();
    private static final WeakHashMap<Drawable, Paint> colorPaints = new WeakHashMap<>();

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            ContourLightShader.init(pkg.classLoader);
            strokeUniforms = XposedHelpers.findClass("com.oplus.posteffect.GradientStrokeLineParamsKt", pkg.classLoader);
            Class<?> flags = XposedHelpers.findClass("com.oplus.systemui.qs.base.res.model.TileViewFlag", pkg.classLoader);
            activeAttr = (Integer) XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(flags, "Active"), "getAttr");
            inoperableAttr = (Integer) XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(flags, "Inoperable"), "getAttr");
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable",
                    pkg.classLoader, "draw", Canvas.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            boolean customColor = readBool(KEY_QS_ACTIVE_COLOR_ENABLED, false);
                            if (!customColor && !readBool(KEY_QS_ACTIVE_OUTLINE_ENABLED, false)) return;
                            Drawable drawable = (Drawable) p.thisObject;
                            if (!tracked.containsKey(drawable)) {
                                Object autoBlur = XposedHelpers.getObjectField(drawable, "autoBlurDrawable");
                                Object proxy = XposedHelpers.callMethod(autoBlur, "getViewBlurProxy");
                                View owner = (View) XposedHelpers.callMethod(proxy, "getView");
                                if (owner != null) { views.put(owner, true); tracked.put(drawable, true); }
                            }
                            if (!customColor || !isActive(drawable)) return;
                            int original = XposedHelpers.getIntField(drawable, "maskColor");
                            p.setObjectExtra("originalMaskColor", original);
                            XposedHelpers.setIntField(drawable, "maskColor", readInt(KEY_QS_ACTIVE_COLOR, 0xff00b4d8));
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Object original = p.getObjectExtra("originalMaskColor");
                            if (original instanceof Integer color) XposedHelpers.setIntField(p.thisObject, "maskColor", color);
                            if (p.hasThrowable() || !readBool(KEY_QS_ACTIVE_OUTLINE_ENABLED, false)) return;
                            Drawable drawable = (Drawable) p.thisObject;
                            if (!isActive(drawable) || drawable.getAlpha() == 0) return;
                            try { drawOverlay(drawable, (Canvas) p.args[0]); }
                            catch (Throwable t) {
                                // 出错的实例停止尝试，避免绘制循环中反复创建着色器或输出日志。
                                if (!overlays.containsKey(drawable) || overlays.get(drawable) != null)
                                    log("active tile outline draw failed: " + t);
                                overlays.put(drawable, null);
                            }
                        }
                    });
            // 非模糊模式的卡片使用 GradientTileDrawable，直接替换本次填色，不改原生颜色状态。
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.base.res.drawable.GradientTileDrawable",
                    pkg.classLoader, "draw", Canvas.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Drawable drawable = (Drawable) p.thisObject;
                            if (!readBool(KEY_QS_ACTIVE_COLOR_ENABLED, false) || !isActive(drawable)) return;
                            if (!tracked.containsKey(drawable)) {
                                Drawable.Callback callback = drawable.getCallback();
                                for (int i = 0; callback instanceof Drawable parent && i < 8; i++) callback = parent.getCallback();
                                if (callback instanceof View view) { views.put(view, true); tracked.put(drawable, true); }
                            }
                            Paint paint = colorPaints.get(drawable);
                            if (paint == null) { paint = new Paint(Paint.ANTI_ALIAS_FLAG); colorPaints.put(drawable, paint); }
                            int color = readInt(KEY_QS_ACTIVE_COLOR, 0xff00b4d8);
                            paint.setColor(color);
                            paint.setAlpha((color >>> 24) * drawable.getAlpha() / 255);
                            Drawable original = (Drawable) XposedHelpers.getObjectField(drawable, "colorDrawable");
                            paint.setColorFilter(original.getColorFilter());
                            ((Canvas) p.args[0]).drawPath((Path) XposedHelpers.getObjectField(drawable, "path"), paint);
                            p.setResult(null);
                        }
                    });
        } catch (Throwable t) { log("active tile outline hook failed: " + t); }
    }

    private static boolean isActive(Drawable drawable) {
        for (int state : drawable.getState()) if (state == activeAttr || state == inoperableAttr) return true;
        return false;
    }

    public static void refresh() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (View view : views.keySet().toArray(new View[0])) if (view != null) view.invalidate();
        });
    }

    private static void drawOverlay(Drawable drawable, Canvas canvas) {
        if (overlays.containsKey(drawable) && overlays.get(drawable) == null) return;
        Object autoBlur = XposedHelpers.getObjectField(drawable, "autoBlurDrawable");
        Object proxy = XposedHelpers.callMethod(autoBlur, "getViewBlurProxy");
        Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
        Object lines = XposedHelpers.callMethod(config, "getGradientStrokeLineParam");
        // 系统关闭质感轮廓光时参数为空或无效，不自行启用系统效果。
        if (lines == null || !(Boolean) XposedHelpers.callMethod(lines, "isValid")) return;
        Object corner = XposedHelpers.callMethod(config, "getGradientStrokeCornerParam");
        Object type = XposedHelpers.callMethod(corner, "getType");
        Rect bounds = drawable.getBounds();
        if (bounds.isEmpty()) return;
        Overlay overlay = overlays.get(drawable);
        if (overlay == null || overlay.type != type) {
            overlay = new Overlay(type, ContourLightShader.source(type));
            overlays.put(drawable, overlay);
        }
        // 参数对象由系统原地更新，因此比较内容及边界，不能只比较对象身份。
        int hash = 31 * lines.hashCode() + corner.hashCode();
        if (!overlay.configured || overlay.paramsHash != hash || !overlay.bounds.equals(bounds)) {
            XposedHelpers.callStaticMethod(strokeUniforms, "setGradientStrokeLineUniform", overlay.shader, lines);
            overlay.shader.setFloatUniform("u_size", bounds.width(), bounds.height());
            overlay.shader.setFloatUniform("u_origin", bounds.left, bounds.top);
            overlay.shader.setFloatUniform("u_corner", (Float) XposedHelpers.callMethod(corner, "getRadius"));
            overlay.shader.setFloatUniform("u_weight", (Float) XposedHelpers.callMethod(corner, "getWeight"));
            overlay.glowPaint.setShader(new LinearGradient(0f, bounds.top, 0f,
                    bounds.top + Math.max(1f, bounds.height() * .6f),
                    new int[]{0x28ffffff, 0x12ffffff, 0x00ffffff}, new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP));
            overlay.bounds.set(bounds);
            overlay.paramsHash = hash;
            overlay.configured = true;
        }
        overlay.paint.setAlpha(drawable.getAlpha());
        // 复用系统实际路径，兼容圆形磁贴、卡片圆角及比例缩放。
        Path path = (Path) XposedHelpers.getObjectField(drawable, "path");
        // 轮廓光参数有效且已激活才会到达这里；渐变与轮廓光共用严格的系统开关条件。
        View owner = (View) XposedHelpers.callMethod(proxy, "getView");
        boolean slider = false;
        for (View view = owner; view != null; view = view.getParent() instanceof View parent ? parent : null) {
            String name = view.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("seekbar") || name.contains("slider") || name.endsWith(".level")) { slider = true; break; }
        }
        if (!slider) {
            overlay.glowPaint.setAlpha(drawable.getAlpha());
            canvas.drawPath(path, overlay.glowPaint);
        }
        canvas.drawPath(path, overlay.paint);
    }

    private static final class Overlay {
        final Object type;
        final RuntimeShader shader;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect bounds = new Rect();
        int paramsHash;
        boolean configured;
        Overlay(Object type, String source) {
            this.type = type;
            shader = new RuntimeShader(source);
            paint.setShader(shader);
        }
    }
}
