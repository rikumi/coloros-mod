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

/**
 * Launcher(com.android.launcher) 作用域的全部 hook：桌面布局、文件夹、编辑模式、弹窗尺寸、多任务。
 */
public final class LauncherHooks {
    // 修改安全中心"隐藏应用"对电话本的处理: 系统原生是整包禁用(会连拨号一起失效), 这里让
    private static final Object sHiddenSettingsLock = new Object();
    private static boolean sHiddenSettingsInitialized;
    private static boolean sNeedsInitialModelReload;
    private static java.util.Set<android.content.ComponentName> sLastHiddenComponents = java.util.Collections.emptySet();

    /** 只在隐藏组件集合改变时刷新 Launcher 模型。 */
    public static void onSettingsSnapshotPublished(java.util.Map<String, Integer> settings) {
        refreshPowerSaveTaskLocks(settingValue(settings, KEY_POWER_SAVE_KEEP_LOCKED_TASKS_ENABLED) == 1);
        if (!sModelReloadActive) return;
        java.util.Set<android.content.ComponentName> components = hiddenLauncherComponents(settings);
        synchronized (sHiddenSettingsLock) {
            boolean initialized = sHiddenSettingsInitialized;
            if (initialized && components.equals(sLastHiddenComponents)) return;
            sHiddenSettingsInitialized = true;
            sLastHiddenComponents = components;
            // 冷启动直接由第一次模型加载过滤；只有设置变化或跨版本热重载才重载模型。
            if (!initialized && !sNeedsInitialModelReload) return;
            sNeedsInitialModelReload = false;
            // 与设置值在同一把锁内计数, 使 captureHotReloadSettings 看到一致的"值 + 是否待刷新"。
            sPendingModelReloads++;
        }

        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        reloadLauncherModelWhenReady(main, 0);
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
            final int attempt) {
        Runnable reload = new Runnable() {
            @Override
            public void run() {
                if (!sModelReloadActive) return;
                try {
                    ClassLoader classLoader = sLauncherClassLoader;
                    if (classLoader == null) {
                        retryLauncherModelReload(main, attempt);
                        return;
                    }
                    Class<?> appStateClass = XposedHelpers.findClass(
                            "com.android.launcher3.LauncherAppState", classLoader);
                    Object appState = XposedHelpers.callStaticMethod(
                            appStateClass, "getInstanceNoCreate");
                    if (appState == null) {
                        retryLauncherModelReload(main, attempt);
                        return;
                    }
                    Object model = XposedHelpers.callMethod(appState, "getModel");
                    if (model == null) {
                        retryLauncherModelReload(main, attempt);
                        return;
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

    private static void retryLauncherModelReload(android.os.Handler main, int attempt) {
        if (attempt < 10) {
            reloadLauncherModelWhenReady(main, attempt + 1);
        } else {
            finishModelReload();
        }
    }

    /**
     * 保存已应用到 Launcher 模型的隐藏开关, 让新 generation 在设置未变时跳过 forceReload(避免桌面闪烁)。
     * 若仍有未完成的刷新(cleanup 会取消它), 返回 null, 让新 generation 首个 snapshot 重新刷新。
     */
    public static Object captureHotReloadSettings() {
        synchronized (sHiddenSettingsLock) {
            if (!sHiddenSettingsInitialized || sPendingModelReloads > 0) return null;
            java.util.ArrayList<String> components = new java.util.ArrayList<>();
            for (android.content.ComponentName component : sLastHiddenComponents)
                components.add(component.flattenToString());
            return components.toArray(new String[0]);
        }
    }

    /** 必须在新 generation 启动设置加载之前调用, 否则首个 snapshot 可能先于种子值发布。 */
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

    // 以下为检测"桌面是否处于正常状态(NORMAL)"所需的反射缓存; 仅在 NORMAL 状态才响应手势,
    // 编辑状态(长按桌面进入)下的 pinch-out 交由系统处理(回到正常状态), 不触发本功能。
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

    // 最近任务壁纸模糊的混色临时清零/恢复: setBlur 用 mBlurBlendColor * blur 构造 COLORMIX
    // 混色, 是背景发灰/提亮的来源; 仅在进入/处于最近任务的那次 setBlur 里清零, 调用后恢复。
    private static final String EXTRA_RECENTS_BLEND = "recentsBlendColor";
    private static final float[] RECENTS_CLEAR_BLEND = new float[]{0f, 0f, 0f, 0f};
    // 手势进入最近任务期间保存的原始混色: doBackGroundAnim(true) 清零, doBackGroundAnim(false) 恢复。
    private static volatile float[] sRecentsSavedBlend;
    private static volatile Object sRecentsSavedDepthController;

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

    // 缩小桌面图标长按菜单。该菜单尺寸由布局与主题属性决定, 不在运行时经 Resources.getDimension* 解析
    // (实测长按时无相关 dimen 被读取), 故资源钩子无效; 改为监听菜单根容器 deep_shortcuts_container 的
    // onAttachedToWindow, 对内部卡片容器做整体 scaleX/scaleY。
    static volatile Class<?> sPopupContainerClass = null;

    // 缩小桌面图标长按菜单: 对 OplusPopupContainerWithArrow 内部的卡片容器(mAllPopupShortcutContainer)
    // 做整体 scaleX/scaleY。该容器承载卡片背景与所有菜单项, 而 popup 打开动画只缩放外层容器、不触碰它,
    // 所以变换恒定生效。"更多功能"使用独立 PopupWindow；保留其动画外层, 只缩放内部视觉卡片。
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
            // "更多功能"的二级菜单属于独立 PopupWindow, 并不在 mAllPopupShortcutContainer 内。
            // 构造完成后子菜单 ListView 和外层卡片均已初始化。hook 全部构造函数可避免依赖
            // OplusPopupContainerWithArrow#setSubPopWindow 的具体签名, 提高 Launcher 小版本兼容性。
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

    // 轴心 = 箭头位置。用 launcher 自带的 calculatePivotX() 得外层坐标系下箭头 x, 再用屏幕坐标差换算到
    // 被缩放的内层卡片坐标系(对任意嵌套都鲁棒), 保证左/右/居中弹出时菜单都围绕箭头缩放而不整体偏移。
    // 垂直方向: 弹出在图标上方(mIsAboveIcon)则箭头在卡片底边 -> pivotY=h, 否则在顶边 -> 0。
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

    // 外层 RoundFrameLayout 是 COUI 原生动画直接控制的对象, 不能缩放它。保持外层的位置、裁切、
    // translation 与动画完全不变, 仅缩放内部 ListView。把卡片背景移到 ListView 后, 视觉上仍是整张
    // 卡片缩小。原生窗口左上角已与一级卡片的原始左上角对齐, 因此使用同一个局部缩放矩阵
    // (pivot=0,0), 菜单项的内距也会按相同比例变化；无需追踪或补偿任何其它元素的位置。
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
                // RoundFrameLayout.dispatchDraw() 无空值检查地调用 background.setBounds(),
                // 因此外层必须保留一个非空背景；透明 ColorDrawable 不参与视觉绘制。
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

    // RoundFrameLayout 的原生动画会更新自身 Outline, 但复用同一 provider 的 ListView 不会自动收到
    // invalidateOutline。通过公开 View API 比较它自己的轮廓, 仅在矩形或透明度变化时刷新内层缓存；
    // 不依赖 COUI 动画控制器的混淆方法名和字段名, 动画结束后也不会持续触发重绘。
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
        if (sRecentsSavedBlend != null && sRecentsSavedDepthController != null) {
            saved.add(new Object[] { "recentsBlend", sRecentsSavedDepthController,
                    sRecentsSavedBlend.clone() });
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
                    System.arraycopy(RECENTS_CLEAR_BLEND, 0, blend, 0,
                            Math.min(blend.length, RECENTS_CLEAR_BLEND.length));
                    sRecentsSavedDepthController = depthController;
                    sRecentsSavedBlend = savedBlend;
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
        sLastPowerSaveTaskLockEnabled = null;
        new android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacksAndMessages(sPowerSaveTaskLockToken);
        // 在主线程执行: 置 false 后再移除队列, 保证旧 generation 不会再调用 forceReload。
        sModelReloadActive = false;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .removeCallbacksAndMessages(sModelReloadToken);
        if (sRecentsSavedBlend != null && sRecentsSavedDepthController != null) {
            try {
                float[] blend = (float[]) XposedHelpers.getObjectField(
                        sRecentsSavedDepthController, "mBlurBlendColor");
                System.arraycopy(sRecentsSavedBlend, 0, blend, 0,
                        Math.min(blend.length, sRecentsSavedBlend.length));
            } catch (Throwable t) {
                log("launcher recents blend cleanup failed: " + t);
            }
        }
        sRecentsSavedBlend = null;
        sRecentsSavedDepthController = null;
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

    // 每个 DeepShortcutView 内的 R.id.divider 是列表项之间的分割线, 其高度来自
    // @dimen/coui_list_divider_height(物理 1px)。整体被 scaleX/Y 缩小 scale 后渲染成 sub-pixel 不可见。
    // 把它改大为 1px / scale(向上取整), 缩小后恰好渲染成约 1px 的细线。
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
        // 目标: 整体缩小 scale 后分割线仍渲染出 1px。
        // 故预先把高度设为 1px / scale, 向上取整保证缩小后至少 1px(整数布局高度)。
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
        try {
            XposedHelpers.findAndHookMethod("com.android.launcher3.BubbleTextView", lp.classLoader,
                    "isShouldShowGreenDot", boolean.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readBoolCached(KEY_HIDE_LAUNCHER_UPDATE_DOT_ENABLED, false))
                                p.setResult(Boolean.FALSE);
                        }
                    });
        } catch (Throwable t) { log("launcher update dot hook failed: " + t); }
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
        } catch (Throwable t) { log("launcher secondary menu hook failed: " + t); }
    }

    public static void hookLauncher(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched launcher, classLoader=" + lpparam.classLoader);
        sLauncherClassLoader = lpparam.classLoader;
        hookUpdateDotAndSecondaryMenu(lpparam);
        hookPowerSaveTaskLocks(lpparam);
        float density = readDensity();

        // Feature 12 — 缩小长按菜单: 在 launcher 进程内拦截 Resources.getDimension*, 对菜单 dimen 缩放。
        hookPopupMenuDimens(lpparam);

        // Feature 24/25 — 桌面长按菜单背景: 动态模糊 + 自定义背景亮度。
        // 始终注入, 运行时按各自开关门控。
        hookPopupBgBlur(lpparam);


        // Feature 1 — 图标间距: 始终注入, 运行时按 KEY_ICON_GAP_ENABLED 门控(关闭返回原值),
        // 间距值由 KEY_ICON_GAP_DP(0-8dp, 默认 4dp) 在运行时读取, App 内拖滑条即时生效。
        hookPxRuntime(lpparam, "com.android.launcher.layoutparam.IconParam",
                "getIconDrawablePaddingPx", density, KEY_ICON_GAP_ENABLED, KEY_ICON_GAP_DP, ICON_GAP_DP, 8, 1);
        hookPxRuntime(lpparam, "com.android.launcher.layoutparam.AllAppsParam",
                "getAllAppsIconDrawablePaddingPx", density, KEY_ICON_GAP_ENABLED, KEY_ICON_GAP_DP, ICON_GAP_DP, 8, 1);

        // 调整抽屉图标大小与间距: 始终注入, 运行时按 KEY_DRAWER_LAYOUT_ENABLED 门控。
        // 只改抽屉尺寸和间距，列数保持系统设置。
        hookDrawerLayout(lpparam);

        // 字母索引滚动定位: 始终注入, 运行时按 KEY_DRAWER_LETTER_SCROLL_ENABLED 门控。
        hookDrawerLetterScroll(lpparam);

        // Feature 2 — 页面与 Dock 间距: 始终注入, 运行时按 KEY_INDICATOR_ENABLED 门控。
        // 系统会把 hotseat 高度变化按 workspaceTopPercentage 分摊到页面位置，
        // 因此不能直接减 requestedDp；hook 中会反推实际需要的 hotseat 高度变化。
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

        // 隐藏应用文件夹标题显示用户自定义文件夹名: 标题由 DeepProtectedAppsManager
        // #createVirtualFolder() 硬编码为 R.string.app_hidden_title, hook 它并在返回后把
        // folderInfo.title 替换为用户在 OplusFavoritesProvider 中自定义的名称。
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

        // Feature 11 — 从桌面隐藏指定的单个 LAUNCHER 活动(按设置中的组件列表):
        // hook LauncherApps.getActivityList, 在结果中剔除已开启门控的目标组件。
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

        // 桌面双指张开(pinch-out)手势打开隐藏应用文件夹: 在 DragLayer 上挂被动 ScaleGestureDetector
        // (不消费事件), 用"当前 span - 起始 span > 阈值"判定而非累计比例, 避免轻微张开误触发;
        // 要求累计放大 > 1.2 做方向校验。仅 NORMAL 状态响应, 编辑态交由系统处理。
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

    /** Feature 9 — 通过 launcher 内部 API 打开隐藏应用(深度保护)文件夹。 */
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

    // 判断桌面是否处于 NORMAL 状态。DragLayer 的 context 即 Launcher 实例, 通过
    // Launcher#isInState(LauncherState.NORMAL) 判定。反射结果做缓存; 任何异常均保守返回 false(不响应手势)。
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

    // 桌面文件夹展开背景透明化: 展开时系统对壁纸施加 blur=1.0 + mBlurBlendColor 暗色。
    // 所有壁纸模糊都汇入 OplusDepthController.setBlur(float, boolean)(唯一收口点), hook 它在有
    // 文件夹打开(含动画)且停留在桌面时把模糊强制为 0。
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
                                // 只在停留/前往桌面时生效, 否则(详见 isLauncherOnWorkspace)打开文件夹后
                                // 上滑进多任务会连多任务的遮罩一起去掉。
                                // 进入后台(BACKGROUND_APP)时同样必须归零: 从文件夹点应用启动时
                                // setState 仍按"文件夹开着"把 blur 取成 getFolderBlur()=1.0,
                                // 若不归零, 这个 1.0 会留在 mBlur 里; 从应用返回桌面时若正好
                                // handleInvalidSurface 成立(直接 return, 且 onDraw 里 surface
                                // 设置失败会再按文件夹开着置回 1.0), 模糊就被带了回来。
                                if (!isLauncherOnWorkspace(launcher, lpparam.classLoader)
                                        && !isLauncherBackgroundApp(launcher, lpparam.classLoader)) return;
                                param.args[0] = 0f;
                            } catch (Throwable t) {
                                log("folder bg blur hook error: " + t);
                            }
                        }
                    });
            // 从最近任务返回桌面时，文件夹仍保持打开：setState 会再次把目标 blur 算作 1.0，
            // 但若进入最近任务时 mBlur 已经是 1.0，它不会调用 setBlur，导致文件夹页面保留遮罩。
            // 在 NORMAL 状态切换完成后显式归零，覆盖有动画和无动画两条状态切换路径。
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

    // 桌面是否停在/正在前往 NORMAL(桌面)状态。
    // OplusDepthController#setState 中, 只要 getOpenFolder() != null 就把 blur 取成 1.0, 与切到哪个
    // 状态无关: 因此打开文件夹后上滑进多任务, 系统会按"文件夹开着"给多任务也加模糊, 而我们无条件
    // 归零就会把多任务的遮罩一并去掉。故这里加一层状态判定, 只在桌面上才让文件夹背景透明。
    // 状态取 OPlusBaseState#getTargetLauncherState(静态, StateManager#goToState 一进来就写入目标状态,
    // 切换动画期间它已是新状态, 而 mLauncher 的当前状态此时还是旧的), 取不到时退回 StateManager#getState。
    static boolean isLauncherOnWorkspace(Object launcher, ClassLoader cl) {
        Object state = launcherTargetState(cl);
        if (state == null) state = launcherCurrentState(launcher);
        if (state == null) return false;
        return state == launcherNormalState(cl);
    }

    // 桌面是否停在/正在前往最近任务(OVERVIEW)。上滑手势进入最近任务的过渡期间,
    // target 已由 StateManager#goToState(OVERVIEW) 写入, 手势驱动 setBlur 前一刻即生效。
    static boolean isLauncherInOverview(Object launcher, ClassLoader cl) {
        Object state = launcherTargetState(cl);
        if (state == null) state = launcherCurrentState(launcher);
        if (state == null) return false;
        return state == launcherOverviewState(cl);
    }

    // 桌面是否正在进入后台(BACKGROUND_APP: 从文件夹启动应用, 或按 Home 离开桌面)。
    // 此时 Launcher 窗口被应用盖住, 模糊值本身没有视觉影响, 但会被记进 mBlur。
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

    // 调整桌面文件夹展开/收起动画持续时间。ColorOS 有两条路径, 只 hook Resources.getInteger 不够: 普通
    // 动画用 spring 物理动画(时长由 response 决定), light 动画用 ObjectAnimator + setDuration(150/400ms)。
    // 故分别覆盖 spring response、getAnimDuration、getLightFolderContentAnimation, 并保留 getInteger 覆盖。
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

    // 普通 spring 路径: 文件夹子图标/标题/页脚动画全部经 RtSpringAnimatorWrapper 包装,
    // spring force 的 response(响应时间, 秒)决定动画时长, 这里统一改为 ms/1000。
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
        } catch (Throwable t) {
            log("HOOK FAIL launcher RtSpringAnimatorWrapper (folder spring duration): " + t);
        }
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
            final Class<?> propsHolderClass = XposedHelpers.findClass(
                    "com.android.launcher3.anim.light.FolderAnimPropsHolder", lpparam.classLoader);
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
            // 隐藏应用文件夹(AppHiddenFolder)在动画 props 无效时回退的超轻量动画:
            // getAnimator() 中跳过 spring 路径后走这里, 硬编码 360/300ms, 统一覆盖。
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
            final int[] folderAnimResIds = {
                    0x7f0b0028, // config_materialFolderExpandDuration (FolderAnimationManager.mDuration)
                    0x7f0b0072, // folder_close_duration
                    0x7f0b0073, // folder_light_close_duration
                    0x7f0b0074, // folder_light_open_duration
                    0x7f0b0075, // folder_open_duration
            };
            XposedHelpers.findAndHookMethod(android.content.res.Resources.class, "getInteger",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int id = (Integer) param.args[0];
                                boolean hit = false;
                                for (int rid : folderAnimResIds) {
                                    if (rid == id) {
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

    // 多任务背景保留壁纸模糊, 但去掉两处"变灰/提亮"的来源: 一是 OplusDepthController#setBlur
    // 里用 mBlurBlendColor(深蓝灰) 注入的 COLORMIX 混色, 二是动态 blur 不可用时 OplusOverviewScrim
    // 绘制的纯色遮罩回退。混色只在进入/处于最近任务时临时清零, 调用结束即恢复, 不影响其它场景。
    // 截图由 OplusTaskThumbnailViewImpl 以平滑圆角 Path 绘制；复用该 Path 仅为截图区域描边，
    // 并与系统的各角形状、动画裁切范围完全一致。
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

            // 保留模糊本身(blur 半径), 只把模糊附带的混色清零: setBlur 内部用
            // mBlurBlendColor * blur 构造 COLORMIX 混色, 是背景发灰/提亮的根源。
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
                                        || !isLauncherInOverview(launcher, lpparam.classLoader)) {
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

            // 手势进入最近任务时(doBackGroundAnim(true)), blur 动画在 goToState(OVERVIEW) 之前
            // 就已启动, setBlur 的状态判定来不及生效; 直接在 blur 驱动入口把混色清零, 返回桌面
            // (doBackGroundAnim(false)) 再恢复, 覆盖手势停顿与松手后的整个过渡阶段。
            Class<?> swipeHelperClass = XposedHelpers.findClass(
                    "com.android.quickstep.touch.SwipeToRecentAnimationHelper", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(swipeHelperClass, "doBackGroundAnim", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_RECENTS_BG_TRANSPARENT_ENABLED, false)) return;
                            try {
                                Object depthController = XposedHelpers.getObjectField(
                                        param.thisObject, "mDepthController");
                                if (depthController == null) return;
                                float[] blend = (float[]) XposedHelpers.getObjectField(
                                        depthController, "mBlurBlendColor");
                                if ((Boolean) param.args[0]) {
                                    if (sRecentsSavedBlend == null) {
                                        sRecentsSavedBlend = blend.clone();
                                        sRecentsSavedDepthController = depthController;
                                    }
                                    System.arraycopy(RECENTS_CLEAR_BLEND, 0, blend, 0,
                                            blend.length);
                                } else if (sRecentsSavedBlend != null) {
                                    System.arraycopy(sRecentsSavedBlend, 0, blend, 0,
                                            blend.length);
                                    sRecentsSavedBlend = null;
                                    sRecentsSavedDepthController = null;
                                }
                            } catch (Throwable t) {
                                log("recents swipe blend hook error: " + t);
                            }
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
            log("HOOK OK launcher OplusDepthController/SwipeToRecentAnimationHelper/"
                    + "OplusOverviewScrim/OplusTaskThumbnailViewImpl (transparent recents bg)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher transparent recents bg: " + Log.getStackTraceString(t));
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

    // 取消桌面编辑模式的背景遮罩: ToggleBarState / PagePreviewState 把编辑态壁纸 blur 固定为 1.0f。
    // 只改最终 setBlur 会错过状态切换动画, 因此直接在状态提供目标值的方法上返回 0,
    // 进入和退出编辑态都保持幂等。
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

    // 多任务(quickstep)显示被系统隐藏的应用: 系统"隐藏应用"经 OplusPrivacyManager.isHiddenPkg 判定,
    // 最近任务在 OplusRecentTasksFilter.filterTaskInfo 据此剔除隐藏任务, OplusRecentsViewImpl 据此跳过 stub。
    // 这里加 beforeHook: 调用方位于 com.android.quickstep 多任务渲染/手势路径时返回 false。应用锁不受影响。
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

    // 多任务不显示小窗应用: OplusRecentTasksFilter#filterTaskInfo 逐任务过滤(返回 true 即剔除),
    // hook 它在开关开启且任务为小窗(isFlexibleFloatingWindow)时 setResult(true) 剔除卡片。
    // 应用本身仍在前台运行, 不受影响。
    public static void hookRecentsHideFreeform(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final String flag = KEY_RECENTS_HIDE_FREEFORM_ENABLED;
            XposedHelpers.findAndHookMethod(
                    "com.oplus.quickstep.data.OplusRecentTasksFilter",
                    lpparam.classLoader, "filterTaskInfo",
                    int.class, int.class,
                    "com.android.wm.shell.shared.GroupedTaskInfo",
                    "java.util.ArrayList",
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

    // 多任务隐藏未在运行的应用: 剔除已经没有存活 Activity 的任务卡片。
    // 判据 android.app.TaskInfo#isRunning(PUBLIC boolean, 已 dexdump 核对):
    // system_server 侧 Task#fillTaskInfo 里 info.isRunning = (top != null), 即任务是否还有
    // 存活的 Activity; 应用被杀/任务被销毁后为 false, 卡片留在最近任务里但已不"运行"。
    // 分屏任务(taskInfo2 != null)要求两个任务都不在运行才隐藏, 避免误杀掉一半的组合。
    public static void hookRecentsHideNotRunning(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final String flag = KEY_RECENTS_HIDE_NOT_RUNNING_ENABLED;
            XposedHelpers.findAndHookMethod(
                    "com.oplus.quickstep.data.OplusRecentTasksFilter",
                    lpparam.classLoader, "filterTaskInfo",
                    int.class, int.class,
                    "com.android.wm.shell.shared.GroupedTaskInfo",
                    "java.util.ArrayList",
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

    // 该 GroupedTaskInfo 里指定的那一半任务是否还在运行; 取不到(单任务时 taskInfo2 为 null)
    // 视为不在运行, 由调用方决定是否隐藏。
    private static boolean isTaskInfoRunning(Object groupedTaskInfo, String getter) {
        try {
            Object taskInfo = XposedHelpers.callMethod(groupedTaskInfo, getter);
            if (taskInfo == null) return false;
            return XposedHelpers.getBooleanField(taskInfo, "isRunning");
        } catch (Throwable ignored) { }
        return false;
    }

    // 多任务上划彻底结束进程。
    // 上划卡片的完整链路(网格/栈/堆叠三种布局统一):
    //   TaskViewTouchController(上划) -> OplusRecentsViewImpl#createTaskDismissAnimation
    //   -> delegate(Stack/Grid/Tile) -> RecentsViewAnimUtil 的 dismiss 动画结束监听
    //   -> handleSuccessfulDismiss -> createTaskRemovalRunnable -> OplusRecentsViewImpl#removeTask
    //   -> KillAppWrapper.forceStopTasks(ctx, task) -> (异步执行器)
    //   -> KillAppWrapper#forceStopAppList(ctx, list, null, false) -> athena type=13(STOP)。
    // forceStopAppList 是唯一收口, 末位 boolean 决定请求类型: false=13(STOP, 只停任务)、
    // true=11(KILL_OR_STOP, 杀进程)。两条下发路径(Osense 新 API 与 startService 老 API)都读它。
    // 注意 forceStopTasks 是提交到 OplusExecutors 异步执行的, ThreadLocal/调用栈都跨不了线程,
    // 所以只能在 forceStopAppList 本身上按开关改写入参。
    //
    // 真正结束进程不在这里做: Launcher 没权限(见下文 requestForceStop)。本方法只负责
    //   (a) 补调 removeTask 让卡片消失(newApiSupport 时系统自己会跳过),
    //   (b) 决定要不要强杀、杀整包还是只杀这一个任务。
    // 判定口径:
    //   主任务 + 附属开关开 -> 连同该包剩余附属任务一起移除, 并整包强杀;
    //   附属任务           -> 只定向杀它自己所在进程, 主进程要留着;
    //   两者都判定不了     -> 退回"同包没有别的卡片才整包强杀"。
    private static final ThreadLocal<String> sPendingKillPkg = new ThreadLocal<>();
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
                                    collectTaskIdsOfPkg(param.thisObject, pkg, others, taskId);
                                    if (!others.isEmpty()) {
                                        sPendingExtraTaskIds.set(others);
                                        // 清完附属任务后该包一个不剩, 整包强杀。
                                        // 本开关的语义就是 "kill + 从最近任务移除", 所以不额外
                                        // 再要求"彻底结束进程"开关; 没有附属任务时才退回那个开关。
                                        sPendingKillPkg.set(pkg);
                                        return;
                                    }
                                } else if (kill && Boolean.FALSE.equals(main)) {
                                    // 划掉附属任务: 只杀它自己所在进程, 主进程要留着
                                    sPendingKillTaskId.set(taskId);
                                    return;
                                }
                                if (kill && countTaskViewsOfPkg(param.thisObject, pkg) <= 1) {
                                    // 常规规则: 同包没有别的卡片才整包强杀
                                    sPendingKillPkg.set(pkg);
                                }
                            } catch (Throwable ignored) { }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String pkg = sPendingKillPkg.get();
                                Integer removeId = sPendingRemoveId.get();
                                java.util.List<Integer> extra = sPendingExtraTaskIds.get();
                                Integer killTaskId = sPendingKillTaskId.get();
                                sPendingKillPkg.set(null);
                                sPendingRemoveId.set(null);
                                sPendingExtraTaskIds.set(null);
                                sPendingKillTaskId.set(null);
                                if (removeId == null) return;
                                Context ctx = (Context) XposedHelpers.callMethod(
                                        param.thisObject, "getContext");
                                // newApiSupport()==true 时系统自己跳过 removeTask, 只把请求
                                // 交给 athena(不执行), 卡片不消失, 这里补调一次。
                                // 不论后面要不要杀进程, 卡片都必须移除, 否则划掉它不会消失。
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
                                if (pkg != null) {
                                    requestForceStop(ctx, pkg);
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
                                    all.add(pkg);
                                    if (isLockedTaskView(tv, lpparam)) keep.add(pkg);
                                }
                                // 附属任务与主任务同包, 只要该包有未锁定的卡片, 全部清除就会
                                // 把它们一起划掉, 清完之后整包一个不剩 -> 杀。
                                for (String pkg : all) {
                                    if (!keep.contains(pkg)) requestForceStop(ctx, pkg);
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

    // RecentsView 里该包还剩下几张卡片(getTaskViewCount/getTaskViewAt 均为 OplusRecentsViewImpl
    // 的公开方法, 已 dexdump 核对)。
    private static int countTaskViewsOfPkg(Object recentsView, String pkg) {
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
                if (pkg.equals(XposedHelpers.callMethod(key, "getPackageName"))) count++;
            }
        } catch (Throwable ignored) { }
        return count;
    }

    // 收集 RecentsView 里该包其它任务(排除 excludeId)的任务 id。
    private static void collectTaskIdsOfPkg(Object recentsView, String pkg,
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
                if (!pkg.equals(XposedHelpers.callMethod(key, "getPackageName"))) continue;
                int id = (Integer) XposedHelpers.getObjectField(key, "id");
                if (id != excludeId) out.add(id);
            }
        } catch (Throwable ignored) { }
    }

    // 该任务是不是主任务: 任务的根组件是否属于该包的 launcher 入口。
    // 用 LauncherApps.getActivityList() 拿该包全部入口(alias 也在内), 所以 splash/alias 启动的
    // 应用其主任务仍判为主任务; 而小程序这类任务的根组件不在入口列表里 -> 判为附属。
    // 与系统 OplusTaskUtils#getTitle 的比对方式一致: key.baseIntent.getComponent()。
    // 返回 null 表示判定不了(此时调用方会退回保守逻辑, 不做附属清理)。
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
                    la.getActivityList(pkg, android.os.Process.myUserHandle());
            if (list == null || list.isEmpty()) return null;
            for (android.content.pm.LauncherActivityInfo info : list) {
                if (base.equals(info.getComponentName())) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable ignored) { }
        return null;
    }

    // 卡片是否被锁定(锁定的卡片上划不掉、清除全部时也会保留)。复用系统自己的判定,
    // 与 createAllTasksDismissAnimation 决定是否给它消失动画用的是同一个方法。
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

    // 请求彻底结束进程。Launcher 是普通应用, 没有 FORCE_STOP_PACKAGES, 反射调
    // ActivityManager#forceStopPackage 必被 SecurityException 拒(KernelSU 也不给 app root),
    // 所以借道 ActivityManager#killBackgroundProcesses 把包名送进 system_server ——
    // 那边的 hook 会拦下这次调用并升级成 AMS#forceStopPackage(见 SystemServerHooks)。
    private static void requestForceStop(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(pkg);
        } catch (Throwable ignored) { }
    }

    // 定向杀掉某个任务所在的进程(用于划掉小程序: 只关这一个, 不能整包强杀)。
    // 与 requestForceStop 走同一个通道, 用 "cmtask:<taskId>" 前缀区分; 两端都是本模块的 hook,
    // 且 system_server 侧会 setResult 跳掉原方法, 不会真的去杀这个"不存在的包名"。
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

    // 判断本次 isHiddenPkg 的调用方是否位于 quickstep 多任务渲染/手势路径
    // (最近任务列表过滤与 recents 视图均在 com.android.quickstep 包下; 应用锁不调此方法)
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

    // 系统布局把 hotseat 高度变化按 workspaceTopPercentage 分摊到 Workspace 顶部 padding, hotseat 缩短
    // x 像素时页面实际只移动 x*(1-percentage)。这里反推缩短量, 使设置中的 dp 值对应真实页面到 Dock 的间距变化。
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
        return readBool(KEY_DRAWER_LETTER_SCROLL_ENABLED, false);
    }

    // 图标间左右间隔保留比例: 缩小八分之一即保留 7/8。
    static final float DRAWER_ICON_GAP_KEEP = 0.875f;
    static final int DISPLAY_ALL_APPS = 1;

    // 只在当前页面实际显示右侧字母条时应用抽屉布局调整。
    // 分类页没有字母条，保留系统原来的尺寸与边距。
    static boolean drawerHasVisibleLetterScroller(android.view.View anchor) {
        int id = anchor.getResources().getIdentifier(
                "coui_fast_scroller", "id", "com.android.launcher");
        if (id == 0) return false;
        android.view.View scroller = anchor.getRootView().findViewById(id);
        return scroller != null && scroller.getVisibility() == android.view.View.VISIBLE
                && scroller.isShown();
    }

    // 用系统右侧 padding(未改过)反推左侧并扣掉字母条宽度。
    // 字母条布局写死 28dp, 不能按 View.getWidth() 取 —— 第一次 apply 时还没 layout。
    static int drawerAdjustedLeftPadding(int systemPx, float density) {
        int minLeft = Math.round(4f * density);
        int letterBar = Math.round(28f * density);
        return Math.max(minLeft, systemPx - letterBar);
    }

    // onLayout 时抽屉及字母条的父容器可能仍不可见，不能在这里固化边距。
    // 绘制前再按最终可见状态调整；边距改变时取消这一帧，等图标重新布局后再显示。
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
            // 测量可能发生在页签暂时隐藏时，沿用该 RecyclerView 最后确认的状态。
            // 状态只在绘制前更新；未知状态留给首次 pre-draw，避免隐藏/显示时反复恢复尺寸。
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
    // 已确认状态只允许在 pager 停稳后由当前页更新，不能用目标页的字母条改动其它页。
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

    // 首次打开抽屉时预备 work，防止它滑入后才缩小。仅在当前字母条已显示、
    // 系统确认这是启用中的 work RecyclerView 时执行；分类页不会使用这条路径。
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

    // 每页独立保存已确认状态；未挂载或隐藏中的测量不会改变页面状态。
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

    // 抽屉右侧字母索引: 系统点字母走 ClusterAppsContainer, 弹出该字母的图标分组;
    // 同时 injectScrollToPositionAtProgress 在桌面抽屉(mLauncher != null)里故意不滚动。
    // 开启后拦下分组切换, 并把列表滚到对应分区。
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
                                // 不用 smoothScrollToSection 的像素累加: 格子高度和实际行高
                                // 对不齐时会多滚一行, 每字母差不多一行时就变成点 A 出 B。
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
                                // 复用桌面自己的 TopSmoothScroller。START + margin 会让目标行
                                // 平滑停在浮动 header/顶部虚化层下方，同时避免按估算行高累加
                                // 导致字母定位偏一行。
                                Object scroller = XposedHelpers.getObjectField(
                                        param.thisObject, "mSmoothScroller");
                                int topOffset = drawerLetterScrollTopOffset(rv);
                                XposedHelpers.callMethod(scroller, "setGravity",
                                        android.view.Gravity.START);
                                XposedHelpers.callMethod(scroller, "setMargin", topOffset);
                                XposedHelpers.callMethod(scroller, "setTargetPosition", pos);
                                // LinearSmoothScroller 的减速阶段约为线性滚动时间 / 0.3356。
                                // 按目标距离动态提速，使完整的近距离减速动画最长为 350ms；
                                // 长距离寻位阶段也会随距离同比提速。
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
            // 不写死 RecyclerView 打包后可能变化的父类混淆名（当前版本为 c0）。
            // findAndHookMethod 会从稳定的桌面入口 TopSmoothScroller 向上查找声明类。
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

    // ColorOS 抽屉把 personal/work 切换条(以及 header 里其它行)浮在 RecyclerView 上,
    // paddingTop 是 0。只按 tabs.getHeight() 不够: 系统真正留给内容的是
    // FloatingHeaderView#getMaxTranslation(含 header 内边距/底部调整)。
    // 用遮挡层在 RV 坐标系里的底边做 offset, 把目标行顶到可见区域。
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

    // 通用像素增量 hook: delta 在运行时按 dpKey 滑条值(默认 dpDef)计算, sign 为 +1 叠加 / -1 缩减;
    // 开关(gateKey)关闭则返回原值。与 hookPx 的区别是增量值不在注入时固定, App 内拖滑条即时生效。
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


    // Feature 25 动态模糊 + Feature 24 背景亮度。系统把"预烘焙模糊壁纸 + dragLayer 截图(半径 4)"装进
    // PopupBlurView 后只做 ALPHA 渐显, 模糊量恒定。动态模糊需三处配合(详见各 hook 处): WallpaperBlur
    // #getBlurredWallpaper 半径改 0 并作废缓存(命中缓存时不再模糊)、PopupBlurHelper#blurBitmap 半径改 0。
    public static void hookPopupBgBlur(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookPopupBgBlurSource(lpparam);
        hookPopupWallpaperBlurRadius(lpparam);
        hookPopupBlurAnim(lpparam);
    }

    /** 壁纸: 动态模糊时半径置 0; 两者任一开启都要作废预烘焙缓存, 让 blurBitmap 每次都跑。 */
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
                                // 缓存里存的是系统预烘焙的模糊壁纸(且混入的是未调整亮度的颜色),
                                // 命中时原方法直接返回、完全不走 blurBitmap, 故必须作废。
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

    // dragLayer 截图(半径 4.0)置 0, 图标层交给动态模糊; 壁纸层缩放混入色以调整背景亮度。
    // 两个功能都落在这里 —— 这是壁纸与截图两条路径唯一的公共入口。
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

    /** 把系统的 ALPHA 渐显换成半径渐增的高斯模糊。 */
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
                                // 打开时背景保持可见(动态模糊由半径体现, 不靠透明度渐显);
                                // 关闭时让 alpha 跟随 progress 淡出, 这样长按后拖动取消菜单时能
                                // 透出后面真实图标、看到其它图标的避让运动。
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

    /** createBlurAnim 的驱动目标: 借 ObjectAnimator 的 Property 机制, 避开反射 setter 的兼容问题。 */
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
                    // 打开时背景始终可见(动态模糊由半径体现); 关闭时 alpha 随 progress 淡出,
                    // 透出后面真实图标与避让运动。
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

    /** 对 PopupBlurView 施加动态高斯模糊; progress=0 时清空效果。 */
    private static void applyPopupBgEffect(View view, float progress) {
        if (Build.VERSION.SDK_INT < 31) return;
        float radius = Math.max(0f, progress) * POPUP_DYNAMIC_BLUR_MAX_RADIUS;
        RenderEffect effect = radius > 0.01f
                ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                : null;
        view.setRenderEffect(effect);
    }

    // 长按背景亮度: 壁纸层以 blendMode=1(ONLY_MASK) 混入 popup_blur_blend_color, 结果
    // out = wp*(1-a) + blendRGB*a, blendRGB 即系统硬加的"最低亮度"。把 blendRGB 缩放到 k 倍即可线性抵消。
    // 走系统自己的混合链路(与模糊正交), 而非外叠颜色滤镜 —— 后者依赖合成顺序且实测无效。
    private static void applyPopupBlendBrightness(Object[] args) {
        float k = popupBgBrightnessScale();
        if (k >= 1f) return;
        if (((Integer) args[4]) != 1) return;
        Color blend = (Color) args[5];
        if (blend == null) return;
        args[5] = Color.valueOf(blend.red() * k, blend.green() * k, blend.blue() * k,
                blend.alpha());
    }

    /** 背景亮度系数 k: 1 = 系统默认, 0 = 完全去掉系统抬的最低亮度。开关关闭时为 1。 */
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
