package com.rikumi.colorosmod.hooks;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import com.rikumi.colorosmod.xposed.*;
import static com.rikumi.colorosmod.XposedInit.*;

/** Collapse carrier-only rows; retain rows that also own a date or another control. */
final class QsCarrierRows {
    private static final WeakHashMap<ViewGroup, State> rows = new WeakHashMap<>();
    private static boolean hiding, writing;
    private static final WeakHashMap<Object, Allocation> allocations = new WeakHashMap<>();
    private static boolean updatingAllocation;
    private static final class Allocation {
        final WeakReference<ViewGroup> row;
        WeakReference<View> icons;
        int nativeHeight;
        boolean collapsed;
        Allocation(ViewGroup view, View statusIcons) { row = new WeakReference<>(view); icons = new WeakReference<>(statusIcons); }
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (android.os.Build.VERSION.SDK_INT < 37) return;
        Class<?> provider = XposedHelpers.findClassIfExists("com.oplus.systemui.plugins.qs.CellAreaProvider", lp.classLoader);
        if (provider == null) return;
        Class<?> config = XposedHelpers.findClassIfExists("com.oplus.systemui.plugins.qs.CellAreaConfig", lp.classLoader);
        if (config != null) for (java.lang.reflect.Method method : config.getDeclaredMethods())
            if (method.getName().equals("update")) XposedBridge.deoptimize(method);
        XposedBridge.hookAllMethods(provider, "notifyConfigUpdate", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Allocation allocation = allocations.get(p.thisObject);
                if (allocation != null) applyAllocation(p.thisObject, allocation, false);
            }
        });
    }

    static void trackController(Object controller, boolean hide) {
        try {
            Object container = XposedHelpers.getObjectField(controller, "statusBarContainer");
            Object provider = XposedHelpers.getObjectField(controller, "cellAreaProvider");
            if (!(container instanceof ViewGroup row) || provider == null) return;
            View icons = null;
            try {
                Object value = XposedHelpers.getObjectField(controller, "statusIconsView");
                if (value instanceof View view) icons = view;
            } catch (NoSuchFieldError unavailable) { }
            Allocation allocation = allocations.get(provider);
            if (allocation == null || allocation.row.get() != row) {
                Allocation replacement = new Allocation(row, icons);
                if (allocation != null) {
                    replacement.nativeHeight = allocation.nativeHeight;
                    replacement.collapsed = allocation.collapsed;
                }
                allocations.put(provider, replacement);
            } else if (allocation.icons.get() != icons) allocation.icons = new WeakReference<>(icons);
            track(row, hide);
        } catch (NoSuchFieldError unavailable) { /* The leaf/row hooks remain available. */ }
    }

    private static void applyAllocation(Object provider, Allocation allocation, boolean notify) {
        ViewGroup row = allocation.row.get();
        State state = row == null ? null : rows.get(row);
        boolean collapse = state != null && state.collapsed;
        try {
            Object config = XposedHelpers.callMethod(provider, "getCellAreaConfig");
            int current = XposedHelpers.getIntField(config, "statusBarHeight");
            if (XposedHelpers.getBooleanField(config, "isEditMode")) allocation.nativeHeight = 0;
            else if (!allocation.collapsed || current != 0) allocation.nativeHeight = current;
            int target = collapse ? 0 : allocation.nativeHeight;
            allocation.collapsed = collapse;
            if (current == target) return;
            // Keep statusBarRealHeight: fake status-icon animations still need the physical size.
            XposedHelpers.setIntField(config, "statusBarHeight", target);
            if (notify && !updatingAllocation) {
                updatingAllocation = true;
                try { XposedHelpers.callMethod(provider, "notifyConfigUpdate"); }
                finally { updatingAllocation = false; }
            }
        } catch (NoSuchFieldError | NoSuchMethodError unavailable) {
            log("separate carrier area allocation unavailable: " + unavailable);
        }
    }

    private static final class State {
        int visibility, height, minimum, top, bottom;
        boolean collapsed;
        View.OnLayoutChangeListener listener;
        State(ViewGroup row) { capture(row); }
        void capture(ViewGroup row) {
            visibility = row.getVisibility();
            minimum = row.getMinimumHeight();
            ViewGroup.LayoutParams params = row.getLayoutParams();
            height = params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height;
            if (params instanceof ViewGroup.MarginLayoutParams margins) {
                top = margins.topMargin;
                bottom = margins.bottomMargin;
            }
        }
    }

    static void track(ViewGroup row, boolean hide) {
        hiding = hide;
        State state = rows.get(row);
        if (state == null) {
            state = new State(row);
            state.listener = (view, l, t, r, b, ol, ot, or, ob) -> {
                if (!writing) refresh(hiding);
            };
            rows.put(row, state);
            row.addOnLayoutChangeListener(state.listener);
        }
        apply(row, state);
    }

    static void discoverParents(View view, boolean hide) {
        for (Object parent = view.getParent(); parent instanceof ViewGroup row; parent = row.getParent()) {
            if (rows.containsKey(row)) {
                track(row, hide);
                continue;
            }
            if (row.getId() == View.NO_ID) continue;
            String name;
            try { name = row.getResources().getResourceEntryName(row.getId()); }
            catch (android.content.res.Resources.NotFoundException missing) { continue; }
            if (name.equals("carrier_group") || name.equals("qs_carrier_group")
                    || name.equals("qs_fake_carrier_container") || name.equals("qs_fake_keyguard_carrier_container"))
                track(row, hide);
        }
    }

    private static boolean carrierOnly(ViewGroup row) {
        return carrierOnly(row, null);
    }

    private static boolean carrierOnly(ViewGroup row, View ignoredIcons) {
        int[] labels = {0};
        return carrierContents(row, labels, true, ignoredIcons == row ? null : ignoredIcons) && labels[0] > 0;
    }

    private static boolean informationVisible(View view) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) return false;
        try { return ((Number) XposedHelpers.callMethod(view, "getTransitionAlpha")).floatValue() > 0f; }
        catch (NoSuchMethodError unavailable) { return true; }
    }

    private static boolean carrierContents(View view, int[] labels, boolean informationVisible, View ignoredIcons) {
        if (view == ignoredIcons) return true;
        if (view instanceof TextView) {
            if (QsCarrierTextTargets.isCarrierText(view)) { labels[0]++; return true; }
            return !informationVisible || !informationVisible(view);
        }
        if (view instanceof ViewGroup group) {
            if (view.getClass().getName().contains("ComposeView")) return !informationVisible;
            int before = labels[0];
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                State childState = child instanceof ViewGroup childGroup ? rows.get(childGroup) : null;
                boolean visible = informationVisible && (informationVisible(child)
                        || (childState != null && childState.collapsed));
                if (!carrierContents(child, labels, visible, ignoredIcons)) return false;
            }
            return !informationVisible || !(view.isClickable() || view.isLongClickable()) || labels[0] > before;
        }
        if (!informationVisible || !informationVisible(view)) return true;
        if (view.isClickable() || view.isLongClickable()) return false;
        // SIM icons and separators may disappear with their labels; unknown content stays visible.
        return view instanceof ImageView || view.getClass() == View.class;
    }

    private static void apply(ViewGroup row, State state) {
        Allocation statusRow = null;
        for (Allocation allocation : allocations.values())
            if (allocation.row.get() == row) { statusRow = allocation; break; }
        // The native status row owns the carrier on the left and status icons on
        // the right. Its shared space is removable only when BOTH are disabled.
        boolean collapse = hiding && (statusRow != null
                ? readBoolCached(KEY_QS_TOPMARGIN_ENABLED, false) && carrierOnly(row, statusRow.icons.get())
                : carrierOnly(row));
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (!state.collapsed) state.capture(row);
        else {
            // Resource/configuration updates can replace native dimensions while hidden.
            if (row.getVisibility() != View.GONE) state.visibility = row.getVisibility();
            if (row.getMinimumHeight() != 0) state.minimum = row.getMinimumHeight();
            if (params != null && params.height != 0) state.height = params.height;
            if (params instanceof ViewGroup.MarginLayoutParams margins) {
                if (margins.topMargin != 0) state.top = margins.topMargin;
                if (margins.bottomMargin != 0) state.bottom = margins.bottomMargin;
            }
        }
        if (!collapse && !state.collapsed) return;
        writing = true;
        try {
            if (params != null) {
                boolean changed = params.height != (collapse ? 0 : state.height);
                params.height = collapse ? 0 : state.height;
                if (params instanceof ViewGroup.MarginLayoutParams margins) {
                    int top = collapse ? 0 : state.top, bottom = collapse ? 0 : state.bottom;
                    changed |= margins.topMargin != top || margins.bottomMargin != bottom;
                    margins.topMargin = top;
                    margins.bottomMargin = bottom;
                }
                if (changed) row.setLayoutParams(params);
            }
            int minimum = collapse ? 0 : state.minimum;
            if (row.getMinimumHeight() != minimum) row.setMinimumHeight(minimum);
            int visibility = collapse ? View.GONE : state.visibility;
            if (row.getVisibility() != visibility) row.setVisibility(visibility);
            state.collapsed = collapse;
        } finally { writing = false; }
        if (!updatingAllocation) for (var entry : new ArrayList<>(allocations.entrySet()))
            if (entry.getValue().row.get() == row) applyAllocation(entry.getKey(), entry.getValue(), true);
    }

    static void refresh(boolean hide) {
        hiding = hide;
        for (var entry : new ArrayList<>(rows.entrySet())) apply(entry.getKey(), entry.getValue());
        if (!updatingAllocation) for (var entry : new ArrayList<>(allocations.entrySet()))
            applyAllocation(entry.getKey(), entry.getValue(), true);
    }

    static Object capture() {
        ArrayList<Object[]> saved = new ArrayList<>();
        for (var entry : new ArrayList<>(rows.entrySet())) {
            State s = entry.getValue();
            if (!s.collapsed) s.capture(entry.getKey());
            saved.add(new Object[]{entry.getKey(), s.visibility, s.height, s.minimum, s.top, s.bottom});
        }
        return saved;
    }

    static void restore(Object snapshot, boolean hide) {
        if (!(snapshot instanceof Iterable<?> entries)) return;
        for (Object item : entries) {
            if (!(item instanceof Object[] entry) || entry.length != 6 || !(entry[0] instanceof ViewGroup row)) continue;
            track(row, hide);
            State state = rows.get(row);
            state.visibility = (Integer) entry[1]; state.height = (Integer) entry[2];
            state.minimum = (Integer) entry[3]; state.top = (Integer) entry[4]; state.bottom = (Integer) entry[5];
            state.collapsed = true;
            apply(row, state);
        }
    }

    static void cleanup() {
        refresh(false);
        for (var entry : rows.entrySet()) entry.getKey().removeOnLayoutChangeListener(entry.getValue().listener);
        rows.clear();
        allocations.clear();
    }
}
