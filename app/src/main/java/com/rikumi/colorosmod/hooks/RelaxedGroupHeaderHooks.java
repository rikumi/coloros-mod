package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.text.TextUtils;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class RelaxedGroupHeaderHooks {
    private static final WeakHashMap<View, WeakReference<TextView>> names = new WeakHashMap<>();
    private static final WeakHashMap<View, HeaderLine> headerLines = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> lineEndPaddings = new WeakHashMap<>();
    private static final class HeaderLine {
        final WeakReference<TextView> name, time;
        final WeakReference<View> button;
        HeaderLine(TextView name, TextView time, View button) {
            this.name = new WeakReference<>(name);
            this.time = new WeakReference<>(time);
            this.button = new WeakReference<>(button);
        }
    }
    private static final WeakHashMap<View, Integer> expandedLinePaddings = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> iconPositions = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> headerPaddings = new WeakHashMap<>();
    private static final WeakHashMap<TextView, WeakReference<Object>> transformations = new WeakHashMap<>();
    private static boolean enabled() { return readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 2; }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> container = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer", lp.classLoader);
            Class<?> row = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader);
            XposedHelpers.findAndHookMethod(FrameLayout.class, "onLayout", boolean.class, int.class, int.class,
                    int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            HeaderLine line = headerLines.get((View) p.thisObject);
                            if (line != null && enabled()) alignTime((View) p.thisObject, line);
                        }
                    });
            XposedHelpers.findAndHookMethod("android.view.NotificationHeaderView", lp.classLoader,
                    "onMeasure", int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (enabled()) alignExpandedHeader((View) p.thisObject, false);
                        }
                    });
            XposedHelpers.findAndHookMethod(container,
                    "onLayout", boolean.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (enabled() && XposedHelpers.getObjectField(p.thisObject, "mGroupHeader") instanceof View header)
                                alignExpandedHeader(header, true);
                        }
                    });
            String group = "com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupExtImpl";
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "updateSingleLinePadding", row, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled() || (Integer) p.args[1] != 0) return;
                            Object parent = XposedHelpers.callMethod(p.args[0], "getNotificationParent");
                            Object container = parent == null ? null : XposedHelpers.callMethod(parent, "getChildrenContainer");
                            // Only groups with the added name row can move the time out of the body.
                            if (!(container instanceof View view) || !names.containsKey(view)
                                    || NotificationHooks.isMinimizedGroup(container)) return;
                            Object single = XposedHelpers.callMethod(p.args[0], "getSingleLineView");
                            if (single instanceof View body) {
                                lineEndPaddings.put(body, body.getPaddingEnd());
                                body.setPaddingRelative(body.getPaddingStart(), body.getPaddingTop(),
                                        XposedHelpers.getIntField(p.thisObject, "contentPaddingEnd"), body.getPaddingBottom());
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "recreateOplusNotificationHeader", container,
                    View.OnClickListener.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) { if (enabled()) refresh(p.args[0]); }
                    });
            XposedHelpers.findAndHookMethod(group, lp.classLoader, "measureOplusHeader", container,
                    int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object view = XposedHelpers.getObjectField(p.thisObject, "opusGroupView");
                            if (view instanceof FrameLayout header) alignHeader(p.args[0], p.thisObject, header);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.notification.stack.OplusNotificationChildrenContainerOverlayExImpl",
                    lp.classLoader, "layoutGroupOverlay", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object group = XposedHelpers.callMethod(p.thisObject, "getChildrenContainer");
                            Object object = XposedHelpers.getObjectField(p.thisObject, "overlayContainer");
                            int top = extraHeight(group) + NotificationGroupPaddingHooks.inset(group) + fixedTopInset(group);
                            if (object instanceof ViewGroup overlay) {

                                for (int i = 0; i < overlay.getChildCount(); i++)
                                    overlay.getChildAt(i).offsetTopAndBottom(top);
                                XposedHelpers.callMethod(p.thisObject, "updateOverlayTranslation", "relaxed body layout");
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.notification.stack.OplusNotificationChildrenContainerOverlayExImpl",
                    lp.classLoader, "updateOverlayTranslation", String.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object group = XposedHelpers.callMethod(p.thisObject, "getChildrenContainer");
                            if (((java.util.List<?>) XposedHelpers.callMethod(group, "getAttachedChildren")).isEmpty()) return;
                            int bodyInset = extraHeight(group) + NotificationGroupPaddingHooks.inset(group) + fixedTopInset(group);
                            Object object = XposedHelpers.getObjectField(p.thisObject, "overlayContainer");
                            Object header = XposedHelpers.getObjectField(p.thisObject, "opusCollapsedGroupHeader");
                            if (object instanceof View overlay && header instanceof View title) {

                                float base = Math.max(0f, overlay.getTranslationY() - bodyInset);
                                overlay.setTranslationY(base);
                                title.setTranslationY(base);
                            }
                        }
                    });
        } catch (Throwable error) { log("relaxed compact group name hook failed: " + error); }
    }

    static void refresh(Object container) {
        if (!enabled()) return;
        try {
            Object extension = XposedHelpers.callMethod(container, "getExt");
            Object owner = XposedHelpers.getObjectField(extension, "oplusNotificationGroupExtImpl");
            Object object = XposedHelpers.getObjectField(owner, "opusGroupView");
            if (!(object instanceof FrameLayout header)) return;
            Object original = XposedHelpers.callMethod(container, "getNotificationHeaderWrapper");
            if (original == null) return;
            View originalView = (View) XposedHelpers.getObjectField(original, "mView");
            int id = originalView.getResources().getIdentifier("app_name_text", "id", "android");
            View source = id == 0 ? null : originalView.findViewById(id);
            if (!(source instanceof TextView appName)) return;
            View key = (View) container;
            WeakReference<TextView> reference = names.get(key);
            TextView name = reference == null ? null : reference.get();
            if (name == null || name.getParent() != header) {
                if (name != null && name.getParent() instanceof ViewGroup old) old.removeView(name);
                name = new TextView(header.getContext());
                name.setId(View.generateViewId());
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setIncludeFontPadding(false);
                name.setClickable(false);
                header.addView(name);
                names.put(key, new WeakReference<>(name));
            }
            name.setText(appName.getText());
            name.setTextColor(appName.getTextColors());
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, appName.getTextSize());
            name.setTypeface(appName.getTypeface());
            int top = XposedHelpers.getIntField(owner, "groupCollapsedTopPadding");
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            params.topMargin = top;
            params.setMarginStart(XposedHelpers.getIntField(owner, "contentPaddingStart"));
            Object wrapper = XposedHelpers.getObjectField(owner, "oplusHeaderWrapper");
            if (wrapper != null) {
                XposedHelpers.callMethod(wrapper, "addTransformedViews", (Object) new View[]{name});
                transformations.put(name, new WeakReference<>(XposedHelpers.getObjectField(wrapper, "mTransformationHelper")));
            }
            View button = wrapper == null ? null : (View) XposedHelpers.callMethod(wrapper, "getExpandButton");
            Object time = wrapper == null ? null : XposedHelpers.callMethod(wrapper, "getTimeView");
            if (button != null && time instanceof TextView timestamp)
                headerLines.put(header, new HeaderLine(name, timestamp, button));
            params.setMarginEnd(XposedHelpers.getIntField(owner, "contentPaddingEnd")
                    + Math.max(button == null ? 0 : button.getMeasuredWidth(), Math.round(24 * name.getResources().getDisplayMetrics().density)));
            name.setLayoutParams(params);
            alignHeader(container, owner, header);
            key.requestLayout();
        } catch (Throwable error) { log("relaxed compact group name failed: " + error); }
    }

    private static void alignTime(View header, HeaderLine line) {
        TextView name = line.name.get(), time = line.time.get();
        View button = line.button.get();
        if (name == null || time == null || button == null || name.getHeight() == 0) return;

        int center = name.getTop() + name.getHeight() / 2;
        button.offsetTopAndBottom(center - (button.getTop() + button.getHeight() / 2));
        if (time.getBaseline() < 0 || name.getBaseline() < 0) return;
        int top = 0;
        View current = time;
        while (current != header) {
            top += current.getTop();
            if (!(current.getParent() instanceof View parent)) return;
            current = parent;
        }

        time.offsetTopAndBottom(name.getTop() + name.getBaseline() - top - time.getBaseline());
    }

    private static void alignHeader(Object container, Object owner, FrameLayout header) {
        int[] original = headerPaddings.get(header);
        if (original == null) {
            original = new int[]{header.getPaddingTop(), header.getPaddingBottom()};
            headerPaddings.put(header, original);
        }
        int padding = NotificationGroupPaddingHooks.inset(container);
        int fixedTop = fixedTopInset(container);
        int fixedBottom = fixedExtraHeight(container) - fixedTop;
        if (header.getPaddingTop() != original[0] + padding + fixedTop
                || header.getPaddingBottom() != original[1] + padding + fixedBottom)
            header.setPaddingRelative(header.getPaddingStart(), original[0] + padding + fixedTop,
                    header.getPaddingEnd(), original[1] + padding + fixedBottom);
        int id = header.getResources().getIdentifier("icon_container", "id", "com.android.systemui");
        View iconContainer = id == 0 ? null : header.findViewById(id);
        if (iconContainer == null) return;

        alignIcon(iconContainer, Gravity.CENTER_VERTICAL | Gravity.START, 0);
        id = header.getResources().getIdentifier("icon", "id", "com.android.systemui");
        View icon = id == 0 ? null : iconContainer.findViewById(id);
        if (icon != null) alignIcon(icon, Gravity.CENTER, 0);
    }

    private static void alignExpandedHeader(View header, boolean laidOut) {
        if (!(header.getParent() instanceof View container) || !names.containsKey(container)
                || NotificationHooks.isMinimizedGroup(container)
                || XposedHelpers.getObjectField(container, "mGroupHeader") != header) return;
        Object extension = XposedHelpers.callMethod(container, "getExt");
        Object owner = XposedHelpers.getObjectField(extension, "oplusNotificationGroupExtImpl");
        int lineId = NotificationResourceIds.get(header.getResources(), "notification_top_line", "id", "android");
        View line = header.findViewById(lineId);
        if (line == null) return;
        if (!laidOut) {
            expandedLinePaddings.putIfAbsent(line, line.getPaddingStart());
            int start = XposedHelpers.getIntField(owner, "contentPaddingStart");
            if (line.getPaddingStart() != start)
                line.setPaddingRelative(start, line.getPaddingTop(), line.getPaddingEnd(), line.getPaddingBottom());
            return;
        }
        TextView compactName = names.get(container).get();
        int nameId = NotificationResourceIds.get(header.getResources(), "app_name_text", "id", "android");
        View appName = header.findViewById(nameId);
        if (compactName == null || appName == null || compactName.getHeight() == 0) return;
        View compactHeader = (View) compactName.getParent();
        // Both shells keep the same text baseline, including the module's inset.
        int target = compactHeader.getTop() + compactName.getTop() + compactName.getBaseline();
        int current = header.getTop() + line.getTop() + appName.getTop() + appName.getBaseline();
        line.offsetTopAndBottom(target - current);
    }

    private static void alignIcon(View icon, int gravity, int top) {
        if (!(icon.getLayoutParams() instanceof FrameLayout.LayoutParams params)) return;
        iconPositions.putIfAbsent(icon, new int[]{params.gravity, params.topMargin});
        if (params.gravity != gravity || params.topMargin != top) {
            params.gravity = gravity;
            params.topMargin = top;
            icon.setLayoutParams(params);
        }
    }

    static int extraHeight(Object object) {
        if (!enabled() || !(object instanceof View group) || NotificationHooks.isMinimizedGroup(object)) return 0;
        WeakReference<TextView> reference = names.get(group);
        TextView name = reference == null ? null : reference.get();
        if (name == null) return 0;
        int height = name.getLineHeight() + Math.round(4 * name.getResources().getDisplayMetrics().density);
        if (XposedHelpers.getBooleanField(group, "mUserLocked")) {
            Object row = XposedHelpers.callMethod(group, "getContainingNotification");
            Object extension = XposedHelpers.getObjectField(row, "mRowEx");
            float progress = ((Number) XposedHelpers.callMethod(extension, "getGroupExpansionProgress")).floatValue();
            return Math.round(height * (1f - Math.max(0f, Math.min(1f, progress))));
        }
        return XposedHelpers.getBooleanField(group, "mChildrenExpanded") ? 0 : height;
    }

    static int fixedExtraHeight(Object object) {
        if (!enabled() || !(object instanceof View group) || NotificationHooks.isMinimizedGroup(group)) return 0;

        return Math.max(0, (int) (2f * group.getResources().getDisplayMetrics().density));
    }

    static int fixedTopInset(Object object) { return (fixedExtraHeight(object) + 1) / 2; }

    static void cleanup() {
        for (var entry : lineEndPaddings.entrySet()) {
            View body = entry.getKey();
            body.setPaddingRelative(body.getPaddingStart(), body.getPaddingTop(), entry.getValue(), body.getPaddingBottom());
            body.requestLayout();
        }
        lineEndPaddings.clear();
        for (var entry : expandedLinePaddings.entrySet()) {
            View line = entry.getKey();
            line.setPaddingRelative(entry.getValue(), line.getPaddingTop(), line.getPaddingEnd(), line.getPaddingBottom());
        }
        expandedLinePaddings.clear();
        for (View header : headerLines.keySet()) header.requestLayout();
        headerLines.clear();
        for (var entry : transformations.entrySet()) {
            Object helper = entry.getValue().get();
            if (helper != null && XposedHelpers.getObjectField(helper, "mTransformedViews") instanceof java.util.Map<?, ?> views)
                views.remove(entry.getKey().getId());
        }
        transformations.clear();
        for (var reference : names.values()) {
            TextView name = reference.get();
            if (name != null && name.getParent() instanceof ViewGroup parent) parent.removeView(name);
        }
        names.clear();
        for (var entry : iconPositions.entrySet()) {
            View icon = entry.getKey();
            if (icon.getLayoutParams() instanceof FrameLayout.LayoutParams params) {
                params.gravity = entry.getValue()[0];
                params.topMargin = entry.getValue()[1];
                icon.setLayoutParams(params);
            }
        }
        iconPositions.clear();
        for (var entry : headerPaddings.entrySet()) {
            View header = entry.getKey();
            header.setPaddingRelative(header.getPaddingStart(), entry.getValue()[0],
                    header.getPaddingEnd(), entry.getValue()[1]);
        }
        headerPaddings.clear();
    }
}
