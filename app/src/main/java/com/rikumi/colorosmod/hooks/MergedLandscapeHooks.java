package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class MergedLandscapeHooks {
    private static Class<?> containerClass, mediaContainerClass, footerControllerClass;
    private static final WeakHashMap<View, Boolean> roots = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> visibility = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> collapsed = new WeakHashMap<>();
    private static final WeakHashMap<View, Resources> portraitResources = new WeakHashMap<>();

    static boolean enabled(View view) {
        return view != null && readBool(KEY_QS_MERGED_CARD_RATIO, false)
                && view.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && root(view) != null;
    }

    private static ViewGroup root(View view) {
        for (View current = view; current != null;
                current = current.getParent() instanceof View parent ? parent : null)
            if (containerClass != null && containerClass.isInstance(current)) return (ViewGroup) current;
        return null;
    }

    static Resources resources(View view) {
        if (!enabled(view)) return view.getResources();
        View root = root(view);
        Resources cached = portraitResources.get(root);
        if (cached == null || cached.getConfiguration().densityDpi != view.getResources().getConfiguration().densityDpi
                || cached.getConfiguration().fontScale != view.getResources().getConfiguration().fontScale) {
            Configuration config = new Configuration(view.getResources().getConfiguration());
            config.orientation = Configuration.ORIENTATION_PORTRAIT;
            int width = Math.min(config.screenWidthDp, config.screenHeightDp);
            config.screenHeightDp = Math.max(config.screenWidthDp, config.screenHeightDp);
            config.screenWidthDp = width;
            cached = view.getContext().createConfigurationContext(config).getResources();
            portraitResources.put(root, cached);
        }
        return cached;
    }

    static void compact(View tile) {
        if (!enabled(tile)) return;
        View label = (View) XposedHelpers.getObjectField(tile, "mLabelContainer");
        hide(label, View.GONE);
        collapsed.putIfAbsent(tile, XposedHelpers.getBooleanField(tile, "mCollapsedView"));
        XposedHelpers.setBooleanField(tile, "mCollapsedView", true);
    }

    private static void hide(View view, int value) {
        if (view == null) return;
        visibility.putIfAbsent(view, view.getVisibility());
        if (view.getVisibility() != value) view.setVisibility(value);
    }

    private static boolean hideMediaHeader(View root) {
        if (!enabled(root) || root.getResources().getConfiguration().smallestScreenWidthDp >= 600)
            return false;
        View media = root.findViewById(id(root, "media_container"));
        return media != null && media.getVisibility() == View.VISIBLE
                && mediaContainerClass.isInstance(media.getParent())
                && Boolean.TRUE.equals(XposedHelpers.callMethod(media.getParent(), "getMediaMode"));
    }

    private static boolean updateMediaHeader(View root) {
        boolean hidden = hideMediaHeader(root);
        View footer = root.findViewById(id(root, "qs_footer"));
        if (footer != null) {
            if (hidden) hide(footer, View.GONE);
            else {
                Integer original = visibility.remove(footer);
                if (original != null) footer.setVisibility(original);
            }
        }
        updateMediaFrame(root, hidden);
        return hidden;
    }

    private static final WeakHashMap<View, FrameInset> frameInsets = new WeakHashMap<>();
    private static final class FrameInset {
        int original, applied;
        FrameInset(int top) { original = top; applied = top; }
    }

    private static void updateMediaFrame(View root, boolean hidden) {
        if (!(root.getParent() instanceof View frame) || frame.getId() != id(root, "qs_frame")
                || !(frame.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins)) return;
        FrameInset inset = frameInsets.get(frame);
        if (!hidden) { restoreFrameInset(frame); return; }
        if (inset == null) {
            inset = new FrameInset(margins.topMargin);
            frameInsets.put(frame, inset);
        } else if (margins.topMargin != inset.applied) {
            // 原生配置更新写入了新边距；记录新基准，避免重复减半。
            inset.original = margins.topMargin;
        }
        inset.applied = Math.max(0, Math.round(inset.original / 2f));
        if (margins.topMargin != inset.applied) {
            margins.topMargin = inset.applied;
            frame.setLayoutParams(margins);
        }
    }

    private static void restoreFrameInset(View frame) {
        FrameInset inset = frameInsets.remove(frame);
        if (inset != null && frame.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins
                && margins.topMargin == inset.applied && margins.topMargin != inset.original) {
            margins.topMargin = inset.original;
            frame.setLayoutParams(margins);
        }
    }

    private static void hookMediaHeader(XC_LoadPackage.LoadPackageParam lp) {
        mediaContainerClass = XposedHelpers.findClass("com.oplus.systemui.qs.OplusQSTileMediaContainer", lp.classLoader);
        footerControllerClass = XposedHelpers.findClass("com.oplus.systemui.qs.OplusQSFooterViewController", lp.classLoader);
        Class<?> controller = XposedHelpers.findClass("com.oplus.systemui.qs.OplusQSContainerImplController", lp.classLoader);
        Class<?> notificationContainer = XposedHelpers.findClass(
                "com.android.systemui.shade.NotificationsQuickSettingsContainer", lp.classLoader);
        Class<?> constraintLayout = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintLayout", lp.classLoader);
        XposedHelpers.findAndHookDeclaredMethod(constraintLayout, "onMeasure", int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!notificationContainer.isInstance(p.thisObject)) return;
                View parent = (View) p.thisObject;
                View root = parent.findViewById(id(parent, "quick_settings_container"));
                if (root != null && containerClass.isInstance(root)) updateMediaHeader(root);
            }
        });
        XposedHelpers.findAndHookMethod("com.android.systemui.shade.NotificationsQSContainerController", lp.classLoader,
                "updateResources", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        View frame = (View) XposedHelpers.callMethod(p.thisObject, "getQsFrame");
                        restoreFrameInset(frame);
                    }
                });
        XC_MethodHook layoutNodes = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View root = (View) XposedHelpers.callMethod(p.thisObject, "getView");
                if (!updateMediaHeader(root)) return;
                Object nodes = XposedHelpers.getObjectField(p.thisObject, "mNodes");
                if (!(nodes instanceof CopyOnWriteArrayList<?> original)) return;
                CopyOnWriteArrayList<Object> filtered = new CopyOnWriteArrayList<>(original);
                if (!filtered.removeIf(footerControllerClass::isInstance)) return;
                // 同步排除高度、间距和楼层索引；仅在本次原生布局计算期间替换节点列表。
                XposedHelpers.setObjectField(p.thisObject, "mNodes", filtered);
                p.setObjectExtra("landscapeMediaNodes", original);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object original = p.getObjectExtra("landscapeMediaNodes");
                if (original != null) XposedHelpers.setObjectField(p.thisObject, "mNodes", original);
            }
        };
        XposedHelpers.findAndHookMethod(controller, "calcExpandMeasureHeightExceptQsPanel", layoutNodes);
        XposedHelpers.findAndHookMethod(controller, "calcExpansionHeight", float.class, layoutNodes);
        XposedHelpers.findAndHookMethod(controller, "updatePosition$2", layoutNodes);
        XposedHelpers.findAndHookMethod(footerControllerClass, "setVisibility", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View footer = (View) XposedHelpers.callMethod(p.thisObject, "getView");
                View root = root(footer);
                if (root != null && hideMediaHeader(root)) {
                    visibility.put(footer, (Integer) p.args[0]);
                    p.args[0] = View.GONE;
                } else visibility.remove(footer);
            }
        });
        XposedHelpers.findAndHookMethod(mediaContainerClass, "setMediaMode", boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View root = root((View) p.thisObject);
                if (root != null) { updateMediaHeader(root); root.requestLayout(); }
            }
        });
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            containerClass = XposedHelpers.findClass("com.oplus.systemui.qs.OplusQSContainerImpl", lp.classLoader);
            hookMediaHeader(lp);
            XposedHelpers.findAndHookMethod(containerClass, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    roots.put(root, true);
                    updateMediaHeader(root);
                    if (!enabled(root)) { restore(root); return; }
                    int width = View.MeasureSpec.getSize((Integer) p.args[0]);
                    WindowManager manager = root.getContext().getSystemService(WindowManager.class);
                    android.graphics.Rect bounds = manager.getMaximumWindowMetrics().getBounds();
                    int contentWidth = MergedLandscapeGeometry.contentWidth(width, bounds.width(), bounds.height());
                    p.setObjectExtra("outerWidth", width);
                    p.args[0] = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.getObjectExtra("outerWidth") == null) return;
                    ViewGroup root = (ViewGroup) p.thisObject;

                    XposedHelpers.callMethod(root, "setMeasuredDimension", (Integer) p.getObjectExtra("outerWidth"), root.getMeasuredHeight());
                    View customizer = root.findViewById(id(root, "qs_customize"));
                    if (customizer != null) customizer.measure(
                            View.MeasureSpec.makeMeasureSpec((Integer) p.getObjectExtra("outerWidth"), View.MeasureSpec.EXACTLY), (Integer) p.args[1]);
                }
            });
            XposedHelpers.findAndHookMethod("com.android.systemui.qs.QSContainerImpl", lp.classLoader,
                    "onLayout", boolean.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            ViewGroup root = (ViewGroup) p.thisObject;
                            if (!enabled(root)) return;
                            for (int i = 0; i < root.getChildCount(); i++) {
                                View child = root.getChildAt(i);
                                if (child.getId() == id(root, "qs_customize")) continue;
                                child.offsetLeftAndRight((root.getWidth() - child.getWidth()) / 2 - child.getLeft());
                            }
                        }
                    });

            outerGap(lp, "com.oplus.systemui.qs.OplusQuickStatusBarHeaderController", "getTopGap", true);
            outerGap(lp, "com.oplus.systemui.qs.OplusQSFooterViewController", "getTopGap", true);
            outerGap(lp, "com.oplus.systemui.qs.OplusQSBottomViewController", "getBottomGap", false);
        } catch (Throwable error) { log("merged landscape hook failed: " + error); }
    }

    private static int id(View view, String name) {
        return NotificationResourceIds.get(view.getResources(), name, "id", "com.android.systemui");
    }

    private static void outerGap(XC_LoadPackage.LoadPackageParam lp, String owner, String method, boolean fraction) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View view = (View) XposedHelpers.callMethod(p.thisObject, "getView");
                if (!enabled(view) || !(p.getResult() instanceof Number gap)) return;
                Configuration config = view.getResources().getConfiguration();
                p.setResult(gap.floatValue() * MergedLandscapeGeometry.verticalInsetScale(config.screenWidthDp, config.screenHeightDp));
            }
        };
        if (fraction) XposedHelpers.findAndHookMethod(owner, lp.classLoader, method, float.class, hook);
        else XposedHelpers.findAndHookMethod(owner, lp.classLoader, method, hook);
    }

    private static boolean belongs(View view, View root) {
        for (View current = view; current != null;
                current = current.getParent() instanceof View parent ? parent : null)
            if (current == root) return true;
        return false;
    }

    private static void restore(View root) {
        if (root.getParent() instanceof View frame) restoreFrameInset(frame);
        for (View view : new ArrayList<>(visibility.keySet())) if (belongs(view, root)) {
            view.setVisibility(visibility.remove(view));
        }
        for (View view : new ArrayList<>(collapsed.keySet())) if (belongs(view, root)) {
            XposedHelpers.setBooleanField(view, "mCollapsedView", collapsed.remove(view));
        }
        portraitResources.remove(root);
    }

    static void refresh() {
        for (View root : new ArrayList<>(roots.keySet())) {
            if (!enabled(root)) restore(root);
            root.requestLayout();
        }
    }

    static void cleanup() {
        for (View root : new ArrayList<>(roots.keySet())) { restore(root); root.requestLayout(); }
        for (View frame : new ArrayList<>(frameInsets.keySet())) restoreFrameInset(frame);
        roots.clear(); visibility.clear(); collapsed.clear(); portraitResources.clear();
    }
}
