package com.rikumi.colorosmod;

import android.content.Context;
import android.content.SharedPreferences;

/** Folder limits depend on hardware type, not the current window or fold state. */
public final class FolderGridSettings {
    public static final String ROWS_ENABLED = "folder_rows_enabled";
    public static final String ROWS = "folder_rows";
    public static final String COLUMNS_ENABLED = "folder_columns_enabled";
    public static final String COLUMNS = "folder_columns";
    public static final String NATIVE_GRID = "launcher_native_folder_grid";

    public static boolean isFoldable() {
        return feature("oplus.hardware.type.fold") && !feature("oplus.software.fold_remap_display_disabled");
    }

    private static boolean feature(String name) {
        try {
            Class<?> manager = Class.forName("com.oplus.content.OplusFeatureConfigManager");
            Object instance = manager.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(manager.getMethod("hasFeature", String.class).invoke(instance, name));
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            return false;
        }
    }

    public static int maxRows() { return tabletOnly() ? 5 : 6; }
    public static int minColumns() { return tabletOnly() ? 4 : 3; }
    public static int maxColumns() { return tabletOnly() || isFoldable() ? 8 : 4; }
    private static boolean tabletOnly() { return TabletDevice.isTablet() && !isFoldable(); }
    public static int rows(int value, int nativeValue) {
        return value == 0 ? nativeValue : Math.max(3, Math.min(maxRows(), value));
    }
    public static int columns(int value, int nativeValue) {
        return value == 0 ? nativeValue : Math.max(minColumns(), Math.min(maxColumns(), value));
    }

    private static SharedPreferences metrics(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("launcher_folder_grid", Context.MODE_PRIVATE);
    }

    public static int nativeRows(Context context) { return metrics(context).getInt(ROWS, 4); }
    public static int nativeColumns(Context context) { return metrics(context).getInt(COLUMNS, 0); }
    public static int masterColumns(Context context) {
        if (isFoldable()) {
            int value = nativeColumns(context);
            // 0 follows native until Launcher reports its profile.
            return value == 0 ? 0 : columns(value, value);
        }
        return tabletOnly() ? 6 : 3;
    }
    public static int displayColumns(Context context) {
        int nativeValue = nativeColumns(context);
        return nativeValue > 0 ? nativeValue : (tabletOnly() ? 6 : 3);
    }

    public static void recordNative(Context context, int rows, int columns) {
        if (rows <= 0 || columns <= 0) return;
        metrics(context).edit().putInt(ROWS, rows).putInt(COLUMNS, columns).apply();
    }

    private FolderGridSettings() {}
}
