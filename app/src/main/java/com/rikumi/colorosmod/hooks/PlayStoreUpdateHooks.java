package com.rikumi.colorosmod.hooks;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.rikumi.colorosmod.PlayStoreUpdatePolicy;
import com.rikumi.colorosmod.xposed.*;
import java.util.Set;
import java.util.Collection;
import java.util.Map;
import java.lang.reflect.Field;
import static com.rikumi.colorosmod.XposedInit.log;

/** Install before PMS boot scanning; ordinary asynchronous preferences are not ready then. */
public final class PlayStoreUpdateHooks {
    private static boolean installed;

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT < 37) return;
        try {
            Class<?> properties = XposedHelpers.findClass("android.os.SystemProperties", lp.classLoader);
            if (installed) {
                repairLiveSettings(lp.classLoader, properties);
                return;
            }
            XposedHelpers.findAndHookMethod("com.android.server.pm.PackageManagerServiceExtImpl", lp.classLoader,
                    "afterReadUserSettingsInConstructor", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !"1".equals(XposedHelpers.callStaticMethod(properties,
                            "get", PlayStoreUpdatePolicy.PROPERTY, "0"))) return;
                    try {
                        Object lock = XposedHelpers.getObjectField(p.thisObject, "mSpecialSafeModeLock");
                        synchronized (lock) {
                            @SuppressWarnings("unchecked")
                            Set<String> nativeSet = (Set<String>) XposedHelpers.getObjectField(
                                    p.thisObject, "mSpecialSafeModeBootPackageSet");
                            Set<String> filtered = PlayStoreUpdatePolicy.filter(nativeSet, true);
                            if (filtered != nativeSet) {
                                XposedHelpers.setObjectField(p.thisObject, "mSpecialSafeModeBootPackageSet", filtered);
                                log("Play Store update preserved: excluded com.android.vending from pms_restore boot snapshot");
                            }
                        }
                    } catch (Throwable error) { log("Play Store recovery exclusion failed: " + error); }
                }
            });
            // The OEM writer can keep serializing an old recovery shadow even after
            // a new Play Store update is installed. That stale path is no longer scanned
            // on the next restart; the real update directory is then removed as orphaned.
            for (String method : new String[]{"getPackagesForSpecialSafeModeWrite",
                    "getDisabledSystemPackagesForSpecialSafeModeWrite"}) {
                XposedHelpers.findAndHookMethod("com.android.server.pm.PackageManagerServiceExtImpl", lp.classLoader,
                        method, Collection.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable() || !"1".equals(XposedHelpers.callStaticMethod(properties,
                                "get", PlayStoreUpdatePolicy.PROPERTY, "0"))) return;
                        try {
                            @SuppressWarnings("unchecked")
                            Collection<Object> live = (Collection<Object>) p.args[0];
                            @SuppressWarnings("unchecked")
                            Collection<Object> serialized = (Collection<Object>) p.getResult();
                            if (live == null || serialized == null) return;
                            Collection<Object> corrected = PlayStoreUpdatePolicy.preferLivePlayStore(live, serialized,
                                    item -> (String) XposedHelpers.callMethod(item, "getPackageName"));
                            if (corrected != serialized) {
                                p.setResult(corrected);
                                log("Play Store settings: replaced recovery shadow with live package in " + method);
                            }
                        } catch (Throwable error) { log("Play Store settings preservation failed: " + error); }
                    }
                });
            }
            installed = true;
            repairLiveSettings(lp.classLoader, properties);
        } catch (Throwable error) { log("Play Store recovery hooks unavailable: " + error); }
    }

    private static void repairLiveSettings(ClassLoader loader, Class<?> properties) {
        Looper main = Looper.getMainLooper();
        if (main == null || !"1".equals(XposedHelpers.callStaticMethod(properties,
                "get", PlayStoreUpdatePolicy.PROPERTY, "0"))) return;
        new Handler(main).post(() -> {
            try {
                Class<?> localServices = XposedHelpers.findClass("com.android.server.LocalServices", loader);
                Class<?> managerType = XposedHelpers.findClass("android.content.pm.PackageManagerInternal", loader);
                Object manager = XposedHelpers.callStaticMethod(localServices, "getService", managerType);
                if (manager == null) return; // PMS has not been published during early boot.
                Object pms = null;
                for (Class<?> type = manager.getClass(); type != null && pms == null; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (field.getType().getName().equals("com.android.server.pm.PackageManagerService")) {
                            field.setAccessible(true);
                            pms = field.get(manager);
                            break;
                        }
                    }
                }
                if (pms == null) throw new IllegalStateException("Package manager owner is unavailable");
                synchronized (XposedHelpers.getObjectField(pms, "mLock")) {
                    Object extension = XposedHelpers.getObjectField(pms, "mPackageManagerServiceExt");
                    synchronized (XposedHelpers.getObjectField(extension, "mSpecialSafeModeLock")) {
                        for (String name : new String[]{"mSpecialSafeModeDataPackageSettings",
                                "mSpecialSafeModeSystemPackageSettings"}) {
                            Map<?, ?> shadows = (Map<?, ?>) XposedHelpers.getObjectField(extension, name);
                            shadows.remove(PlayStoreUpdatePolicy.PACKAGE);
                        }
                        @SuppressWarnings("unchecked")
                        Set<String> packages = (Set<String>) XposedHelpers.getObjectField(extension,
                                "mSpecialSafeModeBootPackageSet");
                        XposedHelpers.setObjectField(extension, "mSpecialSafeModeBootPackageSet",
                                PlayStoreUpdatePolicy.filter(packages, true));
                    }
                }
                // Native API queues its own write. Never edit packages.xml from the helper process.
                XposedHelpers.callMethod(manager, "writeSettings", true);
                log("Play Store recovery shadows cleared; requested native settings write");
            } catch (Throwable error) { log("Play Store live settings repair failed: " + error); }
        });
    }

    private PlayStoreUpdateHooks() {}
}
