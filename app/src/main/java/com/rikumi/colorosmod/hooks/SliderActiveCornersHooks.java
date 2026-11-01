package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Apply the same 2dp top corners as Flyme Mod to the active track only. */
public final class SliderActiveCornersHooks {
    private static final String KEY = "slider_active_top_corners";
    private static final class ClipState {
        final Path path = new Path();
        final float[] radii = new float[8];
    }
    private static final WeakHashMap<View, ClipState> sliders = new WeakHashMap<>();

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        Class<?> slider = XposedHelpers.findClassIfExists(
                "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar", pkg.classLoader);
        if (slider == null) return;
        XC_MethodHook clip = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!slider.isInstance(p.thisObject)) return;
                View view = (View) p.thisObject;
                ClipState state = sliders.computeIfAbsent(view, key -> new ClipState());
                if (!readBoolCached(KEY, false)) return;
                // The thumb variant already draws a rounded active track and must retain its thumb.
                if (XposedHelpers.getBooleanField(view, "mShowThumb")) return;
                Rect progress = (Rect) XposedHelpers.getObjectField(view, "mProgressRect");
                Rect track = (Rect) XposedHelpers.getObjectField(view, "mClipProgressRect");
                float left = Math.max(progress.left, track.left);
                float right = Math.min(progress.right, track.right);
                float top = Math.max(progress.top, track.top);
                float bottom = Math.min(progress.bottom, track.bottom);
                if (right <= left || bottom <= top) return;
                float radius = Math.min(2f * view.getResources().getDisplayMetrics().density,
                        Math.min((right - left) / 2f, (bottom - top) / 2f));
                state.radii[0] = state.radii[1] = state.radii[2] = state.radii[3] = radius;
                state.path.reset();
                state.path.addRoundRect(left, top, right, bottom, state.radii, Path.Direction.CW);
                Canvas canvas = (Canvas) p.args[0];
                int save = canvas.save();
                p.setObjectExtra(KEY, save);
                canvas.clipPath(state.path);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object save = p.getObjectExtra(KEY);
                if (save instanceof Integer count) ((Canvas) p.args[0]).restoreToCount(count);
            }
        };
        // ColorOS 17 mix/blur rendering; older ROMs and non-blur themes use the COUI path.
        try { XposedHelpers.findAndHookMethod(slider, "drawActiveMixColorTrack", Canvas.class, clip); }
        catch (Throwable t) { log("slider corners mix path unavailable: " + t); }
        try { XposedHelpers.findAndHookMethod(slider, "drawActiveTrack", Canvas.class, clip); }
        catch (Throwable t) { log("slider corners plain path unavailable: " + t); }
        // Deoptimize callers too: the active draw method may have been inlined before hot reload.
        try { XposedHelpers.findAndHookMethod(slider, "onDraw", Canvas.class, new XC_MethodHook() {}); }
        catch (Throwable t) { log("slider corners mix caller unavailable: " + t); }
        try {
            XposedHelpers.findAndHookMethod("com.coui.appcompat.seekbar.COUIVerticalSeekBar",
                    pkg.classLoader, "onDraw", Canvas.class, new XC_MethodHook() {});
        } catch (Throwable t) { log("slider corners plain caller unavailable: " + t); }
    }

    public static void refresh() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (View view : new ArrayList<>(sliders.keySet())) if (view != null) view.invalidate();
        });
    }

    public static Object capture() { return new ArrayList<>(sliders.keySet()); }

    public static void restore(Object state) {
        if (state instanceof Iterable<?> hosts) for (Object host : hosts)
            if (host instanceof View view) sliders.put(view, new ClipState());
        refresh();
    }
}
