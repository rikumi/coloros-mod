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

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 手机管家(com.coloros.safecenter) 作用域的全部 hook：移除风险检测、去除认证开关、隐藏联系人入口。
 */
public final class SafecenterHooks {
    // 隐藏应用免验证打开: AppHideLauncherActivity 的 onCreate 调私有方法 d0()(checkPrivacyPwd),
    // 若实例字段 I(noNeedCheckPrivacyPwd) 为 true 就走 e0() 的"已验证"分支, 跳过密码/指纹校验。
    // 这里在 d0() 前把 I 置 true。(d0 与 I 均为该版本 SafeCenter.apk 内真实混淆名, 已 dexdump 核对。)
    public static void hookSafecenter(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched com.oplus.safecenter");
        // 两个 feature 始终注入, 运行时门控见各自 hook 内部。
        try {
            hookSafecenterNoverify(lpparam);
            hookSafecenterTitleFolder(lpparam);
            hookSafecenterHideContacts(lpparam);
        } catch (Throwable t) {
            log("hookSafecenter failed: " + t);
        }
    }

    /** Feature 7 实现: 在 AppHideNewCheckActivity#d0() 前把字段 I(noNeedCheckPrivacyPwd) 置 true。 */
    public static void hookSafecenterNoverify(final XC_LoadPackage.LoadPackageParam lpparam) {
        log("hide_apps_noverify enabled=" + readBool(KEY_HIDE_APPS_NOVERIFY_ENABLED, false));
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.safecenter.privacy.view.space.AppHideNewCheckActivity",
                    lpparam.classLoader, "d0",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 运行时动态门控: 关闭则不跳过密码校验。
                                if (!readBool(KEY_HIDE_APPS_NOVERIFY_ENABLED, false)) return;
                                Object obj = param.thisObject;
                                // noNeedCheckPrivacyPwd = true -> 跳过密码/指纹, 直接进入已验证流程
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

    // 应用隐藏界面标题改为隐藏文件夹的实际名称: 系统 AppProtectListActivity#l0(boolean) 里
    // setTitle(R.string.privacy_app_hide_name); hook 它, 仅当参数为 false(表示"应用隐藏"界面,
    // 非应用锁)时用 launcher OplusFavoritesProvider 里的自定义文件夹名覆盖标题, 失败保持原标题。
    public static void hookSafecenterTitleFolder(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // hook setTitle(CharSequence) 而非 l0(): 覆盖 Activity 自身与内部 fragment 的任意 setTitle 调用,
            // 确保标题稳定为自定义文件夹名。l0() 通过 setTitle(int) -> 内部 setTitle(CharSequence), 同样被拦截。
            // setTitle 为 Activity 继承方法, findAndHookMethod(exact) 找不到, 故用 getMethod + XposedBridge.hookMethod。
            Class<?> actClass = XposedHelpers.findClass(
                    "com.oplus.safecenter.privacy.view.AppProtectListActivity", lpparam.classLoader);
            java.lang.reflect.Method m = actClass.getMethod("setTitle", CharSequence.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 运行时动态门控: 关闭则保持系统原标题("应用隐藏")。
                        if (!readBool(KEY_HIDE_APPS_TITLE_FOLDER_ENABLED, false)) return;
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

    // 安全中心特殊处理: 修改"隐藏应用"对电话本的处理。
    // 1) PMSHideAppListUtil#t 对 com.android.contacts 返回 true -> 只写隐藏应用列表并清除整包
    //    PMS 禁用, 联系人进入隐藏应用但拨号保持可用。
    // 2) OplusPmsHiddeManager#isApplicationOplusHiddenAsUser 对 com.android.contacts 返回 true,
    //    使安全中心 UI 回读时显示为已隐藏。
    public static void hookSafecenterHideContacts(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> utilClass = XposedHelpers.findClass(
                    "com.oplus.safecenter.privacy.utils.PMSHideAppListUtil", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(utilClass, "t",
                    android.content.Context.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String pkg = (String) param.args[1];
                                if ("com.android.contacts".equals(pkg)) {
                                    param.setResult(true);
                                }
                            } catch (Throwable t) {
                                log("hide contacts safecenter t error: " + t);
                            }
                        }
                    });
            log("HOOK OK safecenter PMSHideAppListUtil#t (hide contacts system)");
        } catch (Throwable t) {
            log("HOOK FAIL safecenter PMSHideAppListUtil#t: " + t);
        }
        try {
            Class<?> pmhClass = XposedHelpers.findClass(
                    "com.oplus.safecenter.privacy.sdk.OplusPmsHiddeManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(pmhClass, "isApplicationOplusHiddenAsUser",
                    android.content.Context.class, String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String pkg = (String) param.args[1];
                                if ("com.android.contacts".equals(pkg)) {
                                    param.setResult(true);
                                }
                            } catch (Throwable t) {
                                log("hide contacts safecenter isAppHidden error: " + t);
                            }
                        }
                    });
            log("HOOK OK safecenter OplusPmsHiddeManager#isApplicationOplusHiddenAsUser (hide contacts system)");
        } catch (Throwable t) {
            log("HOOK FAIL safecenter OplusPmsHiddeManager#isApplicationOplusHiddenAsUser: " + t);
        }
    }
}
