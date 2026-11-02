package com.rikumi.colorosmod.hooks;

import android.os.Build;
import com.rikumi.colorosmod.PlayStoreUpdatePolicy;
import com.rikumi.colorosmod.xposed.*;
import java.util.Set;
import java.util.Collection;
import static com.rikumi.colorosmod.XposedInit.log;

/** Install before PMS boot scanning; ordinary asynchronous preferences are not ready then. */
public final class PlayStoreUpdateHooks {
    private static boolean installed;

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (installed || Build.VERSION.SDK_INT < 37) return;
        try {
            Class<?> properties = XposedHelpers.findClass("android.os.SystemProperties", lp.classLoader);
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
        } catch (Throwable error) { log("Play Store recovery hooks unavailable: " + error); }
    }

    private PlayStoreUpdateHooks() {}
}
