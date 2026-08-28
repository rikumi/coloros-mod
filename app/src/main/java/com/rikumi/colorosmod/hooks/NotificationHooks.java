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
 * 通知(分组副标题、通知内边距)相关的 SystemUI hook。
 */
public final class NotificationHooks {
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
}
