package com.rikumi.colorosmod;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/** 按桌面入口保存；旧的包级特殊规则只用于一次性迁移。 */
public final class HiddenLauncherApps {
    public static final String COMPONENTS = "hidden_launcher_components";
    public static final String ENABLED = "hidden_launcher_apps_enabled";
    public static final String PREFIX = "hidden_launcher_component:";
    private static final String MIGRATED = "hidden_launcher_components_migrated";

    @SuppressWarnings("deprecation")
    public static List<ResolveInfo> queryActivities(Context context) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return context.getPackageManager().queryIntentActivities(intent,
                PackageManager.GET_META_DATA | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
    }

    public static synchronized boolean migrate(Context context, SharedPreferences prefs) {
        if (prefs.getBoolean(MIGRATED, false)) return false;
        // 不用迁移标记抢先填充空 DE 存储，保留旧 CE 设置的首次迁移条件。
        if (prefs.getAll().isEmpty()) return false;
        Set<String> selected = new HashSet<>(prefs.getStringSet(COMPONENTS, java.util.Collections.emptySet()));
        String[][] legacy = {
                {"hide_contacts_enabled", "com.android.contacts/com.android.contacts.PeopleActivityAlias"},
                {"hide_gboard_enabled", "com.google.android.inputmethod.latin/com.google.android.libraries.inputmethod.launcher.LauncherActivity"},
                {"hide_ghostlock_enabled", "com.ghostlock.app/com.ghostlock.app.MainActivity"}
        };
        for (String[] entry : legacy) {
            if (prefs.getBoolean(entry[0], false)) selected.add(entry[1]);
        }
        if (prefs.getBoolean("hide_lsposed_modules_enabled", false)) {
            java.util.Map<String, Boolean> modules = new java.util.HashMap<>();
            // 仅迁移已安装且有桌面入口的模块，之后不再自动隐藏新安装的模块。
            for (ResolveInfo info : queryActivities(context)) {
                ApplicationInfo app = info.activityInfo.applicationInfo;
                if (!context.getPackageName().equals(app.packageName)
                        && modules.computeIfAbsent(app.packageName, key -> isModule(app))) {
                    selected.add(new ComponentName(info.activityInfo.packageName,
                            info.activityInfo.name).flattenToString());
                }
            }
        }
        SharedPreferences.Editor editor = prefs.edit().putStringSet(COMPONENTS, selected)
                .putBoolean(MIGRATED, true);
        if (!selected.isEmpty() && !prefs.contains(ENABLED)) editor.putBoolean(ENABLED, true);
        for (String[] entry : legacy) editor.remove(entry[0]);
        editor.remove("hide_lsposed_modules_enabled");
        if (!editor.commit()) throw new IllegalStateException("Cannot migrate hidden launcher components");
        return true;
    }

    private static boolean isModule(ApplicationInfo info) {
        Object marker = info.metaData == null ? null : info.metaData.get("xposedmodule");
        if (Boolean.TRUE.equals(marker) || (marker instanceof Number && ((Number) marker).intValue() == 1)
                || (marker != null && "true".equalsIgnoreCase(marker.toString()))) return true;
        if (info.sourceDir == null) return false;
        try (ZipFile apk = new ZipFile(info.sourceDir)) {
            return apk.getEntry("META-INF/xposed/module.prop") != null;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot inspect module " + info.packageName, e);
        }
    }
}
