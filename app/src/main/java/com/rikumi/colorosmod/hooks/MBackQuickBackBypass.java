package com.rikumi.colorosmod.hooks;

import android.view.KeyEvent;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Keeps mBack keys on the normal key-policy path instead of Launcher animation callbacks. */
public final class MBackQuickBackBypass {
    private MBackQuickBackBypass() {}

    public static void hook(ClassLoader classLoader) {
        if (android.os.Build.VERSION.SDK_INT < 37) return;
        hookKey(classLoader, "interceptKeyEventToLauncherIfNeed", KeyEvent.KEYCODE_BACK);
    }

    private static void hookKey(ClassLoader classLoader, String method, int code) {
        try {
            Class<?> policy = XposedHelpers.findClass(
                    "com.android.server.wm.QuickBackInputPolicy", classLoader);
            XposedHelpers.findAndHookMethod(policy,
                    method, KeyEvent.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    KeyEvent event = (KeyEvent) param.args[0];
                    if (event != null && event.getKeyCode() == code
                            && (event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable error) {
            com.rikumi.colorosmod.XposedInit.log("mBack policy hook unavailable: " + method + ": " + error);
        }
    }
}
