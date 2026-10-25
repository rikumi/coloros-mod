package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.view.View;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

public final class MergedSettingsRotationHooks {
    private static final WeakHashMap<View, Float> originals = new WeakHashMap<>();
    private static final WeakHashMap<View, Float> footers = new WeakHashMap<>();
    private static boolean compatibilityErrorLogged;

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.OplusQSFooterImpl", pkg.classLoader,
                    "updateExpand", float.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View footer = (View) p.thisObject;
                            float fraction = p.args != null && p.args.length > 0
                                    && p.args[0] instanceof Number
                                    ? ((Number) p.args[0]).floatValue() : 0f;
                            if (!Float.isNaN(fraction) && !Float.isInfinite(fraction)) {
                                footers.put(footer, fraction);
                            }
                            applySafely(footer);
                        }
                    });
        } catch (Throwable t) { log("merged settings rotation hook failed: " + t); }
    }
    private static void apply(View footer) {
        View button = (View) XposedHelpers.getObjectField(footer, "mSettingsButton");
        View icon;
        try {
            icon = (View) XposedHelpers.getObjectField(footer, "mSettingsButtonIcon");
        } catch (Throwable oldSystemUi) {
            // SystemUI 15.99.13 uses the ImageView itself as the settings icon.
            icon = button;
        }
        if (button == null || icon == null) return;
        float original = originals.computeIfAbsent(icon, view -> view.getRotation());
        float rotation = original;
        if (readBool(KEY_QS_MERGED_SETTINGS_ROTATION, false)) {
            Float fractionValue = footers.get(footer);
            float fraction = fractionValue == null ? 0f : fractionValue.floatValue();
            fraction = Math.max(0f, Math.min(1f, fraction));
            float direction = button.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1f : -1f;
            rotation += 120f * direction * fraction;
        }
        if (icon.getRotation() != rotation) icon.setRotation(rotation);
    }
    private static void applySafely(View footer) {
        try { apply(footer); }
        catch (Throwable t) {
            if (!compatibilityErrorLogged) {
                compatibilityErrorLogged = true;
                log("merged settings rotation refresh failed: " + t);
            }
        }
    }
    public static void refresh() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (View footer : new ArrayList<>(footers.keySet())) if (footer != null) applySafely(footer);
        });
    }
    public static Object capture() { return new ArrayList<>(footers.keySet()); }
    public static void restoreHosts(Object state) {
        if (state instanceof Iterable<?> views) for (Object view : views)
            if (view instanceof View footer) {
                float fraction = 0f;
                try { fraction = XposedHelpers.getFloatField(footer, "mExpansionAmount"); }
                catch (Throwable ignored) { }
                footers.put(footer, fraction);
                applySafely(footer);
            }
    }
    public static void cleanup() {
        for (View icon : new ArrayList<>(originals.keySet())) if (icon != null) icon.setRotation(originals.get(icon));
        originals.clear(); footers.clear();
    }
}
