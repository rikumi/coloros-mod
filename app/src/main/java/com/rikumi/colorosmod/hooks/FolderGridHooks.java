package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import static com.rikumi.colorosmod.FolderGridSettings.*;

import android.content.Context;
import android.os.Bundle;
import android.util.DisplayMetrics;
import com.rikumi.colorosmod.FolderGridSettings;
import com.rikumi.colorosmod.SettingsProvider;
import com.rikumi.colorosmod.xposed.*;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Apply the grid before native DeviceProfile cell sizing and folder pagination. */
final class FolderGridHooks {
    private static final Object REFRESH = new Object();
    private static final ThreadLocal<Boolean> rebuilding = new ThreadLocal<>();
    private static final ExecutorService REPORTER = Executors.newSingleThreadExecutor();
    private static WeakReference<Object> profile = new WeakReference<>(null);
    private static WeakReference<Context> context = new WeakReference<>(null);
    private static Class<?> idpClass;
    private static int[] lastSettings;
    private static volatile boolean active = true;
    private static int reportedRows, reportedColumns;

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        idpClass = XposedHelpers.findClass("com.android.launcher3.InvariantDeviceProfile", lp.classLoader);
        XposedHelpers.findAndHookMethod(idpClass, "applyPartnerDeviceProfileOverrides",
                Context.class, DisplayMetrics.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!active || p.hasThrowable()) return;
                        Object idp = p.thisObject;
                        Context host = ((Context) p.args[0]).getApplicationContext();
                        profile = new WeakReference<>(idp);
                        context = new WeakReference<>(host);
                        // This stage follows native simple/fold/tablet overrides and
                        // precedes OplusInvariantDeviceProfile's profile cache/build.
                        int nativeRows = XposedHelpers.getIntField(idp, "numFolderRows");
                        int nativeColumns = XposedHelpers.getIntField(idp, "numFolderColumns");
                        reportNative(host, nativeRows, nativeColumns);
                        int rows = readBoolCached(ROWS_ENABLED, false)
                                ? FolderGridSettings.rows(readIntCached(ROWS, 0), nativeRows) : nativeRows;
                        int columns = readBoolCached(COLUMNS_ENABLED, false)
                                ? FolderGridSettings.columns(readIntCached(COLUMNS, 0), nativeColumns) : nativeColumns;
                        Object saved = XposedHelpers.getAdditionalInstanceField(idp, "colorosmodFolderGrid");
                        int[] previous = saved instanceof int[] ? (int[]) saved : new int[]{nativeRows, nativeColumns};
                        if (previous[0] != rows || previous[1] != columns) clearCache(idp);
                        XposedHelpers.setIntField(idp, "numFolderRows", rows);
                        XposedHelpers.setIntField(idp, "numFolderColumns", columns);
                        XposedHelpers.setAdditionalInstanceField(idp, "colorosmodFolderGrid", new int[]{rows, columns});
                    }
                });
        Class<?> oplus = XposedHelpers.findClass("com.android.launcher3.OplusInvariantDeviceProfile", lp.classLoader);
        XposedBridge.hookAllMethods(oplus, "initGridIntercept", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (active && Boolean.TRUE.equals(rebuilding.get())) p.setResult(null);
            }
        });
        log("HOOK OK native folder grid profile and cache refresh");
    }

    private static void clearCache(Object idp) {
        // Older Launchers have no fold/rotation profile cache.
        try { XposedHelpers.setObjectField(idp, "mProfileCache", null); }
        catch (NoSuchFieldError ignored) { }
    }

    private static void reportNative(Context host, int rows, int columns) {
        if (rows == reportedRows && columns == reportedColumns) return;
        reportedRows = rows;
        reportedColumns = columns;
        REPORTER.execute(() -> {
            if (!active) return;
            try {
                Bundle values = new Bundle();
                values.putInt(ROWS, rows);
                values.putInt(COLUMNS, columns);
                host.getContentResolver().call(SettingsProvider.AUTHORITY, NATIVE_GRID, null, values);
            } catch (Throwable error) { log("native folder grid report failed: " + error); }
        });
    }

    static void onSettingsSnapshotPublished(Map<String, Integer> settings) {
        if (!active || idpClass == null) return;
        int rowsEnabled = settings.getOrDefault(ROWS_ENABLED, 0);
        int columnsEnabled = settings.getOrDefault(COLUMNS_ENABLED, 0);
        int[] next = {rowsEnabled, rowsEnabled == 1 ? settings.getOrDefault(ROWS, 0) : 0,
                columnsEnabled, columnsEnabled == 1 ? settings.getOrDefault(COLUMNS, 0) : 0};
        if (Arrays.equals(lastSettings, next)) return;
        boolean first = lastSettings == null;
        lastSettings = next;
        if (first && next[0] == 0 && next[2] == 0) return;
        LauncherIdleRefresh.post(REFRESH, FolderGridHooks::refresh);
    }

    private static void refresh() {
        if (!active) return;
        try {
            Context host = context.get();
            Object idp = profile.get();
            if (host == null) {
                Object activity = LauncherIdleRefresh.captureActivity();
                if (activity instanceof Context) host = ((Context) activity).getApplicationContext();
            }
            if (host == null) return;
            if (idp == null) idp = XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(idpClass, "INSTANCE"), "get", host);
            String gridName = (String) XposedHelpers.callStaticMethod(idpClass, "getCurrentGridName", host);
            clearCache(idp);
            rebuilding.set(true);
            try { XposedHelpers.callMethod(idp, "initGrid", host, gridName, true); }
            finally { rebuilding.remove(); }
            // Native model-state comparison excludes folder dimensions. Force the
            // regular IDP change notification so bound folders and previews rebind.
            XposedHelpers.callMethod(idp, "notifyOnIdpChangeCompat", idp, true);
            log("launcher folder grid rebuilt");
        } catch (Throwable error) { log("launcher folder grid refresh failed: " + error); }
    }

    static void cleanup() {
        active = false;
        LauncherIdleRefresh.cancel(REFRESH);
        REPORTER.shutdownNow();
        profile.clear();
        context.clear();
    }
    private FolderGridHooks() {}
}
