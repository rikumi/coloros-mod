package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.view.View;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class LegacyGroupExpansionHooks {
    private static boolean enabled() { return readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 1; }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> row = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader);
            Class<?> state = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.ExpandableViewState", lp.classLoader);
            String overlay = "com.oplus.systemui.statusbar.notification.stack.OplusNotificationChildrenContainerOverlayExImpl";
            XposedHelpers.findAndHookMethod(row, "updateChildrenStates", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (enabled() && XposedHelpers.getBooleanField(p.thisObject, "mIsSummaryWithChildren")) layout(p.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(row, "getIntrinsicHeight", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || XposedHelpers.getBooleanField(p.thisObject, "mUserLocked")) return;
                    Object guts = XposedHelpers.getObjectField(p.thisObject, "mGuts");
                    if (guts != null && XposedHelpers.getBooleanField(guts, "mExposed")) return;
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(p.thisObject, "isChildInGroup"))
                            && !Boolean.TRUE.equals(XposedHelpers.callMethod(p.thisObject, "isGroupExpanded$1"))) {
                        Object content = XposedHelpers.getObjectField(p.thisObject, "mPrivateLayout");
                        p.setResult(XposedHelpers.callMethod(content, "getMinHeight"));
                    }
                }
            });
            XposedHelpers.findAndHookMethod(overlay, lp.classLoader, "setOverlayVisibility", String.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    Object copy = XposedHelpers.getObjectField(p.thisObject, "overlayContainer");
                    if (copy instanceof View view) view.setVisibility(View.INVISIBLE);
                    Object title = XposedHelpers.getObjectField(p.thisObject, "expandGroupHeader");
                    Object container = XposedHelpers.callMethod(p.thisObject, "getChildrenContainer");
                    if (title instanceof View view && !Boolean.TRUE.equals(XposedHelpers.callMethod(container, "showingAsLowPriority")))
                        view.setVisibility(View.VISIBLE);
                }
            });
            XposedHelpers.findAndHookMethod(overlay, lp.classLoader, "onGroupExpansionProgressChange", float.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    LegacyGroupAppearanceHooks.apply(XposedHelpers.callMethod(p.thisObject, "getChildrenContainer"), (Float) p.args[0]);
                    p.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod(overlay, lp.classLoader, "updateGroupChildStateEx", row, state,
                    int.class, boolean.class, float.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (enabled()) neutralize(XposedHelpers.callMethod(p.args[0], "getViewState"));
                        }
                    });
            String rowExt = "com.oplus.systemui.statusbar.notification.row.OplusExpandableNotificationRowExImpl";
            for (String method : new String[]{"setGroupExpandHeaderAlpha", "setGroupExpandContentAlpha"}) {
                XposedHelpers.findAndHookMethod(rowExt, lp.classLoader, method, float.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled()) return;
                        Object view = XposedHelpers.callMethod(p.thisObject, "getView");
                        if (view != null && (XposedHelpers.getBooleanField(view, "mIsSummaryWithChildren")
                                || Boolean.TRUE.equals(XposedHelpers.callMethod(view, "isChildInGroup")))) p.args[0] = 1f;
                    }
                });
            }

            String split = "com.oplus.systemui.notification.stackednotification.NotificationGroupSplitExImpl";
            Class<?> properties = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.AnimationProperties", lp.classLoader);
            Class<?> expandable = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableView", lp.classLoader);
            XC_MethodHook skip = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { if (enabled()) p.setResult(null); }
            };
            XposedHelpers.findAndHookMethod(split, lp.classLoader, "initGroupChildCustomDelay", expandable, properties, int.class, skip);
            XposedHelpers.findAndHookMethod(split, lp.classLoader, "initGroupChildCustomAnimType", row, properties, int.class, skip);
            XposedHelpers.findAndHookMethod(split, lp.classLoader, "notificationGroupExpansionInterpolator", properties, boolean.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    XposedHelpers.setLongField(p.args[0], "duration", 360L);
                    p.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod(split, lp.classLoader, "getExpandGroupMaxDelay", expandable, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { if (enabled()) p.setResult(0L); }
            });
        } catch (Throwable error) { log("legacy group expansion hook failed: " + error); }
    }

    private static void neutralize(Object state) {
        Object ext = XposedHelpers.getObjectField(state, "mExpandableViewStateEx");
        Object animation = XposedHelpers.callMethod(ext, "getRowAnimationStateEx");
        ClassLoader loader = state.getClass().getClassLoader();
        Class<?> scale = XposedHelpers.findClass("com.android.systemui.statusbar.notification.animation.ScaleState", loader);
        XposedHelpers.callMethod(animation, "requestRowScale", "key_grouped_notification_expanding", XposedHelpers.newInstance(scale, 1f, 1f));
        XposedHelpers.callMethod(ext, "setGroupExpandContentAlpha", 1f);
        XposedHelpers.callMethod(ext, "setGroupExpandHeaderAlpha", 1f);
    }

    private static void layout(Object row) {
        try {
            Object container = XposedHelpers.getObjectField(row, "mChildrenContainer");
            boolean expanded = XposedHelpers.getBooleanField(container, "mChildrenExpanded");
            boolean locked = XposedHelpers.getBooleanField(container, "mUserLocked");
            boolean low = Boolean.TRUE.equals(XposedHelpers.callMethod(container, "showingAsLowPriority"));
            boolean dragging = locked && !low;
            float fraction = locked ? ((Number) XposedHelpers.callMethod(container, "getGroupExpandFraction")).floatValue() : 0f;
            int y = XposedHelpers.getIntField(container, "mCollapsedHeaderMargin")
                    + XposedHelpers.getIntField(container, "mCurrentHeaderTranslation")
                    + NotificationGroupPaddingHooks.inset(container);
            int gap = XposedHelpers.getIntField(container, "mChildPadding");
            int divider = XposedHelpers.getIntField(container, "mDividerHeight");
            int extra = XposedHelpers.getIntField(container, "mAdditionalExpandedHeaderMargin");
            java.util.List<?> children = (java.util.List<?>) XposedHelpers.callMethod(container, "getAttachedChildren");
            int visible = ((Number) XposedHelpers.callMethod(container, "getMaxAllowedVisibleChildren", locked)).intValue();
            for (int i = 0; i < children.size(); i++) {
                Object child = children.get(i);
                y = LegacyGroupGeometry.childTop(y, i == 0, expanded, dragging, fraction, gap, divider, extra);
                Object state = XposedHelpers.callMethod(child, "getViewState");
                int height = ((Number) XposedHelpers.callMethod(child, "getIntrinsicHeight")).intValue();
                XposedHelpers.setIntField(state, "height", height);
                XposedHelpers.callMethod(state, "setYTranslation", (float) y);
                if (i < visible) XposedHelpers.callMethod(state, "setAlpha", low ? fraction : 1f);
                neutralize(state);
                y += height;
            }
            Object headerState = XposedHelpers.getObjectField(container, "mHeaderViewState");
            if (headerState != null && !low) {

                XposedHelpers.callMethod(headerState, "setYTranslation", (float)
                        XposedHelpers.getIntField(container, "mCurrentHeaderTranslation"));
            }
            neutralize(XposedHelpers.callMethod(row, "getViewState"));
        } catch (Throwable error) { log("legacy group child state failed: " + error); }
    }
}
