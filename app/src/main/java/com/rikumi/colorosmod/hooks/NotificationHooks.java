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

public final class NotificationHooks {

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

                                int reduceSp = Math.max(0, Math.min(16,
                                        readInt(KEY_NOTIFICATION_SUBTITLE_SP,
                                                SUBTITLE_REDUCE_SP_DEFAULT)));
                                float t = reduceSp / (float) SUBTITLE_REDUCE_SP_DEFAULT;
                                float fontSizePx = (SUBTITLE_ORIG_SP - reduceSp) * density;
                                float offsetPx = SUBTITLE_OFFSET_DP * t * density;
                                int padPx = Math.round(SUBTITLE_PAD_DP * t * density);
                                Object view = param.thisObject;
                                Object label = XposedHelpers.getObjectField(view, "mLabelView");
                                Object contents = XposedHelpers.getObjectField(view, "mContents");
                                if (label instanceof android.widget.TextView) {
                                    applyNotificationSubtitle(view, (android.widget.TextView) label,
                                            contents instanceof android.view.ViewGroup
                                                    ? (android.view.ViewGroup) contents : null,
                                            fontSizePx, offsetPx, padPx);
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

    private static final class SubtitleState {
        final java.lang.ref.WeakReference<android.widget.TextView> labelRef;
        final java.lang.ref.WeakReference<android.view.ViewGroup> contentsRef;
        final float textSize;
        final float translationX;
        final float translationY;
        final int[] labelPadding;
        final int[] contentsPadding;

        SubtitleState(android.widget.TextView label, android.view.ViewGroup contents) {
            this(label, contents, label.getTextSize(), label.getTranslationX(),
                    label.getTranslationY(), paddingOf(label), paddingOf(contents));
        }

        SubtitleState(android.widget.TextView label, android.view.ViewGroup contents,
                float textSize, float translationX, float translationY,
                int[] labelPadding, int[] contentsPadding) {
            this.labelRef = new java.lang.ref.WeakReference<>(label);
            this.contentsRef = new java.lang.ref.WeakReference<>(contents);
            this.textSize = textSize;
            this.translationX = translationX;
            this.translationY = translationY;
            this.labelPadding = labelPadding;
            this.contentsPadding = contentsPadding;
        }
    }

    private static final java.util.Map<Object, SubtitleState> sSubtitleStates =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Object, SubtitleState>());

    private static int[] paddingOf(android.view.View view) {
        if (view == null) return null;
        return new int[] { view.getPaddingStart(), view.getPaddingTop(),
                view.getPaddingEnd(), view.getPaddingBottom() };
    }

    private static void setPadding(android.view.View view, int[] padding) {
        if (view == null || padding == null || padding.length < 4) return;
        view.setPaddingRelative(padding[0], padding[1], padding[2], padding[3]);
    }

    private static void applyNotificationSubtitle(Object owner, android.widget.TextView label,
            android.view.ViewGroup contents, float fontSizePx, float offsetPx, int padPx) {
        SubtitleState state;
        synchronized (sSubtitleStates) {
            state = sSubtitleStates.get(owner);
            if (state == null || state.labelRef.get() != label
                    || state.contentsRef.get() != contents) {
                state = new SubtitleState(label, contents);
                sSubtitleStates.put(owner, state);
            }
        }
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx);
        label.setTranslationX(offsetPx);
        label.setTranslationY(0f);
        label.setPaddingRelative(state.labelPadding[0], state.labelPadding[1] + padPx,
                state.labelPadding[2], state.labelPadding[3] + padPx);
        if (contents != null && state.contentsPadding != null) {
            contents.setPaddingRelative(state.contentsPadding[0],
                    Math.max(0, state.contentsPadding[1] - (int) offsetPx),
                    state.contentsPadding[2], state.contentsPadding[3]);
        }
    }

    private static final java.util.Set<android.view.View> sMutatedNotificationViews =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<android.view.View, Boolean>()));

    // 由 onNotificationUpdated 刷新，异步模板绑定时复用。
    static volatile boolean sNotifPadEnabled = false;

    static volatile int sNotifPadPx = 0;

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

            String contentClass = "com.android.systemui.statusbar.notification.row.NotificationContentView";
            XC_MethodHook bindPadding = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object row = XposedHelpers.getObjectField(param.thisObject, "mContainingNotification");
                        boolean minimized = !sNotifPadEnabled || (row != null
                                && XposedHelpers.getBooleanField(row, "mIsMinimized"));
                        applyNotificationChildPadding(param.thisObject, minimized, sNotifPadPx);
                    } catch (Throwable error) { log("notification template padding failed: " + error); }
                }
            };
            for (String method : new String[]{"setContractedChild", "setExpandedChild", "setHeadsUpChild"}) {
                XposedHelpers.findAndHookMethod(contentClass, lpparam.classLoader, method,
                        android.view.View.class, bindPadding);
            }
            XposedHelpers.findAndHookMethod(contentClass, lpparam.classLoader, "setSingleLineView",
                    XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.HybridNotificationView", lpparam.classLoader), bindPadding);
            XposedHelpers.findAndHookMethod(contentClass, lpparam.classLoader,
                    "updateAllSingleLineViews", bindPadding);
            XposedHelpers.findAndHookMethod(contentClass, lpparam.classLoader,
                    "setIsChildInGroup", boolean.class, bindPadding);
            NotificationChildPaddingMeasureHooks.hook(lpparam);
            NotificationGroupPaddingHooks.hook(lpparam);

            log("HOOK OK notification full and hybrid template padding");
        } catch (Throwable t) {
            log("HOOK FAIL ExpandableNotificationRow#onNotificationUpdated :: " + t);
        }
    }

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

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper",
                    lpparam.classLoader, "handleMenuRowSwipe", MotionEvent.class, View.class,
                    float.class, "com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_NOTIFICATION_SWIPE_TO_DISMISS_ENABLED, false)) return;
                                View row = (View) param.args[1];
                                if (!isExpandableNotificationRow(row)) return;
                                float velocityX = ((Number) param.args[2]).floatValue();
                                // 右滑完整保留系统原有逻辑；左滑仅补齐同口径的速度清除路径。
                                if (velocityX >= 0.0f) return;
                                float escapeVelocity = ((Number) XposedHelpers.callMethod(
                                        param.thisObject, "getEscapeVelocity")).floatValue();
                                if (velocityX < -escapeVelocity) {
                                    XposedHelpers.callMethod(param.thisObject, "dismiss", row, velocityX);
                                } else {
                                    XposedHelpers.callMethod(param.thisObject, "snapClosed", row,
                                            velocityX);
                                    XposedHelpers.callMethod(param.args[3], "onSnapClosed");
                                }
                                param.setResult(null);
                            } catch (Throwable t) {
                                log("notification_swipe_to_dismiss handleMenuRowSwipe fail: " + t);
                            }
                        }
                    });
            log("HOOK OK NotificationSwipeHelper#handleMenuRowSwipe (left velocity)");
        } catch (Throwable t) {
            log("HOOK FAIL NotificationSwipeHelper#handleMenuRowSwipe :: "
                    + Log.getStackTraceString(t));
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.notification.row.NotificationMenuRowExtImpl",
                    lpparam.classLoader, "performHapticFeedback", View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_NOTIFICATION_SWIPE_TO_DISMISS_ENABLED, false)) return;
                            try {

                                Object row = XposedHelpers.callMethod(
                                        param.thisObject, "getMenuRowParent");
                                float translation = 0.0f;
                                try {
                                    Object value = XposedHelpers.callMethod(row, "getTranslation");
                                    if (value instanceof Number) {
                                        translation = ((Number) value).floatValue();
                                    }
                                } catch (Throwable ignored) {
                                }
                                if (translation < 0.0f
                                        || (row instanceof View
                                                && ((View) row).getTranslationX() < 0.0f)) {
                                    param.setResult(null);
                                    log("notification_swipe_to_dismiss haptic blocked: " + translation);
                                }
                            } catch (Throwable t) {
                                log("notification_swipe_to_dismiss haptic fail: " + t);
                            }
                        }
                    });
            log("HOOK OK NotificationMenuRowExtImpl#performHapticFeedback (swipe dismiss)");
        } catch (Throwable t) {
            log("HOOK FAIL NotificationMenuRowExtImpl#performHapticFeedback :: "
                    + Log.getStackTraceString(t));
        }
    }

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

    private static final String CLS_ICON_AREA_REPOSITORY =
            "com.oplus.systemui.statusbar.icon.data.OplusNotificationIconAreaRepository";
    private static final String CLS_LEGACY_ICON_AREA_CONTROLLER =
            "com.oplus.systemui.statusbar.phone.OplusNotificationIconAreaController";

    private static volatile Object sIconAreaRepository = null;

    private static volatile boolean sLyricNumberMode = false;

    private static final long SHOW_MODE_APPLY_INTERVAL_MS = 500L;
    private static volatile boolean sShowModeLoopStarted = false;
    private static volatile boolean sShowModeLoopActive = false;
    private static Handler sShowModeHandler;

    public static void hookNotificationIconAreaRepository(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> repoCls = XposedHelpers.findClassIfExists(
                    CLS_ICON_AREA_REPOSITORY, lpparam.classLoader);
            if (repoCls != null) {
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
            } else {
                Class<?> legacyController = XposedHelpers.findClass(
                        CLS_LEGACY_ICON_AREA_CONTROLLER, lpparam.classLoader);
                XposedBridge.hookAllConstructors(legacyController, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sIconAreaRepository = param.thisObject;
                        startShowModeLoop();
                    }
                });
                log("HOOK OK OplusNotificationIconAreaController#<init> (notification show mode)");
            }
        } catch (Throwable t) {
            log("HOOK FAIL notification icon area show mode :: "
                    + Log.getStackTraceString(t));
        }
    }

    private static void startShowModeLoop() {
        if (sShowModeLoopStarted) return;
        sShowModeLoopStarted = true;
        sShowModeLoopActive = true;
        final Handler handler = new Handler(Looper.getMainLooper());
        sShowModeHandler = handler;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    applyNotificationShowMode();
                } catch (Throwable t) {
                    log("notification show mode loop fail: " + t);
                }
                if (sShowModeLoopActive) {
                    handler.postDelayed(this, SHOW_MODE_APPLY_INTERVAL_MS);
                }
            }
        }, SHOW_MODE_APPLY_INTERVAL_MS);
    }

    public static Object captureHotReloadHost() {
        return sIconAreaRepository;
    }

    public static Object captureHotReloadViews() {
        java.util.ArrayList<Object> padded = new java.util.ArrayList<>();
        java.util.ArrayList<Object> translated = new java.util.ArrayList<>();
        java.util.ArrayList<Object> subtitles = new java.util.ArrayList<>();
        synchronized (sMutatedNotificationViews) {
            for (android.view.View view : sMutatedNotificationViews) {
                Object top = view.getTag(TAG_NOTIF_PAD_TOP);
                Object bottom = view.getTag(TAG_NOTIF_PAD_BOTTOM);
                if (top instanceof Integer && bottom instanceof Integer
                        && (view.getPaddingTop() != (Integer) top
                                || view.getPaddingBottom() != (Integer) bottom)) {
                    padded.add(view);
                }
                Object translation = view.getTag(TAG_NOTIF_GROUP_HEADER_TRANSLATION);
                if (translation instanceof Float
                        && view.getTranslationY() != (Float) translation) {
                    translated.add(view);
                }
            }
        }
        synchronized (sSubtitleStates) {
            for (java.util.Map.Entry<Object, SubtitleState> entry : sSubtitleStates.entrySet()) {
                SubtitleState subtitle = entry.getValue();
                android.widget.TextView label = subtitle.labelRef.get();
                android.view.ViewGroup contents = subtitle.contentsRef.get();
                if (label == null) continue;
                subtitles.add(new Object[] { entry.getKey(), label, contents,
                        new float[] { subtitle.textSize, subtitle.translationX,
                                subtitle.translationY, label.getTextSize(),
                                label.getTranslationX(), label.getTranslationY() },
                        subtitle.labelPadding, subtitle.contentsPadding,
                        paddingOf(label), paddingOf(contents) });
            }
        }
        return new Object[] { padded.toArray(), translated.toArray(),
                Boolean.valueOf(sNotifPadEnabled), Integer.valueOf(sNotifPadPx),
                subtitles.toArray(), MonochromeNotificationHooks.capture() };
    }

    public static void restoreHotReloadViews(Object saved) {
        if (!(saved instanceof Object[])) return;
        Object[] state = (Object[]) saved;
        if (state.length < 4 || !(state[2] instanceof Boolean) || !(state[3] instanceof Integer)) {
            return;
        }
        sNotifPadEnabled = (Boolean) state[2];
        sNotifPadPx = (Integer) state[3];
        if (state.length > 5) MonochromeNotificationHooks.restore(state[5]);
        if (state.length > 4 && state[4] instanceof Object[]) {
            for (Object object : (Object[]) state[4]) {
                if (!(object instanceof Object[])) continue;
                Object[] subtitle = (Object[]) object;
                if (subtitle.length < 8 || !(subtitle[1] instanceof android.widget.TextView)
                        || !(subtitle[3] instanceof float[])
                        || !(subtitle[4] instanceof int[]) || !(subtitle[6] instanceof int[])) {
                    continue;
                }
                try {
                    Object owner = subtitle[0];
                    android.widget.TextView label = (android.widget.TextView) subtitle[1];
                    android.view.ViewGroup contents = subtitle[2] instanceof android.view.ViewGroup
                            ? (android.view.ViewGroup) subtitle[2] : null;
                    float[] values = (float[]) subtitle[3];
                    int[] originalLabelPadding = (int[]) subtitle[4];
                    int[] originalContentsPadding = subtitle[5] instanceof int[]
                            ? (int[]) subtitle[5] : null;
                    int[] appliedLabelPadding = (int[]) subtitle[6];
                    int[] appliedContentsPadding = subtitle[7] instanceof int[]
                            ? (int[]) subtitle[7] : null;
                    if (values.length < 6 || owner == null) continue;
                    sSubtitleStates.put(owner, new SubtitleState(label, contents,
                            values[0], values[1], values[2], originalLabelPadding,
                            originalContentsPadding));
                    label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, values[3]);
                    label.setTranslationX(values[4]);
                    label.setTranslationY(values[5]);
                    setPadding(label, appliedLabelPadding);
                    setPadding(contents, appliedContentsPadding);
                } catch (Throwable t) {
                    log("notification subtitle restore failed: " + t);
                }
            }
        }
        if (!sNotifPadEnabled) return;
        if (state[0] instanceof Object[]) {
            for (Object object : (Object[]) state[0]) {
                if (!(object instanceof android.view.View)) continue;
                android.view.View view = (android.view.View) object;
                view.setTag(TAG_NOTIF_PAD_TOP, Integer.valueOf(view.getPaddingTop()));
                view.setTag(TAG_NOTIF_PAD_BOTTOM, Integer.valueOf(view.getPaddingBottom()));
                sMutatedNotificationViews.add(view);
                view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop() + sNotifPadPx,
                        view.getPaddingEnd(), view.getPaddingBottom() + sNotifPadPx);
            }
        }

    }

    public static void restoreHotReloadHost(Object host) {
        if (host == null || (!CLS_ICON_AREA_REPOSITORY.equals(host.getClass().getName())
                && !CLS_LEGACY_ICON_AREA_CONTROLLER.equals(host.getClass().getName()))) return;
        sIconAreaRepository = host;
        startShowModeLoop();
    }

    public static void cleanupForHotReload() {
        SystemNotificationGroupHooks.cleanup();
        MonochromeNotificationHooks.cleanup();
        sShowModeLoopActive = false;
        Handler handler = sShowModeHandler;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        sShowModeHandler = null;
        sShowModeLoopStarted = false;
        sIconAreaRepository = null;
        sLyricNumberMode = false;
        synchronized (sSubtitleStates) {
            for (SubtitleState subtitle :
                    new java.util.ArrayList<SubtitleState>(sSubtitleStates.values())) {
                try {
                    android.widget.TextView label = subtitle.labelRef.get();
                    android.view.ViewGroup contents = subtitle.contentsRef.get();
                    if (label == null) continue;
                    label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                            subtitle.textSize);
                    label.setTranslationX(subtitle.translationX);
                    label.setTranslationY(subtitle.translationY);
                    setPadding(label, subtitle.labelPadding);
                    setPadding(contents, subtitle.contentsPadding);
                } catch (Throwable t) {
                    log("notification subtitle cleanup failed: " + t);
                }
            }
            sSubtitleStates.clear();
        }
        synchronized (sMutatedNotificationViews) {
            for (android.view.View view :
                    new java.util.ArrayList<>(sMutatedNotificationViews)) {
                try {
                    Object top = view.getTag(TAG_NOTIF_PAD_TOP);
                    Object bottom = view.getTag(TAG_NOTIF_PAD_BOTTOM);
                    if (top instanceof Integer && bottom instanceof Integer) {
                        view.setPaddingRelative(view.getPaddingStart(), (Integer) top,
                                view.getPaddingEnd(), (Integer) bottom);
                        view.setTag(TAG_NOTIF_PAD_TOP, null);
                        view.setTag(TAG_NOTIF_PAD_BOTTOM, null);
                    }
                    Object translation = view.getTag(TAG_NOTIF_GROUP_HEADER_TRANSLATION);
                    if (translation instanceof Float) {
                        view.setTranslationY((Float) translation);
                        view.setTag(TAG_NOTIF_GROUP_HEADER_TRANSLATION, null);
                    }
                } catch (Throwable t) {
                    log("notification padding cleanup failed: " + t);
                }
            }
            sMutatedNotificationViews.clear();
        }
        sNotifPadEnabled = false;
        sNotifPadPx = 0;
    }

    static void applyNotificationShowMode() {
        Object repo = sIconAreaRepository;
        if (repo == null) return;
        // 歌词显示期间强制"显示数字", 否则还原成系统设置里的值。
        int desired = sLyricNumberMode
                ? NOTIFICATION_PROMPT_SHOW_NUMBER : readNotificationPromptMode();
        Object mode = XposedHelpers.getObjectField(repo, "notificationShowMode");
        if (mode instanceof Number) {
            if (((Number) mode).intValue() != desired) {
                XposedHelpers.setIntField(repo, "notificationShowMode", desired);
                XposedHelpers.callMethod(repo, "updateStatusBarIcons");
                log("notification show mode -> " + desired);
            }
            return;
        }
        Object flow = mode;
        if (flow == null) return;
        Object current = XposedHelpers.callMethod(flow, "getValue");
        if (current instanceof Integer && ((Integer) current).intValue() == desired) return;
        XposedHelpers.callMethod(flow, "setValue", Integer.valueOf(desired));
        log("notification show mode -> " + desired);
    }

    public static void setLyricNumberMode(boolean on) {
        sLyricNumberMode = on;
        applyNotificationShowMode();
    }

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

    static boolean isMinimizedGroup(Object container) {
        try {
            Object row = XposedHelpers.getObjectField(container, "mContainingNotification");
            return row != null && XposedHelpers.getBooleanField(row, "mIsMinimized");
        } catch (Throwable t) {
            return false;
        }
    }

    static void applyNotificationChildPadding(Object contentView, boolean minimized, int padPx) {
        if (!(contentView instanceof android.view.ViewGroup)) return;
        boolean grouped = XposedHelpers.getBooleanField(contentView, "mIsChildInGroup");
        String[] fields = { "mContractedChild", "mExpandedChild", "mHeadsUpChild", "mSingleLineView" };
        for (String f : fields) {
            Object child = XposedHelpers.getObjectField(contentView, f);
            if (!(child instanceof android.view.View)) continue;
            android.view.View v = (android.view.View) child;
            Integer origTop = (Integer) v.getTag(TAG_NOTIF_PAD_TOP);
            Integer origBottom = (Integer) v.getTag(TAG_NOTIF_PAD_BOTTOM);
            if (origTop == null) { origTop = v.getPaddingTop(); v.setTag(TAG_NOTIF_PAD_TOP, origTop); }
            if (origBottom == null) { origBottom = v.getPaddingBottom(); v.setTag(TAG_NOTIF_PAD_BOTTOM, origBottom); }
            sMutatedNotificationViews.add(v);
            boolean omitPadding = minimized || (grouped && "mSingleLineView".equals(f));
            int top = omitPadding ? origTop : origTop + padPx;
            int bottom = omitPadding ? origBottom : origBottom + padPx;
            if (v.getPaddingTop() != top || v.getPaddingBottom() != bottom) {
                v.setPaddingRelative(v.getPaddingStart(), top, v.getPaddingEnd(), bottom);
            }
        }
    }
}
