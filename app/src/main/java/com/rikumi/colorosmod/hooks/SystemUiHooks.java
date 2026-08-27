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
 * SystemUI(com.android.systemui) 作用域的全部 hook：QS 页头/边距/磁贴/背景压暗、通知内外边距、状态栏电量。
 */
public final class SystemUiHooks {
    public static void hookSystemUi(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched systemui, classLoader=" + lpparam.classLoader);
        float density = readDensity();

        // Feature 5 — 隐藏运营商名: 始终注入, 运行时按 KEY_QS_CARRIER_ENABLED 门控(见内部)。
        hookQsHideCarrier(lpparam);

        // Feature 6 — 控制中心顶栏间距: 始终注入, 运行时按 KEY_QS_TOPMARGIN_ENABLED 门控。
        {
            final int footerPx = Math.round(QS_FOOTER_MARGIN_DP * density);
            log("qs_topmargin footerPx(fixed " + QS_FOOTER_MARGIN_DP + "dp)=" + footerPx);
            hookQsTopMargin(lpparam, footerPx);
        }

        // Feature 3 — 通知分组副标题: 始终注入, 运行时按 KEY_NOTIFICATION_SUBTITLE_ENABLED 门控,
        // 字号缩减量由 KEY_NOTIFICATION_SUBTITLE_SP(0-16sp, 默认 8sp) 运行时读取, 偏移/内边距随其等比缩放。
        hookNotificationSubtitle(lpparam, density);

        // Feature 3b — 通知内边距: 始终注入, 运行时按 KEY_NOTIFICATION_PADDING_ENABLED 门控,
        // 内边距由 KEY_NOTIFICATION_PADDING_DP(0-8dp, 默认 4dp) 运行时读取。
        hookNotificationPadding(lpparam, density);

        // Feature 10 — 合并控制中心背景压暗(半透明黑): 始终注入, 运行时按 KEY_QS_SCRIM_TRANSLUCENT_ENABLED 门控。
        hookQsBackgroundDim(lpparam);
        // Feature 13 — 控制中心 WLAN/蓝牙 名称单行省略: 始终注入, 运行时按 KEY_QS_TILE_NAME_ELLIPSIS_ENABLED 门控。
        hookQsTileNameEllipsis(lpparam);
        // Feature 17 — 流体云出现时不隐藏电量百分比: 始终注入, 运行时按 KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED 门控。
        hookFluidCloudKeepPercent(lpparam);
        // Feature 18 — 悬浮小窗贴边挂机: 真正的提交逻辑在 system_server(android 作用域), 见 hookFloatWindowEdgeHangSystemServer。
        GestureHooks.hookGestureBarHeight(lpparam);
        GestureHooks.hookGestureBarLongPressDisable(lpparam);
        GestureHooks.hookMBack(lpparam);
        // 独立功能 — 避免手势区域点击穿透: 与 mBack 解耦, 各自独立开关。
        GestureHooks.hookGestureTouchThrough(lpparam);
    }

    /**
     * 经典(合并)控制中心: 隐藏运营商名。OplusQuickStatusBarHeader#onFinishInflate 中
     * R.id.qs_carrier_text (位于 qs_clock_container 内) / R.id.carrier_group 显示运营商名, 直接 GONE。
     * (用户使用经典模式, 不再 hook 分离模式的 SeparateQSFakeStatusController, 以免与经典模式叠加。)
     */
    public static void hookQsHideCarrier(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.qs.OplusQuickStatusBarHeader",
                    lpparam.classLoader, "onFinishInflate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.view.View header = (android.view.View) param.thisObject;
                                android.content.res.Resources res = header.getResources();
                                int id = res.getIdentifier("qs_carrier_text", "id", "com.android.systemui");
                                int id2 = res.getIdentifier("carrier_group", "id", "com.android.systemui");
                                // 运行时动态门控: 关闭则还原为显示。
                                if (!readBool(KEY_QS_CARRIER_ENABLED, false)) {
                                    if (id != 0) {
                                        android.view.View c = header.findViewById(id);
                                        if (c != null) c.setVisibility(android.view.View.VISIBLE);
                                    }
                                    if (id2 != 0) {
                                        android.view.View g = header.findViewById(id2);
                                        if (g != null) g.setVisibility(android.view.View.VISIBLE);
                                    }
                                    return;
                                }
                                if (id != 0) {
                                    android.view.View carrier = header.findViewById(id);
                                    if (carrier != null) carrier.setVisibility(android.view.View.GONE);
                                }
                                if (id2 != 0) {
                                    android.view.View g = header.findViewById(id2);
                                    if (g != null) g.setVisibility(android.view.View.GONE);
                                }
                                log("qs_carrier(classic) applied");
                            } catch (Throwable t) {
                                log("qs_carrier(classic) apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK com.oplus.systemui.qs.OplusQuickStatusBarHeader#onFinishInflate");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQuickStatusBarHeader#onFinishInflate :: " + Log.getStackTraceString(t));
        }
    }

    // 控制中心顶栏间距(经典/合并模式)。
    //  - 右侧状态图标簇 quick_qs_status_icons: 在 OplusQSFakeStatusController 的展开进度回调中
    //    原地渐隐, 避免先执行系统原生位移动画再消失。
    //  - 页脚 OplusQSFooterImpl#mSettingsContainer: 让日期/设置按钮小幅下沉。
    public static void hookQsTopMargin(final XC_LoadPackage.LoadPackageParam lpparam,
                                        final int footerPx) {
        // 真正执行顶部图标簇位移动画的是 OplusQSFakeStatusController 的展开进度监听器，
        // 不是 OplusQuickStatusBarHeader#setExpansion（该版本没有这个方法）。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.qs.fake.OplusQSFakeStatusController$qsPanelExpandFractionListener$1",
                    lpparam.classLoader, "onFractionChanged", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                float fraction = ((Float) param.args[0]).floatValue();
                                applyQsStatusClusterFade(param.thisObject, fraction);
                            } catch (Throwable t) {
                                log("qs_status fade apply fail: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                float fraction = ((Float) param.args[0]).floatValue();
                                applyQsStatusClusterFade(param.thisObject, fraction);
                            } catch (Throwable t) {
                                log("qs_status fade after apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQSFakeStatusController$qsPanelExpandFractionListener#onFractionChanged");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSFakeStatusController fraction listener :: " + Log.getStackTraceString(t));
        }
        // 经典控制中心: 设置按钮/页脚日期 (仅 collapsed 时 updateResources$15 重置 padding 为 0 后再叠加 footerPx)
        try {
            boolean ok = false;
            for (String m : new String[]{"updateResources$15", "updateResources"}) {
                if (ok) break;
                try {
                    XposedHelpers.findAndHookMethod(
                            "com.oplus.systemui.qs.OplusQSFooterImpl",
                            lpparam.classLoader, m,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    // 运行时动态门控: 关闭则不叠加页脚顶部间距。
                                    if (!readBool(KEY_QS_TOPMARGIN_ENABLED, false)) return;
                                    addFooterTopPadding(param.thisObject, footerPx);
                                }
                            });
                    ok = true;
                    log("HOOK OK com.oplus.systemui.qs.OplusQSFooterImpl#" + m);
                } catch (Throwable t) {
                    // 尝试下一个候选方法名
                }
            }
            if (!ok) log("HOOK FAIL OplusQSFooterImpl (no updateResources method matched)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSFooterImpl :: " + Log.getStackTraceString(t));
        }
    }

    /**
     * 展开回调中原生会同时移动两个不同的节点：
     * mStatusIconsView 是状态栏节点，quickStatus 是 QS 顶栏节点；二者不是同一个 View。
     * 因此在 fraction 第一次大于 0 时直接 INVISIBLE，在原生 translation 执行前彻底阻止移动动画可见。
     */
    static void applyQsStatusClusterFade(Object fractionListener, float expansionFraction) {
        Object controller = XposedHelpers.getObjectField(fractionListener, "this$0");
        boolean enabled = readBool(KEY_QS_TOPMARGIN_ENABLED, false);
        boolean hide = enabled && expansionFraction > 0f;

        setQsStatusVisibility(controller, "quickStatus", hide);
        setQsStatusVisibility(controller, "fakeStatusIconContainer", hide);
        try {
            Object headerController = XposedHelpers.getObjectField(controller, "statusBarHeader");
            Object icons = XposedHelpers.callMethod(headerController, "getMStatusIconsView");
            if (icons instanceof android.view.View) {
                ((android.view.View) icons).setVisibility(
                        hide ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }

    static void setQsStatusVisibility(Object controller, String fieldName, boolean hide) {
        try {
            Object value = XposedHelpers.getObjectField(controller, fieldName);
            if (value instanceof android.view.View) {
                ((android.view.View) value).setVisibility(
                        hide ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }

    static void addFooterTopPadding(Object footer, int footerPx) {        try {
            Object container = XposedHelpers.getObjectField(footer, "mSettingsContainer");
            if (container instanceof android.view.View) {
                android.view.View v = (android.view.View) container;
                v.setPaddingRelative(v.getPaddingStart(), v.getPaddingTop() + footerPx,
                        v.getPaddingEnd(), v.getPaddingBottom());
            }
        } catch (Throwable t) {
            log("qs_footer top padding fail: " + t);
        }
    }

    // 通知面板分组副标题: 布局由 SectionHeaderView 驱动, 其内部 TextView(@id/header_label) 原始
    // textSize=24sp, 被包在 FrameLayout(@id/content, paddingTop=12dp) 中。hook onFinishInflate(after):
    //   - 缩小字体至 fontSizePx(16sp);
    //   - content 的 paddingTop 减少 offsetPx(8dp) 让文字上移 —— 不能用 translationY, 否则文字顶端
    //     越过 content 上边界被 clipChildren 裁切(这正是之前"上半部分被裁切"的原因)。
    public static void hookNotificationSubtitle(final XC_LoadPackage.LoadPackageParam lpparam,
                                                 final float density) {
        try {
            XposedHelpers.findAndHookMethod(
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

    // 非静默(未被最小化, mIsMinimized==false)通知: 给其通知子视图(contracted/expanded/headsUp/
    // singleLine)的上下内边距各加 padPx。直接改子视图 padding 才能被
    // NotificationContentView#getViewHeight 计入高度(它取子视图自身 getHeight, 不含自身 padding),
    // 从而整张卡片增高、内部上下留白增加; 静默(最小化)通知还原原始 padding。
    // 在 onNotificationUpdated(after) 中施加, 以首次记录的原始 padding 为基准叠加, 保证幂等。
    public static void hookNotificationPadding(final XC_LoadPackage.LoadPackageParam lpparam,
                                                final float density) {
        try {
            XposedHelpers.findAndHookMethod(
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

                XposedHelpers.findAndHookMethod(
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

                XposedHelpers.findAndHookMethod(
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

    // 合并(经典)控制中心背景压暗。承载背景的是"背后 scrim"。为什么"纯黑背景反而变灰"(根因不在本模块):
    // behind scrim 的平台混色默认 top=LUMINOSITY+#99333333、bottom=OVERLAY+#80999999 —— LUMINOSITY 把
    // 亮度归一化到 ~0.2(黑底提亮成灰), 且这层混色在 SurfaceFlinger 的 AGSL shader 里合成、位于窗口
    // 内容之下, 任何半透明黑叠加都去不掉这层灰。故 hook getPanelPlatformMixConfig 把 top 改为
    // LUMINOSITY+近黑、bottom OVERLAY 置 0, 仅替换 backgroundShaderParam(不动 PANEL_*_MIX_COLOR)。
    public static void hookQsBackgroundDim(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            try {
                Class<?> exImpClass = XposedHelpers.findClass(
                        "com.oplus.systemui.statusbar.phone.ScrimControllerExImp", lpparam.classLoader);
                XposedHelpers.findAndHookMethod(exImpClass, "getPanelPlatformMixConfig",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!readBool(KEY_QS_SCRIM_TRANSLUCENT_ENABLED, false)) return;
                                    Object cfg = param.getResult();
                                    if (cfg == null) return;
                                    if (!cfg.getClass().getName().contains("BlurMixSingleWithShader")) return;
                                    Object bg = XposedHelpers.getObjectField(cfg, "backgroundShaderParam");
                                    if (bg == null) return;
                                    // top=LUMINOSITY(5) 把亮度归一化到该层 RGB 的亮度; bottom=OVERLAY(2) 关闭。
                                    // alpha=0x99(与系统默认同强度)使结果亮度直接等于 top 层 RGB 亮度。
                                    // 亮度由滑条 qs_scrim_brightness(0-20, 默认 10)控制:
                                    //   0=全黑(RGB 0), 20=系统默认 lumin(0x33=51, 即不压暗), 10≈50% lumin。
                                    // LUMINOSITY 仍保留底层模糊色相/纹理。
                                    int brightness = readInt(KEY_QS_SCRIM_BRIGHTNESS, QS_SCRIM_BRIGHTNESS_DEFAULT);
                                    brightness = Math.max(0, Math.min(20, brightness));
                                    int gray = Math.round(brightness * QS_SCRIM_LUMIN_MAX / 20f);
                                    int darkTop = Color.argb(0x99, gray, gray, gray);
                                    Object dark = XposedHelpers.newInstance(bg.getClass(), 5, darkTop, 2, 0);
                                    XposedHelpers.setObjectField(cfg, "backgroundShaderParam", dark);
                                } catch (Throwable t) {
                                    log("qs_scrim mixconfig error: " + t);
                                }
                            }
                        });
                log("HOOK OK ScrimControllerExImp#getPanelPlatformMixConfig (qs_scrim_translucent)");
            } catch (Throwable t) {
                log("HOOK FAIL getPanelPlatformMixConfig :: " + Log.getStackTraceString(t));
            }

            log("HOOK OK ScrimView background-dim (qs_scrim_translucent)");
        } catch (Throwable t) {
            log("HOOK FAIL qs_scrim_translucent :: " + Log.getStackTraceString(t));
        }
    }

    // 控制中心 WLAN/蓝牙 名称单行省略。WLAN 的 SSID 写在主标题 labelTitle(R.id.tile_label),
    // 由 handleTileStateChange 经 TextSwitcherExtKt.setContent 写入; 蓝牙设备名写在副标题
    // labelDesc(R.id.tile_label_desc), 由 updateLabelDescText 写入。在这两个方法之后对两个
    // TextSwitcher 下的所有 TextView 强制单行 + 行尾省略号。幂等。
    // (本类没有 updateLabelText, 标题是在 handleTileStateChange 内直接设置的。)
    public static void hookQsTileNameEllipsis(final XC_LoadPackage.LoadPackageParam lpparam) {
        // WLAN/BT 等可伸缩磁贴(2x1)实现 updateLabelText / updateLabelDescText, 字段名一致。
        final String cls = "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne";
        // WLAN 的 SSID 写在主标题 labelTitle, 由 handleTileStateChange(QSTile.State) 直接设置;
        // 蓝牙设备名/副标题写在 labelDesc, 由 updateLabelDescText(QSTile.State) 设置。
        // 注意: 本类没有 updateLabelText 方法(标题在 handleTileStateChange 里直接写), 故 hook 这两个入口。
        final String[] methods = { "handleTileStateChange", "updateLabelDescText" };
        final String[] labelFields = { "labelTitle", "labelDesc" };
        for (final String m : methods) {
            try {
                XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, m,
                        "com.android.systemui.plugins.qs.QSTile$State",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!readBool(KEY_QS_TILE_NAME_ELLIPSIS_ENABLED, false)) return;
                                    for (String f : labelFields) {
                                        Object v = XposedHelpers.getObjectField(param.thisObject, f);
                                        forceSingleLineEllipsis(v);
                                    }
                                } catch (Throwable ignored) {
                                    // 按规则: 不靠日志排错, 忽略
                                }
                            }
                        });
            } catch (Throwable t) {
                log("HOOK FAIL " + cls + "#" + m + " :: " + Log.getStackTraceString(t));
            }
        }
    }

    // 对任意 View(TextSwitcher / ViewGroup / TextView) 递归对其下所有 TextView 强制单行省略。
    static void forceSingleLineEllipsis(Object view) {
        if (view instanceof TextView) {
            applyEllipsis((TextView) view);
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                forceSingleLineEllipsis(vg.getChildAt(i));
            }
            // TextSwitcher 复用两个子视图, 动画切换时 getNextView() 指向尚未显示的那个, 也覆盖到。
            if (view instanceof TextSwitcher) {
                android.view.View next = ((TextSwitcher) view).getNextView();
                if (next != null) forceSingleLineEllipsis(next);
            }
        }
    }

    static void applyEllipsis(TextView tv) {
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setHorizontallyScrolling(false); // 关闭横向滚动/跑马灯, 仅静态行尾省略
    }

    // 流体云出现时不隐藏电量百分比: 流体云胶囊出现时 BatteryStyleModel.capsuleShowing=true,
    // 令 BatteryViewBinder.bind$updatePercentOutView 中的 PercentOutIcon.isVisible=false。
    // 这里 hook 该方法, 在 beforeHook 中把 isVisible 强制置 true。
    public static void hookFluidCloudKeepPercent(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> percentOutIconClass = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.model.PercentOutIcon",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.binder.BatteryViewBinder",
                    lpparam.classLoader,
                    "bind$updatePercentOutView",
                    TextView.class,
                    XposedHelpers.findClass(
                            "com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView",
                            lpparam.classLoader),
                    percentOutIconClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED, false)) return;
                            try {
                                Object percentOutIcon = param.args[2];
                                if (percentOutIcon == null) return;
                                // 强制 isVisible = true, 使电量百分比不被流体云隐藏。
                                XposedHelpers.setBooleanField(percentOutIcon, "isVisible", true);
                            } catch (Throwable t) {
                                log("fluid_cloud_keep_percent hook error: " + t);
                            }
                        }
                    });
            log("HOOK OK BatteryViewBinder#bind$updatePercentOutView (fluid cloud keep percent)");
        } catch (Throwable t) {
            log("HOOK FAIL BatteryViewBinder#bind$updatePercentOutView: " + t);
        }
    }
}
