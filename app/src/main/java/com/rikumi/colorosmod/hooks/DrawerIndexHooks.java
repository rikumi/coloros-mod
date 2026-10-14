package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.view.MotionEvent;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Launcher 16.6.11 的字母条显示、命中和分组入口；不修改原生或模块索引偏好。 */
public final class DrawerIndexHooks {
    private static final String DRAWER_VIEW = "colorosmod_drawer_index_view";
    private static final Map<Object, Boolean> helpers = Collections.synchronizedMap(new WeakHashMap<>());

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> helper = XposedHelpers.findClass(
                    "com.android.launcher3.allapps.LetterIndexFastScrollHelper", lp.classLoader);
            XposedBridge.hookAllConstructors(helper, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!isDrawer(p.thisObject)) return;
                    helpers.put(p.thisObject, Boolean.TRUE);
                    Object view = XposedHelpers.callMethod(p.thisObject, "getMFastScroller");
                    if (view != null) {
                        XposedHelpers.setAdditionalInstanceField(view, DRAWER_VIEW, Boolean.TRUE);
                        if (DrawerInstallTimeHooks.enabled()) XposedHelpers.callMethod(view, "setVisibility", 8);
                    }
                    // 容器在构造返回后才设置 mScrollHelper，随后原生调用 updateFastScrollerVisibility。
                }
            });
            XposedHelpers.findAndHookMethod(helper, "getFastScrollerVisibility", Integer.class,
                    int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (blocked(p.thisObject)) p.setResult(8);
                        }
                    });
            for (String method : new String[]{"isHitInParent", "isInContinueEvent"}) {
                XC_MethodHook guard = new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (blocked(p.thisObject)) p.setResult(false);
                    }
                };
                if ("isHitInParent".equals(method)) {
                    XposedHelpers.findAndHookMethod(helper, method, MotionEvent.class, guard);
                } else XposedHelpers.findAndHookMethod(helper, method, guard);
            }
            XposedHelpers.findAndHookMethod(helper, "onKey", int.class, int.class, int.class,
                    CharSequence.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (blocked(p.thisObject)) p.setResult(null);
                        }
                    });
            Class<?> layout = XposedHelpers.findClass(
                    "com.android.launcher3.allapps.OplusFastScrollLayout", lp.classLoader);
            for (String method : new String[]{"onInterceptTouchEvent", "onTouchEvent"}) {
                XposedHelpers.findAndHookDeclaredMethod(layout, method, MotionEvent.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (DrawerInstallTimeHooks.enabled()
                                        && XposedHelpers.getBooleanField(p.thisObject, "mIsInDrawerContainer")) {
                                    p.setResult(false);
                                }
                            }
                        });
            }
            XposedHelpers.findAndHookMethod("com.android.launcher3.allapps.OplusCOUITouchSearchView",
                    lp.classLoader, "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (DrawerInstallTimeHooks.enabled() && Boolean.TRUE.equals(
                                    XposedHelpers.getAdditionalInstanceField(p.thisObject, DRAWER_VIEW))) {
                                p.setResult(false);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.launcher3.allapps.ClusterAppsContainer",
                    lp.classLoader, "onSectionChange", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (DrawerInstallTimeHooks.enabled()) p.setResult(null);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.launcher3.allapps.OplusAllAppsRecyclerView",
                    lp.classLoader, "injectScrollToPositionAtProgress",
                    "com.android.launcher3.allapps.AlphabeticalAppsList$FastScrollSectionInfo",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (DrawerInstallTimeHooks.enabled()
                                    && XposedHelpers.getObjectField(p.thisObject, "mLauncher") != null) {
                                p.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) { log("HOOK FAIL drawer index gates: " + t); }
    }

    static void refresh() {
        ArrayList<Object> current;
        synchronized (helpers) { current = new ArrayList<>(helpers.keySet()); }
        for (Object helper : current) refreshHelper(helper);
    }

    private static void refreshHelper(Object helper) {
        try {
            if (DrawerInstallTimeHooks.enabled()) {
                Object view = XposedHelpers.callMethod(helper, "getMFastScroller");
                if (view != null) {
                    Object pending = XposedHelpers.removeAdditionalInstanceField(view,
                            "colorosmod_clear_letter_highlight");
                    if (pending instanceof Runnable) XposedHelpers.callMethod(view, "removeCallbacks", pending);
                    XposedHelpers.callMethod(view, "closing");
                }
                XposedHelpers.callMethod(helper, "setCanStartAnim", false);
                Object recycler = XposedHelpers.callMethod(helper, "getActiveRecyclerView");
                if (recycler != null) {
                    XposedHelpers.callMethod(recycler, "stopScroll");
                    XposedHelpers.callMethod(recycler, "onFastScrollComplete");
                }
                Object container = XposedHelpers.getObjectField(helper, "mContainerView");
                Object cluster = XposedHelpers.getObjectField(container, "mClusterAppsContainer");
                if (cluster != null) XposedHelpers.callMethod(cluster, "changeToDrawerLayout", true);
            }
            // 原生入口根据当前页、搜索状态及用户原有排序偏好计算显示；关闭不强制置 VISIBLE。
            XposedHelpers.callMethod(helper, "updateFastScrollerVisibility");
        } catch (Throwable t) { log("drawer index refresh: " + t); }
    }

    private static boolean blocked(Object helper) {
        return DrawerInstallTimeHooks.enabled() && isDrawer(helper);
    }

    private static boolean isDrawer(Object helper) {
        try { return (Boolean) XposedHelpers.callMethod(helper, "isInDrawerContainer"); }
        catch (Throwable t) { return false; }
    }
}
