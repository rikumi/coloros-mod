package com.rikumi.colorosmod.hooks;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.*;

public final class LegacyChargingAnimationHooks {
    private static final WeakHashMap<Object, Boolean> controllers = new WeakHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean installed, lastEnabled;

    static boolean hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT > 35) return false;
        try {
            Class<?> implementation = XposedHelpers.findClass("com.oplus.charge.viewmodel.OplusChargeAnimImpl", lp.classLoader);
            Class<?> wrapper = XposedHelpers.findClass("com.oplus.systemui.keyguard.charginganim.ChargingAnimationImpl", lp.classLoader);
            implementation.getDeclaredMethod("updateChargeAnimState", int.class, String.class);
            wrapper.getDeclaredMethod("isShouldResume");
            XposedHelpers.findAndHookMethod(implementation, "updateChargeAnimState", int.class, String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    synchronized (controllers) { controllers.put(p.thisObject, true); }
                    int state = (Integer) p.args[0];
                    if (enabled() && (state == 1 || state == 2)) {
                        // Run native cancellation so callbacks, window removal and delayed VFX are cleaned up.
                        p.args[0] = 4;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(wrapper, "isShouldResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (enabled()) p.setResult(false);
                }
            });
            XposedBridge.hookAllConstructors(implementation, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    synchronized (controllers) { controllers.put(p.thisObject, true); }
                    if (enabled()) main.post(() -> cancel(p.thisObject));
                }
            });
            installed = true;
            log("HOOK OK legacy charge create/resume/cancel");
            return true;
        } catch (Throwable error) { log("legacy charge animation hook failed: " + error); return false; }
    }

    private static boolean enabled() { return readBoolCached(KEY_KEYGUARD_NO_CHARGE_ANIM_ENABLED, false); }

    public static void refresh() {
        if (!installed) return;
        boolean enabled = enabled();
        if (enabled == lastEnabled) return;
        lastEnabled = enabled;
        if (enabled) main.post(() -> {
            ArrayList<Object> snapshot;
            synchronized (controllers) { snapshot = new ArrayList<>(controllers.keySet()); }
            for (Object controller : snapshot) cancel(controller);
        });
    }

    private static void cancel(Object controller) {
        if (!enabled()) return;
        try { XposedHelpers.callMethod(controller, "updateChargeAnimState", 4, "ColorOS Mod charging animation disabled"); }
        catch (Throwable error) { log("legacy charge animation cancellation failed: " + error); }
    }
}
