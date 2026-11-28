package com.rikumi.colorosmod.hooks;

import android.view.View;
import android.widget.TextView;

/** The information row can contain both a date and carrier labels. Hide labels only. */
final class QsCarrierTextTargets {
    static boolean isCarrierText(View view) {
        if (!(view instanceof TextView) || QsHeaderVisibility.containsClock(view)) return false;
        String id = "";
        if (view.getId() != View.NO_ID) {
            try { id = view.getResources().getResourceEntryName(view.getId()); }
            catch (android.content.res.Resources.NotFoundException ignored) { }
        }
        if (id.contains("date") || id.contains("clock")) return false;
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if (name.contains("Date") || name.contains("Clock")) return false;
        }
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass())
            if (type.getSimpleName().endsWith("CarrierText")) return true;
        return id.contains("carrier");
    }
}
