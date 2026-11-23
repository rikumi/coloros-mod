package com.rikumi.colorosmod.hooks;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import com.rikumi.colorosmod.xposed.*;
import java.util.ArrayList;
import java.util.WeakHashMap;
import static com.rikumi.colorosmod.XposedInit.log;

/** ColorOS 15 draws the slider foreground with two translucent blend passes. */
final class LegacyRadiantTrackHooks {
    private static final ThreadLocal<Frame> drawing = new ThreadLocal<>();
    private static final WeakHashMap<View, Boolean> views = new WeakHashMap<>();
    private static final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static boolean active = true;
    static {
        white.setColor(Color.WHITE);
        white.setBlendMode(BlendMode.SRC_OVER);
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> slider = XposedHelpers.findClass("com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar", lp.classLoader);
            Class<?> params = XposedHelpers.findClass("com.oplus.posteffect.ForegroundBlurParam", lp.classLoader);
            XposedHelpers.findAndHookMethod(slider, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (!active) return;
                    views.put(view, true);
                    if (!MergedRadiantHooks.enabled(view)) return;
                    Paint progress = (Paint) XposedHelpers.getObjectField(view, "mProgressPaint");
                    Frame frame = new Frame(drawing.get(), view, progress,
                            XposedHelpers.getIntField(view, "mProgressColor"));
                    p.setObjectExtra("legacyRadiantTrack", frame);
                    drawing.set(frame);
                    // The non-mix COUI path reads this color while drawing. Keep
                    // the original ColorStateList and native state transitions.
                    XposedHelpers.setIntField(view, "mProgressColor", Color.WHITE);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.getObjectExtra("legacyRadiantTrack") instanceof Frame frame)) return;
                    try {
                        XposedHelpers.setIntField(frame.view, "mProgressColor", frame.color);
                        frame.progress.setColor(frame.paintColor);
                    } finally {
                        if (frame.previous == null) drawing.remove(); else drawing.set(frame.previous);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(slider, "drawForegroundBlur", Canvas.class, Paint.class,
                    params, Path.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Frame frame = drawing.get();
                            if (!active || frame == null || p.args[1] != XposedHelpers.getObjectField(frame.view, "activeTrackPaint")) return;
                            // Reuse the native clipped/smoothed active path. The
                            // inactive pass and the slider's overall alpha stay native.
                            Canvas canvas = (Canvas) p.args[0];
                            int save = SliderActiveCornersHooks.clipActiveTrack(frame.view, canvas);
                            try { canvas.drawPath((Path) p.args[3], white); }
                            finally { SliderActiveCornersHooks.restoreActiveTrackClip(frame.view, canvas, save); }
                            p.setResult(null);
                        }
                    });
            // The helper is inlined into onDraw in this SystemUI build.
            XposedBridge.deoptimize(XposedHelpers.findMethodExact(slider, "onDraw", Canvas.class));
        } catch (Throwable error) { log("legacy radiant track hooks unavailable: " + error); }
    }

    static void refresh() {
        for (View view : new ArrayList<>(views.keySet())) view.invalidate();
    }

    static void cleanup() {
        active = false;
        refresh();
        views.clear();
    }

    private static final class Frame {
        final Frame previous;
        final View view;
        final Paint progress;
        final int color, paintColor;
        Frame(Frame previous, View view, Paint progress, int color) {
            this.previous = previous;
            this.view = view;
            this.progress = progress;
            this.color = color;
            paintColor = progress.getColor();
        }
    }
    private LegacyRadiantTrackHooks() {}
}
