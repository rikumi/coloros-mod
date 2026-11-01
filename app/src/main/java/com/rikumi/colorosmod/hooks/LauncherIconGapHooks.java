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
        XposedHelpers.findAndHookMethod(bubble, "getRegularCompoundDrawablePadding", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!active || p.hasThrowable() || !(p.thisObject instanceof TextView view)
                        || !(p.getResult() instanceof Integer padding) || !supported(view)) return;
                p.setResult(padding + extraPadding(view));
            }
        });
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
            if (!supported(view)) return false;
            // 17.3.12 直接读取混淆后的参数字段，已无 IconParam / AllAppsParam getter。
            // 显式调用 BubbleTextView 的真实入口也能避开已编译调用点的内联，
            // 并从原生基准重算，不能在现有 compound padding 上反复累加。
            int padding = (Integer) XposedHelpers.callMethod(view, "getRegularCompoundDrawablePadding");
            if (view.getCompoundDrawablePadding() != padding) {
                view.setCompoundDrawablePadding(padding);
                view.requestLayout();
                view.invalidate();
                return true;
            }
        } catch (Throwable error) { log("launcher icon-label spacing update failed: " + error); }
        return false;
    }

    private static boolean supported(TextView view) {
        int display = XposedHelpers.getIntField(view, "mDisplay");
        return display == 0 || display == 1;
    }

    private static int extraPadding(TextView view) {
        if (!readBoolCached(KEY_ICON_GAP_ENABLED, false)) return 0;
        int dp = Math.max(0, Math.min(8, readIntCached(KEY_ICON_GAP_DP, ICON_GAP_DP)));
        return Math.round(dp * view.getResources().getDisplayMetrics().density);
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
