package com.rikumi.colorosmod.hooks;

import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.*;
import static com.rikumi.colorosmod.XposedInit.log;

/** The separated shade's resizable Wi-Fi/Bluetooth cards have their own outline lifecycle. */
final class SeparateQsCardHooks {
    private static Class<?> cardClass, constantClass;
    private static boolean active = true;
    private static final WeakHashMap<View, OutlineState> cards = new WeakHashMap<>();
    private static final class OutlineState {
        java.lang.ref.WeakReference<Object> drawable = new java.lang.ref.WeakReference<>(null);
        Object nativeOutline, appliedOutline;
        Float radius;
        View.OnLayoutChangeListener listener;
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT < 37) return;
        cardClass = XposedHelpers.findClassIfExists(
                "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne", lp.classLoader);
        if (cardClass == null) return;
        constantClass = XposedHelpers.findClass("com.oplus.systemui.qs.base.res.util.QSConstant", lp.classLoader);
        active = true;
        for (String name : new String[]{"onOutlineUpdate", "onDrawableUpdate", "onAttachedToWindow"})
            XposedBridge.hookAllMethods(cardClass, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (active && !p.hasThrowable()) apply((View) p.thisObject);
                }
            });
        // Discover existing hosts after hot reload, without depending on a coroutine's generated name.
        XposedBridge.hookAllMethods(cardClass, "onMeasure", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (active && !p.hasThrowable()) apply((View) p.thisObject);
            }
        });
    }

    private static void apply(View card) {
        try {
            Object drawable = XposedHelpers.callMethod(card, "getTransitionDrawable");
            Object current = XposedHelpers.callMethod(drawable, "getPathProvider");
            OutlineState state = cards.get(card);
            if (state == null) {
                state = new OutlineState();
                state.listener = (v, l, t, r, b, ol, ot, or, ob) -> {
                    if (active && (r - l != or - ol || b - t != ob - ot)) apply(v);
                };
                cards.put(card, state);
                card.addOnLayoutChangeListener(state.listener);
            }
            if (state.drawable.get() != drawable || current != state.appliedOutline) {
                state.nativeOutline = current;
                state.drawable = new java.lang.ref.WeakReference<>(drawable);
                state.appliedOutline = null;
                state.radius = null;
            }
            View background = (View) XposedHelpers.callMethod(card, "getBg");
            Float radius = active ? QsHooks.resolveQsCornerRadiusPx(background) : null;
            if (radius == null) {
                if (state.appliedOutline != null) {
                    XposedHelpers.callMethod(drawable, "setPathProvider", state.nativeOutline);
                    state.appliedOutline = null;
                    state.radius = null;
                    card.invalidate();
                }
            } else if (!radius.equals(state.radius) || state.appliedOutline == null) {
                Object outline = XposedHelpers.callStaticMethod(constantClass,
                        "getSmoothRoundRectOutlineProvider", card.getContext(), radius);
                XposedHelpers.callMethod(drawable, "setPathProvider", outline);
                state.appliedOutline = outline;
                state.radius = radius;
                card.invalidate();
            }
        } catch (Throwable error) { log("separate QS card outline failed: " + error); }
    }

    static boolean isCardBackground(Drawable drawable) {
        if (!active || cardClass == null) return false;
        View owner;
        if (drawable.getClass().getSimpleName().equals("MixColorTileDrawable")) {
            Object blur = XposedHelpers.getObjectField(drawable, "autoBlurDrawable");
            Object proxy = XposedHelpers.callMethod(blur, "getViewBlurProxy");
            owner = (View) XposedHelpers.callMethod(proxy, "getView");
        } else {
            Drawable.Callback callback = drawable.getCallback();
            for (int i = 0; callback instanceof Drawable parent && i < 12; i++) callback = parent.getCallback();
            owner = callback instanceof View view ? view : null;
        }
        for (View view = owner; view != null; view = view.getParent() instanceof View parent ? parent : null)
            if (cardClass.isInstance(view)) return XposedHelpers.callMethod(view, "getBg") == owner;
        return false;
    }

    static void refresh() {
        if (active) for (View card : new ArrayList<>(cards.keySet())) apply(card);
    }

    static void cleanup() {
        active = false;
        for (View card : new ArrayList<>(cards.keySet())) {
            apply(card);
            card.removeOnLayoutChangeListener(cards.get(card).listener);
        }
        cards.clear();
    }
}
