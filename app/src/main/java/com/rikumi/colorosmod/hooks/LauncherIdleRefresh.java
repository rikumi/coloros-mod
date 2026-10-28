package com.rikumi.colorosmod.hooks;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/** Defers startup recovery until the home screen is focused and quiet. */
final class LauncherIdleRefresh {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Runnable> PENDING = new LinkedHashMap<>();
    private static final long QUIET_MS = 3000;
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static long idleSince;
    private static boolean touching, active = true, idleQueued;
    private static final android.os.MessageQueue.IdleHandler IDLE = () -> {
        idleQueued = false;
        if (active && isIdle()) {
            Runnable[] tasks = PENDING.values().toArray(new Runnable[0]);
            PENDING.clear();
            for (Runnable task : tasks) task.run();
        }
        schedule();
        return false;
    };
    private static final Runnable CHECK = () -> {
        if (!active || PENDING.isEmpty()) return;
        if (isIdle()) {
            if (!idleQueued) {
                idleQueued = true;
                Looper.myQueue().addIdleHandler(IDLE);
            }
        } else schedule();
    };

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        active = true;
        Class<?> launcher = XposedHelpers.findClass("com.android.launcher3.Launcher", lp.classLoader);
        XposedBridge.hookAllConstructors(launcher, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.thisObject instanceof Activity) {
                    activity = new WeakReference<>((Activity) p.thisObject);
                    idleSince = 0;
                    touching = false;
                }
            }
        });
    }

    static Object captureActivity() { return activity.get(); }

    static void restoreActivity(Object host) {
        if (host instanceof Activity) {
            activity = new WeakReference<>((Activity) host);
            idleSince = 0;
        }
    }

    static void onTouch(android.view.View view, MotionEvent event) {
        if (event == null) return;
        if (view.getContext() instanceof Activity) restoreActivity(view.getContext());
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) touching = true;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touching = false;
        idleSince = 0;
    }

    static boolean isIdle() {
        Activity host = activity.get();
        boolean ready = active && !touching && host != null && !host.isFinishing()
                && !host.isDestroyed() && host.hasWindowFocus()
                && LauncherHooks.isLauncherInNormalState(host);
        if (ready) {
            try {
                Object manager = XposedHelpers.callMethod(host, "getStateManager");
                ready = !Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isInTransition"))
                        && !LauncherHooks.isLauncherFolderOpen(host, host.getClassLoader());
            } catch (Throwable ignored) { ready = false; }
        }
        if (!ready) { idleSince = 0; return false; }
        long now = SystemClock.uptimeMillis();
        if (idleSince == 0) idleSince = now;
        return now - idleSince >= QUIET_MS;
    }

    static void post(Object token, Runnable task) {
        MAIN.post(() -> {
            if (!active) return;
            PENDING.put(token, task);
            schedule();
        });
    }

    static void cancel(Object token) {
        MAIN.post(() -> PENDING.remove(token));
    }

    private static void schedule() {
        MAIN.removeCallbacks(CHECK);
        if (active && !PENDING.isEmpty()) MAIN.postDelayed(CHECK, 500);
    }

    static void cleanup() {
        active = false;
        MAIN.removeCallbacks(CHECK);
        Looper.getMainLooper().getQueue().removeIdleHandler(IDLE);
        idleQueued = false;
        PENDING.clear();
        activity.clear();
    }

    private LauncherIdleRefresh() {}
}
