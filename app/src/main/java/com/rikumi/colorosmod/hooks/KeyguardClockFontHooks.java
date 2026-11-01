package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

public final class KeyguardClockFontHooks {
    private static final String HOST = "com.oplus.systemui.shared.clocks.SingleClockView";
    private static final String PLUGIN_TIME = "com.oplus.keyguard.clock.base.ui.view.ClockTimeView";
    private static final String PLUGIN_TEXT = "com.oplus.keyguard.clock.base.widget.CustomizedTextView";
    private static final String DIGITAL_TIME = "com.oplus.keyguard.clock.digital.ui.view.ClockTimeView";
    private static final String DIGITAL_TEXT = "com.oplus.keyguard.clock.digital.widget.MyCustomizedTextView";
    private static String lastDigitalStyle;
    private static boolean digitalDefault;
    private static Object configRecord;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<TextView, Original> views = new WeakHashMap<>();
    private static final HashMap<Integer, Typeface> fonts = new HashMap<>();
    private static Class<?> updateMonitor;
    private static Context moduleContext;
    private static boolean active = true, failed;

    private static final class Original {
        final WeakReference<View> host;
        Typeface font, applied;
        final float spacing;
        final String features;
        final boolean symbol;
        final Rect digitBounds = new Rect(), symbolBounds = new Rect();
        boolean centered;
        Original(TextView view, View owner) {
            host = new WeakReference<>(owner);
            symbol = view == XposedHelpers.getObjectField(owner,
                    PLUGIN_TIME.equals(owner.getClass().getName()) ? "timeSymbolTextView" : "mClockSymbol");
            font = view.getTypeface(); spacing = view.getLetterSpacing(); features = view.getFontFeatureSettings();
        }
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            updateMonitor = XposedHelpers.findClass("com.android.keyguard.KeyguardUpdateMonitor", lp.classLoader);
            Class<?> record = XposedHelpers.findClass("com.oplus.systemui.keyguard.clockstyle.KeyguardStyleClockConfigRecord", lp.classLoader);
            configRecord = XposedHelpers.getStaticObjectField(record, "INSTANCE");
            XC_MethodHook bind = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { track((View) p.thisObject); }
            };
            XposedHelpers.findAndHookMethod(HOST, lp.classLoader, "onFinishInflate", bind);
            XposedHelpers.findAndHookMethod(HOST, lp.classLoader, "setTextFont$2", bind);
            XposedHelpers.findAndHookMethod(TextView.class, "onMeasure", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                        TextView view = (TextView) p.thisObject;
                        if (!active) return;
                        try {
                            if (DIGITAL_TEXT.equals(view.getClass().getName())) {
                                View host = pluginHost(view);
                                if (host != null && defaultTheme(host)) DigitalKeyguardFontHooks.trackText(host, view);
                                return;
                            }
                        Original original = views.get(view);
                        if (original == null && pluginText(view)) {
                            View host = pluginHost(view);
                            if (host != null && defaultTheme(host)) {
                                original = new Original(view, host);
                                views.put(view, original);
                            }
                        }
                        if (original != null) apply(view, original);
                    } catch (Throwable error) {
                        if (!failed) { failed = true; log("default keyguard clock discovery failed: " + error); }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(TextView.class, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Original original = views.get((TextView) p.thisObject);
                    if (!active || original == null || !original.symbol || !original.centered) return;
                    TextView view = (TextView) p.thisObject;
                    String text = view.getText().toString();
                    if (text.isEmpty()) return;

                    view.getPaint().getTextBounds("0123456789", 0, 10, original.digitBounds);
                    view.getPaint().getTextBounds(text, 0, text.length(), original.symbolBounds);
                    if (original.digitBounds.isEmpty() || original.symbolBounds.isEmpty()) return;
                    Canvas canvas = (Canvas) p.args[0];
                    p.setObjectExtra("clockSymbolCanvas", canvas.save());
                    canvas.translate(0, original.digitBounds.exactCenterY() - original.symbolBounds.exactCenterY());
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object save = p.getObjectExtra("clockSymbolCanvas");
                    if (save instanceof Integer count) ((Canvas) p.args[0]).restoreToCount(count);
                }
            });
            refresh();
        } catch (Throwable error) { log("default keyguard clock font hook failed: " + error); }
    }

    private static void track(View host) {
        if (!active || !HOST.equals(host.getClass().getName())) return;
        for (String field : new String[]{"mTimeHour", "mTimeMinute", "mClockSymbol"}) {
            Object object = XposedHelpers.getObjectField(host, field);
            if (object instanceof TextView view) {
                Original original = views.get(view);
                if (original == null) { original = new Original(view, host); views.put(view, original); }
                apply(view, original);
            }
        }
    }

    private static View pluginHost(View view) {
        // Date/message views use the same text widget; only ClockTimeView digits qualify.
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View owner; parent = owner.getParent())
            if (PLUGIN_TIME.equals(owner.getClass().getName()) || DIGITAL_TIME.equals(owner.getClass().getName())) return owner;
        return null;
    }

    private static boolean pluginText(View view) {
        return PLUGIN_TEXT.equals(view.getClass().getName()) || DIGITAL_TEXT.equals(view.getClass().getName());
    }

    static boolean defaultTheme(View host) {
        if (host == null) return false;
        boolean digital = DIGITAL_TIME.equals(host.getClass().getName());
        boolean plugin = PLUGIN_TIME.equals(host.getClass().getName()) || digital;
        if (!plugin && !HOST.equals(host.getClass().getName())) return false;

        int user = (Integer) XposedHelpers.callStaticMethod(updateMonitor, "getCurrentUser");
        String owner = (String) XposedHelpers.callStaticMethod(Settings.System.class, "getStringForUser",
                host.getContext().getContentResolver(), "oplus_customize_unlock_change_pkg", user);
        if (!TextUtils.isEmpty(owner) && !"com.android.systemui".equals(owner)) return false;
        if (plugin) {

            if (digital) {
                Object data = XposedHelpers.callMethod(configRecord, "getPersonalityData");
                String style = data == null ? null : (String) XposedHelpers.callMethod(data, "getStyleData");
                if (!TextUtils.equals(lastDigitalStyle, style)) {
                    lastDigitalStyle = style;
                    digitalDefault = false;
                    try {
                        org.json.JSONObject config = new org.json.JSONObject(style);
                        // ColorOS 17 now saves the default digital clock under its own
                        // package; migrated configurations may still name the base clock.
                        String pkg = config.optString("pkg");
                        digitalDefault = ("com.oplus.keyguard.clock.base".equals(pkg)
                                || "com.oplus.keyguard.clock.digital".equals(pkg))
                                && new org.json.JSONObject(config.optString("clockStyleConfig")).optInt("clockStyle", -1) == 0;
                    } catch (Exception ignored) { }
                }
                return digitalDefault;
            }

            Object config = XposedHelpers.callMethod(configRecord, "getKeyguardStyleConfig");
            Object clock = XposedHelpers.callMethod(configRecord, "getClockStyleConfig");
            return config != null && clock != null
                    && "com.oplus.keyguard.clock.base".equals(XposedHelpers.callMethod(config, "getPkg"))
                    && ((Integer) XposedHelpers.callMethod(clock, "getClockStyle")) == 0;
        }
        // ColorOS 17 reports key_user_apply_default_theme=false even while its own clock is
        // in use, so that setting no longer identifies the default clock. SingleClockView is
        // only inflated for the system clock provider, and the owner check above already
        // excludes third-party lock-screen themes.
        return true;
    }

    private static void apply(TextView view, Original original) {
        try {
            if (view.getTypeface() != original.applied) original.font = view.getTypeface();
            if (!active || !defaultTheme(original.host.get())) { restore(view, original); return; }
            int family = Math.max(0, Math.min(4, readIntCached("keyguard_clock_font", 1)));
            int weight = Math.max(0, Math.min(9, readIntCached("keyguard_clock_weight", 3))) * 100;
            Typeface selected = resolve(view.getContext(), family, weight, original.font);
            if (view.getTypeface() != selected) view.setTypeface(selected);
            original.applied = selected;
            original.centered = family != 0 || weight != 0;
            float spacing = readBoolCached("keyguard_clock_spacing_enabled", true)
                    ? (Math.max(-6, Math.min(14, readIntCached("keyguard_clock_spacing", 0))) - 4) / 100f : original.spacing;
            if (view.getLetterSpacing() != spacing) view.setLetterSpacing(spacing);
            String features = original.features;
            if (readBoolCached("keyguard_clock_monospace", true))
                features = TextUtils.isEmpty(features) ? "'tnum'" : features + ", 'tnum'";
            if (!TextUtils.equals(view.getFontFeatureSettings(), features)) view.setFontFeatureSettings(features);
        } catch (Throwable error) {
            if (!failed) { failed = true; log("default keyguard clock font apply failed: " + error); }
        }
    }

    static Typeface resolve(Context context, int family, int weight, Typeface nativeFont) throws Exception {
        if (family == 0 && weight == 0) return nativeFont;
        if (weight == 0) weight = nativeFont == null ? 400 : nativeFont.getWeight();
        if (family == 0) return Typeface.create(nativeFont, weight, false);
        if (family == 2) weight = Math.max(200, Math.min(800, weight));
        if (family == 3) weight = Math.max(300, weight);
        if (family == 4) weight = weight >= 600 ? 700 : weight >= 500 ? 500 : 400;
        int key = family * 1000 + weight;
        Typeface font = fonts.get(key);
        if (font != null) return font;
        if (moduleContext == null) moduleContext = context.createPackageContext(MODULE_PACKAGE, 0);
        String file = family == 1 ? "Inter.ttf" : family == 2 ? "Manrope.ttf" : family == 3 ? "Rubik.ttf"
                : weight >= 700 ? "Lato-Bold.ttf" : weight >= 500 ? "Lato-Medium.ttf" : "Lato-Regular.ttf";
        Typeface.Builder builder = new Typeface.Builder(moduleContext.getAssets(), "clock_fonts/" + file);
        if (family != 4) builder.setFontVariationSettings("'wght' " + weight + (family == 1 ? ", 'opsz' 32" : ""));
        font = builder.setWeight(weight).setItalic(false).build();
        fonts.put(key, font);
        return font;
    }

    private static void restore(TextView view, Original original) {
        if (view.getTypeface() != original.font) view.setTypeface(original.font);
        original.applied = original.font;
        original.centered = false;
        if (view.getLetterSpacing() != original.spacing) view.setLetterSpacing(original.spacing);
        if (!TextUtils.equals(view.getFontFeatureSettings(), original.features)) view.setFontFeatureSettings(original.features);
    }

    public static void refresh() {
        main.post(() -> {
            if (!active) return;
            try {
                Object manager = XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"), "getInstance");
                for (String name : (String[]) XposedHelpers.callMethod(manager, "getViewRootNames")) {
                    Object root = XposedHelpers.callMethod(manager, "getRootView", name);
                    if (root instanceof View view) discover(view);
                }
                for (TextView view : new ArrayList<>(views.keySet())) { apply(view, views.get(view)); view.requestLayout(); view.invalidate(); }
                DigitalKeyguardFontHooks.refresh();
            } catch (Throwable error) { if (!failed) { failed = true; log("default keyguard clock refresh failed: " + error); } }
        });
    }

    private static void discover(View view) {
        if (HOST.equals(view.getClass().getName())) { track(view); return; }
        if (DIGITAL_TIME.equals(view.getClass().getName()) && defaultTheme(view)) {
            DigitalKeyguardFontHooks.track(view);
            return;
        }
        if (view instanceof TextView text && pluginText(view)) {
            View host = pluginHost(view);
            if (host != null && defaultTheme(host)) {
                if (DIGITAL_TEXT.equals(view.getClass().getName())) {
                    DigitalKeyguardFontHooks.track(host);
                    return;
                }
                Original original = views.get(text);
                if (original == null) { original = new Original(text, host); views.put(text, original); }
                apply(text, original);
            }
        }
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) discover(group.getChildAt(i));
    }

    public static Object captureHotReloadHosts() {
        ArrayList<View> hosts = new ArrayList<>();
        for (Original original : views.values()) {
            View host = original.host.get();
            if (host != null && !hosts.contains(host)) hosts.add(host);
        }
        hosts.addAll(DigitalKeyguardFontHooks.captureHosts());
        return hosts;
    }

    public static void restoreHotReloadHosts(Object state) {
        if (state instanceof java.util.List<?> hosts)
            for (Object host : hosts) if (host instanceof View view) discover(view);
        refresh();
    }

    static void cleanup() {
        active = false;
        main.removeCallbacksAndMessages(null);
        for (TextView view : new ArrayList<>(views.keySet())) restore(view, views.get(view));
        views.clear(); fonts.clear(); moduleContext = null;
        DigitalKeyguardFontHooks.cleanup();
    }
}
