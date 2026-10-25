package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.view.View;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class NotificationGroupPaddingHooks {
    static int inset(Object container) {
        if (!NotificationHooks.sNotifPadEnabled || NotificationHooks.isMinimizedGroup(container)) return 0;
        return NotificationHooks.sNotifPadPx;
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> container = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer", lp.classLoader);
            XC_MethodHook height = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.method.getName().equals("getIntrinsicHeight")
                            && readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 1
                            && !XposedHelpers.getBooleanField(p.thisObject, "mChildrenExpanded")) {
                        Object row = XposedHelpers.callMethod(p.thisObject, "getContainingNotification");
                        if (row != null && Boolean.TRUE.equals(XposedHelpers.callMethod(
                                XposedHelpers.getObjectField(row, "mRowEx"), "isChildrenExpandedAnimating")))
                            return; // Native fallback already used the padded collapsed height.
                    }
                    if (p.getResult() instanceof Integer value) p.setResult(value + addedHeight(p.thisObject));
                }
            };
            for (String name : new String[]{"getIntrinsicHeight", "getMaxContentHeight", "getVisibleChildrenExpandHeight"})
                XposedHelpers.findAndHookMethod(container, name, height);

            XposedHelpers.findAndHookMethod(container, "getMinHeight", int.class, int.class, boolean.class, height);
            XposedHelpers.findAndHookMethod(container, "onLayout", boolean.class,
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 1
                                    || Boolean.TRUE.equals(XposedHelpers.callMethod(p.thisObject, "showingAsLowPriority"))) return;
                            Object object = XposedHelpers.getObjectField(p.thisObject, "mGroupHeader");
                            if (object instanceof View header) {

                                int top = inset(p.thisObject);
                                header.offsetTopAndBottom(top - header.getTop());
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(container, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    int extra = addedHeight(p.thisObject);
                    if (extra == 0) return;
                    View view = (View) p.thisObject;
                    int height = view.getMeasuredHeight() + extra;
                    int spec = (Integer) p.args[1];
                    if (View.MeasureSpec.getMode(spec) != View.MeasureSpec.UNSPECIFIED)
                        height = Math.min(height, View.MeasureSpec.getSize(spec));
                    XposedHelpers.callMethod(view, "setMeasuredDimension", view.getMeasuredWidth(), height);
                    XposedHelpers.setIntField(view, "mRealHeight", XposedHelpers.getIntField(view, "mRealHeight") + extra);
                }
            });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader,
                    "updateChildrenStates", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!XposedHelpers.getBooleanField(p.thisObject, "mIsSummaryWithChildren")) return;
                            // The legacy continuous layout inserts the body inset itself.
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 1) return;
                            Object group = XposedHelpers.getObjectField(p.thisObject, "mChildrenContainer");
                            int padding = inset(group) + RelaxedGroupHeaderHooks.extraHeight(group)
                                    + RelaxedGroupHeaderHooks.fixedTopInset(group);
                            if (padding == 0) return;
                            java.util.List<?> children = (java.util.List<?>) XposedHelpers.callMethod(group, "getAttachedChildren");
                            for (Object child : children) offset(XposedHelpers.callMethod(child, "getViewState"), padding);
                            Object overflow = XposedHelpers.getObjectField(group, "mGroupOverFlowState");
                            if (overflow != null) offset(overflow, padding);
                        }
                    });
        } catch (Throwable error) { log("notification group body padding hook failed: " + error); }
    }

    private static int addedHeight(Object group) {
        return 2 * inset(group) + RelaxedGroupHeaderHooks.extraHeight(group)
                + RelaxedGroupHeaderHooks.fixedExtraHeight(group);
    }

    private static void offset(Object state, int inset) {
        float y = ((Number) XposedHelpers.callMethod(state, "getYTranslation")).floatValue();
        XposedHelpers.callMethod(state, "setYTranslation", y + inset);
    }
}
