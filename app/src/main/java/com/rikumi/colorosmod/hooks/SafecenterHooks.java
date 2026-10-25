package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.TextSwitcher;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

public final class SafecenterHooks {

    // 这里在 d0() 前把 I 置 true。(d0 与 I 均为该版本 SafeCenter.apk 内真实混淆名, 已 dexdump 核对。)
    public static void hookSafecenter(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched com.oplus.safecenter");
        // 两个 feature 始终注入, 运行时门控见各自 hook 内部。
        try {
            hookSafecenterNoverify(lpparam);
            hookSafecenterTitleFolder(lpparam);
        } catch (Throwable t) {
            log("hookSafecenter failed: " + t);
        }
    }

    public static void hookSafecenterNoverify(final XC_LoadPackage.LoadPackageParam lpparam) {
        log("hide_apps_noverify enabled=" + readBoolCached(KEY_HIDE_APPS_NOVERIFY_ENABLED, false));
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.safecenter.privacy.view.space.AppHideNewCheckActivity",
                    lpparam.classLoader, "d0",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 运行时动态门控: 关闭则不跳过密码校验。
                                if (!readBoolCached(KEY_HIDE_APPS_NOVERIFY_ENABLED, false)) return;
                                Object obj = param.thisObject;

                                Class<?> c = obj.getClass();
                                while (c != null && c != Object.class) {
                                    try {
                                        java.lang.reflect.Field f = c.getDeclaredField("I");
                                        f.setAccessible(true);
                                        f.setBoolean(obj, true);
                                        break;
                                    } catch (NoSuchFieldException ignored) {
                                        c = c.getSuperclass();
                                    }
                                }
                                log("safecenter hide-apps: forced noNeedCheckPrivacyPwd=true");
                            } catch (Throwable t) {
                                log("safecenter hide-apps hook error: " + t);
                            }
                        }
                    });
            log("safecenter hide-apps hook installed");
        } catch (Throwable t) {
            log("hookSafecenterNoverify failed: " + t);
        }
    }

    public static void hookSafecenterTitleFolder(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {

            Class<?> actClass = XposedHelpers.findClass(
                    "com.oplus.safecenter.privacy.view.AppProtectListActivity", lpparam.classLoader);
            java.lang.reflect.Method m = actClass.getMethod("setTitle", CharSequence.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 运行时动态门控: 关闭则保持系统原标题("应用隐藏")。
                        if (!readBoolCached(KEY_HIDE_APPS_TITLE_FOLDER_ENABLED, false)) return;
                        Object obj = param.thisObject;
                        if (!(obj instanceof android.app.Activity)) return;
                        android.app.Activity act = (android.app.Activity) obj;
                        // 仅"应用隐藏"界面(type != 1)替换; 应用锁界面(type == 1)保持原"应用锁"标题
                        int type = 0;
                        try {
                            java.lang.reflect.Field f = obj.getClass().getDeclaredField("P");
                            f.setAccessible(true);
                            type = f.getInt(obj);
                        } catch (Throwable ignored) {}
                        if (type == 1) return;
                        String name = readAppHideFolderName(act);
                        if (name == null || name.isEmpty()) return;
                        param.args[0] = name;
                        log("safecenter title -> folder name: " + name);
                    } catch (Throwable t) {
                        log("safecenter title hook error: " + t);
                    }
                }
            });
            log("HOOK OK com.oplus.safecenter.privacy.view.AppProtectListActivity#setTitle (title folder)");
        } catch (Throwable t) {
            log("HOOK FAIL AppProtectListActivity#setTitle :: " + Log.getStackTraceString(t));
        }
    }

}
