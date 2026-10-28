package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.rikumi.colorosmod.R;
import com.rikumi.colorosmod.xposed.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Replace the OTA card's presentation, independently of the updater's installation. */
public final class AboutDeviceCardHooks {
    private static final String KEY = "about_device_card_style";
    private static final String PREF = "key_ota_update";
    private static final String CARD = "com.oplus.settings.widget.preference.AboutDeviceOtaUpdatePreference";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Binding values only hold weak View references, including their root.
    private static final Map<ViewGroup, Binding> bindings = new WeakHashMap<>();
    private static final Map<Object, WeakReference<Object>> screens = new WeakHashMap<>();
    private static boolean active = true;
    private static final Runnable REFRESH = AboutDeviceCardHooks::refresh;

    private static int mode() {
        int value = active ? readIntCached(KEY, 0) : 0;
        return value >= 1 && value <= 9 ? value : 0;
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.oplus.settings.feature.deviceinfo.controller.OtaUpdateController", lp.classLoader);
            XposedBridge.hookAllMethods(controller, "getAvailabilityStatus", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable() && mode() != 0 && isCardController(p.thisObject)) p.setResult(0);
                }
            });
            XposedBridge.hookAllMethods(controller, "displayPreference", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!isCardController(p.thisObject) || p.args.length != 1) return;
                    screens.put(p.thisObject, new WeakReference<>(p.args[0]));
                    if (mode() != 0) ensurePreference(p.thisObject, p.args[0]);
                }
            });
            XposedBridge.hookAllMethods(controller, "handlePreferenceTreeClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (mode() == 0 || p.args.length != 1 || !PREF.equals(
                            XposedHelpers.callMethod(p.args[0], "getKey"))) return;
                    if (!updaterAvailable(context(p.args[0]), p.args[0])) p.setResult(true);
                }
            });
            Class<?> preference = XposedHelpers.findClass(CARD, lp.classLoader);
            // Native animations would cover the selected static artwork after binding.
            for (String method : new String[]{"isSupportTopVideo", "isSupportEasterEggVideo"})
                XposedBridge.hookAllMethods(preference, method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (mode() != 0) p.setResult(false);
                    }
                });
            XposedBridge.hookAllMethods(preference, "onBindViewHolder", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 1) return;
                    Object root = XposedHelpers.getObjectField(p.args[0], "itemView");
                    Binding previous = bindings.remove(root);
                    if (previous != null) previous.restore();
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (mode() == 0 || p.hasThrowable() || p.args.length != 1) return;
                    Object root = XposedHelpers.getObjectField(p.args[0], "itemView");
                    try {
                        if (root instanceof ViewGroup group) replace(p.thisObject, group, mode());
                    } catch (Throwable error) {
                        Binding failed = bindings.remove(root);
                        if (failed != null) failed.restore();
                        log("about device card bind failed: " + error);
                    }
                }
            });
            log("HOOK OK about device card replacement");
        } catch (Throwable error) { log("HOOK FAIL about device card: " + error); }
    }

    private static boolean isCardController(Object controller) {
        return PREF.equals(XposedHelpers.callMethod(controller, "getPreferenceKey"));
    }

    private static Context context(Object preference) {
        return (Context) XposedHelpers.callMethod(preference, "getContext");
    }

    private static Object ensurePreference(Object controller, Object screen) {
        Object preference = XposedHelpers.callMethod(screen, "findPreference", PREF);
        if (preference != null) return preference;
        Context host = (Context) XposedHelpers.callMethod(screen, "getContext");
        Class<?> type = XposedHelpers.findClass(CARD, controller.getClass().getClassLoader());
        preference = XposedHelpers.newInstance(type, host);
        XposedHelpers.callMethod(preference, "setKey", PREF);
        int order = host.getResources().getIdentifier("key_ota_update_order", "integer", host.getPackageName());
        XposedHelpers.callMethod(preference, "setOrder", order == 0 ? 150 : host.getResources().getInteger(order));
        XposedHelpers.callMethod(screen, "addPreference", preference);
        return preference;
    }

    private static String systemProperty(String key) {
        try {
            Class<?> properties = XposedHelpers.findClass("android.os.SystemProperties", null);
            return (String) XposedHelpers.callStaticMethod(properties, "get", key, "");
        } catch (Throwable unavailable) { return ""; }
    }

    private static boolean updaterAvailable(Context host, Object preference) {
        try {
            PackageManager pm = host.getPackageManager();
            ApplicationInfo app = pm.getApplicationInfo("com.oplus.ota", 0);
            if (!app.enabled || (app.flags & ApplicationInfo.FLAG_INSTALLED) == 0) return false;
            Intent target = (Intent) XposedHelpers.callMethod(preference, "getTargetIntentStatic");
            ResolveInfo resolved = target == null ? null : pm.resolveActivity(target, 0);
            return resolved != null && resolved.activityInfo != null && resolved.activityInfo.enabled
                    && resolved.activityInfo.applicationInfo.enabled;
        } catch (Throwable unavailable) { return false; }
    }

    private static void replace(Object preference, ViewGroup root, int selected) throws Exception {
        Context host = root.getContext();
        Context module = host.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        int layout = host.getResources().getIdentifier("about_device_ota_item", "layout", host.getPackageName());
        if (layout == 0) return;
        // Inflate the ROM's own foreground hierarchy instead of approximating its
        // typography, spacing, order, dark-mode resources or tablet qualifiers.
        ViewGroup card = (ViewGroup) LayoutInflater.from(host).inflate(layout, root, false);
        if (selected == 5 || selected == 7) {
            View originalContent = find(card, "lin_center", View.class);
            if (originalContent != null) card.removeView(originalContent);
            View legacyContent = LayoutInflater.from(module).inflate(R.layout.about_card_oxygenos15_foreground, card, false);
            ImageView phoneBrand = legacyContent.findViewById(R.id.about_card15_phone_brand);
            if (phoneBrand != null) {
                if (selected == 7) {
                    Drawable phoneLogo = module.getDrawable(AboutCardStyleResolver.phoneBrandResource(Build.BRAND)).mutate();
                    phoneLogo.setTint(module.getColor(R.color.about_card_realme15_logo_color));
                    phoneBrand.setImageDrawable(phoneLogo);
                }
                else if (Build.BRAND.equalsIgnoreCase("oneplus"))
                    phoneBrand.setImageDrawable(module.getDrawable(R.drawable.about_card_phone_oneplus));
            }
            card.addView(legacyContent);
        }
        boolean straightPhone = selected != 9 || isStraightPhone(host);
        if (selected == 9) {
            View originalContent = find(card, "lin_center", View.class);
            if (originalContent != null) card.removeView(originalContent);
            // 17 moves update controls outside lin_center. Include them in the replacement group.
            hide(card, "update_text"); hide(card, "update_find");
            View nativeStatus = find(card, "update_text", View.class);
            View nativeUpdate = find(card, "update_find", View.class);
            if (nativeStatus != null && nativeStatus.getParent() instanceof ViewGroup parent) parent.removeView(nativeStatus);
            if (nativeUpdate != null && nativeUpdate.getParent() instanceof ViewGroup parent) parent.removeView(nativeUpdate);
            ViewGroup content = (ViewGroup) LayoutInflater.from(module).inflate(R.layout.about_card_coloros17_foreground, card, false);
            View brandGroup = content.findViewById(R.id.about_card17_brand_group);
            if (brandGroup != null && brandGroup.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
                margins.topMargin = dp(host, straightPhone ? 104 : 66); brandGroup.setLayoutParams(margins);
            }
            for (String id : new String[]{"update_text", "update_find"}) {
                TextView status = find(content, id, TextView.class);
                if (status != null && status.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
                    margins.bottomMargin = dp(host, straightPhone ? 40 : 20); status.setLayoutParams(margins);
                }
            }
            card.addView(content);
        }
        for (String name : new String[]{"device_market_name", "model_name", "model_build_number", "model_ai",
                "update_text", "update_find"}) copyText(root, card, name);
        hide(card, "about_device_video_clip_container");
        hide(card, "about_device_top_anim_bg");
        hide(card, "about_device_top_video_mask");
        hide(card, "coloros_easter_egg_logo");
        ImageView background = find(card, "about_device_top_bg", ImageView.class);
        ImageView logo = find(card, "coloros_logo", ImageView.class);
        TextView version = find(card, "model_build_number", TextView.class);
        TextView name = find(card, "device_market_name", TextView.class);
        if (background == null || logo == null || version == null) return;
        Resources resources = module.getResources();
        boolean tabletLandscape = host.getResources().getConfiguration().smallestScreenWidthDp >= 600
                && host.getResources().getConfiguration().orientation == 2;
        int artwork = selected == 4 ? AboutCardStyleResolver.resolve(
                systemProperty("ro.oplus.image.system_ext.area"),
                systemProperty("ro.oplus.image.system_ext.brand"),
                systemProperty("ro.build.version.ota"),
                systemProperty("ro.build.version.oplusrom.display"),
                systemProperty("ro.build.version.oplusrom"), Build.DISPLAY) : selected;
        int bg = AboutCardStyleResolver.backgroundResource(artwork, tabletLandscape);
        int brand = switch (artwork) {
            case 2 -> R.drawable.about_card_logo_coloros15;
            case 3 -> selected == 4 ? R.drawable.about_card_logo_coloros16 : R.drawable.about_card_logo_oxygenos;
            case 5 -> selected == 4 ? R.drawable.about_card_logo_coloros15 : R.drawable.about_card_logo_oxygenos15;
            case 6 -> R.drawable.about_card_logo_coloros161;
            case 7 -> R.drawable.about_card_logo_realme15;
            case 8 -> R.drawable.about_card_logo_realme16;
            case 9 -> R.drawable.about_card_logo_coloros17;
            default -> R.drawable.about_card_logo_coloros16;
        };
        background.setImageDrawable(resources.getDrawable(bg, module.getTheme()));
        background.setVisibility(View.VISIBLE);
        logo.setImageDrawable(resources.getDrawable(brand, module.getTheme()));
        logo.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams logoParams = logo.getLayoutParams();
        logoParams.width = dp(host, selected == 9 ? 220 : selected == 8 ? 193 : selected == 7 ? 69 : selected == 5 ? 74 : selected == 3 ? 179 : 165);
        logoParams.height = dp(host, selected == 9 ? 60 : selected == 7 ? 13 : selected == 5 ? 17 : 34);
        logo.setLayoutParams(logoParams);
        View nativeCard = find(card, "about_device_top_video_card", View.class);
        if (nativeCard != null) XposedHelpers.callMethod(nativeCard, "setCardBackgroundColor",
                artwork == 3 || artwork == 5 ? 0xff010103 : 0x99ffffff);
        int color = selected == 9 ? 0xd9ffffff : artwork == 5 ? module.getColor(R.color.about_card15_text_color)
                : artwork == 2 || artwork == 7 || artwork == 8 ? Color.WHITE : 0xffffddeb;
        version.setTextColor(color);
        if (name != null) name.setTextColor(color);
        if (selected == 5 || selected == 7) {
            version.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            version.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            if (name != null) {
                name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 34);
                name.setTypeface(Typeface.create("sans-serif-semibold", Typeface.NORMAL));
            }
        }
        if (selected == 9) {
            version.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
            version.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (selected == 8) {
            version.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            if (name != null) name.setTypeface(Typeface.create("sans-serif-semibold", Typeface.NORMAL));
        }
        if (selected == 7 || selected == 8) {
            version.setText(AboutCardStyleResolver.realmeVersion(
                    systemProperty("ro.build.version.ota"),
                    systemProperty("ro.build.version.oplusrom.display"),
                    systemProperty("ro.build.version.oplusrom"), Build.DISPLAY, version.getText().toString()));
        }
        if (selected == 4) addModLabel(logo, color, artwork == 6 ? 0f : 34f / 36f);
        boolean hasUpdater = updaterAvailable(host, preference);
        // Adding/removing a compound drawable changes the measured width of this
        // centered row; no empty arrow slot remains when the updater is absent.
        Drawable arrow = hasUpdater && selected != 9 ? resources.getDrawable(R.drawable.about_card_version_arrow, module.getTheme()) : null;
        if (arrow != null) {
            arrow = arrow.mutate();
            arrow.setTint(color);
            int side = dp(host, selected == 5 || selected == 7 ? 15 : 16);
            arrow.setBounds(0, 0, side, side);
        }
        version.setCompoundDrawablesRelative(null, null, arrow, null);
        version.setCompoundDrawablePadding(arrow != null ? dp(host, 4) : 0);
        version.setGravity(Gravity.CENTER);
        version.setOnClickListener(hasUpdater ? v -> XposedHelpers.callMethod(preference, "performClick") : null);
        version.setClickable(hasUpdater);
        if (version.getParent() instanceof LinearLayout row) row.setGravity(Gravity.CENTER);
        TextView status = find(card, "update_text", TextView.class);
        if (status != null) {
            if (!hasUpdater) {
                int latest = host.getResources().getIdentifier("about_phone_already_last_version", "string", host.getPackageName());
                status.setText(latest != 0 ? host.getString(latest) : module.getString(R.string.ui_about_card_latest));
                status.setVisibility(View.VISIBLE);
                status.setClickable(false);
            }
            status.setTextColor(selected == 9 ? Color.WHITE : artwork == 5 || selected == 7 ? color : 0xe6ffffff);
            if (selected == 9) style17Status(status, module, false);
        }
        if (!hasUpdater) hide(card, "update_find");
        else {
            TextView update = find(card, "update_find", TextView.class);
            if (update != null) {
                if (selected == 9) style17Status(update, module, true);
                else if (selected == 5 || selected == 7) {
                    update.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 8);
                    update.setTypeface(Typeface.create("sans-serif-semibold", Typeface.BOLD));
                    update.setTextColor(color);
                } else styleUpdateButton(update, artwork);
                update.setOnClickListener(v -> XposedHelpers.callMethod(preference, "performClick"));
            }
        }
        Binding binding = new Binding(root);
        bindings.put(root, binding);
        if (selected == 9) {
            ViewGroup.LayoutParams rootParams = root.getLayoutParams();
            rootParams.height = dp(host, straightPhone ? 328 : 240);
            root.setLayoutParams(rootParams);
            float radius = dp(host, straightPhone ? 28 : 24);
            ViewOutlineProvider outline = new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline shape) {
                    shape.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            };
            root.setOutlineProvider(outline); root.setClipToOutline(true);
            card.setOutlineProvider(outline); card.setClipToOutline(true);
        }
        if (selected == 5 || selected == 7) {
            // Both original 15 layouts declare about_device_ota_item_height = 234dp.
            ViewGroup.LayoutParams rootParams = root.getLayoutParams();
            rootParams.height = dp(host, 234);
            root.setLayoutParams(rootParams);
        }
        // Preserve the native binder's Views and contents for immediate switch-off.
        for (int i = 0; i < root.getChildCount(); i++) root.getChildAt(i).setVisibility(View.GONE);
        ViewGroup.LayoutParams params = card.getLayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        if (params instanceof ViewGroup.MarginLayoutParams margins) {
            margins.setMargins(0, 0, 0, 0); margins.setMarginStart(0); margins.setMarginEnd(0);
        }
        card.setLayoutParams(params);
        root.addView(card);
        root.setClickable(hasUpdater);
        binding.replacement = new WeakReference<>(card);
    }

    private static boolean isStraightPhone(Context host) {
        try {
            Class<?> tablet = XposedHelpers.findClass("com.oplus.settings.utils.TabletUtils", host.getClassLoader());
            if ((Boolean) XposedHelpers.callStaticMethod(tablet, "isTablet")) return false;
            Class<?> foldable = XposedHelpers.findClass("com.oplus.settings.feature.compatwindow.FoldableScreenUtil", host.getClassLoader());
            return !(Boolean) XposedHelpers.callStaticMethod(foldable, "isFoldableScreen")
                    || (Boolean) XposedHelpers.callStaticMethod(foldable, "isFolded", host);
        } catch (Throwable unavailable) {
            return host.getResources().getConfiguration().smallestScreenWidthDp < 600;
        }
    }

    private static void style17Status(TextView status, Context module, boolean update) {
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        Drawable end = update ? module.getDrawable(R.drawable.about_card17_update_arrow) : null;
        if (end != null) { int size = dp(status.getContext(), 12); end.setBounds(0, 0, size, size); }
        status.setCompoundDrawablesRelative(null, null, end, null);
        status.setCompoundDrawablePadding(dp(status.getContext(), 6));
    }

    private static void styleUpdateButton(TextView update, int selected) {
        Context host = update.getContext();
        String background;
        ViewGroup.LayoutParams params = update.getLayoutParams();
        if (selected == 2) {
            // Native ColorOS 15 branch: wrap-content pill with 20dp side insets.
            background = "about_device_update_btn_confidentiality";
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            update.setPadding(dp(host, 20), 0, dp(host, 20), 0);
        } else {
            // Match the 16 branch's width-dependent native background selection.
            boolean wide = update.getPaint().measureText(update.getText().toString())
                    / host.getResources().getDisplayMetrics().density > 130;
            boolean landscape = host.getResources().getConfiguration().smallestScreenWidthDp >= 600
                    && host.getResources().getConfiguration().orientation == 2;
            background = wide ? (landscape ? "about_device_update_btn_max_land" : "about_device_update_btn_max")
                    : "about_device_update_btn";
            String golden = wide ? (landscape ? "about_device_update_btn_max_land_16_1" : "about_device_update_btn_max_16_1")
                    : landscape ? "about_device_update_btn_16_1_land" : "about_device_update_btn_16_1";
            if (selected == 6 && host.getResources().getIdentifier(golden, "drawable", host.getPackageName()) != 0)
                background = golden;
            String dimension = wide ? "update_btn_width_max" : "update_btn_width_min";
            int id = host.getResources().getIdentifier(dimension, "dimen", host.getPackageName());
            params.width = id == 0 ? dp(host, wide ? 180 : 160) : host.getResources().getDimensionPixelSize(id);
            update.setPadding(dp(host, 10), update.getPaddingTop(), dp(host, 10), update.getPaddingBottom());
        }
        int id = host.getResources().getIdentifier(background, "drawable", host.getPackageName());
        if (id != 0) update.setBackground(host.getDrawable(id));
        update.setTextColor(0xe6ffffff);
        update.setLayoutParams(params);
    }

    private static void addModLabel(ImageView logo, int color, float logoInkTopDp) {
        if (!(logo.getParent() instanceof LinearLayout parent)) return;
        int index = parent.indexOfChild(logo);
        ViewGroup.LayoutParams original = logo.getLayoutParams();
        parent.removeView(logo);
        LinearLayout row = new LinearLayout(logo.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        row.setBaselineAligned(false);
        row.addView(logo, new LinearLayout.LayoutParams(original.width, original.height));
        TextView mod = new TextView(logo.getContext());
        mod.setText("(Mod)");
        mod.setTextColor(color);
        mod.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        mod.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mod.setIncludeFontPadding(false);
        LinearLayout.LayoutParams superscript = new LinearLayout.LayoutParams(-2, -2);
        superscript.gravity = Gravity.TOP;
        superscript.setMarginStart(dp(logo.getContext(), 4));
        row.addView(mod, superscript);
        mod.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            Rect ink = new Rect();
            mod.getPaint().getTextBounds("(Mod)", 0, 5, ink);
            float logoInkTop = logoInkTopDp * logo.getResources().getDisplayMetrics().density;
            mod.setTranslationY(logoInkTop - (mod.getBaseline() + ink.top));
        });
        parent.addView(row, index, new LinearLayout.LayoutParams(-2, original.height));
    }

    private static void copyText(ViewGroup original, ViewGroup replacement, String id) {
        TextView from = find(original, id, TextView.class), to = find(replacement, id, TextView.class);
        if (from == null || to == null) return;
        to.setText(from.getText());
        to.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, from.getTextSize());
        to.setTypeface(from.getTypeface());
        to.setVisibility(from.getVisibility());
        to.setTextColor(from.getTextColors());
        to.setContentDescription(from.getContentDescription());
    }

    private static <T extends View> T find(View root, String name, Class<T> type) {
        // Module IDs and host IDs use independent resource tables and can collide.
        View tagged = root.findViewWithTag("colorosmod:aboutcard:" + name);
        if (type.isInstance(tagged)) return type.cast(tagged);
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        View view = id == 0 ? null : root.findViewById(id);
        return type.isInstance(view) ? type.cast(view) : null;
    }
    private static void hide(View root, String name) {
        View view = find(root, name, View.class);
        if (view != null) view.setVisibility(View.GONE);
    }
    private static int dp(Context host, int value) { return Math.round(host.getResources().getDisplayMetrics().density * value); }

    public static void onSettingsSnapshotPublished() {
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }
    private static void refresh() {
        for (var entry : new ArrayList<>(screens.entrySet())) {
            Object screen = entry.getValue().get();
            if (screen == null) continue;
            try {
                XposedHelpers.callMethod(entry.getKey(), "displayPreference", screen);
                Object preference = XposedHelpers.callMethod(screen, "findPreference", PREF);
                if (preference != null) {
                    XposedHelpers.callMethod(preference, "setVisible",
                            ((Integer) XposedHelpers.callMethod(entry.getKey(), "getAvailabilityStatus")) == 0);
                    XposedHelpers.callMethod(preference, "notifyChanged");
                }
            } catch (Throwable error) { log("about device card refresh failed: " + error); }
        }
    }
    public static void cleanupForHotReload() {
        active = false;
        MAIN.removeCallbacks(REFRESH);
        for (Binding binding : bindings.values()) binding.restore();
        bindings.clear();
        refresh();
        screens.clear();
    }
    private static final class Binding {
        final WeakReference<ViewGroup> root;
        final ArrayList<WeakReference<View>> children = new ArrayList<>();
        final ArrayList<Integer> visibility = new ArrayList<>();
        final boolean clickable;
        final int originalHeight;
        final ViewOutlineProvider originalOutline;
        final boolean originalClipToOutline;
        WeakReference<View> replacement = new WeakReference<>(null);
        Binding(ViewGroup group) {
            root = new WeakReference<>(group); clickable = group.isClickable();
            originalHeight = group.getLayoutParams().height;
            originalOutline = group.getOutlineProvider(); originalClipToOutline = group.getClipToOutline();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                children.add(new WeakReference<>(child)); visibility.add(child.getVisibility());
            }
        }
        void restore() {
            ViewGroup group = root.get();
            View added = replacement.get();
            if (group != null && added != null) group.removeView(added);
            for (int i = 0; i < children.size(); i++) {
                View child = children.get(i).get();
                if (child != null) child.setVisibility(visibility.get(i));
            }
            if (group != null) {
                group.setClickable(clickable);
                group.setOutlineProvider(originalOutline); group.setClipToOutline(originalClipToOutline);
                ViewGroup.LayoutParams params = group.getLayoutParams();
                if (params.height != originalHeight) { params.height = originalHeight; group.setLayoutParams(params); }
            }
        }
    }
    private AboutDeviceCardHooks() {}
}
