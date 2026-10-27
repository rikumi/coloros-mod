package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Repair only spacing left by missing preferences, using the ROM's own category gap. */
public final class SettingsSpacingHooks {
    private static final String APPS = "com.oplus.settings.feature.appmanager.AppManagerDashboardFragment";
    private static final String SYSTEM = "com.oplus.settings.feature.othersettings.MoreSystemSettingsFragment";
    private static final String ABOUT = "com.oplus.settings.feature.deviceinfo.aboutphone.DeviceInfoFragment";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Object, Page> PAGES = new WeakHashMap<>();
    private static final Runnable REFRESH = () -> {
        for (Map.Entry<Object, Page> entry : new ArrayList<>(PAGES.entrySet())) {
            try { apply(entry.getKey(), entry.getValue()); }
            catch (Throwable t) { log("settings missing-module spacing: " + t); }
        }
    };

    private static final class Page {
        final WeakHashMap<Object, Boolean> hiddenCategories = new WeakHashMap<>();
        WeakReference<View> list = new WeakReference<>(null);
        int addedTop;
        int baseTop;
    }

    private static boolean target(Object fragment) {
        String name = fragment.getClass().getName();
        return APPS.equals(name) || SYSTEM.equals(name) || ABOUT.equals(name);
    }

    private static void schedule() {
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }

    public static void onSettingsSnapshotPublished() { schedule(); }

    public static void cleanupForHotReload() {
        MAIN.removeCallbacks(REFRESH);
        for (Page page : PAGES.values()) restore(page);
        PAGES.clear();
    }

    public static void hook(XC_LoadPackage.LoadPackageParam param) {
        try {
            XC_MethodHook pageChanged = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!target(p.thisObject)) return;
                    if (!PAGES.containsKey(p.thisObject)) PAGES.put(p.thisObject, new Page());
                    schedule();
                }
            };
            ClassLoader loader = param.classLoader;
            Class<?> dashboard = XposedHelpers.findClass("com.android.settings.dashboard.DashboardFragment", loader);
            XposedBridge.hookAllMethods(dashboard, "refreshAllPreferences", pageChanged);
            XposedBridge.hookAllMethods(dashboard, "updatePreferenceStates", pageChanged);
            XposedBridge.hookAllMethods(dashboard, "onResume", pageChanged);
            XposedHelpers.findAndHookMethod("androidx.preference.PreferenceFragmentCompat", loader,
                    "onViewCreated", View.class, android.os.Bundle.class, pageChanged);
            XC_MethodHook visibilityChanged = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object preference = p.args.length > 0 && !(p.args[0] instanceof Boolean)
                            ? p.args[0] : p.thisObject;
                    if (preference == null || PAGES.isEmpty()) return;
                    Object key = XposedHelpers.callMethod(preference, "getKey");
                    if ("system_unloadable_app_retrieve".equals(key)
                            || "pref_software_update".equals(key) || "key_ota_update".equals(key)) schedule();
                }
            };
            XposedHelpers.findAndHookMethod("androidx.preference.Preference", loader,
                    "setVisible", boolean.class, visibilityChanged);
            Class<?> preference = XposedHelpers.findClass("androidx.preference.Preference", loader);
            XposedHelpers.findAndHookMethod("androidx.preference.PreferenceGroup", loader,
                    "removePreference", preference, visibilityChanged);
            log("HOOK OK settings missing-module spacing");
        } catch (Throwable t) { log("HOOK FAIL settings missing-module spacing: " + t); }
    }

    private static Object find(Object screen, String key) {
        return XposedHelpers.callMethod(screen, "findPreference", key);
    }

    private static boolean visible(Object preference) {
        return preference != null && Boolean.TRUE.equals(XposedHelpers.callMethod(preference, "isVisible"));
    }

    // An empty category still renders its native 12/16dp spacer. Hide only that empty row.
    private static void collapseEmptyCategory(Page page, Object category, boolean missing) {
        if (category == null) return;
        int count = (Integer) XposedHelpers.callMethod(category, "getPreferenceCount");
        boolean empty = true;
        for (int i = 0; i < count; i++) {
            if (visible(XposedHelpers.callMethod(category, "getPreference", i))) { empty = false; break; }
        }
        if (missing && empty) {
            if (!page.hiddenCategories.containsKey(category)) {
                page.hiddenCategories.put(category, visible(category));
            }
            if (visible(category)) XposedHelpers.callMethod(category, "setVisible", false);
        } else {
            Boolean original = page.hiddenCategories.remove(category);
            if (original != null) XposedHelpers.callMethod(category, "setVisible", original);
        }
    }

    private static void restore(Page page) {
        for (Map.Entry<Object, Boolean> entry : page.hiddenCategories.entrySet()) {
            XposedHelpers.callMethod(entry.getKey(), "setVisible", entry.getValue());
        }
        page.hiddenCategories.clear();
        updateTop(page, page.list.get(), 0);
    }

    private static void updateTop(Page page, View list, int extra) {
        View previous = page.list.get();
        if (previous != null && previous != list && page.addedTop != 0
                && previous.getPaddingTop() == page.baseTop + page.addedTop) {
            previous.setPadding(previous.getPaddingLeft(), page.baseTop,
                    previous.getPaddingRight(), previous.getPaddingBottom());
        }
        if (list != null) {
            if (previous != list || list.getPaddingTop() != page.baseTop + page.addedTop) {
                // A fresh view or a native inset/layout pass establishes a new baseline.
                page.baseTop = list.getPaddingTop();
            }
            int target = page.baseTop + extra;
            if (list.getPaddingTop() != target) {
                list.setPadding(list.getPaddingLeft(), target,
                        list.getPaddingRight(), list.getPaddingBottom());
            }
        }
        page.list = new WeakReference<>(list);
        page.addedTop = list == null ? 0 : extra;
    }

    private static void apply(Object fragment, Page page) {
        if (!readBoolCached(KEY_SETTINGS_DISABLED_MODULE_SPACING, false)) { restore(page); return; }
        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen == null) return;
        String name = fragment.getClass().getName();
        if (APPS.equals(name)) {
            collapseEmptyCategory(page, find(screen, "key_app_attr_category"),
                    !visible(find(screen, "system_unloadable_app_retrieve")));
        } else if (SYSTEM.equals(name)) {
            int count = (Integer) XposedHelpers.callMethod(screen, "getPreferenceCount");
            if (count == 0) return;
            Object first = XposedHelpers.callMethod(screen, "getPreference", 0);
            // XML's unnamed first category (order 48) contains only the OTA preference.
            if (first.getClass().getName().endsWith("SettingsPreferenceCategory")
                    && ((Integer) XposedHelpers.callMethod(first, "getOrder")) == 48) {
                collapseEmptyCategory(page, first, !visible(find(screen, "pref_software_update")));
            }
        } else if (ABOUT.equals(name)) {
            View list = (View) XposedHelpers.callMethod(fragment, "getListView");
            if (list == null) return;
            int extra = 0;
            if (!visible(find(screen, "key_ota_update"))) {
                int id = list.getResources().getIdentifier("coui_preference_category_margintop_large",
                        "dimen", "com.android.settings");
                if (id != 0) extra = list.getResources().getDimensionPixelSize(id);
            }
            updateTop(page, list, extra);
        }
    }
}
