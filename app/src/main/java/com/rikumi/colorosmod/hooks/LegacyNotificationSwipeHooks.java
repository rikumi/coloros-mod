package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import com.rikumi.colorosmod.xposed.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import static com.rikumi.colorosmod.XposedInit.*;

/** The ColorOS 15 menu extension takes the concrete menu row and four arguments. */
final class LegacyNotificationSwipeHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ext = XposedHelpers.findClass("com.oplus.systemui.notification.row.NotificationMenuRowExtImpl", lp.classLoader);
        Class<?> row = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.NotificationMenuRow", lp.classLoader);
        Class<?> plugin = XposedHelpers.findClass("com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin", lp.classLoader);
        Map<Object, Boolean> menus = new WeakHashMap<>();
        XposedHelpers.findAndHookMethod(ext, "createMenuViewsExt", boolean.class, row, ArrayList.class,
                Context.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object parent = XposedHelpers.callMethod(p.args[1], "getParent");
                        if (!NotificationHooks.isExpandableNotificationRow(parent)) return;
                        boolean enabled = enabled();
                        menus.put(p.args[1], enabled);
                        if (!enabled) return;
                        ((ArrayList<?>) p.args[2]).clear();
                        NotificationHooks.clearMenuItem(p.thisObject, "settingsItem");
                        NotificationHooks.clearMenuItem(p.thisObject, "deleteItem");
                    }
                });
        XposedHelpers.findAndHookMethod(row, "onTouchStart", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!NotificationHooks.isExpandableNotificationRow(XposedHelpers.callMethod(p.thisObject, "getParent"))) return;
                boolean enabled = enabled();
                // Rebuild cached menus once per switch transition, including restoring their native items.
                Boolean previous = menus.get(p.thisObject);
                if (previous == null || previous != enabled)
                    XposedHelpers.callMethod(p.thisObject, "createMenuViews", true);
            }
        });
        XposedHelpers.findAndHookMethod("com.oplus.systemui.notification.row.swipe.OplusSwipeHelperExImpl",
                lp.classLoader, "shouldNotShowMenuExt", MotionEvent.class, View.class, float.class, plugin,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (enabled() && NotificationHooks.isExpandableNotificationRow(p.args[1])) p.setResult(true);
                    }
                });
        XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper",
                lp.classLoader, "handleMenuRowSwipe", MotionEvent.class, View.class, float.class, plugin,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled() || !NotificationHooks.isExpandableNotificationRow(p.args[1])) return;
                        View view = (View) p.args[1];
                        float velocity = ((Number) p.args[2]).floatValue();
                        if (translation(view) >= 0 || velocity > 0) return;
                        MotionEvent event = (MotionEvent) p.args[0];
                        Object callback = XposedHelpers.getObjectField(p.thisObject, "mCallback");
                        boolean dismissible = Boolean.TRUE.equals(XposedHelpers.callMethod(callback,
                                "canChildBeDismissedInDirection", view, true));
                        Object falsing = XposedHelpers.getObjectField(p.thisObject, "mFalsingManager");
                        boolean safe = event.getActionMasked() == MotionEvent.ACTION_UP && dismissible
                                && !Boolean.TRUE.equals(XposedHelpers.callMethod(falsing, "isUnlockingDisabled"))
                                && !Boolean.TRUE.equals(XposedHelpers.callMethod(p.thisObject, "isFalseGesture"));
                        float escape = ((Number) XposedHelpers.callMethod(p.thisObject, "getEscapeVelocity")).floatValue();
                        boolean far = Boolean.TRUE.equals(XposedHelpers.callMethod(p.thisObject, "swipedFarEnough"));
                        if (safe && (velocity < -escape || far)) XposedHelpers.callMethod(p.thisObject, "dismiss", view, velocity);
                        else {
                            XposedHelpers.callMethod(p.thisObject, "snapClosed", view, velocity);
                            XposedHelpers.callMethod(p.args[3], "onSnapClosed");
                        }
                        p.setResult(null);
                    }
                });
        XposedHelpers.findAndHookMethod(ext, "performHapticFeedback", View.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled()) return;
                Object menu = XposedHelpers.getObjectField(p.thisObject, "menuRow");
                Object parent = menu == null ? p.args[0] : XposedHelpers.callMethod(menu, "getParent");
                if (parent instanceof View view && NotificationHooks.isExpandableNotificationRow(view)
                        && translation(view) < 0) p.setResult(null);
            }
        });
    }
    private static float translation(View view) {
        return ((Number) XposedHelpers.callMethod(view, "getTranslation")).floatValue();
    }
    private static boolean enabled() { return readBool(KEY_NOTIFICATION_SWIPE_TO_DISMISS_ENABLED, false); }
    private LegacyNotificationSwipeHooks() {}
}
