package com.rikumi.colorosmod.hooks;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import com.rikumi.colorosmod.xposed.*;
import java.util.*;
import static com.rikumi.colorosmod.XposedInit.*;

final class LauncherIconGapHooks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<TextView> VIEWS = Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile boolean active = true;
    private static Integer lastGap;
    private static final Runnable UPDATE = () -> {
        if (!active) return;
        TextView[] views;
        synchronized (VIEWS) { views = VIEWS.toArray(new TextView[0]); }
        // 缓存页和回收池中的图标也可能保留首次加载时的间距。
        int changed = 0;
        for (TextView view : views) if (apply(view)) changed++;
        XposedBridge.log("launcher icon-label spacing refreshed: tracked=" + views.length
                + ", changed=" + changed);
    };

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> bubble = XposedHelpers.findClass("com.android.launcher3.BubbleTextView", lp.classLoader);
        XposedBridge.hookAllMethods(bubble, "onAttachedToWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (active && p.thisObject instanceof TextView view) {
                    synchronized (VIEWS) { VIEWS.add(view); }
                    apply(view);
                }
            }
        });
        XposedBridge.hookAllMethods(bubble, "onMeasure", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (active && p.thisObject instanceof TextView view) {
                    synchronized (VIEWS) { VIEWS.add(view); }
                    apply(view);
                }
            }
        });
    }

    static void onSettingsSnapshotPublished(Map<String, Integer> settings) {
        if (!active) return;
        int gap = settings.getOrDefault(KEY_ICON_GAP_ENABLED, 0) == 1
                ? Math.max(0, Math.min(8, settings.getOrDefault(KEY_ICON_GAP_DP, ICON_GAP_DP))) : 0;
        synchronized (VIEWS) {
            Integer previous = lastGap;
            lastGap = gap;
            // 首份快照可能晚于视图绑定，不能假定构造/测量已读到当前设置。
            if (previous != null && previous == gap) return;
        }
        refresh();
    }

    static void refresh() {
        if (!active) return;
        MAIN.removeCallbacks(UPDATE);
        MAIN.post(UPDATE);
    }

    private static boolean apply(TextView view) {
        try {
            int display = XposedHelpers.getIntField(view, "mDisplay");
            if (display != 0 && display != 1) return false;
            // 不经原生 getRegularCompoundDrawablePadding 的已编译调用点读取 getter：
            // ART 可能已将简单参数 getter 内联，反射调用外层方法也不会经过 getter hook。
            Object activity = XposedHelpers.getObjectField(view, "mActivity");
            Object profile = XposedHelpers.callMethod(activity, "getDeviceProfile");
            Object params = XposedHelpers.callMethod(profile, display == 1 ? "allapps" : "icon");
            int padding = (Integer) XposedHelpers.callMethod(params, display == 1
                    ? "getAllAppsIconDrawablePaddingPx" : "getIconDrawablePaddingPx");
            if (view.getCompoundDrawablePadding() != padding) {
                view.setCompoundDrawablePadding(padding);
                view.requestLayout();
                view.invalidate();
                return true;
            }
        } catch (Throwable error) { log("launcher icon-label spacing update failed: " + error); }
        return false;
    }

    static Object captureViews() {
        synchronized (VIEWS) { return VIEWS.toArray(new TextView[0]); }
    }
    static void restoreViews(Object saved) {
        if (!(saved instanceof TextView[] views)) return;
        synchronized (VIEWS) { Collections.addAll(VIEWS, views); }
        refresh();
    }

    static void cleanup() {
        active = false;
        MAIN.removeCallbacks(UPDATE);
        synchronized (VIEWS) { VIEWS.clear(); lastGap = null; }
    }
    private LauncherIconGapHooks() {}
}
