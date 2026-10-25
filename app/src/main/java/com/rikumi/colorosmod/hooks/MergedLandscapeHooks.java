package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Portrait-sized merged cards and icon-only tiles, preserving native landscape paging. */
final class MergedLandscapeHooks {
    private static Class<?> containerClass;
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

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            containerClass = XposedHelpers.findClass("com.oplus.systemui.qs.OplusQSContainerImpl", lp.classLoader);
            XposedHelpers.findAndHookMethod(containerClass, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    roots.put(root, true);
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
                    // Keep the shade/root and customizer full-width; only the merged
                    // control contents use portrait width and are centered at layout.
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
            // These are the outer spacers around the clock/header and the last
            // row. Card-region gaps and all native paging/expansion remain intact.
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
        roots.clear(); visibility.clear(); collapsed.clear(); portraitResources.clear();
    }
}
