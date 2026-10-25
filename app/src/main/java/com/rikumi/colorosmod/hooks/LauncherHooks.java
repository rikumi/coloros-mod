package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.animation.ObjectAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.Property;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.TextSwitcher;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

public final class LauncherHooks {
    // 修改安全中心"隐藏应用"对电话本的处理: 系统原生是整包禁用(会连拨号一起失效), 这里让
    private static final Object sHiddenSettingsLock = new Object();
    private static boolean sHiddenSettingsInitialized;
    private static boolean sNeedsInitialModelReload = true;
    private static boolean sInitialModelReloadPending;
    private static java.util.Set<android.content.ComponentName> sLastHiddenComponents = java.util.Collections.emptySet();

    public static void onSettingsSnapshotPublished(java.util.Map<String, Integer> settings) {
        DrawerInstallTimeHooks.onSettingsSnapshotPublished(settings);
        refreshPowerSaveTaskLocks(settingValue(settings, KEY_POWER_SAVE_KEEP_LOCKED_TASKS_ENABLED) == 1);
        if (!sModelReloadActive) return;
        java.util.Set<android.content.ComponentName> components = hiddenLauncherComponents(settings);
        boolean waitForInitialLoad;
        synchronized (sHiddenSettingsLock) {
            boolean initialized = sHiddenSettingsInitialized;
            if (initialized && components.equals(sLastHiddenComponents)) return;
            sHiddenSettingsInitialized = true;
            sLastHiddenComponents = components;
            if (!initialized && (!sNeedsInitialModelReload || components.isEmpty())) {
                sNeedsInitialModelReload = false;
                return;
            }
            waitForInitialLoad = !initialized;
            if (waitForInitialLoad) sInitialModelReloadPending = true;
            sNeedsInitialModelReload = false;
            // 与设置值在同一把锁内计数, 使 captureHotReloadSettings 看到一致的"值 + 是否待刷新"。
            sPendingModelReloads++;
        }

        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        reloadLauncherModelWhenReady(main, 0, waitForInitialLoad);
    }

    // hot reload: 旧 generation 的刷新 runnable 统一带此 token 投递, cleanup 时一次性移除。
    private static final Object sModelReloadToken = new Object();
    // hot reload cleanup 后置 false, 已出队的 runnable 与迟到的 snapshot 都不再触碰宿主。
    private static volatile boolean sModelReloadActive = true;
    // 尚未结束(成功/放弃/出错)的刷新链数量; 受 sHiddenSettingsLock 保护。
    private static int sPendingModelReloads;

    private static void finishModelReload() {
        synchronized (sHiddenSettingsLock) {
            if (sPendingModelReloads > 0) sPendingModelReloads--;
        }
    }

    private static void reloadLauncherModelWhenReady(final android.os.Handler main,
            final int attempt, final boolean waitForInitialLoad) {
        Runnable reload = new Runnable() {
            @Override
            public void run() {
                if (!sModelReloadActive) return;
                try {
                    ClassLoader classLoader = sLauncherClassLoader;
                    if (classLoader == null) {
                        retryLauncherModelReload(main, attempt, waitForInitialLoad);
                        return;
                    }
                    Class<?> appStateClass = XposedHelpers.findClass(
                            "com.android.launcher3.LauncherAppState", classLoader);
                    Object appState = XposedHelpers.callStaticMethod(
                            appStateClass, "getInstanceNoCreate");
                    if (appState == null) {
                        retryLauncherModelReload(main, attempt, waitForInitialLoad);
                        return;
                    }
                    Object model = XposedHelpers.callMethod(appState, "getModel");
                    if (model == null) {
                        retryLauncherModelReload(main, attempt, waitForInitialLoad);
                        return;
                    }
                    if (waitForInitialLoad && !Boolean.TRUE.equals(XposedHelpers.callMethod(model, "isModelLoaded"))) {
                        retryLauncherModelReload(main, attempt, true);
                        return;
                    }
                    if (waitForInitialLoad) {
                        synchronized (sHiddenSettingsLock) {
                            if (!sInitialModelReloadPending) { finishModelReload(); return; }
                            sInitialModelReloadPending = false;
                        }
                    }
                    XposedHelpers.callMethod(model, "forceReload");
                    finishModelReload();
                } catch (Throwable t) {
                    finishModelReload();
                    log("hide launcher apps refresh error: " + t);
                }
            }
        };
        long delay = attempt == 0 ? 0 : 300;
        if (!main.postAtTime(reload, sModelReloadToken,
                android.os.SystemClock.uptimeMillis() + delay)) {
            finishModelReload();
        }
    }

    private static void retryLauncherModelReload(android.os.Handler main, int attempt, boolean waitForInitialLoad) {
        if (attempt < (waitForInitialLoad ? 100 : 10)) {
            reloadLauncherModelWhenReady(main, attempt + 1, waitForInitialLoad);
        } else {
            finishModelReload();
            log("hide launcher apps model readiness timeout, initial=" + waitForInitialLoad);
        }
    }

    public static Object captureHotReloadSettings() {
        synchronized (sHiddenSettingsLock) {
            if (!sHiddenSettingsInitialized || sPendingModelReloads > 0 || sInitialModelReloadPending) return null;
            java.util.ArrayList<String> components = new java.util.ArrayList<>();
            for (android.content.ComponentName component : sLastHiddenComponents)
                components.add(component.flattenToString());
            return components.toArray(new String[0]);
        }
    }

    public static void restoreHotReloadSettings(Object saved) {
        synchronized (sHiddenSettingsLock) {
            if (!(saved instanceof String[])) {
                sNeedsInitialModelReload = true;
                return;
            }
            if (sHiddenSettingsInitialized) return;
            java.util.Set<android.content.ComponentName> components = new java.util.HashSet<>();
            for (String value : (String[]) saved) {
                android.content.ComponentName component = android.content.ComponentName.unflattenFromString(value);
                if (component != null) components.add(component);
            }
            sHiddenSettingsInitialized = true;
            sLastHiddenComponents = components;
        }
    }

    private static int settingValue(java.util.Map<String, Integer> settings, String key) {
        Integer value = settings.get(key);
        return value != null && value.intValue() == 1 ? 1 : 0;
    }

    static volatile Class<?> sLauncherClass;

    static volatile ClassLoader sLauncherClassLoader;

    static volatile Class<?> sLauncherStateClass;

    static volatile Object sNormalState;

    static volatile Object sBackgroundAppState;

    static volatile Object sOverviewState;

    // 多任务背景遮罩关闭后，用极浅白色边框保持深色壁纸上任务卡片的可分辨性。
    private static final int RECENTS_TASK_BORDER_COLOR = 0x1AFFFFFF;
    private static final float RECENTS_TASK_BORDER_WIDTH_DP = 1f;
    private static final Paint sRecentsTaskBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final String EXTRA_RECENTS_BLEND = "recentsBlendColor";
    private static final float[] RECENTS_CLEAR_BLEND = new float[]{0f, 0f, 0f, 0f};

    private static final class DrawerIconState {
        int paddingLeft;
        int paddingTop;
        int paddingRight;
        int paddingBottom;
        int iconSize = -1;
        int layoutHeight = Integer.MIN_VALUE;
        float textSize;

        DrawerIconState(android.view.View view) {
            paddingLeft = view.getPaddingLeft();
            paddingTop = view.getPaddingTop();
            paddingRight = view.getPaddingRight();
            paddingBottom = view.getPaddingBottom();
            if (view instanceof android.widget.TextView) {
                textSize = ((android.widget.TextView) view).getTextSize();
            }
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) layoutHeight = lp.height;
        }
    }

    private static Class<?> sDrawerBubbleTextViewClass;
    private static Class<?> sDrawerPagedViewClass;
    private static Class<?> sDrawerContainerClass;

    private static final java.util.Map<Object, DrawerIconState> sDrawerIconStates =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Object, DrawerIconState>());

    private static final java.util.Map<android.view.View, int[]> sDrawerPaddingStates =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<android.view.View, int[]>());

    static volatile Class<?> sPopupContainerClass = null;

    public static void hookPopupMenuDimens(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> popupClass = XposedHelpers.findClass(
                    "com.android.launcher3.popup.OplusPopupContainerWithArrow",
                    lpparam.classLoader);
            sPopupContainerClass = popupClass;
            // 限定本类声明: 上溯到 android.view.View 会让 hook 对 Launcher 内所有 View 生效。
            XposedHelpers.findAndHookDeclaredMethod(popupClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_SHRINK_POPUP_MENU, false)) {
                                return;
                            }
                            android.view.View v = (android.view.View) param.thisObject;
                            int pct = Math.max(0, Math.min(20,
                                    readInt(KEY_POPUP_SCALE_PERCENT, POPUP_SHRINK_PERCENT_DEFAULT)));
                            Object applied = XposedHelpers.getAdditionalInstanceField(v, "colorosmod_popup_pct");
                            if (applied instanceof Integer && (Integer) applied == pct) {
                                return;
                            }
                            XposedHelpers.setAdditionalInstanceField(v, "colorosmod_popup_pct", pct);
                            postOneShotTrackedCallback(v,
                                    "colorosmod_popup_scale_callback",
                                    () -> scalePopupContainer(v));
                        }
                    });

            try {
                final Class<?> subPopupClass = XposedHelpers.findClass(
                        "com.android.launcher3.popup.MoreFunctionsPopupListWindow",
                        lpparam.classLoader);
                XposedBridge.hookAllConstructors(subPopupClass,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!readBool(KEY_SHRINK_POPUP_MENU, false)) {
                                    return;
                                }
                                try {
                                    Object list = XposedHelpers.callMethod(
                                            param.thisObject, "getSubMenuListView");
                                    if (!(list instanceof android.view.View)) {
                                        return;
                                    }
                                    android.view.View content = (android.view.View) list;
                                    android.view.ViewParent parent = content.getParent();
                                    if (!(parent instanceof android.view.View)) {
                                        return;
                                    }
                                    android.view.View wrapper = (android.view.View) parent;
                                    postOneShotTrackedCallback(wrapper,
                                            "colorosmod_popup_submenu_callback",
                                            () -> scalePopupSubMenu(wrapper, content));
                                } catch (Throwable t) {
                                    log("scale popup submenu failed: " + t);
                                }
                            }
                        });
                log("hooked popup submenu scaling");
            } catch (Throwable t) {
                log("hook popup submenu failed: " + t);
            }
            log("hooked popup menu scaling");
        } catch (Throwable t) {
            log("hook popup menu container failed: " + t);
        }
    }

    private static void postOneShotTrackedCallback(android.view.View owner, String key,
            Runnable action) {
        final Runnable[] holder = new Runnable[1];
        Runnable callback = new Runnable() {
            @Override public void run() {
                try {
                    action.run();
                } finally {
                    if (XposedHelpers.getAdditionalInstanceField(owner, key) == holder[0]) {
                        XposedHelpers.removeAdditionalInstanceField(owner, key);
                    }
                }
            }
        };
        holder[0] = callback;
        XposedHelpers.setAdditionalInstanceField(owner, key, callback);
        if (!owner.post(callback)
                && XposedHelpers.getAdditionalInstanceField(owner, key) == callback) {
            XposedHelpers.removeAdditionalInstanceField(owner, key);
        }
    }

    static void scalePopupContainer(android.view.View popupContainer) {
        if (!readBool(KEY_SHRINK_POPUP_MENU, false)) {
            return;
        }
        android.view.View target;
        try {
            Object inner = XposedHelpers.getObjectField(popupContainer, "mAllPopupShortcutContainer");
            target = (inner instanceof android.view.View) ? (android.view.View) inner : popupContainer;
        } catch (Throwable t) {
            target = popupContainer; // 兜底: 直接缩放整个 popup 容器
        }
        if (target == null) {
            return;
        }
        scalePopupTarget(popupContainer, target);
    }

    static void scalePopupTarget(android.view.View popupContainer, android.view.View target) {
        if (!readBool(KEY_SHRINK_POPUP_MENU, false)) {
            return;
        }
        int pct = Math.max(0, Math.min(20,
                readInt(KEY_POPUP_SCALE_PERCENT, POPUP_SHRINK_PERCENT_DEFAULT)));
        float scale = 1f - pct / 100f;
        int w = target.getWidth();
        int h = target.getHeight();

        boolean above = false;
        float pivotX = w / 2.0f;
        float pivotY = h / 2.0f;
        try {
            above = XposedHelpers.getBooleanField(popupContainer, "mIsAboveIcon");
        } catch (Throwable ignored) {
        }
        try {
            Object pxObj = XposedHelpers.callMethod(popupContainer, "calculatePivotX");
            float pxOuter = ((Number) pxObj).floatValue();
            int[] outer = new int[2];
            int[] inner = new int[2];
            popupContainer.getLocationOnScreen(outer);
            target.getLocationOnScreen(inner);
            float offX = inner[0] - outer[0];
            float offY = inner[1] - outer[1];
            pivotX = pxOuter - offX;
            pivotY = above ? (h - offY) : (0 - offY);
        } catch (Throwable t) {
            log("popup pivot calc failed, using fallback: " + t);
            pivotX = w / 2.0f;
            pivotY = above ? h : 0.0f;
        }
        target.setPivotX(pivotX);
        target.setPivotY(pivotY);
        target.setScaleX(scale);
        target.setScaleY(scale);
        fixPopupDividerThickness(target, scale);
        log("popup menu scaled: scale=" + scale + " w=" + w + " h=" + h + " pivotX=" + pivotX
                + " pivotY=" + pivotY + " above=" + above);
    }

    static void scalePopupSubMenu(android.view.View wrapper, android.view.View content) {
        if (!readBool(KEY_SHRINK_POPUP_MENU, false)) {
            return;
        }
        int pct = Math.max(0, Math.min(20,
                readInt(KEY_POPUP_SCALE_PERCENT, POPUP_SHRINK_PERCENT_DEFAULT)));
        float scale = 1f - pct / 100f;
        float pivotX = 0f;
        try {
            android.graphics.drawable.Drawable background = wrapper.getBackground();
            if (background != null) {
                android.graphics.drawable.Drawable.ConstantState state = background.getConstantState();
                content.setBackground(state == null
                        ? background.mutate()
                        : state.newDrawable(wrapper.getResources()).mutate());
                android.view.ViewOutlineProvider outlineProvider = wrapper.getOutlineProvider();
                content.setOutlineProvider(outlineProvider);
                content.setClipToOutline(true);
                syncPopupSubMenuOutline(wrapper, content, outlineProvider);

                wrapper.setBackground(new android.graphics.drawable.ColorDrawable(
                        android.graphics.Color.TRANSPARENT));
            }
        } catch (Throwable t) {
            log("popup submenu scale failed: " + t);
            pivotX = content.getWidth() / 2f;
        }
        content.setPivotX(pivotX);
        content.setPivotY(0f);
        content.setScaleX(scale);
        content.setScaleY(scale);
        fixPopupDividerThickness(content, scale);
        log("popup submenu scaled: scale=" + scale + " w=" + content.getWidth()
                + " h=" + content.getHeight() + " pivotX=" + pivotX + " pivotY=0.0");
    }

    static void syncPopupSubMenuOutline(android.view.View wrapper, android.view.View content,
                                        android.view.ViewOutlineProvider outlineProvider) {
        final java.lang.ref.WeakReference<android.view.View> wrapperRef =
                new java.lang.ref.WeakReference<>(wrapper);
        final java.lang.ref.WeakReference<android.view.View> contentRef =
                new java.lang.ref.WeakReference<>(content);
        android.view.ViewTreeObserver.OnPreDrawListener listener =
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    final android.graphics.Outline outline = new android.graphics.Outline();
                    final android.graphics.Rect currentRect = new android.graphics.Rect();
                    final android.graphics.Rect previousRect = new android.graphics.Rect();
                    boolean hadRect;
                    int previousAlpha = Integer.MIN_VALUE;

                    @Override
                    public boolean onPreDraw() {
                        android.view.View currentWrapper = wrapperRef.get();
                        android.view.View currentContent = contentRef.get();
                        if (currentWrapper == null || currentContent == null) return true;
                        outline.setEmpty();
                        outlineProvider.getOutline(currentWrapper, outline);
                        boolean hasRect = outline.getRect(currentRect);
                        int alpha = Float.floatToIntBits(outline.getAlpha());
                        if (hasRect != hadRect || (hasRect && !currentRect.equals(previousRect))
                                || alpha != previousAlpha) {
                            hadRect = hasRect;
                            previousRect.set(currentRect);
                            previousAlpha = alpha;
                            currentContent.invalidateOutline();
                        }
                        return true;
                    }
                };
        XposedHelpers.setAdditionalInstanceField(content,
                "colorosmod_launcher_predraw_wrapper", wrapperRef);
        trackLauncherPreDraw(content, listener);
    }

    // 抽屉和菜单共用跟踪/卸载机制，避免 detach 或热重载后保留旧回调。
    static void trackLauncherPreDraw(android.view.View content,
                                    android.view.ViewTreeObserver.OnPreDrawListener listener) {
        content.getViewTreeObserver().addOnPreDrawListener(listener);
        XposedHelpers.setAdditionalInstanceField(content,
                "colorosmod_launcher_predraw", listener);
        android.view.View.OnAttachStateChangeListener detachListener =
                new android.view.View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(android.view.View view) { }

                    @Override public void onViewDetachedFromWindow(android.view.View view) {
                        Object tracked = XposedHelpers.getAdditionalInstanceField(
                                view, "colorosmod_launcher_predraw");
                        if (tracked == listener) {
                            android.view.ViewTreeObserver observer = view.getViewTreeObserver();
                            if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
                            XposedHelpers.removeAdditionalInstanceField(
                                    view, "colorosmod_launcher_predraw");
                            XposedHelpers.removeAdditionalInstanceField(
                                    view, "colorosmod_launcher_predraw_wrapper");
                            XposedHelpers.removeAdditionalInstanceField(
                                    view, "colorosmod_drawer_layout_active");
                        }
                        if (XposedHelpers.getAdditionalInstanceField(
                                view, "colorosmod_launcher_predraw_detach") == this) {
                            XposedHelpers.removeAdditionalInstanceField(
                                    view, "colorosmod_launcher_predraw_detach");
                        }
                        view.removeOnAttachStateChangeListener(this);
                    }
                };
        content.addOnAttachStateChangeListener(detachListener);
        XposedHelpers.setAdditionalInstanceField(content,
                "colorosmod_launcher_predraw_detach", detachListener);
    }

    public static Object captureHotReloadHosts() {
        java.util.ArrayList<Object> saved = new java.util.ArrayList<>();
        synchronized (sDrawerIconStates) {
            for (java.util.Map.Entry<Object, DrawerIconState> entry : sDrawerIconStates.entrySet()) {
                Object owner = entry.getKey();
                if (!(owner instanceof android.view.View)) continue;
                android.view.View view = (android.view.View) owner;
                DrawerIconState baseline = entry.getValue();
                int iconSize = -1;
                try {
                    Object size = XposedHelpers.callMethod(owner, "getIconSize");
                    if (size instanceof Integer) iconSize = (Integer) size;
                } catch (Throwable ignored) {
                }
                android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
                int height = lp == null ? Integer.MIN_VALUE : lp.height;
                saved.add(new Object[] { owner,
                        Integer.valueOf(view.getPaddingLeft()), Integer.valueOf(view.getPaddingTop()),
                        Integer.valueOf(view.getPaddingRight()), Integer.valueOf(view.getPaddingBottom()),
                        Integer.valueOf(iconSize), Integer.valueOf(height),
                        Integer.valueOf(baseline.iconSize), Integer.valueOf(baseline.layoutHeight),
                        Float.valueOf(baseline.textSize), Integer.valueOf(baseline.paddingLeft),
                        Integer.valueOf(baseline.paddingTop), Integer.valueOf(baseline.paddingRight),
                        Integer.valueOf(baseline.paddingBottom),
                        Float.valueOf(((android.widget.TextView) view).getTextSize()) });
            }
        }
        synchronized (sDrawerPaddingStates) {
            for (java.util.Map.Entry<android.view.View, int[]> entry : sDrawerPaddingStates.entrySet()) {
                saved.add(new Object[] { "drawerPadding", entry.getKey(),
                        Integer.valueOf(entry.getValue()[0]),
                        Integer.valueOf(entry.getKey().getPaddingLeft()) });
            }
        }
        XposedHelpers.forEachTrackedOwner("colorosmod_launcher_predraw",
                new XposedHelpers.TrackedOwnerConsumer() {
                    @Override public void accept(Object owner) {
                        if (!(owner instanceof android.view.View)) return;
                        Object wrapperValue = XposedHelpers.getAdditionalInstanceField(
                                owner, "colorosmod_launcher_predraw_wrapper");
                        if (!(wrapperValue instanceof java.lang.ref.WeakReference)) return;
                        Object wrapper = ((java.lang.ref.WeakReference<?>) wrapperValue).get();
                        android.view.View content = (android.view.View) owner;
                        android.view.ViewOutlineProvider provider = content.getOutlineProvider();
                        if (wrapper instanceof android.view.View && provider != null) {
                            saved.add(new Object[] { "submenuOutline", wrapper, content, provider });
                        }
                    }
                });
        XposedHelpers.forEachTrackedOwner("colorosmodPopupBlurTarget",
                new XposedHelpers.TrackedOwnerConsumer() {
                    @Override public void accept(Object owner) {
                        if (!(owner instanceof android.view.View)) return;
                        Object value = XposedHelpers.getAdditionalInstanceField(
                                owner, "colorosmodPopupBlurTarget");
                        if (!(value instanceof PopupBlurTarget)) return;
                        PopupBlurTarget target = (PopupBlurTarget) value;
                        saved.add(new Object[] { "popupBlur", owner,
                                Float.valueOf(target.progress), Boolean.valueOf(target.opening) });
                    }
                });
        return saved.toArray();
    }

    public static void restoreHotReloadHosts(Object saved) {
        if (!(saved instanceof Object[])) return;
        for (Object value : (Object[]) saved) {
            if (!(value instanceof Object[])) continue;
            Object[] state = (Object[]) value;
            if (state.length >= 3 && "recentsBlend".equals(state[0])
                    && state[1] != null && state[2] instanceof float[]) {
                try {
                    Object depthController = state[1];
                    float[] savedBlend = ((float[]) state[2]).clone();
                    float[] blend = (float[]) XposedHelpers.getObjectField(
                            depthController, "mBlurBlendColor");

                    System.arraycopy(savedBlend, 0, blend, 0,
                            Math.min(blend.length, savedBlend.length));
                } catch (Throwable t) {
                    log("launcher recents blend restore failed: " + t);
                }
                continue;
            }
            if (state.length >= 4 && "submenuOutline".equals(state[0])
                    && state[1] instanceof android.view.View
                    && state[2] instanceof android.view.View
                    && state[3] instanceof android.view.ViewOutlineProvider) {
                try {
                    android.view.View wrapper = (android.view.View) state[1];
                    android.view.View content = (android.view.View) state[2];
                    if (wrapper.isAttachedToWindow() && content.isAttachedToWindow()) {
                        syncPopupSubMenuOutline(wrapper, content,
                                (android.view.ViewOutlineProvider) state[3]);
                    }
                } catch (Throwable t) {
                    log("launcher submenu outline restore failed: " + t);
                }
                continue;
            }
            if (state.length >= 4 && "popupBlur".equals(state[0])
                    && state[1] instanceof android.view.View
                    && state[2] instanceof Float && state[3] instanceof Boolean) {
                try {
                    android.view.View view = (android.view.View) state[1];
                    PopupBlurTarget target = popupBlurTarget(view);
                    target.opening = (Boolean) state[3];
                    target.progress = target.opening ? 1f : 0f;
                    applyPopupBgEffect(view, target.progress);
                    view.setAlpha(target.opening ? 1f : 0f);
                } catch (Throwable t) {
                    log("launcher popup blur restore failed: " + t);
                }
                continue;
            }
            if (state.length >= 4 && "drawerPadding".equals(state[0])
                    && state[1] instanceof android.view.View) {
                android.view.View view = (android.view.View) state[1];
                int left = (Integer) state[3];
                sDrawerPaddingStates.put(view, new int[] { (Integer) state[2], left });
                view.setPadding(left, view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom());
                ensureDrawerPaddingPreDraw(view);
                continue;
            }
            if (state.length < 9 || !(state[0] instanceof android.view.View)) continue;
            try {
                Object owner = state[0];
                android.view.View view = (android.view.View) owner;
                DrawerIconState baseline = new DrawerIconState(view);
                baseline.iconSize = (Integer) state[7];
                baseline.layoutHeight = (Integer) state[8];
                if (state.length >= 15) {
                    baseline.textSize = (Float) state[9];
                    baseline.paddingLeft = (Integer) state[10];
                    baseline.paddingTop = (Integer) state[11];
                    baseline.paddingRight = (Integer) state[12];
                    baseline.paddingBottom = (Integer) state[13];
                    ((android.widget.TextView) view).setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_PX, (Float) state[14]);
                }
                sDrawerIconStates.put(owner, baseline);
                view.setPadding((Integer) state[1], (Integer) state[2],
                        (Integer) state[3], (Integer) state[4]);
                int iconSize = (Integer) state[5];
                if (iconSize >= 0) {
                    XposedHelpers.setIntField(owner, "mIconSize", iconSize);
                    Object drawable = XposedHelpers.callMethod(owner, "getIcon");
                    if (drawable instanceof android.graphics.drawable.Drawable) {
                        XposedHelpers.callMethod(owner, "applyCompoundDrawables", drawable);
                    }
                }
                android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
                int height = (Integer) state[6];
                if (lp != null && height != Integer.MIN_VALUE) {
                    lp.height = height;
                    view.setLayoutParams(lp);
                } else {
                    view.requestLayout();
                }
            } catch (Throwable t) {
                log("launcher drawer icon restore failed: " + t);
            }
        }
    }

    public static boolean prepareHotReloadListeners() {
        return XposedHelpers.prepareTrackedPreDrawListeners("colorosmod_launcher_predraw");
    }

    public static void cancelHotReloadPreflight() {
        if (!XposedHelpers.cancelTrackedPreDrawListenerPreflight(
                "colorosmod_launcher_predraw")) {
            log("launcher hot reload pre-draw rollback incomplete");
        }
    }

    public static void cleanupForHotReload() {
        DrawerInstallTimeHooks.cleanupForHotReload();
        sLastPowerSaveTaskLockEnabled = null;
        new android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacksAndMessages(sPowerSaveTaskLockToken);
        // 在主线程执行: 置 false 后再移除队列, 保证旧 generation 不会再调用 forceReload。
        sModelReloadActive = false;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .removeCallbacksAndMessages(sModelReloadToken);
        synchronized (sDrawerIconStates) {
            for (java.util.Map.Entry<Object, DrawerIconState> entry :
                    new java.util.ArrayList<>(sDrawerIconStates.entrySet())) {
                try {
                    Object owner = entry.getKey();
                    if (!(owner instanceof android.view.View)) continue;
                    android.view.View view = (android.view.View) owner;
                    DrawerIconState state = entry.getValue();
                    view.setPadding(state.paddingLeft, state.paddingTop,
                            state.paddingRight, state.paddingBottom);
                    ((android.widget.TextView) view).setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_PX, state.textSize);
                    if (state.iconSize >= 0) {
                        XposedHelpers.setIntField(owner, "mIconSize", state.iconSize);
                        Object drawable = XposedHelpers.callMethod(owner, "getIcon");
                        if (drawable instanceof android.graphics.drawable.Drawable) {
                            XposedHelpers.callMethod(owner, "applyCompoundDrawables", drawable);
                        }
                    }
                    android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
                    if (lp != null && state.layoutHeight != Integer.MIN_VALUE) {
                        lp.height = state.layoutHeight;
                        view.setLayoutParams(lp);
                    } else {
                        view.requestLayout();
                    }
                } catch (Throwable t) {
                    log("launcher drawer icon cleanup failed: " + t);
                }
            }
            sDrawerIconStates.clear();
        }
        synchronized (sDrawerPaddingStates) {
            for (java.util.Map.Entry<android.view.View, int[]> entry : sDrawerPaddingStates.entrySet()) {
                android.view.View view = entry.getKey();
                view.setPadding(entry.getValue()[0], view.getPaddingTop(),
                        view.getPaddingRight(), view.getPaddingBottom());
            }
            sDrawerPaddingStates.clear();
        }
        XposedHelpers.cancelTrackedCallbacksAndAnimators();
        XposedHelpers.forEachTrackedOwner("colorosmod_launcher_predraw_detach",
                new XposedHelpers.TrackedOwnerConsumer() {
                    @Override public void accept(Object owner) {
                        if (!(owner instanceof android.view.View)) return;
                        Object value = XposedHelpers.getAdditionalInstanceField(
                                owner, "colorosmod_launcher_predraw_detach");
                        if (value instanceof android.view.View.OnAttachStateChangeListener) {
                            ((android.view.View) owner).removeOnAttachStateChangeListener(
                                    (android.view.View.OnAttachStateChangeListener) value);
                        }
                        XposedHelpers.removeAdditionalInstanceField(
                                owner, "colorosmod_launcher_predraw_detach");
                        XposedHelpers.removeAdditionalInstanceField(
                                owner, "colorosmod_launcher_predraw_wrapper");
                        XposedHelpers.removeAdditionalInstanceField(
                                owner, "colorosmod_drawer_layout_active");
                    }
                });
        if (!XposedHelpers.commitTrackedPreDrawListenerPreflight(
                "colorosmod_launcher_predraw")) {
            log("launcher hot reload pre-draw cleanup missing preflight");
        }
    }

    static void fixPopupDividerThickness(android.view.View root, float scale) {
        int dividerId;
        try {
            dividerId = root.getResources().getIdentifier("divider", "id", "com.android.launcher");
        } catch (Throwable t) {
            return;
        }
        if (dividerId <= 0) {
            return;
        }

        final int oneDp = Math.max(1, (int) Math.ceil(1.0f / Math.max(scale, 0.01f)));
        fixPopupDividerRecursive(root, dividerId, oneDp);
    }

    static void fixPopupDividerRecursive(android.view.View v, int dividerId, int oneDp) {
        if (v == null) {
            return;
        }
        if (v.getId() == dividerId) {
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != oneDp) {
                lp.height = oneDp;
                v.requestLayout();
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                fixPopupDividerRecursive(vg.getChildAt(i), dividerId, oneDp);
            }
        }
    }

    private static final Object sPowerSaveTaskLockToken = new Object();
    private static volatile Boolean sLastPowerSaveTaskLockEnabled;

    private static void hookPowerSaveTaskLocks(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            Class<?> manager = XposedHelpers.findClass("com.oplus.quickstep.applock.OplusLockManager", pkg.classLoader);
            Class<?> modelType = XposedHelpers.findClass("com.oplus.quickstep.applock.AppLockModel", pkg.classLoader);
            XposedHelpers.findAndHookMethod(modelType, "setIsVirtualizeLockData", boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // 此处已进入 SynchronizeInvocationHandler 的串行写入路径，不访问 Provider。
                    if (!readBoolCached(KEY_POWER_SAVE_KEEP_LOCKED_TASKS_ENABLED, false)) return;
                    p.args[0] = false;
                    if (!XposedHelpers.getBooleanField(p.thisObject, "isVirtual")) {
                        java.util.Map<?, ?> locks = (java.util.Map<?, ?>) XposedHelpers.callMethod(p.thisObject, "getAppLockInfoMap");
                        for (Object info : locks.values()) {
                            if (Boolean.TRUE.equals(XposedHelpers.callMethod(info, "isVirtualLock"))) {
                                // 使原生 setter 一并恢复冷启动时载入的虚拟锁定缓存。
                                XposedHelpers.setBooleanField(p.thisObject, "isVirtual", true);
                                break;
                            }
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(manager, "updateLowPowerMode", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!readBool(KEY_POWER_SAVE_KEEP_LOCKED_TASKS_ENABLED, false)) return;
                    // 仅屏蔽任务锁定模块的省电限制，继续向 AMS 发布真实锁定列表。
                    Object model = XposedHelpers.callMethod(p.thisObject, "getAppLockModel");
                    if (model != null) XposedHelpers.callMethod(model, "setIsVirtualizeLockData", false);
                    XposedHelpers.setBooleanField(p.thisObject, "isLowPowerModCurrently", false);
                    p.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod(manager, "canLockApp", String.class, String.class,
                    android.content.Intent.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readBool(KEY_POWER_SAVE_KEEP_LOCKED_TASKS_ENABLED, false))
                                XposedHelpers.setBooleanField(p.thisObject, "isLowPowerModCurrently", false);
                        }
                    });
            refreshPowerSaveTaskLocks(readBoolCached(KEY_POWER_SAVE_KEEP_LOCKED_TASKS_ENABLED, false));
        } catch (Throwable t) { log("launcher power-save task lock hook failed: " + t); }
    }

    private static void refreshPowerSaveTaskLocks(boolean enabled) {
        ClassLoader loader = sLauncherClassLoader;
        if (loader == null || Boolean.valueOf(enabled).equals(sLastPowerSaveTaskLockEnabled)) return;
        sLastPowerSaveTaskLockEnabled = enabled;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.removeCallbacksAndMessages(sPowerSaveTaskLockToken);
        main.postAtTime(() -> {
            if (sLauncherClassLoader != loader) return;
            try {
                Class<?> type = XposedHelpers.findClass("com.oplus.quickstep.applock.OplusLockManager", loader);
                Object manager = XposedHelpers.callStaticMethod(type, "getInstance");
                XposedHelpers.callMethod(manager, "updateLowPowerMode");
                Object companion = XposedHelpers.getStaticObjectField(type, "INSTANCE");
                XposedHelpers.callMethod(companion, "notifyLockViewUiUpdate");
            } catch (Throwable t) { log("launcher power-save task lock refresh failed: " + t); }
        }, sPowerSaveTaskLockToken, android.os.SystemClock.uptimeMillis());
    }

    private static void hookUpdateDotAndSecondaryMenu(XC_LoadPackage.LoadPackageParam lp) {
        XC_MethodHook hideUpdateDot = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (readBoolCached(KEY_HIDE_LAUNCHER_UPDATE_DOT_ENABLED, false))
                    p.setResult(Boolean.FALSE);
            }
        };
        try {
            XposedHelpers.findAndHookMethod("com.android.launcher3.BubbleTextView", lp.classLoader,
                    "isShouldShowGreenDot", boolean.class, boolean.class, hideUpdateDot);
        } catch (Throwable currentSignatureMissing) {
            // ColorOS 15.8.21 uses the original one-argument method.
            try {
                XposedHelpers.findAndHookMethod("com.android.launcher3.BubbleTextView", lp.classLoader,
                        "isShouldShowGreenDot", boolean.class, hideUpdateDot);
                log("HOOK OK launcher BubbleTextView#isShouldShowGreenDot(boolean)");
            } catch (Throwable t) { log("launcher update dot hook failed: " + t); }
        }
        try {
            Class<?> more = XposedHelpers.findClass(
                    "com.android.launcher3.popup.OplusBaseSystemShortcut$MordFunctions", lp.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.popup.OplusPopupContainerWithArrow", lp.classLoader,
                    "populateAndShow", View.class, java.util.List.class, int.class,
                    java.util.List.class, java.util.List.class, java.util.List.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!readBoolCached(KEY_DISABLE_LAUNCHER_SECONDARY_MENU_ENABLED, false)) return;
                            java.util.List<?> source = (java.util.List<?>) p.args[4];
                            java.util.ArrayList<Object> flattened = new java.util.ArrayList<>();
                            java.util.HashSet<Class<?>> enabled = new java.util.HashSet<>();
                            for (Object item : source) {
                                if (!more.isInstance(item)
                                        && Boolean.TRUE.equals(XposedHelpers.callMethod(item, "isEnabled")))
                                    enabled.add(item.getClass());
                            }
                            for (Object item : source) {
                                if (!more.isInstance(item)) {
                                    flattened.add(item);
                                    continue;
                                }
                                java.util.List<?> children = (java.util.List<?>) XposedHelpers.getObjectField(
                                        item, "mMoreSystemShortcutList");
                                for (Object child : children) {
                                    // 保留系统已校验的权限及 mMoreShortcut 标志；不改原始列表。
                                    if (enabled.add(child.getClass())) flattened.add(child);
                                }
                            }
                            p.args[4] = flattened;
                        }
                    });
        } catch (Throwable t) {

            log("launcher secondary menu wrapper unavailable on this Launcher: " + t);
        }
    }

    public static void hookLauncher(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched launcher, classLoader=" + lpparam.classLoader);
        sLauncherClassLoader = lpparam.classLoader;
        try {

            XposedHelpers.findAndHookDeclaredMethod("com.android.launcher3.Launcher", lpparam.classLoader,
                    "finishBindingItems", XposedHelpers.findClass("com.android.launcher3.util.IntSet", lpparam.classLoader),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            synchronized (sHiddenSettingsLock) {
                                if (!sModelReloadActive || !sInitialModelReloadPending || sPendingModelReloads > 0) return;
                                sPendingModelReloads++;
                            }
                            reloadLauncherModelWhenReady(new android.os.Handler(android.os.Looper.getMainLooper()), 0, true);
                        }
                    });
        } catch (Throwable error) { log("hide launcher apps first-bind hook failed: " + error); }
        hookUpdateDotAndSecondaryMenu(lpparam);
        hookPowerSaveTaskLocks(lpparam);
        float density = readDensity();

        // Feature 12 — 缩小长按菜单: 在 launcher 进程内拦截 Resources.getDimension*, 对菜单 dimen 缩放。
        hookPopupMenuDimens(lpparam);

        hookPopupBgBlur(lpparam);

        hookPxRuntime(lpparam, "com.android.launcher.layoutparam.IconParam",
                "getIconDrawablePaddingPx", density, KEY_ICON_GAP_ENABLED, KEY_ICON_GAP_DP, ICON_GAP_DP, 8, 1);
        hookPxRuntime(lpparam, "com.android.launcher.layoutparam.AllAppsParam",
                "getAllAppsIconDrawablePaddingPx", density, KEY_ICON_GAP_ENABLED, KEY_ICON_GAP_DP, ICON_GAP_DP, 8, 1);

        hookDrawerLayout(lpparam);

        // 字母索引滚动定位: 始终注入, 运行时按 KEY_DRAWER_LETTER_SCROLL_ENABLED 门控。
        hookDrawerLetterScroll(lpparam);
        DrawerInstallTimeHooks.hook(lpparam);

        hookIndicatorHotseatSize(lpparam, density);

        // Feature 4 — 多任务显示隐藏应用: 始终注入, 运行时按 KEY_RECENTS_SHOW_HIDDEN_ENABLED 门控。
        hookRecentsShowHidden(lpparam);
        // 多任务背景遮罩: 同时处理动态 blur、纯色 scrim 回退与任务卡片边框。
        hookRecentsBackgroundTransparent(lpparam);
        // Feature 19 — 多任务不显示小窗应用: 始终注入, 运行时按 KEY_RECENTS_HIDE_FREEFORM_ENABLED 门控。
        hookRecentsHideFreeform(lpparam);
        // 多任务隐藏未在运行的应用: 始终注入, 运行时按 KEY_RECENTS_HIDE_NOT_RUNNING_ENABLED 门控。
        hookRecentsHideNotRunning(lpparam);
        // 多任务上划彻底结束进程: 始终注入, 运行时按 KEY_RECENTS_SWIPE_UP_KILL_ENABLED 门控。
        hookRecentsSwipeUpKill(lpparam);

        try {
            Class<?> mgrClass = XposedHelpers.findClass(
                    "com.android.launcher.filter.DeepProtectedAppsManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(mgrClass, "createVirtualFolder", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // 运行时动态门控: 关闭则保持系统原标题("应用隐藏")。
                        if (!readBool(KEY_HIDE_APPS_TITLE_FOLDER_ENABLED, false)) return;
                        Object folderInfo = param.getResult();
                        if (folderInfo == null) return;
                        Object ctx = XposedHelpers.getObjectField(param.thisObject, "context");
                        if (!(ctx instanceof android.content.Context)) return;
                        String name = readAppHideFolderName((android.content.Context) ctx);
                        if (name == null || name.isEmpty()) return;
                        XposedHelpers.setObjectField(folderInfo, "title", name);
                        log("launcher virtual folder title -> " + name);
                    } catch (Throwable t) {
                        log("launcher virtual folder hook error: " + t);
                    }
                }
            });
            log("HOOK OK launcher DeepProtectedAppsManager#createVirtualFolder");
        } catch (Throwable t) {
            log("HOOK FAIL launcher createVirtualFolder: " + t);
        }

        try {
            Class<?> launcherAppsClass = XposedHelpers.findClass(
                    "android.content.pm.LauncherApps", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(launcherAppsClass, "getActivityList",
                    String.class, android.os.UserHandle.class, new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                java.util.Set<android.content.ComponentName> targets =
                                        getHiddenLauncherComponents();
                                if (targets.isEmpty()) return;
                                Object result = param.getResult();
                                if (!(result instanceof java.util.List)) return;
                                java.util.List<Object> list = (java.util.List<Object>) result;
                                java.util.Iterator<Object> it = list.iterator();
                                int removed = 0;
                                while (it.hasNext()) {
                                    Object info = it.next();
                                    if (!(info instanceof android.content.pm.LauncherActivityInfo)) continue;
                                    android.content.ComponentName cn =
                                            ((android.content.pm.LauncherActivityInfo) info).getComponentName();
                                    if (targets.contains(cn)) {
                                        it.remove();
                                        removed++;
                                    }
                                }
                                if (removed > 0) dbg("[DBG] hide launcher activities removed=" + removed);
                            } catch (Throwable t) {
                                log("hide launcher activities hook error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher LauncherApps#getActivityList (hide launcher activities)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher getActivityList: " + t);
        }

        // OplusAppFilter 覆盖应用抽屉和持久化桌面模型的组件判断。
        try {
            Class<?> filterClass = XposedHelpers.findClass(
                    "com.android.launcher3.OplusAppFilter", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(filterClass, "shouldShowApp",
                    android.content.ComponentName.class, android.os.UserHandle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object cnObj = param.args[0];
                                if (!(cnObj instanceof android.content.ComponentName)) return;
                                android.content.ComponentName cn = (android.content.ComponentName) cnObj;
                                if (getHiddenLauncherComponents().contains(cn)) {
                                    param.setResult(false);
                                }
                            } catch (Throwable t) {
                                log("hide launcher components shouldShowApp error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher OplusAppFilter#shouldShowApp (hide launcher components)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher OplusAppFilter#shouldShowApp: " + t);
        }

        try {
            Class<?> dragLayerClass = XposedHelpers.findClass(
                    "com.android.launcher3.dragndrop.DragLayer", lpparam.classLoader);
            // 限定本类声明: dispatchTouchEvent 在 ViewGroup 里有实现, 上溯会命中所有容器。
            XposedHelpers.findAndHookDeclaredMethod(dragLayerClass, "dispatchTouchEvent",
                    android.view.MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 运行时动态门控: 关闭则不响应手势。
                                if (!readBool(KEY_PINCH_OUT_OPEN_HIDE_APPS_ENABLED, false)) return;
                                Object dragLayer = param.thisObject;
                                if (!(dragLayer instanceof android.view.View)) return;
                                    android.view.ScaleGestureDetector detector =
                                            (android.view.ScaleGestureDetector) XposedHelpers
                                                    .getAdditionalInstanceField(dragLayer, "colorosmod_pinch");
                                    if (detector == null) {
                                        final android.content.Context ctx =
                                                ((android.view.View) dragLayer).getContext();
                                        // 双指需实际张开的最小距离(按 dp 折算, 适配不同密度屏幕)。
                                        final float minSpreadPx =
                                                100f * ctx.getResources().getDisplayMetrics().density;
                                        final float[] accum = new float[1];
                                        final float[] beginSpan = new float[1];
                                        final boolean[] fired = new boolean[1];
                                        android.view.ScaleGestureDetector.OnScaleGestureListener listener =
                                                new android.view.ScaleGestureDetector.OnScaleGestureListener() {
                                                    @Override
                                                    public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                                                        accum[0] = 1.0f;
                                                        beginSpan[0] = d.getCurrentSpan();
                                                        fired[0] = false;
                                                        return true;
                                                    }
                                                    @Override
                                                    public boolean onScale(android.view.ScaleGestureDetector d) {
                                                        accum[0] *= d.getScaleFactor();
                                                        // 仅当明显张开(累计比例 > 1.2)且实际张开距离达标才触发。
                                                        float spread = d.getCurrentSpan() - beginSpan[0];
                                                        if (!fired[0] && accum[0] > 1.2f
                                                                && spread > minSpreadPx) {
                                                            fired[0] = true;
                                                            openHideAppsFolder(ctx);
                                                        }
                                                        return false;
                                                    }
                                                    @Override
                                                    public void onScaleEnd(android.view.ScaleGestureDetector d) {}
                                                };
                                        detector = new android.view.ScaleGestureDetector(ctx, listener);
                                        XposedHelpers.setAdditionalInstanceField(
                                                dragLayer, "colorosmod_pinch", detector);
                                    }
                                    // 仅在桌面正常(NORMAL)状态响应手势; 编辑等其它状态跳过, 交给系统处理。
                                    if (!isLauncherInNormalState(((android.view.View) dragLayer).getContext())) {
                                        return;
                                    }
                                    android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                                    if (ev != null) detector.onTouchEvent(ev);
                                } catch (Throwable t) {
                                    log("pinch-out hook error: " + t);
                                }
                            }
                        });
                log("HOOK OK launcher DragLayer#dispatchTouchEvent (pinch-out)");
            } catch (Throwable t) {
                log("HOOK FAIL launcher pinch-out: " + t);
            }

        // Feature 14 — 桌面文件夹展开背景透明化: 始终注入, 运行时按 KEY_FOLDER_BG_TRANSPARENT_ENABLED 门控。
        hookFolderOpenBgBlur(lpparam);

        // Feature 23 — 调整文件夹动画持续时间: 始终注入, 运行时按开关+滑条门控。
        hookFolderAnimDuration(lpparam);

        // Feature 16 — 编辑模式背景遮罩透明化: 始终注入, 运行时按开关门控。
        hookEditModeBgBlur(lpparam);
    }

    static void openHideAppsFolder(android.content.Context ctx) {
        try {
            Class<?> mgr = XposedHelpers.findClass(
                    "com.android.launcher.filter.DeepProtectedAppsManager", ctx.getClassLoader());
            Object instance = XposedHelpers.callStaticMethod(mgr, "getInstance", ctx);
            if (instance == null) return;
            XposedHelpers.callMethod(instance, "showHideApps", ctx, false);
            log("pinch-out -> open hide apps folder");
        } catch (Throwable t) {
            log("openHideAppsFolder error: " + t);
        }
    }

    static boolean isLauncherInNormalState(android.content.Context ctx) {
        try {
            if (sLauncherClass == null) {
                sLauncherClass = XposedHelpers.findClass(
                        "com.android.launcher3.Launcher", ctx.getClassLoader());
            }
            if (sLauncherStateClass == null) {
                sLauncherStateClass = XposedHelpers.findClass(
                        "com.android.launcher3.LauncherState", ctx.getClassLoader());
            }
            if (sNormalState == null) {
                sNormalState = XposedHelpers.getStaticObjectField(sLauncherStateClass, "NORMAL");
            }
            if (!sLauncherClass.isInstance(ctx)) return false;
            return (Boolean) XposedHelpers.callMethod(ctx, "isInState", sNormalState);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void hookFolderOpenBgBlur(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> depthClass = XposedHelpers.findClass(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(depthClass, "setBlur", float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FOLDER_BG_TRANSPARENT_ENABLED, false)) return;
                                Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                                if (launcher == null) return;
                                if (!isLauncherFolderOpen(launcher, lpparam.classLoader)) return;

                                if (!isLauncherOnWorkspace(launcher, lpparam.classLoader)
                                        && !isLauncherBackgroundApp(launcher, lpparam.classLoader)) return;
                                param.args[0] = 0f;
                            } catch (Throwable t) {
                                log("folder bg blur hook error: " + t);
                            }
                        }
                    });

            Class<?> launcherStateClass = XposedHelpers.findClass(
                    "com.android.launcher3.LauncherState", lpparam.classLoader);
            Class<?> stateConfigClass = XposedHelpers.findClass(
                    "com.android.launcher3.states.StateAnimationConfig", lpparam.classLoader);
            Class<?> pendingAnimationClass = XposedHelpers.findClass(
                    "com.android.launcher3.anim.PendingAnimation", lpparam.classLoader);
            XC_MethodHook clearOnWorkspaceReturn = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    clearFolderBgBlurOnWorkspaceReturn(param.thisObject, param.args[0],
                            lpparam.classLoader);
                }
            };
            XposedHelpers.findAndHookMethod(depthClass, "setState", launcherStateClass,
                    clearOnWorkspaceReturn);
            XposedHelpers.findAndHookMethod(depthClass, "setStateWithAnimation", launcherStateClass,
                    stateConfigClass, pendingAnimationClass, clearOnWorkspaceReturn);
            log("HOOK OK launcher OplusDepthController#setBlur (transparent folder bg)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher OplusDepthController#setBlur: " + t);
        }
    }

    private static void clearFolderBgBlurOnWorkspaceReturn(Object depthController, Object targetState,
            ClassLoader cl) {
        try {
            if (!readBool(KEY_FOLDER_BG_TRANSPARENT_ENABLED, false)
                    || targetState != launcherNormalState(cl)) return;
            Object launcher = XposedHelpers.getObjectField(depthController, "mLauncher");
            if (launcher == null || !isLauncherFolderOpen(launcher, cl)) return;
            XposedHelpers.callMethod(depthController, "setBlur", 0f, false);
        } catch (Throwable t) {
            log("folder bg blur workspace return error: " + t);
        }
    }

    static boolean isLauncherOnWorkspace(Object launcher, ClassLoader cl) {
        Object state = launcherTargetState(cl);
        if (state == null) state = launcherCurrentState(launcher);
        if (state == null) return false;
        return state == launcherNormalState(cl);
    }

    static boolean isLauncherInOverview(Object launcher, ClassLoader cl) {
        Object state = launcherTargetState(cl);
        if (state == null) state = launcherCurrentState(launcher);
        if (state == null) return false;
        return state == launcherOverviewState(cl);
    }

    static boolean isLauncherBackgroundApp(Object launcher, ClassLoader cl) {
        Object state = launcherTargetState(cl);
        if (state == null) state = launcherCurrentState(launcher);
        if (state == null) return false;
        return state == launcherBackgroundAppState(cl);
    }

    static Class<?> sOplusBaseStateClass;

    private static Object launcherTargetState(ClassLoader cl) {
        try {
            if (sOplusBaseStateClass == null) {
                sOplusBaseStateClass = XposedHelpers.findClass(
                        "com.android.launcher3.states.OPlusBaseState", cl);
            }
            return XposedHelpers.callStaticMethod(sOplusBaseStateClass, "getTargetLauncherState");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object launcherCurrentState(Object launcher) {
        try {
            Object stateManager = XposedHelpers.callMethod(launcher, "getStateManager");
            if (stateManager == null) return null;
            return XposedHelpers.callMethod(stateManager, "getState");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object launcherNormalState(ClassLoader cl) {
        try {
            if (sLauncherStateClass == null) {
                sLauncherStateClass = XposedHelpers.findClass(
                        "com.android.launcher3.LauncherState", cl);
            }
            if (sNormalState == null) {
                sNormalState = XposedHelpers.getStaticObjectField(sLauncherStateClass, "NORMAL");
            }
            return sNormalState;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object launcherOverviewState(ClassLoader cl) {
        try {
            if (sLauncherStateClass == null) {
                sLauncherStateClass = XposedHelpers.findClass(
                        "com.android.launcher3.LauncherState", cl);
            }
            if (sOverviewState == null) {
                sOverviewState = XposedHelpers.getStaticObjectField(sLauncherStateClass, "OVERVIEW");
            }
            return sOverviewState;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object launcherBackgroundAppState(ClassLoader cl) {
        try {
            if (sLauncherStateClass == null) {
                sLauncherStateClass = XposedHelpers.findClass(
                        "com.android.launcher3.LauncherState", cl);
            }
            if (sBackgroundAppState == null) {
                sBackgroundAppState = XposedHelpers.getStaticObjectField(
                        sLauncherStateClass, "BACKGROUND_APP");
            }
            return sBackgroundAppState;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void hookFolderAnimDuration(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookFolderSpringDuration(lpparam);
        hookFolderLightDuration(lpparam);
        hookFolderResDuration(lpparam);
    }

    // 读取用户设置的动画时长; 开关关闭或越界时返回 -1(不生效)。
    static int folderAnimMs() {
        if (!readBool(KEY_FOLDER_ANIM_DURATION_ENABLED, false)) return -1;
        int ms = readInt(KEY_FOLDER_ANIM_DURATION_MS, 300);
        if (ms < 100 || ms > 500) return -1;
        return ms;
    }

    public static void hookFolderSpringDuration(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> wrapperClass = XposedHelpers.findClass(
                    "com.android.launcher3.folder.RtSpringAnimatorWrapper", lpparam.classLoader);
            final Class<?> springAnimClass = XposedHelpers.findClass(
                    "com.coui.appcompat.animation.dynamicanimation.COUISpringAnimation", lpparam.classLoader);
            // 初次创建: 构造后把 COUISpringForce 的 response 改为 ms/1000。
            XposedHelpers.findAndHookConstructor(wrapperClass, springAnimClass,
                    android.view.View.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int ms = folderAnimMs();
                                if (ms < 0) return;
                                Object springAnim = param.args[0];
                                Object force = XposedHelpers.getObjectField(springAnim, "A");
                                if (force != null) {
                                    XposedHelpers.callMethod(force, "d", ms / 1000.0f);
                                }
                            } catch (Throwable t) {
                                log("folder spring duration ctor error: " + t);
                            }
                        }
                    });
            // 打开/关闭期间重入更新参数时同样覆盖 response。
            XposedHelpers.findAndHookMethod(wrapperClass, "setBounceAndResponse",
                    float.class, float.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int ms = folderAnimMs();
                                if (ms < 0) return;
                                param.args[1] = ms / 1000.0f;
                            } catch (Throwable t) {
                                log("folder spring duration setter error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher RtSpringAnimatorWrapper (folder spring duration)");
        } catch (Throwable newerPathMissing) {

            try {
                Class<?> managerClass = XposedHelpers.findClass(
                        "com.android.launcher3.folder.OplusFolderAnimationManager",
                        lpparam.classLoader);
                XposedBridge.hookAllMethods(managerClass, "getAnimator", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int ms = folderAnimMs();
                        Object animator = param.getResult();
                        if (ms > 0 && animator instanceof android.animation.Animator) {
                            setFolderAnimatorDuration((android.animation.Animator) animator, ms);
                        }
                    }
                });
                log("HOOK OK OplusFolderAnimationManager#getAnimator (folder duration fallback)");
            } catch (Throwable t) {
                log("HOOK FAIL launcher folder animation duration fallback: " + t);
            }
        }
    }

    private static void setFolderAnimatorDuration(android.animation.Animator animator, long ms) {
        if (animator instanceof android.animation.AnimatorSet) {
            for (android.animation.Animator child :
                    ((android.animation.AnimatorSet) animator).getChildAnimations()) {
                setFolderAnimatorDuration(child, ms);
            }
        }
        animator.setDuration(ms);
    }

    // light 路径: 文件夹本体动画(ObjectAnimator+setDuration 常量)与 launcher 内容动画时长。
    public static void hookFolderLightDuration(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> animUtilClass = XposedHelpers.findClass(
                    "com.android.launcher3.anim.light.FolderAnimUtil", lpparam.classLoader);
            final Class<?> folderClass = XposedHelpers.findClass(
                    "com.android.launcher3.folder.Folder", lpparam.classLoader);
            final Class<?> folderIconClass = XposedHelpers.findClass(
                    "com.android.launcher3.folder.FolderIcon", lpparam.classLoader);
            Class<?> propsHolderClass;
            try {
                propsHolderClass = XposedHelpers.findClass(
                        "com.android.launcher3.anim.light.FolderAnimPropsHolder",
                        lpparam.classLoader);
            } catch (Throwable ignored) {
                propsHolderClass = XposedHelpers.findClass(
                        "com.android.launcher3.anim.light.animparams.FolderAnimPropsHolder",
                        lpparam.classLoader);
            }
            // light 模式下 launcher 内容(hotseat/workspace/pageIndicator)动画时长。
            XposedHelpers.findAndHookMethod(animUtilClass, "getAnimDuration",
                    boolean.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int ms = folderAnimMs();
                                if (ms < 0) return;
                                param.setResult((long) ms);
                            } catch (Throwable t) {
                                log("folder light animDuration error: " + t);
                            }
                        }
                    });
            // light 模式文件夹本体缩放/位移/透明度动画: 遍历子动画统一 setDuration。
            XposedHelpers.findAndHookMethod(animUtilClass, "getLightFolderContentAnimation",
                    boolean.class, folderClass, folderIconClass, propsHolderClass, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int ms = folderAnimMs();
                                if (ms < 0) return;
                                Object animatorSet = param.getResult();
                                if (animatorSet instanceof android.animation.AnimatorSet) {
                                    for (Object child : ((android.animation.AnimatorSet) animatorSet).getChildAnimations()) {
                                        if (child instanceof android.animation.ValueAnimator) {
                                            ((android.animation.ValueAnimator) child).setDuration(ms);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                log("folder light content error: " + t);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(animUtilClass, "getSuperLightFolderContentAnimation",
                    boolean.class, folderClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int ms = folderAnimMs();
                                if (ms < 0) return;
                                Object animatorSet = param.getResult();
                                if (animatorSet instanceof android.animation.AnimatorSet) {
                                    for (Object child : ((android.animation.AnimatorSet) animatorSet).getChildAnimations()) {
                                        if (child instanceof android.animation.ValueAnimator) {
                                            ((android.animation.ValueAnimator) child).setDuration(ms);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                log("folder super light content error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher FolderAnimUtil (folder light duration)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher FolderAnimUtil (folder light duration): " + t);
        }
    }

    // workspace 背景动画(WallpaperUtil 用 folder_*_duration 资源) + 基类 FolderAnimationManager 时长。
    public static void hookFolderResDuration(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final String[] folderAnimResNames = {
                    "config_materialFolderExpandDuration",
                    "folder_close_duration",
                    "folder_light_close_duration",
                    "folder_light_open_duration",
                    "folder_open_duration",
            };
            XposedHelpers.findAndHookMethod(android.content.res.Resources.class, "getInteger",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int id = (Integer) param.args[0];
                                String name;
                                try {
                                    name = ((android.content.res.Resources) param.thisObject)
                                            .getResourceEntryName(id);
                                } catch (android.content.res.Resources.NotFoundException ignored) {
                                    return;
                                }
                                boolean hit = false;
                                for (String resourceName : folderAnimResNames) {
                                    if (resourceName.equals(name)) {
                                        hit = true;
                                        break;
                                    }
                                }
                                if (!hit) return;
                                int ms = folderAnimMs();
                                if (ms < 0) return;
                                param.setResult(ms);
                            } catch (Throwable t) {
                                log("folder anim duration hook error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher Resources#getInteger (folder anim duration)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher Resources#getInteger (folder anim duration): " + t);
        }
    }

    static Class<?> sAbstractFloatingViewClass;

    static boolean isLauncherFolderOpen(Object launcher, ClassLoader cl) {
        try {
            if (sAbstractFloatingViewClass == null) {
                sAbstractFloatingViewClass = XposedHelpers.findClass(
                        "com.android.launcher3.AbstractFloatingView", cl);
            }
            // AbstractFloatingView.getOpenFolder(ActivityContext): 当前打开的文件夹(含打开/关闭动画期间)。
            return XposedHelpers.callStaticMethod(sAbstractFloatingViewClass, "getOpenFolder", launcher) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void hookRecentsBackgroundTransparent(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> overviewScrimClass = XposedHelpers.findClass(
                    "com.android.launcher3.graphics.OplusOverviewScrim", lpparam.classLoader);
            XC_MethodHook clearScrim = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (readBool(KEY_RECENTS_BG_TRANSPARENT_ENABLED, false)) {
                        param.args[0] = 0f;
                    }
                }
            };
            XposedHelpers.findAndHookMethod(overviewScrimClass, "setScrimProgress", float.class,
                    clearScrim);
            XposedHelpers.findAndHookMethod(overviewScrimClass, "setScrimProgress", float.class,
                    int.class, clearScrim);

            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.graphics.Scrim", lpparam.classLoader,
                    "draw", Canvas.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (overviewScrimClass.isInstance(param.thisObject)
                                    && readBool(KEY_RECENTS_BG_TRANSPARENT_ENABLED, false)) {
                                param.setResult(null);
                            }
                        }
                    });

            Class<?> depthClass = XposedHelpers.findClass(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(depthClass, "setBlur", float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_RECENTS_BG_TRANSPARENT_ENABLED, false)) return;
                            try {
                                Object launcher = XposedHelpers.getObjectField(
                                        param.thisObject, "mLauncher");
                                if (launcher == null
                                        || !(isLauncherInOverview(launcher, lpparam.classLoader)
                                        || isLauncherRecentsGestureActive(launcher))) {
                                    return;
                                }
                                float[] blend = (float[]) XposedHelpers.getObjectField(
                                        param.thisObject, "mBlurBlendColor");
                                param.setObjectExtra(EXTRA_RECENTS_BLEND, blend.clone());
                                System.arraycopy(RECENTS_CLEAR_BLEND, 0, blend, 0, blend.length);
                            } catch (Throwable t) {
                                log("recents bg blend hook error: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object saved = param.getObjectExtra(EXTRA_RECENTS_BLEND);
                            if (saved == null) return;
                            try {
                                float[] blend = (float[]) XposedHelpers.getObjectField(
                                        param.thisObject, "mBlurBlendColor");
                                System.arraycopy((float[]) saved, 0, blend, 0, blend.length);
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    "com.android.quickstep.touch.SwipeToRecentAnimationHelper", lpparam.classLoader,
                    "clearState", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            refreshRecentsGestureBlend(param.thisObject, "mLauncher");
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.android.quickstep.views.OplusRecentsViewImpl", lpparam.classLoader,
                    "onGestureAnimationEnd", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            refreshRecentsGestureBlend(param.thisObject, "mActivity");
                        }
                    });

            Class<?> thumbnailViewClass = XposedHelpers.findClass(
                    "com.android.quickstep.views.OplusTaskThumbnailViewImpl", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(thumbnailViewClass, "drawOnCanvas", Canvas.class,
                    float.class, float.class, float.class, float.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            drawRecentsTaskScreenshotBorder(param.thisObject,
                                    (Canvas) param.args[0]);
                        }
                    });
            log("HOOK OK launcher OplusDepthController/"
                    + "OplusOverviewScrim/OplusTaskThumbnailViewImpl (transparent recents bg)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher transparent recents bg: " + Log.getStackTraceString(t));
        }
    }

    private static boolean isLauncherRecentsGestureActive(Object launcher) {
        try {
            Object recents = XposedHelpers.callMethod(launcher, "getOverviewPanel");
            if (recents == null) return false;
            if (XposedHelpers.getBooleanField(recents, "mGestureActive")) return true;
            return Boolean.TRUE.equals(XposedHelpers.callMethod(recents, "getRecentAnimateDragging"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void refreshRecentsGestureBlend(Object owner, String launcherField) {
        if (!readBool(KEY_RECENTS_BG_TRANSPARENT_ENABLED, false)) return;
        try {
            Object launcher = XposedHelpers.getObjectField(owner, launcherField);
            Object depth = XposedHelpers.callMethod(launcher, "getDepthController");
            if (depth == null) return;
            float blur = ((Number) XposedHelpers.callMethod(depth, "getCurrentBlur")).floatValue();
            if (blur >= 0f) XposedHelpers.callMethod(depth, "setBlur", blur, false);
        } catch (Throwable error) {
            log("recents gesture blend refresh failed: " + error);
        }
    }

    private static void drawRecentsTaskScreenshotBorder(Object thumbnailView, Canvas canvas) {
        if (!readBool(KEY_RECENTS_BG_TRANSPARENT_ENABLED, false)
                || thumbnailView == null || canvas == null) return;
        try {
            Object path = XposedHelpers.getObjectField(thumbnailView, "drawPath");
            if (!(path instanceof Path)) {
                return;
            }
            sRecentsTaskBorderPaint.setStyle(Paint.Style.STROKE);
            sRecentsTaskBorderPaint.setStrokeWidth(RECENTS_TASK_BORDER_WIDTH_DP * readDensity());
            sRecentsTaskBorderPaint.setColor(RECENTS_TASK_BORDER_COLOR);
            canvas.drawPath((Path) path, sRecentsTaskBorderPaint);
        } catch (Throwable ignored) {
            // 截图路径尚未初始化时跳过当前帧，避免干扰系统原有绘制。
        }
    }

    public static void hookEditModeBgBlur(final XC_LoadPackage.LoadPackageParam lpparam) {
        String[] stateClasses = {
                "com.android.launcher3.states.ToggleBarState",
                "com.android.launcher3.states.PagePreviewState"
        };
        Class<?> launcherClass = XposedHelpers.findClass(
                "com.android.launcher3.Launcher", lpparam.classLoader);
        for (String stateClass : stateClasses) {
            hookEditModeFloatMethod(stateClass, lpparam, "getBlurUnchecked",
                    android.content.Context.class);
            hookEditModeIntMethod(stateClass, lpparam, "getLauncherRootViewBgAlpha",
                    android.content.Context.class);
            hookEditModeIntMethod(stateClass, lpparam, "getCellLayoutBgAlpha", launcherClass);
        }
        hookEditModeDepthBlur(lpparam);
    }

    public static void hookEditModeDepthBlur(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> depthClass = XposedHelpers.findClass(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", lpparam.classLoader);
            XC_MethodHook forceZero = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!readBool(KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED, false)) return;
                    try {
                        Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                        if (isLauncherEditMode(launcher)) param.args[0] = 0f;
                    } catch (Throwable t) {
                        log("edit mode depth blur hook error: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(depthClass, "setBlur", float.class, forceZero);
            XposedHelpers.findAndHookMethod(depthClass, "setBlur", float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED, false)) return;
                            try {
                                Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                                if (isLauncherEditMode(launcher)) param.args[0] = 0f;
                            } catch (Throwable t) {
                                log("edit mode depth blur hook error: " + t);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(depthClass, "setBlurWithoutAnim", float.class, forceZero);
            log("HOOK OK launcher OplusDepthController blur paths (edit bg transparent)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher OplusDepthController blur paths (edit bg transparent): " + t);
        }
    }

    static boolean isLauncherEditMode(Object launcher) {
        if (launcher == null) return false;
        try {
            Object stateManager = XposedHelpers.callMethod(launcher, "getStateManager");
            Object state = XposedHelpers.callMethod(stateManager, "getState");
            if (state == null) return false;
            String name = state.getClass().getName();
            return name.endsWith("ToggleBarState") || name.endsWith("PagePreviewState");
        } catch (Throwable t) {
            return false;
        }
    }

    public static void hookEditModeFloatMethod(String className,
                                                 XC_LoadPackage.LoadPackageParam lpparam,
                                                 String methodName, Class<?> argType) {
        try {
            XposedHelpers.findAndHookMethod(className, lpparam.classLoader, methodName, argType,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED, false)) {
                                param.setResult(0f);
                            }
                        }
                    });
            log("HOOK OK launcher " + className + "#" + methodName + " (edit bg transparent)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher " + className + "#" + methodName + ": " + t);
        }
    }

    public static void hookEditModeIntMethod(String className,
                                               XC_LoadPackage.LoadPackageParam lpparam,
                                               String methodName, Class<?> argType) {
        try {
            XposedHelpers.findAndHookMethod(className, lpparam.classLoader, methodName, argType,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED, false)) {
                                param.setResult(0);
                            }
                        }
                    });
            log("HOOK OK launcher " + className + "#" + methodName + " (edit bg transparent)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher " + className + "#" + methodName + ": " + t);
        }
    }

    static final java.util.concurrent.atomic.AtomicInteger sRecentsBypassLogCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static void hookRecentsShowHidden(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.quickstep.privacy.OplusPrivacyManager",
                    lpparam.classLoader, "isHiddenPkg", String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 运行时动态门控: 关闭则保持系统默认(隐藏应用不出现在多任务)。
                            if (!readBool(KEY_RECENTS_SHOW_HIDDEN_ENABLED, false)) return;
                            // 仅当调用方来自 quickstep 多任务渲染/手势路径时, 绕过"隐藏应用"判定
                            if (callerInQuickstepPath()) {
                                Object pkg = param.args[0];
                                param.setResult(false);
                                if (sRecentsBypassLogCount.getAndIncrement() < 30) {
                                    Log.e("ColorOSMod", "recents bypass isHiddenPkg pkg=" + pkg);
                                }
                            }
                        }
                    });
            log("HOOK OK com.oplus.quickstep.privacy.OplusPrivacyManager#isHiddenPkg");
        } catch (Throwable t) {
            log("HOOK FAIL OplusPrivacyManager#isHiddenPkg :: " + Log.getStackTraceString(t));
        }
    }

    public static void hookRecentsHideFreeform(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> groupedTaskInfo = findLauncherGroupedTaskInfo(lpparam.classLoader);
            final String flag = KEY_RECENTS_HIDE_FREEFORM_ENABLED;
            XposedHelpers.findAndHookMethod(
                    "com.oplus.quickstep.data.OplusRecentTasksFilter",
                    lpparam.classLoader, "filterTaskInfo",
                    int.class, int.class,
                    groupedTaskInfo,
                    java.util.ArrayList.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(flag, false)) return;
                                Object gti = param.args[2];
                                if (gti == null) return;
                                Object taskInfo = XposedHelpers.callMethod(gti, "getTaskInfo1");
                                if (taskInfo == null) return;
                                if (isFlexibleFloatingWindow(lpparam.classLoader, taskInfo)) {
                                    param.setResult(true); // 剔除该小窗任务卡片
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
            log("HOOK OK OplusRecentTasksFilter#filterTaskInfo (recents hide freeform)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusRecentTasksFilter#filterTaskInfo :: " + Log.getStackTraceString(t));
        }
    }

    public static void hookRecentsHideNotRunning(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> groupedTaskInfo = findLauncherGroupedTaskInfo(lpparam.classLoader);
            final String flag = KEY_RECENTS_HIDE_NOT_RUNNING_ENABLED;
            XposedHelpers.findAndHookMethod(
                    "com.oplus.quickstep.data.OplusRecentTasksFilter",
                    lpparam.classLoader, "filterTaskInfo",
                    int.class, int.class,
                    groupedTaskInfo,
                    java.util.ArrayList.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(flag, false)) return;
                                Object gti = param.args[2];
                                if (gti == null) return;
                                if (isTaskInfoRunning(gti, "getTaskInfo1")) return;
                                // 分屏: 另一半还在运行就保留
                                if (isTaskInfoRunning(gti, "getTaskInfo2")) return;
                                param.setResult(true); // 剔除该任务卡片
                            } catch (Throwable ignored) { }
                        }
                    });
            log("HOOK OK OplusRecentTasksFilter#filterTaskInfo (recents hide not running)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusRecentTasksFilter#filterTaskInfo (hide not running) :: "
                    + Log.getStackTraceString(t));
        }
    }

    private static Class<?> findLauncherGroupedTaskInfo(ClassLoader classLoader) throws Throwable {
        try {
            return XposedHelpers.findClass(
                    "com.android.wm.shell.shared.GroupedTaskInfo", classLoader);
        } catch (Throwable ignored) {
            // ColorOS 15.8.21 still exposes shell's grouped tasks from the util package.
            return XposedHelpers.findClass(
                    "com.android.wm.shell.util.GroupedRecentTaskInfo", classLoader);
        }
    }

    private static boolean isTaskInfoRunning(Object groupedTaskInfo, String getter) {
        try {
            Object taskInfo = XposedHelpers.callMethod(groupedTaskInfo, getter);
            if (taskInfo == null) return false;
            return XposedHelpers.getBooleanField(taskInfo, "isRunning");
        } catch (Throwable ignored) { }
        return false;
    }

    private static final ThreadLocal<String> sPendingKillPkg = new ThreadLocal<>();
    private static final ThreadLocal<Integer> sPendingKillUser = new ThreadLocal<>();
    // 本次被划掉的任务 id(不论杀不杀进程, 卡片都必须移除, 否则划不掉)
    private static final ThreadLocal<Integer> sPendingRemoveId = new ThreadLocal<>();
    // 划掉主任务时, 需要一起移除的附属任务 id
    private static final ThreadLocal<java.util.List<Integer>> sPendingExtraTaskIds =
            new ThreadLocal<>();
    // 划掉附属任务时, 只定向杀掉它自己所在进程(不能整包强杀, 否则主进程会一起没)
    private static final ThreadLocal<Integer> sPendingKillTaskId = new ThreadLocal<>();

    public static void hookRecentsSwipeUpKill(final XC_LoadPackage.LoadPackageParam lpparam) {
        // (1) 单卡上划
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.quickstep.views.OplusRecentsViewImpl",
                    lpparam.classLoader, "removeTask",
                    "com.android.quickstep.views.TaskView",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                sPendingKillPkg.set(null);
                                sPendingKillUser.set(null);
                                sPendingRemoveId.set(null);
                                sPendingExtraTaskIds.set(null);
                                sPendingKillTaskId.set(null);
                                boolean kill =
                                        readBool(KEY_RECENTS_SWIPE_UP_KILL_ENABLED, false);
                                boolean subsidiary = readBool(
                                        KEY_RECENTS_SWIPE_UP_KILL_SUBSIDIARY_ENABLED, false);
                                if (!kill && !subsidiary) return;
                                Object task = XposedHelpers.callMethod(param.args[0], "getTask");
                                if (task == null) return;
                                Object key = XposedHelpers.getObjectField(task, "key");
                                if (key == null) return;
                                String pkg = (String) XposedHelpers.callMethod(
                                        key, "getPackageName");
                                if (pkg == null || pkg.isEmpty()) return;
                                int taskId = (Integer) XposedHelpers.getObjectField(key, "id");
                                int userId = XposedHelpers.getIntField(key, "userId");
                                sPendingKillUser.set(userId);
                                // 卡片必须无条件移除(见 after), 否则划掉它不会消失。
                                sPendingRemoveId.set(taskId);

                                // 主/附属判定: 任务的根组件是否属于该包的 launcher 入口。
                                Boolean main = subsidiary
                                        ? isMainTask((Context) XposedHelpers.callMethod(
                                                param.thisObject, "getContext"), key)
                                        : null;

                                if (Boolean.TRUE.equals(main)) {
                                    // 划掉主任务: 连同该包剩余的附属任务一起清掉
                                    java.util.List<Integer> others =
                                            new java.util.ArrayList<>();
                                    collectTaskIdsOfPkg(param.thisObject, pkg, userId, others, taskId);
                                    if (!others.isEmpty()) {
                                        sPendingExtraTaskIds.set(others);

                                        sPendingKillPkg.set(pkg);
                                        return;
                                    }
                                } else if (kill && Boolean.FALSE.equals(main)) {
                                    // 划掉附属任务: 只杀它自己所在进程, 主进程要留着
                                    sPendingKillTaskId.set(taskId);
                                    return;
                                }
                                if (kill && countTaskViewsOfPkg(param.thisObject, pkg, userId) <= 1) {
                                    // 常规规则: 同包没有别的卡片才整包强杀
                                    sPendingKillPkg.set(pkg);
                                }
                            } catch (Throwable ignored) { }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String pkg = sPendingKillPkg.get();
                                Integer userId = sPendingKillUser.get();
                                Integer removeId = sPendingRemoveId.get();
                                java.util.List<Integer> extra = sPendingExtraTaskIds.get();
                                Integer killTaskId = sPendingKillTaskId.get();
                                sPendingKillPkg.set(null);
                                sPendingKillUser.set(null);
                                sPendingRemoveId.set(null);
                                sPendingExtraTaskIds.set(null);
                                sPendingKillTaskId.set(null);
                                if (removeId == null) return;
                                Context ctx = (Context) XposedHelpers.callMethod(
                                        param.thisObject, "getContext");

                                Object amw = XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass(
                                                "com.android.systemui.shared.system.ActivityManagerWrapper",
                                                lpparam.classLoader),
                                        "getInstance");
                                XposedHelpers.callMethod(amw, "removeTask", removeId);
                                // 划掉主任务时, 附属任务一并从最近任务里移除
                                if (extra != null) {
                                    for (Integer id : extra) {
                                        XposedHelpers.callMethod(amw, "removeTask", id);
                                    }
                                }
                                if (pkg != null && userId != null) {
                                    requestForceStop(ctx, pkg, userId);
                                } else if (killTaskId != null) {
                                    requestKillTask(ctx, killTaskId);
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
            log("HOOK OK OplusRecentsViewImpl#removeTask (recents swipe up kill)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusRecentsViewImpl#removeTask (recents swipe up kill) :: "
                    + Log.getStackTraceString(t));
        }

        // (2) 点击"全部清除": 系统只会把未锁定的卡片划掉, 这里算出哪些包会被清空并强杀。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.quickstep.views.OplusRecentsViewImpl",
                    lpparam.classLoader, "dismissAllTasks",
                    View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_RECENTS_SWIPE_UP_KILL_ENABLED, false)) return;
                                Context ctx = (Context) XposedHelpers.callMethod(
                                        param.thisObject, "getContext");
                                // 被锁定的卡片不会被清掉, 所以这些包清除后仍有任务, 不能杀。
                                java.util.Set<String> keep = new java.util.HashSet<>();
                                java.util.Set<String> all = new java.util.HashSet<>();
                                int n = (Integer) XposedHelpers.callMethod(
                                        param.thisObject, "getTaskViewCount");
                                for (int i = 0; i < n; i++) {
                                    Object tv = XposedHelpers.callMethod(
                                            param.thisObject, "getTaskViewAt", i);
                                    if (tv == null) continue;
                                    Object task = XposedHelpers.callMethod(tv, "getTask");
                                    if (task == null) continue;
                                    Object key = XposedHelpers.getObjectField(task, "key");
                                    if (key == null) continue;
                                    String pkg = (String) XposedHelpers.callMethod(
                                            key, "getPackageName");
                                    if (pkg == null || pkg.isEmpty()) continue;
                                    String target = XposedHelpers.getIntField(key, "userId") + ":" + pkg;
                                    all.add(target);
                                    if (isLockedTaskView(tv, lpparam)) keep.add(target);
                                }

                                for (String target : all) {
                                    if (keep.contains(target)) continue;
                                    int separator = target.indexOf(':');
                                    requestForceStop(ctx, target.substring(separator + 1),
                                            Integer.parseInt(target.substring(0, separator)));
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
            log("HOOK OK OplusRecentsViewImpl#dismissAllTasks (recents swipe up kill)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusRecentsViewImpl#dismissAllTasks (recents swipe up kill) :: "
                    + Log.getStackTraceString(t));
        }

        try {
            Class<?> killWrapper = XposedHelpers.findClass(
                    "com.oplus.quickstep.memory.KillAppWrapper", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(killWrapper, "forceStopAppList",
                    Context.class, java.util.ArrayList.class, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_RECENTS_SWIPE_UP_KILL_ENABLED, false)) return;
                                param.args[3] = Boolean.TRUE; // 13 STOP -> 11 KILL_OR_STOP
                            } catch (Throwable ignored) { }
                        }
                    });
            log("HOOK OK KillAppWrapper#forceStopAppList (recents swipe up kill)");
        } catch (Throwable t) {
            log("HOOK FAIL KillAppWrapper#forceStopAppList :: " + Log.getStackTraceString(t));
        }
    }

    private static int countTaskViewsOfPkg(Object recentsView, String pkg, int userId) {
        int count = 0;
        try {
            int n = (Integer) XposedHelpers.callMethod(recentsView, "getTaskViewCount");
            for (int i = 0; i < n; i++) {
                Object tv = XposedHelpers.callMethod(recentsView, "getTaskViewAt", i);
                if (tv == null) continue;
                Object task = XposedHelpers.callMethod(tv, "getTask");
                if (task == null) continue;
                Object key = XposedHelpers.getObjectField(task, "key");
                if (key == null) continue;
                if (XposedHelpers.getIntField(key, "userId") == userId
                        && pkg.equals(XposedHelpers.callMethod(key, "getPackageName"))) count++;
            }
        } catch (Throwable ignored) { }
        return count;
    }

    // 收集 RecentsView 里该包其它任务(排除 excludeId)的任务 id。
    private static void collectTaskIdsOfPkg(Object recentsView, String pkg, int userId,
            java.util.List<Integer> out, int excludeId) {
        try {
            int n = (Integer) XposedHelpers.callMethod(recentsView, "getTaskViewCount");
            for (int i = 0; i < n; i++) {
                Object tv = XposedHelpers.callMethod(recentsView, "getTaskViewAt", i);
                if (tv == null) continue;
                Object task = XposedHelpers.callMethod(tv, "getTask");
                if (task == null) continue;
                Object key = XposedHelpers.getObjectField(task, "key");
                if (key == null) continue;
                if (XposedHelpers.getIntField(key, "userId") != userId
                        || !pkg.equals(XposedHelpers.callMethod(key, "getPackageName"))) continue;
                int id = (Integer) XposedHelpers.getObjectField(key, "id");
                if (id != excludeId) out.add(id);
            }
        } catch (Throwable ignored) { }
    }

    private static Boolean isMainTask(Context ctx, Object key) {
        try {
            String pkg = (String) XposedHelpers.callMethod(key, "getPackageName");
            if (pkg == null) return null;
            android.content.ComponentName base = null;
            try {
                Object baseIntent = XposedHelpers.getObjectField(key, "baseIntent");
                base = (android.content.ComponentName) XposedHelpers.callMethod(
                        baseIntent, "getComponent");
            } catch (Throwable ignored) { }
            if (base == null) {
                base = (android.content.ComponentName) XposedHelpers.callMethod(
                        key, "getComponent");
            }
            if (base == null) return null;
            android.content.pm.LauncherApps la =
                    (android.content.pm.LauncherApps) ctx.getSystemService(
                            Context.LAUNCHER_APPS_SERVICE);
            if (la == null) return null;
            java.util.List<android.content.pm.LauncherActivityInfo> list =
                    la.getActivityList(pkg, (android.os.UserHandle) XposedHelpers.callStaticMethod(
                            android.os.UserHandle.class, "of", XposedHelpers.getIntField(key, "userId")));
            if (list == null || list.isEmpty()) return null;
            for (android.content.pm.LauncherActivityInfo info : list) {
                if (base.equals(info.getComponentName())) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean isLockedTaskView(Object taskView,
            XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> util = XposedHelpers.findClass(
                    "com.oplus.quickstep.utils.RecentsViewAnimUtil", lpparam.classLoader);
            Object inst = XposedHelpers.getStaticObjectField(util, "INSTANCE");
            Object r = XposedHelpers.callMethod(inst, "isLockedOrSupportQuickStartup", taskView);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable ignored) { }
        return false;
    }

    private static void requestForceStop(Context ctx, String pkg, int userId) {
        if (ctx == null || pkg == null || userId < 0) return;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses("cmpkg:" + userId + ":" + pkg);
        } catch (Throwable ignored) { }
    }

    private static void requestKillTask(Context ctx, int taskId) {
        if (ctx == null) return;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses("cmtask:" + taskId);
        } catch (Throwable ignored) { }
    }

    // 复刻系统 TaskUtils.isFlexibleFloatingWindow(TaskInfo): 判断任务是否处于小窗/自由窗口状态。
    static boolean isFlexibleFloatingWindow(ClassLoader cl, Object taskInfo) {
        try {
            Class<?> taskUtils = XposedHelpers.findClass(
                    "com.android.systemui.shared.recents.utilities.TaskUtils", cl);
            Object r = XposedHelpers.callStaticMethod(taskUtils, "isFlexibleFloatingWindow",
                    new Class[]{android.app.TaskInfo.class}, taskInfo);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) { }
        // 兜底: 直接按窗口模式判定(WINDOWING_MODE_FREEFORM=5)
        try {
            Object wm = XposedHelpers.callMethod(taskInfo, "getWindowingMode");
            if (wm instanceof Integer) return (Integer) wm == 5;
        } catch (Throwable ignored) { }
        try {
            Object cfg = XposedHelpers.callMethod(taskInfo, "getConfiguration");
            Object wc = XposedHelpers.callMethod(cfg, "getWindowConfiguration");
            Object wm = XposedHelpers.callMethod(wc, "getWindowingMode");
            return wm instanceof Integer && (Integer) wm == 5;
        } catch (Throwable ignored) { }
        return false;
    }

    static boolean callerInQuickstepPath() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String cn = e.getClassName();
            if (cn == null) continue;
            if (cn.startsWith("com.android.quickstep")
                    && !cn.toLowerCase().contains("lock")) {
                return true;
            }
        }
        return false;
    }

    public static void hookIndicatorHotseatSize(final XC_LoadPackage.LoadPackageParam lpparam,
                                                  final float density) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher.layoutparam.HotseatParam", lpparam.classLoader,
                    "getHotseatBarSizePx", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_INDICATOR_ENABLED, false)) return;
                            Object result = param.getResult();
                            if (!(result instanceof Integer)) return;
                            int requestedDp = Math.max(0, Math.min(
                                    32, readInt(KEY_INDICATOR_DP, INDICATOR_REDUCE_DP)));
                            if (requestedDp == 0) return;
                            try {
                                Object workspace = XposedHelpers.getObjectField(
                                        param.thisObject, "mWorkspace");
                                float topPercentage = ((Number) XposedHelpers.callMethod(
                                        workspace, "getWorkspaceTopPercentage")).floatValue();
                                topPercentage = Math.max(0f, Math.min(0.95f, topPercentage));
                                float pageMoveRatio = 1f - topPercentage;
                                int requestedPx = Math.round(requestedDp * density);
                                int hotseatDeltaPx = Math.round(requestedPx / pageMoveRatio);
                                int originalPx = (Integer) result;
                                param.setResult(Math.max(1, originalPx - hotseatDeltaPx));
                            } catch (Throwable t) {
                                // 布局字段不可用时退回直接 dp->px，避免影响桌面正常布局。
                                param.setResult((Integer) result
                                        - Math.round(requestedDp * density));
                            }
                        }
                    });
            log("HOOK OK HotseatParam#getHotseatBarSizePx (workspace compensation)");
        } catch (Throwable t) {
            log("HOOK FAIL HotseatParam#getHotseatBarSizePx (workspace compensation): " + t);
        }
    }

    static boolean drawerLayoutEnabled() {
        return readBool(KEY_DRAWER_LAYOUT_ENABLED, false);
    }

    static boolean drawerLetterScroll() {
        return !DrawerInstallTimeHooks.enabled()
                && readBool(KEY_DRAWER_LETTER_SCROLL_ENABLED, false);
    }

    // 图标间左右间隔保留比例: 缩小八分之一即保留 7/8。
    static final float DRAWER_ICON_GAP_KEEP = 0.875f;
    static final int DISPLAY_ALL_APPS = 1;

    static boolean drawerHasVisibleLetterScroller(android.view.View anchor) {
        int id = anchor.getResources().getIdentifier(
                "coui_fast_scroller", "id", "com.android.launcher");
        if (id == 0) return false;
        android.view.View scroller = anchor.getRootView().findViewById(id);
        return scroller != null && scroller.getVisibility() == android.view.View.VISIBLE
                && scroller.isShown();
    }

    static int drawerAdjustedLeftPadding(int systemPx, float density) {
        int minLeft = Math.round(4f * density);
        int letterBar = Math.round(28f * density);
        return Math.max(minLeft, systemPx - letterBar);
    }

    static void ensureDrawerPaddingPreDraw(android.view.View recyclerView) {
        if (XposedHelpers.getAdditionalInstanceField(
                recyclerView, "colorosmod_launcher_predraw") != null) return;
        final java.lang.ref.WeakReference<android.view.View> viewRef =
                new java.lang.ref.WeakReference<>(recyclerView);
        trackLauncherPreDraw(recyclerView, new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.View view = viewRef.get();
                if (view == null) return true;
                try {
                    // 隐藏页只允许首次预备布局，已有状态继续保持到它真正显示。
                    if (!view.isShown() && XposedHelpers.getAdditionalInstanceField(
                            view, "colorosmod_drawer_layout_active") != null) return true;
                    Boolean pageActive = drawerLayoutForPage(view);
                    if (pageActive == null) return true;
                    boolean active = pageActive;
                    Object previous = XposedHelpers.getAdditionalInstanceField(
                            view, "colorosmod_drawer_layout_active");
                    XposedHelpers.setAdditionalInstanceField(
                            view, "colorosmod_drawer_layout_active", Boolean.valueOf(active));
                    boolean changed = syncDrawerLeftPadding(view, active);
                    changed |= syncDrawerIcons(view, active);
                    if ((previous == null && active)
                            || (previous instanceof Boolean && ((Boolean) previous) != active)) {
                        XposedHelpers.callMethod(view, "invalidateItemDecorations");
                        changed = true;
                    }
                    return !changed;
                } catch (Throwable t) {
                    log("drawer pre-draw left adjust error: " + t);
                    return true;
                }
            }
        });
    }

    // 用未修改的右边距反推系统左边距，重复绘制不会累积扣减。
    static boolean syncDrawerLeftPadding(android.view.View view, boolean active) {
        int right = view.getPaddingRight();
        if (right <= 0) return false;
        float density = view.getResources().getDisplayMetrics().density;
        int extra = 0;
        try {
            int id = view.getResources().getIdentifier(
                    "all_apps_recycle_view_padding_left", "dimen", "com.android.launcher");
            if (id != 0) extra = view.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        int[] state = sDrawerPaddingStates.get(view);
        if (!active && state == null) return false;
        if (active) {
            if (state == null) state = new int[] { view.getPaddingLeft(), view.getPaddingLeft() };
            // 系统重新下发 padding 时，保存新的原生值，避免恢复过期边距。
            else if (view.getPaddingLeft() != state[1]) state[0] = view.getPaddingLeft();
            sDrawerPaddingStates.put(view, state);
        }
        int system = Math.max(0, right - extra);
        int want = active ? extra + drawerAdjustedLeftPadding(system, density)
                : (view.getPaddingLeft() == state[1] ? state[0] : view.getPaddingLeft());
        if (active) state[1] = want;
        else sDrawerPaddingStates.remove(view);
        if (view.getPaddingLeft() == want) return false;
        view.setPadding(want, view.getPaddingTop(), right, view.getPaddingBottom());
        return true;
    }

    // 不修改共用 AllAppsParam，所有调整按当前页面的字母条可见状态执行。
    public static void hookDrawerLayout(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 首次 layout 就注册，开关快照或字母条晚到也会在后续绘制前补调。
            sDrawerPagedViewClass = XposedHelpers.findClass(
                    "com.android.launcher3.PagedView", lpparam.classLoader);
            sDrawerContainerClass = XposedHelpers.findClass(
                    "com.android.launcher3.allapps.OplusLauncherAllAppsContainerView", lpparam.classLoader);
            final Class<?> oplusRv = XposedHelpers.findClass(
                    "com.android.launcher3.allapps.OplusAllAppsRecyclerView", lpparam.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(oplusRv, "onLayout",
                    boolean.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ensureDrawerPaddingPreDraw((android.view.View) param.thisObject);
                        }
                    });
            log("HOOK OK OplusAllAppsRecyclerView#onLayout (drawer pre-draw padding)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusAllAppsRecyclerView#onLayout: " + t);
        }

        try {
            final Class<?> spacingClass = XposedHelpers.findClass(
                    "com.android.launcher3.allapps.GridSpacingItemDecoration",
                    lpparam.classLoader);
            // 在系统算完 item 偏移后缩小间距，不修改 decoration 的共用状态。
            XposedBridge.hookAllMethods(spacingClass, "getItemOffsets",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length != 4
                                    || !(param.args[0] instanceof android.graphics.Rect)
                                    || !(param.args[2] instanceof android.view.View)
                                    || !Boolean.TRUE.equals(drawerLayoutForMeasure(
                                            (android.view.View) param.args[2]))) return;
                            android.graphics.Rect rect = (android.graphics.Rect) param.args[0];
                            rect.left = Math.round(rect.left * DRAWER_ICON_GAP_KEEP);
                            rect.right = Math.round(rect.right * DRAWER_ICON_GAP_KEEP);
                        }
                    });
            log("HOOK OK GridSpacingItemDecoration (drawer icon gap)");
        } catch (Throwable t) {
            log("HOOK FAIL GridSpacingItemDecoration: " + t);
        }

        try {
            final Class<?> btv = XposedHelpers.findClass(
                    "com.android.launcher3.BubbleTextView", lpparam.classLoader);
            sDrawerBubbleTextViewClass = btv;

            XposedHelpers.findAndHookDeclaredMethod(btv, "onMeasure",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.view.View view = (android.view.View) param.thisObject;
                            Boolean active = drawerLayoutForMeasure(view);
                            if (active != null) syncDrawerIconLayout(view, active);
                        }
                    });
            log("HOOK OK BubbleTextView (drawer icon sizes and gap)");
        } catch (Throwable t) {
            log("HOOK FAIL BubbleTextView (drawer icon gap): " + t);
        }
    }

    static boolean drawerLayoutActive(android.view.View view) {
        return drawerLayoutEnabled() && view.isShown() && drawerHasVisibleLetterScroller(view);
    }

    // isShown() 不区分 pager 中的屏幕外页面；共享字母条在滚动中也会临时 GONE。

    static Boolean drawerLayoutForPage(android.view.View recyclerView) {
        if (!drawerLayoutEnabled()) return Boolean.FALSE;
        android.view.ViewParent parent = recyclerView.getParent();
        while (parent instanceof android.view.View) {
            android.view.View view = (android.view.View) parent;
            if (sDrawerPagedViewClass != null && sDrawerPagedViewClass.isInstance(view)) {
                Object cached = XposedHelpers.getAdditionalInstanceField(
                        recyclerView, "colorosmod_drawer_layout_active");
                boolean moving = Boolean.TRUE.equals(XposedHelpers.callMethod(view, "isPageInTransition"));
                int page = (Integer) XposedHelpers.callMethod(view, "getCurrentPage");
                Object currentPage = XposedHelpers.callMethod(view, "getPageAt", page);
                if (moving || currentPage != recyclerView) {
                    if (cached instanceof Boolean) return (Boolean) cached;
                    return drawerInitialWorkLayout(recyclerView, view);
                }
                break;
            }
            parent = view.getParent();
        }
        return recyclerView.isShown() ? drawerLayoutActive(recyclerView) : null;
    }

    static Boolean drawerInitialWorkLayout(android.view.View recyclerView, android.view.View pager) {
        if (!drawerHasVisibleLetterScroller(recyclerView)) return null;
        android.view.ViewParent parent = pager.getParent();
        while (parent instanceof android.view.View) {
            android.view.View container = (android.view.View) parent;
            if (sDrawerContainerClass != null && sDrawerContainerClass.isInstance(container)) {
                try {
                    if (XposedHelpers.callMethod(container, "getWorkRecyclerView") != recyclerView
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(container, "showTabs"))) {
                        return null;
                    }
                    Object manager = XposedHelpers.callMethod(container, "getWorkManager");
                    if (manager == null) return null;
                    Object workSwitch = XposedHelpers.callMethod(manager, "getWorkModeSwitch");
                    return workSwitch != null
                            && Boolean.TRUE.equals(XposedHelpers.callMethod(workSwitch, "isWorkEnable"))
                            ? Boolean.TRUE : null;
                } catch (Throwable ignored) {
                    // 系统接口不可用时继续走当前页绘制前调整，不猜测另一页的类型。
                    return null;
                }
            }
            parent = container.getParent();
        }
        return null;
    }

    static Boolean drawerLayoutForMeasure(android.view.View view) {
        if (!drawerLayoutEnabled()) return Boolean.FALSE;
        android.view.View current = view;
        while (current != null) {
            Object active = XposedHelpers.getAdditionalInstanceField(
                    current, "colorosmod_drawer_layout_active");
            if (active instanceof Boolean) return (Boolean) active;
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof android.view.View ? (android.view.View) parent : null;
        }
        return null;
    }

    static boolean syncDrawerIcons(android.view.View view, boolean active) {
        boolean changed = false;
        if (sDrawerBubbleTextViewClass != null && sDrawerBubbleTextViewClass.isInstance(view)) {
            changed = syncDrawerIconLayout(view, active);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                changed |= syncDrawerIcons(group.getChildAt(i), active);
            }
        }
        return changed;
    }

    // 保存原生尺寸，按当前页面重新计算目标值；字母条消失或关闭开关时恢复原样。
    static boolean syncDrawerIconLayout(android.view.View view, boolean active) {
        try {
            if (XposedHelpers.getIntField(view, "mDisplay") != DISPLAY_ALL_APPS) return false;
            android.widget.TextView text = (android.widget.TextView) view;
            DrawerIconState state = sDrawerIconStates.get(view);
            if (state == null) {
                if (!active) return false;
                state = new DrawerIconState(view);
                state.iconSize = (Integer) XposedHelpers.callMethod(view, "getIconSize");
                if (state.iconSize <= 0) return false;
                sDrawerIconStates.put(view, state);
            }
            int left = active ? Math.round(state.paddingLeft * DRAWER_ICON_GAP_KEEP)
                    : state.paddingLeft;
            int right = active ? Math.round(state.paddingRight * DRAWER_ICON_GAP_KEEP)
                    : state.paddingRight;
            int scaledIcon = active ? Math.max(1, Math.round(state.iconSize * DRAWER_ICON_SCALE))
                    : state.iconSize;
            int iconSize = scaledIcon;
            int width = view.getMeasuredWidth();
            // 5 列时原生 decoration 不提供横向间距，以放大图标吃掉空隙的 1/8。
            if (active && width > scaledIcon) {
                int inner = width - left - right;
                iconSize += Math.round((width - scaledIcon) * (1f - DRAWER_ICON_GAP_KEEP));
                if (inner > 0) iconSize = Math.min(iconSize, inner);
                iconSize = Math.max(scaledIcon, iconSize);
            }
            boolean changed = false;
            if (view.getPaddingLeft() != left || view.getPaddingRight() != right) {
                // 上下 padding 由 BubbleTextView.onMeasure 按实际行高居中计算。
                view.setPadding(left, view.getPaddingTop(), right, view.getPaddingBottom());
                changed = true;
            }
            float textSize = active ? state.textSize * DRAWER_ICON_SCALE : state.textSize;
            if (Math.abs(text.getTextSize() - textSize) > 0.01f) {
                text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize);
                changed = true;
            }
            if (XposedHelpers.getIntField(view, "mIconSize") != iconSize) {
                XposedHelpers.setIntField(view, "mIconSize", iconSize);
                Object drawable = XposedHelpers.callMethod(view, "getIcon");
                if (drawable instanceof android.graphics.drawable.Drawable) {
                    XposedHelpers.callMethod(view, "applyCompoundDrawables", drawable);
                }
                view.requestLayout();
                changed = true;
            }
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null && state.layoutHeight > 0) {
                // 保留原来的格子高度缩放，并补偿横向放大图标吃掉的上下空间。
                int height = active ? Math.max(1, Math.round(state.layoutHeight * DRAWER_ICON_SCALE))
                        + iconSize - scaledIcon : state.layoutHeight;
                if (lp.height != height) {
                    lp.height = height;
                    view.setLayoutParams(lp);
                    changed = true;
                }
            }
            return changed;
        } catch (Throwable t) {
            log("drawer icon layout sync failed: " + t);
            return false;
        }
    }

    public static void hookDrawerLetterScroll(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.allapps.ClusterAppsContainer",
                    lpparam.classLoader, "onSectionChange", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!drawerLetterScroll()) return;
                            try {
                                // 已在分组页时先回到列表; 本来就在列表则内部直接 return。
                                XposedHelpers.callMethod(param.thisObject,
                                        "changeToDrawerLayout", true);
                            } catch (Throwable t) {
                                log("drawer letter scroll leave cluster: " + t);
                            }
                            param.setResult(null);
                        }
                    });
            log("HOOK OK ClusterAppsContainer#onSectionChange (letter scroll)");
        } catch (Throwable t) {
            log("HOOK FAIL ClusterAppsContainer#onSectionChange: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.allapps.OplusAllAppsRecyclerView",
                    lpparam.classLoader, "injectScrollToPositionAtProgress",
                    "com.android.launcher3.allapps.AlphabeticalAppsList$FastScrollSectionInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!drawerLetterScroll()) return;
                            try {
                                if (XposedHelpers.getObjectField(param.thisObject, "mLauncher")
                                        == null) {
                                    return;
                                }
                                Object info = param.args[0];
                                if (info == null) return;

                                int pos = XposedHelpers.getIntField(info, "position");
                                if (pos < 0) return;
                                Object lm = XposedHelpers.callMethod(
                                        param.thisObject, "getLayoutManager");
                                if (lm == null) return;
                                android.view.View rv = (android.view.View) param.thisObject;
                                int letterId = rv.getResources().getIdentifier(
                                        "coui_fast_scroller", "id", "com.android.launcher");
                                if (letterId != 0) {
                                    android.view.View letterScroller = rv.getRootView()
                                            .findViewById(letterId);
                                    if (letterScroller != null) {
                                        XposedHelpers.setAdditionalInstanceField(letterScroller,
                                                "colorosmod_drawer_letter_scroller", Boolean.TRUE);
                                    }
                                }
                                XposedHelpers.callMethod(rv, "stopScroll");

                                Object scroller = XposedHelpers.getObjectField(
                                        param.thisObject, "mSmoothScroller");
                                int topOffset = drawerLetterScrollTopOffset(rv);
                                XposedHelpers.callMethod(scroller, "setGravity",
                                        android.view.Gravity.START);
                                XposedHelpers.callMethod(scroller, "setMargin", topOffset);
                                XposedHelpers.callMethod(scroller, "setTargetPosition", pos);

                                int currentY = (Integer) XposedHelpers.callMethod(
                                        param.thisObject, "getCurrentScrollY");
                                int targetY = (Integer) XposedHelpers.callMethod(
                                        param.thisObject, "getCurrentScrollY", pos, topOffset);
                                int availableY = (Integer) XposedHelpers.callMethod(
                                        param.thisObject, "getAvailableScrollHeight");
                                targetY = Math.max(0, Math.min(availableY, targetY));
                                int distance = Math.abs(targetY - currentY);
                                float millisPerPixel = Math.min(0.05f,
                                        117f / Math.max(1, distance));
                                XposedHelpers.setAdditionalInstanceField(scroller,
                                        "colorosmod_drawer_vertical_scroll", Boolean.TRUE);
                                XposedHelpers.setAdditionalInstanceField(scroller,
                                        "colorosmod_drawer_scroll_ms_per_px", millisPerPixel);
                                XposedHelpers.callMethod(lm, "startSmoothScroll", scroller);
                            } catch (Throwable t) {
                                log("drawer letter scroll error: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusAllAppsRecyclerView#injectScrollToPositionAtProgress (letter scroll)");
        } catch (Throwable t) {
            log("HOOK FAIL injectScrollToPositionAtProgress: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher.locateaction.TopSmoothScroller",
                    lpparam.classLoader, "calculateDxToMakeVisible",
                    android.view.View.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "colorosmod_drawer_vertical_scroll"))) {
                                return;
                            }
                            XposedHelpers.removeAdditionalInstanceField(param.thisObject,
                                    "colorosmod_drawer_vertical_scroll");
                            param.setResult(0);
                        }
                    });
            log("HOOK OK TopSmoothScroller#calculateDxToMakeVisible (letter scroll)");
        } catch (Throwable t) {
            log("HOOK FAIL TopSmoothScroller#calculateDxToMakeVisible: " + t);
        }

        try {

            final Class<?> topSmoothScroller = XposedHelpers.findClass(
                    "com.android.launcher.locateaction.TopSmoothScroller", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    topSmoothScroller, "calculateTimeForScrolling", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object speed = XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "colorosmod_drawer_scroll_ms_per_px");
                            if (speed instanceof Float) {
                                int distance = Math.abs((Integer) param.args[0]);
                                param.setResult((int) Math.ceil(distance * (Float) speed));
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    topSmoothScroller, "calculateTimeForDeceleration", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (XposedHelpers.getAdditionalInstanceField(param.thisObject,
                                    "colorosmod_drawer_scroll_ms_per_px") != null) {
                                param.setResult(Math.min(350, (Integer) param.getResult()));
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    topSmoothScroller, "onStop",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            XposedHelpers.removeAdditionalInstanceField(param.thisObject,
                                    "colorosmod_drawer_vertical_scroll");
                            XposedHelpers.removeAdditionalInstanceField(param.thisObject,
                                    "colorosmod_drawer_scroll_ms_per_px");
                        }
                    });
            log("HOOK OK LinearSmoothScroller duration (letter scroll)");
        } catch (Throwable t) {
            log("HOOK FAIL LinearSmoothScroller duration: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.allapps.OplusCOUITouchSearchView",
                    lpparam.classLoader, "onTouchEvent", android.view.MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!drawerLetterScroll()) return;
                            android.view.MotionEvent event = (android.view.MotionEvent) param.args[0];
                            int action = event.getActionMasked();
                            if (action != android.view.MotionEvent.ACTION_UP
                                    && action != android.view.MotionEvent.ACTION_CANCEL) return;
                            if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, "colorosmod_drawer_letter_scroller"))) return;
                            android.view.View scroller = (android.view.View) param.thisObject;
                            Object pending = XposedHelpers.getAdditionalInstanceField(scroller,
                                    "colorosmod_clear_letter_highlight");
                            if (pending instanceof Runnable) {
                                scroller.removeCallbacks((Runnable) pending);
                            }
                            if (action == android.view.MotionEvent.ACTION_CANCEL) {
                                XposedHelpers.callMethod(scroller, "closing");
                                return;
                            }
                            // 每次抬手重新计时，让最后点击的字母保持高亮 500ms。
                            Runnable clearHighlight = () -> {
                                try {
                                    XposedHelpers.callMethod(scroller, "closing");
                                } catch (Throwable t) {
                                    log("clear drawer letter highlight error: " + t);
                                }
                                XposedHelpers.removeAdditionalInstanceField(scroller,
                                        "colorosmod_clear_letter_highlight");
                            };
                            XposedHelpers.setAdditionalInstanceField(scroller,
                                    "colorosmod_clear_letter_highlight", clearHighlight);
                            scroller.postDelayed(clearHighlight, 500L);
                        }
                    });
            log("HOOK OK OplusCOUITouchSearchView#onTouchEvent (clear letter highlight)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusCOUITouchSearchView#onTouchEvent: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.allapps.LetterIndexFastScrollHelper",
                    lpparam.classLoader, "handleUpEvent",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!drawerLetterScroll()) return;
                            try {
                                Object rv = XposedHelpers.callMethod(
                                        param.thisObject, "getActiveRecyclerView");
                                if (rv != null) {
                                    XposedHelpers.callMethod(rv, "onFastScrollComplete");
                                }
                            } catch (Throwable t) {
                                log("drawer letter scroll complete error: " + t);
                            }
                        }
                    });
            log("HOOK OK LetterIndexFastScrollHelper#handleUpEvent (letter scroll)");
        } catch (Throwable t) {
            log("HOOK FAIL handleUpEvent: " + t);
        }
    }

    static int drawerLetterScrollTopOffset(android.view.View rv) {
        android.content.res.Resources res = rv.getResources();
        android.view.View root = rv.getRootView();
        int[] rvLoc = new int[2];
        rv.getLocationOnScreen(rvLoc);
        int bottom = rvLoc[1];
        int headerId = res.getIdentifier("all_apps_header", "id", "com.android.launcher");
        int tabsId = res.getIdentifier("tabs", "id", "com.android.launcher");
        int categoryId = res.getIdentifier("category_tab", "id", "com.android.launcher");
        bottom = Math.max(bottom, overlayBottomOnScreen(root.findViewById(headerId), true));
        bottom = Math.max(bottom, overlayBottomOnScreen(root.findViewById(tabsId), false));
        bottom = Math.max(bottom, overlayBottomOnScreen(root.findViewById(categoryId), false));
        int offset = Math.max(0, bottom - rvLoc[1]);
        // 切换条下面还有一层顶部虚化, 图标贴着切换条仍会发虚。
        int extraFade = 0;
        int fadeId = res.getIdentifier("all_apps_custom_fade_layer_top_fading_height",
                "dimen", "com.android.launcher");
        if (fadeId != 0) extraFade = res.getDimensionPixelSize(fadeId);
        offset += extraFade;
        try {
            Object fade = XposedHelpers.callMethod(rv, "getTopFadeHeightLimit", Boolean.FALSE);
            if (fade instanceof Integer) offset = Math.max(offset, (Integer) fade);
        } catch (Throwable ignored) {
        }
        return offset;
    }

    static int overlayBottomOnScreen(android.view.View v, boolean useMaxTranslation) {
        if (v == null || v.getVisibility() != android.view.View.VISIBLE) return Integer.MIN_VALUE;
        int h = v.getHeight();
        if (useMaxTranslation) {
            try {
                Object t = XposedHelpers.callMethod(v, "getMaxTranslation");
                if (t instanceof Integer) h = Math.max(h, (Integer) t);
            } catch (Throwable ignored) {
            }
        }
        if (h <= 0) return Integer.MIN_VALUE;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[1] + h;
    }

    public static void hookPxRuntime(XC_LoadPackage.LoadPackageParam lpparam,
                                      String className, String methodName, final float density,
                                      final String gateKey, final String dpKey, final int dpDef,
                                      final int dpMax, final int sign) {
        try {
            XposedHelpers.findAndHookMethod(className, lpparam.classLoader, methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBool(gateKey, false)) return;
                            Object ret = param.getResult();
                            if (ret instanceof Integer) {
                                int dp = Math.max(0, Math.min(dpMax, readInt(dpKey, dpDef)));
                                param.setResult((Integer) ret + sign * Math.round(dp * density));
                            }
                        }
                    });
            log("HOOK OK " + className + "#" + methodName);
        } catch (Throwable t) {
            log("HOOK FAIL " + className + "#" + methodName + " :: " + Log.getStackTraceString(t));
        }
    }

    // 动态模糊的最大半径(px), 与系统 PopupScrimView.BLUR_RADIUS / WorkSpaceScrimView 高斯上限一致。
    private static final float POPUP_DYNAMIC_BLUR_MAX_RADIUS = 80f;

    public static void hookPopupBgBlur(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookPopupBgBlurSource(lpparam);
        hookPopupWallpaperBlurRadius(lpparam);
        hookPopupBlurAnim(lpparam);
    }

    private static void hookPopupWallpaperBlurRadius(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> launcherClass = XposedHelpers.findClass(
                    "com.android.launcher.Launcher", lpparam.classLoader);
            Class<?> callbackClass = XposedHelpers.findClass(
                    "com.android.launcher3.popup.EffectResultCallbackImp", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher.wallpaper.WallpaperBlur", lpparam.classLoader,
                    "getBlurredWallpaper", launcherClass, float.class, callbackClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                boolean dynamic = popupDynamicBlurOn();
                                float k = popupBgBrightnessScale();
                                if (!dynamic && k >= 1f) return;
                                if (dynamic) param.args[1] = Float.valueOf(0f);

                                Object cache = XposedHelpers.getObjectField(
                                        param.thisObject, "mBlurCache");
                                if (cache != null) {
                                    XposedHelpers.callMethod(
                                            cache, "setIsBlurCacheGenerated", Boolean.FALSE);
                                }
                            } catch (Throwable t) {
                                log("popup wallpaper blur radius error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher WallpaperBlur#getBlurredWallpaper (popup bg dynamic blur)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher WallpaperBlur#getBlurredWallpaper "
                    + "(popup bg dynamic blur): " + Log.getStackTraceString(t));
        }
    }

    private static void hookPopupBgBlurSource(final XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (popupDynamicBlurOn()) {
                        float radius = (Float) param.args[1];
                        if (Math.abs(radius - 4.0f) < 0.001f) {
                            param.args[1] = Float.valueOf(0f);
                        }
                    }
                    applyPopupBlendBrightness(param.args);
                } catch (Throwable t) {
                    log("popup dragLayer blur radius error: " + t);
                }
            }
        };
        try {
            Class<?> callbackClass = XposedHelpers.findClass(
                    "com.android.launcher3.popup.EffectResultCallbackImp", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.popup.PopupBlurHelper", lpparam.classLoader,
                    "blurBitmap", Bitmap.class, float.class, Context.class, callbackClass,
                    int.class, Color.class, Color.class, float.class, hook);
            log("HOOK OK launcher PopupBlurHelper#blurBitmap(Bitmap) (popup bg dynamic blur)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher PopupBlurHelper#blurBitmap(Bitmap) "
                    + "(popup bg dynamic blur): " + Log.getStackTraceString(t));
        }
        try {
            Class<?> callbackClass = XposedHelpers.findClass(
                    "com.android.launcher3.popup.EffectResultCallbackImp", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.popup.PopupBlurHelper", lpparam.classLoader,
                    "blurBitmap", HardwareBuffer.class, float.class, Context.class, callbackClass,
                    int.class, Color.class, Color.class, float.class, hook);
            log("HOOK OK launcher PopupBlurHelper#blurBitmap(HardwareBuffer) (popup bg dynamic blur)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher PopupBlurHelper#blurBitmap(HardwareBuffer) "
                    + "(popup bg dynamic blur): " + Log.getStackTraceString(t));
        }
    }

    private static void hookPopupBlurAnim(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.popup.PopupBlurView", lpparam.classLoader,
                    "createBlurAnim", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!popupDynamicBlurOn()) return;
                                View view = (View) param.thisObject;
                                boolean open = Boolean.TRUE.equals(param.args[0]);
                                PopupBlurTarget target = popupBlurTarget(view);
                                ObjectAnimator origin = param.getResult() instanceof ObjectAnimator
                                        ? (ObjectAnimator) param.getResult() : null;
                                ObjectAnimator anim = ObjectAnimator.ofFloat(target,
                                        POPUP_BLUR_PROGRESS, target.progress, open ? 1f : 0f);
                                if (origin != null) {
                                    anim.setDuration(origin.getDuration());
                                    if (origin.getInterpolator() != null) {
                                        anim.setInterpolator(origin.getInterpolator());
                                    }
                                }

                                target.opening = open;
                                view.setAlpha(open ? 1f : target.progress);
                                applyPopupBgEffect(view, target.progress);
                                XposedHelpers.setAdditionalInstanceField(view,
                                        "colorosmod_popup_blur_animator", anim);
                                param.setResult(anim);
                            } catch (Throwable t) {
                                log("popup blur anim error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher PopupBlurView#createBlurAnim (popup bg dynamic blur)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher PopupBlurView#createBlurAnim "
                    + "(popup bg dynamic blur): " + Log.getStackTraceString(t));
        }
    }

    public static final class PopupBlurTarget {
        public final View view;
        public float progress;
        public boolean opening = false;

        PopupBlurTarget(View view) {
            this.view = view;
        }
    }

    private static final Property<PopupBlurTarget, Float> POPUP_BLUR_PROGRESS =
            new Property<PopupBlurTarget, Float>(Float.class, "popupBlurProgress") {
                @Override
                public Float get(PopupBlurTarget target) {
                    return target.progress;
                }

                @Override
                public void set(PopupBlurTarget target, Float value) {
                    target.progress = value;
                    applyPopupBgEffect(target.view, value);

                    target.view.setAlpha(target.opening ? 1f : value);
                }
            };

    private static PopupBlurTarget popupBlurTarget(View view) {
        PopupBlurTarget target = (PopupBlurTarget) XposedHelpers.getAdditionalInstanceField(
                view, "colorosmodPopupBlurTarget");
        if (target == null) {
            target = new PopupBlurTarget(view);
            XposedHelpers.setAdditionalInstanceField(view, "colorosmodPopupBlurTarget", target);
        }
        return target;
    }

    private static void applyPopupBgEffect(View view, float progress) {
        if (Build.VERSION.SDK_INT < 31) return;
        float radius = Math.max(0f, progress) * POPUP_DYNAMIC_BLUR_MAX_RADIUS;
        RenderEffect effect = radius > 0.01f
                ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                : null;
        view.setRenderEffect(effect);
    }

    private static void applyPopupBlendBrightness(Object[] args) {
        float k = popupBgBrightnessScale();
        if (k >= 1f) return;
        if (((Integer) args[4]) != 1) return;
        Color blend = (Color) args[5];
        if (blend == null) return;
        args[5] = Color.valueOf(blend.red() * k, blend.green() * k, blend.blue() * k,
                blend.alpha());
    }

    private static float popupBgBrightnessScale() {
        if (!popupBgBrightnessOn()) return 1f;
        int brightness = Math.max(0, Math.min(DESKTOP_POPUP_BG_BRIGHTNESS_MAX,
                readInt(KEY_DESKTOP_POPUP_BG_BRIGHTNESS, DESKTOP_POPUP_BG_BRIGHTNESS_DEFAULT)));
        return brightness / (float) DESKTOP_POPUP_BG_BRIGHTNESS_MAX;
    }

    private static boolean popupDynamicBlurOn() {
        return readBool(KEY_POPUP_DYNAMIC_BLUR_ENABLED, false);
    }

    private static boolean popupBgBrightnessOn() {
        return readBool(KEY_DESKTOP_POPUP_BG_BRIGHTNESS_ENABLED, false);
    }
}
