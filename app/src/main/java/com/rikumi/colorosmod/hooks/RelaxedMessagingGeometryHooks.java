package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Geometry of the Android 17 messaging shell, independent of legacy templates. */
final class RelaxedMessagingGeometryHooks {
    private static final WeakHashMap<View, Saved> saved = new WeakHashMap<>();
    private static Boolean modernRuntime;
    private record Saved(int top, int bottom, int paddingTop, int paddingBottom, int gravity, int[] rules) {
        Saved(View view, ViewGroup.MarginLayoutParams params) {
            this(params.topMargin, params.bottomMargin, view.getPaddingTop(), view.getPaddingBottom(),
                    params instanceof LinearLayout.LayoutParams line ? line.gravity
                            : params instanceof FrameLayout.LayoutParams frame ? frame.gravity : -1,
                    params instanceof RelativeLayout.LayoutParams relative ? relative.getRules().clone() : null);
        }
    }

    static boolean modern(View root) {
        // Cache the version check because message transforms also call this during animation.
        if (modernRuntime != null) return modernRuntime;
        // hasSenderNameHidden was added with the Android 17 messaging implementation.
        try {
            modernRuntime = NotificationLayoutCompat.hasMethod(Class.forName(
                    "com.android.internal.widget.MessagingGroup", false, root.getClass().getClassLoader()),
                    "hasSenderNameHidden");
        } catch (ClassNotFoundException ignored) { modernRuntime = false; }
        return modernRuntime;
    }

    static void adjust(View root) {
        if (root == null || readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 2
                || RelaxedNotificationSurfaceHooks.isPublicContent(root) || !modern(root)) return;
        String type = root.getClass().getName();
        if (!type.equals("com.android.internal.widget.MessagingLayout")
                && !type.equals("com.android.internal.widget.ConversationLayout")) return;
        if (XposedHelpers.getBooleanField(root, "mIsCollapsed")) return;
        int gapId = NotificationResourceIds.get(root.getResources(), "notification_text_margin_top", "dimen", "android");
        int gap = gapId == 0 ? Math.round(2 * root.getResources().getDisplayMetrics().density)
                : root.getResources().getDimensionPixelSize(gapId);
        View messages = child(root, "notification_messaging");
        if (messages != null && messages.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params) {
            remember(messages, params);
            // MessagingLayout uses a margin; ConversationLayout uses padding. Avoid adding both.
            boolean conversation = type.endsWith("ConversationLayout");
            int top = conversation ? 0 : gap;
            if (params.topMargin != top) {
                params.topMargin = top;
                messages.setLayoutParams(params);
            }
            int paddingTop = conversation ? gap : 0;
            if (messages.getPaddingTop() != paddingTop)
                messages.setPaddingRelative(messages.getPaddingStart(), paddingTop,
                        messages.getPaddingEnd(), messages.getPaddingBottom());
            if (messages instanceof ViewGroup groups) {
                for (int i = 0; i < groups.getChildCount(); i++) {
                    View group = groups.getChildAt(i);
                    topAlign(child(group, "message_icon_container"));
                    topAlign(child(group, "message_icon"));
                }
            }
        }
        // The conversation avatar is a sibling of the top line in NotificationHeaderView.
        topAlign(child(root, "conversation_icon_container"));
    }

    private static View child(View root, String name) {
        int id = NotificationResourceIds.get(root.getResources(), name, "id", "android");
        return id == 0 ? null : root.findViewById(id);
    }

    private static void remember(View view, ViewGroup.MarginLayoutParams params) {
        saved.computeIfAbsent(view, key -> new Saved(view, params));
    }

    private static void topAlign(View view) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)) return;
        remember(view, params);
        boolean changed = false;
        if (params instanceof RelativeLayout.LayoutParams relative) {
            changed = relative.getRule(RelativeLayout.CENTER_VERTICAL) != 0
                    || relative.getRule(RelativeLayout.CENTER_IN_PARENT) != 0
                    || relative.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0
                    || relative.getRule(RelativeLayout.ALIGN_PARENT_TOP) == 0;
            relative.removeRule(RelativeLayout.CENTER_VERTICAL);
            relative.removeRule(RelativeLayout.CENTER_IN_PARENT);
            relative.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            relative.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        } else if (params instanceof LinearLayout.LayoutParams line) {
            int gravity = (Math.max(0, line.gravity) & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.TOP;
            changed = line.gravity != gravity;
            line.gravity = gravity;
        } else if (params instanceof FrameLayout.LayoutParams frame) {
            int gravity = (Math.max(0, frame.gravity) & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.TOP;
            changed = frame.gravity != gravity;
            frame.gravity = gravity;
        }
        if (changed) view.setLayoutParams(params);
    }

    static void cleanup() {
        for (var entry : saved.entrySet()) {
            View view = entry.getKey();
            Saved original = entry.getValue();
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)) continue;
            params.topMargin = original.top;
            params.bottomMargin = original.bottom;
            if (params instanceof LinearLayout.LayoutParams line) line.gravity = original.gravity;
            if (params instanceof FrameLayout.LayoutParams frame) frame.gravity = original.gravity;
            if (params instanceof RelativeLayout.LayoutParams relative && original.rules != null) {
                for (int i = 0; i < original.rules.length; i++) relative.addRule(i, original.rules[i]);
            }
            view.setLayoutParams(params);
            view.setPaddingRelative(view.getPaddingStart(), original.paddingTop, view.getPaddingEnd(), original.paddingBottom);
        }
        saved.clear();
    }
    private RelaxedMessagingGeometryHooks() {}
}
