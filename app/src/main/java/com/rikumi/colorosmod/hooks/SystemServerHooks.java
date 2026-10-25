package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowInsets;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import java.util.Set;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.TextSwitcher;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

public final class SystemServerHooks {
    public static void hookFloatWindowCount(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.FlexibleWindowManagerService", lp.classLoader,
                    "getMaxWinNum", int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {

                            if ((Integer) p.args[0] == 1
                                    && readBoolCached(KEY_FLOAT_WINDOW_COUNT_ENABLED, false)) {
                                p.setResult(Math.max(1, Math.min(10,
                                        readIntCached(KEY_FLOAT_WINDOW_COUNT, 3))));
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.FlexibleWindowManagerService", lp.classLoader,
                    "getTasksNumber", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!readBoolCached(KEY_FLOAT_WINDOW_COUNT_ENABLED, false)) return;
                            // 查询窗口后方的全屏任务/桌面时，仍需覆盖增加的小窗数量。
                            int count = Math.max(1, Math.min(10, readIntCached(KEY_FLOAT_WINDOW_COUNT, 3)));
                            p.setResult(Math.max((Integer) p.getResult(), count + 3));
                        }
                    });
        } catch (Throwable t) { log("float window count hook failed: " + t); }
    }

    private static final long STATUSBAR_OVERLAY_EVENT_DEBOUNCE_MS = 250L;
    private static Handler sStatusBarOverlayEventHandler;
    private static Context sStatusBarOverlayEventContext;
    private static final Runnable sStatusBarOverlayEventRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Context context = sStatusBarOverlayEventContext;
                if (context != null
                        && readBool(KEY_STATUSBAR_LYRIC_AVOID_THIRD_PARTY_ENABLED, false)) {
                    Settings.Global.putLong(context.getContentResolver(),
                            KEY_STATUSBAR_OVERLAY_EVENT, SystemClock.uptimeMillis());
                }
            } catch (Throwable t) {
                log("statusbar_lyric overlay event publish error: "
                        + Log.getStackTraceString(t));
            }
        }
    };

    public static void hookCompactCanvasCaptionInsets(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> windowStateExt = XposedHelpers.findClass(
                    "com.android.server.wm.WindowStateExtImpl", lpparam.classLoader);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(windowStateExt,
                    "adjustInsetsStateForFlexibleWindowIfNeed",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBoolCached(KEY_SHRINK_CAPTION_BAR_ENABLED, false)
                                    || param.args.length < 2 || param.getResult() == null) return;
                            Object task = XposedHelpers.callMethod(param.args[1], "getTask");
                            if (task == null) return;
                            Object taskExt = XposedHelpers.callMethod(
                                    XposedHelpers.callMethod(task, "getWrapper"), "getExtImpl");
                            int launchScenario = ((Number) XposedHelpers.callMethod(
                                    taskExt, "getLaunchScenario")).intValue();
                            if (launchScenario != 1 && launchScenario != 2) return;

                            Object stateExt = XposedHelpers.callMethod(
                                    XposedHelpers.callMethod(param.getResult(), "getWrapper"),
                                    "getExtImpl");
                            Object source = XposedHelpers.callMethod(stateExt, "peekDefaultSource",
                                    WindowInsets.Type.statusBars());
                            if (source == null) return;
                            Object adjustedSource = XposedHelpers.newInstance(
                                    source.getClass(), source);
                            Rect originalFrame = new Rect((Rect) XposedHelpers.callMethod(
                                    adjustedSource, "getFrame"));
                            Object visibleFrame = XposedHelpers.callMethod(
                                    adjustedSource, "getVisibleFrame");
                            Rect legacyVisibleFrame = visibleFrame instanceof Rect
                                    ? new Rect((Rect) visibleFrame) : originalFrame;
                            Rect frame = new Rect(originalFrame);
                            Configuration configuration = (Configuration) XposedHelpers.callMethod(
                                    task, "getConfiguration");
                            int compactHeight = Math.max(1, Math.round(
                                    COMPACT_CAPTION_BAR_HEIGHT_DP
                                            * configuration.densityDpi / 160f));
                            if (launchScenario == 1) {
                                Rect taskBounds = new Rect((Rect) XposedHelpers.callMethod(
                                        task, "getBounds"));
                                frame.set(taskBounds.left, taskBounds.top, taskBounds.right,
                                        taskBounds.top + compactHeight);

                                XposedHelpers.callMethod(adjustedSource, "setVisibleFrame",
                                        legacyVisibleFrame);
                            } else {
                                if (frame.height() <= compactHeight) return;
                                frame.bottom = frame.top + compactHeight;
                            }
                            XposedHelpers.callMethod(adjustedSource, "setFrame", frame);

                            XposedHelpers.callMethod(param.getResult(), "addSource", adjustedSource);
                        }
                    });
            if (hooks.isEmpty()) {
                throw new NoSuchMethodError(windowStateExt.getName()
                        + "#adjustInsetsStateForFlexibleWindowIfNeed");
            }
            log("HOOK OK WindowStateExtImpl status bar insets (compact canvas caption)");
        } catch (Throwable t) {
            log("HOOK FAIL compact canvas caption insets: " + Log.getStackTraceString(t));
        }
    }

    public static void hookCompactFlexibleCaptionBar(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> captionView = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskCaptionView", lpparam.classLoader);
            XC_MethodHook compactLayout = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!readBoolCached(KEY_SHRINK_CAPTION_BAR_ENABLED, false)
                            || !(param.thisObject instanceof View)) return;
                    View root = (View) param.thisObject;
                    int compactHeight = Math.max(1, Math.round(
                            COMPACT_CAPTION_BAR_HEIGHT_DP
                                    * root.getResources().getDisplayMetrics().density));
                    compactFlexibleCaptionFrame(param.thisObject,
                            "mToolbarModeFrame", compactHeight);
                    compactFlexibleCaptionFrame(param.thisObject,
                            "mSimpleModeFrame", compactHeight);
                }
            };
            XposedBridge.hookAllMethods(captionView, "initCaptionView", compactLayout);
            // 系统在缩放或切换工具栏模式时会重新写入原始 topMargin。
            XposedBridge.hookAllMethods(captionView, "scaleToolBarMode", compactLayout);

            final Class<?> captionViewBase = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleCaptionView", lpparam.classLoader);
            XposedBridge.hookAllMethods(captionViewBase, "updateTouchableRegion",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBoolCached(KEY_SHRINK_CAPTION_BAR_ENABLED, false)
                                    || param.args.length == 0
                                    || !(param.args[0] instanceof android.graphics.Region)
                                    || !(param.thisObject instanceof View)
                                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(
                                            param.thisObject, "isDefaultZoomState"))) return;
                            View root = (View) param.thisObject;
                            Object task = XposedHelpers.getObjectField(param.thisObject, "mTask");
                            Object taskExt = XposedHelpers.callMethod(
                                    XposedHelpers.callMethod(task, "getWrapper"), "getExtImpl");
                            float taskScale = ((Number) XposedHelpers.callMethod(
                                    taskExt, "getScale")).floatValue();
                            if (taskScale <= 0f) return;
                            float density = root.getResources().getDisplayMetrics().density;
                            int compactBottom = Math.max(1, Math.round(
                                    COMPACT_CAPTION_BAR_HEIGHT_DP * density / taskScale));
                            int originalBottom = Math.max(compactBottom, Math.round(
                                    40f * density / taskScale));
                            android.graphics.Region region = new android.graphics.Region(
                                    (android.graphics.Region) param.args[0]);
                            Rect bounds = region.getBounds();
                            region.op(new Rect(bounds.left, compactBottom,
                                            bounds.right, originalBottom),
                                    android.graphics.Region.Op.DIFFERENCE);
                            param.args[0] = region;
                        }
                    });

            final Class<?> touchRegionManager = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleCaptionViewTouchRegionMgr",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(touchRegionManager, "updateTouchRegion",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBoolCached(KEY_SHRINK_CAPTION_BAR_ENABLED, false)
                                    || param.args.length < 6
                                    || !(param.args[4] instanceof Number)
                                    || !(param.args[5] instanceof View)) return;
                            float taskScale = ((Number) param.args[4]).floatValue();
                            if (taskScale <= 0f) return;
                            View root = (View) param.args[5];
                            Rect region = (Rect) XposedHelpers.getObjectField(
                                    param.thisObject, "mTopHandleMoveRegion");
                            int bottom = Math.max(1, Math.round(COMPACT_CAPTION_BAR_HEIGHT_DP
                                    * root.getResources().getDisplayMetrics().density / taskScale));
                            if (region != null && region.bottom > bottom) region.bottom = bottom;
                        }
                    });

            final Class<?> controller = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskController", lpparam.classLoader);
            XposedBridge.hookAllMethods(controller, "getFlexibleTaskTopBarHeight",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBoolCached(KEY_SHRINK_CAPTION_BAR_ENABLED, false)
                                    || !(param.getResult() instanceof Number)) return;
                            int oldHeight = ((Number) param.getResult()).intValue();
                            if (oldHeight <= 0) return;
                            param.setResult(Math.max(1, Math.round(oldHeight
                                    * COMPACT_CAPTION_BAR_HEIGHT_DP / 40f)));
                        }
                    });

            final Class<?> atmsExt = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerServiceExtImpl",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(atmsExt, "preBindApplication", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 此方法在 AMS/WMS 全局锁内运行，严禁同步查询 ContentProvider。
                    if (!readBoolCached(KEY_SHRINK_CAPTION_BAR_ENABLED, false)) return;
                    for (Object arg : param.args) {
                        if (arg instanceof Bundle) {
                            // DecorViewExtImpl 否则会把小窗顶部 inset 强制恢复成 40dp。
                            ((Bundle) arg).remove("notSupportZoomFullBar");
                            break;
                        }
                    }
                }
            });

            log("HOOK OK FlexibleTaskCaptionView (compact floating caption bar)");
        } catch (Throwable t) {
            log("HOOK FAIL compact floating caption bar: " + Log.getStackTraceString(t));
        }
    }

    private static void compactFlexibleCaptionFrame(Object captionView, String fieldName,
                                                    int compactHeight) {
        Object value = XposedHelpers.getObjectField(captionView, fieldName);
        if (!(value instanceof ViewGroup)) return;
        ViewGroup frame = (ViewGroup) value;
        ViewGroup.LayoutParams layoutParams = frame.getLayoutParams();
        if (layoutParams == null) return;
        if (layoutParams.height > compactHeight) {
            layoutParams.height = compactHeight;
            frame.setLayoutParams(layoutParams);
        }
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            ViewGroup.LayoutParams childParams = child.getLayoutParams();
            if (!(childParams instanceof FrameLayout.LayoutParams)) continue;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) childParams;
            int gravity = params.gravity == -1 ? Gravity.TOP | Gravity.START : params.gravity;
            gravity = (gravity & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL;
            int height = params.height;
            int width = params.width;

            int originalHeight = height;
            int originalWidth = width;
            if (child instanceof ImageView) {
                Drawable drawable = ((ImageView) child).getDrawable();
                if (drawable != null) {
                    if (originalHeight <= 0) originalHeight = drawable.getIntrinsicHeight();
                    if (originalWidth <= 0) originalWidth = drawable.getIntrinsicWidth();
                }
            }
            if (originalHeight > compactHeight && originalHeight > 0 && originalWidth > 0) {
                height = compactHeight;
                width = Math.max(1, Math.round(originalWidth
                        * (compactHeight / (float) originalHeight)));
            }
            if (params.gravity == gravity && params.topMargin == 0
                    && params.bottomMargin == 0 && params.height == height
                    && params.width == width) continue;

            params.gravity = gravity;
            params.topMargin = 0;
            params.bottomMargin = 0;
            params.height = height;
            params.width = width;
            child.setLayoutParams(params);
        }
    }

    public static void hookStatusBarThirdPartyOverlayEvents(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> wmsClass = XposedHelpers.findClass(
                    "com.android.server.wm.WindowManagerService", lpparam.classLoader);
            final XC_MethodHook windowChanged = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    scheduleStatusBarOverlayEvent(param.thisObject);
                }
            };
            int hooked = 0;
            for (String method : new String[]{"addWindow", "removeWindow", "relayoutWindow"}) {
                int count = XposedBridge.hookAllMethods(wmsClass, method, windowChanged).size();
                hooked += count;
            }
            log("HOOK OK WindowManagerService window events: " + hooked);
        } catch (Throwable t) {
            log("HOOK FAIL WindowManagerService window events :: "
                    + Log.getStackTraceString(t));
        }
    }

    private static void scheduleStatusBarOverlayEvent(Object windowManagerService) {
        try {
            if (sStatusBarOverlayEventContext == null && windowManagerService != null) {
                Object context = XposedHelpers.getObjectField(windowManagerService, "mContext");
                if (context instanceof Context) {
                    sStatusBarOverlayEventContext = (Context) context;
                }
            }
            if (sStatusBarOverlayEventContext == null) {
                Context context = systemContext();
                if (context != null) sStatusBarOverlayEventContext = context;
            }
            if (sStatusBarOverlayEventHandler == null) {
                sStatusBarOverlayEventHandler = new Handler(Looper.getMainLooper());
            }
            sStatusBarOverlayEventHandler.removeCallbacks(sStatusBarOverlayEventRunnable);
            sStatusBarOverlayEventHandler.postDelayed(
                    sStatusBarOverlayEventRunnable, STATUSBAR_OVERLAY_EVENT_DEBOUNCE_MS);
        } catch (Throwable t) {
            log("statusbar_lyric schedule overlay event error: "
                    + Log.getStackTraceString(t));
        }
    }

    public static void hookFloatWindowEdgeHangSystemServer(final XC_LoadPackage.LoadPackageParam lpparam) {
        sEdgeHangHooksReady = false;
        boolean coreHooksReady = false;
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

            // 仅在原生贴边退出的提交期间接管，普通退后台不能因旧浮窗记录而被拦截。
            final Class<?> ftcCls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskController", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(ftcCls, "exitFlexibleTask", taskCls,
                    boolean.class, int.class, int.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setObjectExtra(EDGE_EXIT_TASK, sEdgeExitTask.get());
                            sEdgeExitTask.set(Integer.valueOf(16).equals(param.args[2])
                                    ? param.args[0] : null);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object previous = param.getObjectExtra(EDGE_EXIT_TASK);
                            if (previous == null) sEdgeExitTask.remove();
                            else sEdgeExitTask.set(previous);

                            if (param.hasThrowable() || param.args[0] == null
                                    || sHungTaskIds.isEmpty()) return;
                            try {
                                Object wm = XposedHelpers.getObjectField(param.args[0], "mWmService");
                                synchronized (XposedHelpers.getObjectField(wm, "mGlobalLock")) {
                                    if (isActiveHungTask(param.args[0])) focusTaskBehind(param.args[0]);
                                }
                            } catch (Throwable t) {
                                log("!!! edge_hang commit focus failed: " + t);
                            }
                        }
                    });

            final Class<?> windowCls = XposedHelpers.findClass(
                    "com.android.server.wm.WindowState", lpparam.classLoader);
            XC_MethodHook rejectHungWindowInput = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sHungTaskIds.isEmpty()) return;
                    try {
                        if (isActiveHungTask(XposedHelpers.callMethod(param.thisObject, "getTask"))) {
                            param.setResult(false);
                        }
                    } catch (Throwable t) {
                        log("!!! edge_hang input eligibility failed: " + t);
                    }
                }
            };

            XposedHelpers.findAndHookMethod(windowCls, "canReceiveKeys", boolean.class,
                    rejectHungWindowInput);
            XposedHelpers.findAndHookMethod(windowCls, "canReceiveTouchInput", rejectHungWindowInput);

            final Class<?> arCls = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(arCls, "addToStopping", boolean.class, boolean.class,
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {

                            if (!"startToFloatAnimation".equals(param.args[2])) return;
                            try {
                                Object task = XposedHelpers.callMethod(param.thisObject, "getTask");
                                if (task == sEdgeExitTask.get() && isActiveHungTask(task)) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                log("!!! edge_hang stop eligibility failed: " + t);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(taskExtImplCls, "moveTaskToBackForPanorama",
                    taskCls, boolean.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEdgeHangHooksReady
                                        || !readBoolCached(KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED, false)) return;
                                final Object task = param.args[0];
                                if (task == null || task != sEdgeExitTask.get()) return;
                                final int taskId = XposedHelpers.getIntField(task, "mTaskId");

                                // 只介入"贴边成浮窗"一路: 此时 addFloatHandle 已把任务加入浮窗列表。
                                Object fhc = XposedHelpers.callStaticMethod(fhcCls, "getInstance");
                                if (!Boolean.TRUE.equals(
                                        XposedHelpers.callMethod(fhc, "isInFloatingList", taskId))) {
                                    return;
                                }

                                // 先登记再计算焦点，使本次计算就排除隐藏小窗的输入窗口。
                                sHungTaskIds.add(Integer.valueOf(taskId));
                                sHungTasks.put(Integer.valueOf(taskId), new WeakReference<Object>(task));
                                focusTaskBehind(task);

                                // 可选: 贴边挂机静音, 回到前台时恢复。
                                if (readBoolCached(KEY_FLOAT_WINDOW_EDGE_HANG_MUTE_ENABLED, false)) {
                                    muteFloatTask(taskId, task);
                                }

                                // 跳过 Task.moveTaskToBack: 任务保持在台前, 应用继续挂机。
                                param.setResult(null);
                                log(">>> edge_hang: skip moveTaskToBack, taskId=" + taskId);
                            } catch (Throwable t) {
                                log("!!! edge_hang error: " + t);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(taskCls, "prepareSurfaces", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sHungTaskIds.isEmpty()) return;
                        final int taskId = XposedHelpers.getIntField(param.thisObject, "mTaskId");
                        if (!sHungTaskIds.contains(taskId)) return;
                        boolean floating = isTaskInFloatingList(taskId);
                        if (!floating || !readBoolCached(KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED, false)) {
                            Object task = param.thisObject;
                            releaseHungTask(task);

                            if (floating) XposedHelpers.callMethod(task, "moveTaskToBack", task);
                            return;
                        }
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

                            releaseHungTask(task);
                            // 任务离开浮窗列表 = 用户点开小窗 / 关闭应用: 静音在此处可靠地恢复。
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
            XposedHelpers.findAndHookMethod(taskCls, "removeImmediately", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (sHungTaskIds.contains(XposedHelpers.getIntField(
                                        param.thisObject, "mTaskId"))) releaseHungTask(param.thisObject);
                            } catch (Throwable t) {
                                log("!!! edge_hang remove task cleanup failed: " + t);
                            }
                        }
                    });
            coreHooksReady = true;
            log(">>> matched android (system_server): float_window_edge_hang (skip moveTaskToBack)");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang system_server hook failed: " + t);
        }

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
                                        || Boolean.TRUE.equals(param.args[1])
                                        || !Integer.valueOf(0).equals(param.args[2])) return;
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

        try {
            final Class<?> dcCls = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayContent", lpparam.classLoader);
            final Class<?> arCls = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(dcCls, "setFocusedApp", arCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (sHungTaskIds.isEmpty()
                                || !readBoolCached(KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED, false)) return;
                        Object ar = param.args[0];
                        if (ar == null) return;
                        Object t = XposedHelpers.callMethod(ar, "getTask");
                        if (t == null) return;
                        final Integer id = Integer.valueOf(XposedHelpers.getIntField(t, "mTaskId"));
                        if (!sHungTaskIds.contains(id)) return;
                        if (!isTaskInFloatingList(id.intValue())) {
                            // 已还原成浮窗/已关闭: 焦点归它是对的, 交还系统。
                            releaseHungTask(t);
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
            // 输入、表面及焦点 hook 必须全部可用；不支持的 ROM 保留原生退后台。
            sEdgeHangHooksReady = coreHooksReady;
            log(">>> matched android (system_server): float_window_edge_hang (focus invariant)");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang focus invariant hook failed: " + t);
        }

        hookFloatHandleWhiteBar(lpparam);
    }

    private static void hookFloatHandleWhiteBar(final XC_LoadPackage.LoadPackageParam lpparam) {
        final int BAR_WIDTH_DP = 4;   // 竖条宽度(细)
        final int EDGE_DP = 8;       // 距屏幕右边界距离
        try {
            final Class<?> fhvCls = XposedHelpers.findClass(
                    "com.android.server.wm.floathandle.FloatHandleView", lpparam.classLoader);
            final Class<?> fhiCls = XposedHelpers.findClass(
                    "com.android.server.wm.floathandle.FloatHandleInfo", lpparam.classLoader);

            // 1) 刷新图标时: 白底圆角 + 隐藏图标列表 + 把容器宽度压成细竖条 + 摆放位置。
            XposedHelpers.findAndHookMethod(fhvCls, "updateIconStyle",
                    int.class, fhiCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED, false)) return;
                                final Object view = param.thisObject;
                                final View container =
                                        (View) XposedHelpers.getObjectField(view, "mFloatHandleViewContainer");
                                if (container == null) return;

                                final int barW = dip2px(container, BAR_WIDTH_DP);
                                // 白色圆角竖条: 白底 + 隐藏图标 + 压窄宽度 + 摆到距边 8dp。
                                applyWhiteBarStyle(view, barW, EDGE_DP);
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            // 2) 布局参数应用点: 强制容器宽度为细竖条(框架每次都会用整条把手宽度覆盖, 必须在此拦截)。
            XposedHelpers.findAndHookMethod(fhvCls, "updateViewLayoutParams",
                    boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED, false)) return;
                                final Object view = param.thisObject;
                                final Object lp = XposedHelpers.getObjectField(view, "mViewContainerLayoutParams");
                                if (lp instanceof ViewGroup.LayoutParams) {
                                    ((ViewGroup.LayoutParams) lp).width = dip2px(view, BAR_WIDTH_DP);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            // 3) 位置收口点: 把把手 translation 摆到距屏幕边缘 8dp, 而不是默认的"探出屏幕"位置。
            XposedHelpers.findAndHookMethod(fhvCls, "updateCurrentPosition",
                    float.class, float.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED, false)) return;
                                final Object view = param.thisObject;
                                final View container =
                                        (View) XposedHelpers.getObjectField(view, "mFloatHandleViewContainer");
                                if (container == null) return;
                                applyWhiteBarPosition(view, container,
                                        dip2px(container, BAR_WIDTH_DP), EDGE_DP);
                                container.setAlpha(1.0f);
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(fhvCls, "computeCurrentPosition",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED, false)) return;
                                final Object pt = param.getResult();
                                if (pt == null) return;
                                final Object controller =
                                        XposedHelpers.getObjectField(param.thisObject, "mFloatHandleController");
                                final int screenW = (int) XposedHelpers.callMethod(controller, "getScreenWidth");
                                final int barW = dip2px(param.thisObject, BAR_WIDTH_DP);
                                final int edge = dip2px(param.thisObject, EDGE_DP);
                                XposedHelpers.setIntField(pt, "x", screenW - edge - barW);
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            // 4) 吸附入口: 套用白条样式, 并吸附后强制位置/不透明(去掉可能的残留位移与淡出)。
            XposedHelpers.findAndHookMethod(fhvCls, "switchToFullMode",
                    fhiCls, boolean.class,
                    XposedHelpers.findClass("android.view.SurfaceControl$Transaction", lpparam.classLoader),
                    XposedHelpers.findClass("java.util.function.Consumer", lpparam.classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED, false)) return;
                                final Object view = param.thisObject;
                                final View container =
                                        (View) XposedHelpers.getObjectField(view, "mFloatHandleViewContainer");
                                if (container == null) return;
                                applyWhiteBarStyle(view, dip2px(container, BAR_WIDTH_DP), EDGE_DP);
                            } catch (Throwable ignored) {
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED, false)) return;
                                final Object view = param.thisObject;
                                final View container =
                                        (View) XposedHelpers.getObjectField(view, "mFloatHandleViewContainer");
                                if (container == null) return;
                                applyWhiteBarPosition(view, container,
                                        dip2px(container, BAR_WIDTH_DP), EDGE_DP);
                                container.setAlpha(1.0f);
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            // 5) 兜底: 即使图标列表被重新显示, 也把每个图标 ImageView 隐藏掉。
            final Class<?> adapterCls = XposedHelpers.findClass(
                    "com.android.server.wm.floathandle.FloatHandleView$FloatIconListAdapter",
                    lpparam.classLoader);
            final Class<?> holderCls = XposedHelpers.findClass(
                    "com.android.server.wm.floathandle.FloatHandleView$FloatIconViewHolder",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(adapterCls, "onBindViewHolder",
                    holderCls, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED, false)) return;
                                final Object holder = param.args[0];
                                final Object iv = XposedHelpers.getObjectField(holder, "mImageView");
                                if (iv instanceof View) {
                                    ((View) iv).setVisibility(View.GONE);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            final Class<?> iconListCls = XposedHelpers.findClass(
                    "com.android.server.wm.floathandle.FloatIconListAnimationLayout",
                    lpparam.classLoader);
            XC_MethodHook suppressIconAnim = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (readBool(KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED, false)) {
                            param.setResult(null); // 跳过图标动画
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedHelpers.findAndHookMethod(iconListCls, "animateToExpand", suppressIconAnim);
            XposedHelpers.findAndHookMethod(iconListCls, "animateToStacking", suppressIconAnim);

            log(">>> matched android (system_server): float_window_edge_hang_white_bar");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang_white_bar hook failed: " + t);
        }
    }

    // 把把手容器刷成白色圆角竖条(直接瞬变): 白底 + 隐藏图标 + 压窄宽度 + 摆到距屏幕边缘 edgeDp。
    private static void applyWhiteBarStyle(Object view, int barW, int edgeDp) {
        try {
            final View container =
                    (View) XposedHelpers.getObjectField(view, "mFloatHandleViewContainer");
            if (container == null) return;

            // 白色圆角竖条(两端全圆 = 胶囊形)。
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(barW / 2.0f);
            container.setBackground(bg);
            container.setAlpha(1.0f);

            // 隐藏图标列表(去掉应用图标)。
            final Object list = XposedHelpers.getObjectField(view, "mFloatIconListAnimationLayout");
            if (list instanceof View) {
                ((View) list).setVisibility(View.GONE);
            }

            // 把容器宽度直接压成细竖条。
            final ViewGroup.LayoutParams lp = container.getLayoutParams();
            if (lp != null) {
                lp.width = barW;
                container.setLayoutParams(lp);
            }

            // 摆到距屏幕边缘 edgeDp。
            applyWhiteBarPosition(view, container, barW, edgeDp);
        } catch (Throwable ignored) {
        }
    }

    // 把把手容器摆到距屏幕边缘 EDGE_DP 的位置(替代默认的"探出屏幕"translation)。
    private static void applyWhiteBarPosition(Object view, View container, int barW, int edgeDp) {
        try {
            final int side = XposedHelpers.getIntField(view, "mCurrentSide");
            final Object controller = XposedHelpers.getObjectField(view, "mFloatHandleController");
            final int screenWidth = (int) XposedHelpers.callMethod(controller, "getScreenWidth");
            final int edge = dip2px(container, edgeDp);
            // 固定距屏幕右边界 8dp(左右停靠均贴右边缘)。
            if (side == 0 || side == 1) {
                container.setTranslationX(screenWidth - edge - barW);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int dip2px(Object view, int dp) {
        try {
            final Resources res = ((View) view).getResources();
            return Math.round(dp * res.getDisplayMetrics().density);
        } catch (Throwable t) {
            return dp * 3;
        }
    }

    private static boolean isActiveHungTask(Object task) {
        if (task == null || sHungTaskIds.isEmpty()
                || !readBoolCached(KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED, false)) return false;
        try {
            int id = XposedHelpers.getIntField(task, "mTaskId");
            WeakReference<Object> ref = sHungTasks.get(id);
            return sHungTaskIds.contains(id) && ref != null && ref.get() == task
                    && isTaskInFloatingList(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // 调用者持有 WM 全局锁；表面记账恢复为实际隐藏状态，下一次原生遍历才能重新 show。
    private static void releaseHungTask(Object task) {
        int id = XposedHelpers.getIntField(task, "mTaskId");
        sHungTaskIds.remove(id);
        sHungTasks.remove(id);
        XposedHelpers.setBooleanField(task, "mLastSurfaceShowing", false);
        releaseHungTaskMuteAsync(id);
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
                    releaseHungTaskMuteAsync(id.intValue());
                    continue;
                }
                Object wm = XposedHelpers.getObjectField(task, "mWmService");
                synchronized (XposedHelpers.getObjectField(wm, "mGlobalLock")) {
                    if (!isActiveHungTask(task)) continue;
                    if (!isFocusedTask(task)) continue;
                    focusTaskBehind(task);
                    log(">>> edge_hang: refocus behind on " + reason + ", taskId=" + id);
                }
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
            if (behind == null || behind == floatTask || isActiveHungTask(behind)) return null;
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

    private static final Object MUTE_LOCK = new Object();
    private static Class<?> sFloatHandleController;   // 兜底: 判断挂机任务是否仍在浮窗列表
    private static Class<?> sFlexibleWindowService;   // FlexibleWindowManagerService
    private static volatile Object sFlexibleTaskController;

    private static final Set<Integer> sHungTaskIds =
            Collections.synchronizedSet(new HashSet<Integer>());
    // taskId -> Task(弱引用, 仅供解锁后纠正焦点时取对象用)。
    private static final Map<Integer, WeakReference<Object>> sHungTasks =
            Collections.synchronizedMap(new HashMap<Integer, WeakReference<Object>>());

    // prepareSurfaces 的 before/after 之间传递 Task.mLastSurfaceShowing 的附加字段键。
    private static final String LAST_SHOWING = "colorosModLastSurfaceShowing";
    private static final String EDGE_EXIT_TASK = "colorosModEdgeExitTask";
    private static final ThreadLocal<Object> sEdgeExitTask = new ThreadLocal<>();
    private static volatile boolean sEdgeHangHooksReady;
    private static volatile Object sPamExt;           // PlaybackActivityMonitorExtImpl 实例
    private static int sMutedTaskId = -1;
    private static int sMutedUid = -1;
    private static float sMutedPrevGain = 1.0f;
    // Pending mute is recorded before posting so hot-reload preflight can see the race window.
    private static int sPendingMuteTaskId = -1;
    private static int sPendingMuteUid = -1;
    private static long sMuteGeneration = 0L;
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
                                if (!readBoolCached(KEY_FLOAT_WINDOW_EDGE_HANG_MUTE_ENABLED, false)) return;
                            } catch (Throwable ignored) {
                                return;
                            }
                            postAsync(new Runnable() {
                                @Override
                                public void run() {
                                    restoreMuteIfNeeded();
                                }
                            });
                        }
                    });
            log(">>> matched android (system_server): float_window_edge_hang_mute");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang_mute system_server hook failed: " + t);
        }
    }

    // 贴边成浮窗时静音: 先同步登记 pending, 再把音量调用抛到主线程，封住 preflight 间隙。
    private static void muteFloatTask(final int taskId, Object task) {
        final int uid = taskUid(task);
        if (uid <= 0) return;
        final long generation;
        synchronized (MUTE_LOCK) {
            generation = ++sMuteGeneration;
            sPendingMuteTaskId = taskId;
            sPendingMuteUid = uid;
        }
        try {
            if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
            if (!sHandler.post(new Runnable() {
                @Override public void run() {
                    synchronized (MUTE_LOCK) {
                        if (generation != sMuteGeneration
                                || sPendingMuteTaskId != taskId
                                || sPendingMuteUid != uid) return;
                        sPendingMuteTaskId = -1;
                        sPendingMuteUid = -1;
                        // The task may have left the floating list while this runnable was queued.
                        if (!isTaskInFloatingList(taskId) || sPamExt == null) return;
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
            })) {
                synchronized (MUTE_LOCK) {
                    if (generation == sMuteGeneration) cancelPendingMuteLocked();
                }
                log("!!! edge_hang_mute post failed");
            }
        } catch (Throwable t) {
            synchronized (MUTE_LOCK) {
                if (generation == sMuteGeneration) cancelPendingMuteLocked();
            }
            log("!!! edge_hang_mute post failed: " + t);
        }
    }

    private static void releaseHungTaskMuteAsync(final int taskId) {
        synchronized (MUTE_LOCK) {
            if (sPendingMuteTaskId == taskId) cancelPendingMuteLocked();
        }
        postAsync(new Runnable() {
            @Override public void run() {
                restoreMuteIfNeeded();
            }
        });
    }

    private static void restoreMuteIfNeeded() {
        synchronized (MUTE_LOCK) {
            if (sPendingMuteTaskId >= 0 && (!sHungTaskIds.contains(sPendingMuteTaskId)
                    || !isTaskInFloatingList(sPendingMuteTaskId))) {
                cancelPendingMuteLocked();
            }
            if (sMutedUid >= 0 && (!sHungTaskIds.contains(sMutedTaskId)
                    || !isTaskInFloatingList(sMutedTaskId))) {
                restoreMutedLocked();
            }
        }
    }

    private static void cancelPendingMuteLocked() {
        sMuteGeneration++;
        sPendingMuteTaskId = -1;
        sPendingMuteUid = -1;
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

    private static Context systemContext() {
        try {
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            if (at != null) {
                Object ctx = XposedHelpers.callMethod(at, "getSystemContext");
                if (ctx instanceof Context) return (Context) ctx;
            }
        } catch (Throwable ignored) {
            // 回落到 Application。
        }
        return currentApplication();
    }

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

    public static void hookFloatWindowSizeLimits(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> zoomDisplayCls = XposedHelpers.findClass(
                    "com.android.server.wm.OplusZoomDisplay", lpparam.classLoader);

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

    private static final Object WIDTH_POLICY_LOCK = new Object();
    private static final java.util.Map<Object, Integer> sOriginalMaxVisualWidths =
            new java.util.IdentityHashMap<Object, Integer>();
    private static final java.util.Set<Object> sWidthParameterStores =
            java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<Object, Boolean>());

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
        synchronized (WIDTH_POLICY_LOCK) {
            sWidthParameterStores.add(parameterStore);
        }

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
                synchronized (WIDTH_POLICY_LOCK) {
                    if (!sOriginalMaxVisualWidths.containsKey(data)) {
                        sOriginalMaxVisualWidths.put(data,
                                Integer.valueOf(XposedHelpers.getIntField(data, "mMaxVisualWidth")));
                    }
                }
                XposedHelpers.setIntField(data, "mMaxVisualWidth", newWidth);
            } catch (Throwable t) {
                log("!!! float_window_size widenMaxVisualWidth set field failed: " + t);
            }
        }
    }

    private static volatile int sLauncherUid = -1;
    private static final java.util.Set<Thread> sKillThreads =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Thread>());

    public static void hookRecentsSwipeUpKillSystemServer(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> amsCls = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(amsCls, "killBackgroundProcesses",
                    String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {

                                if (!readBool(KEY_RECENTS_SWIPE_UP_KILL_ENABLED, false)
                                        && !readBool(
                                                KEY_RECENTS_SWIPE_UP_KILL_SUBSIDIARY_ENABLED,
                                                false)) {
                                    return;
                                }
                                final int uid = android.os.Binder.getCallingUid();
                                if (uid == android.os.Process.myUid()) return;
                                if (sLauncherUid < 0) {
                                    Context ctx = (Context) XposedHelpers.getObjectField(
                                            param.thisObject, "mContext");
                                    if (ctx == null) return;
                                    sLauncherUid = ctx.getPackageManager()
                                            .getPackageUid("com.android.launcher", 0);
                                }
                                if (uid != sLauncherUid) return;
                                final String arg = (String) param.args[0];
                                if (arg == null || (!arg.startsWith("cmtask:")
                                        && !arg.startsWith("cmpkg:"))) return;
                                param.setResult(null); // 跳过原方法, 不做"只杀后台"的弱处理
                                final Object ams = param.thisObject;
                                // "cmtask:<taskId>": 定向杀掉某个任务所在的进程, 用于划掉小程序。
                                if (arg.startsWith("cmtask:")) {
                                    final int taskId;
                                    try {
                                        taskId = Integer.parseInt(arg.substring(7));
                                    } catch (Throwable e) {
                                        return;
                                    }
                                    Thread killThread = new Thread(new Runnable() {
                                        @Override public void run() {
                                            try {
                                                try { Thread.sleep(300L); }
                                                catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    return;
                                                }
                                                long id = android.os.Binder.clearCallingIdentity();
                                                try { killTaskProcess(ams, taskId); }
                                                finally { android.os.Binder.restoreCallingIdentity(id); }
                                            } finally {
                                                sKillThreads.remove(Thread.currentThread());
                                            }
                                        }
                                    }, "ColorOSMod-KillTask");
                                    synchronized (sKillThreads) {
                                        sKillThreads.add(killThread);
                                        try { killThread.start(); }
                                        catch (Throwable t) { sKillThreads.remove(killThread); throw t; }
                                    }
                                    return;
                                }
                                // userId 来自被划掉任务的 TaskKey，不能用 Launcher 自身用户。
                                if (!arg.startsWith("cmpkg:")) return;
                                int separator = arg.indexOf(':', 6);
                                if (separator < 0) return;
                                final int userId = Integer.parseInt(arg.substring(6, separator));
                                final String pkg = arg.substring(separator + 1);
                                if (userId < 0 || pkg.isEmpty()) return;
                                Thread killThread = new Thread(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            try { Thread.sleep(300L); }
                                            catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                return;
                                            }
                                            long ident = android.os.Binder.clearCallingIdentity();
                                            try {
                                                XposedHelpers.callMethod(
                                                        ams, "forceStopPackage", pkg, userId);
                                            } catch (Throwable t) {
                                                log("!!! recents_swipe_up_kill forceStopPackage "
                                                        + pkg + " failed: " + t);
                                            } finally {
                                                android.os.Binder.restoreCallingIdentity(ident);
                                            }
                                        } finally {
                                            sKillThreads.remove(Thread.currentThread());
                                        }
                                    }
                                }, "ColorOSMod-ForceStop");
                                synchronized (sKillThreads) {
                                    sKillThreads.add(killThread);
                                    try { killThread.start(); }
                                    catch (Throwable t) { sKillThreads.remove(killThread); throw t; }
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
            log("HOOK OK ActivityManagerService#killBackgroundProcesses (recents swipe up kill)");
        } catch (Throwable t) {
            log("HOOK FAIL ActivityManagerService#killBackgroundProcesses "
                    + "(recents swipe up kill) :: " + Log.getStackTraceString(t));
        }
    }

    // 杀掉某个任务所在的进程(不整包强杀)。ATMS 经 AMS.mAtmInternal 的 this$0 拿到。
    private static void killTaskProcess(Object ams, int taskId) {
        try {
            Object atmInternal = XposedHelpers.getObjectField(ams, "mAtmInternal");
            Object atms = XposedHelpers.getObjectField(atmInternal, "this$0");
            Object root = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            Object task = XposedHelpers.callMethod(root, "anyTaskForId", taskId, 1);
            if (task == null) return;
            java.util.Set<Integer> pids = new java.util.HashSet<>();
            // Task.mRootProcess 是该任务的宿主进程
            Object proc = XposedHelpers.getObjectField(task, "mRootProcess");
            addPid(pids, proc);
            // 兜底: 顶部 Activity 的宿主进程
            Object top = XposedHelpers.callMethod(task, "getTopNonFinishingActivity");
            if (top != null) {
                try {
                    addPid(pids, XposedHelpers.getObjectField(top, "app"));
                } catch (Throwable ignored) { }
            }
            for (Integer pid : pids) {
                if (pid != null && pid > 0 && pid != android.os.Process.myPid()) {
                    android.os.Process.killProcess(pid);
                }
            }
        } catch (Throwable t) {
            log("!!! recents_swipe_up_kill killTaskProcess " + taskId + " failed: " + t);
        }
    }

    private static void addPid(java.util.Set<Integer> pids, Object windowProcessController) {
        if (windowProcessController == null) return;
        try {
            Object r = XposedHelpers.callMethod(windowProcessController, "getPid");
            if (r instanceof Integer) pids.add((Integer) r);
        } catch (Throwable ignored) { }
    }
    public static boolean canHotReload() {
        // Never alter a user's mute state during reload. If it is active, reject before cleanup.
        synchronized (MUTE_LOCK) {
            if (sPendingMuteTaskId >= 0 || sMutedUid >= 0 || !sKillThreads.isEmpty()
                    || !sHungTaskIds.isEmpty() || !sHungTasks.isEmpty()) {
                log("system_server hot reload rejected: hung/mute/kill operation active");
                return false;
            }
        }
        return true;
    }

    public static Object captureHotReloadHost() {
        synchronized (WIDTH_POLICY_LOCK) {
            return new Object[] { sPamExt, sWidthParameterStores.toArray() };
        }
    }

    public static void restoreHotReloadHost(Object host) {
        Object pam = host;
        Object[] stores = null;
        if (host instanceof Object[]) {
            Object[] state = (Object[]) host;
            pam = state.length > 0 ? state[0] : null;
            stores = state.length > 1 && state[1] instanceof Object[]
                    ? (Object[]) state[1] : null;
        }
        if (pam != null
                && "com.android.server.audio.PlaybackActivityMonitorExtImpl"
                        .equals(pam.getClass().getName())) {
            sPamExt = pam;
        }
        if (stores != null && edgeSizeOptimizeEnabled()) {
            for (Object store : stores) {
                try { widenMaxVisualWidth(store); }
                catch (Throwable t) { log("restore float window width policy failed: " + t); }
            }
        }
    }

    public static void cleanupForHotReload() {
        RecentFreeformHistoryHooks.cleanupForHotReload();
        synchronized (MUTE_LOCK) {
            cancelPendingMuteLocked();
            // canHotReload rejects active mute, but restore defensively before dropping sPamExt.
            if (sMutedUid >= 0) restoreMutedLocked();
        }
        if (sStatusBarOverlayEventHandler != null) {
            sStatusBarOverlayEventHandler.removeCallbacks(sStatusBarOverlayEventRunnable);
        }
        if (sHandler != null) sHandler.removeCallbacksAndMessages(null);
        sStatusBarOverlayEventHandler = null;
        sStatusBarOverlayEventContext = null;
        sHandler = null;
        sPamExt = null;
        sFlexibleTaskController = null;
        sFloatHandleController = null;
        sFlexibleWindowService = null;
        sHungTaskIds.clear();
        sHungTasks.clear();
        sEdgeExitTask.remove();
        sEdgeHangHooksReady = false;
        sLauncherUid = -1;
        sSecurityMarginBackup.remove();
        synchronized (WIDTH_POLICY_LOCK) {
            for (java.util.Map.Entry<Object, Integer> entry :
                    new java.util.ArrayList<>(sOriginalMaxVisualWidths.entrySet())) {
                try {
                    XposedHelpers.setIntField(entry.getKey(), "mMaxVisualWidth",
                            entry.getValue().intValue());
                } catch (Throwable t) {
                    log("restore float window width policy cleanup failed: " + t);
                }
            }
            sOriginalMaxVisualWidths.clear();
            sWidthParameterStores.clear();
        }
    }

}
