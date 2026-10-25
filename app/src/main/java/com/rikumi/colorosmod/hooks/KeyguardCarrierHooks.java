package com.rikumi.colorosmod.hooks;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.*;

public final class KeyguardCarrierHooks {
    private static final String KEY = "keyguard_hide_carrier_enabled";
    private static final String CARRIER = "com.oplus.systemui.statusbar.widget.OplusStatCarrierText";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View, Integer> views = new WeakHashMap<>();
    private static boolean applying, active = true;

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.KeyguardStatusBarView", lp.classLoader,
                    "onFinishInflate", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            track((View) XposedHelpers.getObjectField(p.thisObject, "mCarrierLabel"));
                            apply();
                        }
                    });
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!active || applying || !CARRIER.equals(p.thisObject.getClass().getName())) return;
                    View view = (View) p.thisObject;
                    if (!isCarrier(view)) return;
                    views.put(view, (Integer) p.args[0]);
                    if (readBoolCached(KEY, false)) p.args[0] = View.GONE;
                }
            });
            refresh();
        } catch (Throwable error) { log("lock screen carrier hook failed: " + error); }
    }

    private static boolean isCarrier(View view) {
        int id = view.getResources().getIdentifier("keyguard_carrier_text", "id", "com.android.systemui");
        return id != 0 && view.getId() == id;
    }

    private static void track(View view) {
        if (view != null && isCarrier(view)) views.putIfAbsent(view, view.getVisibility());
    }

    public static void refresh() {
        main.post(() -> {
            if (!active) return;
            try {
                Class<?> global = Class.forName("android.view.WindowManagerGlobal");
                Object manager = XposedHelpers.callStaticMethod(global, "getInstance");
                String[] names = (String[]) XposedHelpers.callMethod(manager, "getViewRootNames");
                for (String name : names) {
                    Object object = XposedHelpers.callMethod(manager, "getRootView", name);
                    if (object instanceof View root) {
                        int id = root.getResources().getIdentifier("keyguard_carrier_text", "id", "com.android.systemui");
                        if (id != 0) track(root.findViewById(id));
                    }
                }
            } catch (Throwable error) { log("lock screen carrier refresh failed: " + error); }
            apply();
        });
    }

    private static void apply() {
        boolean hide = active && readBoolCached(KEY, false);
        applying = true;
        try {
            for (View view : new ArrayList<>(views.keySet())) view.setVisibility(hide ? View.GONE : views.get(view));
        } finally { applying = false; }
    }

    static void cleanup() {
        active = false;
        main.removeCallbacksAndMessages(null);
        apply();
        views.clear();
    }
}
