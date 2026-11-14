package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;

/** Controls splash creation before WindowManager starts waiting for its window. */
public final class SplashScreenHooks {
    public static final String MODE = "app_splash_screen_mode";
    public static final int DEFAULT = 0;
    public static final int HIDE_SPLASH = 1;
    public static final int HIDE_ICON = 2;

    private SplashScreenHooks() {}

    public static void hookSystemServer(ClassLoader loader, XposedInit module) {
        try {
            Class<?> activity = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", loader);
            XposedHelpers.findAndHookDeclaredMethod(activity, "getStartingWindowType",
                    boolean.class, boolean.class, boolean.class, boolean.class,
                    boolean.class, boolean.class, "android.window.TaskSnapshot", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            // WindowManager uses 2 for splash, 1 for snapshot. Shell
                            // uses different type values. Decide before StartingData
                            // is allocated so no splash/animation handoff is pending.
                            if (readIntCached(MODE, DEFAULT) == HIDE_SPLASH
                                    && Integer.valueOf(2).equals(p.getResult())) {
                                p.setResult(0);
                            }
                        }
                    });
            for (Method method : activity.getDeclaredMethods()) {
                if (method.getName().equals("addStartingWindow")) module.deoptimize(method);
            }
            log("HOOK OK SplashScreen WindowManager type selection");
        } catch (Throwable error) { log("HOOK FAIL SplashScreen WindowManager: " + error); }
    }

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            XposedHelpers.findAndHookDeclaredMethod(
                    "com.android.wm.shell.startingsurface.SplashscreenContentDrawer$SplashViewBuilder",
                    pkg.classLoader, "build", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readIntCached(MODE, DEFAULT) != HIDE_ICON) return;
                            // Skip icon creation as well as rendering. Otherwise an
                            // animated drawable can enqueue initialization for an
                            // icon surface that will never be attached or copied.
                            Object init = XposedHelpers.getObjectField(p.thisObject, "mUiThreadInitTask");
                            p.setResult(XposedHelpers.callMethod(p.thisObject,
                                    "fillViewWithIcon", 0, null, init));
                        }
                    });
            Class<?> drawer = XposedHelpers.findClass(
                    "com.android.wm.shell.startingsurface.SplashscreenContentDrawer", pkg.classLoader);
            for (Method method : drawer.getDeclaredMethods()) {
                if (method.getName().equals("makeSplashScreenContentView")
                        || method.getName().equals("makeSimpleSplashScreenContentView")) {
                    // Deoptimize the real callers too, so a precompiled builder
                    // cannot bypass the hook through an inlined build() body.
                    com.rikumi.colorosmod.xposed.XposedBridge.hookMethod(method, new XC_MethodHook() {});
                }
            }
            log("HOOK OK SplashScreen icon-free builder");
        } catch (Throwable error) { log("HOOK FAIL SplashScreen icon builder: " + error); }
    }
}
