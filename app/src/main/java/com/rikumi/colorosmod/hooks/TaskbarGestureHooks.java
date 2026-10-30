package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class TaskbarGestureHooks {
    private static final String TASKBAR = "com.android.launcher3.taskbar.";
    private static final int FLAG_SLIPPERY = 1 << 29;
    private static final int EDGE_FROM_GESTURE = 512;
    private static final Map<View, HandleState> HANDLES = new IdentityHashMap<>();
    private static final Map<Object, Insets[]> APPLIED_INSETS = new IdentityHashMap<>();
    private static final Set<WindowManager.LayoutParams> SLIPPERY_OVERRIDES =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable REFRESH = TaskbarGestureHooks::refresh;
    private static boolean active;
    private static boolean replaying;
    private static String settingsSignature;
    private static PendingTouch pendingTouch;

    private TaskbarGestureHooks() { }

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> controller = XposedHelpers.findClassIfExists(
                TASKBAR + "StashedHandleViewController", lpparam.classLoader);
        if (controller == null) return;
        active = true;
        XposedBridge.hookAllMethods(controller, "init", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) register(param.thisObject);
            }
        });
        Class<?> oplus = XposedHelpers.findClassIfExists(
                TASKBAR + "stashedhandleview.OplusStashedHandleViewController", lpparam.classLoader);
        if (oplus != null) {
            XposedBridge.hookAllMethods(oplus, "startPressAnimation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (readBool(KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED, true)
                            || readBool(KEY_MBACK_ENABLED, false)) param.setResult(null);
                }
            });
        }
        XC_MethodHook destroy = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    remove((View) XposedHelpers.getObjectField(param.thisObject, "mStashedHandleView"));
                } catch (Throwable t) { log("taskbar gesture destroy failed: " + t); }
            }
        };
        XposedBridge.hookAllMethods(controller, "onDestroy", destroy);
        if (oplus != null) XposedBridge.hookAllMethods(oplus, "onDestroy", destroy);
        hookInsets(lpparam.classLoader);
        hookInput(lpparam.classLoader);
        hookWindowTouchFlags();
    }

    private static void hookWindowTouchFlags() {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[1] instanceof WindowManager.LayoutParams)) return;
                for (HandleState state : HANDLES.values()) {
                    if (state.host != param.args[0]) continue;
                    try {
                        updateWindowTouchFlags((WindowManager.LayoutParams) param.args[1],
                                state.shouldPreventWindowTransfer());
                    } catch (Throwable t) { log("taskbar touch window flags failed: " + t); }
                    return;
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.view.WindowManagerImpl", null,
                    "addView", View.class, ViewGroup.LayoutParams.class, callback);
        } catch (Throwable t) { log("taskbar touch window add hook failed: " + t); }
        try {
            XposedHelpers.findAndHookMethod("android.view.WindowManagerGlobal", null,
                    "updateViewLayout", View.class, ViewGroup.LayoutParams.class, callback);
        } catch (Throwable t) { log("taskbar touch window update hook failed: " + t); }
    }

    private static boolean updateSlipperyFlag(WindowManager.LayoutParams params, boolean preventTransfer) {
        int oldFlags = params.flags;
        if (preventTransfer) {
            if ((oldFlags & FLAG_SLIPPERY) != 0) SLIPPERY_OVERRIDES.add(params);
            params.flags &= ~FLAG_SLIPPERY;
        } else if (SLIPPERY_OVERRIDES.remove(params)) {
            params.flags |= FLAG_SLIPPERY;
        }
        return oldFlags != params.flags;
    }

    private static boolean updateWindowTouchFlags(WindowManager.LayoutParams params, boolean preventTransfer) {
        boolean changed = updateSlipperyFlag(params, preventTransfer);
        Object[] rotations = (Object[]) XposedHelpers.getObjectField(params, "paramsForRotation");
        if (rotations != null) for (Object rotation : rotations) {
            if (rotation instanceof WindowManager.LayoutParams) {
                changed |= updateSlipperyFlag((WindowManager.LayoutParams) rotation, preventTransfer);
            }
        }
        return changed;
    }

    private static void hookInsets(ClassLoader loader) {
        try {
            Class<?> insets = XposedHelpers.findClass(TASKBAR + "TaskbarInsetsController", loader);
            XposedHelpers.findAndHookMethod(TASKBAR + "TaskbarActivityContext", loader,
                    "notifyUpdateLayoutParams", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int extra = isGestureNav(param.thisObject)
                                        ? extraHeight((android.content.Context) param.thisObject) : 0;
                                Object params = XposedHelpers.callMethod(param.thisObject, "getWindowLayoutParams");
                                updateProvidedInsets(params, extra);
                                Object[] rotations = (Object[]) XposedHelpers.getObjectField(params, "paramsForRotation");
                                if (rotations != null) for (Object rotation : rotations) {
                                    if (rotation != null) updateProvidedInsets(rotation, extra);
                                }
                            } catch (Throwable t) { log("taskbar gesture height failed: " + t); }
                        }
                    });
            XposedHelpers.findAndHookMethod(insets, "updateInsetsTouchability",
                    XposedHelpers.findClass("android.view.ViewTreeObserver$InternalInsetsInfo", null),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object context = XposedHelpers.getObjectField(param.thisObject, "context");
                                HandleState state = findByContext(context);
                                if (state == null || !state.isUsable()) return;
                                boolean block = state.blockEnabled();
                                if (!block && !readBool(KEY_MBACK_ENABLED, false)) return;
                                Object info = param.args[0];
                                // Preserve fullscreen folders, taskbar icons and IME buttons.
                                if (XposedHelpers.getIntField(info, "mTouchableInsets") != 3) return;
                                Rect band = state.touchBounds(state.host, block);
                                if (band.isEmpty()) return;
                                Region region = (Region) XposedHelpers.getObjectField(info, "touchableRegion");
                                region.op(band, Region.Op.UNION);
                            } catch (Throwable t) { log("taskbar gesture touch region failed: " + t); }
                        }
                    });
        } catch (Throwable t) { log("taskbar gesture insets hook failed: " + t); }
    }

    private static void updateProvidedInsets(Object params, int extra) {
        Object[] providers = (Object[]) XposedHelpers.getObjectField(params, "providedInsets");
        if (providers == null) return;
        for (Object provider : providers) {
            int type = ((Number) XposedHelpers.callMethod(provider, "getType")).intValue();
            if (type != WindowInsets.Type.navigationBars()
                    && type != WindowInsets.Type.mandatorySystemGestures()) continue;
            Insets current = (Insets) XposedHelpers.callMethod(provider, "getInsetsSize");
            if (current == null) continue;
            Insets[] applied = APPLIED_INSETS.remove(provider);
            Insets original = applied != null && current == applied[1] ? applied[0] : current;
            Insets adjusted = original.bottom > 0 && extra > 0
                    ? Insets.of(original.left, original.top, original.right, original.bottom + extra)
                    : original;
            if (adjusted != original) APPLIED_INSETS.put(provider, new Insets[] {original, adjusted});
            XposedHelpers.callMethod(provider, "setInsetsSize", adjusted);
        }
    }

    private static void hookInput(ClassLoader loader) {
        try {
            Class<?> service = XposedHelpers.findClass(
                    "com.android.quickstep.OplusBaseTouchInteractionService", loader);
            XposedHelpers.findAndHookDeclaredMethod(service, "onInputEvent", InputEvent.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (replaying || !(param.args[0] instanceof MotionEvent)) return;
                            try {
                                if (onInput(param, (MotionEvent) param.args[0])) param.setResult(null);
                            } catch (Throwable t) {
                                cancelPendingTouch();
                                log("taskbar mback input failed: " + t);
                            }
                        }
                    });
            Class<?> dragLayer = XposedHelpers.findClass(TASKBAR + "OplusTaskbarDragLayer", loader);
            XposedHelpers.findAndHookDeclaredMethod(dragLayer, "dispatchTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            MotionEvent ev = (MotionEvent) param.args[0];
                            for (HandleState state : HANDLES.values()) {
                                if (state.host != param.thisObject) continue;
                                boolean blockDown = ev.getActionMasked() == MotionEvent.ACTION_DOWN
                                        && state.isUsable()
                                        && (state.blockEnabled() || readBool(KEY_MBACK_ENABLED, false))
                                        && state.touchBounds(null, state.blockEnabled())
                                                .contains((int) ev.getRawX(), (int) ev.getRawY());
                                TouchStream stream = (ev.getEdgeFlags() & EDGE_FROM_GESTURE) == 0
                                        ? state.windowTouches : state.forwardedTouches;
                                if (stream.onEvent(ev.getActionMasked(), ev.getDownTime(), blockDown)) {
                                    param.setResult(true);
                                    if (stream == state.windowTouches) state.syncWindowTouchFlags();
                                }
                                return;
                            }
                        }
                    });
        } catch (Throwable t) { log("taskbar mback input hook failed: " + t); }
    }

    private static boolean onInput(XC_MethodHook.MethodHookParam param, MotionEvent ev) throws Exception {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelPendingTouch();
            if (!active || !readBool(KEY_MBACK_ENABLED, false)) return false;
            for (HandleState state : HANDLES.values()) {
                if (!state.isUsable() || state.handle.getDisplay() == null
                        || state.handle.getDisplay().getDisplayId()
                        != ((Number) XposedHelpers.callMethod(ev, "getDisplayId")).intValue()) continue;
                if (state.touchBounds(null, false).contains((int) ev.getRawX(), (int) ev.getRawY())) {
                    pendingTouch = new PendingTouch(state, param.thisObject,
                            (Method) param.method, MotionEvent.obtain(ev));
                    GestureHooks.handleMBackTouch(state.handle, ev);
                    return true;
                }
            }
            return false;
        }
        PendingTouch touch = pendingTouch;
        if (touch == null || ev.getDownTime() != touch.down.getDownTime()) return false;
        if (touch.cancelled || !readBool(KEY_MBACK_ENABLED, false) || !touch.state.isUsable()) {
            abortPendingGesture();
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) cancelPendingTouch();
            return true;
        }
        int slop = ViewConfiguration.get(touch.state.handle.getContext()).getScaledTouchSlop();
        boolean moved = Math.abs(ev.getRawX() - touch.down.getRawX()) > slop
                || Math.abs(ev.getRawY() - touch.down.getRawY()) > slop;
        if (!touch.navigationTriggered
                && (moved || ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)) {
            pendingTouch = null;
            cancelMBack(touch);
            try {
                replaying = true;
                // Only defer DOWN until a tap becomes a swipe; never pilfer the swipe stream.
                touch.method.setAccessible(true);
                touch.method.invoke(touch.receiver, touch.down);
            } finally {
                replaying = false;
                touch.down.recycle();
            }
            return false;
        }
        GestureHooks.handleMBackTouch(touch.state.handle, ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_UP
                || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pendingTouch = null;
            touch.down.recycle();
        }
        return true;
    }

    private static void abortPendingGesture() {
        if (pendingTouch != null && !pendingTouch.cancelled) {
            pendingTouch.cancelled = true;
            cancelMBack(pendingTouch);
        }
    }

    private static void cancelMBack(PendingTouch touch) {
        MotionEvent cancel = MotionEvent.obtain(touch.down);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try { GestureHooks.handleMBackTouch(touch.state.handle, cancel); }
        finally { cancel.recycle(); }
    }

    private static void cancelPendingTouch() {
        PendingTouch touch = pendingTouch;
        pendingTouch = null;
        if (touch != null) {
            cancelMBack(touch);
            touch.down.recycle();
        }
    }

    private static void register(Object controller) {
        try {
            View handle = (View) XposedHelpers.getObjectField(controller, "mStashedHandleView");
            HandleState previous = HANDLES.get(handle);
            if (previous != null && handle.getOutlineProvider() == previous.outline) return;
            if (previous != null) remove(handle);
            HandleState state = new HandleState(controller, handle);
            if (state.originalOutline == null) return;
            HANDLES.put(handle, state);
            handle.setOutlineProvider(state.outline);
            handle.addOnAttachStateChangeListener(state);
            handle.getViewTreeObserver().addOnPreDrawListener(state);
            handle.invalidateOutline();
            scheduleRefresh();
        } catch (Throwable t) { log("taskbar gesture register failed: " + t); }
    }

    static RectF getHandleBounds(View handle, View host) {
        HandleState state = HANDLES.get(handle);
        return state == null ? null : state.boundsIn(host);
    }

    static boolean dispatchNavigation(View handle, boolean home) {
        HandleState state = HANDLES.get(handle);
        if (state == null) return false;
        if (!state.isUsable() || !readBool(KEY_MBACK_ENABLED, false)) return true;
        try {
            Object controllers = XposedHelpers.getObjectField(state.controller, "mControllers");
            Object buttons = XposedHelpers.getObjectField(controllers, "navButtonController");
            if (home && pendingTouch != null && pendingTouch.state == state) {
                pendingTouch.navigationTriggered = true;
            }
            XposedHelpers.callMethod(buttons, "onButtonClick", home ? 2 : 1);
        } catch (Throwable t) { log("taskbar mback navigation failed: " + t); }
        return true;
    }

    private static HandleState findByContext(Object context) {
        for (HandleState state : HANDLES.values()) if (state.context == context) return state;
        return null;
    }

    private static boolean isGestureNav(Object context) {
        return Boolean.TRUE.equals(XposedHelpers.callMethod(context, "isGestureNav"));
    }

    private static int extraHeight(android.content.Context context) {
        if (!active || !readBool(KEY_GESTURE_BAR_HEIGHT_ENABLED, false)) return 0;
        return Math.round(Math.max(0, Math.min(24, readInt(KEY_GESTURE_BAR_HEIGHT_DP, 12)))
                * context.getResources().getDisplayMetrics().density);
    }

    public static void onSettingsSnapshotPublished() {
        String signature = readInt(KEY_GESTURE_BAR_WIDTH_ENABLED, 1) + ":"
                + readInt(KEY_GESTURE_BAR_WIDTH_DP, 100) + ":"
                + readInt(KEY_GESTURE_BAR_HEIGHT_ENABLED, 0) + ":"
                + readInt(KEY_GESTURE_BAR_HEIGHT_DP, 12) + ":"
                + readInt(KEY_MBACK_ENABLED, 0) + ":"
                + readInt(KEY_GESTURE_TOUCH_THROUGH_ENABLED, 0);
        if (signature.equals(settingsSignature)) return;
        settingsSignature = signature;
        scheduleRefresh();
    }

    private static void scheduleRefresh() {
        if (!active) return;
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }

    private static void refresh() {
        for (HandleState state : new ArrayList<>(HANDLES.values())) {
            state.syncWindowTouchFlags();
            try {
                state.handle.invalidateOutline();
                if (!readBool(KEY_MBACK_ENABLED, false)) GestureHooks.removeMBackSurface(state.handle);
                state.host.requestLayout();
                Object controllers = XposedHelpers.getObjectField(state.controller, "mControllers");
                Object insets = XposedHelpers.getObjectField(controllers, "taskbarInsetsController");
                XposedHelpers.callMethod(insets, "onTaskbarWindowHeightOrInsetsChanged");
            } catch (Throwable t) { log("taskbar gesture refresh failed: " + t); }
        }
        if (!readBool(KEY_MBACK_ENABLED, false)) abortPendingGesture();
    }

    public static Object captureControllers() {
        return HANDLES.values().stream().map(state -> state.controller).toArray();
    }

    public static void restoreControllers(Object saved) {
        if (saved instanceof Object[]) for (Object controller : (Object[]) saved) register(controller);
    }

    public static void cleanupForHotReload() {
        active = false;
        MAIN.removeCallbacks(REFRESH);
        cancelPendingTouch();
        refresh();
        for (View handle : new ArrayList<>(HANDLES.keySet())) remove(handle);
        APPLIED_INSETS.clear();
        for (WindowManager.LayoutParams params : new ArrayList<>(SLIPPERY_OVERRIDES)) {
            updateSlipperyFlag(params, false);
        }
        settingsSignature = null;
    }

    private static void remove(View handle) {
        HandleState state = HANDLES.remove(handle);
        if (state == null) return;
        if (pendingTouch != null && pendingTouch.state == state) cancelPendingTouch();
        try {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams)
                    XposedHelpers.callMethod(state.context, "getWindowLayoutParams");
            if (params != null && updateWindowTouchFlags(params, false) && state.host.isAttachedToWindow()) {
                XposedHelpers.callMethod(state.context, "notifyUpdateLayoutParams");
            }
            updateProvidedInsets(params, 0);
            Object[] rotations = (Object[]) XposedHelpers.getObjectField(params, "paramsForRotation");
            if (rotations != null) for (Object rotation : rotations) {
                if (rotation != null) updateProvidedInsets(rotation, 0);
            }
        } catch (Throwable t) { log("taskbar gesture insets cleanup failed: " + t); }
        handle.removeOnAttachStateChangeListener(state);
        if (handle.getViewTreeObserver().isAlive()) handle.getViewTreeObserver().removeOnPreDrawListener(state);
        if (handle.getOutlineProvider() == state.outline) handle.setOutlineProvider(state.originalOutline);
        GestureHooks.removeMBackSurface(handle);
        handle.invalidateOutline();
    }

    private static final class TouchStream {
        long downTime = -1;
        boolean active;

        boolean onEvent(int action, long eventDownTime, boolean blockDown) {
            if (action == MotionEvent.ACTION_DOWN) {
                downTime = blockDown ? eventDownTime : -1;
                active = blockDown;
            }
            boolean consume = downTime != -1 && downTime == eventDownTime;
            if (consume && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                active = false;
            }
            return consume;
        }
    }

    private static final class PendingTouch {
        final HandleState state;
        final Object receiver;
        final Method method;
        final MotionEvent down;
        boolean cancelled;
        boolean navigationTriggered;

        PendingTouch(HandleState state, Object receiver, Method method, MotionEvent down) {
            this.state = state;
            this.receiver = receiver;
            this.method = method;
            this.down = down;
        }
    }

    private static final class HandleState implements View.OnAttachStateChangeListener,
            android.view.ViewTreeObserver.OnPreDrawListener {
        final Object controller;
        final Object context;
        final View handle;
        final View host;
        final ViewOutlineProvider originalOutline;
        final Rect localBounds = new Rect();
        final TouchStream windowTouches = new TouchStream();
        final TouchStream forwardedTouches = new TouchStream();
        final ViewOutlineProvider outline = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline out) {
                originalOutline.getOutline(view, out);
                if (!out.getRect(localBounds)) { localBounds.setEmpty(); return; }
                if (active && isGestureNav(context)) {
                    if (readBool(KEY_GESTURE_BAR_WIDTH_ENABLED, true)) {
                        int width = Math.round(Math.max(80, Math.min(120,
                                readInt(KEY_GESTURE_BAR_WIDTH_DP, 100))) * density());
                        int center = localBounds.centerX();
                        localBounds.left = center - width / 2;
                        localBounds.right = localBounds.left + width;
                    }
                    localBounds.offset(0, -Math.round(extraHeight(handle.getContext()) / 2f));
                    out.setRoundRect(localBounds, out.getRadius());
                }
                try {
                    Rect bounds = (Rect) XposedHelpers.getObjectField(controller, "mStashedHandleBounds");
                    bounds.set(localBounds);
                    XposedHelpers.callMethod(handle, "updateSampledRegion", bounds);
                } catch (Throwable t) { log("taskbar gesture bounds failed: " + t); }
            }
        };

        HandleState(Object controller, View handle) {
            this.controller = controller;
            this.handle = handle;
            context = XposedHelpers.getObjectField(controller, "mActivity");
            host = (View) XposedHelpers.callMethod(context, "getDragLayer");
            originalOutline = handle.getOutlineProvider();
        }

        float density() { return handle.getResources().getDisplayMetrics().density; }

        RectF boundsIn(View target) {
            RectF rect = new RectF(localBounds);
            Matrix matrix = new Matrix();
            handle.transformMatrixToGlobal(matrix);
            if (target != null) target.transformMatrixToLocal(matrix);
            matrix.mapRect(rect);
            if (target == null) {
                float[] origin = {0f, 0f};
                matrix.mapPoints(origin);
                int[] screen = new int[2];
                handle.getLocationOnScreen(screen);
                rect.offset(screen[0] - origin[0], screen[1] - origin[1]);
            }
            return rect;
        }

        Rect touchBounds(View target, boolean block) {
            RectF rect = boundsIn(target);
            float padding = GestureHooks.getMBackBandPaddingDp() * density();
            rect.inset(-padding, -padding);
            if (block) {
                int[] location = new int[2];
                if (target == null) host.getLocationOnScreen(location);
                rect.left = location[0];
                rect.right = rect.left + host.getWidth();
                rect.bottom = location[1] + host.getHeight();
            }
            Rect result = new Rect();
            rect.roundOut(result);
            return result;
        }

        boolean isUsable() {
            if (!active || !handle.isAttachedToWindow() || !handle.isShown()
                    || handle.getAlpha() < 0.01f || host.getAlpha() < 0.01f
                    || localBounds.isEmpty() || !isGestureNav(context)) return false;
            try {
                long flags = ((Number) XposedHelpers.getObjectField(context, "mSystemUiStateFlags")).longValue();
                if ((flags & (2L | 4L | 8L | 64L | 2048L)) != 0) return false;
            } catch (Throwable ignored) { }
            return true;
        }

        boolean blockEnabled() {
            if (!readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)) return false;
            try {
                Object controllers = XposedHelpers.getObjectField(controller, "mControllers");
                Object buttons = XposedHelpers.getObjectField(controllers, "navbarButtonsViewController");
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(buttons, "isImeVisible"))) return false;
            } catch (Throwable ignored) { }
            WindowInsets insets = handle.getRootWindowInsets();
            return insets == null || !insets.isVisible(WindowInsets.Type.ime());
        }

        boolean shouldPreventWindowTransfer() {
            return active && (windowTouches.active || (isUsable()
                    && (blockEnabled() || readBool(KEY_MBACK_ENABLED, false))));
        }

        void syncWindowTouchFlags() {
            try {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams)
                        XposedHelpers.callMethod(context, "getWindowLayoutParams");
                if (params != null && updateWindowTouchFlags(params, shouldPreventWindowTransfer())
                        && host.isAttachedToWindow()) {
                    XposedHelpers.callMethod(context, "notifyUpdateLayoutParams");
                }
            } catch (Throwable t) { log("taskbar touch window refresh failed: " + t); }
        }

        @Override public boolean onPreDraw() {
            syncWindowTouchFlags();
            if (isUsable() && readBool(KEY_MBACK_ENABLED, false)) {
                GestureHooks.updateMBackSurface(handle);
            } else {
                if (pendingTouch != null && pendingTouch.state == this) abortPendingGesture();
                GestureHooks.removeMBackSurface(handle);
            }
            return true;
        }

        @Override public void onViewAttachedToWindow(View view) {
            view.getViewTreeObserver().removeOnPreDrawListener(this);
            view.getViewTreeObserver().addOnPreDrawListener(this);
            scheduleRefresh();
        }

        @Override public void onViewDetachedFromWindow(View view) {
            windowTouches.active = false;
            forwardedTouches.active = false;
            syncWindowTouchFlags();
            if (pendingTouch != null && pendingTouch.state == this) abortPendingGesture();
            GestureHooks.removeMBackSurface(view);
            if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnPreDrawListener(this);
        }
    }
}
