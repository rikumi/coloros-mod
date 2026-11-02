package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.rikumi.colorosmod.TileBackgroundEnhancement;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.*;

/** ColorOS 17 card tint brightness, preserving native coverage and active tiles. */
final class MaterialBackgroundBrightnessHooks {
    private static final int NOTIFICATION = 1, TILE = 2;
    private static final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    // BlurMixConfig has structural equality and a mutable hashCode. Use weak identity keys.
    private static final Map<Key, Integer> configs = new HashMap<>();
    private static final Map<Key, WeakReference<Object>> shaderSources = new HashMap<>();
    private static final WeakHashMap<Object, Boolean> tileProxies = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> activeSliderProxies = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> notificationProxies = new WeakHashMap<>();
    private static final WeakHashMap<Drawable, Boolean> solidTiles = new WeakHashMap<>();
    private static final WeakHashMap<Object, WeakReference<Object>> notificationConfigs = new WeakHashMap<>();
    private static Class<?> shaderParam, shaderMix, groupOverlay, qsSeekBar, platformRenderer, notificationRow;
    private static Class<?> tileViewTags;
    private static int activeTileAttr, inoperableTileAttr;
    private static final WeakHashMap<Object, WeakReference<View>> groupHeaderRenderers = new WeakHashMap<>();

    private static boolean restoring;
    private static String lastSettings;
    private static int lastNotificationDiagnostic = Integer.MIN_VALUE, lastTileDiagnostic = Integer.MIN_VALUE;
    private static int lastOpacityDiagnostic = Integer.MIN_VALUE;
    private static int lastTileOpacityDiagnostic = Integer.MIN_VALUE;
    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object value) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || (other instanceof Key key && get() != null && get() == key.get());
        }
    }
    private static synchronized void mark(Object config, int kind) {
        // The ROM strips InnerClasses metadata: getSimpleName() may include the
        // enclosing binary name (BlurMixConfig$BlurMixMultiWithShader).
        if (config == null || shaderMix == null || !shaderMix.isInstance(config)) return;
        for (Object old; (old = queue.poll()) != null;) {
            configs.remove(old);
            shaderSources.remove(old);
        }
        if (configs.put(new Key(config), kind) == null)
            shaderSources.put(new Key(XposedHelpers.callMethod(config, "getMixMultiShaderParams")), new WeakReference<>(config));
    }
    private static synchronized Integer kind(Object config) { return configs.get(new Key(config)); }
    static void markNotification(Object config) { mark(config, NOTIFICATION); }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            shaderParam = XposedHelpers.findClass("com.oplus.posteffect.agsl.ShaderBlendParam", lp.classLoader);
            shaderMix = XposedHelpers.findClass("com.oplusos.systemui.common.blurability.BlurMixConfig$BlurMixMultiWithShader", lp.classLoader);
            restoring = false;
            notificationRow = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader);
            groupOverlay = XposedHelpers.findClass("com.oplus.systemui.statusbar.notification.view.GroupOverlayContainerView", lp.classLoader);
            qsSeekBar = XposedHelpers.findClass("com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar", lp.classLoader);
            tileViewTags = XposedHelpers.findClass("com.oplus.systemui.qs.base.res.util.ViewTagUtils", lp.classLoader);
            Class<?> flags = XposedHelpers.findClass("com.oplus.systemui.qs.base.res.model.TileViewFlag", lp.classLoader);
            activeTileAttr = (Integer) XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(flags, "Active"), "getAttr");
            inoperableTileAttr = (Integer) XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(flags, "Inoperable"), "getAttr");
            Class<?> renderer = XposedHelpers.findClass(
                    "com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable", lp.classLoader);
            platformRenderer = renderer;
            TileBackgroundSaturationHooks.hook(lp.classLoader);
            // The source list identifies the surface even if the caller's
            // PlatformBlurDrawable.applyBlurConfig was inlined by ART.
            Class<?> blend = XposedHelpers.findClass("com.oplus.posteffect.drawable.BlendDrawable", lp.classLoader);
            XposedHelpers.findAndHookMethod(blend, "setShaderBlendParams", ArrayList.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    WeakReference<Object> source;
                    synchronized (MaterialBackgroundBrightnessHooks.class) {
                        source = shaderSources.get(new Key(p.args[0]));
                    }
                    Object config = source == null ? null : source.get();
                    if (config != null) applySaturation(p.thisObject, kind(config));
                }
            });
            // Modify the actual renderer output. The helper may have been inlined;
            // tagging its input and hooking the helper does not guarantee execution.
            XposedBridge.hookAllMethods(renderer, "applyBlurConfig", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.getResult() == null) return;
                    applyToRenderer(p.thisObject, XposedHelpers.callMethod(p.args[0], "getPlatformMixConfig"), (Float) p.args[1]);
                }
            });
            XposedBridge.hookAllMethods(renderer, "changeBlurColor", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) applyToRenderer(p.thisObject, p.args[0], 1f);
                }
            });
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp", lp.classLoader,
                    "draw", Canvas.class, android.graphics.drawable.Drawable.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object proxy = XposedHelpers.callMethod(p.thisObject, "getViewBlurProxy");
                    if (proxy == null) return;
                    refreshNotificationProxy(proxy);
                }
            });
            Class<?> tiles = XposedHelpers.findClass(
                    "com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable", lp.classLoader);
            XposedBridge.hookAllMethods(tiles, "updatePlatformMixConfig", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    markTileConfig(p.args[0], p.args[1]);
                    tileProxies.put(p.args[0], true);
                }
            });
            // Also discover already visible tiles after hot reload and track initial configs.
            XposedHelpers.findAndHookMethod(tiles, "draw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object drawable = XposedHelpers.getObjectField(p.thisObject, "autoBlurDrawable");
                    Object proxy = XposedHelpers.callMethod(drawable, "getViewBlurProxy");
                    Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
                    Object mix = XposedHelpers.callMethod(config, "getPlatformMixConfig");
                    Integer before = kind(mix);
                    markTileConfig(proxy, mix);
                    if (tileProxies.put(proxy, true) == null || !java.util.Objects.equals(before, kind(mix)))
                        XposedHelpers.callMethod(proxy, "applyBlurConfig");
                }
            });
            // Highlight Wi-Fi/Bluetooth cards can use a solid GradientTileDrawable,
            // bypassing the blur renderer entirely. Reuse its native path/filter.
            XposedHelpers.findAndHookMethod("com.oplus.systemui.qs.base.res.drawable.GradientTileDrawable",
                    lp.classLoader, "draw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Drawable tile = (Drawable) p.thisObject;
                    if (drawSolidTile(tile, (Canvas) p.args[0])) p.setResult(null);
                }
            });
            // Merged notification surfaces and both layers of the QS vertical
            // sliders use AutoBlurDrawable directly, bypassing MixColorTileDrawable.
            XposedHelpers.findAndHookMethod(
                    "com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable", lp.classLoader,
                    "draw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object proxy = XposedHelpers.callMethod(p.thisObject, "getViewBlurProxy");
                    Object view = XposedHelpers.callMethod(proxy, "getView");
                    int kind;
                    WeakHashMap<Object, Boolean> tracked;
                    if (qsSeekBar.isInstance(view)) {
                        if (isActiveSeekBarProxy(proxy, view)) {
                            Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
                            Object mix = XposedHelpers.callMethod(config, "getPlatformMixConfig");
                            boolean wasAdjusted = tileProxies.remove(proxy) != null;
                            synchronized (MaterialBackgroundBrightnessHooks.class) {
                                wasAdjusted |= configs.remove(new Key(mix)) != null;
                            }
                            if (wasAdjusted) XposedHelpers.callMethod(proxy, "applyBlurConfig");
                            if (activeSliderProxies.put(proxy, true) == null) diagnostic("active slider foreground preserved");
                            return;
                        }
                        kind = TILE; tracked = tileProxies;
                    }
                    else if (groupOverlay.isInstance(view)) { kind = NOTIFICATION; tracked = notificationProxies; }
                    else return;
                    Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
                    Object mix = XposedHelpers.callMethod(config, "getPlatformMixConfig");
                    boolean needsApply = tracked.put(proxy, true) == null || kind(mix) == null;
                    markProxy(proxy, kind);
                    if (needsApply && kind(mix) != null) {
                        diagnostic("background discovered: kind=" + kind + ", view=" + view.getClass().getName());
                        XposedHelpers.callMethod(proxy, "applyBlurConfig");
                    }
                }
            });
            // Collapsed group headers wrap a PlatformBlurDrawable owned by an
            // unattached helper View, so neither row-background hook can discover it.
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp$NotificationHeaderMaskColorDrawable",
                    lp.classLoader, "draw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object drawable = XposedHelpers.getObjectField(p.thisObject, "drawable");
                    if (!platformRenderer.isInstance(drawable) || groupHeaderRenderers.containsKey(drawable)) return;
                    Object mask = XposedHelpers.getObjectField(p.thisObject, "maskColor");
                    if (!NotificationLayoutCompat.hasField(mask.getClass(), "f$0")) return;
                    Object extension = XposedHelpers.getObjectField(mask, "f$0");
                    Object container = XposedHelpers.callMethod(extension, "getNotificationChildrenContainer");
                    Object row = XposedHelpers.callMethod(container, "getContainingNotification");
                    if (!(row instanceof View owner)) return;
                    groupHeaderRenderers.put(drawable, new WeakReference<>(owner));
                    refreshGroupHeader(drawable, owner);
                    diagnostic("group header background discovered");
                }
            });
            diagnostic("renderer hooks installed");
        } catch (Throwable error) { log("material background brightness hook failed: " + error); }
    }

    private static void applySaturation(Object drawable, Integer kind) {
        TileBackgroundSaturationHooks.Surface surface = null;
        if (!restoring) {
            if (Integer.valueOf(TILE).equals(kind)) surface = TileBackgroundSaturationHooks.Surface.TILE;
            else if (Integer.valueOf(NOTIFICATION).equals(kind)) surface = TileBackgroundSaturationHooks.Surface.NOTIFICATION;
        }
        TileBackgroundSaturationHooks.apply(drawable, surface);
    }

    @SuppressWarnings("unchecked")
    private static void applyToRenderer(Object renderer, Object config, float blurAmount) {
        Integer kind = kind(config);
        Object drawable = XposedHelpers.getObjectField(renderer, "blurDrawable");
        applySaturation(drawable, kind);
        if (restoring || kind == null) return;
        String enabled = kind == TILE ? "qs_bg_brightness_enabled" : NotificationBackgroundBrightnessHooks.ENABLED;
        String value = kind == TILE ? "qs_bg_brightness_percent" : NotificationBackgroundBrightnessHooks.VALUE;
        if (kind == NOTIFICATION ? !NotificationBackgroundBrightnessHooks.materialEnabled()
                : !tileMaterialEnabled()) return;
        int brightness = readBoolCached(enabled, false) ? (kind == TILE ? percent(value, 75)
                : NotificationBackgroundBrightnessHooks.brightnessPercent()) : 100;
        List<Object> nativeParams = (List<Object>) XposedHelpers.callMethod(config, "getMixMultiShaderParams");
        if (nativeParams.size() < 4) return;
        // The renderer has already regenerated native shader parameters. Copy its
        // output so repeat calls, shared configs and nested color updates cannot compound.
        ArrayList<Object> params = new ArrayList<>(nativeParams);
        if (kind == NOTIFICATION) {
            // The first two layers belong to the panel underlay; the final two
            // are the card tint. Brightness changes RGB; opacity independently
            // replaces the tint alphas without changing the panel underlay.
            for (int i = 2; i < 4; i++) {
                Object param = nativeParams.get(i);
                int mode = (Integer) XposedHelpers.callMethod(param, "getMode");
                int color = (Integer) XposedHelpers.callMethod(param, "getColor");
                params.set(i, XposedHelpers.newInstance(shaderParam, mode,
                        NotificationBackgroundBrightnessHooks.adjustColor(color, i - 2)));
            }
        } else {
            // Use the same RGB-only adjustment as notification brightness. An
            // extra black blend layer changes the material's apparent coverage.
            for (int i = 2; i < 4; i++) {
                Object param = nativeParams.get(i);
                params.set(i, XposedHelpers.newInstance(shaderParam,
                        XposedHelpers.callMethod(param, "getMode"),
                        adjustTileColor((Integer) XposedHelpers.callMethod(param, "getColor"), i - 2)));
            }
        }
        XposedHelpers.callMethod(drawable, "setShaderBlendParams", params);
        int diagnostic = brightness;
        int previous = kind == TILE ? lastTileDiagnostic : lastNotificationDiagnostic;
        int opacity = kind == NOTIFICATION
                ? (readBoolCached(NotificationBackgroundBrightnessHooks.OPACITY_ENABLED, false) ? NotificationBackgroundBrightnessHooks.opacityPercent() : -1)
                : (readBoolCached("qs_bg_opacity_enabled", false) ? tileOpacityPercent() : -1);
        if (diagnostic != previous || opacity != (kind == NOTIFICATION ? lastOpacityDiagnostic : lastTileOpacityDiagnostic)) {
            if (kind == TILE) lastTileDiagnostic = diagnostic; else lastNotificationDiagnostic = diagnostic;
            if (kind == NOTIFICATION) lastOpacityDiagnostic = opacity;
            else lastTileOpacityDiagnostic = opacity;
            diagnostic("brightness applied: kind=" + kind + ", brightness=" + brightness
                    + ", opacity=" + opacity + ", layers=" + params.size());
        }
    }

    private static void markProxy(Object proxy, int kind) {
        Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
        Object mix = XposedHelpers.callMethod(config, "getPlatformMixConfig");
        if (kind == TILE) {
            markTileConfig(proxy, mix);
            return;
        }
        if (kind == NOTIFICATION) {
            Object view = XposedHelpers.callMethod(proxy, "getView");
            if (!(view instanceof View background)) return;
            // GroupOverlayContainerView lives inside NotificationChildrenContainer;
            // its row is an ancestor rather than the direct parent.
            View row = background;
            while (!notificationRow.isInstance(row)) {
                if (!(row.getParent() instanceof View parent)) return;
                row = parent;
            }
            if (XposedHelpers.getBooleanField(row, "mOnKeyguard")
                    && !Boolean.TRUE.equals(XposedHelpers.callMethod(row, "isHeadsUpState"))) {
                synchronized (MaterialBackgroundBrightnessHooks.class) { configs.remove(new Key(mix)); }
                return;
            }
        }
        mark(mix, kind);
    }

    private static void refreshNotificationProxy(Object proxy) {
        Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
        Object mix = XposedHelpers.callMethod(config, "getPlatformMixConfig");
        Integer before = kind(mix);
        WeakReference<Object> previous = notificationConfigs.get(proxy);
        if (previous == null || previous.get() != mix)
            notificationConfigs.put(proxy, new WeakReference<>(mix));
        boolean first = notificationProxies.put(proxy, true) == null;
        markProxy(proxy, NOTIFICATION);
        // The same background View/proxy is reused from heads-up to shade and
        // for later notifications. A newly installed config needs a fresh submission.
        if (first || previous == null || previous.get() != mix
                || !java.util.Objects.equals(before, kind(mix)))
            XposedHelpers.callMethod(proxy, "applyBlurConfig");
    }

    private static void markTileConfig(Object proxy, Object mix) {
        Object view = XposedHelpers.callMethod(proxy, "getView");
        boolean excluded;
        if (qsSeekBar.isInstance(view)) {
            excluded = isActiveSeekBarProxy(proxy, view);
        } else {
            Object owner = XposedHelpers.callStaticMethod(tileViewTags, "getMixColorDrawableTag", view);
            // Do not infer tile state from its tint: custom colors can be identical.
            excluded = !(owner instanceof Drawable drawable) || !adjustableTileBackground(drawable);
        }
        if (excluded) {
            synchronized (MaterialBackgroundBrightnessHooks.class) { configs.remove(new Key(mix)); }
        } else mark(mix, TILE);
    }

    private static boolean isActiveTileState(int[] state) {
        for (int attr : state) if (attr == activeTileAttr || attr == inoperableTileAttr) return true;
        return false;
    }

    private static boolean adjustableTileBackground(Drawable drawable) {
        // In hollow mode the neutral whole-card backdrop follows the active
        // parent state; only the separate icon circle is the active foreground.
        return !isActiveTileState(drawable.getState()) || MergedRadiantHooks.iconOnlyBackground(drawable);
    }

    private static boolean tileMaterialEnabled() {
        return readBoolCached("qs_bg_brightness_enabled", false) || readBoolCached("qs_bg_opacity_enabled", false)
                || readBoolCached(TileBackgroundEnhancement.ENABLED, false);
    }

    private static int tileOpacityPercent() {
        return Math.max(NotificationBackgroundBrightnessHooks.OPACITY_MIN,
                Math.min(100, readIntCached("qs_bg_opacity_percent", NotificationBackgroundBrightnessHooks.OPACITY_DEFAULT)));
    }

    private static int adjustTileColor(int color, int layer) {
        if (readBoolCached("qs_bg_brightness_enabled", false))
            color = NotificationBackgroundBrightnessHooks.scaleColor(color, percent("qs_bg_brightness_percent", 75));
        if (readBoolCached("qs_bg_opacity_enabled", false))
            color = (color & 0x00ffffff) | (NotificationBackgroundBrightnessHooks.opacityAlpha(tileOpacityPercent(), layer) << 24);
        return color;
    }

    private static boolean drawSolidTile(Drawable tile, Canvas canvas) {
        solidTiles.put(tile, true);
        if (restoring || !(readBoolCached("qs_bg_brightness_enabled", false)
                || readBoolCached("qs_bg_opacity_enabled", false)) || !adjustableTileBackground(tile)) return false;
        ColorDrawable fill = (ColorDrawable) XposedHelpers.getObjectField(tile, "colorDrawable");
        int color = fill.getColor();
        if ((color >>> 24) == 0 && fill.getColorFilter() == null) return false;
        Paint paint = (Paint) XposedHelpers.getObjectField(tile, "paint");
        paint.setColorFilter(fill.getColorFilter());
        // Solid backgrounds have one fill layer, so they use the first opacity.
        paint.setColor(adjustTileColor(color, 0));
        canvas.drawPath((Path) XposedHelpers.callMethod(tile, "getPath"), paint);
        // Leave the ColorDrawable and animation endpoints native.
        return true;
    }

    private static boolean isActiveSeekBarProxy(Object proxy, Object view) {
        Object active = XposedHelpers.getObjectField(view, "activeMixColorDrawable");
        return active != null && XposedHelpers.callMethod(active, "getViewBlurProxy") == proxy;
    }

    private static void refreshGroupHeader(Object renderer, View row) {
        Object mix = XposedHelpers.getObjectField(renderer, "currentBlurMixConfig");
        if (!shaderMix.isInstance(mix)) return;
        if (restoring || !NotificationBackgroundBrightnessHooks.materialEnabled()
                || (XposedHelpers.getBooleanField(row, "mOnKeyguard")
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(row, "isHeadsUpState")))) {
            synchronized (MaterialBackgroundBrightnessHooks.class) { configs.remove(new Key(mix)); }
            Object drawable = XposedHelpers.getObjectField(renderer, "blurDrawable");
            XposedHelpers.callMethod(drawable, "setShaderBlendParams", XposedHelpers.callMethod(mix, "getMixMultiShaderParams"));
        } else {
            mark(mix, NOTIFICATION);
            applyToRenderer(renderer, mix, 1f);
        }
        row.invalidate();
    }
    private static void refreshGroupHeaders() {
        for (var entry : new ArrayList<>(groupHeaderRenderers.entrySet())) {
            View row = entry.getValue().get();
            if (row == null) continue;
            try { refreshGroupHeader(entry.getKey(), row); }
            catch (Throwable error) { log("group header brightness refresh failed: " + error); }
        }
    }

    private static int percent(String key, int fallback) { return Math.max(50, Math.min(100, readIntCached(key, fallback))); }
    static void refresh() {
        String settings = (readBoolCached("qs_bg_brightness_enabled", false) ? "on:" + percent("qs_bg_brightness_percent", 75) : "off")
                + ":" + (readBoolCached("qs_bg_opacity_enabled", false) ? "on:" + tileOpacityPercent() : "off")
                + ":" + (readBoolCached(TileBackgroundEnhancement.ENABLED, false) ? "on:" + TileBackgroundEnhancement.clamp(
                    readIntCached(TileBackgroundEnhancement.VALUE, TileBackgroundEnhancement.DEFAULT)) : "off")
                + ":" + (readBoolCached(NotificationBackgroundBrightnessHooks.ENABLED, false)
                    ? "on:" + NotificationBackgroundBrightnessHooks.brightnessPercent() : "off")
                + ":" + (readBoolCached(NotificationBackgroundBrightnessHooks.OPACITY_ENABLED, false)
                    ? "on:" + NotificationBackgroundBrightnessHooks.opacityPercent() : "off")
                + ":" + (readBoolCached(TileBackgroundEnhancement.NOTIFICATION_ENABLED, false) ? "on:" + TileBackgroundEnhancement.clamp(
                    readIntCached(TileBackgroundEnhancement.NOTIFICATION_VALUE, TileBackgroundEnhancement.DEFAULT)) : "off");
        if (settings.equals(lastSettings)) return;
        lastSettings = settings;
        new Handler(Looper.getMainLooper()).post(() -> {
            refreshProxies(tileProxies, TILE);
            refreshProxies(notificationProxies, NOTIFICATION);
            refreshGroupHeaders();
            for (Drawable tile : new ArrayList<>(solidTiles.keySet())) if (tile != null) tile.invalidateSelf();
            NotificationBackgroundBrightnessHooks.refreshMaterialConfigs();
        });
    }
    private static void refreshProxies(WeakHashMap<Object, Boolean> proxies, int kind) {
        for (Object proxy : new ArrayList<>(proxies.keySet())) {
            try {
                markProxy(proxy, kind);
                XposedHelpers.callMethod(proxy, "applyBlurConfig");
                Object view = XposedHelpers.callMethod(proxy, "getView");
                if (view instanceof View owner) owner.invalidate();
            } catch (Throwable error) { log("background brightness refresh failed: " + error); }
        }
    }
    static Object capture() {
        ArrayList<Object[]> headers = new ArrayList<>();
        for (var entry : groupHeaderRenderers.entrySet()) {
            View row = entry.getValue().get();
            if (row != null) headers.add(new Object[]{entry.getKey(), row});
        }
        return new Object[]{tileProxies.keySet().toArray(), notificationProxies.keySet().toArray(), headers.toArray(), solidTiles.keySet().toArray()};
    }
    static void restore(Object state) {
        restoring = false;
        if (state instanceof Object[] groups) {
            if (groups.length >= 2 && groups[0] instanceof Object[] tiles && groups[1] instanceof Object[] notifications) {
                for (Object proxy : tiles) if (proxy != null) tileProxies.put(proxy, true);
                for (Object proxy : notifications) if (proxy != null) notificationProxies.put(proxy, true);
                if (groups.length > 2 && groups[2] instanceof Object[] headers)
                    for (Object header : headers)
                        if (header instanceof Object[] pair && pair.length == 2 && pair[1] instanceof View row)
                            groupHeaderRenderers.put(pair[0], new WeakReference<>(row));
                if (groups.length > 3 && groups[3] instanceof Object[] solids)
                    for (Object solid : solids) if (solid instanceof Drawable tile) solidTiles.put(tile, true);
            } else for (Object proxy : groups) if (proxy != null) tileProxies.put(proxy, true);
        }
        lastSettings = null;
    }
    static void cleanup() {
        restoring = true;
        TileBackgroundSaturationHooks.cleanup();
        refreshProxies(tileProxies, TILE);
        refreshProxies(notificationProxies, NOTIFICATION);
        refreshGroupHeaders();
        groupHeaderRenderers.clear();
        tileProxies.clear();
        activeSliderProxies.clear();
        notificationProxies.clear();
        notificationConfigs.clear();
        for (Drawable tile : new ArrayList<>(solidTiles.keySet())) if (tile != null) tile.invalidateSelf();
        solidTiles.clear();
        synchronized (MaterialBackgroundBrightnessHooks.class) { configs.clear(); shaderSources.clear(); }
    }
    private static void diagnostic(String message) {
        android.util.Log.e("ColorOSMod-Material", message);
        XposedBridge.log("material " + message);
    }
    private MaterialBackgroundBrightnessHooks() {}
}
