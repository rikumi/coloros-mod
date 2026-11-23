package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Paint;
import android.os.Build;
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
    private static final ThreadLocal<ClipFrame> clips = new ThreadLocal<>();
    private static final ThreadLocal<View> legacyDrawing = new ThreadLocal<>();
    private static final class ClipFrame {
        final ClipFrame previous;
        final View view;
        final Canvas canvas;
        final int save;
        ClipFrame(ClipFrame previous, View view, Canvas canvas, int save) {
            this.previous = previous; this.view = view; this.canvas = canvas; this.save = save;
        }
    }

    static int clipActiveTrack(View view, Canvas canvas) {
        ClipState state = sliders.computeIfAbsent(view, key -> new ClipState());
        if (!readBoolCached(KEY, false) || XposedHelpers.getBooleanField(view, "mShowThumb")) return -1;
        for (ClipFrame frame = clips.get(); frame != null; frame = frame.previous)
            if (frame.view == view && frame.canvas == canvas) return -1;
        Rect progress = (Rect) XposedHelpers.getObjectField(view, "mProgressRect");
        Rect track = (Rect) XposedHelpers.getObjectField(view, "mClipProgressRect");
        float left = Math.max(progress.left, track.left), right = Math.min(progress.right, track.right);
        float top = Math.max(progress.top, track.top), bottom = Math.min(progress.bottom, track.bottom);
        if (right <= left || bottom <= top) return -1;
        float radius = Math.min(2f * view.getResources().getDisplayMetrics().density,
                Math.min((right - left) / 2f, (bottom - top) / 2f));
        state.radii[0] = state.radii[1] = state.radii[2] = state.radii[3] = radius;
        state.path.reset();
        state.path.addRoundRect(left, top, right, bottom, state.radii, Path.Direction.CW);
        int save = canvas.save();
        clips.set(new ClipFrame(clips.get(), view, canvas, save));
        canvas.clipPath(state.path);
        return save;
    }

    static void restoreActiveTrackClip(View view, Canvas canvas, int save) {
        if (save < 0) return;
        try { canvas.restoreToCount(save); }
        finally {
            ClipFrame frame = clips.get();
            if (frame != null && frame.view == view && frame.canvas == canvas && frame.save == save) {
                if (frame.previous == null) clips.remove(); else clips.set(frame.previous);
            }
        }
    }

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        Class<?> slider = XposedHelpers.findClassIfExists(
                "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar", pkg.classLoader);
        if (slider == null) return;
        XC_MethodHook clip = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!slider.isInstance(p.thisObject)) return;
                int save = clipActiveTrack((View) p.thisObject, (Canvas) p.args[0]);
                if (save >= 0) p.setObjectExtra(KEY, save);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object save = p.getObjectExtra(KEY);
                if (save instanceof Integer count)
                    restoreActiveTrackClip((View) p.thisObject, (Canvas) p.args[0], count);
            }
        };
        if (Build.VERSION.SDK_INT == 35) hookLegacyMix(pkg, slider);
        // Newer mix/blur rendering and the shared non-blur COUI path.
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

    private static void hookLegacyMix(XC_LoadPackage.LoadPackageParam pkg, Class<?> slider) {
        try {
            Class<?> params = XposedHelpers.findClass("com.oplus.posteffect.ForegroundBlurParam", pkg.classLoader);
            XposedHelpers.findAndHookMethod(slider, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("legacyCornersOwner", legacyDrawing.get());
                    legacyDrawing.set((View) p.thisObject);
                    sliders.computeIfAbsent((View) p.thisObject, key -> new ClipState());
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View previous = (View) p.getObjectExtra("legacyCornersOwner");
                    if (previous == null) legacyDrawing.remove(); else legacyDrawing.set(previous);
                }
            });
            XposedHelpers.findAndHookMethod(slider, "drawForegroundBlur", Canvas.class, Paint.class,
                    params, Path.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            View view = legacyDrawing.get();
                            if (view == null || p.args[1] != XposedHelpers.getObjectField(view, "activeTrackPaint")) return;
                            int save = clipActiveTrack(view, (Canvas) p.args[0]);
                            if (save >= 0) {
                                p.setObjectExtra(KEY, save);
                                p.setObjectExtra("legacyCornersView", view);
                            }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.getObjectExtra(KEY) instanceof Integer save)
                                restoreActiveTrackClip((View) p.getObjectExtra("legacyCornersView"), (Canvas) p.args[0], save);
                        }
                    });
            com.rikumi.colorosmod.xposed.XposedBridge.deoptimize(
                    XposedHelpers.findMethodExact(slider, "onDraw", Canvas.class));
        } catch (Throwable error) { log("slider corners legacy mix path unavailable: " + error); }
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
