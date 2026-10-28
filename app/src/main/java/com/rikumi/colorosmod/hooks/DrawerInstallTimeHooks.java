package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class DrawerInstallTimeHooks {
    private static final String KEY = "drawer_install_time_sort_enabled";
    private static final Map<Object, Boolean> lists = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Object REFRESH_TOKEN = new Object();
    private static volatile Boolean lastEnabled;
    private static volatile boolean active = true;

    static boolean enabled() { return readBoolCached(KEY, false); }

    public static void onSettingsSnapshotPublished(Map<String, Integer> settings) {
        if (!active) return;
        boolean next = Integer.valueOf(1).equals(settings.get(KEY));
        Boolean previous = lastEnabled;
        lastEnabled = next;
        if (previous != null && previous == next) return;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.removeCallbacksAndMessages(REFRESH_TOKEN);
        Runnable refresh = () -> {
            if (!active) return;
            refreshLists();
            DrawerIndexHooks.refresh();
        };
        if (previous == null) {
            // A fresh process already applies the hooks while building its lists.
            // Recover only lists built before settings arrived, once home is idle.
            boolean needsRefresh = false;
            synchronized (lists) {
                for (Boolean applied : lists.values()) if (applied != next) { needsRefresh = true; break; }
            }
            if (needsRefresh) LauncherIdleRefresh.post(REFRESH_TOKEN, refresh);
        } else {
            LauncherIdleRefresh.cancel(REFRESH_TOKEN);
            main.postAtTime(refresh, REFRESH_TOKEN, android.os.SystemClock.uptimeMillis());
        }
    }

    public static void cleanupForHotReload() {
        active = false;
        new android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacksAndMessages(REFRESH_TOKEN);
    }

    private static void refreshLists() {
        boolean enabled = enabled();
        ArrayList<Object> refresh = new ArrayList<>();
        synchronized (lists) {
            for (Map.Entry<Object, Boolean> e : lists.entrySet()) {
                if (e.getValue() != enabled) refresh.add(e.getKey());
            }
        }
        for (Object list : refresh) {
            try { XposedHelpers.callMethod(list, "onAppsUpdated"); }
            catch (Throwable t) { log("drawer install time refresh: " + t); }
        }
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        active = true;
        DrawerIndexHooks.hook(lp);
        try {
            XposedHelpers.findAndHookMethod("com.android.launcher3.allapps.OplusAlphabeticalAppsList",
                    lp.classLoader, "drawAppSort", int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!isDrawer(p.thisObject)) return;
                            boolean enabled = readBool(KEY, false);
                            lists.put(p.thisObject, enabled);
                            if (enabled) p.args[0] = 2;
                        }
                    });
            log("HOOK OK OplusAlphabeticalAppsList#drawAppSort (install-time sort)");
        } catch (Throwable t) { log("HOOK FAIL drawer install-time sort: " + t); }

        // 新旧桌面分别在这两个列表实现上计算字母条是否可用。
        for (String listClass : new String[]{
                "com.android.launcher3.allapps.OplusAlphabeticalAppsList",
                "com.android.launcher3.allapps.LauncherAlphabeticalAppsList"}) {
            try {
                XposedHelpers.findAndHookMethod(listClass, lp.classLoader,
                        "supportsFastScrolling", boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readBool(KEY, false) && isDrawer(p.thisObject)) p.setResult(false);
                        }
                    });
            } catch (Throwable t) {
                log("drawer install-time fast-scroll hook unavailable on " + listClass + ": " + t);
            }
        }
    }

    private static boolean isDrawer(Object list) {
        try {
            return !XposedHelpers.getBooleanField(list, "mIsSearchPage")
                    && !XposedHelpers.getBooleanField(list, "mIsCategoryPage")
                    && !XposedHelpers.getBooleanField(list, "mIsColor");
        } catch (Throwable ignored) {

            try {
                Object context = XposedHelpers.getObjectField(list, "mActivityContext");
                if (context == null) return false;
                String name = context.getClass().getName();
                return "com.android.launcher.Launcher".equals(name)
                        || "com.android.launcher3.Launcher".equals(name);
            } catch (Throwable ignoredAgain) { return false; }
        }
    }
}
