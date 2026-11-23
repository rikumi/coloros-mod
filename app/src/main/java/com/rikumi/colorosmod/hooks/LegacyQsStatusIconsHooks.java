package com.rikumi.colorosmod.hooks;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.*;
import static com.rikumi.colorosmod.XposedInit.*;

/** The 15 shade owns real header icons rather than the newer fake-status controller. */
final class LegacyQsStatusIconsHooks {
    private static final WeakHashMap<View, Integer> nativeVisibility = new WeakHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean active = true, writing;

    private static boolean enabled() { return active && readBoolCached(KEY_QS_TOPMARGIN_ENABLED, false); }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT != 35) return;
        try {
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (writing || !active || !nativeVisibility.containsKey(p.thisObject)) return;
                    nativeVisibility.put((View) p.thisObject, (Integer) p.args[0]);
                    if (enabled() && (Integer) p.args[0] == View.VISIBLE) p.args[0] = View.INVISIBLE;
                }
            });
        } catch (Throwable error) { log("legacy QS status visibility hook failed: " + error); }
        hookHeader(lp, "com.oplus.systemui.qs.OplusQuickStatusBarHeader", "onFinishInflate", false);
        hookHeader(lp, "com.oplus.systemui.qs.impl.OplusSplitShadeHeaderControllerExImpl", "init", true);
        try {
            XposedHelpers.findAndHookMethod("com.oplus.systemui.plugins.qs.OplusQSRootViewController", lp.classLoader,
                    "onViewAttached", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!p.hasThrowable()) track((View) XposedHelpers.getObjectField(p.thisObject, "statusBarContainer"));
                        }
                    });
        } catch (Throwable error) { log("legacy separate QS status hook failed: " + error); }
    }

    private static void hookHeader(XC_LoadPackage.LoadPackageParam lp, String owner, String method, boolean argument) {
        try {
            XC_MethodHook callback = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) track((View) (argument ? p.args[0] : p.thisObject));
                }
            };
            if (argument) XposedHelpers.findAndHookMethod(owner, lp.classLoader, method, ViewGroup.class, callback);
            else XposedHelpers.findAndHookMethod(owner, lp.classLoader, method, callback);
        } catch (Throwable error) { log("legacy QS header hook failed: " + owner + " " + error); }
    }

    private static void track(View header) {
        if (!active || header == null) return;
        int id = header.getResources().getIdentifier("quick_qs_status_icons", "id", "com.android.systemui");
        View icons = id == 0 ? null : header.findViewById(id);
        if (icons == null) return;
        nativeVisibility.putIfAbsent(icons, icons.getVisibility());
        apply(icons);
    }

    private static void apply(View icons) {
        Integer original = nativeVisibility.get(icons);
        if (original == null) return;
        int target = enabled() && original == View.VISIBLE ? View.INVISIBLE : original;
        writing = true;
        try { if (icons.getVisibility() != target) icons.setVisibility(target); }
        finally { writing = false; }
    }

    static void refresh() {
        main.post(() -> {
            if (active) for (View icons : new ArrayList<>(nativeVisibility.keySet())) apply(icons);
        });
    }

    static ArrayList<Object[]> capture() {
        ArrayList<Object[]> saved = new ArrayList<>();
        for (var entry : nativeVisibility.entrySet()) saved.add(new Object[]{entry.getKey(), entry.getValue()});
        return saved;
    }

    static void restore(Object state) {
        if (state instanceof Iterable<?> rows) for (Object row : rows)
            if (row instanceof Object[] pair && pair.length == 2 && pair[0] instanceof View view
                    && pair[1] instanceof Integer visibility) nativeVisibility.put(view, visibility);
        refresh();
    }

    static void cleanup() {
        active = false;
        main.removeCallbacksAndMessages(null);
        for (View icons : new ArrayList<>(nativeVisibility.keySet())) apply(icons);
        nativeVisibility.clear();
    }
}
