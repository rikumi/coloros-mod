package com.rikumi.colorosmod.hooks;

import android.graphics.Canvas;
import android.os.Build;
import android.view.View;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.*;
import static com.rikumi.colorosmod.XposedInit.log;

/** ColorOS 16 draws the merged slider background from COUI's progress radius and curve. */
final class QsSliderCornerCompat {
    private static final WeakHashMap<View, Shape> shapes = new WeakHashMap<>();
    private static boolean active = true;

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT != 36) return;
        for (String owner : new String[]{"com.oplus.systemui.qs.widget.OplusQsToggleSliderLayout",
                "com.oplus.systemui.plugins.qs.widget.OplusQsToggleSliderLayout"}) {
            try {
                Class<?> type = XposedHelpers.findClass(owner, lp.classLoader);
                XposedBridge.deoptimize(type.getDeclaredMethod("updateCornerRadius"));
                if (owner.equals("com.oplus.systemui.qs.widget.OplusQsToggleSliderLayout"))
                    XposedBridge.deoptimize(type.getDeclaredMethod("onAttachedToWindow"));
            } catch (Throwable error) { log("qs slider radius caller deoptimization failed: " + error); }
        }
        try {
            Class<?> layout = XposedHelpers.findClass(
                    "com.oplus.systemui.qs.base.seek.OplusQsBaseToggleSliderLayout", lp.classLoader);
            XposedHelpers.findAndHookMethod("com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar", lp.classLoader,
                    "onDraw", Canvas.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!active) return;
                            View slider = (View) p.thisObject;
                            Integer percent = QsHooks.qsCornerRadiusPercent();
                            if (percent == null) { shapes.remove(slider); return; }
                            if (!(slider.getParent() instanceof View parent) || !layout.isInstance(parent)
                                    || slider.getWidth() <= 0 || slider.getHeight() <= 0) return;
                            try { sync(parent, slider, percent); }
                            catch (Throwable error) { log("qs slider draw radius sync failed: " + error); }
                        }
                    });
        } catch (Throwable error) { log("qs slider draw radius hook failed: " + error); }
    }

    private static void sync(View layout, View slider, int percent) {
        Shape shape = shapes.get(slider);
        float density = slider.getResources().getDisplayMetrics().density;
        if (shape == null || shape.width != slider.getWidth() || shape.height != slider.getHeight()
                || shape.percent != percent || shape.density != density) {
            QsHooks.trackSliderRadius(layout, slider);
            Float requested = QsHooks.resolveQsCornerRadiusPx(layout);
            if (requested == null) return;
            Object params = QsHooks.naturalSliderRoundParams(layout, requested);
            shape = new Shape(slider.getWidth(), slider.getHeight(), percent, density, requested,
                    ((Number) XposedHelpers.callMethod(params, "getRadius")).floatValue(),
                    ((Number) XposedHelpers.callMethod(params, "getWeight")).floatValue());
            shapes.put(slider, shape);
        }
        // Do not reset mCurProgressRadius during touch animations. Correct only a stale
        // base radius/curve; native ensureSize and the mix-background writer do the rest.
        if (XposedHelpers.getFloatField(slider, "mBackgroundRadius") != shape.radius
                || XposedHelpers.getFloatField(slider, "mBackgroundRoundCornerWeight") != shape.weight)
            QsHooks.applyNaturalSliderRoundParams(layout, slider, shape.requested);
    }

    static void cleanup() { active = false; shapes.clear(); }

    private record Shape(int width, int height, int percent, float density,
            float requested, float radius, float weight) { }
}
