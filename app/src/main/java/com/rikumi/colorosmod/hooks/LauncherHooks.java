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
import android.graphics.Color;
import android.graphics.RenderEffect;
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
    // com.android.contacts 走"只加入隐藏应用列表、不整包 PMS 禁用"的 path, 联系人进入隐藏应用
    // 但拨号保持可用; 桌面侧在 OplusAppFilter#shouldShowApp 做组件级特例(拨号始终显示)。
    static final android.content.ComponentName CONTACTS_DIALER =
            new android.content.ComponentName("com.android.contacts",
                    "com.android.contacts.DialtactsActivityAlias");

    static final String[][] HIDDEN_LAUNCHER_TARGETS = {
            // 电话本(保留同包拨号 DialtactsActivityAlias)
            {KEY_HIDE_CONTACTS_ENABLED, "com.android.contacts",
                    "com.android.contacts.PeopleActivityAlias"},
            // Gboard 启动入口
            {KEY_HIDE_GBOARD_ENABLED, "com.google.android.inputmethod.latin",
                    "com.google.android.libraries.inputmethod.launcher.LauncherActivity"},
            // GhostLock 启动入口(已有 root 时无需再 root)
            {KEY_HIDE_GHOSTLOCK_ENABLED, "com.ghostlock.app",
                    "com.ghostlock.app.MainActivity"},
    };

    // 运行时根据门控偏好键, 计算当前需要隐藏的组件集合。
    static java.util.Set<android.content.ComponentName> getHiddenLauncherComponents() {
        java.util.Set<android.content.ComponentName> set = new java.util.HashSet<>();
        for (String[] t : HIDDEN_LAUNCHER_TARGETS) {
            if (readBool(t[0], false)) {
                set.add(new android.content.ComponentName(t[1], t[2]));
            }
        }
        return set;
    }

    // 以下为检测"桌面是否处于正常状态(NORMAL)"所需的反射缓存; 仅在 NORMAL 状态才响应手势,
    // 编辑状态(长按桌面进入)下的 pinch-out 交由系统处理(回到正常状态), 不触发本功能。
    static volatile Class<?> sLauncherClass;

    static volatile Class<?> sLauncherStateClass;

    static volatile Object sNormalState;

    // 缩小桌面图标长按菜单。该菜单尺寸由布局与主题属性决定, 不在运行时经 Resources.getDimension* 解析
    // (实测长按时无相关 dimen 被读取), 故资源钩子无效; 改为监听菜单根容器 deep_shortcuts_container 的
    // onAttachedToWindow, 对内部卡片容器做整体 scaleX/scaleY。
    static volatile Class<?> sPopupContainerClass = null;

    // 缩小桌面图标长按菜单: 对 OplusPopupContainerWithArrow 内部的卡片容器(mAllPopupShortcutContainer)
    // 做整体 scaleX/scaleY。该容器承载卡片背景与所有菜单项, 而 popup 打开动画只缩放外层容器、不触碰它,
    // 所以变换恒定生效。轴心设在箭头一侧, 缩放后箭头仍精确指向图标。
    public static void hookPopupMenuDimens(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> popupClass = XposedHelpers.findClass(
                    "com.android.launcher3.popup.OplusPopupContainerWithArrow",
                    lpparam.classLoader);
            sPopupContainerClass = popupClass;
            XposedHelpers.findAndHookMethod(popupClass, "onAttachedToWindow",
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
                            v.post(() -> scalePopupContainer(v));
                        }
                    });
            log("hooked popup menu container scaling");
        } catch (Throwable t) {
            log("hook popup menu container failed: " + t);
        }
    }

    static void scalePopupContainer(android.view.View popupContainer) {
        if (!readBool(KEY_SHRINK_POPUP_MENU, false)) {
            return;
        }
        int pct = Math.max(0, Math.min(20,
                readInt(KEY_POPUP_SCALE_PERCENT, POPUP_SHRINK_PERCENT_DEFAULT)));
        float scale = 1f - pct / 100f;
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

    public static void hookLauncher(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched launcher, classLoader=" + lpparam.classLoader);
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

        // Feature 2 — 页面与 Dock 间距: 始终注入, 运行时按 KEY_INDICATOR_ENABLED 门控。
        // 系统会把 hotseat 高度变化按 workspaceTopPercentage 分摊到页面位置，
        // 因此不能直接减 requestedDp；hook 中会反推实际需要的 hotseat 高度变化。
        hookIndicatorHotseatSize(lpparam, density);

        // Feature 4 — 多任务显示隐藏应用: 始终注入, 运行时按 KEY_RECENTS_SHOW_HIDDEN_ENABLED 门控。
        hookRecentsShowHidden(lpparam);
        // Feature 19 — 多任务不显示小窗应用: 始终注入, 运行时按 KEY_RECENTS_HIDE_FREEFORM_ENABLED 门控。
        hookRecentsHideFreeform(lpparam);

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

        // Feature 11 — 从桌面隐藏指定的单个 LAUNCHER 活动(见 HIDDEN_LAUNCHER_TARGETS 配置表):
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

        // Feature 11(互补) — 若 launcher 直接走 PackageManager.queryIntentActivities 取 LAUNCHER 列表,
        // 同样过滤目标组件。仅对标准 MAIN+LAUNCHER 查询生效, 不影响分享/解析等其它查询; 幂等。
        try {
            Class<?> pmClass = XposedHelpers.findClass(
                    "android.content.pm.PackageManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(pmClass, "queryIntentActivities",
                    android.content.Intent.class, int.class, new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                java.util.Set<android.content.ComponentName> targets =
                                        getHiddenLauncherComponents();
                                if (targets.isEmpty()) return;
                                android.content.Intent intent = (android.content.Intent) param.args[0];
                                if (intent == null) return;
                                // 仅处理标准 LAUNCHER 查询(MAIN + LAUNCHER)。
                                if (!android.content.Intent.ACTION_MAIN.equals(intent.getAction())) return;
                                if (!intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)) return;
                                Object result = param.getResult();
                                if (!(result instanceof java.util.List)) return;
                                java.util.List<Object> list = (java.util.List<Object>) result;
                                java.util.Iterator<Object> it = list.iterator();
                                int removed = 0;
                                while (it.hasNext()) {
                                    Object ri = it.next();
                                    if (ri == null) continue;
                                    Object ai = XposedHelpers.getObjectField(ri, "activityInfo");
                                    if (ai == null) continue;
                                    String pkg = (String) XposedHelpers.getObjectField(ai, "packageName");
                                    String cls = (String) XposedHelpers.getObjectField(ai, "name");
                                    if (pkg == null || cls == null) continue;
                                    if (targets.contains(new android.content.ComponentName(pkg, cls))) {
                                        it.remove();
                                        removed++;
                                    }
                                }
                                if (removed > 0) dbg("[DBG] hide launcher activities via PM removed=" + removed);
                            } catch (Throwable t) {
                                log("hide launcher activities PM hook error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher PackageManager#queryIntentActivities (hide launcher activities)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher queryIntentActivities: " + t);
        }

    // Feature 11b — 修改系统隐藏行为在桌面的呈现: 安全中心侧已改为只把 contacts 加入隐藏列表、不整包禁用
    // (见 hookSafecenterHideContacts), 拨号保持可用。此处仅做组件级特例: 拨号(DialtactsActivityAlias)始终显示,
    // 电话本(PeopleActivityAlias)随包隐藏状态由系统判定, 即"只藏电话本图标、露拨号"。
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
                                // 拨号: 无论联系人是否被系统隐藏, 都强制显示(只藏电话本、露拨号)
                                if (CONTACTS_DIALER.equals(cn)) {
                                    param.setResult(true);
                                }
                            } catch (Throwable t) {
                                log("hide contacts shouldShowApp error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher OplusAppFilter#shouldShowApp (hide contacts system)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher OplusAppFilter#shouldShowApp: " + t);
        }

        // 桌面双指张开(pinch-out)手势打开隐藏应用文件夹: 在 DragLayer 上挂被动 ScaleGestureDetector
        // (不消费事件), 用"当前 span - 起始 span > 阈值"判定而非累计比例, 避免轻微张开误触发;
        // 要求累计放大 > 1.2 做方向校验。仅 NORMAL 状态响应, 编辑态交由系统处理。
        try {
            Class<?> dragLayerClass = XposedHelpers.findClass(
                    "com.android.launcher3.dragndrop.DragLayer", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(dragLayerClass, "dispatchTouchEvent",
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
    // 文件夹打开(含动画)时把模糊强制为 0; 多任务/应用抽屉等无打开文件夹的场景不受影响。
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
                                param.args[0] = 0f;
                            } catch (Throwable t) {
                                log("folder bg blur hook error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher OplusDepthController#setBlur (transparent folder bg)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher OplusDepthController#setBlur: " + t);
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
                                // 模糊量由半径体现, View 全程可见, 否则又变回透明度渐显。
                                view.setAlpha(1f);
                                applyPopupBgEffect(view, target.progress);
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
