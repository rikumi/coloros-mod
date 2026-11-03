package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.TextSwitcher;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

public final class GestureHooks {
    private static volatile boolean sImeVisible;
    // 判定为向左/右/上划动的阈值（dp），超过则放弃 MBack 接管。
    static final int MBACK_SWIPE_DP = 20;

    static final float ROTATION_BUTTON_MARGIN_DP = 12f;

    static final java.util.WeakHashMap<Object, Boolean> sBarExtraApplied =
            new java.util.WeakHashMap<>();

    public static void hookRotationButtonNativePosition(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        final Class<?> buttonClass;
        try {
            buttonClass = XposedHelpers.findClass(
                    "com.android.systemui.shared.rotation.FloatingRotationButton",
                    lpparam.classLoader);
        } catch (Throwable t) {
            log("rotation button fixed position class failed: " + t);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(buttonClass,
                    "adjustViewPositionAndCreateLayoutParams", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_ROTATION_BUTTON_FIXED_POSITION_ENABLED, false)) return;
                            try {
                                int rotation = XposedHelpers.getIntField(
                                        param.thisObject, "mDisplayRotation");
                                int gravity = rotationButtonGravity(rotation,
                                        rotationButtonSuggestedRotation(
                                                param.thisObject, rotation));
                                android.view.WindowManager.LayoutParams windowParams =
                                        (android.view.WindowManager.LayoutParams) param.getResult();
                                if (windowParams != null) {
                                    windowParams.gravity = gravity;
                                    windowParams.y = Math.round(
                                            ROTATION_BUTTON_MARGIN_DP * readDensity());
                                }

                                android.view.View button = (android.view.View)
                                        XposedHelpers.getObjectField(param.thisObject, "mKeyButtonView");
                                android.view.ViewGroup.LayoutParams raw = button.getLayoutParams();
                                if (raw instanceof android.widget.FrameLayout.LayoutParams) {
                                    ((android.widget.FrameLayout.LayoutParams) raw).gravity = gravity;
                                    button.setLayoutParams(raw);
                                }
                            } catch (Throwable t) {
                                log("rotation button fixed position apply failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("rotation button fixed position layout hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(buttonClass, "updateTranslation", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_ROTATION_BUTTON_FIXED_POSITION_ENABLED, false)) return;
                            try {
                                int rotation = XposedHelpers.getIntField(
                                        param.thisObject, "mDisplayRotation");
                                int originalMargin = XposedHelpers.getIntField(
                                        param.thisObject, "mSideMargin");
                                param.setObjectExtra(
                                        "rotation_button_side_margin", originalMargin);
                                int margin = Math.round(
                                        ROTATION_BUTTON_MARGIN_DP * readDensity());
                                if (isRotationButtonOnRight(rotation,
                                        rotationButtonSuggestedRotation(
                                                param.thisObject, rotation))) {
                                    margin = -margin;
                                }
                                XposedHelpers.setIntField(
                                        param.thisObject, "mSideMargin", margin);
                            } catch (Throwable t) {
                                log("rotation button side margin apply failed: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object margin = param.getObjectExtra("rotation_button_side_margin");
                            if (margin instanceof Integer) {
                                try {
                                    XposedHelpers.setIntField(param.thisObject, "mSideMargin",
                                            (Integer) margin);
                                } catch (Throwable t) {
                                    log("rotation button side margin restore failed: " + t);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            log("rotation button fixed position translation hook failed: " + t);
        }
    }

    static int rotationButtonSuggestedRotation(Object button, int rotation) {
        try {
            Object controller = XposedHelpers.getObjectField(
                    button, "mRotationButtonController");
            if (controller == null) return rotation;
            return XposedHelpers.getIntField(controller, "mLastRotationSuggestion");
        } catch (Throwable t) {
            return rotation;
        }
    }

    static boolean isRotationButtonOnRight(int rotation, int suggested) {
        return (suggested - rotation + 4) % 4 == 3;
    }

    // 按钮恒贴当前屏幕底边(BOTTOM): 两条底边的夹角只能落在下边，不会跑到屏幕顶部。
    static int rotationButtonGravity(int rotation, int suggested) {
        return android.view.Gravity.BOTTOM | (isRotationButtonOnRight(rotation, suggested)
                ? android.view.Gravity.RIGHT
                : android.view.Gravity.LEFT);
    }

    public static void hookGestureBarLongPressDisable(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> controllerClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.animator.OplusHandleAnimatorController",
                    lpparam.classLoader);
            Class<?> animTypeClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.animator.OplusHandleAnimType",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(controllerClass, "doHandleAnimator",
                    animTypeClass, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED, true)
                                    && "StartLongPressAnim".equals(String.valueOf(param.args[0]))) {
                                param.setResult(null);
                            }
                        }
                    });

            Class<?> oldAnimTypeClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.animator.OplusHandleOldAnimType",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(controllerClass, "doHandleOldAnimator",
                    oldAnimTypeClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED, true)
                                    && "StartOldLongPressAnim".equals(String.valueOf(param.args[0]))) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("gesture_bar long press hook failed: " + t);
        }
    }

    static final long MBACK_RIPPLE_HIDE_DELAY_MS = 280L;

    private static final class MBackGesture {
        boolean down;
        boolean cancelled;
        boolean longPress;
        long downTime;
        float downX;
        float downY;
        android.view.View handle;
        java.lang.Runnable longPressRunnable;
    }
    private static final java.util.Map<Long, MBackGesture> MBACK_GESTURES =
            new java.util.concurrent.ConcurrentHashMap<Long, MBackGesture>();
    private static final java.util.Map<Long, Boolean> MBACK_IN_RANGE =
            new java.util.concurrent.ConcurrentHashMap<Long, Boolean>();

    public static void hookMBack(final XC_LoadPackage.LoadPackageParam lpparam) {
        GestureTouchReplayHooks.hook(lpparam.classLoader);
        MBackRecentsHooks.hook(lpparam.classLoader);
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(handleClass, "handleValidTouchEvent",
                    android.view.MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.view.View handle = (android.view.View) param.thisObject;
                            android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                            if (handleNativeMBackTouch(handle, ev)) param.setResult(null);
                        }
                    });
        } catch (Throwable t) {
            log("mback hook failed: " + t);
        }

        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(handleClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            syncMBackSurface((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("mback surface attach hook failed: " + t);
        }
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(handleClass, "onLayout", boolean.class,
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            syncMBackSurface((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("mback surface layout hook failed: " + t);
        }
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(handleClass, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            removeMBackSurface((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("mback surface detach hook failed: " + t);
        }
    }

    private static final int FLAG_SLIPPERY = 1 << 29;
    private static final java.util.Map<View, GestureTouchWindowState> GESTURE_TOUCH_WINDOWS =
            new java.util.WeakHashMap<>();
    private static boolean sGestureTouchHooksActive;
    private static final android.os.Handler GESTURE_TOUCH_MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable REFRESH_GESTURE_TOUCH = () -> {
        if (!sGestureTouchHooksActive) return;
        for (View root : new java.util.ArrayList<>(GESTURE_TOUCH_WINDOWS.keySet())) {
            syncGestureTouchWindow(root);
            root.requestApplyInsets();
            root.requestLayout();
        }
    };

    public static void onGestureTouchSettingsChanged() {
        GESTURE_TOUCH_MAIN.removeCallbacks(REFRESH_GESTURE_TOUCH);
        GESTURE_TOUCH_MAIN.post(REFRESH_GESTURE_TOUCH);
    }

    private static boolean isNavigationFrame(View view) {
        String name = view.getClass().getName();
        return name.equals("com.android.systemui.navigationbar.views.NavigationBarFrame")
                || name.equals("com.android.systemui.navigationbar.NavigationBarFrame");
    }

    private static View findNavigationFrame(View view) {
        while (view != null) {
            if (isNavigationFrame(view)) return view;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return null;
    }

    private static boolean isPhoneGestureBlockActive(View root) {
        if (!sGestureTouchHooksActive || !root.isAttachedToWindow()) return false;
        View handle = findHandleInTree(root);
        return handle != null && handle.isShown() && handle.getAlpha() > 0f
                && isGestureBlockActive(handle);
    }

    private static void syncGestureTouchWindow(View view) {
        View root = findNavigationFrame(view);
        if (root == null) return;
        GestureTouchWindowState state = GESTURE_TOUCH_WINDOWS.get(root);
        if (state == null) {
            if (!sGestureTouchHooksActive) return;
            state = new GestureTouchWindowState();
            GESTURE_TOUCH_WINDOWS.put(root, state);
        }
        if (!root.isAttachedToWindow()) state.touchActive = false;
        if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
        try {
            boolean protect = sGestureTouchHooksActive
                    && (state.touchActive || isPhoneGestureBlockActive(root));
            if (state.updateFlags(params, protect) && root.isAttachedToWindow()) {
                root.getContext().getSystemService(WindowManager.class).updateViewLayout(root, params);
            }
        } catch (Throwable t) { log("gesture touch window sync failed: " + t); }
    }

    private static void hookGestureTouchWindow(ClassLoader loader) {
        XC_MethodHook windowHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof View)
                        || !(param.args[1] instanceof WindowManager.LayoutParams)) return;
                View root = (View) param.args[0];
                if (!isNavigationFrame(root)) return;
                GestureTouchWindowState state = GESTURE_TOUCH_WINDOWS.get(root);
                if (state == null) {
                    if (!sGestureTouchHooksActive) return;
                    state = new GestureTouchWindowState();
                    GESTURE_TOUCH_WINDOWS.put(root, state);
                }
                try {
                    state.updateFlags((WindowManager.LayoutParams) param.args[1],
                            sGestureTouchHooksActive && (state.touchActive || isPhoneGestureBlockActive(root)));
                } catch (Throwable t) { log("gesture touch window flags failed: " + t); }
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.view.WindowManagerImpl", null,
                    "addView", View.class, ViewGroup.LayoutParams.class, windowHook);
        } catch (Throwable t) { log("gesture touch window add hook failed: " + t); }
        try {
            XposedHelpers.findAndHookMethod("android.view.WindowManagerGlobal", null,
                    "updateViewLayout", View.class, ViewGroup.LayoutParams.class, windowHook);
        } catch (Throwable t) { log("gesture touch window update hook failed: " + t); }
        for (String prefix : new String[] {
                "com.android.systemui.navigationbar.views.", "com.android.systemui.navigationbar."}) {
            Class<?> frame = XposedHelpers.findClassIfExists(prefix + "NavigationBarFrame", loader);
            if (frame != null) {
                try {
                    XposedHelpers.findAndHookDeclaredMethod(frame, "dispatchTouchEvent", MotionEvent.class,
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam param) {
                                    View root = (View) param.thisObject;
                                    MotionEvent event = (MotionEvent) param.args[0];
                                    if (event == null) return;
                                    syncGestureTouchWindow(root);
                                    GestureTouchWindowState state = GESTURE_TOUCH_WINDOWS.get(root);
                                    if (state == null) return;
                                    boolean blockDown = event.getActionMasked() == MotionEvent.ACTION_DOWN
                                            && isPhoneGestureBlockActive(root)
                                            && event.getX() >= 0 && event.getX() < root.getWidth()
                                            && event.getY() >= computeGestureBandTop(root, getGestureBarHeightPx(root))
                                            && event.getY() < root.getHeight();
                                    if (state.onTouch(event.getActionMasked(), event.getDownTime(), blockDown)) {
                                        try { routeMBackWindowTouch(root, event); }
                                        catch (Throwable error) { log("mback window touch routing failed: " + error); }
                                        // This window owns the protected stream. Dispatching it
                                        // to native children first lets their gesture/dead-zone
                                        // handlers cancel or take over mBack before we consume it.
                                        param.setResult(true);
                                        syncGestureTouchWindow(root);
                                    }
                                }
                            });
                } catch (Throwable t) { log("gesture touch frame hook failed: " + t); }
            }
            Class<?> navView = XposedHelpers.findClassIfExists(prefix + "NavigationBarView", loader);
            if (navView != null) {
                try {
                    XposedHelpers.findAndHookDeclaredMethod(navView, "setSlippery", boolean.class,
                            new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam param) {
                                    View root = findNavigationFrame((View) param.thisObject);
                                    GestureTouchWindowState state = GESTURE_TOUCH_WINDOWS.get(root);
                                    if (state != null && root.getLayoutParams() instanceof WindowManager.LayoutParams) {
                                        state.onNativeSlipperyRequest((WindowManager.LayoutParams) root.getLayoutParams(),
                                                (Boolean) param.args[0]);
                                    }
                                }

                                @Override protected void afterHookedMethod(MethodHookParam param) {
                                    syncGestureTouchWindow((View) param.thisObject);
                                }
                            });
                } catch (Throwable t) { log("gesture touch slippery hook failed: " + t); }
            }
        }
    }

    private static final class GestureTouchWindowState {
        private final java.util.Set<WindowManager.LayoutParams> modified =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        long downTime = -1;
        boolean touchActive;

        boolean onTouch(int action, long eventDownTime, boolean blockDown) {
            if (action == MotionEvent.ACTION_DOWN) {
                downTime = blockDown ? eventDownTime : -1;
                touchActive = blockDown;
            }
            boolean consume = downTime != -1 && downTime == eventDownTime;
            if (consume && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                touchActive = false;
            }
            return consume;
        }

        void onNativeSlipperyRequest(WindowManager.LayoutParams params, boolean slippery) {
            if (!slippery) modified.remove(params);
        }

        boolean updateFlag(WindowManager.LayoutParams params, boolean protect) {
            int before = params.flags;
            if (protect) {
                if ((before & FLAG_SLIPPERY) != 0) modified.add(params);
                params.flags &= ~FLAG_SLIPPERY;
            } else if (modified.remove(params)) {
                params.flags |= FLAG_SLIPPERY;
            }
            return params.flags != before;
        }

        boolean updateFlags(WindowManager.LayoutParams params, boolean protect) {
            boolean changed = updateFlag(params, protect);
            Object rotations = XposedHelpers.getObjectField(params, "paramsForRotation");
            if (rotations instanceof Object[]) for (Object rotated : (Object[]) rotations) {
                if (rotated instanceof WindowManager.LayoutParams) {
                    changed |= updateFlag((WindowManager.LayoutParams) rotated, protect);
                }
            }
            return changed;
        }

        void restore() {
            touchActive = false;
            downTime = -1;
            for (WindowManager.LayoutParams params : modified) params.flags |= FLAG_SLIPPERY;
            modified.clear();
        }
    }

    public static void hookGestureTouchThrough(final XC_LoadPackage.LoadPackageParam lpparam) {
        sGestureTouchHooksActive = true;
        hookGestureTouchWindow(lpparam.classLoader);
        try {
            Class<?> navigationBarClass = findNavigationBarClass(lpparam.classLoader);
            boolean legacyNavigationBar = navigationBarClass.getName().equals(
                    "com.android.systemui.navigationbar.NavigationBar");
            XC_MethodHook imeHook = new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object navView = XposedHelpers.getObjectField(param.thisObject, "mView");
                                if (!readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)) {
                                    sImeVisible = false;
                                    if (navView instanceof android.view.View) {
                                        removeGestureBlockSurfaces((android.view.View) navView);
                                    }
                                    return;
                                }
                                Object helper = XposedHelpers.getObjectField(
                                        param.thisObject, "mNavBarHelper");
                                int imeVisibility = param.args.length >= 5
                                        ? ((Number) param.args[2]).intValue()
                                        : ((Number) param.args[1]).intValue();
                                sImeVisible = callImeVisibility(helper, imeVisibility,
                                        param.args.length >= 5);
                                if (!sImeVisible) {
                                    sImeVisible = XposedHelpers.getBooleanField(
                                            param.thisObject, "mImeVisible");
                                }
                                if (navView instanceof android.view.View) {
                                    syncGestureTouchWindow((View) navView);
                                    if (sImeVisible) removeGestureBlockSurfaces((View) navView);
                                }
                            } catch (Throwable ignored) { }
                        }
                    };
            if (legacyNavigationBar) {
                XposedHelpers.findAndHookMethod(navigationBarClass, "setImeWindowStatus",
                        int.class, android.os.IBinder.class, int.class, int.class, boolean.class, imeHook);
            } else {
                XposedHelpers.findAndHookMethod(navigationBarClass, "setImeWindowStatus",
                        int.class, int.class, int.class, boolean.class, imeHook);
            }
        } catch (Throwable t) {
            dbg("gesture IME state hook failed: " + t);
        }
        try {
            // InternalInsetsInfo 为 @hide 类, 编译期不可见, 用反射访问。
            Class<?> insetsInfoClass = XposedHelpers.findClass(
                    "android.view.ViewTreeObserver$InternalInsetsInfo", null);
            Class<?> navigationBarClass = findNavigationBarClass(lpparam.classLoader);
            XC_MethodHook insetsHook = new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object navBar = XposedHelpers.getObjectField(param.thisObject, "f$0");
                                Object viewObj = XposedHelpers.getObjectField(navBar, "mView");
                                if (viewObj instanceof android.view.View) {
                                    android.view.View view = (android.view.View) viewObj;
                                    if (!readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)) {
                                        removeGestureBlockSurfaces(view);
                                        return;
                                    }
                                    boolean navImeVisible = isImeVisible(view);
                                    try {
                                        navImeVisible = navImeVisible || XposedHelpers.getBooleanField(
                                                navBar, "mImeVisible");
                                    } catch (Throwable ignored) { }
                                    if (navImeVisible) {

                                        removeGestureBlockSurfaces(view);
                                        return;
                                    }
                                    syncGestureBlockSurface(view);
                                    // 通知中心/控制中心展开时保留系统原始区域, 不做任何拦截。
                                    if (!isGestureBlockActive(view)) return;

                                    int topY = computeGestureBandTop(view, getGestureBarHeightPx(view));
                                    Object info = param.args[0];
                                    Object regionObj = XposedHelpers.getObjectField(info, "touchableRegion");
                                    if (regionObj instanceof android.graphics.Region) {
                                        ((android.graphics.Region) regionObj).set(
                                                0, topY, view.getWidth(), view.getHeight());
                                    }
                                    // TOUCHABLE_INSETS_REGION = 3
                                    XposedHelpers.callMethod(info, "setTouchableInsets", 3);
                                    XposedHelpers.setAdditionalInstanceField(view,
                                            "gesture_block_insets_applied", Boolean.TRUE);
                                }
                            } catch (Throwable t) {
                                dbg("gesture touch-through region error: " + t);
                            }
                        }
                    };
            // R8 changes synthetic class numbers between ROM builds. Resolve the
            // listener from the owning field instead, including existing hot-reload hosts.
            try {
                Class<?> declaredListener = navigationBarClass.getDeclaredField(
                        "mOnComputeInternalInsetsListener").getType();
                if (!declaredListener.isInterface()) {
                    hookGestureInsetsListener(declaredListener, insetsInfoClass, insetsHook);
                }
            } catch (NoSuchFieldException ignored) { }
            // Some builds declare the field as the listener interface. Resolve its
            // actual class when NavigationBar registers it with ViewTreeObserver.
            Class<?> listenerInterface = XposedHelpers.findClass(
                    "android.view.ViewTreeObserver$OnComputeInternalInsetsListener", null);
            XposedHelpers.findAndHookMethod(android.view.ViewTreeObserver.class,
                    "addOnComputeInternalInsetsListener", listenerInterface, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object listener = param.args[0];
                    if (listener == null) return;
                    try {
                        Object owner = XposedHelpers.getObjectField(listener, "f$0");
                        if (navigationBarClass.isInstance(owner)) {
                            hookGestureInsetsListener(listener.getClass(), insetsInfoClass, insetsHook);
                        }
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable t) {
            dbg("gesture touch-through region hook failed: " + t);
        }
        try {
            // 拦截层消费进入窗口视图树的手势带触摸。
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            // 同 mBack: 限定本类声明, 避免上溯到 android.view.View。
            XposedHelpers.findAndHookDeclaredMethod(handleClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            android.view.View handle = (android.view.View) param.thisObject;
                            if (!readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)) {
                                removeGestureBlockSurfaces(handle);
                                return;
                            }
                            rememberGestureBarHeight(handle);
                            syncGestureBlockSurface(handle);
                        }
                    });
        } catch (Throwable t) {
            log("gesture touch-through attach hook failed: " + t);
        }
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(handleClass, "onLayout", boolean.class,
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            syncGestureBlockSurface((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("gesture touch-through layout hook failed: " + t);
        }
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(handleClass, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            removeGestureBlockSurfaces((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("gesture touch-through detach hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplusos.systemui.navigationbar.OplusNavigationBarView",
                    lpparam.classLoader, "updateSlippery", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.view.View navBarView = (android.view.View) param.thisObject;
                                android.view.View handle = findHandleInTree(navBarView);
                                if (handle == null) return;
                                if (!readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)) {
                                    removeGestureBlockSurfaces(navBarView);
                                    return;
                                }
                                syncGestureBlockSurface(handle);
                                // 触发一次 traversal 让 touchableRegion 按新状态重算。
                                navBarView.requestLayout();
                            } catch (Throwable t) {
                                dbg("gesture block shade sync error: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("gesture block shade sync hook failed: " + t);
        }
    }

    private static final java.util.Set<Class<?>> GESTURE_INSETS_LISTENERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void hookGestureInsetsListener(Class<?> listener, Class<?> info, XC_MethodHook hook) {
        if (!GESTURE_INSETS_LISTENERS.add(listener)) return;
        try {
            XposedHelpers.findAndHookMethod(listener, "onComputeInternalInsets", info, hook);
        } catch (Throwable error) {
            GESTURE_INSETS_LISTENERS.remove(listener);
            log("gesture touch insets listener hook failed: " + error);
        }
    }

    private static Class<?> findNavigationBarClass(ClassLoader classLoader) {
        Class<?> current = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.views.NavigationBar", classLoader);
        return current != null ? current : XposedHelpers.findClass(
                "com.android.systemui.navigationbar.NavigationBar", classLoader);
    }

    private static boolean callImeVisibility(Object helper, int imeVisibility, boolean legacy) {
        String first = legacy ? "isImeShown" : "isImeVisible";
        String second = legacy ? "isImeVisible" : "isImeShown";
        try {
            Object visible = XposedHelpers.callMethod(helper, first, imeVisibility);
            if (visible instanceof Boolean) return (Boolean) visible;
        } catch (Throwable ignored) { }
        try {
            Object visible = XposedHelpers.callMethod(helper, second, imeVisibility);
            if (visible instanceof Boolean) return (Boolean) visible;
        } catch (Throwable ignored) { }
        return false;
    }

    static float getMBackBandPaddingDp() {
        int dp = readInt(KEY_GESTURE_BAR_HEIGHT_ENABLED, 0) == 1
                ? readInt(KEY_GESTURE_BAR_HEIGHT_DP, 12)
                : 0;
        return Math.min(dp / 2f + 4f, 8f);
    }

    static volatile int sGestureBarHeightPx = -1;
    static volatile int sGestureBarHandleBottomPx = -1;

    static void rememberGestureBarHeight(android.view.View handle) {
        try {
            int h = XposedHelpers.getIntField(handle, "mHeight");
            if (h > 0) sGestureBarHeightPx = h;
            int bottom = XposedHelpers.getIntField(handle, "mHandleBottom");
            if (bottom > 0) sGestureBarHandleBottomPx = bottom;
        } catch (Throwable ignored) {
        }
    }

    static int getGestureBarHeightPx(android.view.View root) {
        if (sGestureBarHeightPx > 0) return sGestureBarHeightPx;
        android.view.View handle = findHandleInTree(root);
        if (handle != null) rememberGestureBarHeight(handle);
        return sGestureBarHeightPx > 0 ? sGestureBarHeightPx : Math.round(3f * readDensity());
    }

    static int getGestureBarHandleBottomPx(android.view.View root) {
        if (sGestureBarHandleBottomPx > 0) return sGestureBarHandleBottomPx;
        android.view.View handle = findHandleInTree(root);
        if (handle != null) rememberGestureBarHeight(handle);
        // 反编译资源 navigation_handle_bottom = 7dp。
        return sGestureBarHandleBottomPx > 0
                ? sGestureBarHandleBottomPx : Math.round(7f * readDensity());
    }

    static boolean isShadeExpanded(android.view.View view) {
        android.view.View v = view;
        while (v != null) {
            // 只在导航栏根布局这一层做反射, 避免每层都触发字段查找异常。
            if (v.getClass().getName().endsWith("NavigationBarView")) {
                Object interactor = null;
                try {
                    interactor = XposedHelpers.getObjectField(v, "mPanelExpansionInteractor");
                } catch (Throwable ignored) {
                    // 字段尚未注入(多实例场景), 按未展开处理。
                }
                if (interactor == null) return false;
                try {
                    return Boolean.TRUE.equals(
                            XposedHelpers.callMethod(interactor, "isFullyExpanded"))
                            || Boolean.TRUE.equals(
                                    XposedHelpers.callMethod(interactor, "isPanelExpanded"));
                } catch (Throwable t) {
                    dbg("shade expanded check error: " + t);
                    return false;
                }
            }
            android.view.ViewParent parent = v.getParent();
            v = (parent instanceof android.view.View) ? (android.view.View) parent : null;
        }
        return false;
    }

    static android.view.View findHandleInTree(android.view.View v) {
        if (v == null) return null;
        // NavigationBar keeps horizontal and vertical trees inflated together.
        // A hidden tree must not supply the handle or its hit-test coordinates.
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v.getClass().getName().contains("OplusNavigationHandle")) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = findHandleInTree(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    static int computeGestureBandTop(android.view.View host, int barHeight) {
        float density = readDensity();
        int padding = Math.round(getMBackBandPaddingDp() * density);
        int handleBottom = getGestureBarHandleBottomPx(host);
        return Math.max(0, host.getHeight() - handleBottom - barHeight - padding
                - getGestureBarCanvasLiftPx());
    }

    static GestureBlockSurface ensureGestureBlockSurface(android.view.View handle) {
        if (!readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)) {
            removeGestureBlockSurfaces(handle);
            return null;
        }
        Object existing = XposedHelpers.getAdditionalInstanceField(handle, "gesture_block_surface");
        if (existing instanceof GestureBlockSurface) {
            ((GestureBlockSurface) existing).update();
            return (GestureBlockSurface) existing;
        }
        android.widget.FrameLayout host = findMBackHost(handle);
        if (host == null) return null;
        GestureBlockSurface surface = new GestureBlockSurface(handle, host);
        surface.setClickable(true);
        surface.setFocusable(true);
        surface.setFocusableInTouchMode(true);
        surface.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View view, android.view.MotionEvent event) {
                if (!isGestureBlockActive(view)) return false;
                // Insets and window dispatch differ across phone ROM versions.
                // A touch delivered to the blocker must still reach mBack even
                // when the NavigationBarFrame hook did not claim that stream.
                routeMBackWindowTouch(view.getRootView(), event);
                return true;
            }
        });
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(1, 1);
        host.addView(surface, 0, lp);
        XposedHelpers.setAdditionalInstanceField(handle, "gesture_block_surface", surface);
        surface.update();
        return surface;
    }

    static void updateGestureBlockSurface(android.view.View handle) {
        if (!isGestureBlockActive(handle)) {
            removeGestureBlockSurfaces(handle);
            return;
        }
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "gesture_block_surface");
        if (surface instanceof GestureBlockSurface) {
            ((GestureBlockSurface) surface).update();
        }
    }

    static boolean isGestureBlockActive(android.view.View view) {
        return readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)
                && !isShadeExpanded(view) && !isImeVisible(view);
    }

    static boolean shouldBlockGestureReplay(View handle, MotionEvent event) {
        View root = findNavigationFrame(handle);
        if (root == null || !isPhoneGestureBlockActive(root)) return false;
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        float y = event.getRawY() - location[1];
        return x >= 0 && x < root.getWidth()
                && y >= computeGestureBandTop(root, getGestureBarHeightPx(root))
                && y < root.getHeight();
    }

    static boolean isImeVisible(android.view.View view) {
        if (sImeVisible) return true;
        if (view == null) return false;
        try {
            android.view.WindowInsets insets = view.getRootWindowInsets();
            if (insets != null && insets.isVisible(android.view.WindowInsets.Type.ime())) return true;
        } catch (Throwable ignored) { }
        try {
            android.graphics.Rect frame = new android.graphics.Rect();
            view.getWindowVisibleDisplayFrame(frame);
            if (view.getRootView().getHeight() - frame.bottom > Math.round(120 * readDensity())) return true;
        } catch (Throwable ignored) {
        }
        // An active editor or an IME-switcher flag does not imply a visible keyboard.
        // Independent navigation windows use the native setImeWindowStatus cache above.
        return false;
    }

    static void syncGestureBlockSurface(android.view.View handle) {
        syncGestureTouchWindow(handle);
        if (isGestureBlockActive(handle)) {
            ensureGestureBlockSurface(handle);
        } else if (XposedHelpers.getAdditionalInstanceField(
                handle, "gesture_block_surface") != null) {
            removeGestureBlockSurfaces(handle);
        }
    }

    static void removeGestureBlockSurface(android.view.View handle) {
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "gesture_block_surface");
        if (surface instanceof GestureBlockSurface) {
            android.view.ViewParent parent = ((GestureBlockSurface) surface).getParent();
            if (parent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) parent).removeView((GestureBlockSurface) surface);
            }
        }
        XposedHelpers.removeAdditionalInstanceField(handle, "gesture_block_surface");
    }

    static void removeGestureBlockSurfaces(android.view.View view) {
        syncGestureTouchWindow(view);
        android.view.View current = view;
        while (current != null) {
            removeGestureBlockSurface(current);
            if (XposedHelpers.removeAdditionalInstanceField(
                    current, "gesture_block_insets_applied") != null
                    && current.isAttachedToWindow()) {
                current.requestApplyInsets();
                current.requestLayout();
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof android.view.View ? (android.view.View) parent : null;
        }
        android.view.View handle = findHandleInTree(view.getRootView());
        if (handle != null) removeGestureBlockSurface(handle);
    }

    static void handleMBackTouch(final android.view.View handle,
            android.view.MotionEvent event) {
        if (event == null) return;
        int action = event.getActionMasked();
        long downTime = event.getDownTime();
        MBackGesture g = MBACK_GESTURES.get(downTime);
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            g = new MBackGesture();
            g.down = true;
            g.downTime = downTime;
            g.handle = handle;
            g.downX = event.getX();
            g.downY = event.getY();
            MBACK_GESTURES.put(downTime, g);
            GestureTouchReplayHooks.begin(handle, downTime);
            // 视觉反馈层非必要, 异常不得阻断触发逻辑。
            try {
                MBackSurface surface = ensureMBackSurface(handle);
                if (surface != null) {
                    surface.update();
                    surface.showAnimated();
                }
            } catch (Throwable ignored) { }
            int timeout = android.view.ViewConfiguration.getLongPressTimeout();
            final MBackGesture activeGesture = g;
            g.longPressRunnable = () -> {
                if (MBACK_GESTURES.get(downTime) != activeGesture || !activeGesture.down || activeGesture.cancelled
                        || activeGesture.longPress || !GestureTouchReplayHooks.isMBackLongPressDue(
                                handle, downTime, android.os.SystemClock.uptimeMillis(), timeout)) return;
                fireMBackLongPress(activeGesture);
            };
            GESTURE_TOUCH_MAIN.postAtTime(g.longPressRunnable, downTime + timeout);
            return;
        }
        if (g == null || !g.down) return;
        if (action == android.view.MotionEvent.ACTION_MOVE) {
            // 从白条向左/右/上划动时放弃接管（不震动、不触发导航），交由系统手势处理。
            float dx = event.getX() - g.downX;
            float dy = event.getY() - g.downY;
            float swipe = Math.round(MBACK_SWIPE_DP * readDensity());
            if (Math.abs(dx) > swipe || dy < -swipe) {
                g.cancelled = true;
                cancelMBackLongPress(g);
                GestureTouchReplayHooks.release(handle, downTime);
                try { hideMBackSurface(handle); } catch (Throwable ignored) { }
            }
            if (!g.cancelled && !g.longPress && event.getEventTime() - downTime
                    >= android.view.ViewConfiguration.getLongPressTimeout()) {
                fireMBackLongPress(g);
            }
            return;
        }
        if (action == android.view.MotionEvent.ACTION_UP) {
            boolean cancelled = g.cancelled;
            boolean longPressed = g.longPress;
            cancelMBackLongPress(g);
            // Releasing a held gesture must not launch Home. Only the threshold
            // callback (or a MOVE crossing it) performs the long-press action.
            boolean tap = event.getEventTime() - downTime
                    < android.view.ViewConfiguration.getLongPressTimeout();
            if (!cancelled) GestureTouchReplayHooks.consume(handle, downTime);
            if (!cancelled && !longPressed && tap) {
                try {
                    handle.performHapticFeedback(
                            android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                } catch (Throwable ignored) { }
                MBackRecentsHooks.navigate(handle, downTime, false);
            } else if (!cancelled && longPressed) {
                // 松手时再震动一次。阈值处的震动不能抑制这次释放反馈。
                try {
                    handle.performHapticFeedback(
                            android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                } catch (Throwable ignored) { }
            }
            MBACK_GESTURES.remove(downTime);
            MBACK_IN_RANGE.remove(downTime);
            g.down = false;
            try { hideMBackSurface(handle); } catch (Throwable ignored) { }
            return;
        }
        if (action == android.view.MotionEvent.ACTION_CANCEL
                || action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            cancelMBackLongPress(g);
            GestureTouchReplayHooks.release(handle, downTime);
            g.cancelled = true;
            g.down = false;
            MBACK_GESTURES.remove(downTime);
            MBACK_IN_RANGE.remove(downTime);
            try { hideMBackSurface(handle); } catch (Throwable ignored) { }
        }
    }

    static boolean handleNativeMBackTouch(View handle, MotionEvent event) {
        if (event == null) return false;
        // The protected navigation window already owns this stream. Its direct
        // dispatch must not depend on, or be duplicated by, the animation callback.
        if (GestureTouchReplayHooks.ownsWindowMBack(handle, event.getDownTime())
                || GestureTouchReplayHooks.ownsMonitorMBack(handle, event.getDownTime())) return true;
        if (!readBool(KEY_MBACK_ENABLED, false)) return false;
        long downTime = event.getDownTime();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            MBACK_IN_RANGE.put(downTime, isInMBackBarRange(handle, event));
        }
        if (!Boolean.TRUE.equals(MBACK_IN_RANGE.get(downTime))) return false;
        handleMBackTouch(handle, event);
        return true;
    }

    static void routeMBackWindowTouch(View root, MotionEvent event) {
        View handle = findHandleInTree(root);
        if (handle == null) return;
        routeMBackInput(handle, event, false);
    }

    static void routeMBackMonitorTouch(View handle, MotionEvent event) {
        routeMBackInput(handle, event, true);
    }

    static void routeMBackInput(View handle, MotionEvent event, boolean monitor) {
        long downTime = event.getDownTime();
        // A navigation-window CANCEL can mean another system gesture monitor
        // pilfered pointers. Only the raw monitor may cancel its own active stream.
        if (!monitor && GestureTouchReplayHooks.ownsMonitorMBack(handle, downTime)) return;
        MotionEvent copy = MotionEvent.obtain(event);
        try {
            // View dispatch uses window coordinates; the existing mBack recognizer
            // and viewScreenLeft both use display coordinates, like the native monitor.
            copy.setLocation(event.getRawX(), event.getRawY());
            if (copy.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // ColorOS can hide the navigation window while its independent
                // input monitor keeps receiving bottom gestures (for example,
                // when Launcher owns the gesture-bar surface). IME visibility
                // makes the window visible again; it must not gate raw input.
                boolean unavailable = monitor
                        ? !handle.isAttachedToWindow() || handle.getWidth() <= 0
                        : !handle.isShown();
                if (!readBool(KEY_MBACK_ENABLED, false) || unavailable
                        || isShadeExpanded(handle) || !isInMBackBarRange(handle, copy)) return;
                if (monitor) GestureTouchReplayHooks.claimMonitorMBack(handle, downTime);
                else GestureTouchReplayHooks.claimWindowMBack(handle, downTime);
                // The monitor can deliver DOWN first. Adopt that pending gesture
                // rather than restart its timer or repeat completed navigation.
                if (MBACK_GESTURES.containsKey(downTime)
                        || GestureTouchReplayHooks.wasNavigationConsumed(handle, downTime)) return;
                MBACK_IN_RANGE.put(downTime, true);
            } else if (monitor ? !GestureTouchReplayHooks.ownsMonitorMBack(handle, downTime)
                    : !GestureTouchReplayHooks.ownsWindowMBack(handle, downTime)) {
                return;
            }
            if (!readBool(KEY_MBACK_ENABLED, false)) copy.setAction(MotionEvent.ACTION_CANCEL);
            handleMBackTouch(handle, copy);
        } finally {
            copy.recycle();
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                GestureTouchReplayHooks.clearMBackPhysicalTerminal(handle, downTime);
            }
        }
    }

    private static void fireMBackLongPress(MBackGesture gesture) {
        if (gesture == null || !gesture.down || gesture.cancelled || gesture.longPress) return;
        gesture.longPress = true;
        cancelMBackLongPress(gesture);
        GestureTouchReplayHooks.consume(gesture.handle, gesture.downTime);
        // Issue the Home request at the threshold, before feedback.
        // Releasing the finger does not issue navigation.
        MBackRecentsHooks.navigate(gesture.handle, gesture.downTime, true);
        try {
            gesture.handle.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS);
        } catch (Throwable ignored) { }
    }

    private static void cancelMBackLongPress(MBackGesture gesture) {
        if (gesture != null && gesture.longPressRunnable != null && gesture.handle != null) {
            GESTURE_TOUCH_MAIN.removeCallbacks(gesture.longPressRunnable);
            gesture.longPressRunnable = null;
        }
    }

    static boolean isInMBackBarRange(android.view.View handle,
            android.view.MotionEvent ev) {
        try {
            int padding = Math.round(getMBackBandPaddingDp() * readDensity());
            float x = ev.getRawX();
            int[] location = new int[2];
            handle.getLocationOnScreen(location);
            int left = location[0];
            int right = left + handle.getWidth();
            return x >= left - padding && x <= right + padding;
        } catch (Throwable t) {
            return true;
        }
    }

    static MBackSurface ensureMBackSurface(android.view.View handle) {
        Object existing = XposedHelpers.getAdditionalInstanceField(handle, "mback_surface");
        if (existing instanceof MBackSurface) {
            ((MBackSurface) existing).update();
            return (MBackSurface) existing;
        }
        android.widget.FrameLayout host = findMBackHost(handle);
        if (host == null) return null;
        MBackSurface surface = new MBackSurface(handle, host);
        surface.setVisibility(android.view.View.INVISIBLE);

        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(1, 1);
        host.addView(surface, 0, lp);
        XposedHelpers.setAdditionalInstanceField(handle, "mback_surface", surface);
        surface.update();
        return surface;
    }

    static android.widget.FrameLayout findMBackHost(android.view.View view) {
        android.view.ViewParent parent = view.getParent();
        android.widget.FrameLayout fallback = null;
        while (parent instanceof android.view.View) {
            if (parent instanceof android.widget.FrameLayout) {
                if (fallback == null) fallback = (android.widget.FrameLayout) parent;
                if (parent.getClass().getName().contains("NavigationBarFrame")) {
                    return (android.widget.FrameLayout) parent;
                }
            }
            parent = parent.getParent();
        }
        return fallback;
    }

    static void updateMBackSurface(android.view.View handle) {
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "mback_surface");
        if (surface instanceof MBackSurface) ((MBackSurface) surface).update();
    }

    static void syncMBackSurface(android.view.View handle) {
        if (readBool(KEY_MBACK_ENABLED, false)) {
            ensureMBackSurface(handle);
        } else if (XposedHelpers.getAdditionalInstanceField(handle, "mback_surface") != null) {
            removeMBackSurface(handle);
        }
    }

    static void hideMBackSurface(android.view.View handle) {
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "mback_surface");
        if (surface instanceof MBackSurface) ((MBackSurface) surface).hideAnimated();
    }

    static void removeMBackSurface(android.view.View handle) {
        // 取消该 handle 上所有未触发的长按, 并清理对应的手势状态。
        for (java.util.Iterator<MBackGesture> it = MBACK_GESTURES.values().iterator(); it.hasNext(); ) {
            MBackGesture gg = it.next();
            if (gg.handle == handle) {
                cancelMBackLongPress(gg);
                // Removing a host cancels its unresolved replay ownership as well.
                GestureTouchReplayHooks.release(handle, gg.downTime);
                it.remove();
            }
        }
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "mback_surface");
        if (surface instanceof MBackSurface) {
            MBackSurface mback = (MBackSurface) surface;
            mback.removeCallbacks(mback.hideRunnable);
            if (mback.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) mback.getParent()).removeView(mback);
            }
        }
        XposedHelpers.removeAdditionalInstanceField(handle, "mback_surface");
    }

    public static void cleanupForHotReload() {
        sGestureTouchHooksActive = false;
        GESTURE_TOUCH_MAIN.removeCallbacks(REFRESH_GESTURE_TOUCH);
        for (View root : new java.util.ArrayList<>(GESTURE_TOUCH_WINDOWS.keySet())) {
            syncGestureTouchWindow(root);
        }
        for (GestureTouchWindowState state : GESTURE_TOUCH_WINDOWS.values()) state.restore();
        GESTURE_TOUCH_WINDOWS.clear();
        // Snapshot first: removeMBackSurface also removes entries from MBACK_GESTURES.
        java.util.List<MBackGesture> gestures =
                new java.util.ArrayList<MBackGesture>(MBACK_GESTURES.values());
        final java.util.Set<android.view.View> handles =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<android.view.View, Boolean>());
        XposedHelpers.TrackedOwnerConsumer collectView =
                new XposedHelpers.TrackedOwnerConsumer() {
                    @Override public void accept(Object owner) {
                        if (owner instanceof android.view.View) {
                            handles.add((android.view.View) owner);
                        }
                    }
                };
        XposedHelpers.forEachTrackedOwner("mback_surface", collectView);
        XposedHelpers.forEachTrackedOwner("gesture_block_surface", collectView);
        XposedHelpers.forEachTrackedOwner("gesture_block_insets_applied", collectView);
        for (MBackGesture gesture : gestures) {
            cancelMBackLongPress(gesture);
            if (gesture.handle != null) handles.add(gesture.handle);
        }
        GestureTouchReplayHooks.cleanup();
        MBackRecentsHooks.cleanup();
        MBACK_GESTURES.clear();
        MBACK_IN_RANGE.clear();
        for (android.view.View handle : handles) {
            try { removeMBackSurface(handle); }
            catch (Throwable t) { log("mback surface hot reload cleanup failed: " + t); }
            try { removeGestureBlockSurfaces(handle); }
            catch (Throwable t) { log("gesture block hot reload cleanup failed: " + t); }
        }
        XposedHelpers.removeTrackedViewChildren("mback_surface", "gesture_block_surface");
        sBarExtraApplied.clear();
        sImeVisible = false;
        sGestureBarHeightPx = -1;
        sGestureBarHandleBottomPx = -1;
    }

    static void triggerNavigation(android.view.View handle, boolean home) {
        if (TaskbarGestureHooks.dispatchNavigation(handle, home)) return;
        android.view.View navView = null;
        try {
            android.view.View parent = handle;
            Class<?> navViewClass = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBarView",
                    handle.getContext().getClassLoader());
            while (parent != null && !navViewClass.isInstance(parent)) {
                parent = parent.getParent() instanceof android.view.View
                        ? (android.view.View) parent.getParent() : null;
            }
            navView = parent;
        } catch (Throwable t) {

        }

        if (home) {
            // After resolving the physical gesture's recents transition, let
            // ColorOS route HOME to Launcher for its native closing animation.
            if (injectKey(handle, android.view.KeyEvent.KEYCODE_HOME)) return;
            if (dispatchNavigationButton(navView, true)) return;
            return;
        }

        // ColorOS 17's PhoneWindowManager routes injected BACK keys through
        // QuickBackInputPolicy, which can consume them before the focused app.
        // Mark this synthetic mBack key so system_server can bypass only that
        // QuickBack interception while retaining the normal key policy path.
        if (android.os.Build.VERSION.SDK_INT >= 37
                && injectKey(handle, android.view.KeyEvent.KEYCODE_BACK, true)) return;

        // Use ColorOS' own Back button event path first. KeyButtonView supplies
        // its native device id, flags, display id, and gesture-thread dispatch;
        // injectInputEvent returning true only means queued, not that Back ran.
        if (dispatchNavigationButton(navView, false)) return;
        if (injectKey(handle, android.view.KeyEvent.KEYCODE_BACK)) return;
    }

    private static boolean dispatchNavigationButton(android.view.View navView, boolean home) {
        if (navView == null) return false;
        try {
            Object dispatcher = XposedHelpers.callMethod(navView,
                    home ? "getHomeButton" : "getBackButton");
            Object buttonView = XposedHelpers.callMethod(dispatcher, "getCurrentView");
            if (buttonView == null) return false;
            // Home 和 Back 都由 KeyButtonView.sendEvent 发送按键；performClick
            // 只调用点击监听器，不能代替 Home 按键（手势模式下尤其如此）。
            int expectedCode = home ? android.view.KeyEvent.KEYCODE_HOME
                    : android.view.KeyEvent.KEYCODE_BACK;
            if (XposedHelpers.getIntField(buttonView, "mCode") != expectedCode) return false;
            long now = android.os.SystemClock.uptimeMillis();
            XposedHelpers.setLongField(buttonView, "mDownTime", now);
            int flags = 0;
            XposedHelpers.callMethod(buttonView, "sendEvent", 0, flags, now);
            XposedHelpers.callMethod(buttonView, "sendEvent", 1, flags);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean injectKey(int code) {
        return injectKey(null, code);
    }

    static boolean injectKey(android.view.View handle, int code) {
        return injectKey(handle, code, false);
    }

    private static boolean injectKey(android.view.View handle, int code, boolean mBackMarker) {
        boolean downInjected = false;
        try {
            // ColorOS 17 的 KeyButtonView 使用 InputManagerGlobal；旧系统保留
            // InputManager 兼容分支，不能只依赖旧版隐藏的 getInstance() 接口。
            Class<?> inputManagerClass;
            try {
                inputManagerClass = Class.forName("android.hardware.input.InputManagerGlobal");
            } catch (ClassNotFoundException olderPlatform) {
                inputManagerClass = Class.forName("android.hardware.input.InputManager");
            }
            Object inputManager = XposedHelpers.callStaticMethod(inputManagerClass, "getInstance");
            long now = android.os.SystemClock.uptimeMillis();
            int flags = android.view.KeyEvent.FLAG_FROM_SYSTEM
                    | android.view.KeyEvent.FLAG_VIRTUAL_HARD_KEY;
            if (mBackMarker) flags |= android.view.KeyEvent.FLAG_FALLBACK;
            android.view.KeyEvent down = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_DOWN,
                    code, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            android.view.KeyEvent up = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_UP,
                    code, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            if (handle != null && handle.getDisplay() != null) {
                int displayId = handle.getDisplay().getDisplayId();
                XposedHelpers.callMethod(down, "setDisplayId", displayId);
                XposedHelpers.callMethod(up, "setDisplayId", displayId);
            }
            downInjected = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(inputManager, "injectInputEvent", down, 0));
            if (!downInjected) return false;
            boolean upAccepted = false;
            for (int attempt = 0; attempt < 2 && !upAccepted; attempt++) {
                try {
                    upAccepted = Boolean.TRUE.equals(
                            XposedHelpers.callMethod(inputManager, "injectInputEvent", up, 0));
                } catch (Throwable ignored) { }
            }
            // DOWN 已被接受后不得再走备用路径，否则可能重复返回。
            return true;
        } catch (Throwable t) {
            return downInjected;
        }
    }

    static boolean injectHomeKey() {
        return injectKey(android.view.KeyEvent.KEYCODE_HOME);
    }

    static final class MBackSurface extends android.view.View {
        private final android.view.View source;
        private final android.view.ViewGroup host;

        MBackSurface(android.view.View source, android.view.ViewGroup host) {
            super(source.getContext());
            this.source = source;
            this.host = host;
            android.graphics.drawable.GradientDrawable mask =
                    new android.graphics.drawable.GradientDrawable();
            mask.setColor(android.graphics.Color.WHITE);
            mask.setCornerRadius(1000.0f);
            android.content.res.ColorStateList rippleColor =
                    android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.argb(64, 255, 255, 255));
            setBackground(new android.graphics.drawable.RippleDrawable(
                    rippleColor, null, mask));
            setAlpha(1.0f);
            setWillNotDraw(true);
        }

        void showAnimated() {
            removeCallbacks(hideRunnable);
            setAlpha(1.0f);
            setVisibility(android.view.View.VISIBLE);
            setPressed(true);
            if (getBackground() != null) {
                getBackground().setHotspot(getWidth() / 2.0f, getHeight() / 2.0f);
            }
        }

        void hideAnimated() {
            setPressed(false);
            removeCallbacks(hideRunnable);
            postDelayed(hideRunnable, MBACK_RIPPLE_HIDE_DELAY_MS);
        }

        private final Runnable hideRunnable = new Runnable() {
            @Override
            public void run() {
                setVisibility(android.view.View.INVISIBLE);
            }
        };

        void update() {
            if (getParent() != host) return;
            android.graphics.RectF taskbarBounds = TaskbarGestureHooks.getHandleBounds(source, host);
            if (taskbarBounds != null) {
                int padding = Math.round(getMBackBandPaddingDp()
                        * source.getResources().getDisplayMetrics().density);
                int width = Math.round(taskbarBounds.width()) + padding * 2;
                int height = Math.round(taskbarBounds.height()) + padding * 2;
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) getLayoutParams();
                if (lp.width != width || lp.height != height) {
                    lp.width = width;
                    lp.height = height;
                    setLayoutParams(lp);
                }
                setX(taskbarBounds.centerX() - width / 2f);
                setY(taskbarBounds.centerY() - height / 2f);
                return;
            }
            int barHeight = getSourceInt("mHeight", source.getHeight());
            int handleBottom = getSourceInt("mHandleBottom", 0);
            float density = readDensity();
            int padding = Math.round(getMBackBandPaddingDp() * density);
            int width = source.getWidth() + padding * 2;
            int height = barHeight + padding * 2;
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) getLayoutParams();
            if (lp.width != width || lp.height != height) {
                lp.width = width;
                lp.height = height;
                setLayoutParams(lp);
            }
            int[] sourceLocation = new int[2];
            int[] hostLocation = new int[2];
            source.getLocationInWindow(sourceLocation);
            host.getLocationInWindow(hostLocation);
            int barCenterXInHost = (sourceLocation[0] - hostLocation[0]) + source.getWidth() / 2;

            int barCenterYInHost = (sourceLocation[1] - hostLocation[1])
                    + source.getHeight() - handleBottom - barHeight / 2
                    - getGestureBarCanvasLiftPx();
            setX(barCenterXInHost - width / 2);
            setY(barCenterYInHost - height / 2);
            invalidate();
        }

        private int getSourceInt(String field, int fallback) {
            try {
                return XposedHelpers.getIntField(source, field);
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    static final class GestureBlockSurface extends android.view.View {
        private final android.view.View source;
        private final android.view.ViewGroup host;

        GestureBlockSurface(android.view.View source, android.view.ViewGroup host) {
            super(source.getContext());
            this.source = source;
            this.host = host;
            setWillNotDraw(true);
        }

        void update() {
            if (getParent() != host) return;
            int topY = computeGestureBandTop(host, getSourceInt("mHeight", source.getHeight()));
            int width = host.getWidth();
            int height = host.getHeight() - topY;
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) getLayoutParams();
            if (lp.width != width || lp.height != height) {
                lp.width = width;
                lp.height = height;
                setLayoutParams(lp);
            }
            setX(0);
            setY(topY);
            invalidate();
        }

        private int getSourceInt(String field, int fallback) {
            try {
                return XposedHelpers.getIntField(source, field);
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    public static void hookGestureBarHeight(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> navBarClass = findNavigationBarClass(lpparam.classLoader);
            XposedHelpers.findAndHookMethod(navBarClass, "getBarLayoutParams", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (readInt(KEY_GESTURE_BAR_HEIGHT_ENABLED, 0) != 1) return;
                                int dp = Math.max(0, Math.min(24,
                                        readInt(KEY_GESTURE_BAR_HEIGHT_DP, 12)));
                                int extraPx = Math.round(dp * readDensity());
                                android.view.WindowManager.LayoutParams lp =
                                        (android.view.WindowManager.LayoutParams) param.getResult();
                                if (lp == null || extraPx <= 0) return;
                                applyBarExtra(lp, extraPx);
                            } catch (Throwable t) {
                                log("gesture_bar layout hook err: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("gesture_bar hook (layout) failed: " + t);
        }
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            // view 生命周期 / 绘制回调限定本类声明, 避免上溯到 android.view.View。
            XposedHelpers.findAndHookDeclaredMethod(handleClass, "onDraw", android.graphics.Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int half = getGestureBarCanvasLiftPx();
                                if (half <= 0) return;
                                android.graphics.Canvas c = (android.graphics.Canvas) param.args[0];
                                c.save();
                                c.translate(0, -half);
                                param.setObjectExtra("gb_handle", Boolean.TRUE);
                            } catch (Throwable t) {
                                log("gesture_bar handle before err: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (Boolean.TRUE.equals(param.getObjectExtra("gb_handle"))) {
                                    ((android.graphics.Canvas) param.args[0]).restore();
                                }
                            } catch (Throwable t) {
                                log("gesture_bar handle after err: " + t);
                            }
                        }
                    });
            XposedHelpers.findAndHookDeclaredMethod(handleClass, "setVertical", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyGestureBarWidth((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("gesture_bar hook (handle) failed: " + t);
        }
        // 单独 try: 旧版 ROM 无此覆写时不连累上面的 onDraw / setVertical。
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(handleClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyGestureBarWidth((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("gesture_bar width attach hook failed: " + t);
        }
    }

    static int getGestureBarCanvasLiftPx() {
        if (readInt(KEY_GESTURE_BAR_HEIGHT_ENABLED, 0) != 1) return 0;
        int dp = 10;
        if (dp <= 0) return 0;
        return Math.round(dp * readDensity() * 0.5f);
    }

    static void applyGestureBarWidth(android.view.View view) {
        try {
            if (readInt(KEY_GESTURE_BAR_WIDTH_ENABLED, 0) != 1) return;
            int dp = Math.max(80, Math.min(120,
                    readInt(KEY_GESTURE_BAR_WIDTH_DP, 100)));
            int width = Math.round(dp * readDensity());
            android.view.ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (!(raw instanceof android.widget.LinearLayout.LayoutParams)) return;
            android.widget.LinearLayout.LayoutParams lp =
                    (android.widget.LinearLayout.LayoutParams) raw;
            if (lp.width == width && lp.gravity == android.view.Gravity.CENTER) return;
            lp.width = width;
            lp.gravity = android.view.Gravity.CENTER;
            view.setLayoutParams(lp);
        } catch (Throwable t) {
            log("gesture_bar width hook err: " + t);
        }
    }

    static void applyBarExtra(android.view.WindowManager.LayoutParams lp, int extraPx) {
        lp.height += extraPx;
        bumpInsetsIfPresent(lp, extraPx);
        try {
            java.lang.reflect.Field f = lp.getClass().getField("paramsForRotation");
            Object arr = f.get(lp);
            if (arr instanceof Object[]) {
                for (Object o : (Object[]) arr) {
                    if (o instanceof android.view.WindowManager.LayoutParams) {
                        android.view.WindowManager.LayoutParams p = (android.view.WindowManager.LayoutParams) o;
                        p.height += extraPx;
                        bumpInsetsIfPresent(p, extraPx);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static void bumpInsetsIfPresent(android.view.WindowManager.LayoutParams lp, int extraPx) {
        try {
            Object raw = XposedHelpers.getObjectField(lp, "providedInsets");
            if (!(raw instanceof Object[])) return;
            for (Object provider : (Object[]) raw) {
                if (provider == null) continue;
                try {
                    Object current;
                    try {
                        current = XposedHelpers.callMethod(provider, "getInsetsSize");
                    } catch (Throwable ignored) {
                        current = XposedHelpers.getObjectField(provider, "mInsetsSize");
                    }
                    if (current == null) continue;
                    int left = XposedHelpers.getIntField(current, "left");
                    int top = XposedHelpers.getIntField(current, "top");
                    int right = XposedHelpers.getIntField(current, "right");
                    int bottom = XposedHelpers.getIntField(current, "bottom");
                    if (bottom <= 0) continue;
                    Object updated = android.graphics.Insets.of(left, top, right, bottom + extraPx);
                    try {
                        XposedHelpers.callMethod(provider, "setInsetsSize", updated);
                    } catch (Throwable ignored) {
                    }
                    XposedHelpers.setObjectField(provider, "mInsetsSize", updated);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
