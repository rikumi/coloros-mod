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
        RelaxedNotificationSurfaceHooks.hook(lp);
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
                            if (!enabled() || !hasRedundantExpandedContent(p.thisObject)) return;
                            fullReloadNotice.set(true);
                            try {
                                Object object = XposedHelpers.getObjectField(p.thisObject, "mStyle") == null
                                        ? createFullSimpleContent(p.thisObject)
                                        : XposedHelpers.callMethod(p.thisObject, "createExpandedContentView");
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
                                    && hasRedundantExpandedContent(p.thisObject)) p.setResult(null);
                        }
                    });
            // The inflater's compiled lambda may inline Builder's public wrappers.
            NotificationLayoutCompat.deoptimizeInflater(lp.classLoader);
            XposedHelpers.findAndHookMethod("android.app.Notification$Builder", lp.classLoader,
                    "createBigContentView", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (enabled() && hasRedundantExpandedContent(p.thisObject)) p.setResult(null);
                        }
                    });

            Class<?> messagingWrapper = XposedHelpers.findClass("com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationMessagingTemplateViewWrapperExImpl", lp.classLoader);
            XposedHelpers.findAndHookMethod(messagingWrapper,
                    NotificationLayoutCompat.hasMethod(messagingWrapper, "onContentUpdated") ? "onContentUpdated" : "onFindViews", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object wrapper = XposedHelpers.callMethod(p.thisObject, "getBase");
                            if (wrapper != null) anchorIcon(XposedHelpers.getObjectField(wrapper, "mIcon"));
                        }
                    });

            Class<?> params = XposedHelpers.findClass("android.app.Notification$StandardTemplateParams", lp.classLoader);
            // ColorOS's domestic policy moves expanded/headsup titles into alt_title
            // in the top line. Keeping the app name visible alone would then put it
            // beside the title. Retain the full header and body title in relaxed mode.
            NotificationLayoutCompat.optionalHook(XposedHelpers.findClass("android.app.Notification$Builder", lp.classLoader),
                    "shouldApplyDomesticAppNamePolicy", params, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (enabled()) p.setResult(false);
                        }
                    });
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
                            // Android 17 adds the expanded title's start margin in
                            // Builder. Our collapsed shell already insets the entire
                            // message column, so retaining it applies the inset twice.
                            if (enabled() && Boolean.TRUE.equals(compactMessage.get())
                                    && p.getResult() instanceof android.widget.RemoteViews views) {
                                views.setViewLayoutMargin(android.R.id.title, 4, 0f, 0);
                                Object builder = XposedHelpers.getObjectField(p.thisObject, "mBuilder");
                                android.content.Context context = (android.content.Context) XposedHelpers.getObjectField(builder, "mContext");
                                Resources resources = context.getResources();
                                int messaging = NotificationResourceIds.get(resources, "notification_messaging", "id", "android");
                                int gap = NotificationResourceIds.get(resources, "notification_2025_text_margin_top", "dimen", "android");
                                if (messaging != 0 && gap != 0)
                                    views.setViewLayoutMargin(messaging, 1, resources.getDimensionPixelSize(gap), 0);
                            }
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
                            int mode = readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT);
                            if (mode == 1 || mode == 2) p.args[0] = false;
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
                    if (!enabled() || RelaxedNotificationSurfaceHooks.nativePublicInflation()
                            || (((Integer) p.args[0]) >>> 24) != 1) return;
                    Resources resources = (Resources) p.thisObject;
                    String expanded = shells.get(resources.getResourceEntryName((Integer) p.args[0]));
                    if (expanded == null) return;
                    int id = NotificationResourceIds.get(resources, expanded, "layout", "android");
                    if (id != 0) p.setResult(resources.getLayout(id));
                }
            });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.NotificationContentView", lp.classLoader,
                    "onMeasure", int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled() || RelaxedNotificationSurfaceHooks.isPublicContent((View) p.thisObject)) return;
                            Object expandedChild = XposedHelpers.getObjectField(p.thisObject, "mExpandedChild");
                            if (expandedChild instanceof View expandedView) RelaxedMessagingGeometryHooks.adjust(expandedView);
                            Object object = XposedHelpers.getObjectField(p.thisObject, "mContractedChild");
                            if (!(object instanceof View child)) return;
                            int id = NotificationResourceIds.get(child.getResources(), "notification_header", "id", "android");
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
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object group = RelaxedGroupHeaderHooks.expandableGroup(p.thisObject);
                            if (group == null) return;
                            // A summary's content need not expand, but its group does.
                            // Bind the group listener rather than the summary-content listener.
                            p.args[0] = true;
                            p.args[1] = XposedHelpers.getObjectField(group, "mHeaderClickListener");
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object root = XposedHelpers.getObjectField(p.thisObject, "mView");
                            if (root instanceof View view && RelaxedNotificationSurfaceHooks.isPublicContent(view)) return;
                            Object row = XposedHelpers.getObjectField(p.thisObject, "mRow");
                            if (row == null) return;
                            if (XposedHelpers.getBooleanField(row, "mIsSummaryWithChildren")
                                    && RelaxedGroupHeaderHooks.expandableGroup(p.thisObject) == null) return;
                            Object object = XposedHelpers.getObjectField(p.thisObject, "mExpandButton");
                            if (!(object instanceof View button)) return;
                            LegacyExpandAlignmentHooks.centerArrow(button);
                            int id = NotificationResourceIds.get(button.getResources(), "expand_button_pill", "id", "android");
                            View pill = id == 0 ? null : button.findViewById(id);

                            if (pill != null) pill.setVisibility(Boolean.TRUE.equals(p.args[0]) ? View.VISIBLE : View.GONE);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.NotificationContentView", lp.classLoader,
                    "updateExpandButtonsDuringLayout", boolean.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled() || RelaxedNotificationSurfaceHooks.isPublicContent((View) p.thisObject)
                                    || !Boolean.TRUE.equals(p.args[0])
                                    || XposedHelpers.getBooleanField(p.thisObject, "mIsContentExpandable")) return;
                            Object row = XposedHelpers.getObjectField(p.thisObject, "mContainingNotification");
                            if (row == null || XposedHelpers.getBooleanField(row, "mIsSummaryWithChildren")) return;
                            Object expanded = XposedHelpers.getObjectField(p.thisObject, "mExpandedChild");
                            Object contracted = XposedHelpers.getObjectField(p.thisObject, "mContractedChild");
                            if (!(expanded instanceof View big) || !(contracted instanceof View small)
                                    || big.getHeight() == 0 || big.getHeight() > small.getHeight()) return;
                            // A relaxed contracted shell can be as tall as its expanded
                            // counterpart while still showing different text/actions.
                            // Android 17's height-only check must not disable that switch.
                            Object click = XposedHelpers.getObjectField(p.thisObject, "mExpandClickListener");
                            for (String field : new String[]{"mContractedWrapper", "mExpandedWrapper", "mHeadsUpWrapper"}) {
                                Object wrapper = XposedHelpers.getObjectField(p.thisObject, field);
                                if (wrapper != null) XposedHelpers.callMethod(wrapper, "updateExpandability", true, click, true);
                            }
                            XposedHelpers.setBooleanField(p.thisObject, "mIsContentExpandable", true);
                        }
                    });
        } catch (Throwable error) { log("relaxed notification hook failed: " + error); }
    }

    private static Object createFullSimpleContent(Object builder) {
        // This is the single contracted slot's appearance, not a second expandable slot.
        // Do not depend on expandedContentViewRequired(): Android 17 may legitimately
        // return null for a plain notification, even though relaxed mode needs its full shell.
        Object params = XposedHelpers.getObjectField(builder, "mParams");
        XposedHelpers.callMethod(params, "reset");
        int expanded = (Integer) XposedHelpers.getStaticObjectField(params.getClass(), "VIEW_TYPE_EXPANDED");
        XposedHelpers.callMethod(params, "viewType", expanded);
        XposedHelpers.callMethod(params, "allowTextWithProgress", true);
        XposedHelpers.callMethod(params, "fillTextsFrom", builder);
        XposedHelpers.callMethod(params, "hideAppName", false);
        int layout = (Integer) XposedHelpers.callMethod(builder, "getExpandedBaseLayoutResource");
        return XposedHelpers.callMethod(builder, "applyStandardTemplateWithActions", layout, params, null);
    }

    private static boolean hasRedundantExpandedContent(Object builder) {
        android.app.Notification notification = (android.app.Notification) XposedHelpers.getObjectField(builder, "mN");

        if (notification.contentView != null || notification.bigContentView != null
                || (notification.actions != null && notification.actions.length != 0)) return false;
        // Builder actions may not have been copied into mN.actions yet.
        Object actions = XposedHelpers.getObjectField(builder, "mActions");
        if (actions instanceof java.util.Collection<?> list && !list.isEmpty()) return false;
        android.os.Bundle extras = notification.extras;
        String template = extras == null ? null : extras.getString("android.template");
        Object style = XposedHelpers.getObjectField(builder, "mStyle");
        // Android 17 can synthesize an expanded standard template even without any
        // additional content. Its minimal header is smaller than our relaxed shell.
        if (style == null && android.text.TextUtils.isEmpty(template)) return true;
        if (extras == null || !"android.app.Notification$BigTextStyle".equals(template)) return false;
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
        int id = NotificationResourceIds.get(view.getResources(), "notification_2025_margin", "dimen", "android");
        return id == 0 ? Math.round(16 * view.getResources().getDisplayMetrics().density)
                : view.getResources().getDimensionPixelSize(id);
    }

    private static void anchorIcon(Object object, int requestedStart, int requestedTop) {
        if (!enabled() || !(object instanceof View icon)) return;
        if (RelaxedNotificationSurfaceHooks.isPublicContent(icon)) return;

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
        if (RelaxedNotificationSurfaceHooks.isPublicContent(root)) return;
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
        float top = root.getPaddingTop() + iconMargin(icon);
        // The fixed-height header constrains children by their layout margins. Moving a
        // 32dp icon down with topMargin shrinks its measured height (95x75 on PKT110).
        // Keep the square inside the header while measuring, then offset its drawing.
        int requestedTop = Math.max(0, Math.round(top) - parentTop);
        anchorIcon(icon, collapsed ? iconMargin(icon) : Math.round(expandedStart * density), collapsed ? 0 : -1);
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
        centerMessageAppIcon(root, line, icon);
    }

    private static void centerMessageAppIcon(View root, View line, View icon) {
        int id = NotificationResourceIds.get(root.getResources(), "app_name_text", "id", "android");
        View name = id == 0 ? null : line.findViewById(id);
        if (name == null || name.getVisibility() != View.VISIBLE || name.getWidth() == 0
                || name.getHeight() == 0 || icon.getWidth() == 0 || icon.getHeight() == 0) return;
        float nameLeft = positionWithin(name, root, true, true);
        float nameTop = positionWithin(name, root, false, true);
        float iconLeft = positionWithin(icon, root, true, false);
        float iconTop = positionWithin(icon, root, false, false);
        if (Float.isNaN(nameLeft) || Float.isNaN(nameTop) || Float.isNaN(iconLeft) || Float.isNaN(iconTop)) return;
        boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float innerEdge = rtl ? root.getWidth() - root.getPaddingRight() - iconMargin(icon)
                : root.getPaddingLeft() + iconMargin(icon);
        float nameEdge = rtl ? nameLeft + name.getWidth() : nameLeft;
        float targetLeft = (innerEdge + nameEdge - icon.getWidth()) / 2f;
        float targetTop = nameTop + (name.getHeight() - icon.getHeight()) / 2f;
        // Use the actual name position, including ancestor offsets. In ConversationLayout
        // the app icon can be nested inside the avatar badge rather than directly in the header.
        float x = targetLeft - iconLeft, y = targetTop - iconTop;
        if (icon.getTranslationX() != x) icon.setTranslationX(x);
        if (icon.getTranslationY() != y) icon.setTranslationY(y);
    }

    private static float positionWithin(View view, View root, boolean horizontal, boolean ownTranslation) {
        float position = 0f;
        boolean first = true;
        while (view != root) {
            position += horizontal ? view.getLeft() : view.getTop();
            if (!first || ownTranslation)
                position += horizontal ? view.getTranslationX() : view.getTranslationY();
            if (!(view.getParent() instanceof View parent)) return Float.NaN;
            position -= horizontal ? parent.getScrollX() : parent.getScrollY();
            view = parent;
            first = false;
        }
        return position;
    }

    static void cleanup() {
        RelaxedMessagingGeometryHooks.cleanup();
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
