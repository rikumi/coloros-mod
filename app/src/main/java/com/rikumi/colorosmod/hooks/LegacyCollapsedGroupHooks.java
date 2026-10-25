package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Restore framework group headers and hybrid rows without replacing the current stack. */
final class LegacyCollapsedGroupHooks {
    private static final java.util.WeakHashMap<View, Integer> nameInsets = new java.util.WeakHashMap<>();
    private static boolean enabled() { return readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 1; }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> container = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer", lp.classLoader);
            Class<?> wrapper = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper", lp.classLoader);
            Class<?> row = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader);
            String group = "com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupExtImpl";
            XposedHelpers.findAndHookMethod(container, "onLayout", boolean.class,
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (enabled()) alignName(p.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(container, "initDimens$2", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { if (enabled()) dimensions(p.thisObject); }
            });
            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp", lp.classLoader,
                    "updateNotificationSummary", container, XposedHelpers.findClass("android.view.NotificationHeaderView", lp.classLoader), new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            stackHeader((View) p.args[0], (ViewGroup) p.args[1]);
                            p.setResult(null);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp", lp.classLoader,
                    "updateIntrinsicHeight", container, XposedHelpers.findClass("android.view.NotificationHeaderView", lp.classLoader),
                    int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            // The new path snaps to collapsed height while its animation
                            // flag is set, or subtracts the header for a single child.
                            // The old path returns the continuously calculated body height.
                            if (enabled()) p.setResult(p.args[3]);
                        }
                    });
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "getFirstYPosition", boolean.class, float.class, boolean.class, boolean.class, int.class, argument(4));
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "recreateOplusNotificationHeader", container, View.OnClickListener.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { if (enabled()) showHeader(p.thisObject, p.args[0]); }
            });
            XC_MethodHook keepHeader = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    showHeader(p.thisObject, p.args[0]);
                    p.setResult(null);
                }
            };
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "setChildrenExpanded", container, boolean.class, wrapper, boolean.class, keepHeader);
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "updateHeaderState", container, float.class, wrapper, String.class, keepHeader);
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "updateSingleLinePadding", row, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    Object hybrid = XposedHelpers.callMethod(p.args[0], "getSingleLineView");
                    if (hybrid instanceof View view) removeIndent(view);
                    p.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.HybridNotificationView", lp.classLoader, "onFinishInflate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { if (enabled()) removeIndent((View) p.thisObject); }
            });
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "getGroupTopPaddingWhenCollapsed", int.class, boolean.class, container, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    dimensions(p.args[2]);
                    p.setResult(XposedHelpers.getIntField(p.args[2], "mCollapsedHeaderMargin"));
                }
            });
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "getGroupTopMargin", boolean.class, boolean.class, float.class, int.class, boolean.class, boolean.class, container, argument(3));
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "getCollapsedBottomPadding", Resources.class, int.class, boolean.class, argument(1));
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "setExpandButtonNumber", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { if (enabled()) p.setResult(false); }
            });
        } catch (Throwable error) { log("legacy notification group hook failed: " + error); }
    }

    private static void alignName(Object container) {
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(container, "showingAsLowPriority"))) return;
        Object value = XposedHelpers.getObjectField(container, "mGroupHeader");
        if (!(value instanceof ViewGroup header)) return;
        int lineId = NotificationResourceIds.get(header.getResources(), "notification_top_line", "id", "android");
        int nameId = NotificationResourceIds.get(header.getResources(), "app_name_text", "id", "android");
        View line = header.findViewById(lineId), name = header.findViewById(nameId);
        if (line == null || name == null || name.getWidth() == 0) return;
        // The restored ordinary header has a 16dp icon inset, 14dp icon and
        // 6dp gap: its app-name start is 36dp. Group-only outer padding must
        // not be counted again. Adjust logical start, preserving RTL/end padding.
        int left = 0;
        for (View child = name; child != container;) {
            left += child.getLeft();
            if (!(child.getParent() instanceof View parent)) return;
            child = parent;
        }
        int start = header.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? ((View) container).getWidth() - left - name.getWidth() : left;
        int target = Math.round(36f * header.getResources().getDisplayMetrics().density);
        int padding = Math.max(0, line.getPaddingStart() + target - start);
        if (padding != line.getPaddingStart()) {
            nameInsets.putIfAbsent(line, line.getPaddingStart());
            line.setPaddingRelative(padding, line.getPaddingTop(), line.getPaddingEnd(), line.getPaddingBottom());
        }
    }

    static void cleanup() {
        for (var item : nameInsets.entrySet()) {
            View line = item.getKey();
            line.setPaddingRelative(item.getValue(), line.getPaddingTop(), line.getPaddingEnd(), line.getPaddingBottom());
        }
        nameInsets.clear();
    }

    private static XC_MethodHook argument(int index) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { if (enabled()) p.setResult(p.args[index]); }
        };
    }

    static void refresh(Object container) {
        if (!enabled()) return;
        try {
            Object extension = XposedHelpers.callMethod(container, "getExt");
            showHeader(XposedHelpers.getObjectField(extension, "oplusNotificationGroupExtImpl"), container);
            Object row = XposedHelpers.callMethod(container, "getContainingNotification");
            if (row != null) {
                XposedHelpers.callMethod(row, "updateChildrenStates");
                XposedHelpers.callMethod(row, "notifyHeightChanged", false);
            }
        } catch (Throwable error) { log("legacy notification group refresh failed: " + error); }
    }

    private static void dimensions(Object container) {
        float density = ((View) container).getResources().getDisplayMetrics().density;
        XposedHelpers.setIntField(container, "mCollapsedHeaderMargin", Math.round(LegacyGroupGeometry.COLLAPSED_BODY_DP * density));
        // Header glyphs stay centered in the real 46dp template. The compact
        // child list starts at 40dp independently, as before the height correction.
        // Keep the expanded first-child position at 56dp.
        XposedHelpers.setIntField(container, "mAdditionalExpandedHeaderMargin", Math.round((LegacyGroupGeometry.EXPANDED_BODY_DP - LegacyGroupGeometry.COLLAPSED_BODY_DP) * density));
        XposedHelpers.setIntField(container, "mChildPadding", Math.round(4 * density));
        XposedHelpers.setFloatField(container, "mCollapsedBottomPadding", Math.round(16 * density));
        int headerHeight = LegacyGroupGeometry.headerHeight(LegacyGroupAppearanceHooks.progress(container), density);
        if (XposedHelpers.getIntField(container, "mHeaderHeight") != headerHeight) {
            XposedHelpers.setIntField(container, "mHeaderHeight", headerHeight);
            ((View) container).requestLayout();
        }
        XposedHelpers.setIntField(container, "mCurrentHeaderTranslation", 0);
        XposedHelpers.setFloatField(container, "mHeaderVisibleAmount", 1f);
    }

    private static void removeIndent(View view) {
        int inset = Math.round(16 * view.getResources().getDisplayMetrics().density);
        view.setPaddingRelative(inset, view.getPaddingTop(), inset, view.getPaddingBottom());
    }

    private static void stackHeader(View container, ViewGroup header) {
        int id = NotificationResourceIds.get(container.getResources(), "oplus_notification_expand_group_header", "id", "com.android.systemui");
        if (id == 0) return;
        View metrics = header.findViewById(id);
        if (metrics == null) {
            metrics = new View(header.getContext());
            metrics.setId(id);
            header.addView(metrics, 0, ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            ViewGroup.LayoutParams params = metrics.getLayoutParams();
            if (params.width != 0 || params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = 0;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                metrics.setLayoutParams(params);
            }
        }
        // Current stacked ruler unconditionally reads this ID's height during expansion.
        // Keep the compact header's height without rendering the new large title.
        metrics.setVisibility(View.INVISIBLE);
        metrics.setClickable(false);
        metrics.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static void showHeader(Object owner, Object container) {
        try {
            dimensions(container);
            Object custom = XposedHelpers.getObjectField(owner, "oplusHeaderWrapper");
            if (custom != null && XposedHelpers.getObjectField(custom, "mView") instanceof View customView
                    && customView.getVisibility() == View.VISIBLE) XposedHelpers.callMethod(custom, "setVisible", false);
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(container, "showingAsLowPriority"))) return;
            Object original = XposedHelpers.callMethod(container, "getNotificationHeaderWrapper");
            if (original == null) return;
            ViewGroup header = (ViewGroup) XposedHelpers.getObjectField(original, "mView");
            // setVisible resets transformation state; do not call it on every
            // expansion frame when the original header is already visible.
            if (header.getVisibility() != View.VISIBLE) XposedHelpers.callMethod(original, "setVisible", true);
            stackHeader((View) container, header);
            for (String name : new String[]{"icon", "notification_top_line", "app_name_text", "expand_button", "expand_button_pill", "expand_button_icon"}) {
                int id = NotificationResourceIds.get(header.getResources(), name, "id", "android");
                View child = id == 0 ? null : header.findViewById(id);
                if (child != null) {
                    child.setVisibility(View.VISIBLE);
                    if (name.equals("expand_button") && readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 1)
                        LegacyExpandAlignmentHooks.attach(child);
                }
            }
            LegacyExpandAlignmentHooks.hideTimeDivider(header);
            boolean expanded = XposedHelpers.getBooleanField(container, "mChildrenExpanded");
            Object row = XposedHelpers.callMethod(container, "getContainingNotification");
            Object entry = XposedHelpers.callMethod(row, "getEntry");
            Object sbn = XposedHelpers.callMethod(entry, "getSbn");
            android.app.Notification notification = (android.app.Notification) XposedHelpers.callMethod(sbn, "getNotification");
            for (String name : new String[]{"time", "chronometer", "header_text"}) {
                int id = NotificationResourceIds.get(header.getResources(), name, "id", "android");
                View text = id == 0 ? null : header.findViewById(id);
                if (text == null) continue;
                boolean show = !expanded && (name.equals("time") ? Boolean.TRUE.equals(XposedHelpers.callMethod(notification, "showsTime"))
                        : name.equals("chronometer") ? Boolean.TRUE.equals(XposedHelpers.callMethod(notification, "showsChronometer"))
                        : text instanceof android.widget.TextView label && label.length() > 0);
                text.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            XposedHelpers.callMethod(header, "setHeaderBackgroundDrawable", (Object) null);
            header.setVisibility(View.VISIBLE);
        } catch (Throwable error) { log("legacy notification group header failed: " + error); }
    }
}
