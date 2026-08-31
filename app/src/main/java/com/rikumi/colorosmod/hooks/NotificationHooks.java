package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
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

/**
 * 通知(分组副标题、通知内边距)相关的 SystemUI hook。
 */
public final class NotificationHooks {
// 通知面板分组副标题: 布局由 SectionHeaderView 驱动, 内部 TextView(@id/header_label) 原始 24sp。
// hook onFinishInflate(after): 缩小字体至 fontSizePx(16sp), 并把 content 的 paddingTop 减少
// offsetPx(8dp) 让文字上移 —— 不能用 translationY, 否则文字顶端越界被 clipChildren 裁切。
    public static void hookNotificationSubtitle(final XC_LoadPackage.LoadPackageParam lpparam,
                                                 final float density) {
        try {
            // 限定本类声明: onFinishInflate 在 View 里有实现, 上溯会命中进程内所有 View。
            XposedHelpers.findAndHookDeclaredMethod(
                    "com.android.systemui.statusbar.notification.stack.SectionHeaderView",
                    lpparam.classLoader, "onFinishInflate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 运行时动态门控: 关闭则保持系统默认样式。
                                if (!readBool(KEY_NOTIFICATION_SUBTITLE_ENABLED, false)) return;
                                // 滑条 = 字号缩减量(0=系统默认 24sp, 默认 8sp -> 16sp, 最大 16sp -> 8sp);
                                // 偏移与额外内边距按缩减比例等比缩放, 缩减为 0 时整体即系统默认样式。
                                int reduceSp = Math.max(0, Math.min(16,
                                        readInt(KEY_NOTIFICATION_SUBTITLE_SP,
                                                SUBTITLE_REDUCE_SP_DEFAULT)));
                                float t = reduceSp / (float) SUBTITLE_REDUCE_SP_DEFAULT;
                                float fontSizePx = (SUBTITLE_ORIG_SP - reduceSp) * density;
                                float offsetPx = SUBTITLE_OFFSET_DP * t * density;
                                int padPx = Math.round(SUBTITLE_PAD_DP * t * density);
                                Object view = param.thisObject;
                                Object label = XposedHelpers.getObjectField(view, "mLabelView");
                                if (label instanceof android.widget.TextView) {
                                    android.widget.TextView tv = (android.widget.TextView) label;
                                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx);
                                    tv.setTranslationX(offsetPx);
                                    tv.setTranslationY(0f);
                                    tv.setPaddingRelative(tv.getPaddingStart(),
                                            tv.getPaddingTop() + padPx,
                                            tv.getPaddingEnd(),
                                            tv.getPaddingBottom() + padPx);
                                }
                                Object contents = XposedHelpers.getObjectField(view, "mContents");
                                if (contents instanceof android.view.ViewGroup) {
                                    android.view.ViewGroup c = (android.view.ViewGroup) contents;
                                    c.setPaddingRelative(c.getPaddingStart(),
                                            Math.max(0, c.getPaddingTop() - (int) offsetPx),
                                            c.getPaddingEnd(), c.getPaddingBottom());
                                }
                                log("notification_subtitle applied");
                            } catch (Throwable t) {
                                log("notification_subtitle apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK com.android.systemui.statusbar.notification.stack.SectionHeaderView#onFinishInflate");
        } catch (Throwable t) {
            log("HOOK FAIL SectionHeaderView#onFinishInflate :: " + Log.getStackTraceString(t));
        }
    }

    // 记录通知子视图原始上下 padding 的 tag, 用于幂等叠加 / 还原
    static final int TAG_NOTIF_PAD_TOP = 0x4E0F0001;

    static final int TAG_NOTIF_PAD_BOTTOM = 0x4E0F0002;

    static final int TAG_NOTIF_GROUP_HEADER_TRANSLATION = 0x4E0F0003;

    // 高频路径缓存: 由 onNotificationUpdated (低频) 刷新, onLayout/onMeasure/applyState 直接读。
    static volatile boolean sNotifPadEnabled = false;

    static volatile int sNotifPadPx = 0;

// 非静默通知: 给子视图(contracted/expanded/headsUp/singleLine)上下各加 padPx 内边距。
// 直接改子视图 padding 才能被 NotificationContentView#getViewHeight 计入高度, 从而整卡增高。
// 在 onNotificationUpdated(after) 中以首次记录的原始 padding 为基准叠加, 保证幂等。
    public static void hookNotificationPadding(final XC_LoadPackage.LoadPackageParam lpparam,
                                                final float density) {
        try {
            // 限定本类声明: 上溯会命中父类, 影响面变大且语义不明确。
            XposedHelpers.findAndHookDeclaredMethod(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    lpparam.classLoader, "onNotificationUpdated",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 低频路径: 刷新缓存值供高频 hook 读取。
                                boolean enabled = readBool(KEY_NOTIFICATION_PADDING_ENABLED, false);
                                int padPx = Math.round(Math.max(0, Math.min(8,
                                        readInt(KEY_NOTIFICATION_PADDING_DP,
                                                NOTIFICATION_PADDING_DP))) * density);
                                sNotifPadEnabled = enabled;
                                sNotifPadPx = padPx;

                                Object row = param.thisObject;
                                if (!enabled) {
                                    applyNotificationChildPadding(
                                            XposedHelpers.getObjectField(row, "mPrivateLayout"), true, padPx);
                                    applyNotificationChildPadding(
                                            XposedHelpers.getObjectField(row, "mPublicLayout"), true, padPx);
                                    return;
                                }
                                boolean minimized = XposedHelpers.getBooleanField(row, "mIsMinimized");
                                Object privateLayout = XposedHelpers.getObjectField(row, "mPrivateLayout");
                                Object publicLayout = XposedHelpers.getObjectField(row, "mPublicLayout");
                                applyNotificationChildPadding(privateLayout, minimized, padPx);
                                applyNotificationChildPadding(publicLayout, minimized, padPx);
                            } catch (Throwable t) {
                                log("notification_padding apply fail: " + t);
                            }
                        }
                    });

            // 合并通知由 NotificationChildrenContainer 统一布局；给每个子通知加 padding 会只撑开某一行。
            // 这里增加容器总高度，并在布局完成后把整个容器内容整体下移 padPx，形成上下外侧留白。
            // 高频路径优化: 直接读 volatile 变量, 不走 IPC/HashMap。
            final String groupContainerClass =
                    "com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer";
            try {
                XposedHelpers.findAndHookMethod(
                        groupContainerClass, lpparam.classLoader, "getIntrinsicHeight",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!sNotifPadEnabled) return;
                                if (isMinimizedGroup(param.thisObject)) return;
                                Object result = param.getResult();
                                if (result instanceof Integer) {
                                    param.setResult((Integer) result + sNotifPadPx * 2);
                                }
                            }
                        });

                // onMeasure / onLayout 在 View(ViewGroup) 里有实现, 限定本类声明。
                XposedHelpers.findAndHookDeclaredMethod(
                        groupContainerClass, lpparam.classLoader, "onMeasure", int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!sNotifPadEnabled) return;
                                if (isMinimizedGroup(param.thisObject)) return;
                                int extra = sNotifPadPx * 2;
                                android.view.View container = (android.view.View) param.thisObject;
                                XposedHelpers.callMethod(container, "setMeasuredDimension",
                                        container.getMeasuredWidth(), container.getMeasuredHeight() + extra);
                                XposedHelpers.setIntField(container, "mRealHeight",
                                        XposedHelpers.getIntField(container, "mRealHeight") + extra);
                            }
                        });

                XposedHelpers.findAndHookDeclaredMethod(
                        groupContainerClass, lpparam.classLoader, "onLayout",
                        boolean.class, int.class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!sNotifPadEnabled) return;
                                if (isMinimizedGroup(param.thisObject)) return;
                                int padPx = sNotifPadPx;
                                android.view.ViewGroup container = (android.view.ViewGroup) param.thisObject;
                                for (int i = 0; i < container.getChildCount(); i++) {
                                    container.getChildAt(i).offsetTopAndBottom(padPx);
                                }
                            }
                        });

                XposedHelpers.findAndHookMethod(
                        groupContainerClass, lpparam.classLoader, "applyState",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                boolean enabled = sNotifPadEnabled && !isMinimizedGroup(param.thisObject);
                                int padPx = sNotifPadPx;
                                android.view.ViewGroup container =
                                        (android.view.ViewGroup) param.thisObject;
                                applyGroupHeaderOffset(container, "mGroupHeader", enabled, padPx);
                                applyGroupHeaderOffset(container, "mMinimizedGroupHeader", enabled, padPx);
                            }
                        });

                try {
                    XposedHelpers.findAndHookMethod(
                            "com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp",
                            lpparam.classLoader,
                            "layoutOplusHeader",
                            groupContainerClass,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        if (!sNotifPadEnabled) return;
                                        android.view.ViewGroup container =
                                                (android.view.ViewGroup) param.args[0];
                                        if (isMinimizedGroup(container)) return;
                                        Object groupExt = XposedHelpers.getObjectField(
                                                param.thisObject, "oplusNotificationGroupExtImpl");
                                        Object wrapper = XposedHelpers.getObjectField(
                                                groupExt, "oplusHeaderWrapper");
                                        Object headerView = XposedHelpers.getObjectField(wrapper, "mView");
                                        applyViewTranslation(headerView, true, sNotifPadPx);
                                    } catch (Throwable ignored) {}
                                }
                            });
                } catch (Throwable ignored) {}

                try {
                    XposedHelpers.findAndHookMethod(
                            "com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupExtImpl",
                            lpparam.classLoader,
                            "layoutOplusHeader",
                            groupContainerClass,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        if (!sNotifPadEnabled) return;
                                        android.view.ViewGroup container =
                                                (android.view.ViewGroup) param.args[0];
                                        if (isMinimizedGroup(container)) return;
                                        Object wrapper = XposedHelpers.getObjectField(
                                                param.thisObject, "oplusHeaderWrapper");
                                        Object headerView = XposedHelpers.getObjectField(wrapper, "mView");
                                        applyViewTranslation(headerView, true, sNotifPadPx);
                                    } catch (Throwable ignored) {}
                                }
                            });
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            log("HOOK OK ExpandableNotificationRow#onNotificationUpdated + group container hooks");
        } catch (Throwable t) {
            log("HOOK FAIL ExpandableNotificationRow#onNotificationUpdated :: " + t);
        }
    }

// 通知左滑直接清除(走海外 exp 分支)。
// 国内版左滑通知会先露出"设置/删除"两个侧边按钮, 要滑到头才清除; exp 分支不生成任何按钮、
// 抬手达阈值即清除。区分点在两处(均已 dexdump 核对, 同在 SystemUI 的 classes3.dex):
//   1. NotificationMenuRowExtImpl#createMenuViewsExt(Z, NotificationMenuRowPlugin, ArrayList,
//        Context, Z, Z)V (PUBLIC) —— 源码 265 行 `if (!FeatureOption.isExpRegion())` 内
//        addFirst 设置项(gear)与删除项(lottie); exp 分支在末尾 arrayList.clear()(332 行)。
//   2. OplusSwipeHelperExImpl#shouldNotShowMenuExt(MotionEvent, View, float,
//        NotificationMenuRowPlugin)Z (PUBLIC) —— 源码 182 行
//        `(FeatureOption.isExpRegion() && view instanceof ExpandableNotificationRow)`。
//        返回 true 时 NotificationSwipeHelper#handleMenuRowSwipe(240 行)跳过"吸附露出菜单"
//        分支, 改走 dismiss / snapClosed。
// 不 hook FeatureOption.isExpRegion(): 全 SystemUI 有 150+ 处调用, 影响面不可控。
    public static void hookNotificationSwipeToDismiss(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.notification.row.NotificationMenuRowExtImpl",
                    lpparam.classLoader, "createMenuViewsExt",
                    boolean.class,
                    "com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin",
                    java.util.ArrayList.class, Context.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_NOTIFICATION_SWIPE_TO_DISMISS_ENABLED, false)) {
                                    return;
                                }
                                Object items = param.args[2];
                                if (items instanceof java.util.ArrayList) {
                                    ((java.util.ArrayList) items).clear();
                                }
                                // 与 exp 分支保持一致: 侧边按钮实例也不保留, 否则 onDismissRow()
                                // 会拿这两个未挂载的 view 跑移除动画。
                                clearMenuItem(param.thisObject, "settingsItem");
                                clearMenuItem(param.thisObject, "deleteItem");
                            } catch (Throwable t) {
                                log("notification_swipe_to_dismiss create fail: " + t);
                            }
                        }
                    });
            log("HOOK OK NotificationMenuRowExtImpl#createMenuViewsExt (swipe to dismiss)");
        } catch (Throwable t) {
            log("HOOK FAIL NotificationMenuRowExtImpl#createMenuViewsExt :: "
                    + Log.getStackTraceString(t));
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.notification.row.swipe.OplusSwipeHelperExImpl",
                    lpparam.classLoader, "shouldNotShowMenuExt",
                    MotionEvent.class, View.class, float.class,
                    "com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_NOTIFICATION_SWIPE_TO_DISMISS_ENABLED, false)) {
                                    return;
                                }
                                // 与 exp 分支同口径: 只对普通通知行生效, OplusCustomRow 保持原生。
                                if (!isExpandableNotificationRow(param.args[1])) return;
                                param.setResult(Boolean.TRUE);
                            } catch (Throwable t) {
                                log("notification_swipe_to_dismiss shouldNotShowMenu fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusSwipeHelperExImpl#shouldNotShowMenuExt (swipe to dismiss)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusSwipeHelperExImpl#shouldNotShowMenuExt :: "
                    + Log.getStackTraceString(t));
        }
    }

// 通知下滑展开(走海外/一加 OxygenOS 的 exp 分支)。
// 国内版 ColorOS 关掉了 AOSP 的 ExpandHelper(单指下拉通知展开), 两处判断(均已 dexdump 核对):
//   1. NotificationStackScrollLayout 构造末尾(源码 3080-3085 行)
//        `if (FeatureOption.isExpRegion() || getView() == null) return; view.setExpandingEnabled(false);`
//      —— 国内版构造时就把 mExpandHelper.setEnabled(false); exp 分支什么都不做,
//        保留构造里设的 expandHelper.mEnabled = true。
//   2. NotificationStackScrollLayoutExtImpl#setExpandingEnabled(Z)V (PUBLIC, classes4.dex)
//      —— `if (!FeatureOption.isExpRegion() || getView() == null) return;` 直接短路,
//        唯一的调用方 NotificationStackScrollLayoutController(237 行)传 !onKeyguard(),
//        国内版永远传不进去。
// 因此要补两刀: 构造后把 enabled 复位为 true(对齐 exp 的初值), 并让 ext 层把参数透传下去
// (保留"锁屏上不展开"的原生语义)。不 hook FeatureOption.isExpRegion(): 全 SystemUI 150+ 处调用。
    public static void hookNotificationPullExpand(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookConstructor(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
                    lpparam.classLoader, Context.class, android.util.AttributeSet.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_NOTIFICATION_PULL_EXPAND_ENABLED, false)) {
                                    return;
                                }
                                // exp 分支在构造里不做任何处理, mEnabled 保持 true。
                                XposedHelpers.callMethod(param.thisObject,
                                        "setExpandingEnabled", Boolean.TRUE);
                            } catch (Throwable t) {
                                log("notification_pull_expand ctor fail: " + t);
                            }
                        }
                    });
            log("HOOK OK NotificationStackScrollLayout#<init> (pull expand)");
        } catch (Throwable t) {
            log("HOOK FAIL NotificationStackScrollLayout#<init> :: "
                    + Log.getStackTraceString(t));
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.statusbar.notification.stack.NotificationStackScrollLayoutExtImpl",
                    lpparam.classLoader, "setExpandingEnabled", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_NOTIFICATION_PULL_EXPAND_ENABLED, false)) {
                                    return;
                                }
                                // 等价于接口 NotificationStackScrollLayoutExt 的 default 实现
                                // (Ext.java 152-155 行): 直接透传给 view, 跳过 exp 判断。
                                Object view = XposedHelpers.callMethod(param.thisObject, "getView");
                                if (view == null) return;
                                XposedHelpers.callMethod(view, "setExpandingEnabled", param.args[0]);
                                param.setResult(null);
                            } catch (Throwable t) {
                                log("notification_pull_expand setExpandingEnabled fail: " + t);
                            }
                        }
                    });
            log("HOOK OK NotificationStackScrollLayoutExtImpl#setExpandingEnabled (pull expand)");
        } catch (Throwable t) {
            log("HOOK FAIL NotificationStackScrollLayoutExtImpl#setExpandingEnabled :: "
                    + Log.getStackTraceString(t));
        }
    }


    // ------------------------------------------------- 通知图标区显示方式(状态栏歌词专用)
    // 通知图标区的显示模式由 OplusNotificationIconAreaRepository 的 notificationShowMode
    // (MutableStateFlow) 决定: 0=显示图标, 1=显示数字, 2=不显示。系统由 Settings.Secure
    // notification_prompt_mode 的观察者写入; 显示歌词时要切"显示数字", 只能改这个流。
    // 调用方见 StatusBarLyricHooks#setNotificationNumberMode。
    private static final String CLS_ICON_AREA_REPOSITORY =
            "com.oplus.systemui.statusbar.icon.data.OplusNotificationIconAreaRepository";

    /** 通知图标区仓库实例(构造时抓取), 用于下发"显示数字"及其还原值。 */
    private static volatile Object sIconAreaRepository = null;
    /** 歌词显示期间强制"显示数字"。 */
    private static volatile boolean sLyricNumberMode = false;
    /** 显示模式对齐间隔(ms): 只在目标值与仓库当前值不同时才写, 平时只读一次 StateFlow。 */
    private static final long SHOW_MODE_APPLY_INTERVAL_MS = 500L;
    private static volatile boolean sShowModeLoopStarted = false;

    public static void hookNotificationIconAreaRepository(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> repoCls =
                    XposedHelpers.findClass(CLS_ICON_AREA_REPOSITORY, lpparam.classLoader);
            XposedHelpers.findAndHookConstructor(repoCls,
                    Context.class,
                    XposedHelpers.findClass(
                            "kotlinx.coroutines.CoroutineScope", lpparam.classLoader),
                    XposedHelpers.findClass(
                            "com.android.systemui.dump.DumpManager", lpparam.classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sIconAreaRepository = param.thisObject;
                            startShowModeLoop();
                        }
                    });
            log("HOOK OK OplusNotificationIconAreaRepository#<init> (notification show mode)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusNotificationIconAreaRepository#<init> :: "
                    + Log.getStackTraceString(t));
        }
    }


    // 轮询对齐显示模式: 只在目标值与仓库当前值不同时才写, 平时只读一次 StateFlow。
    // 轮询是必需的 —— 歌词状态变化不会触发系统的设置观察者, 且儿童模式/专注模式下仓库
    // 仍会输出"不显示", 那是系统行为, 保留。
    private static void startShowModeLoop() {
        if (sShowModeLoopStarted) return;
        sShowModeLoopStarted = true;
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    applyNotificationShowMode();
                } catch (Throwable t) {
                    log("notification show mode loop fail: " + t);
                }
                handler.postDelayed(this, SHOW_MODE_APPLY_INTERVAL_MS);
            }
        }, SHOW_MODE_APPLY_INTERVAL_MS);
    }

    /** 把目标显示模式写进通知图标区仓库; 与当前值一致时不写。 */
    static void applyNotificationShowMode() {
        Object repo = sIconAreaRepository;
        if (repo == null) return;
        Object flow = XposedHelpers.getObjectField(repo, "notificationShowMode");
        if (flow == null) return;
        // 歌词显示期间强制"显示数字", 否则还原成系统设置里的值。
        int desired = sLyricNumberMode
                ? NOTIFICATION_PROMPT_SHOW_NUMBER : readNotificationPromptMode();
        Object current = XposedHelpers.callMethod(flow, "getValue");
        if (current instanceof Integer && ((Integer) current).intValue() == desired) return;
        XposedHelpers.callMethod(flow, "setValue", Integer.valueOf(desired));
        log("notification show mode -> " + desired);
    }

    /** 歌词显示期间强制"显示数字"(由 StatusBarLyricHooks 调用)。 */
    public static void setLyricNumberMode(boolean on) {
        sLyricNumberMode = on;
        applyNotificationShowMode();
    }

    /** 读系统设置里的"通知栏显示方式", 用于歌词结束后还原。 */
    private static int readNotificationPromptMode() {
        try {
            Context ctx = sAppContext;
            if (ctx == null) return NOTIFICATION_PROMPT_SHOW_ICON;
            return Settings.Secure.getInt(ctx.getContentResolver(),
                    SETTINGS_KEY_NOTIFICATION_PROMPT_MODE, NOTIFICATION_PROMPT_SHOW_ICON);
        } catch (Throwable t) {
            return NOTIFICATION_PROMPT_SHOW_ICON;
        }
    }

    static void clearMenuItem(Object ext, String fieldName) {
        try {
            XposedHelpers.setObjectField(ext, fieldName, null);
        } catch (Throwable ignored) {
        }
    }

    // 按类名逐级向上匹配, 不依赖 findClass(避免 classLoader 差异导致 isInstance 判否)。
    static boolean isExpandableNotificationRow(Object view) {
        for (Class<?> c = view == null ? null : view.getClass();
             c != null && c != Object.class;
             c = c.getSuperclass()) {
            if ("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
                    .equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 快速判断分组容器是否处于最小化状态 (无 IPC) */
    static boolean isMinimizedGroup(Object container) {
        try {
            Object row = XposedHelpers.getObjectField(container, "mContainingNotification");
            return row != null && XposedHelpers.getBooleanField(row, "mIsMinimized");
        } catch (Throwable t) {
            return false;
        }
    }

    static void applyGroupHeaderOffset(android.view.ViewGroup container,
                                                String fieldName, boolean enabled, int padPx) {
        try {
            Object header = XposedHelpers.getObjectField(container, fieldName);
            applyViewTranslation(header, enabled, padPx);
        } catch (Throwable ignored) {}
    }

    static void applyViewTranslation(Object object, boolean enabled, int padPx) {
        if (!(object instanceof android.view.View)) return;
        android.view.View view = (android.view.View) object;
        Object tag = view.getTag(TAG_NOTIF_GROUP_HEADER_TRANSLATION);
        float original = tag instanceof Float ? (Float) tag : view.getTranslationY();
        if (!(tag instanceof Float)) {
            view.setTag(TAG_NOTIF_GROUP_HEADER_TRANSLATION, original);
        }
        float target = enabled ? original + padPx : original;
        if (view.getTranslationY() != target) {
            view.setTranslationY(target);
        }
    }

    static void applyNotificationChildPadding(Object contentView, boolean minimized, int padPx) {
        if (!(contentView instanceof android.view.ViewGroup)) return;
        String[] fields = { "mContractedChild", "mExpandedChild", "mHeadsUpChild" };
        for (String f : fields) {
            Object child = XposedHelpers.getObjectField(contentView, f);
            if (!(child instanceof android.view.View)) continue;
            android.view.View v = (android.view.View) child;
            Integer origTop = (Integer) v.getTag(TAG_NOTIF_PAD_TOP);
            Integer origBottom = (Integer) v.getTag(TAG_NOTIF_PAD_BOTTOM);
            if (origTop == null) { origTop = v.getPaddingTop(); v.setTag(TAG_NOTIF_PAD_TOP, origTop); }
            if (origBottom == null) { origBottom = v.getPaddingBottom(); v.setTag(TAG_NOTIF_PAD_BOTTOM, origBottom); }
            int top = minimized ? origTop : origTop + padPx;
            int bottom = minimized ? origBottom : origBottom + padPx;
            if (v.getPaddingTop() != top || v.getPaddingBottom() != bottom) {
                v.setPaddingRelative(v.getPaddingStart(), top, v.getPaddingEnd(), bottom);
            }
        }
    }
}
