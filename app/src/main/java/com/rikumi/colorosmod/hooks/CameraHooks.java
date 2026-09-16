package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.CAMERA_FIND_LIGHT_STYLE_DEFAULT;
import static com.rikumi.colorosmod.XposedInit.CAMERA_FIND_LIGHT_STYLE_DISABLED;
import static com.rikumi.colorosmod.XposedInit.CAMERA_FIND_LIGHT_STYLE_ENABLED;
import static com.rikumi.colorosmod.XposedInit.KEY_CAMERA_FIND_LIGHT_STYLE;
import static com.rikumi.colorosmod.XposedInit.KEY_CAMERA_HASSELBLAD_ORANGE_UI;
import static com.rikumi.colorosmod.XposedInit.readInt;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** 系统相机 Find 与哈苏橙色 UI 的三态开关。 */
public final class CameraHooks {
    private CameraHooks() {}

    public static void hookCamera(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> deviceUtil = XposedHelpers.findClass("r7.z0", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(deviceUtil, "l", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyTriState(param, KEY_CAMERA_FIND_LIGHT_STYLE);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            Class<?> vendorTagConfig = XposedHelpers.findClass("x7.e", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(vendorTagConfig, "e", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    applyTriState(param, KEY_CAMERA_HASSELBLAD_ORANGE_UI);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void applyTriState(XC_MethodHook.MethodHookParam param, String key) {
        int style = readInt(key, CAMERA_FIND_LIGHT_STYLE_DEFAULT);
        if (style == CAMERA_FIND_LIGHT_STYLE_ENABLED) {
            param.setResult(true);
        } else if (style == CAMERA_FIND_LIGHT_STYLE_DISABLED) {
            param.setResult(false);
        }
    }
}
