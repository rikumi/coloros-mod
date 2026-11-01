package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.PathInterpolator;
import android.widget.TextView;
import com.rikumi.colorosmod.xposed.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.WeakHashMap;

/** ColorOS 17's View-based password dial, separate from COUINumericKeyboard. */
public final class PasswordDialHooks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View, Boolean> dials = new WeakHashMap<>();
    private static final WeakHashMap<View, Slide> slides = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> canceledGestures = new WeakHashMap<>();
    private static final WeakHashMap<View, Visual> visuals = new WeakHashMap<>();
    private static final WeakHashMap<View, Visual> keyVisuals = new WeakHashMap<>();
    private static final float SHRUNK_SCALE = 2f / 3f;
    private static boolean active = true;
    private static final Runnable REFRESH = PasswordDialHooks::refreshNow;

    private static boolean enabled(String key) { return active && readBoolCached(key, false); }

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> dial = XposedHelpers.findClassIfExists(
                "com.coui.appcompat.passworddial.COUIPasswordDial", lp.classLoader);
        if (dial == null) return;
        try {
            // Draw-time transforms leave the native reveal/press scale and pivots intact.
            XposedHelpers.findAndHookMethod(View.class, "draw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View key = (View) p.thisObject;
                    Visual visual = keyVisuals.get(key);
                    if (visual == null || visual.progress <= 0f) return;
                    Canvas canvas = (Canvas) p.args[0];
                    p.setObjectExtra("slideCanvasSave", canvas.save());
                    float scale = 1f + (SHRUNK_SCALE - 1f) * visual.progress;
                    canvas.scale(scale, scale, key.getWidth() / 2f, key.getHeight() / 2f);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object save = p.getObjectExtra("slideCanvasSave");
                    if (save instanceof Integer count) ((Canvas) p.args[0]).restoreToCount(count);
                }
            });
            XposedHelpers.findAndHookMethod(TextView.class, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View key = (View) p.thisObject;
                    Visual visual = keyVisuals.get(key);
                    if (visual == null || visual.progress <= 0f) return;
                    Canvas canvas = (Canvas) p.args[0];
                    // Only fade the digit; its circular material remains visible.
                    p.setObjectExtra("slideTextSave", canvas.saveLayerAlpha(0f, 0f,
                            key.getWidth(), key.getHeight(), Math.round(255f * (1f - visual.progress))));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object save = p.getObjectExtra("slideTextSave");
                    if (save instanceof Integer count) ((Canvas) p.args[0]).restoreToCount(count);
                }
            });
            XposedHelpers.findAndHookMethod(dial, "applyKeyRenderModeToKeys", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    dials.put((View) p.thisObject, true);
                    if (!enabled(KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED)) return;
                    p.setObjectExtra("originalKeyRenderMode", XposedHelpers.getIntField(p.thisObject, "keyRenderMode"));
                    // Native solid mode removes optics, inner shadows and environment
                    // light, while retaining the native circular background and text.
                    XposedHelpers.setIntField(p.thisObject, "keyRenderMode", 1);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object original = p.getObjectExtra("originalKeyRenderMode");
                    if (original instanceof Integer value)
                        XposedHelpers.setIntField(p.thisObject, "keyRenderMode", value);
                }
            });
            XposedHelpers.findAndHookMethod(dial, "playEnterAnimIfNeeded", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled(KEY_KEYGUARD_NO_DIGIT_SHAKE_ENABLED)) return;
                    // Use the native settled state, including its pending-layout path.
                    XposedHelpers.callMethod(p.thisObject, "showExpandedImmediately");
                    p.setResult(null);
                }
            });
            Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.coui.appcompat.passworddial.gesture.PasswordDialProgressiveController", lp.classLoader);
            if (controller != null) XposedHelpers.findAndHookMethod(controller, "playEnterAnimInternal", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled(KEY_KEYGUARD_NO_DIGIT_SHAKE_ENABLED)) return;
                    // Also cover idle auto-expansion and deferred layout callbacks.
                    XposedHelpers.callMethod(p.thisObject, "showExpandedImmediatelyInternal");
                    p.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!dial.isInstance(p.thisObject)) return;
                    View root = (View) p.thisObject;
                    MotionEvent event = (MotionEvent) p.args[0];
                    if (handleTouch(root, event)) p.setResult(true);
                }
            });
            XposedHelpers.findAndHookMethod(
                    "com.coui.appcompat.passworddial.PasswordUnlockGestureLayout", lp.classLoader,
                    "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            View root = findDial((View) p.thisObject, dial);
                            if (root == null) return;
                            MotionEvent event = (MotionEvent) p.args[0];
                            boolean capture = slides.containsKey(root) || canceledGestures.containsKey(root);
                            if (!capture && enabled(KEY_KEYGUARD_SLIDE_INPUT_ENABLED)
                                    && event.getActionMasked() == MotionEvent.ACTION_DOWN
                                    && root.isEnabled() && !touchExploration(root)) {
                                Matrix parentToGlobal = new Matrix(), rootToGlobal = new Matrix(), globalToRoot = new Matrix();
                                ((View) p.thisObject).transformMatrixToGlobal(parentToGlobal);
                                root.transformMatrixToGlobal(rootToGlobal);
                                float[] point = {event.getX(), event.getY()};
                                parentToGlobal.mapPoints(point);
                                if (rootToGlobal.invert(globalToRoot)) {
                                    globalToRoot.mapPoints(point);
                                    capture = hit(root, point[0], point[1]) != null;
                                }
                            }
                            if (!capture) return;
                            // The parent otherwise steals vertical MOVE events for its
                            // chain reveal gesture before the dial receives them.
                            Object bridge = XposedHelpers.getObjectField(p.thisObject, "touchBridge");
                            if (bridge == null) return;
                            p.setObjectExtra("nativeDialTouchBridge", bridge);
                            XposedHelpers.setObjectField(p.thisObject, "touchBridge", null);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Object bridge = p.getObjectExtra("nativeDialTouchBridge");
                            if (bridge != null) XposedHelpers.setObjectField(p.thisObject, "touchBridge", bridge);
                        }
                    });
            XposedHelpers.findAndHookMethod(dial, "onDetachedFromWindow", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View root = (View) p.thisObject;
                    cancel(root);
                    resetVisual(root);
                }
            });
            log("HOOK OK ColorOS 17 password dial light, slide input and digit entrance");
        } catch (Throwable error) { log("password dial hook failed: " + error); }
    }

    private static final class Slide {
        final HashMap<Integer, WeakReference<View>> keys = new HashMap<>();
        final Visual visual;
        boolean shrinkTriggered;
        Slide(Visual visual) { this.visual = visual; }
    }

    private static final class Visual {
        final ArrayList<WeakReference<View>> keys = new ArrayList<>();
        float progress;
        ValueAnimator animator;
        void invalidate() {
            for (WeakReference<View> reference : keys) {
                View key = reference.get();
                if (key != null) key.invalidate();
            }
        }
    }

    private static Visual visual(View root) {
        Visual visual = visuals.get(root);
        if (visual != null) return visual;
        visual = new Visual();
        Object value = XposedHelpers.getObjectField(root, "progressiveKeyViews");
        if (value instanceof Iterable<?> keys) for (Object item : keys) {
            if (item instanceof View key) {
                visual.keys.add(new WeakReference<>(key));
                keyVisuals.put(key, visual);
            }
        }
        visuals.put(root, visual);
        return visual;
    }

    private static void animate(Visual visual, float target) {
        if (visual.animator != null) visual.animator.cancel();
        ValueAnimator animator = ValueAnimator.ofFloat(visual.progress, target);
        visual.animator = animator;
        animator.setDuration(150L);
        animator.setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f));
        animator.addUpdateListener(animation -> {
            visual.progress = (float) animation.getAnimatedValue();
            visual.invalidate();
        });
        animator.start();
    }

    private static void resetVisual(View root) {
        Visual visual = visuals.remove(root);
        if (visual == null) return;
        if (visual.animator != null) visual.animator.cancel();
        visual.progress = 0f;
        visual.invalidate();
        for (WeakReference<View> reference : visual.keys) {
            View key = reference.get();
            if (key != null && keyVisuals.get(key) == visual) keyVisuals.remove(key);
        }
    }

    private static View findDial(View root, Class<?> dial) {
        if (dial.isInstance(root)) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = findDial(group.getChildAt(i), dial);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean handleTouch(View root, MotionEvent event) {
        Slide slide = slides.get(root);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) canceledGestures.remove(root);
        else if (canceledGestures.containsKey(root)) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) canceledGestures.remove(root);
            return true;
        }
        if (!enabled(KEY_KEYGUARD_SLIDE_INPUT_ENABLED) || !root.isEnabled() || touchExploration(root)) {
            if (slide == null) return false;
            cancel(root);
            resetVisual(root);
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) canceledGestures.put(root, true);
            return true; // Do not hand the native keyboard the tail of a captured gesture.
        }
        if (action == MotionEvent.ACTION_DOWN) {
            cancel(root);
            View hit = hit(root, event.getX(), event.getY());
            if (hit == null) return false; // Delete/confirm and empty space retain native gestures.
            slide = new Slide(visual(root));
            slides.put(root, slide);
            if (root.getParent() != null) root.getParent().requestDisallowInterceptTouchEvent(true);
            move(slide, event.getPointerId(0), hit);
            return true;
        }
        if (slide == null) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int pointer = event.getPointerId(i);
                if (slide.keys.containsKey(pointer)) move(slide, pointer, hit(root, event.getX(i), event.getY(i)));
            }
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int i = event.getActionIndex();
            move(slide, event.getPointerId(i), hit(root, event.getX(i), event.getY(i)));
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            release(slide.keys.remove(event.getPointerId(event.getActionIndex())));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancel(root);
        }
        return true;
    }

    private static boolean touchExploration(View root) {
        AccessibilityManager manager = root.getContext().getSystemService(AccessibilityManager.class);
        return manager != null && manager.isTouchExplorationEnabled();
    }

    private static View hit(View root, float x, float y) {
        Object value = XposedHelpers.getObjectField(root, "progressiveKeyViews");
        if (!(value instanceof Iterable<?> keys)) return null;
        Matrix rootToGlobal = new Matrix();
        root.transformMatrixToGlobal(rootToGlobal);
        float[] global = {x, y};
        rootToGlobal.mapPoints(global);
        Matrix keyToGlobal = new Matrix(), globalToKey = new Matrix();
        float[] local = new float[2];
        for (Object item : keys) {
            if (!(item instanceof View key) || !key.isEnabled() || !key.isClickable()
                    || key.getVisibility() != View.VISIBLE || key.getAlpha() <= 0f) continue;
            keyToGlobal.reset();
            key.transformMatrixToGlobal(keyToGlobal);
            if (!keyToGlobal.invert(globalToKey)) continue;
            local[0] = global[0];
            local[1] = global[1];
            globalToKey.mapPoints(local);
            float dx = local[0] - key.getWidth() / 2f;
            float dy = local[1] - key.getHeight() / 2f;
            // Always use the final shrunken circle, even during the 150 ms transition.
            // Inverse mapping includes native scale, translation, pivot and ancestors.
            float radius = Math.min(key.getWidth(), key.getHeight()) * SHRUNK_SCALE / 2f;
            if (dx * dx + dy * dy <= radius * radius) return key;
        }
        return null;
    }

    private static void move(Slide slide, int pointer, View key) {
        WeakReference<View> previous = slide.keys.get(pointer);
        if (previous != null && previous.get() == key) return;
        if (previous != null && previous.get() != null && !slide.shrinkTriggered) {
            slide.shrinkTriggered = true;
            animate(slide.visual, 1f);
        }
        release(previous);
        slide.keys.put(pointer, new WeakReference<>(key));
        if (key == null) return;
        // The original digit's listener performs input and retains lockout checks.
        // Cancel the synthetic press instead of sending UP, so it cannot click twice.
        press(key, MotionEvent.ACTION_DOWN);
        if (key.isHapticFeedbackEnabled()) key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        key.performClick();
    }

    private static void press(View key, int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, key.getWidth() / 2f, key.getHeight() / 2f, 0);
        try { key.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private static void release(WeakReference<View> reference) {
        View key = reference == null ? null : reference.get();
        if (key != null) press(key, MotionEvent.ACTION_CANCEL);
    }

    private static void cancel(View root) {
        Slide slide = slides.remove(root);
        if (slide == null) return;
        for (WeakReference<View> key : slide.keys.values()) release(key);
        animate(slide.visual, 0f);
        if (root.getParent() != null) root.getParent().requestDisallowInterceptTouchEvent(false);
    }

    public static void refresh() {
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }

    private static void refreshNow() {
        if (!enabled(KEY_KEYGUARD_SLIDE_INPUT_ENABLED)) {
            for (View root : new ArrayList<>(slides.keySet())) {
                cancel(root);
                canceledGestures.put(root, true);
            }
            for (View root : new ArrayList<>(visuals.keySet())) resetVisual(root);
        }
        for (View root : new ArrayList<>(dials.keySet())) {
            try { XposedHelpers.callMethod(root, "applyKeyRenderModeToKeys"); }
            catch (Throwable error) { log("password dial material refresh failed: " + error); }
        }
    }

    public static void cleanup() {
        active = false;
        MAIN.removeCallbacks(REFRESH);
        for (View root : new ArrayList<>(slides.keySet())) cancel(root);
        for (View root : new ArrayList<>(visuals.keySet())) resetVisual(root);
        refreshNow();
        dials.clear();
        canceledGestures.clear();
    }
}
