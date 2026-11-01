package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.xposed.XposedHelpers;

/** ColorOS 17 keeps the view-based QS implementation in qs.deprecated. */
final class QsClassCompat {
    private QsClassCompat() {}

    static Class<?> find(String legacy, ClassLoader loader) {
        Class<?> type = XposedHelpers.findClassIfExists(legacy, loader);
        return type != null ? type : XposedHelpers.findClass(relocated(legacy), loader);
    }

    static boolean matches(Class<?> type, String legacy) {
        String name = type.getName();
        return name.equals(legacy) || name.equals(relocated(legacy));
    }

    private static String relocated(String legacy) {
        if (legacy.equals("com.oplus.systemui.qs.widget.OplusHeaderTileLayout"))
            return "com.android.systemui.qs.deprecated.OplusHeaderTileLayout";
        return switch (legacy) {
            case "com.android.systemui.qs.QSImpl", "com.android.systemui.qs.TileLayout",
                    "com.android.systemui.qs.SideLabelTileLayout", "com.android.systemui.qs.PagedTileLayout",
                    "com.android.systemui.qs.QSPanel", "com.android.systemui.qs.QuickQSPanel",
                    "com.android.systemui.qs.QuickQSPanelController" ->
                legacy.replace("com.android.systemui.qs.", "com.android.systemui.qs.deprecated.");
            default -> legacy;
        };
    }
}
