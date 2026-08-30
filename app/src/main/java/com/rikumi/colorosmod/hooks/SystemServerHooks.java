package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import java.util.Set;

import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.TextSwitcher;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

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
            sFloatHandleController = fhcCls;
            sFlexibleWindowService = fwmsCls;

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
                                focusTaskBehind(task);

                                // 可选: 贴边挂机静音, 回到前台时恢复。
                                if (readBool(KEY_FLOAT_WINDOW_EDGE_HANG_MUTE_ENABLED, false)) {
                                    muteFloatTask(taskId, task);
                                }

                                // 跳过 Task.moveTaskToBack: 任务保持在台前, 应用继续挂机。
                                param.setResult(null);
                                sHungTaskIds.add(Integer.valueOf(taskId));
                                sHungTasks.put(Integer.valueOf(taskId), new WeakReference<Object>(task));
                                log(">>> edge_hang: skip moveTaskToBack, taskId=" + taskId);
                            } catch (Throwable t) {
                                log("!!! edge_hang error: " + t);
                            }
                        }
                    });

            // 挂机期间锁屏再解锁, 系统会重新把任务的 surface show 出来(ActivityRecord#setVisibility
            // 会连带 show 所有父容器 surface 并把 Task.mLastSurfaceShowing 置 true), 真实窗口就会重新出现。
            // 这里在 Task#prepareSurfaces 之后把挂机任务的 surface 重新 hide, 作为恒常不变式。
            XposedHelpers.findAndHookMethod(taskCls, "prepareSurfaces", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sHungTaskIds.isEmpty()) return;
                        final int taskId = XposedHelpers.getIntField(param.thisObject, "mTaskId");
                        if (!sHungTaskIds.contains(taskId)) return;
                        XposedHelpers.setAdditionalInstanceField(param.thisObject,
                                LAST_SHOWING, Boolean.valueOf(
                                        XposedHelpers.getBooleanField(param.thisObject, "mLastSurfaceShowing")));
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Object task = param.thisObject;
                    Boolean before = (Boolean) XposedHelpers.getAdditionalInstanceField(task, LAST_SHOWING);
                    XposedHelpers.removeAdditionalInstanceField(task, LAST_SHOWING);
                    try {
                        if (sHungTaskIds.isEmpty() || task == null) return;
                        final int taskId = XposedHelpers.getIntField(task, "mTaskId");
                        if (!sHungTaskIds.contains(taskId)) return;

                        if (!isTaskInFloatingList(taskId)) {
                            // 已还原成浮窗(点把手)或已被移除: 交还系统。mLastSurfaceShowing 置 false,
                            // 否则系统认为 surface 已是 shown, 不再下发 show, 窗口反倒不再显示。
                            sHungTaskIds.remove(taskId);
                            sHungTasks.remove(Integer.valueOf(taskId));
                            XposedHelpers.setBooleanField(task, "mLastSurfaceShowing", false);
                            log(">>> edge_hang: task " + taskId + " left floating list, release surface");
                            return;
                        }

                        Object sc = XposedHelpers.callMethod(task, "getSurfaceControl");
                        if (sc == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(sc, "isValid"))) {
                            return;
                        }
                        if (Boolean.FALSE.equals(before) && XposedHelpers.getBooleanField(task, "mLastSurfaceShowing")) {
                            log(">>> edge_hang: system re-showed task " + taskId + " surface, hide again");
                        }
                        XposedHelpers.callMethod(XposedHelpers.callMethod(task, "getSyncTransaction"),
                                "hide", sc);
                    } catch (Throwable t) {
                        log("!!! edge_hang prepareSurfaces failed: " + t);
                    }
                }
            });
            log(">>> matched android (system_server): float_window_edge_hang (skip moveTaskToBack)");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang system_server hook failed: " + t);
        }

        // 挂机任务没有 moveTaskToBack, 解锁时系统 resume 台前任务会把焦点指回它;
        // 它的 surface 已被我们隐藏, 焦点落在不可见窗口上会导致音量键等事件 dispatch 失败。
        // 解锁后把焦点交还给小窗后面的主窗口。
        try {
            final Class<?> ftcCls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskController", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(ftcCls, "notifyKeyguardStateChanged",
                    boolean.class, boolean.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (sHungTaskIds.isEmpty()) return;
                                // args: (keyguardChanged, keyguardShowing, displayId); 只处理解锁。
                                if (!Boolean.TRUE.equals(param.args[0])
                                        || Boolean.TRUE.equals(param.args[1])) return;
                                // 解锁后的 resume 晚于本回调, 延后纠正; 再复查一次兜住更慢的时序。
                                postAsyncDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        refocusBehindHungTasks("unlock");
                                    }
                                }, 500);
                                postAsyncDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        refocusBehindHungTasks("unlock-recheck");
                                    }
                                }, 1500);
                            } catch (Throwable t) {
                                log("!!! edge_hang keyguard refocus hook failed: " + t);
                            }
                        }
                    });
            log(">>> matched android (system_server): float_window_edge_hang (refocus on unlock)");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang keyguard refocus hook failed: " + t);
        }

        // 不变式: 任何把焦点指向挂机任务的调用, 一律改指到它下面的主窗口。
        // 解锁时系统会 resume 台前任务把焦点拨回小窗, 而小窗 surface 已被隐藏, 焦点落在看不见的窗口上
        // 会让音量键等按键事件 dispatch 失败。一次性纠正会被后续焦点计算覆盖, 故做成不变式。
        try {
            final Class<?> dcCls = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayContent", lpparam.classLoader);
            final Class<?> arCls = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(dcCls, "setFocusedApp", arCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sHungTaskIds.isEmpty()) return;
                        Object ar = param.args[0];
                        if (ar == null) return;
                        Object t = XposedHelpers.callMethod(ar, "getTask");
                        if (t == null) return;
                        final Integer id = Integer.valueOf(XposedHelpers.getIntField(t, "mTaskId"));
                        if (!sHungTaskIds.contains(id)) return;
                        if (!isTaskInFloatingList(id.intValue())) {
                            // 已还原成浮窗/已关闭: 焦点归它是对的, 交还系统。
                            sHungTaskIds.remove(id);
                            sHungTasks.remove(id);
                            return;
                        }
                        Object behind = findBehindActivity(t);
                        if (behind == null) return;
                        param.args[0] = behind;
                        log(">>> edge_hang: redirect focus off hung task " + id);
                    } catch (Throwable t2) {
                        log("!!! edge_hang setFocusedApp hook failed: " + t2);
                    }
                }
            });
            log(">>> matched android (system_server): float_window_edge_hang (focus invariant)");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang focus invariant hook failed: " + t);
        }
    }

    // 若焦点正落在挂机小窗上, 把它交还给下面的主窗口。
    private static void refocusBehindHungTasks(String reason) {
        try {
            if (sHungTaskIds.isEmpty()) return;
            for (Integer id : new ArrayList<Integer>(sHungTaskIds)) {
                if (id == null) continue;
                WeakReference<Object> ref = sHungTasks.get(id);
                Object task = ref == null ? null : ref.get();
                if (task == null) {
                    sHungTaskIds.remove(id);
                    sHungTasks.remove(id);
                    continue;
                }
                if (!isTaskInFloatingList(id)) {
                    // 已还原成浮窗: 焦点归它是对的, 不再干预。
                    sHungTaskIds.remove(id);
                    sHungTasks.remove(id);
                    continue;
                }
                if (!isFocusedTask(task)) continue;
                focusTaskBehind(task);
                log(">>> edge_hang: refocus behind on " + reason + ", taskId=" + id);
            }
        } catch (Throwable t) {
            log("!!! edge_hang refocus failed: " + t);
        }
    }

    // 焦点是否在该任务上。Task 没有可靠的 isFocused(), 用 DisplayContent.mFocusedApp 判定。
    private static boolean isFocusedTask(Object task) {
        try {
            Object dc = XposedHelpers.getObjectField(task, "mDisplayContent");
            if (dc == null) return false;
            Object focusedApp = XposedHelpers.getObjectField(dc, "mFocusedApp");
            if (focusedApp == null) return false;
            return XposedHelpers.callMethod(focusedApp, "getTask") == task;
        } catch (Throwable t) {
            return false;
        }
    }

    // 把焦点交给小窗下方任务。不走 FlexibleTaskController#setFocusTask:
    // 它最终是 ATMS#setFocusedTask(taskId, null), 而该方法只在 moveFocusableActivityToTop 成功时
    // 才真正转移焦点(ActivityTaskManagerService.java:1763/1769) —— touchedActivity 为 null 且移不动时
    // 什么都不做(实测无效); 一旦移动成功, 后方任务被移到 top 并 resume, 挂机任务随即被 pause, 挂机失效。
    // 这里改走 AOSP 同一分支中"只更新焦点不动栈"的做法(DisplayContent#setFocusedApp + 重算焦点窗口)。
    private static void focusTaskBehind(Object floatTask) {
        try {
            Object ar = findBehindActivity(floatTask);
            if (ar == null) return;
            Object dc = XposedHelpers.getObjectField(floatTask, "mDisplayContent");
            if (dc == null) return;
            XposedHelpers.callMethod(dc, "setFocusedApp", ar);
            XposedHelpers.callMethod(XposedHelpers.getObjectField(dc, "mWmService"),
                    "updateFocusedWindowLocked", 0, true);
        } catch (Throwable t) {
            log("!!! edge_hang focusTaskBehind failed: " + t);
        }
    }

    // 小窗下方那个可聚焦任务(原厂 getTaskUnderFlexible: 排除小窗与画中画)的顶层 Activity。
    private static Object findBehindActivity(Object floatTask) {
        try {
            if (floatTask == null) return null;
            Object ftc = getFlexibleTaskController();
            if (ftc == null) return null;
            // getTaskUnderFlexible 是 private, XposedHelpers 反射可调用。
            Object behind = XposedHelpers.callMethod(ftc, "getTaskUnderFlexible", floatTask);
            if (behind == null) return null;
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(behind, "isTopActivityFocusable"))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(behind, "isVisible"))) {
                return null;
            }
            return XposedHelpers.callMethod(behind, "getTopNonFinishingActivity");
        } catch (Throwable t) {
            log("!!! edge_hang findBehindActivity failed: " + t);
            return null;
        }
    }

    // FlexibleWindowManagerService 是单例, FlexibleTaskController 实例由它持有(attach 之后才非空)。
    private static Object getFlexibleTaskController() {
        try {
            if (sFlexibleTaskController != null) return sFlexibleTaskController;
            if (sFlexibleWindowService == null) return null;
            Object fwms = XposedHelpers.callStaticMethod(sFlexibleWindowService, "getInstance",
                    new Object[] { null });
            if (fwms == null) return null;
            Object ftc = XposedHelpers.callMethod(fwms, "getFlexibleTaskController");
            if (ftc != null) sFlexibleTaskController = ftc;
            return ftc;
        } catch (Throwable t) {
            log("!!! edge_hang getFlexibleTaskController failed: " + t);
            return null;
        }
    }

    // 贴边挂机静音: 走系统多应用音量通道 PlaybackActivityMonitorExtImpl#setVolumeForUid,
    // isEternalSet=true 会写入 mMusicVolumeMap, 当前及此后新建的播放器都按该值生效。
    private static final Object MUTE_LOCK = new Object();
    private static Class<?> sFloatHandleController;   // 兜底: 判断挂机任务是否仍在浮窗列表
    private static Class<?> sFlexibleWindowService;   // FlexibleWindowManagerService
    private static volatile Object sFlexibleTaskController;   // 单例持有的控制器实例
    // 贴边挂机中的任务: 真实窗口已隐藏、任务仍留在台前。
    private static final Set<Integer> sHungTaskIds =
            Collections.synchronizedSet(new HashSet<Integer>());
    // taskId -> Task(弱引用, 仅供解锁后纠正焦点时取对象用)。
    private static final Map<Integer, WeakReference<Object>> sHungTasks =
            Collections.synchronizedMap(new HashMap<Integer, WeakReference<Object>>());

    // prepareSurfaces 的 before/after 之间传递 Task.mLastSurfaceShowing 的附加字段键。
    private static final String LAST_SHOWING = "colorosModLastSurfaceShowing";
    private static volatile Object sPamExt;           // PlaybackActivityMonitorExtImpl 实例
    private static int sMutedTaskId = -1;
    private static int sMutedUid = -1;
    private static float sMutedPrevGain = 1.0f;
    private static Handler sHandler;

    public static void hookFloatWindowEdgeHangMute(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 捕获多应用音量实现实例: 其构造早于任何播放, 拿一次即可。
        try {
            XposedHelpers.findAndHookConstructor(
                    "com.android.server.audio.PlaybackActivityMonitorExtImpl", lpparam.classLoader,
                    Object.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sPamExt = param.thisObject;
                        }
                    });
        } catch (Throwable t) {
            log("!!! edge_hang_mute: capture PlaybackActivityMonitorExtImpl failed: " + t);
        }

        // 焦点中枢: 一参 setFocusedTask(int) 内部也转调这个两参版本, 挂机小窗回前台必过此。
        try {
            final Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.ActivityTaskManagerService", lpparam.classLoader,
                    "setFocusedTask", int.class, activityRecord,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_MUTE_ENABLED, false)) return;
                            } catch (Throwable ignored) {
                                return;
                            }
                            final int focusedTaskId = ((Number) param.args[0]).intValue();
                            postAsync(new Runnable() {
                                @Override
                                public void run() {
                                    restoreMuteIfNeeded(focusedTaskId);
                                }
                            });
                        }
                    });
            log(">>> matched android (system_server): float_window_edge_hang_mute");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang_mute system_server hook failed: " + t);
        }
    }

    // 贴边成浮窗时静音: 先记下原音量再置 0, 回到前台按原值恢复。
    private static void muteFloatTask(final int taskId, Object task) {
        final int uid = taskUid(task);
        if (uid <= 0) return;
        postAsync(new Runnable() {
            @Override
            public void run() {
                synchronized (MUTE_LOCK) {
                    if (sPamExt == null) return;
                    // 同一应用重复贴边: 已静音, 保留最初记录的原始音量, 勿把 0 记成原值。
                    if (sMutedUid == uid) {
                        sMutedTaskId = taskId;
                        return;
                    }
                    if (sMutedUid >= 0) applyAppVolume(sMutedUid, sMutedPrevGain);
                    float prev = applyAppVolume(uid, 0.0f);
                    sMutedTaskId = taskId;
                    sMutedUid = uid;
                    sMutedPrevGain = prev;
                }
            }
        });
    }

    // 挂机小窗回到前台, 或已被关闭(不在浮窗列表), 就恢复原音量, 避免长期静音。
    private static void restoreMuteIfNeeded(int focusedTaskId) {
        synchronized (MUTE_LOCK) {
            if (sMutedUid < 0) return;
            if (focusedTaskId >= 0 && focusedTaskId == sMutedTaskId) {
                restoreMutedLocked();
            } else if (!isTaskInFloatingList(sMutedTaskId)) {
                restoreMutedLocked();
            }
        }
    }

    private static void restoreMutedLocked() {
        final int uid = sMutedUid;
        final float gain = sMutedPrevGain;
        sMutedTaskId = -1;
        sMutedUid = -1;
        sMutedPrevGain = 1.0f;
        applyAppVolume(uid, gain);
    }

    // 经系统多应用音量通道设置某 uid 的应用音量, 返回设置前的音量(不在表中即 1.0)。
    @SuppressWarnings("unchecked")
    private static float applyAppVolume(int uid, float gain) {
        final Object pamExt = sPamExt;
        if (pamExt == null || uid <= 0) return 1.0f;
        try {
            final String pkg = pkgNameForUid(pamExt, uid);
            if (pkg == null) return 1.0f;
            float prev = 1.0f;
            final Map<String, Float> map =
                    (Map<String, Float>) XposedHelpers.getObjectField(pamExt, "mMusicVolumeMap");
            if (map != null) {
                synchronized (map) {
                    Float v = map.get(pkg);
                    if (v != null) prev = v;
                }
            }
            XposedHelpers.callMethod(pamExt, "setVolumeForUid", gain, uid, pkg, true);
            log(">>> edge_hang_mute: " + pkg + " gain " + prev + " -> " + gain);
            return prev;
        } catch (Throwable t) {
            log("!!! edge_hang_mute applyAppVolume failed: " + t);
            return 1.0f;
        }
    }

    // 音量表的键须与系统一致: PackageManager#getNameForUid(uid)。
    // ExtImpl 的 mContext 有时为 null(实测 NPE), 故按多条路径依次取 PackageManager。
    private static String pkgNameForUid(Object pamExt, int uid) {
        // 1) ExtImpl.mContext
        try {
            Object ctx = XposedHelpers.getObjectField(pamExt, "mContext");
            String pkg = nameForUidFromContext(ctx, uid);
            if (pkg != null) return pkg;
        } catch (Throwable ignored) {
            // 继续尝试下一条路径。
        }

        // 2) ExtImpl.mPam -> getWrapper().getContext()
        try {
            Object pam = XposedHelpers.getObjectField(pamExt, "mPam");
            if (pam != null) {
                Object wrapper = XposedHelpers.callMethod(pam, "getWrapper");
                Object ctx = XposedHelpers.callMethod(wrapper, "getContext");
                String pkg = nameForUidFromContext(ctx, uid);
                if (pkg != null) return pkg;
            }
        } catch (Throwable ignored) {
            // 继续尝试下一条路径。
        }

        // 3) system_server 的 Application
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            String pkg = nameForUidFromContext(app, uid);
            if (pkg != null) return pkg;
        } catch (Throwable ignored) {
            // 继续尝试下一条路径。
        }

        // 4) 直接走 IPackageManager(AppGlobals), 不依赖任何 Context。
        try {
            Object ipm = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.AppGlobals", null),
                    "getPackageManager");
            if (ipm != null) {
                String pkg = (String) XposedHelpers.callMethod(ipm, "getNameForUid", uid);
                if (pkg != null && !pkg.isEmpty()) return pkg;
            }
        } catch (Throwable ignored) {
            // 全部失败。
        }

        log("!!! edge_hang_mute: cannot resolve package for uid=" + uid);
        return null;
    }

    private static String nameForUidFromContext(Object ctx, int uid) {
        if (ctx == null) return null;
        try {
            Object pm = XposedHelpers.callMethod(ctx, "getPackageManager");
            if (pm == null) return null;
            String pkg = (String) XposedHelpers.callMethod(pm, "getNameForUid", uid);
            return (pkg == null || pkg.isEmpty()) ? null : pkg;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int taskUid(Object task) {
        try {
            int uid = XposedHelpers.getIntField(task, "effectiveUid");
            if (uid > 0) return uid;
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.getIntField(task, "mCallingUid");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isTaskInFloatingList(int taskId) {
        try {
            if (sFloatHandleController == null) return true;
            Object fhc = XposedHelpers.callStaticMethod(sFloatHandleController, "getInstance");
            return Boolean.TRUE.equals(XposedHelpers.callMethod(fhc, "isInFloatingList", taskId));
        } catch (Throwable t) {
            return true;
        }
    }

    // 音量与 PM 查询不能在 WM 全局锁内做, 一律抛到主线程延后执行。
    private static void postAsync(Runnable r) {
        try {
            if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
            sHandler.post(r);
        } catch (Throwable t) {
            log("!!! edge_hang_mute post failed: " + t);
        }
    }

    private static void postAsyncDelayed(Runnable r, long delayMs) {
        try {
            if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
            sHandler.postDelayed(r, delayMs);
        } catch (Throwable t) {
            log("!!! edge_hang_mute postDelayed failed: " + t);
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

    // 小窗尺寸与贴边: 1) 缩到最小(mini)时贴靠边缘只留 FLOAT_WINDOW_SIDE_MARGIN_DP;
    // 2) 放大的最大宽度 = 屏幕宽度 - 2 * FLOAT_WINDOW_SIDE_MARGIN_DP。
    public static void hookFloatWindowSizeLimits(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> zoomDisplayCls = XposedHelpers.findClass(
                    "com.android.server.wm.OplusZoomDisplay", lpparam.classLoader);

            // 1) mini 窗口贴边时与屏幕边缘的间距(小屏竖屏默认 20dp)。
            //    getDefaultMulitMiniRect / getDefaultMiniRect 用它算贴靠后的 right = 屏宽 - limit,
            //    getLimitBoundInMiniFlexibleTask 也用它当拖动边界, 所以左右两侧统一留出该间距。
            XposedHelpers.findAndHookMethod(zoomDisplayCls, "getLeftRightLimitInMini",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!edgeSizeOptimizeEnabled()) return;
                                param.setResult(Integer.valueOf(sideMarginPx()));
                            } catch (Throwable t) {
                                log("!!! float_window_size getLeftRightLimitInMini failed: " + t);
                            }
                        }
                    });

            // 2) 各屏幕档位的可视宽度上限: 从源头把每个比例的 mMaxVisualWidth 改成 屏幕宽度 - 左右边距。
            //    该字段是"该 ratio 下最大可视宽度"的唯一来源, getCurrentRatioMaxVisualWidth(dp) 与
            //    getCurrRatioMaxFlexibleScale(= mMaxVisualWidth / policyReferValue) 都读它,
            //    改字段二者自动同步, 不会出现 bounds 能到屏宽而 scale 被旧上限拉回去的错位。
            final String[] paramClsNames = {
                    "com.android.server.wm.OplusZoomSmallScreenParameter",
                    "com.android.server.wm.OplusZoomMiddleScreenParameter",
                    "com.android.server.wm.OplusZoomLargeScreenParameter",
            };
            for (final String clsName : paramClsNames) {
                final Class<?> cls;
                try {
                    cls = XposedHelpers.findClass(clsName, lpparam.classLoader);
                } catch (Throwable t) {
                    continue;
                }
                XposedHelpers.findAndHookMethod(cls, "computeAllRatioData",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!edgeSizeOptimizeEnabled()) return;
                                    widenMaxVisualWidth(param.thisObject);
                                } catch (Throwable t) {
                                    log("!!! float_window_size computeAllRatioData failed: " + t);
                                }
                            }
                        });
            }

            // 3) 自由比例应用(支持任意比例): 系统把最大宽度压到 屏宽 * 0.96, 放开到 屏宽 - 左右边距。
            XposedHelpers.findAndHookMethod(zoomDisplayCls, "getFreeRatioSupportMaxWidthProp",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!edgeSizeOptimizeEnabled()) return;
                                float prop = freeRatioMaxWidthProp();
                                if (prop > 0f) param.setResult(Float.valueOf(prop));
                            } catch (Throwable t) {
                                log("!!! float_window_size getFreeRatioSupportMaxWidthProp failed: " + t);
                            }
                        }
                    });

            // 4) 打开/重算小窗尺寸时的 clamp: 系统会预留左右各 18dp 安全边距, 临时改成 2dp。
            //    只在方法执行期间改, 不动多窗排布用到的其它间距计算。
            final Class<?> policyCls = XposedHelpers.findClass(
                    "com.android.server.wm.OplusFlexibleTaskLayoutPolicy", lpparam.classLoader);
            final Class<?> taskCls = XposedHelpers.findClass(
                    "com.android.server.wm.Task", lpparam.classLoader);
            final Class<?> ftiCls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskInfo", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(policyCls, "regulateSizeIfOverScreen",
                    taskCls, ftiCls, float.class, float.class,
                    android.graphics.Point.class, android.graphics.Point.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!edgeSizeOptimizeEnabled()) {
                                sSecurityMarginBackup.remove();
                                return;
                            }
                            sSecurityMarginBackup.set(overrideSecurityMargin(param.thisObject));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            restoreSecurityMargin(param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(policyCls, "getTaskRealSize",
                    boolean.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!edgeSizeOptimizeEnabled()) {
                                sSecurityMarginBackup.remove();
                                return;
                            }
                            sSecurityMarginBackup.set(overrideSecurityMargin(param.thisObject));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            restoreSecurityMargin(param.thisObject);
                        }
                    });

            log(">>> matched android (system_server): float_window_size_limits");
        } catch (Throwable t) {
            log("!!! float_window_size_limits system_server hook failed: " + t);
        }
    }

    // 用线程局部保存, 避免不同线程/嵌套调用互相覆盖。
    private static final ThreadLocal<Integer> sSecurityMarginBackup = new ThreadLocal<>();

    private static Integer overrideSecurityMargin(Object policy) {
        if (policy == null) return null;
        try {
            int old = XposedHelpers.getIntField(policy, "mSecurityMarginRight");
            XposedHelpers.setIntField(policy, "mSecurityMarginRight", sideMarginPx());
            return Integer.valueOf(old);
        } catch (Throwable t) {
            log("!!! float_window_size overrideSecurityMargin failed: " + t);
            return null;
        }
    }

    private static void restoreSecurityMargin(Object policy) {
        Integer backup = sSecurityMarginBackup.get();
        sSecurityMarginBackup.remove();
        if (policy == null || backup == null) return;
        try {
            XposedHelpers.setIntField(policy, "mSecurityMarginRight", backup.intValue());
        } catch (Throwable t) {
            log("!!! float_window_size restoreSecurityMargin failed: " + t);
        }
    }

    private static boolean edgeSizeOptimizeEnabled() {
        return readBool(KEY_FLOAT_WINDOW_EDGE_SIZE_OPTIMIZE_ENABLED, false);
    }

    private static int sideMarginPx() {
        float density = Resources.getSystem().getDisplayMetrics().density;
        if (density <= 0f) return 0;
        return Math.round(FLOAT_WINDOW_SIDE_MARGIN_DP * density);
    }

    // 自由比例的最大宽度 = 屏幕宽度 * prop, 反解出保留左右边距后的 prop。
    private static float freeRatioMaxWidthProp() {
        android.util.DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        int widthPx = Math.min(dm.widthPixels, dm.heightPixels);
        if (widthPx <= 0) return 0f;
        int margin = sideMarginPx() * 2;
        if (margin >= widthPx) return 0f;
        return (widthPx - margin) * 1.0f / widthPx;
    }

    // 把每个比例的 mMaxVisualWidth(dp) 改写成 屏幕宽度 - 左右边距:
    // 竖屏场景(mIsPort)用屏幕短边, 横屏场景用长边, 这样两种旋转下都是"当前屏幕宽度"。
    @SuppressWarnings("unchecked")
    private static void widenMaxVisualWidth(Object parameterStore) {
        if (parameterStore == null) return;
        Object raw;
        try {
            raw = XposedHelpers.getObjectField(parameterStore, "mRatioDataList");
        } catch (Throwable t) {
            return;
        }
        if (!(raw instanceof java.util.List)) return;
        java.util.List<Object> list = (java.util.List<Object>) raw;
        if (list.isEmpty()) return;

        float density = 0f;
        int shortSide = 0, longSide = 0;
        try {
            Object dc = XposedHelpers.getObjectField(parameterStore, "mDisplayContent");
            Object di = XposedHelpers.callMethod(dc, "getDisplayInfo");
            int w = XposedHelpers.getIntField(di, "logicalWidth");
            int h = XposedHelpers.getIntField(di, "logicalHeight");
            Object dm = XposedHelpers.callMethod(dc, "getDisplayMetrics");
            density = XposedHelpers.getFloatField(dm, "density");
            shortSide = Math.min(w, h);
            longSide = Math.max(w, h);
        } catch (Throwable ignored) {
            // 回退到系统 metrics。
        }
        if (density <= 0f || shortSide <= 0 || longSide <= 0) {
            android.util.DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            density = dm.density;
            shortSide = Math.min(dm.widthPixels, dm.heightPixels);
            longSide = Math.max(dm.widthPixels, dm.heightPixels);
        }
        if (density <= 0f || shortSide <= 0 || longSide <= 0) return;

        final int marginDp = Math.round(2 * FLOAT_WINDOW_SIDE_MARGIN_DP);
        final int portWidthDp = Math.round(shortSide / density) - marginDp;
        final int landWidthDp = Math.round(longSide / density) - marginDp;
        if (portWidthDp <= 0 || landWidthDp <= 0) return;
        for (Object data : list) {
            if (data == null) continue;
            boolean isPort;
            try {
                isPort = XposedHelpers.getBooleanField(data, "mIsPort");
            } catch (Throwable t) {
                continue;
            }
            int newWidth = isPort ? portWidthDp : landWidthDp;
            try {
                XposedHelpers.setIntField(data, "mMaxVisualWidth", newWidth);
            } catch (Throwable t) {
                log("!!! float_window_size widenMaxVisualWidth set field failed: " + t);
            }
        }
    }
}
