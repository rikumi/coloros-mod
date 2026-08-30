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

/**
 * 设置(com.android.settings) 作用域的全部 hook：首页图标风格(紧凑/列表)。
 */
public final class SettingsHooks {
// 设置首页图标样式: 复用 Settings/Oplus 已有的 COUIRoundImageView 绘制路径。
// Oplus 首页来自 top_level_settings_oplus.xml, 不经过 DashboardFeatureProviderImpl;
// COUIPreference 的 couiIconStyle=0 是圆形, =1 是不规则圆角图标。
    public static void hookSettingsHomeIconStyle(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> oplusTopLevelClass = XposedHelpers.findClass(
                    "com.oplus.settings.feature.homepage.OplusTopLevelSettings",
                    lpparam.classLoader);
            final Class<?> preferenceScreenClass = XposedHelpers.findClass(
                    "androidx.preference.PreferenceScreen", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.dashboard.DashboardFragment",
                    lpparam.classLoader,
                    "displayResourceTilesToScreen",
                    preferenceScreenClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!oplusTopLevelClass.isInstance(param.thisObject)) return;
                            int style = readInt(KEY_SETTINGS_HOME_ICON_STYLE,
                                    SETTINGS_HOME_ICON_STYLE_DEFAULT);
                            if (style == SETTINGS_HOME_ICON_STYLE_DEFAULT) return;
                            int couiStyle = style == SETTINGS_HOME_ICON_STYLE_CIRCLE ? 0 : 1;
                            applySettingsHomeIconStyle(param.args[0], couiStyle,
                                    lpparam.classLoader);
                        }
                    });
            log("HOOK OK settings native COUI homepage icon style");
        } catch (Throwable t) {
            log("HOOK FAIL settings native COUI homepage icon style: " + t);
        }
    }

    static void applySettingsHomeIconStyle(Object preferenceGroup,
            int couiStyle, ClassLoader classLoader) {
        if (preferenceGroup == null) return;
        try {
            Class<?> couiPreferenceClass = XposedHelpers.findClass(
                    "com.coui.appcompat.preference.COUIPreference", classLoader);
            if (couiPreferenceClass.isInstance(preferenceGroup)) {
                Object icon = XposedHelpers.callMethod(preferenceGroup, "getIcon");
                if (icon != null) {
                    int current = (Integer) XposedHelpers.callMethod(
                            preferenceGroup, "getIconStyle");
                    if (current != couiStyle) {
                        XposedHelpers.callMethod(preferenceGroup, "setIconStyle", couiStyle);
                    }
                }
            }
            Class<?> preferenceGroupClass = XposedHelpers.findClass(
                    "androidx.preference.PreferenceGroup", classLoader);
            if (!preferenceGroupClass.isInstance(preferenceGroup)) return;
            int count = (Integer) XposedHelpers.callMethod(
                    preferenceGroup, "getPreferenceCount");
            for (int i = 0; i < count; i++) {
                Object child = XposedHelpers.callMethod(
                        preferenceGroup, "getPreference", i);
                applySettingsHomeIconStyle(child, couiStyle, classLoader);
            }
        } catch (Throwable t) {
            log("settings homepage COUI icon style error: " + t);
        }
    }

// 隐藏应用免验证的更直接入口: 安全中心会让本进程启动 ConfirmNumberPrivacy(或指纹变体
// ConfirmBiometricInfo) 作为校验闸门, 成功时它本就会 setResult(-1) 交回安全中心。
// 因此 onCreate 后立刻 setResult(-1)+finish() 即可, 不依赖 com.oplus.safecenter 作用域。
    public static void hookSettings(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched com.android.settings");
        hookSettingsHomeIconStyle(lpparam);
        // 始终注入, 运行时按 KEY_HIDE_APPS_NOVERIFY_ENABLED 门控(见 afterHook)。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.settings.privacy.ConfirmAbstractPrivacy",
                    lpparam.classLoader, "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 运行时动态门控: 关闭则不绕过校验。
                                if (!readBool(KEY_HIDE_APPS_NOVERIFY_ENABLED, false)) return;
                                Object obj = param.thisObject;
                                String name = obj.getClass().getName();
                                // 仅对隐藏应用相关的校验界面生效, 避免误伤其它隐私确认流程
                                if (!"com.oplus.settings.privacy.ConfirmNumberPrivacy".equals(name)
                                        && !"com.oplus.settings.privacy.ConfirmBiometricInfo".equals(name)) {
                                    return;
                                }
                                if (obj instanceof android.app.Activity) {
                                    android.app.Activity act = (android.app.Activity) obj;
                                    act.setResult(-1);
                                    act.finish();
                                    log("settings confirm-privacy bypassed: " + name);
                                }
                            } catch (Throwable t) {
                                log("settings hide-apps hook error: " + t);
                            }
                        }
                    });
            log("settings hide-apps hook installed");
        } catch (Throwable t) {
            log("hookSettings failed: " + t);
        }
    }
}
