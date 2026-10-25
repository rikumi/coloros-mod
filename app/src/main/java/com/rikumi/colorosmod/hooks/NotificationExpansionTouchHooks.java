package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.view.View;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class NotificationExpansionTouchHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            String row = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
            XposedHelpers.findAndHookMethod(row, lp.classLoader, "setUserExpanded", boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) { reset(p.thisObject); }
                    });
            XposedHelpers.findAndHookMethod(row, lp.classLoader, "setChildrenExpanded", boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) { reset(p.thisObject); }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.notification.row.NotificationContentView", lp.classLoader,
                    "selectLayout", boolean.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 0)
                                p.setObjectExtra("previousType", XposedHelpers.getIntField(p.thisObject, "mVisibleType"));
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Object previous = p.getObjectExtra("previousType");
                            if (previous instanceof Integer type && type >= 0
                                    && type != XposedHelpers.getIntField(p.thisObject, "mVisibleType"))
                                reset(XposedHelpers.getObjectField(p.thisObject, "mContainingNotification"));
                        }
                    });
        } catch (Throwable error) { log("notification expansion touch hook failed: " + error); }
    }

    private static void reset(Object row) {
        if (row == null || readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 0) return;
        try {
            Object state = XposedHelpers.callMethod(row, "getViewState");
            if (state == null) return;
            Object extension = XposedHelpers.getObjectField(state, "mExpandableViewStateEx");
            Object animation = XposedHelpers.callMethod(extension, "getRowAnimationStateEx");
            Object source = XposedHelpers.callMethod(animation, "getEffectiveClipWidthSource");
            if (!"clip_width_touch_scale".equals(source)) return;
            Object viewExtension = XposedHelpers.getObjectField(row, "mExpandableViewEx");
            if (viewExtension != null) {

                XposedHelpers.callMethod(viewExtension, "resetTouchScaleImmediately", "notification template expansion");
                XposedHelpers.callMethod(state, "applyToView", row);
                ((View) row).invalidate();
            }
        } catch (Throwable error) { log("notification expansion touch reset failed: " + error); }
    }
}
