package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Hooks for com.oplus.wallpapers (desktop/theme customization screens). */
public final class WallpapersHooks {
    // Fixed desktop customization background: very dark blue-black (#0A0C10).
    private static final int NEUTRAL_DARK = Color.rgb(10, 12, 16); // #0A0C10

    private WallpapersHooks() {}

    public static void hookWallpapers(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookThemeEditActivity(lpparam);
    }

    /**
     * 桌面自定义页: uiautomator 显示当前页面为 ThemeEditActivity, 背景由
     * background_wallpaper 与 background_wallpaper_mask 两个全屏 ImageView 叠加。
     * 开启后把它们稳定压成 #0A0C10, 避免原背景随壁纸/遮罩变成偏蓝灰。
     */
    private static void hookThemeEditActivity(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.wallpapers.themes.edit.ThemeEditActivity",
                    lpparam.classLoader,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyThemeEditBackground((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.oplus.wallpapers.themes.edit.ThemeEditActivity",
                    lpparam.classLoader,
                    "onWindowFocusChanged",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(param.args[0])) {
                                applyThemeEditBackground((Activity) param.thisObject);
                            }
                        }
                    });
            log("HOOK OK wallpapers ThemeEditActivity background");
        } catch (Throwable t) {
            log("HOOK FAIL wallpapers ThemeEditActivity background: " + t);
        }
    }

    private static void applyThemeEditBackground(Activity activity) {
        if (activity == null || !readBool(KEY_WEAKEN_DESKTOP_CUSTOMIZATION_BG_ENABLED, false)) {
            return;
        }
        try {
            View decor = activity.getWindow().getDecorView();
            applyNeutralDarkBackground(activity, decor);
            Object installed = XposedHelpers.getAdditionalInstanceField(decor,
                    "colorosmod_wallpaper_bg_predraw");
            if (installed == null) {
                ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        applyNeutralDarkBackground(activity, decor);
                        return true;
                    }
                };
                decor.getViewTreeObserver().addOnPreDrawListener(listener);
                XposedHelpers.setAdditionalInstanceField(decor,
                        "colorosmod_wallpaper_bg_predraw", listener);
            }
        } catch (Throwable t) {
            log("wallpapers theme edit bg apply failed: " + t);
        }
    }

    private static void applyNeutralDarkBackground(Activity activity, View decor) {
        if (activity == null || decor == null) return;
        if (!readBool(KEY_WEAKEN_DESKTOP_CUSTOMIZATION_BG_ENABLED, false)) return;
        try {
            decor.setBackgroundColor(NEUTRAL_DARK);
            int wallpaperId = activity.getResources().getIdentifier(
                    "background_wallpaper", "id", activity.getPackageName());
            int maskId = activity.getResources().getIdentifier(
                    "background_wallpaper_mask", "id", activity.getPackageName());
            View wallpaper = wallpaperId == 0 ? null : decor.findViewById(wallpaperId);
            View mask = maskId == 0 ? null : decor.findViewById(maskId);
            forceDarkView(wallpaper, true);
            forceDarkView(mask, false);
        } catch (Throwable t) {
            log("wallpapers theme edit bg force failed: " + t);
        }
    }

    private static void forceDarkView(View view, boolean opaque) {
        if (view == null) return;
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            image.setImageDrawable(new ColorDrawable(opaque ? NEUTRAL_DARK : Color.TRANSPARENT));
            image.setColorFilter(null);
            image.setAlpha(1f);
            image.setVisibility(View.VISIBLE);
            image.setBackgroundColor(opaque ? NEUTRAL_DARK : Color.TRANSPARENT);
        } else {
            view.setBackgroundColor(opaque ? NEUTRAL_DARK : Color.TRANSPARENT);
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                    || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                view.setLayoutParams(lp);
            }
        }
    }
}
