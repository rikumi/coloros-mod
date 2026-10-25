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
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.WeakHashMap;

public final class ActiveTileOutlineHooks {
    private static Class<?> strokeUniforms;
    private static int activeAttr, inoperableAttr;
    private static Class<?> separateTileClass, separateSliderClass, nativeBlurClass;
    private static final ThreadLocal<DrawFrame> drawing = new ThreadLocal<>();

    private static final WeakHashMap<Drawable, Overlay> overlays = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> views = new WeakHashMap<>();
    private static final WeakHashMap<Drawable, Boolean> tracked = new WeakHashMap<>();
    private static final WeakHashMap<Drawable, Paint> colorPaints = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> legacyTiles = new WeakHashMap<>();
    private static final WeakHashMap<GradientDrawable, Stroke> legacyStrokes = new WeakHashMap<>();
    private static boolean legacySystemUi;

    private static final class Stroke {
        final int width;
        final ColorStateList color;
        Stroke(int width, ColorStateList color) { this.width = width; this.color = color; }
    }

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        if (XposedHelpers.findClassIfExists("com.oplus.posteffect.GradientStrokeLineParamsKt",
                pkg.classLoader) == null) {
            hookLegacy(pkg);
            return;
        }
        try {
            ContourLightShader.init(pkg.classLoader);
            strokeUniforms = XposedHelpers.findClass("com.oplus.posteffect.GradientStrokeLineParamsKt", pkg.classLoader);
            separateTileClass = XposedHelpers.findClassIfExists(
                    "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView", pkg.classLoader);
            separateSliderClass = XposedHelpers.findClassIfExists(
                    "com.oplus.systemui.plugins.qs.widget.OplusQsToggleSliderLayout", pkg.classLoader);
            nativeBlurClass = XposedHelpers.findClass("com.android.internal.graphics.drawable.BackgroundBlurDrawable", pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod("com.oplusos.systemui.common.blurability.ViewBlurProxy",
                    pkg.classLoader, "getBlurDrawable", Drawable.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            DrawFrame frame = drawing.get();
                            if (frame != null && frame.proxy == p.thisObject && !p.hasThrowable()
                                    && nativeBlurClass.isInstance(p.getResult()) && frame.canvas.isHardwareAccelerated())
                                frame.nativeBlurDrawn = true;
                        }
                    });
            Class<?> flags = XposedHelpers.findClass("com.oplus.systemui.qs.base.res.model.TileViewFlag", pkg.classLoader);
            activeAttr = (Integer) XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(flags, "Active"), "getAttr");
            inoperableAttr = (Integer) XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(flags, "Inoperable"), "getAttr");
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable",
                    pkg.classLoader, "draw", Canvas.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Drawable drawable = (Drawable) p.thisObject;
                            if (MergedRadiantHooks.iconOnlyBackground(drawable)) return;
                            Integer radiantColor = MergedRadiantHooks.activeBackground(drawable);
                            boolean customColor = readBool(KEY_QS_ACTIVE_COLOR_ENABLED, false);
                            if (radiantColor == null && !customColor && !readBool(KEY_QS_ACTIVE_OUTLINE_ENABLED, false)) return;
                            if (!tracked.containsKey(drawable)) {
                                Object autoBlur = XposedHelpers.getObjectField(drawable, "autoBlurDrawable");
                                Object proxy = XposedHelpers.callMethod(autoBlur, "getViewBlurProxy");
                                View owner = (View) XposedHelpers.callMethod(proxy, "getView");
                                if (owner != null) { views.put(owner, true); tracked.put(drawable, true); }
                            }
                            if ((radiantColor != null || customColor) && isActive(drawable)) {
                                int original = XposedHelpers.getIntField(drawable, "maskColor");
                                p.setObjectExtra("originalMaskColor", original);
                                XposedHelpers.setIntField(drawable, "maskColor", radiantColor != null ? radiantColor : readInt(KEY_QS_ACTIVE_COLOR, DEFAULT_QS_ACTIVE_COLOR));
                            }
                            if (readBool(KEY_QS_ACTIVE_OUTLINE_ENABLED, false)) {
                                Object autoBlur = XposedHelpers.getObjectField(drawable, "autoBlurDrawable");
                                Object proxy = XposedHelpers.callMethod(autoBlur, "getViewBlurProxy");
                                View owner = (View) XposedHelpers.callMethod(proxy, "getView");
                                int mask = XposedHelpers.getIntField(drawable, "maskColor");
                                int alpha = drawable.getAlpha();
                                // Match MixColorTileDrawable.draw's effective mask alpha.
                                int maskAlpha = ((mask >>> 24) * (alpha + (alpha >> 7))) >> 8;
                                DrawFrame frame = new DrawFrame(drawing.get(), proxy, (Canvas) p.args[0],
                                        isSeparate(owner), maskAlpha);
                                p.setObjectExtra("contourDrawFrame", frame);
                                drawing.set(frame);
                            }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            DrawFrame frame = (DrawFrame) p.getObjectExtra("contourDrawFrame");
                            try {
                                Object original = p.getObjectExtra("originalMaskColor");
                                if (original instanceof Integer color) XposedHelpers.setIntField(p.thisObject, "maskColor", color);
                                if (p.hasThrowable() || !readBool(KEY_QS_ACTIVE_OUTLINE_ENABLED, false)) return;
                                Drawable drawable = (Drawable) p.thisObject;
                                if (MergedRadiantHooks.iconOnlyBackground(drawable) || !isActive(drawable) || drawable.getAlpha() == 0) return;

                                if (!shouldSupplement(frame)) return;
                                try { drawOverlay(drawable, (Canvas) p.args[0]); }
                                catch (Throwable t) {
                                    if (!overlays.containsKey(drawable) || overlays.get(drawable) != null)
                                        log("active tile outline draw failed: " + t);
                                    overlays.put(drawable, null);
                                }
                            } finally {
                                if (frame != null) {
                                    if (frame.previous == null) drawing.remove();
                                    else drawing.set(frame.previous);
                                }
                            }
                        }
                    });
            // 非模糊模式的卡片使用 GradientTileDrawable，直接替换本次填色，不改原生颜色状态。
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.base.res.drawable.GradientTileDrawable",
                    pkg.classLoader, "draw", Canvas.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Drawable drawable = (Drawable) p.thisObject;
                            if (MergedRadiantHooks.iconOnlyBackground(drawable)) return;
                            Integer radiantColor = MergedRadiantHooks.activeBackground(drawable);
                            if ((radiantColor == null && !readBool(KEY_QS_ACTIVE_COLOR_ENABLED, false)) || !isActive(drawable)) return;
                            if (!tracked.containsKey(drawable)) {
                                Drawable.Callback callback = drawable.getCallback();
                                for (int i = 0; callback instanceof Drawable parent && i < 8; i++) callback = parent.getCallback();
                                if (callback instanceof View view) { views.put(view, true); tracked.put(drawable, true); }
                            }
                            Paint paint = colorPaints.get(drawable);
                            if (paint == null) { paint = new Paint(Paint.ANTI_ALIAS_FLAG); colorPaints.put(drawable, paint); }
                            int color = radiantColor != null ? radiantColor : readInt(KEY_QS_ACTIVE_COLOR, DEFAULT_QS_ACTIVE_COLOR);
                            paint.setColor(color);
                            paint.setAlpha((color >>> 24) * drawable.getAlpha() / 255);
                            Drawable original = (Drawable) XposedHelpers.getObjectField(drawable, "colorDrawable");
                            paint.setColorFilter(radiantColor != null ? null : original.getColorFilter());
                            ((Canvas) p.args[0]).drawPath((Path) XposedHelpers.getObjectField(drawable, "path"), paint);
                            p.setResult(null);
                        }
                    });
        } catch (Throwable t) { log("active tile outline hook failed: " + t); }
    }

    private static void hookLegacy(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            separateTileClass = XposedHelpers.findClassIfExists(
                    "com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView", pkg.classLoader);
            if (separateTileClass == null) return;
            legacySystemUi = true;
            Class<?> tileState = XposedHelpers.findClass("com.android.systemui.plugins.qs.QSTile$State",
                    pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(separateTileClass, "handleStateChanged", tileState,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View tile = (View) p.thisObject;
                            if (!legacyMerged(tile)) return;
                            legacyTiles.put(tile, true);
                        }
                    });
            for (String method : new String[]{"updateBackground", "updateBackgroundMask"}) {
                XposedHelpers.findAndHookMethod(separateTileClass, method, Drawable.class,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                View tile = (View) p.thisObject;
                                if (!legacyMerged(tile)) return;
                                legacyTiles.put(tile, true);
                                applyLegacyStroke((Drawable) p.args[0], tile);
                            }
                        });
            }
            XposedHelpers.findAndHookMethod(separateTileClass, "getActiveColorWithDarkMode",
                    android.content.Context.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View tile = (View) p.thisObject;
                            if (legacyMerged(tile) && readBool(KEY_QS_ACTIVE_COLOR_ENABLED, false))
                                p.setResult(readInt(KEY_QS_ACTIVE_COLOR, DEFAULT_QS_ACTIVE_COLOR));
                        }
                    });
            log("HOOK OK legacy QS active color and contour");
        } catch (Throwable t) { log("legacy active tile contour hook failed: " + t); }
    }

    private static boolean legacyMerged(View tile) {
        for (View current = tile; current != null;
                current = current.getParent() instanceof View parent ? parent : null) {
            String name = current.getClass().getName();
            if (name.equals("com.oplus.systemui.qs.OplusQSTileMediaContainer")
                    || name.equals("com.oplus.systemui.qs.widget.OplusHeaderTileLayout")
                    || name.equals("com.android.systemui.qs.TileLayout")
                    || name.equals("com.android.systemui.qs.SideLabelTileLayout")) return true;
        }
        return false;
    }

    private static void applyLegacyStroke(Drawable drawable, View tile) {
        if (drawable == null) return;
        while (drawable instanceof DrawableWrapper wrapper && wrapper.getDrawable() != null)
            drawable = wrapper.getDrawable();
        if (!(drawable instanceof GradientDrawable gradient)) return;
        int state;
        try { state = XposedHelpers.getIntField(tile, "mTileIconState"); }
        catch (Throwable ignored) { state = 0; }
        boolean active = state == 2 || state == 101;
        boolean enabled = active && readBool(KEY_QS_ACTIVE_OUTLINE_ENABLED, false);
        Stroke original = legacyStrokes.get(gradient);
        if (original == null) {
            original = readLegacyStroke(gradient);
            legacyStrokes.put(gradient, original);
        }
        if (enabled) {
            int px = Math.max(1, Math.round(1.5f * tile.getResources().getDisplayMetrics().density));
            gradient.setStroke(px, readInt(KEY_QS_ACTIVE_COLOR, DEFAULT_QS_ACTIVE_COLOR));
        } else {
            gradient.setStroke(original.width, original.color == null ? ColorStateList.valueOf(0)
                    : original.color);
        }
        gradient.invalidateSelf();
    }

    private static Stroke readLegacyStroke(GradientDrawable gradient) {
        try {
            Object state = XposedHelpers.getObjectField(gradient, "mGradientState");
            int width = XposedHelpers.getIntField(state, "mStrokeWidth");
            ColorStateList color = (ColorStateList) XposedHelpers.getObjectField(state, "mStrokeColor");
            return new Stroke(width, color);
        } catch (Throwable t) {
            log("legacy tile background stroke state unavailable: " + t);
            return new Stroke(0, ColorStateList.valueOf(0));
        }
    }

    private static boolean isSeparate(View owner) {
        for (View view = owner; view != null; view = view.getParent() instanceof View parent ? parent : null)
            if ((separateTileClass != null && separateTileClass.isInstance(view))
                    || (separateSliderClass != null && separateSliderClass.isInstance(view))) return true;
        return false;
    }

    private static boolean shouldSupplement(DrawFrame frame) {
        return frame == null || !frame.separate || !frame.nativeBlurDrawn || frame.maskAlpha == 255;
    }

    private static final class DrawFrame {
        final DrawFrame previous;
        final Object proxy;
        final Canvas canvas;
        final boolean separate;
        final int maskAlpha;
        boolean nativeBlurDrawn;
        DrawFrame(DrawFrame previous, Object proxy, Canvas canvas, boolean separate, int maskAlpha) {
            this.previous = previous; this.proxy = proxy; this.canvas = canvas;
            this.separate = separate; this.maskAlpha = maskAlpha;
        }
    }

    private static boolean isActive(Drawable drawable) {
        for (int state : drawable.getState()) if (state == activeAttr || state == inoperableAttr) return true;
        return false;
    }

    public static void refresh() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (legacySystemUi) {
                for (View tile : legacyTiles.keySet().toArray(new View[0])) {
                    if (tile == null) continue;
                    try {
                        Object backgroundProxy = XposedHelpers.getObjectField(tile, "mBackgroundProxy");
                        XposedHelpers.callMethod(backgroundProxy, "refreshViewBackground");
                    } catch (Throwable t) { tile.invalidate(); }
                }
                return;
            }
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
