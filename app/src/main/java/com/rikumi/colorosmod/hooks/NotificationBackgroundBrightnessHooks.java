package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.rikumi.colorosmod.xposed.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;

/** Notification-card brightness, with separate native ColorOS 16 and 17 rendering paths. */
public final class NotificationBackgroundBrightnessHooks {
    private static final String BLUR = "com.oplusos.systemui.common.blurability.";
    private static WeakReference<Object> manager = new WeakReference<>(null);
    private static Class<?> shaderColor;
    private static boolean modern;
    private static boolean restoring;
    private static String lastSetting;
    public static final String ENABLED = "notification_bg_brightness_enabled";
    public static final String VALUE = "notification_bg_brightness_percent";
    public static final int DEFAULT = 50;
    public static final String OPACITY_ENABLED = "notification_bg_opacity_enabled";
    public static final String OPACITY_VALUE = "notification_bg_opacity_percent";
    public static final int OPACITY_DEFAULT = 70;
    public static final int OPACITY_MIN = 40;

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        restoring = false;
        try {
            shaderColor = XposedHelpers.findClass(BLUR + "MixColorWithShader", lp.classLoader);
            Class<?> managerClass = XposedHelpers.findClass(
                    "com.oplus.systemui.notification.blur.ViewBlurManager", lp.classLoader);
            modern = XposedHelpers.findClassIfExists(
                    "com.oplus.systemui.notification.blur.NotificationPlatFormBlurParamsManager", lp.classLoader) != null;
            if (modern) {
                MaterialBackgroundBrightnessHooks.hook(lp);
                // These callers may contain ART-inlined getMixConfig copies.
                XposedBridge.hookAllMethods(managerClass, "requireBlurProxyForView", new XC_MethodHook() {});
                XposedBridge.hookAllMethods(managerClass, "updatePlatformBlurParams", new XC_MethodHook() {});
                XposedBridge.hookAllMethods(managerClass, "refreshMotionMixConfig", new XC_MethodHook() {});
            }
            // Do not store customized heads-up colors in the reusable BlurConfig.
            // Substitute only while the motion renderer consumes them, then restore.
            Class<?> proxyClass = XposedHelpers.findClass(BLUR + "ViewBlurProxy", lp.classLoader);
            XposedBridge.hookAllMethods(proxyClass, "applyConfigToMotionBlur", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("notification_motion_material", beginMotion(p.thisObject));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object state = p.getObjectExtra("notification_motion_material");
                    if (state instanceof Object[] saved) endMotion(saved);
                }
            });
            XposedBridge.hookAllMethods(proxyClass, "applyBlurConfig", new XC_MethodHook() {});
            XposedBridge.hookAllMethods(proxyClass, "setBlurType", new XC_MethodHook() {});
            XposedBridge.hookAllConstructors(managerClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    manager = new WeakReference<>(p.thisObject);
                }
            });
            // getMixConfig also discovers an existing manager after module hot reload.
            XposedBridge.hookAllMethods(managerClass, "getMixConfig", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    manager = new WeakReference<>(p.thisObject);
                    if (p.getResult() == null || !"NOTIFICATION".equals(((Enum<?>) p.args[0]).name())) return;
                    if (modern) {
                        // Include shade and floating notification cards, excluding lockscreen cards.
                        String mode = ((Enum<?>) p.args[1]).name();
                        if (mode.equals("SHADE_LIGHT") || mode.equals("SHADE_DARK") || mode.startsWith("HEADS_UP_"))
                            MaterialBackgroundBrightnessHooks.markNotification(p.getResult());
                        return;
                    }
                    if (!enabled()) return;
                    p.setResult(adjust(p.getResult()));
                }
            });

        } catch (Throwable error) { log("notification background brightness hook failed: " + error); }
    }

    private static boolean enabled() {
        return !restoring && materialEnabled();
    }

    static boolean materialEnabled() {
        return readBoolCached(ENABLED, false) || readBoolCached(OPACITY_ENABLED, false);
    }

    private static Object[] beginMotion(Object proxy) {
        if (!enabled()) return null;
        Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
        if (((Number) XposedHelpers.callMethod(config, "getCardType")).intValue() != 0) return null;
        Object original = XposedHelpers.callMethod(config, "getMotionBlurMixConfig");
        if (original == null) return null;
        Object replacement = adjustMotion(original);
        XposedHelpers.callMethod(config, "setMotionBlurMixConfig", replacement);
        return new Object[]{config, original, replacement};
    }

    private static void endMotion(Object[] state) {
        if (XposedHelpers.callMethod(state[0], "getMotionBlurMixConfig") == state[2])
            XposedHelpers.callMethod(state[0], "setMotionBlurMixConfig", state[1]);
    }

    private static Object adjustMotion(Object config) {
        Object mix = XposedHelpers.callMethod(config, "getMixColor");
        Object replacementMix = XposedHelpers.newInstance(mix.getClass(),
                XposedHelpers.getIntField(mix, "mode"),
                adjustColor(XposedHelpers.getIntField(mix, "topLayerColor"), 0),
                adjustColor(XposedHelpers.getIntField(mix, "bottomLayerColor"), 1));
        Object replacement = XposedHelpers.newInstance(config.getClass(), replacementMix);
        copyProperties(config, replacement);
        return replacement;
    }

    private static Object adjust(Object config) {
        // Low-Gaussian fallback has a different configuration; do not change its rendering mode.
        if (!config.getClass().getName().equals(BLUR + "BlurMixConfig$BlurMixMultiWithShader")) return config;
        Object nativeForeground = XposedHelpers.callMethod(config, "getForegroundShaderParam");
        Object foreground = XposedHelpers.newInstance(shaderColor,
                XposedHelpers.callMethod(nativeForeground, "getTopMode"),
                adjustColor((Integer) XposedHelpers.callMethod(nativeForeground, "getTopLayerColor"), 0),
                XposedHelpers.callMethod(nativeForeground, "getBottomMode"),
                adjustColor((Integer) XposedHelpers.callMethod(nativeForeground, "getBottomLayerColor"), 1));
        Object background = XposedHelpers.callMethod(config, "getBackgroundShaderParam");
        Object result = XposedHelpers.newInstance(config.getClass(), foreground, background);
        copyProperties(config, result);
        return result;
    }

    private static void copyProperties(Object config, Object result) {
        XposedHelpers.callMethod(result, "setMirrorScale", XposedHelpers.callMethod(config, "getMirrorScale"));
        XposedHelpers.callMethod(result, "setAlphaWithBlurAmount", XposedHelpers.callMethod(config, "getAlphaWithBlurAmount"));
        XposedHelpers.callMethod(result, "setPlaceHolderColor", XposedHelpers.callMethod(config, "getPlaceHolderColor"));
    }

    static int adjustColor(int color, int layer) {
        if (readBoolCached(ENABLED, false)) color = scaleColor(color, brightnessPercent());
        if (readBoolCached(OPACITY_ENABLED, false)) {
            int alpha = opacityAlpha(opacityPercent(), layer);
            color = (color & 0x00ffffff) | (alpha << 24);
        }
        return color;
    }

    static int opacityPercent() {
        return Math.max(OPACITY_MIN, Math.min(100, readIntCached(OPACITY_VALUE, OPACITY_DEFAULT)));
    }

    static int brightnessPercent() {
        return Math.max(50, Math.min(100, readIntCached(VALUE, DEFAULT)));
    }

    static int opacityAlpha(int firstPercent, int layer) {
        float percent = layer == 0 ? firstPercent : firstPercent / 4f + 27.5f;
        return Math.round(255f * percent / 100f);
    }

    static int scaleColor(int color, int brightness) {
        float factor = Math.max(50, Math.min(100, brightness)) / 100f;
        return Color.argb(Color.alpha(color), Math.min(255, Math.round(Color.red(color) * factor)),
                Math.min(255, Math.round(Color.green(color) * factor)),
                Math.min(255, Math.round(Color.blue(color) * factor)));
    }

    public static void refresh() {
        MaterialBackgroundBrightnessHooks.refresh();
        String setting = (readBoolCached(ENABLED, false) ? "on:" + brightnessPercent() : "off")
                + ":" + (readBoolCached(OPACITY_ENABLED, false) ? "on:" + opacityPercent() : "off");
        if (setting.equals(lastSetting)) return;
        lastSetting = setting;
        new Handler(Looper.getMainLooper()).post(NotificationBackgroundBrightnessHooks::refreshNow);
    }

    static void refreshMaterialConfigs() { refreshNow(); }

    private static void refreshNow() {
        Object host = manager.get();
        if (host == null) return;
        try {
            if (!modern) {
                Context context = (Context) XposedHelpers.getObjectField(host, "context");
                XposedHelpers.callMethod(host, "onConfigChanged", context.getResources().getConfiguration());
                return;
            }
            Map<?, ?> proxies = (Map<?, ?>) XposedHelpers.getObjectField(host, "viewBlurProxyes");
            for (Object proxy : new ArrayList<>(proxies.values())) {
                Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
                // NOTIFICATION is ordinal 0 on both supported versions. Exclude QS and custom cards.
                if (((Number) XposedHelpers.callMethod(config, "getCardType")).intValue() != 0) continue;
                XposedHelpers.callMethod(host, "updatePlatformBlurParams", proxy, true);
                XposedHelpers.callMethod(host, "refreshMotionMixConfig", proxy, null);
                XposedHelpers.callMethod(proxy, "applyBlurConfig");
                Object view = XposedHelpers.callMethod(proxy, "getView");
                if (view instanceof View) ((View) view).invalidate();
            }
        } catch (Throwable error) { log("notification background brightness refresh failed: " + error); }
    }

    public static Object capture() { return new Object[]{manager.get(), MaterialBackgroundBrightnessHooks.capture()}; }
    public static void restore(Object host) {
        restoring = false;
        if (host instanceof Object[] state && state.length == 2) {
            MaterialBackgroundBrightnessHooks.restore(state[1]);
            host = state[0];
        }
        manager = new WeakReference<>(host);
        lastSetting = null;
        refresh();
    }
    public static void cleanupForHotReload() {
        restoring = true;
        MaterialBackgroundBrightnessHooks.cleanup();
        refreshNow();
        manager.clear();
    }
    private NotificationBackgroundBrightnessHooks() {}
}
