package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class LegacyCollapsedGroupHooks {
    private static final java.util.WeakHashMap<View, Integer> nameInsets = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, java.lang.ref.WeakReference<android.view.ViewTreeObserver.OnPreDrawListener>> headerListeners = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Boolean> boundButtons = new java.util.WeakHashMap<>();
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
                    "updateIntrinsicHeight", container, XposedHelpers.findClass("android.view.NotificationHeaderView", lp.classLoader),
                    int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {

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
        for (var item : headerListeners.entrySet()) {
            var listener = item.getValue().get();
            if (listener != null && item.getKey().getViewTreeObserver().isAlive())
                item.getKey().getViewTreeObserver().removeOnPreDrawListener(listener);
        }
        headerListeners.clear();
        boundButtons.clear();
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
        Object extension = XposedHelpers.callMethod(container, "getExt");
        Object nativeTitle = XposedHelpers.getObjectField(extension, "oplusExpandHeader");
        if (!(nativeTitle instanceof View view) || view.getParent() != header) {
            XposedHelpers.callMethod(extension, "updateNotificationSummary", container, header);
            nativeTitle = XposedHelpers.getObjectField(extension, "oplusExpandHeader");
        }
        if (!(nativeTitle instanceof View title)) return;

        for (int i = header.getChildCount() - 1; i >= 0; i--) {
            View child = header.getChildAt(i);
            if (child != title && child.getId() == id) header.removeViewAt(i);
        }
        int nameId = NotificationResourceIds.get(header.getResources(), "app_name_text", "id", "android");
        int labelId = NotificationResourceIds.get(container.getResources(), "oplus_notification_expand_group_header_title", "id", "com.android.systemui");
        View source = header.findViewById(nameId), target = title.findViewById(labelId);
        if (source instanceof android.widget.TextView name && target instanceof android.widget.TextView label
                && !android.text.TextUtils.equals(name.getText(), label.getText())) label.setText(name.getText());
        int buttonId = NotificationResourceIds.get(container.getResources(), "oplus_notification_expand_group_header_button", "id", "com.android.systemui");
        int expandId = NotificationResourceIds.get(header.getResources(), "expand_button", "id", "android");
        View button = title.findViewById(buttonId), expand = header.findViewById(expandId);
        if (button != null && expand != null && !boundButtons.containsKey(button)) {
            button.setOnClickListener(v -> expand.performClick());
            boundButtons.put(button, true);
        }
        if (!headerListeners.containsKey(header)) {
            android.view.ViewTreeObserver.OnPreDrawListener listener = () -> {
                if (enabled()) applyHeaderProgress(container, header, LegacyGroupAppearanceHooks.progress(container));
                return true;
            };
            headerListeners.put(header, new java.lang.ref.WeakReference<>(listener));
            header.getViewTreeObserver().addOnPreDrawListener(listener);
        }
        applyHeaderProgress(container, header, LegacyGroupAppearanceHooks.progress(container));
    }

    static void applyHeaderProgress(Object container, ViewGroup header, float progress) {
        int id = NotificationResourceIds.get(((View) container).getResources(), "oplus_notification_expand_group_header", "id", "com.android.systemui");
        View title = id == 0 ? null : header.findViewById(id);
        if (title == null) return;
        if (title.getAlpha() != progress) title.setAlpha(progress);
        int titleVisibility = progress > 0f ? View.VISIBLE : View.INVISIBLE;
        if (title.getVisibility() != titleVisibility) title.setVisibility(titleVisibility);
        for (String name : new String[]{"icon", "notification_top_line", "alternate_expand_target", "expand_button"}) {
            int childId = NotificationResourceIds.get(header.getResources(), name, "id", "android");
            View compact = childId == 0 ? null : header.findViewById(childId);
            int visibility = progress >= 1f ? View.INVISIBLE : View.VISIBLE;
            if (compact != null && compact.getVisibility() != visibility) compact.setVisibility(visibility);
        }
        for (int i = 0; i < header.getChildCount(); i++) {
            View child = header.getChildAt(i);
            if (child != title) {
                if (child.getAlpha() != 1f - progress) child.setAlpha(1f - progress);

                if (progress >= 1f && child.getVisibility() == View.VISIBLE) child.setVisibility(View.INVISIBLE);
            }
        }
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
            applyHeaderProgress(container, header, LegacyGroupAppearanceHooks.progress(container));
        } catch (Throwable error) { log("legacy notification group header failed: " + error); }
    }
}
