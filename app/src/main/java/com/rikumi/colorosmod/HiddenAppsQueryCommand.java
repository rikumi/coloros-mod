package com.rikumi.colorosmod;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** root 只读查询：包含 PMS 隐藏入口，并使用桌面相同的安全中心隐藏标志分组。 */
public final class HiddenAppsQueryCommand {
    public static void main(String[] args) {
        try {
            int user = Integer.parseInt(args[0]);
            Class<?> accessClass = Class.forName("com.oplus.app.OPlusAccessControlManager");
            Object access = accessClass.getMethod("getInstance").invoke(null);
            Object data = accessClass.getMethod("getAccessControlAppsInfo", String.class, int.class)
                    .invoke(access, "type_hide", user);
            if (!(data instanceof Map<?, ?>)) throw new IllegalStateException("Access control service unavailable");
            Map<?, ?> hidden = (Map<?, ?>) data;

            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            @SuppressWarnings("unchecked")
            List<ResolveInfo> activities = (List<ResolveInfo>) pm.getClass().getMethod(
                    "queryIntentActivitiesAsUser", Intent.class, int.class, int.class)
                    .invoke(pm, intent, PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, user);
            JSONArray results = new JSONArray();
            Map<String, Resources> appResources = new HashMap<>();
            for (ResolveInfo info : activities) {
                String component = new ComponentName(info.activityInfo.packageName,
                        info.activityInfo.name).flattenToString();
                JSONObject entry = new JSONObject();
                entry.put("component", component);
                Object flags = hidden.get(info.activityInfo.packageName);
                entry.put("safeHidden", flags instanceof Number && (((Number) flags).intValue() & 1) != 0);
                entry.put("name", component);
                try {
                    ActivityInfo activity = info.activityInfo;
                    Resources resources = appResources.get(activity.packageName);
                    if (resources == null) {
                        // 直接打开 APK 资源，避免系统 Context 的主题图标加载依赖已绑定的应用。
                        AssetManager assets = AssetManager.class.getConstructor().newInstance();
                        java.lang.reflect.Method addPath = AssetManager.class.getMethod("addAssetPath", String.class);
                        addPath.invoke(assets, activity.applicationInfo.publicSourceDir);
                        if (activity.applicationInfo.splitPublicSourceDirs != null) {
                            for (String path : activity.applicationInfo.splitPublicSourceDirs) addPath.invoke(assets, path);
                        }
                        resources = new Resources(assets, context.getResources().getDisplayMetrics(),
                                context.getResources().getConfiguration());
                        appResources.put(activity.packageName, resources);
                    }
                    CharSequence label = activity.nonLocalizedLabel;
                    int labelId = activity.labelRes != 0 ? activity.labelRes : activity.applicationInfo.labelRes;
                    if (label == null && labelId != 0) {
                        try { label = resources.getText(labelId); } catch (Resources.NotFoundException ignored) { }
                    }
                    if (label == null) label = activity.applicationInfo.nonLocalizedLabel;
                    entry.put("name", label != null ? label.toString() : activity.packageName);
                    int iconId = activity.icon != 0 ? activity.icon : activity.applicationInfo.icon;
                    Drawable icon = iconId != 0 ? resources.getDrawable(iconId, null) : pm.getDefaultActivityIcon();
                    Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                    try {
                        icon.setBounds(0, 0, 96, 96);
                        icon.draw(new Canvas(bitmap));
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
                        entry.put("icon", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
                    } finally { bitmap.recycle(); }
                } catch (RuntimeException ignored) {
                    // 个别入口资源失效不应阻止其它入口显示；保留组件名及隐藏状态。
                }
                results.put(entry);
            }
            System.out.println("HIDDEN_APPS_QUERY:" + results);
            System.exit(0);
        } catch (Throwable error) {
            System.err.println("HIDDEN_APPS_QUERY_ERROR:" + error);
            System.exit(1);
        }
    }
}
