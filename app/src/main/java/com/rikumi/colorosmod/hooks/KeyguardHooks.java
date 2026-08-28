package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.TextSwitcher;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 锁屏 / 解锁界面(bouncer)交互相关的 SystemUI hook。
 */
public final class KeyguardHooks {
    // 解锁时关机无需校验密码。系统"关机校验密码"开关存在 Settings.Secure
    // oplus_shutdown_need_verification_password, 由设置的 ShutdownVerificationPasswordSwitchController 写入。
    // 电源菜单里关机/重启的凭据校验(BiometricPrompt, allowedAuthenticators=DEVICE_CREDENTIAL)只有一个闸门:
    // ShutdownBiometricPrompt.isEnable(Context) —— 返回 true 才弹校验, false 则直接执行关机/重启。
    //   ShutdownViewControl -> AuthenticationListener.handleAuthentication(onSuccess, onError)
    //   -> ShutdownBiometricPrompt.isEnable(mContext) (OplusGlobalActionsDialog / ...SubDisplay 两处)
    // 因此设备已解锁时把该返回值改成 false 即可跳过校验; 锁屏/未解锁时保持系统原生行为。
    public static void hookUnlockedShutdownNoVerify(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.shutdown.ShutdownBiometricPrompt",
                    lpparam.classLoader, "isEnable", android.content.Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_UNLOCKED_SHUTDOWN_NOVERIFY_ENABLED, false)) return;
                                Boolean orig = (Boolean) param.getResult();
                                // 系统本就不需要校验时无需干预。
                                if (orig == null || !orig.booleanValue()) return;
                                if (isDeviceLocked((android.content.Context) param.args[0])) return;
                                param.setResult(Boolean.FALSE);
                            } catch (Throwable t) {
                                log("unlocked_shutdown_noverify error: " + t);
                            }
                        }
                    });
            log("HOOK OK ShutdownBiometricPrompt#isEnable (unlocked_shutdown_noverify)");
        } catch (Throwable t) {
            log("HOOK FAIL ShutdownBiometricPrompt#isEnable :: " + Log.getStackTraceString(t));
        }
    }

    // 设备是否仍处于"未解锁"状态: 锁屏显示中或当前用户尚未解锁(二者任一为真即视为未解锁, 保守不跳过校验)。
    // 取不到状态时返回 true, 保证异常情况下退回系统原生校验行为。
    static boolean isDeviceLocked(android.content.Context ctx) {
        if (ctx == null) return true;
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE);
            if (km == null) return true;
            return km.isKeyguardLocked() || km.isDeviceLocked();
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 锁屏通知区域下移
    //
    // 锁屏上通知区(NotificationStackScrollLayout 的 top padding)的顶部位置有且仅有三个来源:
    //   1. 静止 / 拖拽 / 收起态:
    //      com.android.systemui.shade.NotificationPanelViewController
    //        #getKeyguardNotificationStaticPadding()
    //      非锁屏时直接返回 0(内部 isKeyguardShowing() 为假); 否则返回
    //      KeyguardClockPositionAlgorithm.Result.stackScrollerPadding + 通知堆叠拖拽量。
    //   2. 锁屏通知中心展开态:
    //      com.oplus.systemui.notification.lockscreen.stack
    //        .OplusLockscreenShadeTransitionControllerExImpl#getNtfTopPaddingInLockscreenNtfCenter()
    //      (= 资源 stacked_notification_shade_margin_top, 96dp)
    //   3. 锁屏通知中心收起动画态: 同一 Impl 类的 #getNtfTopPaddingInLockscreen()。
    //      该值由 1 在每次请求时用 setNtfTopPaddingInLockscreen(result.stackScrollerPadding)
    //      写入, 存的是未经本 hook 处理的原始值, 与 1 的返回值是两条独立的数据流。
    //
    // 三者最终都汇入 NotificationStackScrollLayout#updateTopPadding -> AmbientState.topPadding,
    // 由堆栈算法决定首个通知的 y; getKeyguardNotificationStaticPadding 同时还参与
    // KeyguardNotificationStackedRuler 里 "topPadding - staticPadding" 这类差值计算。
    // 因此三处叠加同一个偏移量: 既让通知区整体下移, 又保证所有内部差值与动画起止值不变。
    //
    // dexdump 核对:
    //   classes2.dex: Lcom/android/systemui/shade/NotificationPanelViewController;
    //                   .getKeyguardNotificationStaticPadding:()I  (PUBLIC)
    //                   .isKeyguardShowing:()Z                     (PUBLIC FINAL)
    //   classes3.dex: Lcom/oplus/systemui/notification/lockscreen/stack/
    //                   OplusLockscreenShadeTransitionControllerExImpl;
    //                   .getNtfTopPaddingInLockscreen:()I          (PUBLIC)
    //                   .getNtfTopPaddingInLockscreenNtfCenter:()I (PUBLIC)
    // ---------------------------------------------------------------------------------------------
    private static final String CLS_NOTIFICATION_PANEL_VC =
            "com.android.systemui.shade.NotificationPanelViewController";
    private static final String CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL =
            "com.oplus.systemui.notification.lockscreen.stack."
                    + "OplusLockscreenShadeTransitionControllerExImpl";

    public static void hookKeyguardNotificationOffset(final XC_LoadPackage.LoadPackageParam lpparam,
                                                      final float density) {
        // 1) 静止 / 拖拽 / 收起态
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_NOTIFICATION_PANEL_VC, lpparam.classLoader,
                    "getKeyguardNotificationStaticPadding",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int extra = keyguardNotificationOffsetPx(density);
                            if (extra == 0) return;
                            Object result = param.getResult();
                            if (!(result instanceof Integer)) return;
                            // 非锁屏时原始返回值就是 0, 不能叠加偏移。
                            if (!isKeyguardShowing(param.thisObject)) return;
                            param.setResult(Integer.valueOf((Integer) result + extra));
                        }
                    });
            log("HOOK OK NotificationPanelViewController#getKeyguardNotificationStaticPadding"
                    + " (keyguard_notification_offset)");
        } catch (Throwable t) {
            log("HOOK FAIL NotificationPanelViewController#getKeyguardNotificationStaticPadding :: "
                    + Log.getStackTraceString(t));
        }

        // 2) 3) 锁屏通知中心: 展开态与收起动画态
        String[] ntfPaddingMethods = {
                "getNtfTopPaddingInLockscreen",
                "getNtfTopPaddingInLockscreenNtfCenter",
        };
        for (String methodName : ntfPaddingMethods) {
            try {
                XposedHelpers.findAndHookMethod(
                        CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL, lpparam.classLoader, methodName,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                int extra = keyguardNotificationOffsetPx(density);
                                if (extra == 0) return;
                                Object result = param.getResult();
                                if (result instanceof Integer) {
                                    param.setResult(Integer.valueOf((Integer) result + extra));
                                }
                            }
                        });
                log("HOOK OK OplusLockscreenShadeTransitionControllerExImpl#" + methodName
                        + " (keyguard_notification_offset)");
            } catch (Throwable t) {
                log("HOOK FAIL OplusLockscreenShadeTransitionControllerExImpl#" + methodName + " :: "
                        + Log.getStackTraceString(t));
            }
        }
    }

    /** 锁屏通知区域下移量(px); 功能关闭时为 0。 */
    static int keyguardNotificationOffsetPx(float density) {
        if (!readBool(KEY_KEYGUARD_NOTIFICATION_OFFSET_ENABLED, false)) return 0;
        int dp = readInt(KEY_KEYGUARD_NOTIFICATION_OFFSET_DP,
                KEYGUARD_NOTIFICATION_OFFSET_DP_DEFAULT);
        if (dp < 0) dp = 0;
        if (dp > KEYGUARD_NOTIFICATION_OFFSET_DP_MAX) dp = KEYGUARD_NOTIFICATION_OFFSET_DP_MAX;
        return Math.round(dp * density);
    }

    /** 锁屏是否正在显示; 取不到该方法时不下移(保持原样最安全)。 */
    static boolean isKeyguardShowing(Object panelViewController) {
        try {
            return (Boolean) XposedHelpers.callMethod(panelViewController, "isKeyguardShowing");
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 输入密码界面支持侧滑或下滑返回
    //
    // 需求: 开启后, 在锁屏密码/PIN 界面(bouncer)允许手势返回锁屏。反编译核对(com.android.systemui):
    //
    //   返回入口: bouncer 显示时 StatusBarKeyguardViewManager 经 PrimaryBouncerExpansionCallback 调
    //     registerOnBackInvokedCallback(0, mOnBackInvokedCallback), 该 callback 的 onBackInvokedCompat()
    //     直接调 StatusBarKeyguardViewManager#onBackPressed()。这就是系统 back 收起 bouncer 的入口,
    //     下面两个手势最终都收敛到它。
    //
    //   1) 侧滑: 设备 hide_navigationbar_enable=3(侧滑模式), EdgeBackGestureHandler#onInputEvent$1 因此走
    //      mSideGestureDetector.onMotionEventImpl(ev) —— 真实实现是
    //      com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector
    //      (com.android.systemui.navigationbar.SideGestureDetectorEx 只是空壳基类, hook 它无效;
    //       EdgeBackGestureHandler#isHandlingGestures() 只是 mIsEnabled/mIsBackGestureAllowed 的只读
    //      getter, 不参与逐点判定, 之前 hook 它所以无效)。
    //      onMotionEventImpl 的按下判定链为:
    //        mAllowGesture = !mDisabledForQuickstep && mIsBackGestureAllowed && !z14 && z15
    //      z14 = (mSysUiFlags & 64) != 0(64 = SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING), 命中时日志输出
    //      "back gesture disabled by sysui flags" —— 这就是系统屏蔽侧滑的确切位置。
    //      另外 shouldRespondToGesture() 要求 !mNavBarHidden || mAllowGestureIgnoringBarVisibility,
    //      后者由 mSysUiFlags 的 bit17(131072) 决定。
    //      => 开启且 bouncer 显示时, 在 onMotionEventImpl 执行期间临时清 bit64、置 bit17,
    //         让系统侧滑手势走完, 由 BackAnimation 派发到上面注册的 OnBackInvokedCallback。
    //
    //   2) 下滑: 两种键盘是**不同的类**, 必须各 hook 一个:
    //      - PIN/数字密码界面: com.oplus.keyguard.security.widget.NumericKeyboardWidget
    //        extends com.coui.appcompat.lockview.COUINumericKeyboard(View, 自定义网格绘制)。
    //        ⚠️ 首版只 hook 了 SecurityKeyboardView, 而它仅用于**字母**密码界面
    //        (AlphabetKeyboardWidget 内部持有), PIN 界面根本不加载它, 所以下滑全程无效果。
    //        网格为 4 行 x 3 列, 索引 = row*3+col, 命中判定用私有 checkForNewHit(x, y) 取 Cell:
    //          callback(i): 0..8 -> onClickNumber(i+1); 10 -> onClickNumber(0);
    //                      9 -> onClickLeft(0 左侧键); 11 -> onClickRight(0 右侧/删除键)
    //        => 允许下滑的起点: 没命中(null)、9(左侧键)、11(右侧/删除键); 数字键 0..8/10 不拦截。
    //           这正好覆盖需求里的"0 两侧的不可见按键(或删除按钮可见态)"。
    //      - 字母密码界面: com.oplus.securitykeyboardui.SecurityKeyboardView(自定义 View)。
    //      两者 onTouchEvent 都会把触摸全部消费。之前让 onTouchEvent 在下滑时返 false 无效 ——
    //      一旦它在 ACTION_DOWN 消费了事件, 后续 MOVE 就固定发给它, 父容器拿不到这些点无法判定 fling。
    //      故改为自行识别下滑后直接调 onBackPressed()。
    //
    //   3) 提示文案: OplusKeyguardInputViewController#displayDefaultSecurityMessage 经由
    //      KeyguardMessageAreaController#setOplusBouncerMessage(int, String, boolean) 显示; 开启且文案含
    //      "上滑…指纹解锁"时, 运行时替换为"下滑返回指纹解锁"。
    // ---------------------------------------------------------------------------------------------
    private static final String CLS_SECURITY_KEYBOARD_VIEW =
            "com.oplus.securitykeyboardui.SecurityKeyboardView";
    private static final String CLS_COUI_NUMERIC_KEYBOARD =
            "com.coui.appcompat.lockview.COUINumericKeyboard";
    private static final String CLS_SIDE_GESTURE_DETECTOR =
            "com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector";
    private static final String CLS_STATUS_BAR_KEYGUARD_VIEW_MANAGER =
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager";
    private static final String CLS_MSG_AREA_CONTROLLER =
            "com.android.keyguard.KeyguardMessageAreaController";

    private static final String BOUNCER_SWIPE_BACK_HINT = "下滑返回指纹解锁";

    /** SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING(1<<6): 置位时侧滑被判定为 "back gesture disabled by sysui flags"。 */
    private static final long SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING = 1L << 6;
    /** SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY(1<<17): 置位时 shouldRespondToGesture() 恒为真。 */
    private static final long SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY = 1L << 17;

    /** 缓存 StatusBarKeyguardViewManager 实例, 供触发 onBackPressed() 使用。 */
    private static volatile Object sKeyguardViewManager;

    public static void hookBouncerSwipeBack(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 0) 缓存 StatusBarKeyguardViewManager 实例, 供触发 onBackPressed() 使用。
        //    用 hookAllConstructors(免签名) 而不是等某个方法被调用 —— 后者若总不被调用则实例为 null,
        //    下滑会静默失效。StatusBarKeyguardViewManager 在 SystemUI 中是单例。
        try {
            XposedBridge.hookAllConstructors(
                    XposedHelpers.findClass(CLS_STATUS_BAR_KEYGUARD_VIEW_MANAGER, lpparam.classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sKeyguardViewManager = param.thisObject;
                        }
                    });
            log("HOOK OK StatusBarKeyguardViewManager ctor (bouncer_swipe_back)");
        } catch (Throwable t) {
            log("HOOK FAIL StatusBarKeyguardViewManager ctor :: " + Log.getStackTraceString(t));
        }

        // 1) PIN/数字密码键盘下滑返回(COUINumericKeyboard)
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_COUI_NUMERIC_KEYBOARD, lpparam.classLoader, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED, false)) return;
                            MotionEvent ev = (MotionEvent) param.args[0];
                            int action = ev.getActionMasked();
                            if (action == MotionEvent.ACTION_DOWN) {
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSwipeFired", Boolean.FALSE);
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSwipeStartY", Float.valueOf(ev.getY()));
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSwipeAllowed",
                                        Boolean.valueOf(isNumericKeyboardSwipeStartAllowed(
                                                param.thisObject, ev)));
                                return;
                            }
                            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSwipeFired", Boolean.FALSE);
                            }
                            if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "bouncerSwipeFired"))) {
                                param.setResult(true); // 已触发返回, 吞掉后续事件避免误触按键
                                return;
                            }
                            if (action != MotionEvent.ACTION_MOVE) {
                                return;
                            }
                            if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "bouncerSwipeAllowed"))) {
                                return;
                            }
                            Object start = XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "bouncerSwipeStartY");
                            if (!(start instanceof Float)) return;
                            // 阈值自适应(见 bouncerSwipeThresholdPx)。
                            if (ev.getY() - (Float) start < bouncerSwipeThresholdPx(
                                    (View) param.thisObject, (Float) start)) return;
                            if (dismissBouncer()) {
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSwipeFired", Boolean.TRUE);
                                param.setResult(true);
                            }
                        }
                    });
            log("HOOK OK COUINumericKeyboard#onTouchEvent (bouncer_swipe_back)");
        } catch (Throwable t) {
            log("HOOK FAIL COUINumericKeyboard#onTouchEvent :: " + Log.getStackTraceString(t));
        }

        // 2) 字母密码键盘下滑返回(SecurityKeyboardView)
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_SECURITY_KEYBOARD_VIEW, lpparam.classLoader, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED, false)) return;
                            MotionEvent ev = (MotionEvent) param.args[0];
                            int action = ev.getActionMasked();
                            if (action == MotionEvent.ACTION_DOWN) {
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSwipeFired", Boolean.FALSE);
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSwipeStartY", Float.valueOf(ev.getY()));
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSwipeAllowed",
                                        Boolean.valueOf(isKeyboardSwipeStartAllowed(param.thisObject, ev)));
                                return;
                            }
                            if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "bouncerSwipeFired"))) {
                                param.setResult(true); // 已触发返回, 吞掉后续事件避免误触按键
                                return;
                            }
                            if (action != MotionEvent.ACTION_MOVE) {
                                return;
                            }
                            if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "bouncerSwipeAllowed"))) {
                                return;
                            }
                            Object start = XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "bouncerSwipeStartY");
                            if (!(start instanceof Float)) return;
                            if (ev.getY() - (Float) start < bouncerSwipeThresholdPx(
                                    (View) param.thisObject, (Float) start)) return;
                            if (dismissBouncer()) {
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSwipeFired", Boolean.TRUE);
                                param.setResult(true);
                            }
                        }
                    });
            log("HOOK OK SecurityKeyboardView#onTouchEvent (bouncer_swipe_back)");
        } catch (Throwable t) {
            log("HOOK FAIL SecurityKeyboardView#onTouchEvent :: " + Log.getStackTraceString(t));
        }

        // 3) 系统侧滑返回放行: 临时摘掉 mSysUiFlags 里的 keyguard 位, 让侧滑走完系统 back 链路
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_SIDE_GESTURE_DETECTOR, lpparam.classLoader, "onMotionEventImpl", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED, false)) return;
                            Object ksc = XposedHelpers.getObjectField(param.thisObject, "mKeyguardStateController");
                            if (ksc == null || !isPrimaryBouncerShowing(ksc)) return;
                            long flags = XposedHelpers.getLongField(param.thisObject, "mSysUiFlags");
                            XposedHelpers.setAdditionalInstanceField(
                                    param.thisObject, "bouncerSavedSysUiFlags", Long.valueOf(flags));
                            XposedHelpers.setLongField(param.thisObject, "mSysUiFlags",
                                    (flags & ~SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING)
                                            | SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object saved = XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "bouncerSavedSysUiFlags");
                            if (saved instanceof Long) {
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "bouncerSavedSysUiFlags", null);
                                XposedHelpers.setLongField(param.thisObject, "mSysUiFlags", (Long) saved);
                            }
                        }
                    });
            log("HOOK OK SideGestureDetector#onMotionEventImpl (bouncer_swipe_back)");
        } catch (Throwable t) {
            log("HOOK FAIL SideGestureDetector#onMotionEventImpl :: " + Log.getStackTraceString(t));
        }

        // 3) 提示文案: "上滑使用指纹解锁" -> "下滑返回指纹解锁"
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_MSG_AREA_CONTROLLER, lpparam.classLoader, "setOplusBouncerMessage",
                    int.class, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED, false)) return;
                            Object msg = param.args[1];
                            if (msg instanceof String) {
                                String s = (String) msg;
                                if (s.contains("上滑") && s.contains("指纹")) {
                                    param.args[1] = BOUNCER_SWIPE_BACK_HINT;
                                }
                            }
                        }
                    });
            log("HOOK OK KeyguardMessageAreaController#setOplusBouncerMessage (bouncer_swipe_back)");
        } catch (Throwable t) {
            log("HOOK FAIL KeyguardMessageAreaController#setOplusBouncerMessage :: " + Log.getStackTraceString(t));
        }
    }

    /** primary bouncer 是否显示; 取不到时按 false 处理(不改变原行为)。 */
    private static boolean isPrimaryBouncerShowing(Object ksc) {
        try {
            Object r = XposedHelpers.callMethod(ksc, "isPrimaryBouncerShowing");
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) {
            // 退回读字段
        }
        try {
            return XposedHelpers.getBooleanField(ksc, "mPrimaryBouncerShowing");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 键盘下滑触发阈值(px), 必须随起手位置自适应。
     *
     * ⚠️ 固定阈值不可行: "0 两侧按钮"与删除键位于键盘**最底行**, 紧贴屏幕底部。从那里往下到屏幕
     * 边缘通常只剩几十 px, 固定 48dp(约 144px) 时手指滑出屏幕也达不到, 手势永远不触发 ——
     * 这正是前几版"侧滑生效但 0 两侧下滑无反应"的原因。
     *
     * 手指一旦成为该 View 的触摸目标, 滑出 View 边界仍会持续收到 MOVE 事件, 但超出屏幕就收不到了,
     * 因此可用滑动距离 = 起始点到屏幕底部的距离。取该距离的 60% 为阈值(上限 48dp), 保证任何位置
     * 起手都滑得到; 起手越低阈值越小, 但仍是明确的向下拖拽动作。
     */
    private static float bouncerSwipeThresholdPx(View v, float startY) {
        DisplayMetrics dm = v.getResources().getDisplayMetrics();
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float toScreenBottom = dm.heightPixels - (loc[1] + startY);
        return Math.min(48.0f * dm.density, toScreenBottom * 0.6f);
    }

    /**
     * PIN/数字键盘下滑起点是否允许触发返回。
     *
     * 4 行 x 3 列网格, 索引 = row*3+col。COUINumericKeyboard#callback(i) 的分工:
     * 0..8 -> 数字 1-9, 10 -> 数字 0, 9 -> 左侧键, 11 -> 右侧键(删除)。
     * 因此只允许: 没命中任何键(null)、9(0 左侧键)、11(0 右侧/删除键);
     * 落在数字键上(0..8, 10)时不拦截, 交给键盘正常输入。
     * checkForNewHit 是私有方法, XposedHelpers.callMethod 会自动 setAccessible。
     */
    private static boolean isNumericKeyboardSwipeStartAllowed(Object keyboardView, MotionEvent ev) {
        // 主判定: 私有方法 checkForNewHit(FF)。它一旦反射失败会抛异常 -> 恒 false -> 整个功能静默
        // 失效, 故另加一层纯几何兜底。
        boolean byCell = false;
        try {
            Object cell = XposedHelpers.callMethod(
                    keyboardView, "checkForNewHit", Float.valueOf(ev.getX()), Float.valueOf(ev.getY()));
            if (cell == null) {
                byCell = true; // 按键间隙
            } else {
                Object row = XposedHelpers.callMethod(cell, "getRow");
                Object col = XposedHelpers.callMethod(cell, "getColumn");
                if (row instanceof Integer && col instanceof Integer) {
                    int idx = (Integer) row * 3 + (Integer) col;
                    byCell = (idx == 9 || idx == 11); // 0 左侧键 / 右侧删除键
                }
            }
        } catch (Throwable ignored) {
            // 反射失败时走下面的几何兜底
        }

        // 几何兜底: 语义与主判定等价(最底行 + 左右两侧列, 排除中间的 0), 只依赖 getWidth/getHeight。
        // 底行从 3/4 处开始, 故阈值取 0.75, 避免误判到倒数第二行的 7 / 9。
        if (!byCell && keyboardView instanceof View) {
            View v = (View) keyboardView;
            float h = v.getHeight();
            float w = v.getWidth();
            boolean bottomRow = h > 0 && ev.getY() > h * 0.75f;
            boolean sideCol = w > 0 && (ev.getX() < w / 3.0f || ev.getX() > w * 2.0f / 3.0f);
            return bottomRow && sideCol;
        }
        return byCell;
    }

    /**
     * 键盘下滑起点是否允许触发返回。
     *
     * 注意不能用 "getKeyIndices 返回 -1" 作为唯一依据: 该方法仅当落点落在特殊符号竖列
     * (x <= mSpecialKeyWidth 的窄条) 内才返回 -1, 0 两侧的按钮 / 删除键 / 空隙都返回**有效索引**,
     * 首版据此误把这些区域全排除了, 导致 0 两侧下滑无效。
     * 改为取 mKeys[idx].codes[0]: 只排除数字键 '0'-'9', 其余(特殊符号、删除键 -5、按键空隙)均允许。
     */
    private static boolean isKeyboardSwipeStartAllowed(Object keyboardView, MotionEvent ev) {
        try {
            Object idxObj = XposedHelpers.callMethod(
                    keyboardView, "getKeyIndices", (int) ev.getX(), (int) ev.getY(), null);
            if (!(idxObj instanceof Integer)) return false;
            int idx = (Integer) idxObj;
            if (idx < 0) return true; // 非按键区 / 特殊符号列
            Object[] mKeys = (Object[]) XposedHelpers.getObjectField(keyboardView, "mKeys");
            if (mKeys == null || idx >= mKeys.length || mKeys[idx] == null) return false;
            int[] codes = (int[]) XposedHelpers.getObjectField(mKeys[idx], "codes");
            if (codes == null || codes.length == 0) return false;
            int code = codes[0];
            return code < '0' || code > '9'; // 数字键交给键盘正常输入
        } catch (Throwable t) {
            return false;
        }
    }

    /** 收起 bouncer 返回锁屏, 与系统 back / 背景层下滑同一入口。 */
    private static boolean dismissBouncer() {
        Object kvm = sKeyguardViewManager;
        if (kvm == null) return false;
        try {
            Boolean showing = (Boolean) XposedHelpers.callMethod(kvm, "isBouncerShowing");
            if (showing == null || !showing) return false;
            XposedHelpers.callMethod(kvm, "onBackPressed");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
