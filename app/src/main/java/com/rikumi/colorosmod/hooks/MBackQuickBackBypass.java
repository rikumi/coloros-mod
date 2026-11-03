package com.rikumi.colorosmod.hooks;

import android.view.KeyEvent;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Lets synthetic mBack BACK events pass the ColorOS 17 QuickBack interceptor. */
public final class MBackQuickBackBypass {
    private MBackQuickBackBypass() {}

    public static void hook(ClassLoader classLoader) {
        try {
            Class<?> policy = XposedHelpers.findClass(
                    "com.android.server.wm.QuickBackInputPolicy", classLoader);
            XposedHelpers.findAndHookMethod(policy,
                    "interceptKeyEventToLauncherIfNeed", KeyEvent.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    KeyEvent event = (KeyEvent) param.args[0];
                    if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                            && (event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable ignored) { }
    }
}
