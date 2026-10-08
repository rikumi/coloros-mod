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

/** Rotation follows the native footer translation, including interrupted gestures. */
public final class MergedSettingsRotationHooks {
    private static final WeakHashMap<View, Float> originals = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> footers = new WeakHashMap<>();

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.OplusQSFooterImpl", pkg.classLoader,
                    "updateExpand", float.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View footer = (View) p.thisObject;
                            footers.put(footer, true);
                            apply(footer);
                        }
                    });
        } catch (Throwable t) { log("merged settings rotation hook failed: " + t); }
    }
    private static void apply(View footer) {
        View button = (View) XposedHelpers.getObjectField(footer, "mSettingsButton");
        View icon = (View) XposedHelpers.getObjectField(footer, "mSettingsButtonIcon");
        if (button == null || icon == null) return;
        float original = originals.computeIfAbsent(icon, view -> view.getRotation());
        float rotation = original;
        if (readBool(KEY_QS_MERGED_SETTINGS_ROTATION, false)) {
            int margin = footer.getResources().getIdentifier("qs_footer_button_margin", "dimen", "com.android.systemui");
            int size = footer.getResources().getIdentifier("qs_footer_settings_button_size", "dimen", "com.android.systemui");
            float travel = footer.getResources().getDimension(margin)
                    + (button.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? button.getWidth()
                    : footer.getResources().getDimensionPixelSize(size));
            if (travel > 0f) rotation += 120f * Math.max(-1f, Math.min(1f, button.getTranslationX() / travel));
        }
        if (icon.getRotation() != rotation) icon.setRotation(rotation);
    }
    public static void refresh() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (View footer : new ArrayList<>(footers.keySet())) if (footer != null) apply(footer);
        });
    }
    public static Object capture() { return new ArrayList<>(footers.keySet()); }
    public static void restoreHosts(Object state) {
        if (state instanceof Iterable<?> views) for (Object view : views)
            if (view instanceof View footer) { footers.put(footer, true); apply(footer); }
    }
    public static void cleanup() {
        for (View icon : new ArrayList<>(originals.keySet())) if (icon != null) icon.setRotation(originals.get(icon));
        originals.clear(); footers.clear();
    }
}
