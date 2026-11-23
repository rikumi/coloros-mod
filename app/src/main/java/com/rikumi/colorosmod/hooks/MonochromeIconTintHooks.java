package com.rikumi.colorosmod.hooks;

import android.graphics.Canvas;
import android.view.View;
import android.widget.ImageView;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
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
    private static boolean hasColorable;
    private static final WeakHashMap<View, Drawable> legacyMasks = new WeakHashMap<>();

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            String type = "com.android.systemui.statusbar.StatusBarIconView";
            Class<?> iconClass = XposedHelpers.findClass(type, lp.classLoader);
            try { updateIconColor = iconClass.getDeclaredMethod("updateIconColor"); }
            catch (NoSuchMethodException renamed) {
                updateIconColor = iconClass.getDeclaredMethod("updateIconColor$1");
            }
            updateIconColor.setAccessible(true);
            try {
                iconClass.getDeclaredField("mIsIconColorable");
                iconClass.getDeclaredMethod("setIsIconColorable", boolean.class);
                hasColorable = true;
            } catch (NoSuchFieldException | NoSuchMethodException legacy) { hasColorable = false; }
            if (hasColorable) XposedHelpers.findAndHookMethod(type, lp.classLoader, "setIsIconColorable", boolean.class, new XC_MethodHook() {
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
                    boolean old = hasColorable && XposedHelpers.getBooleanField(view, "mIsIconColorable");
                    if (prepare(view) && (!wasTracked || (hasColorable && !old))) updateColor(view);
                }
            });
            if (!hasColorable) hookLegacyDrawable(lp, iconClass);
        } catch (Throwable error) { log("monochrome icon tint hook failed: " + error); }
    }

    private static void hookLegacyDrawable(XC_LoadPackage.LoadPackageParam lp, Class<?> iconClass) {
        XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.phone.StatusBarIconControllerExImpl", lp.classLoader,
                "updateStatusBarIconDrawable", Drawable.class, iconClass, StatusBarNotification.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        View view = (View) p.args[1];
                        if (!MonochromeNotificationHooks.enlargedIcon(view)) { legacyMasks.remove(view); return; }
                        legacyMasks.put(view, (Drawable) p.args[0]);
                        int id = view.getResources().getIdentifier("icon_is_grayscale", "id", "com.android.systemui");
                        if (id != 0) view.setTag(id, true);
                        // Keep the native PNG/mask path instead of rounding it and asynchronously replacing it.
                        p.setResult(false);
                    }
                });
        XposedHelpers.findAndHookMethod(ImageView.class, "setImageDrawable", Drawable.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Drawable mask = legacyMasks.get(p.thisObject);
                if (mask != null && p.args[0] != null && MonochromeNotificationHooks.enlargedIcon((View) p.thisObject))
                    p.args[0] = mask; // Ignore an older vendor coroutine completing after a preference change.
            }
        });
    }

    private static void updateColor(View view) {
        if (updateIconColor == null) return;
        try { updateIconColor.invoke(view); }
        catch (ReflectiveOperationException error) { log("monochrome icon color refresh failed: " + error); }
    }

    private static boolean prepare(View view) {
        if (MonochromeNotificationHooks.enlargedIcon(view)) {
            nativeColorable.putIfAbsent(view, hasColorable && XposedHelpers.getBooleanField(view, "mIsIconColorable"));
            if (hasColorable) XposedHelpers.setBooleanField(view, "mIsIconColorable", true);
            XposedHelpers.setBooleanField(view, "mShowsConversation", false);
            return true;
        }
        Boolean original = nativeColorable.remove(view);
        if (original != null && hasColorable) XposedHelpers.setBooleanField(view, "mIsIconColorable", original);
        legacyMasks.remove(view);
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
        legacyMasks.clear();
        for (var entry : saved.entrySet()) {
            if (hasColorable) XposedHelpers.setBooleanField(entry.getKey(), "mIsIconColorable", entry.getValue());
            updateColor(entry.getKey());
            entry.getKey().invalidate();
        }
    }
}
