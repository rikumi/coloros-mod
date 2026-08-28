package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.TextSwitcher;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 手势导航(SystemUI)相关 hook：手势条高度、mBack 触摸与反馈、手势带防穿透。
 */
public final class GestureHooks {
    // 判定为向左/右/上划动的阈值（dp），超过则放弃 MBack 接管。
    static final int MBACK_SWIPE_DP = 20;

    static final java.util.WeakHashMap<Object, Boolean> sBarExtraApplied =
            new java.util.WeakHashMap<>();

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

    public static void hookMBack(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(handleClass, "handleValidTouchEvent",
                    android.view.MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_MBACK_ENABLED, false)) return;
                            android.view.View handle = (android.view.View) param.thisObject;
                            android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                            // mBack 仅在白条水平范围内生效; 白条外(两侧)只响应系统手势,
                            // 不触发返回/震动, 也不接管事件(放行 handle 原逻辑)。
                            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                                XposedHelpers.setAdditionalInstanceField(handle, "mback_in_range",
                                        isInMBackBarRange(handle, ev));
                            }
                            if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                    handle, "mback_in_range"))) {
                                return;
                            }
                            handleMBackTouch(handle, ev);
                            param.setResult(null);
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            syncMBackSurface((android.view.View) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onLayout", boolean.class,
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            syncMBackSurface((android.view.View) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            removeMBackSurface((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("mback hook failed: " + t);
        }
    }

    // 避免手势区域点击穿透: 手势导航下 NavigationBarExImpl.updateInsetsTouchability 把导航栏
    // 窗口的 touchableRegion 限制为白条区域, 手势带其余部分的事件不派发给该窗口, 直接透传。
    // 1) 把 touchableRegion 设为「mBack 热区顶部 -> 窗口底部」这一段(导航栏窗口实测高 179px,
    //    设成整窗口会挡住底部整条); 2) 同段内放全宽透明拦截层消费触摸。
    // 手势识别在 input monitor 层(SideGestureDetector)不受影响。
    public static void hookGestureTouchThrough(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // InternalInsetsInfo 为 @hide 类, 编译期不可见, 用反射访问。
            Class<?> insetsInfoClass = XposedHelpers.findClass(
                    "android.view.ViewTreeObserver$InternalInsetsInfo", null);
            Class<?> insetsListenerClass = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBar$$ExternalSyntheticLambda10",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(insetsListenerClass, "onComputeInternalInsets",
                    insetsInfoClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object info = param.args[0];
                                Object navBar = XposedHelpers.getObjectField(param.thisObject, "f$0");
                                Object viewObj = XposedHelpers.getObjectField(navBar, "mView");
                                if (viewObj instanceof android.view.View) {
                                    android.view.View view = (android.view.View) viewObj;
                                    // 通知中心/控制中心展开时保留系统原始区域, 不做任何拦截。
                                    if (!isGestureBlockActive(view)) return;
                                    // 触摸区域决定窗口真正拦截的范围(导航栏窗口实测 179px 高,
                                    // 设成整窗口会挡住底部整条)。只取 mBack 热区顶部到窗口
                                    // 底部这一段; 其上方的事件照旧透传给下层应用。
                                    int topY = computeGestureBandTop(view, getGestureBarHeightPx(view));
                                    Object regionObj = XposedHelpers.getObjectField(info, "touchableRegion");
                                    if (regionObj instanceof android.graphics.Region) {
                                        ((android.graphics.Region) regionObj).set(
                                                0, topY, view.getWidth(), view.getHeight());
                                    }
                                    // TOUCHABLE_INSETS_REGION = 3
                                    XposedHelpers.callMethod(info, "setTouchableInsets", 3);
                                }
                            } catch (Throwable t) {
                                dbg("gesture touch-through region error: " + t);
                            }
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
            XposedHelpers.findAndHookMethod(handleClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            android.view.View handle = (android.view.View) param.thisObject;
                            rememberGestureBarHeight(handle);
                            syncGestureBlockSurface(handle);
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onLayout", boolean.class,
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            syncGestureBlockSurface((android.view.View) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            removeGestureBlockSurface((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("gesture touch-through hook failed: " + t);
        }
        // 面板展开/收起时导航栏窗口不一定重算 insets, 靠 onComputeInternalInsets 无法及时撤销
        // 拦截。OplusNavigationBarView.updateSlippery 在 CentralSurfacesImpl 的展开 fraction
        // 达到 0/1 以及 QS 展开变化时都会被调用, 是可靠的同步时机。
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

    // mBack 热区留白: 手势条高度设置值(KEY_GESTURE_BAR_HEIGHT_DP, 单位 dp)的一半加 4dp, 上限 10dp。
    // readInt(KEY_GESTURE_BAR_HEIGHT_DP, 0) 的默认值 0 即关闭功能时的系统初始值:
    // 开关关闭时把该值当作 0 处理, 继续按公式计算。
    static float getMBackBandPaddingDp() {
        int dp = readInt(KEY_GESTURE_BAR_HEIGHT_ENABLED, 0) == 1
                ? readInt(KEY_GESTURE_BAR_HEIGHT_DP, 0)
                : 0;
        return Math.min(dp / 2f + 4f, 10f);
    }

    // 白条厚度(mHeight)与白条底部间距(mHandleBottom)。触摸区域的 dispatch 独立于
    // 拦截层的创建时机, 因此区域计算不能依赖拦截层: 一旦拿不到就退化成
    // "完全不设置区域", 表现为整条穿透且 mBack 失效。
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

    // 通知中心/控制中心是否已展开。从 view 向上找到 NavigationBarView
    // (OplusNavigationBarView), 读其 mPanelExpansionInteractor 判断。
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

    // 取 mBack 热区顶部 -> 窗口底部这一段: 返回热区顶部在 host 坐标系中的 y。
    // 口径与 MBackSurface 一致(以白条绘制中心垂直居中, 上下各 padding 留白):
    // 白条中心 y = host 高 - mHandleBottom - mHeight/2 - 画布上移量,
    // 热区顶部 = 白条中心 - (mHeight + 2*padding)/2。
    // 不能用 getLocationInWindow: OplusNavigationHandle 铺满整个导航栏窗口,
    // 其 top 相对 host 恒为 0, 会退化成"整条导航栏"。
    static int computeGestureBandTop(android.view.View host, int barHeight) {
        float density = readDensity();
        int padding = Math.round(getMBackBandPaddingDp() * density);
        int handleBottom = getGestureBarHandleBottomPx(host);
        return Math.max(0, host.getHeight() - handleBottom - barHeight - padding
                - getGestureBarCanvasLiftPx());
    }

    static GestureBlockSurface ensureGestureBlockSurface(android.view.View handle) {
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
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "gesture_block_surface");
        if (surface instanceof GestureBlockSurface) {
            ((GestureBlockSurface) surface).update();
        }
    }

    // 防穿透拦截层严格跟随 gesture_touch_through_enabled 运行期取值, 与 mBack 无关:
    // 开则创建/更新, 关则立即从视图树移除(否则会残留到下次 SystemUI 重启)。
    // 通知中心/控制中心展开时一律不生效: 面板窗口本身已接管触摸,
    // 继续吞掉手势带事件只会让面板底部区域点不动。
    static boolean isGestureBlockActive(android.view.View view) {
        return readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false) && !isShadeExpanded(view);
    }

    static void syncGestureBlockSurface(android.view.View handle) {
        if (isGestureBlockActive(handle)) {
            ensureGestureBlockSurface(handle);
        } else if (XposedHelpers.getAdditionalInstanceField(
                handle, "gesture_block_surface") != null) {
            removeGestureBlockSurface(handle);
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
        XposedHelpers.setAdditionalInstanceField(handle, "gesture_block_surface", null);
    }

    static void handleMBackTouch(final android.view.View handle,
            android.view.MotionEvent event) {
        if (event == null) return;
        MBackSurface surface = ensureMBackSurface(handle);
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            cancelMBackLongPress(handle);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down", Boolean.TRUE);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_long", Boolean.FALSE);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_cancelled", Boolean.FALSE);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down_x", event.getX());
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down_y", event.getY());
            if (surface != null) {
                surface.update();
                surface.showAnimated();
            }
            final Runnable longPress = new Runnable() {
                @Override
                public void run() {
                    if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                            handle, "mback_down"))
                            || Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                            handle, "mback_cancelled"))) {
                        return;
                    }
                    XposedHelpers.setAdditionalInstanceField(handle, "mback_long", Boolean.TRUE);
                    android.view.View feedback = handle;
                    feedback.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    triggerNavigation(handle, true);
                }
            };
            XposedHelpers.setAdditionalInstanceField(handle, "mback_runnable", longPress);
            handle.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
            return;
        }
        if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(handle, "mback_down"))) {
            return;
        }
        if (action == android.view.MotionEvent.ACTION_MOVE) {
            // 从白条向左/右/上划动时放弃接管（不震动、不触发导航），交由系统手势处理。
            float dx = event.getX() - getMBackFloat(handle, "mback_down_x");
            float dy = event.getY() - getMBackFloat(handle, "mback_down_y");
            float swipe = Math.round(MBACK_SWIPE_DP * readDensity());
            if (Math.abs(dx) > swipe || dy < -swipe) {
                XposedHelpers.setAdditionalInstanceField(handle, "mback_cancelled", Boolean.TRUE);
                cancelMBackLongPress(handle);
                hideMBackSurface(handle);
            }
            return;
        }
        if (action == android.view.MotionEvent.ACTION_UP) {
            boolean cancelled = Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                    handle, "mback_cancelled"));
            boolean longPressed = Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                    handle, "mback_long"));
            cancelMBackLongPress(handle);
            if (!cancelled && !longPressed) {
                handle.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                triggerNavigation(handle, false);
            } else if (!cancelled && longPressed) {
                // 长按触发回桌面后松手: 追加与长按触发一致的线性马达反馈。
                handle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down", Boolean.FALSE);
            hideMBackSurface(handle);
            return;
        }
        if (action == android.view.MotionEvent.ACTION_CANCEL
                || action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            XposedHelpers.setAdditionalInstanceField(handle, "mback_cancelled", Boolean.TRUE);
            cancelMBackLongPress(handle);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down", Boolean.FALSE);
            hideMBackSurface(handle);
        }
    }

    // 判断 DOWN 事件是否落在白条水平范围(含 8dp 留白), 与 MBackSurface 宽度口径一致。
    // 依据反编译: 系统原生用 ev.getX()(input monitor 转发, 即屏幕坐标) 与实例字段
    // viewScreenLeft(onLayout 时赋值, 屏幕坐标) 比较判定 inGestureXRange。
    // 这里复刻该口径并放宽 padding。白条外(两侧区域)不触发 mBack, 放行 handle 原逻辑。
    static boolean isInMBackBarRange(android.view.View handle,
            android.view.MotionEvent ev) {
        try {
            int padding = Math.round(getMBackBandPaddingDp() * readDensity());
            float x = ev.getX();
            int left = XposedHelpers.getIntField(handle, "viewScreenLeft");
            int right = left + handle.getWidth();
            return x >= left - padding && x <= right + padding;
        } catch (Throwable t) {
            return true;
        }
    }

    static float getMBackFloat(android.view.View view, String key) {
        Object value = XposedHelpers.getAdditionalInstanceField(view, key);
        return value instanceof Number ? ((Number) value).floatValue() : 0.0f;
    }

    static void cancelMBackLongPress(android.view.View handle) {
        Object runnable = XposedHelpers.getAdditionalInstanceField(handle, "mback_runnable");
        if (runnable instanceof Runnable) handle.removeCallbacks((Runnable) runnable);
        XposedHelpers.setAdditionalInstanceField(handle, "mback_runnable", null);
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
        // 纯视觉反馈层: 不 clickable / 不 focusable / 不设 OnTouchListener。
        // 白条 View 铺满整个导航栏窗口, 一旦这层参与触摸分发就会变成全宽拦截层,
        // 等于在只开 mBack 时也屏蔽了手势区域穿透。
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

    // mBack 反馈层严格跟随 mback_enabled 运行期取值: 开则创建/更新, 关则立即移除。
    // 它只是一层视觉反馈(Ripple), 绝不能参与触摸拦截, 与防穿透功能完全无关。
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
        cancelMBackLongPress(handle);
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "mback_surface");
        if (surface instanceof MBackSurface && ((MBackSurface) surface).getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) ((MBackSurface) surface).getParent()).removeView((MBackSurface) surface);
        }
        XposedHelpers.setAdditionalInstanceField(handle, "mback_surface", null);
    }

    static void triggerNavigation(android.view.View handle, boolean home) {
        try {
            android.view.View parent = handle;
            Class<?> navViewClass = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBarView",
                    handle.getContext().getClassLoader());
            while (parent != null && !navViewClass.isInstance(parent)) {
                parent = parent.getParent() instanceof android.view.View
                        ? (android.view.View) parent.getParent() : null;
            }
            if (parent == null) return;
            if (home) {
                if (injectHomeKey()) return;
                Object homeDispatcher = XposedHelpers.callMethod(parent, "getHomeButton");
                Object homeView = XposedHelpers.callMethod(homeDispatcher, "getCurrentView");
                if (homeView != null) XposedHelpers.callMethod(homeView, "performClick");
                return;
            }
            Object dispatcher = XposedHelpers.callMethod(parent, "getBackButton");
            Object keyView = XposedHelpers.callMethod(dispatcher, "getCurrentView");
            if (keyView == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            XposedHelpers.callMethod(keyView, "sendEvent", 0, 0, now);
            XposedHelpers.callMethod(keyView, "sendEvent", 1, 0);
        } catch (Throwable t) {
            log("mback navigation failed: " + t);
        }
    }

    static boolean injectHomeKey() {
        try {
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Object inputManager = XposedHelpers.callStaticMethod(inputManagerClass, "getInstance");
            long now = android.os.SystemClock.uptimeMillis();
            int flags = android.view.KeyEvent.FLAG_FROM_SYSTEM
                    | android.view.KeyEvent.FLAG_VIRTUAL_HARD_KEY;
            android.view.KeyEvent down = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_HOME, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            android.view.KeyEvent up = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_HOME, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            XposedHelpers.callMethod(inputManager, "injectInputEvent", down, 0);
            XposedHelpers.callMethod(inputManager, "injectInputEvent", up, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
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

        // MBackSurface 完全跟随白条实际绘制位置：与白条中心严格垂直居中，
        // 尺寸 = 白条尺寸 + 上下左右各 getMBackBandPaddingDp() 留白。
        // 白条在 OplusNavigationHandle 内的绘制区域为
        // [viewHeight - mHandleBottom - mHeight, viewHeight - mHandleBottom]（见反编译 onDraw），
        // 防烧屏通过 setTranslationY 平移整个 View，getLocationInWindow 经矩阵映射已含该位移。

        void update() {
            if (getParent() != host) return;
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
            // 白条绘制中心在 host 坐标系中的 y：View 顶 + 白条中心相对 View 顶的偏移，
            // 再扣除手势条加高模块在 onDraw 中的画布上移量。
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

    // 全宽透明拦截层: 覆盖整条手势带(host 整宽 × 手势带高度), 消费所有触摸,
    // 防止点击穿透到下层应用。无任何视觉效果。
    static final class GestureBlockSurface extends android.view.View {
        private final android.view.View source;
        private final android.view.ViewGroup host;

        GestureBlockSurface(android.view.View source, android.view.ViewGroup host) {
            super(source.getContext());
            this.source = source;
            this.host = host;
            setWillNotDraw(true);
        }

        // 覆盖 mBack 热区顶部 -> 窗口底部这一段(全宽), 消费进入窗口的触摸。
        // 实际拦截范围由触摸区域(touchableRegion)决定, 两者必须使用同一段矩形。
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
            Class<?> navBarClass = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBar", lpparam.classLoader);
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
            XposedHelpers.findAndHookMethod(handleClass, "onDraw", android.graphics.Canvas.class,
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
            XposedHelpers.findAndHookMethod(handleClass, "setVertical", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyGestureBarWidth((android.view.View) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyGestureBarWidth((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("gesture_bar hook (handle) failed: " + t);
        }
    }

    // 手势条加高模块在 OplusNavigationHandle#onDraw 中的画布上移量(px)。
    // MBackSurface 定位需扣除同一数值，两者必须保持一致。
    static int getGestureBarCanvasLiftPx() {
        if (readInt(KEY_GESTURE_BAR_HEIGHT_ENABLED, 0) != 1) return 0;
        int dp = 10;
        if (dp <= 0) return 0;
        return Math.round(dp * readDensity() * 0.5f);
    }

    static void applyGestureBarWidth(android.view.View view) {
        try {
            if (readInt(KEY_GESTURE_BAR_WIDTH_ENABLED, 1) != 1) return;
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
