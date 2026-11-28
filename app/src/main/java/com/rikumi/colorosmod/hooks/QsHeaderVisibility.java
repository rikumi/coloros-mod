package com.rikumi.colorosmod.hooks;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Clock/date containers must not be hidden as carrier or status-icon groups. */
final class QsHeaderVisibility {
    private static final java.util.Set<View> clockViews = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    static boolean containsClock(Object controller, View group) {
        trackClockViews(controller);
        return containsClock(group);
    }

    static void trackClockViews(Object controller) {
        boolean changed = false;
        for (String name : new String[]{"qsClock", "qsDate"}) {
            Object value = field(controller, name);
            if (value instanceof View clock) changed |= clockViews.add(clock);
        }
        Object entrance = field(controller, "quickEntranceContainerViewController");
        if (entrance != null) {
            for (String getter : new String[]{"getClockView", "getDateView"}) {
                View view = callView(entrance, getter);
                if (view != null) changed |= clockViews.add(view);
            }
        }
        Object element = field(controller, "qsFakeClock");
        View clock = element == null ? null : callView(element, "getElementView");
        if (clock != null) changed |= clockViews.add(clock);
        // A hot-reloaded snapshot can predate date-view discovery. Re-evaluate
        // previously hidden labels when their actual date identity becomes known.
        if (changed) StatusBarExtrasHooks.refresh();
    }

    private static Object field(Object object, String name) {
        try { return XposedHelpers.getObjectField(object, name); }
        catch (NoSuchFieldError unavailable) { return null; }
    }

    private static View callView(Object object, String name) {
        try {
            Object value = XposedHelpers.callMethod(object, name);
            return value instanceof View view ? view : null;
        } catch (NoSuchMethodError unavailable) { return null; }
    }

    static boolean containsClock(View view) {
        if (view == null) return false;
        if (clockViews.contains(view)) return true;
        if (view instanceof TextClock) return true;
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if (name.endsWith("Clock") || name.endsWith("ClockView") || name.equals("OplusQSDateView")) return true;
        }
        if (view.getId() != View.NO_ID) {
            try {
                String name = view.getResources().getResourceEntryName(view.getId());
                if (name.equals("clock") || name.equals("date") || name.equals("qs_clock")
                        || name.equals("qs_date") || name.equals("clock_container")
                        || name.equals("qs_clock_container")) return true;
            } catch (android.content.res.Resources.NotFoundException ignored) { }
        }
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsClock(group.getChildAt(i))) return true;
        return false;
    }
}
