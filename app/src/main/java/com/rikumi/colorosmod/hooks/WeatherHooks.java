package com.rikumi.colorosmod.hooks;

import android.view.View;
import android.view.WindowInsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.*;

public final class WeatherHooks {
    static final String BASE = "com.oplus.weather.";
    private WeatherHooks() {}

    static boolean enabled(String key) { return readBool("weather_" + key, false); }

    interface Callback { void run(XC_MethodHook.MethodHookParam param) throws Throwable; }

    static void hook(ClassLoader loader, String name, String method, boolean before, Callback action) {
        try {
            Class<?> type = XposedHelpers.findClass(BASE + name, loader);
            int count = 0;
            for (Method m : type.getDeclaredMethods()) {
                // Kotlin bridge methods delegate to the concrete implementation.
                if (!m.getName().equals(method) || m.isBridge()) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    private void apply(MethodHookParam p) {
                        try { action.run(p); }
                        catch (Throwable t) { log("Weather " + name + "#" + method + ": " + t); }
                    }
                    @Override protected void beforeHookedMethod(MethodHookParam p) { if (before) apply(p); }
                    @Override protected void afterHookedMethod(MethodHookParam p) { if (!before && !p.hasThrowable()) apply(p); }
                });
                count++;
            }
            if (count == 0) throw new NoSuchMethodException(method);
            log("HOOK OK " + name + "#" + method + " (weather)");
        } catch (Throwable t) { log("HOOK FAIL " + name + "#" + method + ": " + t); }
    }

    private static void hideCreator(ClassLoader cl, String name, String key) {
        hook(cl, name, "create", true, p -> { if (enabled(key)) p.setResult(null); });
    }

    public static void hookWeather(XC_LoadPackage.LoadPackageParam pkg) {
        ClassLoader cl = pkg.classLoader;
        hideCreator(cl, "main.view.itemview.AirQualityItemCreator", "hide_air_quality");
        hideCreator(cl, "main.view.itemview.SunViewItemCreator", "hide_sun");
        hideCreator(cl, "main.view.itemview.CctvWeatherForecastItemCreator", "hide_cctv");

        hook(cl, "adloop.utils.NoticeDataUtils", "createCCTVWeatherForecast", true,
                p -> { if (enabled("hide_cctv")) p.setResult(null); });

        hook(cl, "adloop.utils.NoticeDataUtils", "getConfigByNoticeType", true,
                p -> { if (enabled("hide_clothing") && "0606".equals(p.args[0])) p.setResult(null); });
        hideCreator(cl, "main.view.itemview.LifeGroupItemCreator", "hide_life");
        hideCreator(cl, "indexoperations.IndexOperationsItemCreator", "hide_operations");
        // Filter a copy at the data boundary: both cloud and default UI configurations use it.
        hook(cl, "main.model.WeatherWrapper", "getOperationsCards", false, p -> {
            if (!(p.getResult() instanceof List<?> original)) return;
            ArrayList<Object> filtered = new ArrayList<>(original.size());
            for (Object card : original) {
                int type = (Integer) XposedHelpers.callMethod(card, "getCardType");
                String key = switch (type) {
                    case 6 -> "hide_ads";
                    case 19 -> "hide_pollen";
                    case 20 -> "hide_clothing";
                    case 22 -> "hide_sunscreen";
                    case 23 -> "hide_makeup";
                    case 24 -> "hide_flu";
                    default -> null;
                };
                if (key == null || !enabled(key)) filtered.add(card);
            }
            if (filtered.size() != original.size()) p.setResult(filtered);
        });
        // R controller still ships with its shaders/assets and native lifecycle in domestic 16.
        if (XposedHelpers.findClassIfExists(BASE + "main.skin.DefaultWeatherEffectControllerR", cl) != null) {
            hook(cl, "utils.AppFeatureUtils", "isUseWeatherAnimOld", true,
                    p -> { if (enabled("legacy_background")) p.setResult(true); });
        }
        hook(cl, "main.skin.ThemeUtil", "isShowThemeIcon", false,
                p -> { if (enabled("hide_theme")) p.setResult(false); });
        hook(cl, "main.view.itemview.TodayWeatherItem", "getVoiceVisible", false,
                p -> { if (enabled("hide_voice")) p.setResult(View.GONE); });
        hook(cl, "main.view.WeatherFragment", "onViewCreated", false, p -> {
            View list = (View) XposedHelpers.callMethod(p.thisObject, "getBindingRecyclerView");
            if (list == null || XposedHelpers.getAdditionalInstanceField(list, NAV_PADDING_STATE) != null) return;
            NavigationPadding state = new NavigationPadding(list);
            XposedHelpers.setAdditionalInstanceField(list, NAV_PADDING_STATE, state);
            list.getViewTreeObserver().addOnGlobalLayoutListener(() -> state.update(list));
            state.update(list);
        });
        hookApi(cl);
        hookMeteorologyBackground(cl);
    }

    private static final String NAV_PADDING_STATE = "colorosmod.weather.navigationPadding";

    private static final class NavigationPadding {
        private int baseBottom;
        private int appliedBottom;

        NavigationPadding(View view) {
            baseBottom = appliedBottom = view.getPaddingBottom();
        }

        void update(View view) {
            WindowInsets insets = view.getRootWindowInsets();
            if (insets == null) return;
            // Preserve native padding updates and never accumulate our previous addition.
            if (view.getPaddingBottom() != appliedBottom) baseBottom = view.getPaddingBottom();
            int extra = enabled("navigation_padding")
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom : 0;
            appliedBottom = baseBottom + extra;
            if (view.getPaddingBottom() != appliedBottom) {
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), appliedBottom);
            }
        }
    }

    private static void hookApi(ClassLoader cl) {
        String[][] endpoints = {
            {"getAddress", "7a0e7a0a79436c43345130442c493b166500720461133d5c305c3d4a245035563e10731c71", "7a0e7a0a79436c43345130442c493b166500720461133d5c305c3d4a244b3816751a77"},
            {"getUIConfigAddress", "7a0e7a0a79436c43345130442c493b167713701f71177e1934573917761a7617600e7a1f7c143a59365b", ""},
            {"getScrollbarInfoAddress", "7a0e7a0a79436c432a483a5522513446680e6b0e6a19375f3a433756264b24462f01620d60", ""}
        };
        for (String[] endpoint : endpoints) {
            hook(cl, "service.network.host.Host", endpoint[0], true, p -> {
                int region = readInt("weather_api_region", 0);
                if (region != 1 && region != 2) return;
                String encoded = endpoint[region];
                p.setResult(encoded.isEmpty() ? "" : XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass(BASE + "utils.HostAppConfigUtil", cl), "getString", encoded));
            });
        }
    }

    private static final String ORIGINAL_BACKGROUND_VISIBILITY = "colorosmod.weather.backgroundVisibility";

    private static void hookMeteorologyBackground(ClassLoader cl) {

        Class<?> bindingClass = XposedHelpers.findClassIfExists(
                BASE + "databinding.ItemMeteorologyWeatherBinding", cl);
        if (bindingClass == null) return;
        hook(cl, "main.recycler.BindingItemViewHolder", "bindData", true, p ->
                restoreBackground(XposedHelpers.callMethod(p.thisObject, "getBinding"), bindingClass));
        hook(cl, "main.recycler.BindingItemViewHolder", "bindData", false, p ->
                hideBackground(XposedHelpers.callMethod(p.thisObject, "getBinding"), bindingClass));
        if (XposedHelpers.findClassIfExists(BASE + "main.recycler.EmbedBindingAdapter", cl) != null) {
            hook(cl, "main.recycler.EmbedBindingAdapter", "bindData", true, p ->
                    restoreBackground(p.args[0], bindingClass));
            hook(cl, "main.recycler.EmbedBindingAdapter", "bindData", false, p ->
                    hideBackground(p.args[0], bindingClass));
        }
    }

    private static View background(Object binding, Class<?> bindingClass) {
        if (!bindingClass.isInstance(binding)) return null;
        return (View) XposedHelpers.getObjectField(binding, "meteorologyItemBg");
    }

    private static void restoreBackground(Object binding, Class<?> bindingClass) {
        View view = background(binding, bindingClass);
        if (view == null) return;
        Object visibility = XposedHelpers.removeAdditionalInstanceField(view, ORIGINAL_BACKGROUND_VISIBILITY);
        if (visibility instanceof Integer original) view.setVisibility(original);
    }

    private static void hideBackground(Object binding, Class<?> bindingClass) {
        if (!enabled("hide_meteorology_bg")) return;
        View view = background(binding, bindingClass);
        if (view == null) return;
        XposedHelpers.setAdditionalInstanceField(view, ORIGINAL_BACKGROUND_VISIBILITY, view.getVisibility());
        view.setVisibility(View.GONE);
    }
}
