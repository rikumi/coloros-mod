package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.view.WindowInsets;
import android.graphics.Insets;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class MergedSplitShadeHooks {
    private static Class<?> separateModeClass, constraintSetClass, shadeClass, containerClass, mediaClass;
    private static Method mediaResourcesMethod;
    private static final WeakHashMap<Object, Boolean> shades = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> containers = new WeakHashMap<>();
    private static final WeakHashMap<Object, Object> nativeConstraints = new WeakHashMap<>();
    private static final WeakHashMap<View, SplitLayout> layouts = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> mediaViews = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> phoneScope = new ThreadLocal<>();
    private static final ThreadLocal<Float> panelExpansion = new ThreadLocal<>();
    private static boolean restoring;
    private static Boolean lastEnabled;

    static boolean enabled(Resources resources) {
        if (restoring || !readBool(KEY_QS_MERGED_LANDSCAPE_SWAP, false)
                || resources.getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE
                || separateModeClass == null) return false;
        Object helper = XposedHelpers.getStaticObjectField(separateModeClass, "INSTANCE");
        return !Boolean.TRUE.equals(XposedHelpers.callMethod(helper, "getSeparateMode"));
    }

    static boolean phoneLayout(View view) {
        if (view == null || !enabled(view.getResources()) || onKeyguard(view)) return false;
        for (View current = view; current != null;
                current = current.getParent() instanceof View parent ? parent : null) {
            if ((mediaClass != null && mediaClass.isInstance(current))
                    || current.getClass().getName().equals("com.oplus.systemui.qs.OplusQSContainerImpl")) return true;
        }
        return false;
    }

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            separateModeClass = XposedHelpers.findClass("com.android.systemui.util.SeparateModeHelper", pkg.classLoader);
            constraintSetClass = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintSet", pkg.classLoader);
            shadeClass = XposedHelpers.findClass("com.android.systemui.shade.NotificationPanelViewController", pkg.classLoader);
            containerClass = XposedHelpers.findClass("com.android.systemui.shade.NotificationsQSContainerController", pkg.classLoader);
            XC_MethodHook split = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (enabled((Resources) p.args[0])) p.setResult(true);
                }
            };
            XposedHelpers.findAndHookMethod("com.android.systemui.util.LargeScreenUtils", pkg.classLoader,
                    "shouldUseSplitNotificationShade", Resources.class, split);
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.policy.SplitShadeStateControllerImpl", pkg.classLoader,
                    "shouldUseSplitNotificationShade", Resources.class, split);
            XposedBridge.hookAllConstructors(shadeClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) shades.put(p.thisObject, true);
                }
            });
            XposedHelpers.findAndHookMethod(shadeClass, "updateResources", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { shades.put(p.thisObject, true); }
            });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
                    pkg.classLoader, "setStatusBarState", int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View stack = (View) p.thisObject;
                            // State changes need a measure pass even without a configuration change.
                            for (View root : new ArrayList<>(layouts.keySet())) {
                                if (root.getRootView() == stack.getRootView()) root.requestLayout();
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(containerClass, "updateResources", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    containers.put(p.thisObject, true);
                    restoreConstraints(p.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    try { applyConstraints(p.thisObject); }
                    catch (Throwable error) {
                        restoreConstraints(p.thisObject);
                        log("merged split constraints failed: " + error);
                    }
                }
            });
            Class<?> constraintLayout = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintLayout", pkg.classLoader);
            XposedHelpers.findAndHookDeclaredMethod(constraintLayout, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View root = (View) p.thisObject;
                    SplitLayout layout = layouts.get(root);
                    if (layout == null) return;
                    if (!enabled(root.getResources())) {
                        layouts.remove(root);
                        layout.restoreClearAllTranslation(root);
                        applyLayoutOnly(layout.original, root);
                        return;
                    }
                    if (onKeyguard(root)) {
                        layout.suspend(root);
                        return;
                    }
                    int spec = (Integer) p.args[0];
                    if (View.MeasureSpec.getMode(spec) != View.MeasureSpec.UNSPECIFIED)
                        layout.apply(root, View.MeasureSpec.getSize(spec));
                }
            });
            XposedHelpers.findAndHookMethod("com.android.systemui.qs.QSImpl", pkg.classLoader,
                    "setQsExpansion", float.class, float.class, float.class, float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            View view = (View) XposedHelpers.callMethod(p.thisObject, "getView");
                            if (!phoneLayout(view) || XposedHelpers.getIntField(p.thisObject, "mStatusBarState") == 1) return;
                            p.setObjectExtra("mergedPanelExpansionScoped", true);
                            p.setObjectExtra("mergedPreviousPanelExpansion", panelExpansion.get());
                            panelExpansion.set((Float) p.args[1]);
                            // 保持分栏磁贴布局展开；内容显隐仍使用真实的面板进度。
                            p.args[0] = 1f;
                            p.args[3] = 1f;
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!Boolean.TRUE.equals(p.getObjectExtra("mergedPanelExpansionScoped"))) return;
                            Float previous = (Float) p.getObjectExtra("mergedPreviousPanelExpansion");
                            if (previous == null) panelExpansion.remove();
                            else panelExpansion.set(previous);
                        }
                    });
        } catch (Throwable error) { log("merged split shade hook failed: " + error); }
        hookPhoneCards(pkg);
        hookHeaderAndEditor(pkg);
        hookClearAllPosition(pkg);
        MergedShadeAlignmentHooks.hook(pkg);
    }

    private static void hookClearAllPosition(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.systemui.notification.clearall.ClearAllController", pkg.classLoader,
                    "updateClearAllPosition", boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            View button = (View) XposedHelpers.getObjectField(p.thisObject, "clearAll");
                            if (button != null && enabled(button.getResources()) && !onKeyguard(button)
                                    && enclosingLayout(button) != null)
                                p.args[0] = false;
                        }
                    });
        } catch (Throwable error) { log("merged split clear button hook failed: " + error); }
    }

    private static void hookHeaderAndEditor(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            Class<?> qsClass = XposedHelpers.findClass("com.oplus.systemui.qs.OplusQSContainerImpl", pkg.classLoader);
            XposedHelpers.findAndHookMethod(qsClass, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    SplitLayout layout = enclosingLayout(root);
                    for (int i = 0; i < root.getChildCount(); i++) {
                        View child = root.getChildAt(i);
                        if (layout == null || layout.applied == null || !phoneLayout(root)) {
                            ChildBounds original = childBounds.remove(child);
                            if (original != null) original.restore(child);
                            continue;
                        }
                        ChildBounds original = childBounds.computeIfAbsent(child, ChildBounds::new);
                        boolean header = fullWidthHeader(root, child);
                        original.apply(child, root, header ? 0 : layout.applied.qsOffset(),
                                header ? layout.applied.groupWidth() : layout.applied.qsWidth());
                    }
                    View editor = root.findViewById(id(root, "qs_customize"));
                    if (editor != null) updateEditor(editor);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View root = (View) p.thisObject;
                    if (p.hasThrowable() || !phoneLayout(root)) return;
                    View editor = root.findViewById(id(root, "qs_customize"));
                    if (editor != null && childBounds.containsKey(editor)) editor.measure(
                            View.MeasureSpec.makeMeasureSpec(Math.max(1, editor.getLayoutParams().width), View.MeasureSpec.EXACTLY),
                            (Integer) p.args[1]);
                }
            });
            // 布局展开度与面板显隐进度独立，收起时交还原生分层动画。
            XposedHelpers.findAndHookMethod("com.oplus.systemui.qs.OplusQSContainerImplController", pkg.classLoader,
                    "updatePanelExpansion", float.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View root = (View) XposedHelpers.callMethod(p.thisObject, "getView");
                    Float fraction = panelExpansion.get();
                    if (!phoneLayout(root) || fraction == null) return;
                    p.args[0] = fraction;
                    if (fraction >= 1f) p.args[1] = true;
                }
            });
            Class<?> editor = XposedHelpers.findClass("com.oplus.systemui.qs.customize.OplusQSCustomizer", pkg.classLoader);
            XposedHelpers.findAndHookMethod(editor, "updateResources", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) updateEditor((View) p.thisObject);
                }
            });
        } catch (Throwable error) { log("merged split header/editor hook failed: " + error); }
    }

    private static final WeakHashMap<View, ChildBounds> childBounds = new WeakHashMap<>();
    private static final WeakHashMap<Object, Integer> editorColumns = new WeakHashMap<>();

    private static SplitLayout enclosingLayout(View view) {
        for (View current = view; current != null;
                current = current.getParent() instanceof View parent ? parent : null) {
            SplitLayout layout = layouts.get(current);
            if (layout != null) return layout;
        }
        return null;
    }

    private static boolean fullWidthHeader(View root, View child) {
        return child.getId() == id(root, "qs_footer") || child.getId() == id(root, "header")
                || child.getId() == id(root, "qs_fake_status_layout");
    }

    private static final class ChildBounds {
        final int width, left, right, start, end, gravity;
        ChildBounds(View view) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            width = params.width; left = params.leftMargin; right = params.rightMargin;
            start = params.getMarginStart(); end = params.getMarginEnd(); gravity = params.gravity;
        }
        void apply(View view, ViewGroup root, int offset, int width) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            int targetWidth = Math.max(1, width - root.getPaddingLeft() - root.getPaddingRight() - left - right);
            if (params.width == targetWidth && params.leftMargin == offset + left
                    && params.gravity == (android.view.Gravity.TOP | android.view.Gravity.LEFT)) return;
            params.width = targetWidth;
            params.setMarginStart(Integer.MIN_VALUE); params.setMarginEnd(Integer.MIN_VALUE);
            params.leftMargin = offset + left; params.rightMargin = right;
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            view.setLayoutParams(params);
        }
        void restore(View view) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            params.width = width; params.gravity = gravity;
            params.setMarginStart(start); params.setMarginEnd(end);
            params.leftMargin = left; params.rightMargin = right;
            view.setLayoutParams(params);
        }
    }

    private static void updateEditor(View editor) {
        boolean active = phoneLayout(editor);
        for (String field : new String[]{"mLayoutManagerTop", "mLayoutManagerBottom"}) {
            Object manager = XposedHelpers.getObjectField(editor, field);
            if (manager == null) continue;
            int current = XposedHelpers.getIntField(manager, "mColumns");
            Integer original = editorColumns.get(manager);
            if (active) {
                if (original == null || current != 4) editorColumns.put(manager, current);
                if (current != 4) XposedHelpers.callMethod(manager, "updateItemHeightAndColumn",
                        XposedHelpers.getIntField(manager, "mItemHeight"), 4);
            } else if (original != null) {
                editorColumns.remove(manager);
                if (current == 4) XposedHelpers.callMethod(manager, "updateItemHeightAndColumn",
                        XposedHelpers.getIntField(manager, "mItemHeight"), original);
            }
        }
    }

    private static void hookPhoneCards(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            mediaClass = XposedHelpers.findClass("com.oplus.systemui.qs.OplusQSTileMediaContainer", pkg.classLoader);
            XC_MethodHook phoneFeature = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(phoneScope.get())) p.setResult(false);
                }
            };
            Class<?> feature = XposedHelpers.findClass("com.oplusos.systemui.common.feature.FeatureOption", pkg.classLoader);
            XposedHelpers.findAndHookMethod(feature, "isTablet", phoneFeature);
            XposedHelpers.findAndHookMethod(feature, "isFoldDevice", phoneFeature);
            XC_MethodHook scoped = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    mediaViews.put(view, true);
                    p.setObjectExtra("previousPhoneScope", phoneScope.get());
                    phoneScope.set(phoneLayout(view));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Boolean previous = (Boolean) p.getObjectExtra("previousPhoneScope");
                    if (previous == null) phoneScope.remove();
                    else phoneScope.set(previous);
                }
            };
            for (Method method : mediaClass.getDeclaredMethods()) {
                if (method.getName().startsWith("updateResources") && method.getParameterCount() == 0
                        && method.getReturnType() == void.class) {
                    mediaResourcesMethod = method;
                    XposedBridge.hookMethod(method, scoped);
                }
                if (method.getName().equals("updateViewState")) XposedBridge.hookMethod(method, scoped);
            }
        } catch (Throwable error) { log("merged split phone cards hook failed: " + error); }
    }

    private static View view(Object controller) {
        return (View) XposedHelpers.getObjectField(controller, "mView");
    }

    private static int id(View root, String name) {
        return NotificationResourceIds.get(root.getResources(), name, "id", "com.android.systemui");
    }

    private static boolean onKeyguard(View view) {
        View stack = view.getRootView().findViewById(id(view, "notification_stack_scroller"));
        // Use the notification stack's own state, including transitions to/from locked shade.
        return stack != null && XposedHelpers.getIntField(stack, "mStatusBarState") == 1;
    }

    private static void applyLayoutOnly(Object set, View root) {
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Object constraint = XposedHelpers.callMethod(set, "getConstraint", child.getId());
            if (constraint == null) continue;
            // ConstraintSet.applyTo also restores captured visibility, alpha and transforms.
            // Those belong to the current keyguard/shade animations, not our layout snapshot.
            ViewGroup.LayoutParams params = child.getLayoutParams();
            XposedHelpers.callMethod(constraint, "applyTo", params);
            child.setLayoutParams(params);
        }
    }

    private static Object constraints(View root) {
        Object set = XposedHelpers.newInstance(constraintSetClass);
        XposedHelpers.callMethod(set, "clone", root);
        return set;
    }

    private static void applyConstraints(Object controller) {
        View root = view(controller);
        if (!enabled(root.getResources())) return;
        int qs = id(root, "qs_frame");
        int notifications = id(root, "shared_notification_container");
        if (qs == 0 || notifications == 0 || root.findViewById(qs) == null
                || root.findViewById(notifications) == null) return;
        Object original = constraints(root);
        nativeConstraints.put(controller, original);
        int qsMargin = Math.max(0, XposedHelpers.getIntField(controller, "panelMarginHorizontal"));
        View notificationView = root.findViewById(notifications);
        int notificationMargin = notificationView.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams margins
                ? Math.max(0, margins.getMarginStart()) : qsMargin;
        SplitLayout layout = new SplitLayout(original, qs, notifications, id(root, "split_shade_status_bar"),
                Math.max(qsMargin, notificationMargin), qsMargin + notificationMargin);
        layouts.put(root, layout);
        if (!onKeyguard(root)) layout.apply(root, root.getWidth());
        root.requestLayout();
    }

    private static final class SplitLayout {
        final Object original;
        final int qs, notifications, header, outerMargin, gap;
        MergedLandscapeGeometry.SplitPanels applied;
        Float clearAllTranslation;
        int appliedTop = Integer.MIN_VALUE;

        SplitLayout(Object original, int qs, int notifications, int header, int outerMargin, int gap) {
            this.original = original;
            this.qs = qs;
            this.notifications = notifications;
            this.header = header;
            this.outerMargin = outerMargin;
            this.gap = gap;
        }

        void apply(View root, int width) {
            int available = width - root.getPaddingLeft() - root.getPaddingRight();
            if (available <= 0) return;
            WindowInsets windowInsets = root.getRootWindowInsets();
            Insets safe = windowInsets == null ? Insets.NONE
                    : windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
            int[] location = new int[2];
            root.getLocationInWindow(location);
            int safeLeft = Math.max(0, safe.left - location[0] - root.getPaddingLeft());
            int safeRight = Math.max(0, location[0] + width - root.getPaddingRight()
                    - (root.getRootView().getWidth() - safe.right));
            MergedLandscapeGeometry.SplitPanels panels = MergedLandscapeGeometry.splitPanels(
                    available, root.getResources().getDisplayMetrics().density, outerMargin, gap,
                    safeLeft, safeRight);
            Object originalQs = XposedHelpers.callMethod(original, "getConstraint", qs);
            int nativeTop = XposedHelpers.getIntField(XposedHelpers.getObjectField(originalQs, "layout"), "topMargin");
            int top = Math.max(nativeTop, safe.top - location[1] - root.getPaddingTop());
            View clearAll = root.findViewById(id(root, "clear_all"));
            if (clearAll != null) {
                if (clearAllTranslation == null) clearAllTranslation = clearAll.getTranslationX();
                clearAll.setTranslationX(0f);
            }
            if (panels.equals(applied) && top == appliedTop && clearAllCentered(clearAll, notifications)
                    && placed(root.findViewById(notifications), panels.notificationLeft(), panels.notificationWidth())
                    && placed(root.findViewById(qs), panels.notificationLeft(), panels.groupWidth())) return;
            Object set = constraints(root);
            place(set, notifications, panels.notificationLeft(), panels.notificationWidth());
            // qs_frame hosts both the shared header and the right-column content.
            // Its children use coordinates relative to the start of the combined group.
            place(set, qs, panels.notificationLeft(), panels.groupWidth());
            XposedHelpers.callMethod(set, "setMargin", qs, 3, top);
            if (header != 0 && root.findViewById(header) != null)
                place(set, header, panels.notificationLeft(), panels.groupWidth());
            if (clearAll != null) {
                int target = clearAll.getId();
                for (int side : new int[]{1, 2, 6, 7}) XposedHelpers.callMethod(set, "clear", target, side);
                XposedHelpers.callMethod(set, "connect", target, 1, notifications, 1, 0);
                XposedHelpers.callMethod(set, "connect", target, 2, notifications, 2, 0);
                // 当前 ROM 的 ConstraintSet 未保留 setHorizontalBias 方法。
                Object constraint = XposedHelpers.callMethod(set, "getConstraint", target);
                XposedHelpers.setFloatField(XposedHelpers.getObjectField(constraint, "layout"), "horizontalBias", .5f);
            }
            applyLayoutOnly(set, root);
            applied = panels;
            appliedTop = top;
        }

        void suspend(View root) {
            if (applied == null) return;
            restoreClearAllTranslation(root);
            applyLayoutOnly(original, root);
            applied = null;
            appliedTop = Integer.MIN_VALUE;
        }

        void restoreClearAllTranslation(View root) {
            View button = root.findViewById(id(root, "clear_all"));
            if (button != null && clearAllTranslation != null) button.setTranslationX(clearAllTranslation);
        }
    }

    private static boolean clearAllCentered(View button, int notifications) {
        if (button == null) return true;
        Object params = button.getLayoutParams();
        return XposedHelpers.getIntField(params, "leftToLeft") == notifications
                && XposedHelpers.getIntField(params, "rightToRight") == notifications
                && XposedHelpers.getIntField(params, "startToStart") == -1
                && XposedHelpers.getIntField(params, "endToEnd") == -1
                && XposedHelpers.getFloatField(params, "horizontalBias") == .5f;
    }

    private static boolean placed(View view, int left, int width) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)) return false;
        return params.width == width && params.leftMargin == left
                && XposedHelpers.getIntField(params, "leftToLeft") == 0
                && XposedHelpers.getIntField(params, "rightToRight") == -1
                && XposedHelpers.getIntField(params, "startToStart") == -1
                && XposedHelpers.getIntField(params, "endToEnd") == -1;
    }

    private static void place(Object set, int target, int left, int width) {
        for (int side : new int[]{1, 2, 6, 7}) XposedHelpers.callMethod(set, "clear", target, side);
        XposedHelpers.callMethod(set, "constrainWidth", target, width);
        XposedHelpers.callMethod(set, "connect", target, 1, 0, 1, left);
    }

    private static void restoreConstraints(Object controller) {
        View root = view(controller);
        SplitLayout layout = layouts.remove(root);
        if (layout != null) layout.restoreClearAllTranslation(root);
        Object original = nativeConstraints.remove(controller);
        if (original != null) applyLayoutOnly(original, root);
    }

    private static void updateMedia(View media) {
        if (mediaResourcesMethod != null) XposedHelpers.callMethod(media, mediaResourcesMethod.getName());
        media.requestLayout();
    }

    static void refresh() {
        boolean current = readBool(KEY_QS_MERGED_LANDSCAPE_SWAP, false);
        if (lastEnabled != null && lastEnabled == current) return;
        lastEnabled = current;
        for (View media : new ArrayList<>(mediaViews.keySet())) {
            try { updateMedia(media); }
            catch (Throwable error) { log("merged split media refresh failed: " + error); }
        }
        for (Object shade : new ArrayList<>(shades.keySet())) {
            try { XposedHelpers.callMethod(shade, "updateResources"); }
            catch (Throwable error) { log("merged split shade refresh failed: " + error); }
        }
        for (Object container : new ArrayList<>(containers.keySet())) {
            try { XposedHelpers.callMethod(container, "updateResources"); }
            catch (Throwable error) { log("merged split container refresh failed: " + error); }
        }
    }

    static ArrayList<Object> capture() {
        ArrayList<Object> state = new ArrayList<>(shades.keySet());
        state.addAll(containers.keySet());
        state.addAll(mediaViews.keySet());
        return state;
    }

    static void restoreHosts(Iterable<?> state) {
        for (Object host : state) {
            if (shadeClass != null && shadeClass.isInstance(host)) shades.put(host, true);
            if (containerClass != null && containerClass.isInstance(host)) containers.put(host, true);
            if (mediaClass != null && mediaClass.isInstance(host)) mediaViews.put((View) host, true);
        }
        lastEnabled = null;
    }

    static void cleanup() {
        restoring = true;
        try {
            for (View child : new ArrayList<>(childBounds.keySet())) childBounds.get(child).restore(child);
            childBounds.clear();
            for (Object container : new ArrayList<>(nativeConstraints.keySet())) restoreConstraints(container);
            for (View media : new ArrayList<>(mediaViews.keySet())) updateMedia(media);
            for (Object shade : new ArrayList<>(shades.keySet())) XposedHelpers.callMethod(shade, "updateResources");
        } finally {
            restoring = false;
            shades.clear(); containers.clear(); nativeConstraints.clear(); layouts.clear(); mediaViews.clear();
            lastEnabled = null;
            phoneScope.remove();
            panelExpansion.remove();
        }
    }
}
