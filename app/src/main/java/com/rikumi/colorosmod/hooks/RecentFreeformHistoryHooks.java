package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collection;
import com.rikumi.colorosmod.xposed.XposedBridge;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecentFreeformHistoryHooks {
    private static final String CLOSED = "colorosmod_closed_freeform";
    private static final Object RECORD_LOCK = new Object();
    private static Object generation = new Object();
    private static boolean lastEnabled;
    private static boolean lastHideActive;
    private static volatile WeakReference<Object> taskNotifications = new WeakReference<>(null);

    public static void onSettingsSnapshotPublished(Map<String, Integer> settings) {
        // 即使关闭后未查询多任务便再次开启，旧记录也必须失效。
        boolean next = Integer.valueOf(1).equals(settings.get(KEY_RECENTS_HIDE_CLOSED_FREEFORM_ENABLED));
        boolean changed;
        synchronized (RECORD_LOCK) {
            if (!next) generation = new Object();
            boolean hideActive = Integer.valueOf(1).equals(settings.get(KEY_RECENTS_HIDE_FREEFORM_ENABLED));
            changed = lastEnabled != next || lastHideActive != hideActive;
            lastHideActive = hideActive;
            lastEnabled = next;
        }
        if (changed) notifyRecentTasksChanged();
    }

    public static void cleanupForHotReload() {
        synchronized (RECORD_LOCK) { generation = new Object(); }
        notifyRecentTasksChanged();
    }

    private static void notifyRecentTasksChanged() {
        Object controller = taskNotifications.get();
        if (controller == null) return;
        try {
            // 原生事件经 Shell RecentTasksController 使 Launcher RecentTasksList 缓存失效。
            XposedHelpers.callMethod(controller, "notifyTaskListUpdated");
        } catch (Throwable t) { log("closed freeform recents notification: " + t); }
    }

    private static void recordClosed(Object task) {
        synchronized (RECORD_LOCK) {
            if (readBoolCached(KEY_RECENTS_HIDE_CLOSED_FREEFORM_ENABLED, false)) {
                XposedHelpers.setAdditionalInstanceField(task, CLOSED, generation);
            } else XposedHelpers.removeAdditionalInstanceField(task, CLOSED);
        }
    }

    private static boolean floating(Object task) {
        Object ext = XposedHelpers.callMethod(XposedHelpers.callMethod(task, "getWrapper"), "getExtImpl");
        return (Integer) XposedHelpers.callMethod(ext, "getLaunchScenario") == 1
                && !(Boolean) XposedHelpers.callMethod(ext, "isTaskEmbedded")
                && (Boolean) XposedHelpers.callMethod(ext, "isHasCaption");
    }

    private static boolean clearClosed(Object task) {
        if (task == null || XposedHelpers.getAdditionalInstanceField(task, CLOSED) == null) return false;
        XposedHelpers.removeAdditionalInstanceField(task, CLOSED);
        return true;
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        cleanupForHotReload();
        try {
            // 6=关闭/退到后台；4=全屏；7=分屏。使用实际策略，避免把分屏误当关闭。
            for (String strategy : new String[]{"ExitFlexibleTaskToBackStrategy",
                    "ExitFlexibleTaskToFullStrategy", "ExitFlexibleTaskToSplitStrategy"}) {
                try {
                    XposedHelpers.findAndHookMethod("com.android.server.wm." + strategy,
                            lp.classLoader, "handleEvent", new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam p) {
                                    Object task = XposedHelpers.getObjectField(p.thisObject, "mTask");
                                    if (task == null) return;
                                    int exitTo = (Integer) XposedHelpers.callMethod(p.thisObject, "getExitTo");
                                    if (exitTo == 6 && floating(task)) {
                                        recordClosed(task);
                                    } else {
                                        XposedHelpers.removeAdditionalInstanceField(task, CLOSED);
                                    }
                                }
                            });
                } catch (Throwable t) { log("closed freeform strategy unavailable: " + strategy + ": " + t); }
            }
        } catch (Throwable t) { log("closed freeform strategies unavailable: " + t); }
        // 部分固件/关闭入口直接调用 controller，不经过 ExitFlexibleTaskToBackStrategy。
        try {
            Class<?> taskClass = XposedHelpers.findClass("com.android.server.wm.Task", lp.classLoader);
            XposedHelpers.findAndHookMethod("com.android.server.wm.FlexibleTaskController", lp.classLoader,
                    "exitFlexibleTask", taskClass, boolean.class, int.class, int.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Object task = p.args[0];
                            if (task != null && (Integer) p.args[2] == 0 && floating(task)) {
                                p.setObjectExtra("previousClosed", XposedHelpers.getAdditionalInstanceField(task, CLOSED));
                                p.setObjectExtra("recordedClosed", true);
                                recordClosed(task);
                            }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!Boolean.TRUE.equals(p.getObjectExtra("recordedClosed"))) return;
                            if (p.hasThrowable()) {
                                Object previous = p.getObjectExtra("previousClosed");
                                if (previous == null) XposedHelpers.removeAdditionalInstanceField(p.args[0], CLOSED);
                                else XposedHelpers.setAdditionalInstanceField(p.args[0], CLOSED, previous);
                            } else notifyRecentTasksChanged();
                        }
                    });
        } catch (Throwable t) { log("closed freeform controller unavailable: " + t); }
        try {
            Class<?> taskClass = XposedHelpers.findClass("com.android.server.wm.Task", lp.classLoader);
            XposedBridge.hookAllMethods(taskClass, "moveToFront", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    // 前移发生时 OEM 窗口模式可能尚未复位，不能用旧模式或 reason 白名单判断重新打开。
                    Object task = p.args.length > 1 && p.args[1] != null ? p.args[1] : p.thisObject;
                    boolean changed = clearClosed(task);
                    changed |= clearClosed(XposedHelpers.callMethod(task, "getTopMostTask"));
                    if (changed) notifyRecentTasksChanged();
                }
            });
            XposedHelpers.findAndHookMethod(taskClass, "onAppFocusChanged", boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable() && Boolean.TRUE.equals(p.args[0]) && clearClosed(p.thisObject))
                        notifyRecentTasksChanged();
                }
            });
        } catch (Throwable t) { log("closed freeform reopen hook unavailable: " + t); }
        try {
            // 任务复用/Activity 直接恢复不一定经过 moveToFront；RESUMED 是当前任务的真实生命周期。
            Class<?> fragment = XposedHelpers.findClass("com.android.server.wm.TaskFragment", lp.classLoader);
            XposedBridge.hookAllMethods(fragment, "onActivityStateChanged", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 2 || !"RESUMED".equals(String.valueOf(p.args[1])) || p.args[0] == null) return;
                    Object task = XposedHelpers.callMethod(p.args[0], "getTask");
                    p.setObjectExtra("clearedClosedFreeform", clearClosed(task));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(p.getObjectExtra("clearedClosedFreeform"))) notifyRecentTasksChanged();
                }
            });
        } catch (Throwable t) { log("closed freeform resumed hook unavailable: " + t); }
        try {
            Class<?> taskClass = XposedHelpers.findClass("com.android.server.wm.Task", lp.classLoader);
            XposedHelpers.findAndHookMethod("com.android.server.wm.RecentTasks", lp.classLoader,
                    "remove", taskClass, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args[0] != null) XposedHelpers.removeAdditionalInstanceField(p.args[0], CLOSED);
                        }
                    });
        } catch (Throwable t) { log("closed freeform removal hook unavailable: " + t); }
        try {
            // 参数数量随固件变化，过滤只依赖返回的 RecentTaskInfo 列表。
            Class<?> recentTasks = XposedHelpers.findClass("com.android.server.wm.RecentTasks", lp.classLoader);
            if (XposedBridge.hookAllMethods(recentTasks, "getRecentTasksImpl",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (!(p.getResult() instanceof List)) return;
                                taskNotifications = new WeakReference<>(XposedHelpers.getObjectField(
                                        p.thisObject, "mTaskNotificationController"));
                                Object current;
                                synchronized (RECORD_LOCK) {
                                    current = readBoolCached(KEY_RECENTS_HIDE_CLOSED_FREEFORM_ENABLED, false)
                                            ? generation : null;
                                }
                                Collection<?> tasks = (Collection<?>) XposedHelpers.callMethod(p.thisObject, "getRawTasks");
                                Set<String> hidden = new HashSet<>();
                                for (Object task : tasks) {
                                    Object record = XposedHelpers.getAdditionalInstanceField(task, CLOSED);
                                    if (current == null || record != current) {
                                        XposedHelpers.removeAdditionalInstanceField(task, CLOSED);
                                    } else {
                                        hidden.add(XposedHelpers.getIntField(task, "mUserId") + ":"
                                                + XposedHelpers.getIntField(task, "mTaskId"));
                                    }
                                }
                                // 只修改返回列表，不触碰 RecentTasks 的保留/裁剪及 Task 本体 Intent。
                                synchronized (RECORD_LOCK) {
                                    // 设置发布可与 WM 查询并发；关闭或换代后不再过滤本次结果。
                                    if (current != null && current == generation
                                            && readBoolCached(KEY_RECENTS_HIDE_CLOSED_FREEFORM_ENABLED, false)) {
                                        List<?> result = new ArrayList<>((List<?>) p.getResult());
                                        result.removeIf(info -> hidden.contains(
                                                XposedHelpers.getIntField(info, "userId") + ":"
                                                        + XposedHelpers.getIntField(info, "taskId")));
                                        p.setResult(result);
                                    }
                                }
                            } catch (Throwable t) { log("closed freeform recents filter: " + t); }
                        }
                    }).isEmpty()) log("closed freeform recents: getRecentTasksImpl unavailable");
        } catch (Throwable t) { log("HOOK FAIL closed freeform recents: " + t); }
    }
}
