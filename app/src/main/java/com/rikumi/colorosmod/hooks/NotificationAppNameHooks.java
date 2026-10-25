package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.widget.TextView;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

public final class NotificationAppNameHooks {
    public static final String KEY = "notification_real_app_name_enabled";

    static boolean enabled() { return readBoolCached(KEY, false); }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        SystemNotificationGroupHooks.hook(lp);
        SystemNotificationGroupIconHooks.hook(lp);
        try {
            XposedHelpers.findAndHookMethod(Notification.class, "loadHeaderAppName", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled() || !(p.args[0] instanceof Context context)) return;
                    try {
                        ApplicationInfo info = (ApplicationInfo) XposedHelpers.callMethod(p.thisObject, "getApplicationInfo", context);
                        String name = realName(context.getPackageManager(), info);
                        if (name != null) p.setResult(name);
                    } catch (Throwable error) { log("system notification template label failed: " + error); }
                }
            });
            XposedHelpers.findAndHookMethod("com.oplus.systemui.util.NotificationUtil", lp.classLoader,
                    "loadHeaderAppName", StatusBarNotification.class, PackageManager.class, ApplicationInfo.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            try {
                                StatusBarNotification sbn = (StatusBarNotification) p.args[0];
                                PackageManager pm = (PackageManager) p.args[1];
                                String name = realName(pm, pm.getApplicationInfo(sbn.getPackageName(), 0));
                                if (name != null) p.setResult(name);
                            } catch (Throwable error) { log("system notification vendor label failed: " + error); }
                        }
                    });
            // Also cover previously built RemoteViews, without modifying their contents.
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper",
                    lp.classLoader, "resolveHeaderViews", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            try {
                                Object row = XposedHelpers.getObjectField(p.thisObject, "mRow");
                                Object entry = row == null ? null : XposedHelpers.callMethod(row, "getEntry");
                                Object text = XposedHelpers.getObjectField(p.thisObject, "mAppNameText");
                                if (entry == null || !(text instanceof TextView label)) return;
                                StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.callMethod(entry, "getSbn");
                                PackageManager pm = label.getContext().getPackageManager();
                                String name = realName(pm, pm.getApplicationInfo(sbn.getPackageName(), 0));
                                if (name != null) label.setText(name);
                            } catch (Throwable error) { log("system notification bound label failed: " + error); }
                        }
                    });
        } catch (Throwable error) { log("system notification application name hook failed: " + error); }
    }

    private static String realName(PackageManager pm, ApplicationInfo info) {
        if (info == null || (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) return null;
        CharSequence name = pm.getApplicationLabel(info);
        return TextUtils.isEmpty(name) ? null : name.toString();
    }
}
