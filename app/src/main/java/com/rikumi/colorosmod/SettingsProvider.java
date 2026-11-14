package com.rikumi.colorosmod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class SettingsProvider extends ContentProvider {
    private final ChargingInputReader chargingInputReader = new ChargingInputReader();
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
        if (getContext() != null) {
            Context context = getContext().getApplicationContext();
            FontFailureLog.initialize(context);
            new Thread(() -> SystemFontMounts.INSTANCE.resume(context), "font-mount-recovery").start();
        }
        return true;
    }

    private SharedPreferences prefs() {
        Context ctx = getContext();
        if (ctx == null) return null;
        Context de = ctx.createDeviceProtectedStorageContext();
        SharedPreferences preferences = (de != null ? de : ctx).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        migrateNotificationIconPrefs(ctx, preferences);
        migrateKeyguardClockSpacing(ctx, preferences);
        HiddenLauncherApps.migrate(ctx, preferences);
        return preferences;
    }

    // Split the former shared icon behavior once, preserving an explicit panel choice.
    // Persist false even on a fresh install so a later master toggle cannot trigger migration.
    public static void migrateNotificationIconPrefs(Context context, SharedPreferences preferences) {
        synchronized (preferences) {
            String panelKey = "notification_center_monochrome_icons_enabled";
            String statusBarKey = "statusbar_monochrome_notification_icons_enabled";
            if (!preferences.contains(panelKey)
                    && preferences.edit().putBoolean(panelKey,
                            preferences.getBoolean(statusBarKey, false)).commit()) {
                notifySettingsChanged(context);
            }
        }
    }

    public static void migrateKeyguardClockSpacing(Context context, SharedPreferences preferences) {
        synchronized (preferences) {
            String migrated = "keyguard_clock_spacing_shifted_v1";
            if (preferences.getBoolean(migrated, false)) return;
            SharedPreferences.Editor editor = preferences.edit().putBoolean(migrated, true);
            String key = "keyguard_clock_spacing";
            if (preferences.contains(key)) editor.putInt(key, preferences.getInt(key, -4) + 4);
            if (editor.commit()) notifySettingsChanged(context);
        }
    }

    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        if (ChargingInfo.METHOD.equals(method)) {
            Context context = getContext();
            if (context == null) return null;
            int uid = android.os.Binder.getCallingUid();
            boolean allowed = uid == android.os.Process.myUid();
            try { allowed |= uid == context.getPackageManager().getPackageUid("com.android.systemui", 0); }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
            if (!allowed) throw new SecurityException("Only SystemUI may read charging input");
            android.os.Bundle result = new android.os.Bundle();
            SharedPreferences preferences = prefs();
            int mode = preferences == null ? 0 : preferences.getInt(ChargingInfoHooks.KEY, 0);
            if (ChargingInfo.enabled(mode)) {
                long identity = android.os.Binder.clearCallingIdentity();
                try {
                    result.putString("sample_micro", chargingInputReader.read(mode == ChargingInfo.POWER_PROTOCOL));
                    if (mode == ChargingInfo.POWER_PROTOCOL) result.putString("protocol", chargingInputReader.protocol());
                    result.putString("status", chargingInputReader.status());
                } finally { android.os.Binder.restoreCallingIdentity(identity); }
            }
            return result;
        }
        if (!FolderGridSettings.NATIVE_GRID.equals(method))
            return super.call(method, arg, extras);
        Context context = getContext();
        if (context == null) return null;
        String[] callers = context.getPackageManager().getPackagesForUid(android.os.Binder.getCallingUid());
        if (callers == null || !java.util.Arrays.asList(callers).contains("com.android.launcher"))
            throw new SecurityException("Launcher lifecycle calls require Launcher UID");
        if (extras != null) FolderGridSettings.recordNative(context,
                extras.getInt(FolderGridSettings.ROWS), extras.getInt(FolderGridSettings.COLUMNS));
        return new android.os.Bundle();
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
