package com.rikumi.colorosmod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class SettingsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.rikumi.colorosmod.settings";
    public static final String PREF_NAME = "settings";
    // 一次性取回全部设置的特殊键, 供被 hook 进程后台预热(见 XposedInit#startSettingsLoader)。
    public static final String KEY_ALL = "__all__";

    public static final Uri NOTIFY_URI = Uri.parse("content://" + AUTHORITY + "/" + KEY_ALL);

    public static void notifySettingsChanged(Context context) {
        context.getContentResolver().notifyChange(NOTIFY_URI, null);
    }

    @Override
    public boolean onCreate() {
        if (getContext() != null) HomeControlsTileSettings.sync(getContext());
        return true;
    }

    private SharedPreferences prefs() {
        Context ctx = getContext();
        if (ctx == null) return null;
        Context de = ctx.createDeviceProtectedStorageContext();
        SharedPreferences preferences = (de != null ? de : ctx).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        migrateNotificationIconPrefs(ctx, preferences);
        HiddenLauncherApps.migrate(ctx, preferences);
        return preferences;
    }

    // Split the former shared icon behavior once, preserving an explicit panel choice.
    public static void migrateNotificationIconPrefs(Context context, SharedPreferences preferences) {
        synchronized (preferences) {
            String panelKey = "notification_center_monochrome_icons_enabled";
            String statusBarKey = "statusbar_monochrome_notification_icons_enabled";
            if (!preferences.contains(panelKey) && preferences.contains(statusBarKey)
                    && preferences.edit().putBoolean(panelKey,
                            preferences.getBoolean(statusBarKey, false)).commit()) {
                notifySettingsChanged(context);
            }
        }
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/setting";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            String key = uri.getLastPathSegment();
            if (key == null) return null;
            SharedPreferences sp = prefs();
            if (sp == null) return null;
            java.util.Map<String, ?> all = sp.getAll();
            if (KEY_ALL.equals(key)) {
                MatrixCursor all2 = new MatrixCursor(new String[]{"k", "v"});
                for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                    Integer v = intValue(e.getValue());
                    if (v != null) all2.addRow(new Object[]{e.getKey(), v});
                }
                Object components = all.get(HiddenLauncherApps.COMPONENTS);
                if (components instanceof java.util.Set<?>) {
                    for (Object component : (java.util.Set<?>) components) {
                        if (component instanceof String) {
                            all2.addRow(new Object[]{HiddenLauncherApps.PREFIX + component, 1});
                        }
                    }
                }
                Object navigationPackages = all.get(AppNavigationSettings.PACKAGES);
                if (navigationPackages instanceof java.util.Set<?>) {
                    all2.addRow(new Object[]{AppNavigationSettings.CONFIGURED, 1});
                    for (Object pkg : (java.util.Set<?>) navigationPackages) {
                        if (pkg instanceof String) all2.addRow(new Object[]{AppNavigationSettings.PREFIX + pkg, 1});
                    }
                }
                return all2;
            }
            Integer val = intValue(all.get(key));
            if (val == null) return new MatrixCursor(new String[]{"v"});
            MatrixCursor c = new MatrixCursor(new String[]{"v"});
            c.addRow(new Object[]{val});
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer intValue(Object raw) {
        if (raw instanceof Boolean) return ((Boolean) raw) ? 1 : 0;
        if (raw instanceof Number) return ((Number) raw).intValue();
        return null;
    }
}
