package com.rikumi.colorosmod.hooks;

import android.graphics.Canvas;
import android.view.View;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.lang.reflect.Method;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.log;

final class MonochromeIconTintHooks {
    private static final WeakHashMap<View, Boolean> nativeColorable = new WeakHashMap<>();
    private static Method updateIconColor;

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            String type = "com.android.systemui.statusbar.StatusBarIconView";
            Class<?> iconClass = XposedHelpers.findClass(type, lp.classLoader);
            try { updateIconColor = iconClass.getDeclaredMethod("updateIconColor"); }
            catch (NoSuchMethodException renamed) {
                updateIconColor = iconClass.getDeclaredMethod("updateIconColor$1");
            }
            updateIconColor.setAccessible(true);
            XposedHelpers.findAndHookMethod(type, lp.classLoader, "setIsIconColorable", boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    boolean mask = MonochromeNotificationHooks.enlargedIcon(view);
                    if (mask || nativeColorable.containsKey(view)) nativeColorable.put(view, (Boolean) p.args[0]);
                    if (mask) p.args[0] = true;
                }
            });
            com.rikumi.colorosmod.xposed.XposedBridge.hookMethod(updateIconColor, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    prepare((View) p.thisObject);
                }
            });
            // Already-bound icons may not run native setIcon again after a reload.
            XposedHelpers.findAndHookMethod(type, lp.classLoader, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    boolean wasTracked = nativeColorable.containsKey(view);
                    boolean old = XposedHelpers.getBooleanField(view, "mIsIconColorable");
                    if (prepare(view) && (!wasTracked || !old)) updateColor(view);
                }
            });
        } catch (Throwable error) { log("monochrome icon tint hook failed: " + error); }
    }

    private static void updateColor(View view) {
        if (updateIconColor == null) return;
        try { updateIconColor.invoke(view); }
        catch (ReflectiveOperationException error) { log("monochrome icon color refresh failed: " + error); }
    }

    private static boolean prepare(View view) {
        if (MonochromeNotificationHooks.enlargedIcon(view)) {
            nativeColorable.putIfAbsent(view, XposedHelpers.getBooleanField(view, "mIsIconColorable"));
            XposedHelpers.setBooleanField(view, "mIsIconColorable", true);
            XposedHelpers.setBooleanField(view, "mShowsConversation", false);
            return true;
        }
        Boolean original = nativeColorable.remove(view);
        if (original != null) XposedHelpers.setBooleanField(view, "mIsIconColorable", original);
        return false;
    }

    static void refresh() {
        for (View view : new ArrayList<>(nativeColorable.keySet())) {
            prepare(view);
            updateColor(view);
            view.invalidate();
        }
    }

    static void cleanup() {
        var saved = new java.util.HashMap<>(nativeColorable);
        nativeColorable.clear();
        for (var entry : saved.entrySet()) {
            XposedHelpers.setBooleanField(entry.getKey(), "mIsIconColorable", entry.getValue());
            updateColor(entry.getKey());
            entry.getKey().invalidate();
        }
    }
}
