package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecentFreeformHistoryHooks {
    private static final String CLOSED = "colorosmod_closed_freeform";
    private static final Object RECORD_LOCK = new Object();
    private static Object generation = new Object();
    private static boolean lastEnabled;
    private static volatile WeakReference<Object> taskNotifications = new WeakReference<>(null);

    public static void onSettingsSnapshotPublished(Map<String, Integer> settings) {
        // 即使关闭后未查询多任务便再次开启，旧记录也必须失效。
        boolean next = Integer.valueOf(1).equals(settings.get(KEY_RECENTS_HIDE_CLOSED_FREEFORM_ENABLED));
        boolean changed;
        synchronized (RECORD_LOCK) {
            if (!next) generation = new Object();
            changed = lastEnabled != next;
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

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        cleanupForHotReload();
        try {
            Class<?> taskClass = XposedHelpers.findClass("com.android.server.wm.Task", lp.classLoader);
            // 6=关闭/退到后台；4=全屏；7=分屏。使用实际策略，避免把分屏误当关闭。
            for (String strategy : new String[]{"ExitFlexibleTaskToBackStrategy",
                    "ExitFlexibleTaskToFullStrategy", "ExitFlexibleTaskToSplitStrategy"}) {
                XposedHelpers.findAndHookMethod("com.android.server.wm." + strategy,
                        lp.classLoader, "handleEvent", new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                Object task = XposedHelpers.getObjectField(p.thisObject, "mTask");
                                if (task == null) return;
                                int exitTo = (Integer) XposedHelpers.callMethod(p.thisObject, "getExitTo");
                                Object ext = XposedHelpers.callMethod(XposedHelpers.callMethod(task, "getWrapper"), "getExtImpl");
                                boolean floating = (Integer) XposedHelpers.callMethod(ext, "getLaunchScenario") == 1
                                        && !(Boolean) XposedHelpers.callMethod(ext, "isTaskEmbedded")
                                        && (Boolean) XposedHelpers.callMethod(ext, "isHasCaption");
                                if (exitTo == 6 && floating) {
                                    recordClosed(task);
                                } else {
                                    XposedHelpers.removeAdditionalInstanceField(task, CLOSED);
                                }
                            }
                        });
            }
            XposedHelpers.findAndHookMethod(taskClass, "moveToFront", String.class, taskClass,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String reason = (String) p.args[0];
                            // ActivityStarter 的真实启动路径；关闭小窗的 moveToBack 不解除标记。
                            if (!"reuseOrNewTask".equals(reason)
                                    && !"startActivityInner".equals(reason)
                                    && !"intentActivityFound".equals(reason)) return;
                            Object task = p.args[1] != null ? p.args[1] : p.thisObject;
                            task = XposedHelpers.callMethod(task, "getTopMostTask");
                            if (task == null) return;
                            Object ext = XposedHelpers.callMethod(XposedHelpers.callMethod(task, "getWrapper"), "getExtImpl");
                            if ((Integer) XposedHelpers.callMethod(task, "getWindowingMode") == 1
                                    && (Integer) XposedHelpers.callMethod(ext, "getLaunchScenario") != 1) {
                                XposedHelpers.removeAdditionalInstanceField(task, CLOSED);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.server.wm.RecentTasks", lp.classLoader,
                    "remove", taskClass, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args[0] != null) XposedHelpers.removeAdditionalInstanceField(p.args[0], CLOSED);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.server.wm.RecentTasks", lp.classLoader,
                    "getRecentTasksImpl", int.class, int.class, boolean.class, int.class, int.class,
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
                                List<?> tasks = (List<?>) XposedHelpers.callMethod(p.thisObject, "getRawTasks");
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
                                        ((List<?>) p.getResult()).removeIf(info -> hidden.contains(
                                                XposedHelpers.getIntField(info, "userId") + ":"
                                                        + XposedHelpers.getIntField(info, "taskId")));
                                    }
                                }
                            } catch (Throwable t) { log("closed freeform recents filter: " + t); }
                        }
                    });
        } catch (Throwable t) { log("HOOK FAIL closed freeform recents: " + t); }
    }
}
