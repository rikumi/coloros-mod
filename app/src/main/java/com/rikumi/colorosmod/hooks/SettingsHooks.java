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
    // 在设置的应用管理页隐藏已停用应用。
    // 应用列表页(设置 -> 应用管理 -> 应用列表)是 com.android.settings.applications
    // .manageapplications.ManageApplications, 默认 filterType=4, 取 AppFilterRegistry 第 4 项
    // = OplusApplicationsState.FILTER_EVERYTHING_OPLUS(Oplus 自己的"所有应用")。
    // 它只排除了 sDisablePackage / 画报 / 多开 / 配置隐藏的包, 没有排除停用的包;
    // 而 AOSP 的"所有应用"(第 1 项)是 CompoundFilter(FILTER_WITHOUT_DISABLED_UNTIL_USED,
    // FILTER_ALL_ENABLED), 是会排除的 —— 这就是停用后仍出现在列表里的原因。
    // 注意用户级停用(pm disable-user)的包 ApplicationInfo.enabled 仍为 true, 只有
    // enabledSetting == COMPONENT_ENABLED_STATE_DISABLED_USER(4), 所以必须看 enabledSetting。
    // 这里只 hook 这一个 filter 实例, 因此"已停用应用"独立入口(DisabledAppsUtils, 不用这个
    // filter)与列表里的"已停用"筛选项(FILTER_DISABLED, 第 7 项)都不受影响。
    // 判定口径与 DisabledAppsUtils.getDisabledAppList 保持一致: !enabled || enabledSetting==4。
    public static void hookHideDisabledApps(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> oplusStateClass = XposedHelpers.findClass(
                    "com.oplus.settings.feature.appmanager.OplusApplicationsState",
                    lpparam.classLoader);
            Object everythingFilter = XposedHelpers.getStaticObjectField(
                    oplusStateClass, "FILTER_EVERYTHING_OPLUS");
            if (everythingFilter == null) {
                log("HOOK FAIL hide-disabled-apps: FILTER_EVERYTHING_OPLUS is null");
                return;
            }
            Class<?> appEntryClass = XposedHelpers.findClass(
                    "com.oplus.settingslib.applications.ApplicationsState$AppEntry",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(everythingFilter.getClass(), "filterApp", appEntryClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_HIDE_DISABLED_APPS_ENABLED, false)) return;
                                // 已被该 filter 排除的, 无需再判。
                                if (Boolean.FALSE.equals(param.getResult())) return;
                                Object entry = param.args[0];
                                if (entry == null) return;
                                android.content.pm.ApplicationInfo info =
                                        (android.content.pm.ApplicationInfo) XposedHelpers
                                                .getObjectField(entry, "info");
                                if (info == null) return;
                                // ApplicationInfo.enabledSetting 是隐藏字段, SDK 编译不可见, 走反射。
                                int enabledSetting = XposedHelpers.getIntField(info, "enabledSetting");
                                boolean disabled = !info.enabled
                                        || enabledSetting == android.content.pm.PackageManager
                                                .COMPONENT_ENABLED_STATE_DISABLED_USER;
                                if (disabled) param.setResult(Boolean.FALSE);
                            } catch (Throwable t) {
                                log("hide-disabled-apps filter error: " + t);
                            }
                        }
                    });
            log("HOOK OK settings hide-disabled-apps (FILTER_EVERYTHING_OPLUS)");
        } catch (Throwable t) {
            log("HOOK FAIL settings hide-disabled-apps: " + t);
        }
    }

    public static void hookSettings(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched com.android.settings");
        hookSettingsHomeIconStyle(lpparam);
        hookDisableAppsNoVerify(lpparam);
        hookHideDisabledApps(lpparam);
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

    // 停用应用免密码。设置对"受生物识别保护"的包(config_biometric_protected_package_names)
    // 做停用前会强制验证, 两条架构的收口点不同:
    //   SPA(新): PackageInfoPresenter#disable() -> requireAuthAndExecute -> Utils
    //            .isProtectedPackage() 为真才弹 AppInfoDashboardFragment.showLockScreen();
    //   老架构: AppButtonsPreferenceController#handleDialogClick(0) -> AppButtonsPreference
    //            ControllerAdaptor#runKeyguardConfirmation() 返回真则发起验证。
    // 两个判定函数都不是停用专用(前者还服务 forceStop / startUninstallActivity / stopPackage,
    // 后者也可能被其它流程复用), 因此不能直接改它们的返回值, 否则会连带免掉卸载等操作的验证。
    // 这里用 ThreadLocal 标记: 只在"本次调用确实来自停用入口"时把标记置上, 判定函数只在标记
    // 为真时放行; 标记在入口方法返回前清除, 不会泄漏到后续操作。
    private static final ThreadLocal<Boolean> DISABLE_NOVERIFY = new ThreadLocal<>();

    public static void hookDisableAppsNoVerify(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookDisableAppsNoVerifySpa(lpparam);
        hookDisableAppsNoVerifyLegacy(lpparam);
    }

    // SPA 架构(Android 15+ 应用详情页): 标记 disable(), 放行 isProtectedPackage()。
    private static void hookDisableAppsNoVerifySpa(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.spa.app.appinfo.PackageInfoPresenter",
                    lpparam.classLoader, "disable",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            DISABLE_NOVERIFY.set(Boolean.TRUE);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            DISABLE_NOVERIFY.remove();
                        }
                    });
            // 判定函数: 只在标记为真时返回 false, 让 requireAuthAndExecute 走"直接执行"分支。
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.Utils", lpparam.classLoader,
                    "isProtectedPackage", android.content.Context.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Boolean.TRUE.equals(DISABLE_NOVERIFY.get())) return;
                                if (!readBool(KEY_DISABLE_APPS_NOVERIFY_ENABLED, false)) return;
                                param.setResult(Boolean.FALSE);
                            } catch (Throwable t) {
                                log("settings disable-noverify spa error: " + t);
                            }
                        }
                    });
            log("HOOK OK settings disable-noverify (spa)");
        } catch (Throwable t) {
            log("HOOK FAIL settings disable-noverify (spa): " + t);
        }
    }

    // 老架构: 停用走 handleDialogClick(0) -> adaptor.runKeyguardConfirmation(activity,
    // fragment, 102); 返回 false 即视为无需验证, 继续执行 refreshAndDisableApp()。
    private static void hookDisableAppsNoVerifyLegacy(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.applications.appinfo.AppButtonsPreferenceController",
                    lpparam.classLoader, "handleDialogClick", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 0 = 停用对话框(确认按钮); 其余分支是卸载等, 不打标记。
                            if (Integer.valueOf(0).equals(param.args[0])) {
                                DISABLE_NOVERIFY.set(Boolean.TRUE);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            DISABLE_NOVERIFY.remove();
                        }
                    });
            Class<?> fragmentCompat = XposedHelpers.findClass(
                    "androidx.preference.PreferenceFragmentCompat", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.oplus.settings.adaptor.AppButtonsPreferenceControllerAdaptor",
                    lpparam.classLoader, "runKeyguardConfirmation",
                    android.app.Activity.class, fragmentCompat, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Boolean.TRUE.equals(DISABLE_NOVERIFY.get())) return;
                                if (!readBool(KEY_DISABLE_APPS_NOVERIFY_ENABLED, false)) return;
                                param.setResult(Boolean.FALSE);
                            } catch (Throwable t) {
                                log("settings disable-noverify legacy error: " + t);
                            }
                        }
                    });
            log("HOOK OK settings disable-noverify (legacy)");
        } catch (Throwable t) {
            log("HOOK FAIL settings disable-noverify (legacy): " + t);
        }
    }
}
