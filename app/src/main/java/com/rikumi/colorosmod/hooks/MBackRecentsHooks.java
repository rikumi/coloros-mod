package com.rikumi.colorosmod.hooks;

import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Resolves the swipe-up transition that ColorOS starts on the same physical DOWN. */
final class MBackRecentsHooks {
    private static final Map<Object, Long> CONTROLLERS = new ConcurrentHashMap<>();
    private static final Map<Object, Runnable> AFTER_CLEANUP = new ConcurrentHashMap<>();
    private static volatile boolean active;

    static void hook(ClassLoader loader) {
        if (Build.VERSION.SDK_INT < 37 || com.rikumi.colorosmod.TabletDevice.isTablet()) return;
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.android.wm.shell.recents.RecentsTransitionHandler$RecentsController", loader);
            controller.getDeclaredMethod("cancel", boolean.class, boolean.class, String.class);
            XposedBridge.hookAllConstructors(controller, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (active && !param.hasThrowable()) {
                        CONTROLLERS.put(param.thisObject, SystemClock.uptimeMillis());
                    }
                }
            });
            XposedBridge.hookAllMethods(controller, "cleanUp", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    CONTROLLERS.remove(param.thisObject);
                    Runnable navigation = AFTER_CLEANUP.remove(param.thisObject);
                    if (navigation != null && active) navigation.run();
                }
            });
            active = true;
        } catch (Throwable error) {
            com.rikumi.colorosmod.XposedInit.log("mBack recents hook unavailable: " + error);
        }
    }

    static void navigate(View handle, long downTime, boolean home) {
        if (!resolveTransition(handle, downTime, home)) {
            GestureHooks.triggerNavigation(handle, home);
        }
    }

    private static boolean resolveTransition(View handle, long downTime, boolean home) {
        if (!active || handle.getDisplay() == null) return false;
        int displayId = handle.getDisplay().getDisplayId();
        // Only take over a transition created during this press. An older
        // overview session or another display must retain its native behavior.
        for (Map.Entry<Object, Long> entry : CONTROLLERS.entrySet()) {
            if (entry.getValue() < downTime) continue;
            Object controller = entry.getKey();
            try {
                if (XposedHelpers.getIntField(controller, "mDisplayId") != displayId) continue;
                Object owner = XposedHelpers.getObjectField(controller, "this$0");
                Executor executor = (Executor) XposedHelpers.getObjectField(owner, "mExecutor");
                executor.execute(() -> {
                    if (!active) return;
                    java.util.concurrent.atomic.AtomicBoolean dispatched =
                            new java.util.concurrent.atomic.AtomicBoolean();
                    Runnable navigation = () -> {
                        if (dispatched.compareAndSet(false, true)) {
                            handle.post(() -> {
                                if (active) GestureHooks.triggerNavigation(handle, home);
                            });
                        }
                    };
                    try {
                        if (CONTROLLERS.containsKey(controller)) {
                            // Both actions first release the same physical gesture.
                            // Keep the app foreground; navigating before cleanUp
                            // races its restore-to-app transaction. Home must then
                            // go through Launcher, not finish the task straight home.
                            if (AFTER_CLEANUP.putIfAbsent(controller, navigation) != null) return;
                            XposedHelpers.callMethod(controller, "cancel", false, false, "mBack");
                            return;
                        }
                    } catch (Throwable error) {
                        AFTER_CLEANUP.remove(controller, navigation);
                        com.rikumi.colorosmod.XposedInit.log("mBack recents completion failed: " + error);
                    }
                    navigation.run();
                });
                return true;
            } catch (Throwable error) {
                com.rikumi.colorosmod.XposedInit.log("mBack recents dispatch failed: " + error);
            }
        }
        return false;
    }

    static void cleanup() {
        active = false;
        CONTROLLERS.clear();
        AFTER_CLEANUP.clear();
    }
}
