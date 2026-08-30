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
 * system_server(android) 作用域的全部 hook：小窗贴边挂机、横屏小窗保持比例。
 */
public final class SystemServerHooks {
    // 贴边挂机: 拦 TaskExtImpl#moveTaskToBackForPanorama(只切后台), 让图标动画跑完并把任务留在前台。
    // 不可拦 exitFlexibleTaskWindowInnerLocked —— 图标成形/缩小动画在其 handleEvent() 内, 截断就卡在松手位置。
    // 同时把焦点交给小窗下方任务, 避免"窗口已 hide 但仍 focused"导致音量键无响应 / ANR。
    public static void hookFloatWindowEdgeHangSystemServer(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> taskExtImplCls = XposedHelpers.findClass(
                    "com.android.server.wm.TaskExtImpl", lpparam.classLoader);
            final Class<?> taskCls = XposedHelpers.findClass(
                    "com.android.server.wm.Task", lpparam.classLoader);
            final Class<?> fhcCls = XposedHelpers.findClass(
                    "com.android.server.wm.FloatHandleController", lpparam.classLoader);
            final Class<?> fwmsCls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleWindowManagerService", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(taskExtImplCls, "moveTaskToBackForPanorama",
                    taskCls, boolean.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED, false)) return;
                                final Object task = param.args[0];
                                if (task == null) return;
                                final int taskId = XposedHelpers.getIntField(task, "mTaskId");

                                // 只介入"贴边成浮窗"一路: 此时 addFloatHandle 已把任务加入浮窗列表。
                                Object fhc = XposedHelpers.callStaticMethod(fhcCls, "getInstance");
                                if (!Boolean.TRUE.equals(
                                        XposedHelpers.callMethod(fhc, "isInFloatingList", taskId))) {
                                    return;
                                }

                                // 先把焦点交给小窗下方任务, 避免 hidden + focused 造成 ANR。
                                focusTaskBehind(fwmsCls, task);

                                // 跳过 Task.moveTaskToBack: 任务保持在台前, 应用继续挂机。
                                param.setResult(null);
                                log(">>> edge_hang: skip moveTaskToBack, taskId=" + taskId);
                            } catch (Throwable t) {
                                log("!!! edge_hang error: " + t);
                            }
                        }
                    });
            log(">>> matched android (system_server): float_window_edge_hang (skip moveTaskToBack)");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang system_server hook failed: " + t);
        }
    }

    // 把焦点交给小窗下方任务。FlexibleTaskController#setFocusTask 内部即 AOSP
    // ActivityTaskManagerService#setFocusedTask(taskId)(FlexibleTaskController.java:9310)。
    private static void focusTaskBehind(Class<?> fwmsCls, Object floatTask) {
        try {
            Object fwms = XposedHelpers.callStaticMethod(fwmsCls, "getInstance", new Object[] { null });
            if (fwms == null) return;
            Object ftc = XposedHelpers.callMethod(fwms, "getFlexibleTaskController");
            if (ftc == null) return;
            // getTaskUnderFlexible 是 private, XposedHelpers 反射可调用。
            Object behind = XposedHelpers.callMethod(ftc, "getTaskUnderFlexible", floatTask);
            if (behind == null) return;
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(behind, "isTopActivityFocusable"))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(behind, "isVisible"))) {
                return;
            }
            XposedHelpers.callMethod(ftc, "setFocusTask", behind);
        } catch (Throwable t) {
            log("!!! edge_hang focusTaskBehind failed: " + t);
        }
    }

    // 横屏应用小窗保持比例: 系统 fillFlexibleTaskInfo 对横屏应用硬编码 ratio=0.5625f(9:16)。
    // afterHook fillFlexibleTaskInfo 改 ratio 并按系统同款公式重算 scale 与 launchBounds;
    // afterHook getFlexibleTaskAvailableRatioByActivity 把目标比例加入可选列表。
    public static void hookFloatWindowLandscapeKeepRatio(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> ftc = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskController", lpparam.classLoader);
            // FlexibleTaskInfo 与 Builder 在同一包内, 用同 ClassLoader 取。
            final Class<?> ftiClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskInfo", lpparam.classLoader);

            // 1) fillFlexibleTaskInfo: 修正横屏应用小窗的 ratio / scale / launchBounds
            XposedHelpers.findAndHookMethod(ftc, "fillFlexibleTaskInfo",
                    "com.android.server.wm.FlexibleTaskInfo$Builder",
                    android.graphics.Rect.class,
                    android.content.Intent.class,
                    android.content.pm.ActivityInfo.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED, false)) return;
                                if (!((Boolean) param.args[4])) return; // 非横屏应用不处理
                                Object result = param.getResult();
                                if (result == null) return;
                                android.graphics.Rect windowBounds = (android.graphics.Rect) param.args[1];
                                if (windowBounds == null || windowBounds.isEmpty()) return;
                                final int wW = windowBounds.width();
                                final int wH = windowBounds.height();
                                if (wW <= 0 || wH <= 0) return;
                                // 目标 ratio = 高/宽 = 屏幕宽/屏幕高 = 1 / getFlexibleTaskFullScreenRatio(wH, wW)
                                float fullScreenRatio = ((Number) XposedHelpers.callMethod(
                                        param.thisObject, "getFlexibleTaskFullScreenRatio", wH, wW)).floatValue();
                                if (fullScreenRatio <= 0f) return;
                                final float targetRatio = 1.0f / fullScreenRatio; // 高/宽
                                final float scale = targetRatio / (wW * 1.0f / wH);
                                XposedHelpers.callMethod(result, "setRatio", targetRatio);
                                XposedHelpers.callMethod(result, "setScale", scale);
                                // 重算 launchBounds: 保持系统选定的高度, 按目标比例求宽, 在 windowBounds 内居中
                                Object oldBounds = XposedHelpers.callMethod(result, "getLaunchBounds");
                                if (oldBounds instanceof android.graphics.Rect) {
                                    android.graphics.Rect ob = (android.graphics.Rect) oldBounds;
                                    final int h = ob.height();
                                    if (h > 0) {
                                        final int nw = (int) (h / targetRatio + 0.5f);
                                        final int left = ob.centerX() - nw / 2;
                                        final int top = ob.top;
                                        android.graphics.Rect nb = new android.graphics.Rect(left, top, left + nw, top + h);
                                        // 约束在 windowBounds 内(避免越界)
                                        if (nb.right > windowBounds.right) nb.offset(-(nb.right - windowBounds.right), 0);
                                        if (nb.left < windowBounds.left) nb.offset(windowBounds.left - nb.left, 0);
                                        XposedHelpers.callMethod(result, "setLaunchBounds", nb);
                                    }
                                }
                            } catch (Throwable t) {
                                log("!!! landscape_keep_ratio fillFlexibleTaskInfo failed: " + t);
                            }
                        }
                    });

            // 2) getFlexibleTaskAvailableRatioByActivity: 把目标比例加入可选列表(拖拽缩放可达)
            XposedHelpers.findAndHookMethod(ftc, "getFlexibleTaskAvailableRatioByActivity",
                    "com.android.server.wm.ActivityRecord",
                    String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED, false)) return;
                                Object result = param.getResult();
                                if (!(result instanceof java.util.List)) return;
                                @SuppressWarnings("unchecked")
                                java.util.List<Float> list = (java.util.List<Float>) result;
                                android.graphics.Rect windowBounds = (android.graphics.Rect) param.args[1];
                                int wW = 0, wH = 0;
                                if (windowBounds != null && !windowBounds.isEmpty()) {
                                    wW = windowBounds.width(); wH = windowBounds.height();
                                } else {
                                    android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
                                    wW = dm.widthPixels; wH = dm.heightPixels;
                                }
                                if (wW <= 0 || wH <= 0) return;
                                float fullScreenRatio = ((Number) XposedHelpers.callMethod(
                                        param.thisObject, "getFlexibleTaskFullScreenRatio", wH, wW)).floatValue();
                                if (fullScreenRatio <= 0f) return;
                                final float targetRatio = 1.0f / fullScreenRatio;
                                boolean has = false;
                                for (Float f : list) {
                                    if (Math.abs(f - targetRatio) < 0.001f) { has = true; break; }
                                }
                                if (!has) list.add(targetRatio);
                            } catch (Throwable t) {
                                log("!!! landscape_keep_ratio getFlexibleTaskAvailableRatioByActivity failed: " + t);
                            }
                        }
                    });

            log(">>> matched android (system_server): float_window_landscape_keep_ratio (FlexibleTaskController)");
        } catch (Throwable t) {
            log("!!! float_window_landscape_keep_ratio system_server hook failed: " + t);
        }
    }
}
