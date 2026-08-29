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
    // 解锁时关机无需校验密码。电源菜单的凭据校验只有一个闸门: ShutdownBiometricPrompt.isEnable(Context)
    // —— 返回 true 才弹校验, false 则直接执行关机/重启。
    // 故设备已解锁时把返回值改成 false 即可跳过; 锁屏/未解锁时保持系统原生行为。
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

    // 锁屏通知区域下移。通知区顶部位置有三处来源: NotificationPanelViewController#getKeyguardNotification
    // StaticPadding、OplusLockscreenShadeTransitionControllerExImpl#getNtfTopPaddingInLockscreen(NtfCenter)。
    // 三者汇入 updateTopPadding 且参与内部差值计算, 故叠加同一偏移量以保持差值与动画起止值不变。
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

    // 输入密码界面支持侧滑/下滑返回, 两者最终都收敛到 StatusBarKeyguardViewManager#onBackPressed。
    // 侧滑: bouncer 显示时临时清 SideGestureDetector 的 mSysUiFlags bit64、置 bit17 以放行手势。
    // 下滑: PIN(COUINumericKeyboard)与字母(SecurityKeyboardView)两类各 hook 一个, 自识别下滑后回调。
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

    // 键盘下滑触发阈值(px), 必须随起手位置自适应: "0 两侧按钮"与删除键在键盘最底行、紧贴屏幕底部,
    // 固定 48dp 时手指滑出屏幕也达不到(前几版 0 两侧下滑无反应的原因)。滑出 View 仍收到 MOVE、超出屏幕
    // 则收不到, 故可用距离 = 起始点到屏幕底部; 取其 60% 为阈值(上限 48dp), 保证任何位置起手都滑得到。
    private static float bouncerSwipeThresholdPx(View v, float startY) {
        DisplayMetrics dm = v.getResources().getDisplayMetrics();
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float toScreenBottom = dm.heightPixels - (loc[1] + startY);
        return Math.min(48.0f * dm.density, toScreenBottom * 0.6f);
    }

    // PIN/数字键盘下滑起点: 4 行 x 3 列网格, 索引 = row*3+col, callback(i) 中 0..8->数字 1-9、10->0、
    // 9/11->左右侧键(11=删除); 只允许未命中、9、11 触发, 落在数字键上交给键盘。checkForNewHit 是私有方法。
        // 主判定用私有方法 checkForNewHit(FF); 反射失败会恒 false 致功能静默失效, 故另加几何兜底。
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

    // 键盘下滑起点是否允许触发返回。不能用 "getKeyIndices 返回 -1" 作唯一依据: 它仅当落点在特殊符号
    // 竖列(x <= mSpecialKeyWidth)内才返回 -1, 0 两侧按钮/删除键/空隙都返回有效索引, 首版据此误排除了它们。
    // 改为取 mKeys[idx].codes[0]: 只排除数字键 '0'-'9', 其余(特殊符号、删除键 -5、空隙)均允许。
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
