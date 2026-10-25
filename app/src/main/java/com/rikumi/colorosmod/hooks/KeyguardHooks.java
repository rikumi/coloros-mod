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

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

public final class KeyguardHooks {
    private static final String CLS_OPLUS_BIOMETRIC_UNLOCK_CONTROLLER =
            "com.oplus.systemui.statusbar.phone.OplusBiometricUnlockControllerExImpl";
    private static final String CLS_BIOMETRIC_UNLOCK_CONTROLLER =
            "com.android.systemui.statusbar.phone.BiometricUnlockController";
    private static final java.util.concurrent.atomic.AtomicInteger sFingerprintFailureCount =
            new java.util.concurrent.atomic.AtomicInteger();

    public static void hookFingerprintFailureDelay(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_OPLUS_BIOMETRIC_UNLOCK_CONTROLLER, lpparam.classLoader,
                    "handleOnBiometricAuthFailed",
                    XposedHelpers.findClass(CLS_BIOMETRIC_UNLOCK_CONTROLLER, lpparam.classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_KEYGUARD_FINGERPRINT_FAILURE_DELAY_ENABLED, false)) {
                                sFingerprintFailureCount.set(0);
                                return;
                            }
                            if (sFingerprintFailureCount.incrementAndGet() <= 3) {
                                // 返回 true 表示 Oplus 已处理该失败，阻止后续的通用 UDFPS 阈值弹窗。
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            log("HOOK OK OplusBiometricUnlockControllerExImpl#handleOnBiometricAuthFailed"
                    + " (keyguard_fingerprint_failure_delay)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusBiometricUnlockControllerExImpl#handleOnBiometricAuthFailed :: "
                    + Log.getStackTraceString(t));
        }

        try {
            XposedHelpers.findAndHookMethod(
                    CLS_BIOMETRIC_UNLOCK_CONTROLLER, lpparam.classLoader,
                    "onBiometricAuthenticated", int.class,
                    XposedHelpers.findClass("android.hardware.biometrics.BiometricSourceType",
                            lpparam.classLoader), boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object source = param.args[1];
                            if (source != null && "FINGERPRINT".equals(source.toString())) {
                                sFingerprintFailureCount.set(0);
                            }
                        }
                    });
            log("HOOK OK BiometricUnlockController#onBiometricAuthenticated"
                    + " (keyguard_fingerprint_failure_delay)");
        } catch (Throwable t) {
            log("HOOK FAIL BiometricUnlockController#onBiometricAuthenticated :: "
                    + Log.getStackTraceString(t));
        }
    }

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

    private static final String CLS_NOTIFICATION_PANEL_VC =
            "com.android.systemui.shade.NotificationPanelViewController";
    private static final String CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL =
            "com.oplus.systemui.notification.lockscreen.stack."
                    + "OplusLockscreenShadeTransitionControllerExImpl";
    private static final String CLS_LEGACY_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL =
            "com.android.systemui.statusbar.OplusLockscreenShadeTransitionControllerExImpl";

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
                Class<?> transitionClass = XposedHelpers.findClassIfExists(
                        CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL, lpparam.classLoader);
                if (transitionClass == null) {
                    transitionClass = XposedHelpers.findClass(
                            CLS_LEGACY_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL, lpparam.classLoader);
                }
                XposedHelpers.findAndHookMethod(
                        transitionClass, methodName,
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

    static int keyguardNotificationOffsetPx(float density) {
        if (!readBool(KEY_KEYGUARD_NOTIFICATION_OFFSET_ENABLED, false)) return 0;
        int dp = readInt(KEY_KEYGUARD_NOTIFICATION_OFFSET_DP,
                KEYGUARD_NOTIFICATION_OFFSET_DP_DEFAULT);
        if (dp < 0) dp = 0;
        if (dp > KEYGUARD_NOTIFICATION_OFFSET_DP_MAX) dp = KEYGUARD_NOTIFICATION_OFFSET_DP_MAX;
        return Math.round(dp * density);
    }

    static boolean isKeyguardShowing(Object panelViewController) {
        try {
            return (Boolean) XposedHelpers.callMethod(panelViewController, "isKeyguardShowing");
        } catch (Throwable t) {
            return false;
        }
    }

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

    private static final long SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING = 1L << 6;

    private static final long SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY = 1L << 17;

    private static volatile Object sKeyguardViewManager;

    public static Object captureHotReloadHost() {
        return sKeyguardViewManager;
    }

    public static void restoreHotReloadHost(Object host) {
        if (host != null && CLS_STATUS_BAR_KEYGUARD_VIEW_MANAGER.equals(host.getClass().getName())) {
            sKeyguardViewManager = host;
        }
    }

    public static void cleanupForHotReload() {
        KeyguardCarrierHooks.cleanup();
        KeyguardClockFontHooks.cleanup();
        sKeyguardViewManager = null;
    }

    public static void hookBouncerSwipeBack(final XC_LoadPackage.LoadPackageParam lpparam) {

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
            // 限定本类声明: onTouchEvent 在 View 里有实现, 上溯会命中所有 View。
            XposedHelpers.findAndHookDeclaredMethod(
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
            // 限定本类声明: onTouchEvent 在 View 里有实现, 上溯会命中所有 View。
            XposedHelpers.findAndHookDeclaredMethod(
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

    private static float bouncerSwipeThresholdPx(View v, float startY) {
        DisplayMetrics dm = v.getResources().getDisplayMetrics();
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float toScreenBottom = dm.heightPixels - (loc[1] + startY);
        return Math.min(48.0f * dm.density, toScreenBottom * 0.6f);
    }

    private static boolean isNumericKeyboardSwipeStartAllowed(Object keyboardView, MotionEvent ev) {
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
    private static final String CLS_CHARGING_ANIMATION_IMPL =
            "com.oplus.systemui.keyguard.charginganim.ChargingAnimationImpl";

    public static void hookKeyguardNoChargeAnim(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(CLS_CHARGING_ANIMATION_IMPL, lpparam.classLoader,
                    "updateAnimeState", int.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (readBool(KEY_KEYGUARD_NO_CHARGE_ANIM_ENABLED, false)
                            && param.args[0] instanceof Integer
                            && ((Integer) param.args[0]) == 1) param.setResult(null);
                }
            });
            log("HOOK OK ChargingAnimationImpl#updateAnimeState (keyguard_no_charge_anim)");
        } catch (Throwable t) {
            log("HOOK FAIL ChargingAnimationImpl#updateAnimeState :: " + Log.getStackTraceString(t));
        }
    }
}
