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

public final class DrawerIndexHooks {
    private static final String DRAWER_VIEW = "colorosmod_drawer_index_view";
    private static final Map<Object, Boolean> helpers = Collections.synchronizedMap(new WeakHashMap<>());
    private static DrawerIndexAccess access;

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> helper = XposedHelpers.findClass(
                    "com.android.launcher3.allapps.LetterIndexFastScrollHelper", lp.classLoader);
            access = new DrawerIndexAccess(helper);
            XposedBridge.hookAllConstructors(helper, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                    if (!isDrawer(p.thisObject)) return;
                    helpers.put(p.thisObject, Boolean.TRUE);
                    Object view = access.fastScroller(p.thisObject);
                    if (view != null) {
                        XposedHelpers.setAdditionalInstanceField(view, DRAWER_VIEW, Boolean.TRUE);
                        if (DrawerInstallTimeHooks.enabled()) XposedHelpers.callMethod(view, "setVisibility", 8);
                    }
                }
            });
            // New launchers inline visibility calculation into this method, removing
            // getFastScrollerVisibility. Gate the stable update entry instead.
            XposedBridge.hookAllMethods(helper, "updateFastScrollerVisibility", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws ReflectiveOperationException {
                    if (!blocked(p.thisObject)) return;
                    Object view = access.fastScroller(p.thisObject);
                    if (view != null) XposedHelpers.callMethod(view, "setVisibility", 8);
                    p.setResult(null);
                }
            });
            hookHelperMethod(helper, "isHitInParent", new Class<?>[]{MotionEvent.class}, false);
            hookHelperMethod(helper, "isInContinueEvent", new Class<?>[0], false);
            for (String method : new String[]{"onKey", "onNameClick"})
                XposedBridge.hookAllMethods(helper, method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (blocked(p.thisObject)) p.setResult(null);
                    }
                });
            hookTouchLayout(lp.classLoader);
            hookTouchSearch(lp.classLoader);
            hookSectionChange(lp.classLoader);
            hookRecyclerLetterScroll(lp.classLoader);
            log("HOOK OK drawer index compatibility");
        } catch (Throwable t) { log("HOOK FAIL drawer index gates: " + t); }
    }

    private static void hookHelperMethod(Class<?> helper, String method, Class<?>[] signature,
            boolean isVoid) {
        try {
            Object[] args = new Object[signature.length + 1];
            System.arraycopy(signature, 0, args, 0, signature.length);
            args[signature.length] = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (blocked(p.thisObject)) p.setResult(isVoid ? null : Boolean.FALSE);
                }
            };
            XposedHelpers.findAndHookMethod(helper, method, args);
        } catch (Throwable t) { log("drawer index helper method unavailable " + method + ": " + t); }
    }

    private static void hookTouchLayout(ClassLoader loader) {
        try {
            Class<?> layout = XposedHelpers.findClass(
                    "com.android.launcher3.allapps.OplusFastScrollLayout", loader);
            for (String method : new String[]{"onInterceptTouchEvent", "onTouchEvent"}) {
                XposedHelpers.findAndHookDeclaredMethod(layout, method, MotionEvent.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (!DrawerInstallTimeHooks.enabled()) return;
                                try {
                                    if (XposedHelpers.getObjectField(p.thisObject, "mLauncher") != null)
                                        p.setResult(false);
                                } catch (Throwable ignored) { }
                            }
                        });
            }
        } catch (Throwable t) { log("drawer fast-scroll layout hook unavailable: " + t); }
    }

    private static void hookTouchSearch(ClassLoader loader) {
        Class<?> searchView;
        try {
            searchView = XposedHelpers.findClass(
                    "com.android.launcher3.allapps.OplusCOUITouchSearchView", loader);
        } catch (Throwable ignored) {
            try {
                // ColorOS 15.8.21 uses the COUI library class directly.
                searchView = XposedHelpers.findClass(
                        "com.coui.appcompat.touchsearchview.COUITouchSearchView", loader);
            } catch (Throwable t) {
                log("drawer touch-search class unavailable: " + t);
                return;
            }
        }
        try {
            XposedHelpers.findAndHookMethod(searchView, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (DrawerInstallTimeHooks.enabled() && Boolean.TRUE.equals(
                                    XposedHelpers.getAdditionalInstanceField(p.thisObject, DRAWER_VIEW))) {
                                p.setResult(false);
                            }
                        }
                    });
        } catch (Throwable t) { log("drawer touch-search event hook unavailable: " + t); }
    }

    private static void hookSectionChange(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("com.android.launcher3.allapps.ClusterAppsContainer",
                    loader, "onSectionChange", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (DrawerInstallTimeHooks.enabled()) p.setResult(null);
                        }
                    });
        } catch (Throwable t) {
            // This grouped drawer surface was removed before Launcher 15.8.21.
            log("drawer cluster section hook unavailable: " + t);
        }
    }

    private static void hookRecyclerLetterScroll(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("com.android.launcher3.allapps.OplusAllAppsRecyclerView",
                    loader, "injectScrollToPositionAtProgress",
                    "com.android.launcher3.allapps.AlphabeticalAppsList$FastScrollSectionInfo",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (DrawerInstallTimeHooks.enabled()
                                    && XposedHelpers.getObjectField(p.thisObject, "mLauncher") != null) {
                                p.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) { log("drawer recycler letter hook unavailable: " + t); }
    }

    static void refresh() {
        ArrayList<Object> current;
        synchronized (helpers) { current = new ArrayList<>(helpers.keySet()); }
        for (Object helper : current) refreshHelper(helper);
    }

    private static void refreshHelper(Object helper) {
        try {
            if (DrawerInstallTimeHooks.enabled()) {
                Object view = access.fastScroller(helper);
                if (view != null) {
                    Object pending = XposedHelpers.removeAdditionalInstanceField(view,
                            "colorosmod_clear_letter_highlight");
                    if (pending instanceof Runnable) XposedHelpers.callMethod(view, "removeCallbacks", pending);
                    XposedHelpers.callMethod(view, "closing");
                }
                access.disableAnimation(helper);
                Object recycler = XposedHelpers.callMethod(helper, "getActiveRecyclerView");
                if (recycler != null) {
                    XposedHelpers.callMethod(recycler, "stopScroll");
                    XposedHelpers.callMethod(recycler, "onFastScrollComplete");
                }
                try {
                    Object container = XposedHelpers.getObjectField(helper, "mContainerView");
                    Object cluster = XposedHelpers.getObjectField(container, "mClusterAppsContainer");
                    if (cluster != null) XposedHelpers.callMethod(cluster, "changeToDrawerLayout", true);
                } catch (Throwable ignored) {
                    // The 15.8.21 drawer has no clustered-app container.
                }
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
        catch (Throwable ignored) {
            try {
                Object container = XposedHelpers.getObjectField(helper, "mContainerView");
                Class<?> launcherContainer = XposedHelpers.findClass(
                        "com.android.launcher3.allapps.OplusLauncherAllAppsContainerView",
                        helper.getClass().getClassLoader());
                return launcherContainer.isInstance(container);
            } catch (Throwable ignoredAgain) { return false; }
        }
    }
}
