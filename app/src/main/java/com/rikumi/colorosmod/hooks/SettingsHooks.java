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

public final class SettingsHooks {
    // 仅主线程访问页面引用和原生样式；后台快照发布只投递刷新。
    private static final android.os.Handler HOME_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static java.lang.ref.WeakReference<Object> sHomeScreen =
            new java.lang.ref.WeakReference<>(null);
    private static final java.util.WeakHashMap<Object, Integer> sOriginalIconStyles =
            new java.util.WeakHashMap<>();
    private static final Runnable REFRESH_HOME = new Runnable() {
        @Override public void run() {
            Object screen = sHomeScreen.get();
            if (screen == null) return;
            int style = readIntCached(KEY_SETTINGS_HOME_ICON_STYLE,
                    SETTINGS_HOME_ICON_STYLE_DEFAULT);
            int couiStyle = style == SETTINGS_HOME_ICON_STYLE_DEFAULT ? -1
                    : style == SETTINGS_HOME_ICON_STYLE_CIRCLE ? 0 : 1;
            applySettingsHomeIconStyle(screen, couiStyle, screen.getClass().getClassLoader());
        }
    };

    public static void onSettingsSnapshotPublished() {
        SettingsSpacingHooks.onSettingsSnapshotPublished();
        AboutDeviceCardHooks.onSettingsSnapshotPublished();
        AboutDeviceMarketingHooks.onSettingsSnapshotPublished();
        HOME_HANDLER.removeCallbacks(REFRESH_HOME);
        HOME_HANDLER.post(REFRESH_HOME);
    }

    public static void cleanupForHotReload() {
        SettingsSpacingHooks.cleanupForHotReload();
        AboutDeviceCardHooks.cleanupForHotReload();
        AboutDeviceMarketingHooks.cleanupForHotReload();
        HOME_HANDLER.removeCallbacks(REFRESH_HOME);
        // 旧 generation 卸载时恢复页面现有图标，避免遗留模块样式。
        Object screen = sHomeScreen.get();
        if (screen != null) {
            applySettingsHomeIconStyle(screen, -1, screen.getClass().getClassLoader());
        }
        sHomeScreen.clear();
        sOriginalIconStyles.clear();
    }

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
                            sHomeScreen = new java.lang.ref.WeakReference<>(param.args[0]);
                            // 首屏只读缓存；快照尚未就绪时保留原生图标，由 worker 发布后补刷新。
                            REFRESH_HOME.run();
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
                    Integer original = sOriginalIconStyles.get(preferenceGroup);
                    if (couiStyle >= 0 && original == null) {
                        sOriginalIconStyles.put(preferenceGroup, current);
                    }
                    int target = couiStyle >= 0 ? couiStyle
                            : original != null ? original : current;
                    if (current != target) {
                        XposedHelpers.callMethod(preferenceGroup, "setIconStyle", target);
                    }
                    if (couiStyle < 0) sOriginalIconStyles.remove(preferenceGroup);
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
        SettingsSpacingHooks.hook(lpparam);
        AboutDeviceCardHooks.hook(lpparam);
        AboutDeviceMarketingHooks.hook(lpparam);
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
