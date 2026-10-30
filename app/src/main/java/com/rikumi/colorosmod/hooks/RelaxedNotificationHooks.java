package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.view.View;
import android.content.res.Resources;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.RelativeLayout;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class RelaxedNotificationHooks {
    private static boolean enabled() { return readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 2; }
    private static final WeakHashMap<View, IconAnchor> anchors = new WeakHashMap<>();
    private static final WeakHashMap<View, java.lang.ref.WeakReference<View>> messageRoots = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> messageLineStarts = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, boolean[]> messageClips = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> compactMessage = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> fullReloadNotice = new ThreadLocal<>();
    private static final class IconAnchor {
        int start, top, bottom, gravity, parentGravity, width, height;
        float scaleX, scaleY, pivotX, pivotY, translationX, translationY;
        int[] rules;
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp",
                    lp.classLoader, "resolveHeaderViewsPlus", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object wrapper = XposedHelpers.callMethod(p.thisObject, "getBase");
                            if (wrapper != null) anchorIcon(XposedHelpers.getObjectField(wrapper, "mIcon"));
                        }
                    });
            for (String layout : new String[]{"com.android.internal.widget.MessagingLayout", "com.android.internal.widget.ConversationLayout"}) {
                XposedHelpers.findAndHookMethod(layout, lp.classLoader, "onLayout", boolean.class,
                        int.class, int.class, int.class, int.class, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                if (enabled()) alignMessage((View) p.thisObject);
                            }
                        });
            }
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.TransformState", lp.classLoader,
                    "resetTransformedView", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object icon = XposedHelpers.getObjectField(p.thisObject, "mTransformedView");
                            var reference = messageRoots.get(icon);
                            View root = reference == null ? null : reference.get();
                            if (root != null) alignMessage(root);
                        }
                    });
            XposedHelpers.findAndHookMethod("android.app.Notification$Builder", lp.classLoader,
                    "createContentView", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled() || !hasRedundantBigText(p.thisObject)) return;
                            fullReloadNotice.set(true);
                            try {
                                Object object = XposedHelpers.callMethod(p.thisObject, "createExpandedContentView");
                                if (object instanceof android.widget.RemoteViews full) {
                                    android.content.Context context = (android.content.Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                                    int expand = NotificationResourceIds.get(context.getResources(), "expand_button", "id", "android");
                                    if (expand != 0) full.setBoolean(expand, "setExpanded", false);
                                    p.setResult(full);
                                }
                            } finally { fullReloadNotice.remove(); }
                        }
                    });
            XposedHelpers.findAndHookMethod("android.app.Notification$Builder", lp.classLoader,
                    "createExpandedContentView", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (enabled() && !Boolean.TRUE.equals(fullReloadNotice.get())
                                    && hasRedundantBigText(p.thisObject)) p.setResult(null);
                        }
                    });
            // The inflater's compiled lambda may inline Builder's public wrappers.
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.NotificationContentInflater$$ExternalSyntheticLambda3",
                    lp.classLoader, "invoke", new XC_MethodHook() { });
            XposedHelpers.findAndHookMethod("android.app.Notification$Builder", lp.classLoader,
                    "createBigContentView", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (enabled() && hasRedundantBigText(p.thisObject)) p.setResult(null);
                        }
                    });

            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationMessagingTemplateViewWrapperExImpl",
                    lp.classLoader, "onContentUpdated", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object wrapper = XposedHelpers.callMethod(p.thisObject, "getBase");
                            if (wrapper != null) anchorIcon(XposedHelpers.getObjectField(wrapper, "mIcon"));
                        }
                    });

            Class<?> params = XposedHelpers.findClass("android.app.Notification$StandardTemplateParams", lp.classLoader);
            int normal = (Integer) XposedHelpers.getStaticObjectField(params, "VIEW_TYPE_NORMAL");
            int expanded = (Integer) XposedHelpers.getStaticObjectField(params, "VIEW_TYPE_EXPANDED");
            int headsUp = (Integer) XposedHelpers.getStaticObjectField(params, "VIEW_TYPE_HEADS_UP");
            // The compact heads-up shell places sender and app name on the same line.
            // Reuse the expanded messaging layout so sender avatars remain visible.
            XposedHelpers.findAndHookMethod("android.app.Notification$MessagingStyle", lp.classLoader,
                    "makeCompactHeadsUpContentView", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            boolean previous = Boolean.TRUE.equals(compactMessage.get());
                            compactMessage.set(false);
                            try {
                                Object result = XposedHelpers.callMethod(p.thisObject, "makeMessagingView", expanded);
                                if (result instanceof android.widget.RemoteViews) p.setResult(result);
                            } finally {
                                if (previous) compactMessage.set(true); else compactMessage.remove();
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("android.app.Notification$MessagingStyle", lp.classLoader,
                    "makeMessagingView", int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            int type = (Integer) p.args[0];
                            if (type != normal && type != headsUp) return;
                            p.setObjectExtra("relaxedCompactMessage", Boolean.TRUE.equals(compactMessage.get()));
                            // Only the notification-center contracted view hides avatars.
                            compactMessage.set(type == normal);
                            p.args[0] = expanded;
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Object previous = p.getObjectExtra("relaxedCompactMessage");
                            if (previous == null) return;
                            if (Boolean.TRUE.equals(previous)) compactMessage.set(true);
                            else compactMessage.remove();
                        }
                    });
            XposedHelpers.findAndHookMethod("android.widget.RemoteViews", lp.classLoader,
                    "setBoolean", int.class, String.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (Boolean.TRUE.equals(compactMessage.get()) && "setIsCollapsed".equals(p.args[1]))
                                p.args[2] = true;
                        }
                    });
            XposedHelpers.findAndHookMethod("android.app.Notification$StandardTemplateParams", lp.classLoader,
                    "hideAppName", boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (enabled()) p.args[0] = false;
                        }
                    });

            java.util.Map<String, String> shells = java.util.Map.of(
                    "notification_2025_template_collapsed_base", "notification_2025_template_expanded_base",
                    "notification_2025_template_heads_up_base", "notification_2025_template_expanded_base",
                    "notification_2025_template_collapsed_messaging", "notification_2025_template_expanded_messaging",
                    "notification_2025_template_collapsed_conversation", "notification_2025_template_expanded_conversation",
                    "notification_2025_template_collapsed_media", "notification_2025_template_expanded_media",
                    "notification_2025_template_collapsed_call", "notification_2025_template_expanded_call");
            XposedHelpers.findAndHookMethod(Resources.class, "getLayout", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || (((Integer) p.args[0]) >>> 24) != 1) return;
                    Resources resources = (Resources) p.thisObject;
                    String expanded = shells.get(resources.getResourceEntryName((Integer) p.args[0]));
                    if (expanded == null) return;
                    int id = resources.getIdentifier(expanded, "layout", "android");
                    if (id != 0) p.setResult(resources.getLayout(id));
                }
            });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.NotificationContentView", lp.classLoader,
                    "onMeasure", int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object object = XposedHelpers.getObjectField(p.thisObject, "mContractedChild");
                            if (!(object instanceof View child)) return;
                            int id = child.getResources().getIdentifier("notification_header", "id", "android");
                            if (id == 0 || child.findViewById(id) == null) return;
                            p.setObjectExtra("smallHeight", XposedHelpers.getIntField(p.thisObject, "mSmallHeight"));
                            p.setObjectExtra("headsUpHeight", XposedHelpers.getIntField(p.thisObject, "mHeadsUpHeight"));
                            int limit = XposedHelpers.getIntField(p.thisObject, "mNotificationMaxHeight");

                            XposedHelpers.setIntField(p.thisObject, "mSmallHeight", limit);
                            XposedHelpers.setIntField(p.thisObject, "mHeadsUpHeight", limit);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.getObjectExtra("smallHeight") instanceof Integer height)
                                XposedHelpers.setIntField(p.thisObject, "mSmallHeight", height);
                            if (p.getObjectExtra("headsUpHeight") instanceof Integer height)
                                XposedHelpers.setIntField(p.thisObject, "mHeadsUpHeight", height);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper", lp.classLoader,
                    "updateExpandability", boolean.class, View.OnClickListener.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object row = XposedHelpers.getObjectField(p.thisObject, "mRow");
                            if (row == null || XposedHelpers.getBooleanField(row, "mIsSummaryWithChildren")) return;
                            Object object = XposedHelpers.getObjectField(p.thisObject, "mExpandButton");
                            if (!(object instanceof View button)) return;
                            LegacyExpandAlignmentHooks.centerArrow(button);
                            int id = button.getResources().getIdentifier("expand_button_pill", "id", "android");
                            View pill = id == 0 ? null : button.findViewById(id);

                            if (pill != null) pill.setVisibility(Boolean.TRUE.equals(p.args[0]) ? View.VISIBLE : View.GONE);
                        }
                    });
        } catch (Throwable error) { log("relaxed notification hook failed: " + error); }
    }

    private static boolean hasRedundantBigText(Object builder) {
        android.app.Notification notification = (android.app.Notification) XposedHelpers.getObjectField(builder, "mN");

        if (notification.contentView != null || notification.bigContentView != null
                || (notification.actions != null && notification.actions.length != 0)) return false;
        android.os.Bundle extras = notification.extras;
        if (extras == null || !"android.app.Notification$BigTextStyle".equals(extras.getString("android.template"))) return false;
        CharSequence text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
        CharSequence big = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
        CharSequence bigTitle = extras.getCharSequence(android.app.Notification.EXTRA_TITLE_BIG);
        return !android.text.TextUtils.isEmpty(text) && android.text.TextUtils.equals(text, big)
                && (android.text.TextUtils.isEmpty(bigTitle) || android.text.TextUtils.equals(
                        bigTitle, extras.getCharSequence(android.app.Notification.EXTRA_TITLE)))
                && android.text.TextUtils.isEmpty(extras.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT));
    }

    static void anchorIcon(Object object) { anchorIcon(object, -1, -1); }

    static int iconMargin(View view) {
        int id = view.getResources().getIdentifier("notification_2025_margin", "dimen", "android");
        return id == 0 ? Math.round(16 * view.getResources().getDisplayMetrics().density)
                : view.getResources().getDimensionPixelSize(id);
    }

    private static void anchorIcon(Object object, int requestedStart, int requestedTop) {
        if (!enabled() || !(object instanceof View icon)) return;

        if (icon.getClass().getName().equals("com.oplus.systemui.notification.row.oplusgroup.OplusGroupNotificationRowIconView")) return;
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams margins)) return;
        IconAnchor saved = anchors.get(icon);
        if (saved == null) {
            saved = new IconAnchor();
            saved.start = margins.getMarginStart();
            saved.top = margins.topMargin;
            saved.bottom = margins.bottomMargin;
            saved.width = params.width; saved.height = params.height;
            saved.scaleX = icon.getScaleX(); saved.scaleY = icon.getScaleY();
            saved.pivotX = icon.getPivotX(); saved.pivotY = icon.getPivotY();
            saved.translationX = icon.getTranslationX(); saved.translationY = icon.getTranslationY();
            if (params instanceof RelativeLayout.LayoutParams relative)
                saved.rules = relative.getRules().clone();
            if (params instanceof FrameLayout.LayoutParams frame) saved.gravity = frame.gravity;
            if (params instanceof LinearLayout.LayoutParams line) saved.gravity = line.gravity;
            if (icon.getParent() instanceof RelativeLayout parent) saved.parentGravity = parent.getGravity();
            anchors.put(icon, saved);
        }
        int top = iconMargin(icon);
        int start = requestedStart < 0 ? top : requestedStart;
        if (requestedTop >= 0) top = requestedTop;
        boolean changed = margins.topMargin != top || margins.getMarginStart() != start;
        margins.setMarginStart(start);
        if (params instanceof RelativeLayout.LayoutParams relative) {
            changed |= relative.getRule(RelativeLayout.CENTER_VERTICAL) != 0
                    || relative.getRule(RelativeLayout.CENTER_IN_PARENT) != 0
                    || relative.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0
                    || relative.getRule(RelativeLayout.ALIGN_PARENT_TOP) == 0;
            relative.removeRule(RelativeLayout.CENTER_VERTICAL);
            relative.removeRule(RelativeLayout.CENTER_IN_PARENT);
            relative.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            relative.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            if (icon.getParent() instanceof RelativeLayout parent)
                parent.setGravity((parent.getGravity() & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.TOP);
        } else if (params instanceof FrameLayout.LayoutParams frame) {
            changed |= frame.gravity != (Gravity.TOP | Gravity.START);
            frame.gravity = Gravity.TOP | Gravity.START;
        } else if (params instanceof LinearLayout.LayoutParams line) {
            changed |= line.gravity != Gravity.TOP;
            line.gravity = Gravity.TOP;
        }
        margins.topMargin = top;
        if (changed) icon.setLayoutParams(params);
    }

    private static void alignMessage(View root) {
        int iconId = NotificationResourceIds.get(root.getResources(), "icon", "id", "android");
        int lineId = NotificationResourceIds.get(root.getResources(), "notification_top_line", "id", "android");
        View icon = root.findViewById(iconId), line = root.findViewById(lineId);
        if (icon == null || line == null) return;
        float density = root.getResources().getDisplayMetrics().density;
        boolean collapsed = XposedHelpers.getBooleanField(root, "mIsCollapsed");
        float expandedStart = 16f + (58f - 16f - 16f) / 2f;
        int parentTop = 0;
        for (View current = (View) icon.getParent(); current != root;) {
            parentTop += current.getTop();
            if (!(current.getParent() instanceof View parent)) return;
            current = parent;
        }
        int collapsedSize = Math.round(32f * density);
        float top = root.getPaddingTop() + Math.max(16f * density,
                (root.getHeight() - root.getPaddingTop() - root.getPaddingBottom() - collapsedSize) / 2f);
        // The fixed-height header constrains children by their layout margins. Moving a
        // 32dp icon down with topMargin shrinks its measured height (95x75 on PKT110).
        // Keep the square inside the header while measuring, then offset its drawing.
        int requestedTop = Math.max(0, Math.round(top) - parentTop);
        anchorIcon(icon, Math.round((collapsed ? 16f : expandedStart) * density), collapsed ? 0 : -1);
        messageRoots.put(icon, new java.lang.ref.WeakReference<>(root));
        int start = Math.round(58f * density); // 16dp inset + 32dp icon + 10dp gap.
        messageLineStarts.putIfAbsent(line, line.getPaddingStart());
        if (line.getPaddingStart() != start)
            line.setPaddingRelative(start, line.getPaddingTop(), line.getPaddingEnd(), line.getPaddingBottom());
        int size = collapsed ? collapsedSize : Math.round(16f * density);
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        if (params.width != size || params.height != size) {
            params.width = size; params.height = size; icon.setLayoutParams(params);
        }
        icon.setPivotX(0f); icon.setPivotY(0f);
        icon.setScaleX(1f); icon.setScaleY(1f);
        for (View current = icon; current != root;) {
            if (!(current.getParent() instanceof View parent)) return;
            if (parent instanceof ViewGroup group) {
                messageClips.computeIfAbsent(group, key -> new boolean[]{key.getClipChildren(), key.getClipToPadding()});
                if (group.getClipChildren()) group.setClipChildren(false);
                if (group.getClipToPadding()) group.setClipToPadding(false);
            }
            current = parent;
        }
        icon.setTranslationY(collapsed ? requestedTop - icon.getTop() : 0f);
    }

    static void cleanup() {
        compactMessage.remove();
        fullReloadNotice.remove();
        messageRoots.clear();
        for (var entry : messageLineStarts.entrySet()) {
            View line = entry.getKey();
            line.setPaddingRelative(entry.getValue(), line.getPaddingTop(), line.getPaddingEnd(), line.getPaddingBottom());
        }
        messageLineStarts.clear();
        for (var entry : messageClips.entrySet()) {
            entry.getKey().setClipChildren(entry.getValue()[0]);
            entry.getKey().setClipToPadding(entry.getValue()[1]);
        }
        messageClips.clear();
        for (var entry : anchors.entrySet()) {
            View icon = entry.getKey();
            IconAnchor saved = entry.getValue();
            ViewGroup.LayoutParams params = icon.getLayoutParams();
            if (!(params instanceof ViewGroup.MarginLayoutParams margins)) continue;
            margins.setMarginStart(saved.start);
            margins.topMargin = saved.top;
            margins.bottomMargin = saved.bottom;
            params.width = saved.width; params.height = saved.height;
            icon.setScaleX(saved.scaleX); icon.setScaleY(saved.scaleY);
            icon.setPivotX(saved.pivotX); icon.setPivotY(saved.pivotY);
            icon.setTranslationX(saved.translationX); icon.setTranslationY(saved.translationY);
            if (params instanceof RelativeLayout.LayoutParams relative && saved.rules != null) {
                for (int rule : new int[]{RelativeLayout.CENTER_VERTICAL, RelativeLayout.CENTER_IN_PARENT,
                        RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_BOTTOM})
                    relative.addRule(rule, saved.rules[rule]);
                if (icon.getParent() instanceof RelativeLayout parent) parent.setGravity(saved.parentGravity);
            }
            if (params instanceof FrameLayout.LayoutParams frame) frame.gravity = saved.gravity;
            if (params instanceof LinearLayout.LayoutParams line) line.gravity = saved.gravity;
            icon.setLayoutParams(params);
        }
        anchors.clear();
    }
}
