package com.rikumi.colorosmod.hooks;

import android.view.View;
import com.rikumi.colorosmod.xposed.*;
import java.util.*;
import static com.rikumi.colorosmod.hooks.NotificationBackgroundBrightnessHooks.enabled;

/** Use the shade's complete platform material on floating notification surfaces too. */
final class LegacyHeadsUpBackgroundHooks {
    private static final String BLUR = "com.oplusos.systemui.common.blurability.";
    private static final Map<Object, Object> NATIVE = new WeakHashMap<>();
    private static final Set<Object> VIEWS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> SETTING = ThreadLocal.withInitial(() -> false);
    private static Object motion, platform;

    static void hook(XC_LoadPackage.LoadPackageParam lp, Class<?> background, Class<?> proxy) {
        Class<?> type = XposedHelpers.findClass(BLUR + "ViewBlurProxy$BlurType", lp.classLoader);
        motion = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(type.getName() + "$BlurTypeMotion", lp.classLoader), "INSTANCE");
        platform = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(type.getName() + "$BlurTypePlatformStatic", lp.classLoader), "INSTANCE");
        XposedHelpers.findAndHookMethod(proxy, "setBlurType", type, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (SETTING.get() || !background.isInstance(XposedHelpers.callMethod(p.thisObject, "getView"))) return;
                if (p.args[0] == motion && isHeadsUp(p.thisObject)) {
                    NATIVE.put(p.thisObject, motion);
                    VIEWS.add(XposedHelpers.callMethod(p.thisObject, "getView"));
                    if (enabled()) p.args[0] = platform;
                } else {
                    NATIVE.remove(p.thisObject);
                    VIEWS.remove(XposedHelpers.callMethod(p.thisObject, "getView"));
                }
            }
        });
        // The ROM excludes heads-up views from platform blur by default. Lift only that
        // per-view exclusion while creating this notification's native surface drawable.
        Class<?> helper = XposedHelpers.findClass(BLUR + "platformblur.PlatformBlurHelper", lp.classLoader);
        XposedHelpers.findAndHookMethod(helper, "ensureBlurDrawable", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Object view = XposedHelpers.getObjectField(p.thisObject, "hostView");
                if (!enabled() || !VIEWS.contains(view)) return;
                Object rules = XposedHelpers.getObjectField(p.thisObject, "excludeRules");
                p.setObjectExtra("notification_heads_up_rules", new Object[] {rules});
                XposedHelpers.setObjectField(p.thisObject, "excludeRules", null);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object saved = p.getObjectExtra("notification_heads_up_rules");
                if (saved instanceof Object[] state) XposedHelpers.setObjectField(p.thisObject, "excludeRules", state[0]);
            }
        });
        for (java.lang.reflect.Method method : helper.getDeclaredMethods()) {
            if (method.getName().startsWith("setBlurEnabled")) XposedBridge.deoptimize(method);
        }
    }

    private static boolean isHeadsUp(Object proxy) {
        Object rules = XposedHelpers.getObjectField(proxy, "excludeRules");
        return rules != null && Boolean.TRUE.equals(XposedHelpers.callMethod(rules, "invoke", XposedHelpers.callMethod(proxy, "getView")));
    }
    static boolean alignExistingMotion(Object proxy) {
        if (motion == null || !enabled() || XposedHelpers.callMethod(proxy, "getBlurType") != motion || !isHeadsUp(proxy)) return false;
        NATIVE.put(proxy, motion);
        VIEWS.add(XposedHelpers.callMethod(proxy, "getView"));
        setType(proxy, platform);
        return true;
    }
    static void refresh(Object proxy) {
        if (motion == null) return;
        if (!NATIVE.containsKey(proxy) && XposedHelpers.callMethod(proxy, "getBlurType") == motion && isHeadsUp(proxy)) {
            NATIVE.put(proxy, motion);
            VIEWS.add(XposedHelpers.callMethod(proxy, "getView"));
        }
        Object nativeType = NATIVE.get(proxy);
        if (nativeType == null) return;
        Object desired = enabled() ? platform : nativeType;
        if (XposedHelpers.callMethod(proxy, "getBlurType") != desired) setType(proxy, desired);
    }
    private static void setType(Object proxy, Object type) {
        SETTING.set(true);
        try { XposedHelpers.callMethod(proxy, "setBlurType", type); }
        finally { SETTING.set(false); }
    }
    static void cleanup() { NATIVE.clear(); VIEWS.clear(); }
    private LegacyHeadsUpBackgroundHooks() {}
}
