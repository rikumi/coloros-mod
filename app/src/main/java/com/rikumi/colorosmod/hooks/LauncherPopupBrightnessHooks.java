package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.*;

/** Launcher 17 draws wallpaper blend colors in the popup View, after bitmap blur. */
final class LauncherPopupBrightnessHooks {
    private static final String[] COLORS = {"mWallpaperBlendColor", "mWallpaperDarkBlendColor"};
    private static final Set<View> views = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean active = true;

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> popup = XposedHelpers.findClass("com.android.launcher3.popup.PopupBlurView", lp.classLoader);
            // Older Launchers apply the blend inside PopupBlurHelper instead.
            for (String field : COLORS) {
                try { popup.getDeclaredField(field); }
                catch (NoSuchFieldException legacy) { return; }
            }
            XposedHelpers.findAndHookMethod(popup, "dispatchDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!active) return;
                    synchronized (views) { views.add((View) p.thisObject); }
                    if (!readBoolCached(KEY_DESKTOP_POPUP_BG_BRIGHTNESS_ENABLED, false)) return;
                    int value = Math.max(0, Math.min(DESKTOP_POPUP_BG_BRIGHTNESS_MAX,
                            readIntCached(KEY_DESKTOP_POPUP_BG_BRIGHTNESS, DESKTOP_POPUP_BG_BRIGHTNESS_DEFAULT)));
                    float scale = value / (float) DESKTOP_POPUP_BG_BRIGHTNESS_MAX;
                    if (scale >= 1f) return;
                    for (String field : COLORS) {
                        int original = XposedHelpers.getIntField(p.thisObject, field);
                        p.setObjectExtra(field, original);
                        XposedHelpers.setIntField(p.thisObject, field, scaleColor(original, scale));
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    // Restore even after a failed draw. Never darken the already
                    // modified color again on the next animation frame.
                    for (String field : COLORS) {
                        Object original = p.getObjectExtra(field);
                        if (original instanceof Integer color)
                            XposedHelpers.setIntField(p.thisObject, field, color);
                    }
                }
            });
            log("HOOK OK launcher popup background brightness in PopupBlurView#dispatchDraw");
        } catch (Throwable error) { log("launcher popup background brightness hook failed: " + error); }
    }

    private static int scaleColor(int color, float scale) {
        return Color.argb(Color.alpha(color), Math.round(Color.red(color) * scale),
                Math.round(Color.green(color) * scale), Math.round(Color.blue(color) * scale));
    }

    static void refresh() {
        View[] snapshot;
        synchronized (views) { snapshot = views.toArray(new View[0]); }
        for (View view : snapshot) view.postInvalidate();
    }

    static void cleanup() {
        active = false;
        refresh();
        synchronized (views) { views.clear(); }
    }

    private LauncherPopupBrightnessHooks() {}
}
