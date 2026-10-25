package com.rikumi.colorosmod.hooks;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.content.ComponentName;
import android.view.WindowManager;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.SurfaceControl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

final class AppNavigationTaskSurface {
    private static final String SHELL = "com.android.wm.shell.ShellTaskOrganizer";
    private final Field lock, tasks, configuration, windowConfiguration, visible, displayId, topActivityInfo,
            focused, activityType, lastFocused;
    private final Method taskInfo, leash, getBounds, getMode, sameSurface;
    private final Constructor<?> copySurface;
    private volatile Object organizer;
    private final Object windowManager;
    private final Method visibleWindows;
    private final Field windowTask, windowComponent, windowSurface, windowMode, windowAttributes;
    private final java.util.Set<Target> borrowed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    void releaseTargets() {
        for (Target target : borrowed) target.close();
    }

    AppNavigationTaskSurface(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> shell = loader.loadClass(SHELL);
        Class<?> appeared = loader.loadClass("android.window.TaskAppearedInfo");
        Class<?> info = loader.loadClass("android.app.TaskInfo");
        Class<?> window = loader.loadClass("android.app.WindowConfiguration");
        lock = field(shell, "mLock"); tasks = field(shell, "mTasks");
        taskInfo = appeared.getMethod("getTaskInfo"); leash = appeared.getMethod("getLeash");
        configuration = info.getField("configuration"); visible = info.getField("isVisible");
        displayId = info.getField("displayId"); topActivityInfo = info.getField("topActivityInfo");
        focused = info.getField("isFocused"); activityType = info.getField("topActivityType");
        lastFocused = field(shell, "mLastFocusedTaskInfo");
        windowConfiguration = Configuration.class.getField("windowConfiguration");
        getBounds = window.getMethod("getBounds"); getMode = window.getMethod("getWindowingMode");
        copySurface = SurfaceControl.class.getConstructor(SurfaceControl.class, String.class);
        sameSurface = SurfaceControl.class.getMethod("isSameSurface", SurfaceControl.class);
        Class<?> manager = loader.loadClass("android.view.OplusWindowManager");
        windowManager = manager.getMethod("getInstance").invoke(null);
        visibleWindows = manager.getMethod("getVisibleWindowInfo", int.class);
        Class<?> windowInfo = loader.loadClass("com.oplus.app.OplusWindowInfo");
        windowTask = windowInfo.getField("taskId"); windowComponent = windowInfo.getField("componentName");
        windowSurface = windowInfo.getField("mSurfaceControl"); windowMode = windowInfo.getField("windowingMode");
        windowAttributes = windowInfo.getField("windowAttributes");
        shell.getDeclaredMethod("registerOrganizer");
        shell.getDeclaredMethod("onTaskAppeared", ActivityManager.RunningTaskInfo.class, SurfaceControl.class);
        shell.getDeclaredMethod("onTaskInfoChanged", ActivityManager.RunningTaskInfo.class);
        shell.getDeclaredMethod("onTaskVanished", ActivityManager.RunningTaskInfo.class);
    }

    void install(AppNavigationHooks.Installer installer, IntConsumer vanished,
            Consumer<ActivityManager.RunningTaskInfo> focusChanged) {
        installer.hook(SHELL, "registerOrganizer", chain -> {
            Object result = chain.proceed(); organizer = chain.getThisObject(); return result;
        });
        installer.hook(SHELL, "onTaskAppeared", chain -> {
            Object result = chain.proceed(); organizer = chain.getThisObject();
            ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) chain.getArg(0);
            if (focused.getBoolean(info) && visible.getBoolean(info)) focusChanged.accept(info);
            return result;
        }, ActivityManager.RunningTaskInfo.class, SurfaceControl.class);
        installer.hook(SHELL, "onTaskInfoChanged", chain -> {
            Object result = chain.proceed(); organizer = chain.getThisObject();
            ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) chain.getArg(0);
            if (focused.getBoolean(info) && visible.getBoolean(info)) focusChanged.accept(info);
            return result;
        }, ActivityManager.RunningTaskInfo.class);
        installer.hook(SHELL, "onTaskVanished", chain -> {
            vanished.accept(((ActivityManager.RunningTaskInfo) chain.getArg(0)).taskId);
            return chain.proceed();
        }, ActivityManager.RunningTaskInfo.class);
    }

    static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    Object host() { return organizer; }
    void restoreHost(Object host) { organizer = host; }

    ActivityManager.RunningTaskInfo focusedTask() throws ReflectiveOperationException {
        Object owner = organizer;
        if (owner == null) return null;
        synchronized (lock.get(owner)) {
            ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) lastFocused.get(owner);
            return info != null && focused.getBoolean(info) && visible.getBoolean(info) ? info : null;
        }
    }

    boolean isOverviewHost(ActivityManager.RunningTaskInfo info) throws IllegalAccessException {
        int type = activityType.getInt(info);
        return type == 2 || type == 3; // HOME / RECENTS: no live app-row sampling here.
    }

    Target find(ActivityManager.RunningTaskInfo foreground, int display, Rect navigation, ColorOsNavigationPolicy policy)
            throws ReflectiveOperationException {
        Object owner = organizer;
        if (owner == null || foreground == null || foreground.topActivity == null) return null;
        SurfaceControl taskSurface;
        Rect bounds;
        int uid;
        synchronized (lock.get(owner)) {
            Object appeared = ((SparseArray<?>) tasks.get(owner)).get(foreground.taskId);
            if (appeared == null) return null;
            ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) taskInfo.invoke(appeared);
            if (!visible.getBoolean(info) || displayId.getInt(info) != display
                    || !foreground.topActivity.equals(info.topActivity)) return null;
            Object window = windowConfiguration.get(configuration.get(info));
            // A floating/split task cannot own the display-wide navigation strip.
            if ((Integer) getMode.invoke(window) != 1) return null;
            bounds = new Rect((Rect) getBounds.invoke(window));
            if (!bounds.contains(navigation)) return null;
            ActivityInfo activity = (ActivityInfo) topActivityInfo.get(info);
            SurfaceControl surface = (SurfaceControl) leash.invoke(appeared);
            if (activity == null || activity.applicationInfo == null || surface == null || !surface.isValid()) return null;
            taskSurface = copy(surface);
            uid = activity.applicationInfo.uid;
        }
        // Do not make a Binder call while holding ShellTaskOrganizer's lock.
        SurfaceControl anchor = null;
        try {
            Object result = visibleWindows.invoke(windowManager, display);
            if (!(result instanceof java.util.List<?>)) return null;
            java.util.List<?> windows = (java.util.List<?>) result;
            try {
                for (Object window : windows) {
                    if (windowTask.getInt(window) != foreground.taskId || windowMode.getInt(window) != 1
                            || !foreground.topActivity.equals((ComponentName) windowComponent.get(window))) continue;
                    WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) windowAttributes.get(window);
                    if (attrs.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                            || (attrs.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) continue;
                    SurfaceControl surface = (SurfaceControl) windowSurface.get(window);
                    if (surface != null && surface.isValid()) { anchor = copy(surface); break; }
                }
            } finally {
                for (Object window : windows) {
                    SurfaceControl surface = (SurfaceControl) windowSurface.get(window);
                    if (surface != null) surface.release();
                }
            }
            if (anchor == null) return null;
            Target target = new Target(foreground.taskId, uid, bounds, taskSurface, anchor, policy, borrowed);
            borrowed.add(target);
            taskSurface = null;
            anchor = null;
            return target;
        } finally {
            if (taskSurface != null) taskSurface.release();
            if (anchor != null) anchor.release();
        }
    }

    SurfaceControl copy(SurfaceControl source) throws ReflectiveOperationException {
        return (SurfaceControl) copySurface.newInstance(source, "ColorOSMod application navigation");
    }

    boolean same(Target a, Target b) throws ReflectiveOperationException {
        return a != null && b != null && a.id == b.id && a.uid == b.uid
                && a.bounds.equals(b.bounds) && a.policy.variance == b.policy.variance
                && a.policy.fullscreenVariance == b.policy.fullscreenVariance
                && (Boolean) sameSurface.invoke(a.surface, b.surface)
                && (Boolean) sameSurface.invoke(a.windowSurface, b.windowSurface);
    }

    static void remove(SurfaceControl surface) {
        if (surface == null) return;
        try {
            if (surface.isValid()) try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.reparent(surface, null).apply();
            }
        } finally { surface.release(); }
    }

    static final class Target implements AutoCloseable {
        final int id, uid;
        final Rect bounds;
        final SurfaceControl surface, windowSurface;
        final ColorOsNavigationPolicy policy;
        private final java.util.Set<Target> owner;
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        Target(int id, int uid, Rect bounds, SurfaceControl surface, SurfaceControl windowSurface, ColorOsNavigationPolicy policy, java.util.Set<Target> owner) {
            this.owner = owner;
            this.id = id; this.uid = uid; this.bounds = bounds; this.surface = surface;
            this.windowSurface = windowSurface;
            this.policy = policy;
        }
        @Override public void close() {
            if (released.compareAndSet(false, true)) { owner.remove(this); surface.release(); windowSurface.release(); }
        }
    }
}
