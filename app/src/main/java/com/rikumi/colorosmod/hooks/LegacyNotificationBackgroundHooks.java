package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.xposed.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import static com.rikumi.colorosmod.hooks.NotificationBackgroundBrightnessHooks.*;

/** ColorOS 15 has MixColor and BlurMixSingle/Multi, without shader parameters or cardType. */
final class LegacyNotificationBackgroundHooks {
    private static final String BLUR = "com.oplusos.systemui.common.blurability.";
    private static final Set<Object> PROXIES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> DRAWING = ThreadLocal.withInitial(() -> false);

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> background = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.NotificationBackgroundView", lp.classLoader);
        Class<?> proxy = XposedHelpers.findClass(BLUR + "ViewBlurProxy", lp.classLoader);
        LegacyHeadsUpBackgroundHooks.hook(lp, background, proxy);
        for (String method : new String[] { "applyConfigToPlatformBlur", "applyConfigToMotionBlur", "applyConfigToStaticBlur" }) {
            XposedBridge.hookAllMethods(proxy, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!background.isInstance(XposedHelpers.callMethod(p.thisObject, "getView"))) return;
                    PROXIES.add(p.thisObject);
                    if (!enabled() || DRAWING.get()) return;
                    boolean platform = p.method.getName().equals("applyConfigToPlatformBlur");
                    boolean motion = p.method.getName().equals("applyConfigToMotionBlur");
                    if (motion && LegacyHeadsUpBackgroundHooks.alignExistingMotion(p.thisObject)) {
                        p.setResult(null);
                        return;
                    }
                    List<Object[]> saved = new ArrayList<>();
                    // Install the restoration state before touching any config, including failed draws.
                    p.setObjectExtra("legacy_notification_material", saved);
                    DRAWING.set(true);
                    try {
                        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                        for (String getter : platform
                                ? new String[] { "getBlurConfig", "getStackBlurConfigFirst", "getStackBlurConfigSecond" }
                                : new String[] { "getBlurConfig" }) {
                            Object config = XposedHelpers.callMethod(p.thisObject, getter);
                            if (config == null || !seen.add(config)) continue;
                            int color = (Integer) XposedHelpers.callMethod(config, "getBlurColor");
                            Object original = platform ? XposedHelpers.callMethod(config, "getPlatformMixConfig")
                                    : motion ? XposedHelpers.callMethod(config, "getMotionBlurMixConfig") : null;
                            Object replacement = adjust(original);
                            saved.add(new Object[] { config, color, original, replacement, platform, motion });
                            XposedHelpers.callMethod(config, "setBlurColor", adjustColor(color, 0));
                            if (platform) XposedHelpers.callMethod(config, "setPlatformMixConfig", replacement);
                            else if (motion) XposedHelpers.callMethod(config, "setMotionBlurMixConfig", replacement);
                        }
                    } catch (Throwable error) {
                        restore(saved);
                        saved.clear();
                        DRAWING.set(false);
                        com.rikumi.colorosmod.XposedInit.log("legacy notification background adjustment failed: " + error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object saved = p.getObjectExtra("legacy_notification_material");
                    if (!(saved instanceof List<?> states)) return;
                    try { restore(states); } finally { DRAWING.set(false); }
                }
            });
        }
        // Rotation, expansion and blur-type transitions can call compiled copies of the renderer.
        for (java.lang.reflect.Method method : proxy.getDeclaredMethods()) {
            String name = method.getName();
            if (name.equals("applyBlurConfig") || name.equals("setBlurType")
                    || name.equals("setBlurAmount") || name.equals("setBlurEnabled"))
                XposedBridge.deoptimize(method);
        }
    }

    private static Object adjust(Object config) {
        if (config == null) return null;
        String name = config.getClass().getName();
        if (name.equals(BLUR + "BlurMixConfig$BlurMixSingle")) return adjustMotion(config);
        if (!name.equals(BLUR + "BlurMixConfig$BlurMixMulti")) return config;
        Object foreground = XposedHelpers.callMethod(config, "getForegroundMixColor");
        Object mix = XposedHelpers.newInstance(foreground.getClass(),
                XposedHelpers.getIntField(foreground, "mode"),
                adjustColor(XposedHelpers.getIntField(foreground, "topLayerColor"), 0),
                adjustColor(XposedHelpers.getIntField(foreground, "bottomLayerColor"), 1));
        Object replacement = XposedHelpers.newInstance(config.getClass(), mix,
                XposedHelpers.callMethod(config, "getBackgroundMixColor"));
        copyProperties(config, replacement);
        return replacement;
    }

    private static void restore(List<?> states) {
        for (int i = states.size() - 1; i >= 0; i--) {
            Object[] state = (Object[]) states.get(i);
            Object config = state[0];
            XposedHelpers.callMethod(config, "setBlurColor", state[1]);
            if ((Boolean) state[4]) {
                if (XposedHelpers.callMethod(config, "getPlatformMixConfig") == state[3])
                    XposedHelpers.callMethod(config, "setPlatformMixConfig", state[2]);
            } else if ((Boolean) state[5]) {
                if (XposedHelpers.callMethod(config, "getMotionBlurMixConfig") == state[3])
                    XposedHelpers.callMethod(config, "setMotionBlurMixConfig", state[2]);
            }
        }
    }
    static void refresh() {
        for (Object proxy : new ArrayList<>(PROXIES)) {
            try {
                LegacyHeadsUpBackgroundHooks.refresh(proxy);
                XposedHelpers.callMethod(proxy, "applyBlurConfig");
                ((android.view.View) XposedHelpers.callMethod(proxy, "getView")).invalidate();
            } catch (Throwable error) {
                com.rikumi.colorosmod.XposedInit.log("legacy notification background refresh failed: " + error);
            }
        }
    }
    static Object capture() { return PROXIES.toArray(); }
    static void restore(Object saved) {
        if (saved instanceof Object[] proxies) Collections.addAll(PROXIES, proxies);
    }
    static void cleanup() { LegacyHeadsUpBackgroundHooks.cleanup(); PROXIES.clear(); }
    private LegacyNotificationBackgroundHooks() {}
}
