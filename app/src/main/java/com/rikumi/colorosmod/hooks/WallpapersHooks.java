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

    private static final class ViewBaseline {
        android.graphics.drawable.Drawable background;
        android.graphics.drawable.Drawable image;
        android.graphics.ColorFilter colorFilter;
        float alpha;
        int visibility;
        int width;
        int height;

        ViewBaseline(View view) {
            background = copyDrawable(view.getBackground(), view);
            if (view instanceof ImageView) {
                ImageView imageView = (ImageView) view;
                // setImageDrawable() replaces rather than mutates the original drawable, so
                // retain the exact host instance and its current level/state for restoration.
                image = imageView.getDrawable();
                colorFilter = imageView.getColorFilter();
            } else {
                image = null;
                colorFilter = null;
            }
            alpha = view.getAlpha();
            visibility = view.getVisibility();
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            width = lp == null ? Integer.MIN_VALUE : lp.width;
            height = lp == null ? Integer.MIN_VALUE : lp.height;
        }
    }

    private static final class AppliedState {
        final java.lang.ref.WeakReference<android.graphics.drawable.Drawable> background;
        final java.lang.ref.WeakReference<android.graphics.drawable.Drawable> image;
        final Integer backgroundColor;
        final Integer imageColor;
        final android.graphics.ColorFilter colorFilter;
        final float alpha;
        final int visibility;
        final int width;
        final int height;

        AppliedState(View view) {
            android.graphics.drawable.Drawable currentBackground = view.getBackground();
            background = new java.lang.ref.WeakReference<>(currentBackground);
            backgroundColor = drawableColor(currentBackground);
            if (view instanceof ImageView) {
                ImageView imageView = (ImageView) view;
                android.graphics.drawable.Drawable currentImage = imageView.getDrawable();
                image = new java.lang.ref.WeakReference<>(currentImage);
                imageColor = drawableColor(currentImage);
                colorFilter = imageView.getColorFilter();
            } else {
                image = new java.lang.ref.WeakReference<>(null);
                imageColor = null;
                colorFilter = null;
            }
            alpha = view.getAlpha();
            visibility = view.getVisibility();
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            width = lp == null ? Integer.MIN_VALUE : lp.width;
            height = lp == null ? Integer.MIN_VALUE : lp.height;
        }
    }

    private static final java.util.Map<View, ViewBaseline> sViewBaselines =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, ViewBaseline>());
    private static final java.util.Map<View, AppliedState> sAppliedStates =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, AppliedState>());
    private static final java.util.Map<View, java.lang.ref.WeakReference<Activity>> sDecorActivities =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, java.lang.ref.WeakReference<Activity>>());

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
            sDecorActivities.put(decor, new java.lang.ref.WeakReference<Activity>(activity));
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
            refreshDecorBaseline(decor);
            decor.setBackgroundColor(NEUTRAL_DARK);
            rememberAppliedState(decor);
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
        refreshForcedViewBaseline(view, opaque);
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
        rememberAppliedState(view);
    }
    private static android.graphics.drawable.Drawable copyDrawable(
            android.graphics.drawable.Drawable drawable, View owner) {
        if (drawable == null) return null;
        if (drawable instanceof ColorDrawable) {
            ColorDrawable copy = new ColorDrawable(((ColorDrawable) drawable).getColor());
            copy.setAlpha(drawable.getAlpha());
            return copy;
        }
        android.graphics.drawable.Drawable.ConstantState state = drawable.getConstantState();
        if (state == null) return drawable;
        try {
            return state.newDrawable(owner.getResources(), owner.getContext().getTheme()).mutate();
        } catch (Throwable ignored) {
            return state.newDrawable(owner.getResources()).mutate();
        }
    }

    private static ViewBaseline baselineFor(View view) {
        ViewBaseline baseline = sViewBaselines.get(view);
        if (baseline == null) {
            baseline = new ViewBaseline(view);
            sViewBaselines.put(view, baseline);
        }
        return baseline;
    }

    private static Integer drawableColor(android.graphics.drawable.Drawable drawable) {
        return drawable instanceof ColorDrawable
                ? Integer.valueOf(((ColorDrawable) drawable).getColor()) : null;
    }

    private static boolean matchesAppliedDrawable(android.graphics.drawable.Drawable current,
            android.graphics.drawable.Drawable applied, Integer appliedColor) {
        if (current != applied) return false;
        Integer currentColor = drawableColor(current);
        return currentColor == null ? appliedColor == null : currentColor.equals(appliedColor);
    }

    /** Preserve host values whenever they differ from the exact values applied last frame. */
    private static void refreshDecorBaseline(View view) {
        synchronized (sViewBaselines) {
            ViewBaseline baseline = baselineFor(view);
            AppliedState applied = sAppliedStates.get(view);
            if (applied == null || !matchesAppliedDrawable(view.getBackground(),
                    applied.background.get(), applied.backgroundColor)) {
                baseline.background = copyDrawable(view.getBackground(), view);
            }
        }
    }

    private static void refreshForcedViewBaseline(View view, boolean opaque) {
        synchronized (sViewBaselines) {
            ViewBaseline baseline = baselineFor(view);
            AppliedState applied = sAppliedStates.get(view);
            if (applied == null || !matchesAppliedDrawable(view.getBackground(),
                    applied.background.get(), applied.backgroundColor)) {
                baseline.background = copyDrawable(view.getBackground(), view);
            }
            if (view instanceof ImageView) {
                ImageView image = (ImageView) view;
                if (applied == null || !matchesAppliedDrawable(image.getDrawable(),
                        applied.image.get(), applied.imageColor)) {
                    baseline.image = image.getDrawable();
                }
                if (applied == null || image.getColorFilter() != applied.colorFilter) {
                    baseline.colorFilter = image.getColorFilter();
                }
            }
            if (applied == null || Float.compare(view.getAlpha(), applied.alpha) != 0) {
                baseline.alpha = view.getAlpha();
            }
            if (applied == null || view.getVisibility() != applied.visibility) {
                baseline.visibility = view.getVisibility();
            }
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                if (applied == null || lp.width != applied.width) baseline.width = lp.width;
                if (applied == null || lp.height != applied.height) baseline.height = lp.height;
            }
        }
    }

    private static void rememberAppliedState(View view) {
        sAppliedStates.put(view, new AppliedState(view));
    }

    private static void restoreBaseline(View view, ViewBaseline state) {
        view.setBackground(state.background);
        view.setAlpha(state.alpha);
        view.setVisibility(state.visibility);
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            image.setImageDrawable(state.image);
            image.setColorFilter(state.colorFilter);
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null && state.width != Integer.MIN_VALUE && state.height != Integer.MIN_VALUE) {
            lp.width = state.width;
            lp.height = state.height;
            view.setLayoutParams(lp);
        }
    }

    /** Capture all tracked decor hosts; activity recreation can leave more than one entry. */
    public static Object captureHotReloadHosts() {
        java.util.ArrayList<Object> hosts = new java.util.ArrayList<>();
        XposedHelpers.forEachTrackedOwner("colorosmod_wallpaper_bg_predraw", owner -> {
            if (!(owner instanceof View)) return;
            View decor = (View) owner;
            java.lang.ref.WeakReference<Activity> reference = sDecorActivities.get(decor);
            Activity activity = reference == null ? null : reference.get();
            if (activity != null) hosts.add(new Object[] { decor, activity });
        });
        return hosts.toArray();
    }

    public static void restoreHotReloadHosts(Object saved) {
        if (!(saved instanceof Object[])) return;
        for (Object host : (Object[]) saved) {
            if (!(host instanceof Object[])) continue;
            Object[] state = (Object[]) host;
            if (state.length < 2 || !(state[0] instanceof View)
                    || !(state[1] instanceof Activity)) continue;
            View decor = (View) state[0];
            Activity activity = (Activity) state[1];
            if (!decor.isAttachedToWindow() || activity.isFinishing()
                    || activity.isDestroyed()) continue;
            sDecorActivities.put(decor, new java.lang.ref.WeakReference<Activity>(activity));
            applyThemeEditBackground(activity);
        }
    }

    public static void cleanupForHotReload() {
        if (!XposedHelpers.removeTrackedPreDrawListeners("colorosmod_wallpaper_bg_predraw")) {
            log("wallpapers hot reload pre-draw cleanup incomplete");
        }
        synchronized (sViewBaselines) {
            for (java.util.Map.Entry<View, ViewBaseline> entry :
                    new java.util.ArrayList<>(sViewBaselines.entrySet())) {
                try { restoreBaseline(entry.getKey(), entry.getValue()); }
                catch (Throwable t) { log("wallpapers view baseline cleanup failed: " + t); }
            }
            sViewBaselines.clear();
            sAppliedStates.clear();
        }
        sDecorActivities.clear();
    }


}
