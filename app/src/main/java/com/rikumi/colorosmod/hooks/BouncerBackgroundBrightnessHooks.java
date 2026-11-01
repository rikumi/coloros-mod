package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** ColorOS 17 keeps the password background in scrim_in_front, separate from scrim_behind. */
public final class BouncerBackgroundBrightnessHooks {
    private static Class<?> singleMix, autoBlur;
    private static Object nativeMix;
    private static int frontId;
    private static boolean restoring, errorLogged;
    private static int loggedValue = Integer.MIN_VALUE;
    private static final WeakHashMap<View, Applied> views = new WeakHashMap<>();
    private static final class Applied {
        Object mix;
        int value = Integer.MIN_VALUE;
    }

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        Class<?> utils = XposedHelpers.findClassIfExists(
                "com.oplus.systemui.statusbar.phone.ScrimBlurStateUtilsExImpl", pkg.classLoader);
        if (utils == null) return;
        try {
            singleMix = XposedHelpers.findClass(
                    "com.oplusos.systemui.common.blurability.BlurMixConfig$BlurMixSingle", pkg.classLoader);
            autoBlur = XposedHelpers.findClass(
                    "com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable", pkg.classLoader);
            nativeMix = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                    "com.oplusos.systemui.common.util.NotifiAndQsPlatformBlurExKt", pkg.classLoader),
                    "getBOUNCER_MIX_COLOR");
            XposedHelpers.findAndHookMethod("com.android.systemui.scrim.ScrimView", pkg.classLoader,
                    "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { update((View) p.thisObject); }
            });
            // Both callers can inline panelBouncerMixConfig; deoptimize the actual front caller too.
            XposedHelpers.findAndHookMethod(utils, "getFrontScrimBlurType", new XC_MethodHook() {});
            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.phone.ScrimControllerExImp",
                    pkg.classLoader, "getPanelPlatformMixConfig", new XC_MethodHook() {});
            refresh();
        } catch (Throwable t) { log("bouncer front brightness hook failed: " + t); }
    }

    private static void update(View view) {
        if (singleMix == null) return;
        try {
            if (frontId == 0) frontId = view.getResources().getIdentifier("scrim_in_front", "id", "com.android.systemui");
            if (frontId == 0 || view.getId() != frontId) return;
            Applied state = views.computeIfAbsent(view, key -> new Applied());
            Object drawable = XposedHelpers.callMethod(view, "getDrawable");
            if (!autoBlur.isInstance(drawable)) return;
            Object proxy = XposedHelpers.callMethod(drawable, "getViewBlurProxy");
            Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
            Object current = XposedHelpers.callMethod(config, "getPlatformMixConfig");
            if (!singleMix.isInstance(current)) return;
            int value = !restoring && readBoolCached(KEY_KEYGUARD_BOUNCER_BRIGHTNESS_ENABLED, false)
                    ? Math.max(0, Math.min(KEYGUARD_BOUNCER_BRIGHTNESS_MAX,
                            readInt(KEY_KEYGUARD_BOUNCER_BRIGHTNESS, KEYGUARD_BOUNCER_BRIGHTNESS_DEFAULT))) : -1;
            if (state.value == value && state.mix == current) return;
            if (value < 0 && state.value == Integer.MIN_VALUE) return;
            Object mix = nativeMix;
            if (value >= 0) {
                int top = XposedHelpers.getIntField(nativeMix, "topLayerColor");
                int gray = Math.round(Color.red(top) * value / (float) KEYGUARD_BOUNCER_BRIGHTNESS_MAX);
                mix = XposedHelpers.newInstance(nativeMix.getClass(), XposedHelpers.getIntField(nativeMix, "mode"),
                        Color.argb(Color.alpha(top), gray, gray, gray),
                        XposedHelpers.getIntField(nativeMix, "bottomLayerColor"));
            }
            Object replacement = XposedHelpers.newInstance(singleMix, mix);
            XposedHelpers.callMethod(replacement, "setAlphaWithBlurAmount", XposedHelpers.callMethod(current, "getAlphaWithBlurAmount"));
            XposedHelpers.callMethod(replacement, "setMirrorScale", XposedHelpers.callMethod(current, "getMirrorScale"));
            XposedHelpers.callMethod(config, "setPlatformMixConfig", replacement);
            state.mix = replacement;
            state.value = value;
            XposedHelpers.callMethod(proxy, "applyBlurConfig");
            if (loggedValue != value) {
                loggedValue = value;
                XposedBridge.log("bouncer front brightness applied: value=" + value);
            }
        } catch (Throwable t) {
            if (!errorLogged) { errorLogged = true; XposedBridge.log("bouncer front brightness failed: " + t); }
        }
    }

    private static void visit(View view) {
        if (view == null) return;
        if (frontId == 0) frontId = view.getResources().getIdentifier("scrim_in_front", "id", "com.android.systemui");
        if (frontId != 0 && view.getId() == frontId) { update(view); view.invalidate(); return; }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) visit(group.getChildAt(i));
    }

    public static void refresh() {
        if (singleMix == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Object global = XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"), "getInstance");
                Object roots = XposedHelpers.getObjectField(global, "mViews");
                if (roots instanceof Iterable<?> list) for (Object root : new ArrayList<>((java.util.Collection<?>) list))
                    if (root instanceof View view) visit(view);
            } catch (Throwable t) { log("bouncer brightness refresh failed: " + t); }
        });
    }

    public static void cleanup() {
        restoring = true;
        for (View view : new ArrayList<>(views.keySet())) if (view != null) { update(view); view.invalidate(); }
        views.clear();
    }
}
