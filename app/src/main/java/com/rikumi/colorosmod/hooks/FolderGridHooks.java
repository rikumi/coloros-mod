package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import static com.rikumi.colorosmod.FolderGridSettings.*;

import android.content.Context;
import android.os.Bundle;
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
        Class<?> oplus = XposedHelpers.findClass("com.android.launcher3.OplusInvariantDeviceProfile", lp.classLoader);
        Class<?> info = XposedHelpers.findClass("com.android.launcher3.util.DisplayController$Info", lp.classLoader);
        Class<?> option = XposedHelpers.findClass("com.android.launcher3.InvariantDeviceProfile$DisplayOption", lp.classLoader);
        XposedHelpers.findAndHookMethod(oplus, "initGridExt", Context.class, info, option, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!active) return;
                        p.setObjectExtra("previousFolderInit", initializing.get());
                        Init state = new Init(p.thisObject, ((Context) p.args[0]).getApplicationContext());
                        p.setObjectExtra("folderInit", state);
                        initializing.set(state);
                        // A cached DeviceProfile already contains cell dimensions. Do
                        // not reuse it while customizing or restoring folder dimensions.
                        Object previous = XposedHelpers.getAdditionalInstanceField(p.thisObject, "colorosmodFolderGrid");
                        if (customizing() || previous instanceof int[]
                                || Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                        p.thisObject, "colorosmodFolderGridCustomized"))) clearCache(p.thisObject);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object value = p.getObjectExtra("folderInit");
                        if (!(value instanceof Init state)) return;
                        try {
                            if (p.hasThrowable() || !active) return;
                            if (state.nativeRows == 0) state.captureNative();
                            profile = new WeakReference<>(state.idp);
                            context = new WeakReference<>(state.host);
                            reportNative(state.host, state.nativeRows, state.nativeColumns);
                            apply(state.idp, state.nativeRows, state.nativeColumns);
                            boolean custom = customizing();
                            XposedHelpers.setAdditionalInstanceField(state.idp, "colorosmodFolderGridCustomized", custom);
                            // The former implementation stored this field. Clear it
                            // once so disabled native profiles retain their cache again.
                            XposedHelpers.removeAdditionalInstanceField(state.idp, "colorosmodFolderGrid");
                            log("folder grid profile: native=" + state.nativeRows + "x" + state.nativeColumns
                                    + ", applied=" + XposedHelpers.getIntField(state.idp, "numFolderRows") + "x"
                                    + XposedHelpers.getIntField(state.idp, "numFolderColumns"));
                        } finally {
                            Init previous = (Init) p.getObjectExtra("previousFolderInit");
                            if (previous == null) initializing.remove(); else initializing.set(previous);
                        }
                    }
                });
        Class<?> dp = XposedHelpers.findClass("com.android.launcher3.DeviceProfile", lp.classLoader);
        XposedBridge.hookAllConstructors(dp, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Init state = initializing.get();
                if (!active || state == null) return;
                for (Object arg : p.args) if (arg == state.idp) {
                    // Native simple/tablet overrides have completed by this point.
                    // Restore the IDP after each constructor so subsequent profiles
                    // see the same native baseline instead of our customized values.
                    int rows = XposedHelpers.getIntField(arg, "numFolderRows");
                    int columns = XposedHelpers.getIntField(arg, "numFolderColumns");
                    if (state.nativeRows == 0) state.captureNative();
                    p.setObjectExtra("folderProfileBaseline", new Object[]{arg, rows, columns});
                    XposedHelpers.setAdditionalInstanceField(arg, FolderGridWidthHooks.NATIVE_COLUMNS, state.nativeColumns);
                    apply(arg, state.nativeRows, state.nativeColumns);
                    p.setObjectExtra("folderWideMargins", FolderGridWidthHooks.removeMargins(arg, state.nativeColumns));
                    return;
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                FolderGridWidthHooks.restoreMargins(p.getObjectExtra("folderWideMargins"));
                Object saved = p.getObjectExtra("folderProfileBaseline");
                if (saved instanceof Object[] baseline) {
                    XposedHelpers.setIntField(baseline[0], "numFolderRows", (Integer) baseline[1]);
                    XposedHelpers.setIntField(baseline[0], "numFolderColumns", (Integer) baseline[2]);
                }
            }
        });
        try { FolderGridWidthHooks.hook(dp, idpClass); }
        catch (ReflectiveOperationException error) { log("folder grid width refresh path unavailable: " + error); }
        XposedBridge.hookAllMethods(oplus, "initGridIntercept", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (active && Boolean.TRUE.equals(rebuilding.get())) p.setResult(null);
            }
        });
        XposedHelpers.findAndHookMethod("com.android.launcher3.folder.FolderPagedView", lp.classLoader,
                "setupContentDimensions", int.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!active || p.hasThrowable() || !customizing()) return;
                        int rows = XposedHelpers.getIntField(p.thisObject, "mGridCountY");
                        int columns = XposedHelpers.getIntField(p.thisObject, "mGridCountX");
                        String grid = rows + "x" + columns;
                        if (grid.equals(XposedHelpers.getAdditionalInstanceField(p.thisObject, "colorosmodFolderPageGrid"))) return;
                        XposedHelpers.setAdditionalInstanceField(p.thisObject, "colorosmodFolderPageGrid", grid);
                        log("folder grid page: " + grid);
                    }
                });
        log("HOOK OK mandatory folder grid initialization and DeviceProfile sizing");
    }

    private static final ThreadLocal<Init> initializing = new ThreadLocal<>();
    private static final class Init {
        final Object idp;
        final Context host;
        int nativeRows, nativeColumns;
        Init(Object idp, Context host) { this.idp = idp; this.host = host; }
        void captureNative() {
            nativeRows = XposedHelpers.getIntField(idp, "numFolderRows");
            nativeColumns = XposedHelpers.getIntField(idp, "numFolderColumns");
        }
    }

    private static boolean customizing() {
        return readBoolCached(ROWS_ENABLED, false) || readBoolCached(COLUMNS_ENABLED, false);
    }

    private static void apply(Object idp, int nativeRows, int nativeColumns) {
        int rows = readBoolCached(ROWS_ENABLED, false)
                ? FolderGridSettings.rows(readIntCached(ROWS, 0), nativeRows) : nativeRows;
        int columns = readBoolCached(COLUMNS_ENABLED, false)
                ? FolderGridSettings.columns(readIntCached(COLUMNS, 0), nativeColumns) : nativeColumns;
        XposedHelpers.setIntField(idp, "numFolderRows", rows);
        XposedHelpers.setIntField(idp, "numFolderColumns", columns);
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

    static Object captureHost() {
        Object idp = profile.get();
        // Keep only host/framework objects across module ClassLoader generations.
        // Additional instance fields are cleared when the old generation detaches.
        boolean customized = idp != null && Boolean.TRUE.equals(
                XposedHelpers.getAdditionalInstanceField(idp, "colorosmodFolderGridCustomized"));
        return new Object[] {idp, context.get(), customized};
    }

    static void restoreHost(Object saved) {
        if (!active || idpClass == null) return;
        boolean wasCustomized = false;
        if (saved instanceof Object[] state && state.length >= 3) {
            if (idpClass.isInstance(state[0])) profile = new WeakReference<>(state[0]);
            if (state[1] instanceof Context host) context = new WeakReference<>(host);
            wasCustomized = Boolean.TRUE.equals(state[2]);
        }
        // Also works when upgrading from a generation without a folder snapshot:
        // refresh() resolves the singleton through the restored Launcher activity.
        // Rebuild even for unchanged settings, or when disabling a customized grid.
        // This recreates DeviceProfiles, width baselines and bound folder layouts.
        if (customizing() || wasCustomized) {
            LauncherIdleRefresh.post(REFRESH, FolderGridHooks::refresh);
        }
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
