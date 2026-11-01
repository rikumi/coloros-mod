package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.WeakHashMap;

public final class RoundNotificationClearAllHooks {
    private static final WeakHashMap<Object, Boolean> controllers = new WeakHashMap<>();
    private static boolean restoring;

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        if (Build.VERSION.SDK_INT < 37) return;
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.oplus.systemui.notification.clearall.ClearAllController", pkg.classLoader);
            XC_MethodHook update = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    controllers.put(p.thisObject, true);
                    apply(p.thisObject);
                }
            };
            // Native resource/orientation updates recreate or resize the layout parameters.
            XposedHelpers.findAndHookMethod(controller, "updateLayoutParams", String.class, update);
            // Also discover an existing controller when loading into a running SystemUI.
            XposedHelpers.findAndHookMethod(controller, "updateVisibility", boolean.class, update);
        } catch (Throwable error) { log("round notification clear button hook failed: " + error); }
    }

    private static void apply(Object controller) {
        Object button = XposedHelpers.getObjectField(controller, "clearAll");
        if (!(button instanceof View view)) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        int height = XposedHelpers.getIntField(controller, "iconHeight");
        int nativeWidth = XposedHelpers.getIntField(controller, "iconWidth");
        if (height <= 0 || nativeWidth <= 0) return;
        boolean enabled = Build.VERSION.SDK_INT >= 37 && !restoring
                && readBoolCached(KEY_NOTIFICATION_ROUND_CLEAR_ALL_ENABLED, false);
        int width = enabled ? height : nativeWidth;
        if (params.width == width) return;
        params.width = width;
        view.setLayoutParams(params);
        view.invalidateOutline();
    }

    public static void refresh() {
        if (Build.VERSION.SDK_INT < 37) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Object controller : new ArrayList<>(controllers.keySet())) apply(controller);
        });
    }

    public static Object capture() { return new ArrayList<>(controllers.keySet()); }

    public static void restore(Object state) {
        if (Build.VERSION.SDK_INT < 37) return;
        if (state instanceof Iterable<?> hosts) for (Object host : hosts)
            if (host != null) controllers.put(host, true);
        refresh();
    }

    public static void cleanupForHotReload() {
        restoring = true;
        try {
            for (Object controller : new ArrayList<>(controllers.keySet())) apply(controller);
        } finally {
            controllers.clear();
            restoring = false;
        }
    }
}
