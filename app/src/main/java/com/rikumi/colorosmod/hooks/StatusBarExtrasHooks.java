package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

public final class StatusBarExtrasHooks {
    private static final WeakHashMap<View, Integer> carriers = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> legacyCarriers = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> applyingCarrierVisibility = new ThreadLocal<>();
    private static final WeakHashMap<TextView, CharSequence> percents = new WeakHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void hookCarrier(XC_LoadPackage.LoadPackageParam lp) {
        QsCarrierRows.hook(lp);
        if (android.os.Build.VERSION.SDK_INT <= 35) hookLegacyCarrier(lp);
        try {
            XposedHelpers.findAndHookDeclaredMethod(
                    "com.oplus.systemui.qs.OplusQuickStatusBarHeader", lp.classLoader,
                    "onFinishInflate", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View header = (View) p.thisObject;
                            for (String name : new String[]{"qs_carrier_text", "carrier_group"}) {
                                int id = header.getResources().getIdentifier(name, "id", "com.android.systemui");
                                if (id != 0) trackCarrier(header.findViewById(id));
                            }
                        }
                    });
        } catch (Throwable t) { log("QS header carrier hook failed: " + t); }
        String[] labels = android.os.Build.VERSION.SDK_INT >= 37
                ? new String[]{"OplusSecondCarrierText", "OplusQSCarrierText"}
                : new String[]{"OplusSecondCarrierText"};
        for (String label : labels) {
            Class<?> type = XposedHelpers.findClassIfExists("com.oplus.systemui.qs.widget." + label, lp.classLoader);
            if (type == null) continue;
            XposedBridge.hookAllMethods(type, "onAttachedToWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) trackCarrier((View) p.thisObject);
                }
            });
        }
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.oplus.systemui.plugins.qs.seamless.SeparateQSFakeStatusController", lp.classLoader);
            XposedBridge.hookAllMethods(controller, "onInit", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (android.os.Build.VERSION.SDK_INT >= 37) QsHeaderVisibility.trackClockViews(p.thisObject);
                    // 先获取运营商文字；共享信息行由两个隐藏开关共同控制。
                    try {
                        Object entrance = XposedHelpers.getObjectField(p.thisObject, "quickEntranceContainerViewController");
                        if (entrance != null) trackCarrier((View) XposedHelpers.callMethod(entrance, "getCarrierView"));
                    } catch (NoSuchMethodError | NoSuchFieldError ignored) { }
                    // 不同版本的入口组件不一定提供 carrier getter；其余运营商视图独立处理。
                    String[] containers = android.os.Build.VERSION.SDK_INT >= 37
                            ? new String[]{"carrierContainer", "fakeCarrierContainer", "fakeKeyguardCarrierContainer"}
                            : new String[]{"fakeCarrierContainer", "fakeKeyguardCarrierContainer"};
                    for (String name : containers) {
                        try { trackCarrier((View) XposedHelpers.getObjectField(p.thisObject, name)); }
                        catch (NoSuchFieldError ignored) { }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 37)
                        QsCarrierRows.trackController(p.thisObject, readBoolCached(KEY_QS_CARRIER_ENABLED, false));
                }
            });
        } catch (Throwable t) { log("separate QS carrier hook failed: " + t); }
    }

    private static void hookLegacyCarrier(XC_LoadPackage.LoadPackageParam lp) {
        try {
            String widget = "com.oplus.systemui.qs.widget.OplusQSCarrierText";
            XposedHelpers.findClass(widget, lp.classLoader);
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(applyingCarrierVisibility.get())) return;
                    synchronized (carriers) {
                        if (!legacyCarriers.containsKey(p.thisObject)) return;
                        // ConstraintSet and SIM updates must keep supplying the native state, even while hidden.
                        carriers.put((View) p.thisObject, (Integer) p.args[0]);
                    }
                    if (readBoolCached(KEY_QS_CARRIER_ENABLED, false)) p.args[0] = View.GONE;
                }
            });
            XposedHelpers.findAndHookDeclaredMethod(widget, lp.classLoader, "onAttachedToWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { trackLegacyCarrier((View) p.thisObject); }
            });
            XposedHelpers.findAndHookDeclaredMethod("com.oplus.systemui.qs.OplusQSFooterImpl", lp.classLoader,
                    "onFinishInflate", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            trackLegacyCarrier((View) XposedHelpers.getObjectField(p.thisObject, "mOplusQSCarrier"));
                        }
                    });
        } catch (Throwable error) { log("legacy QS footer carrier hook failed: " + error); }
    }

    private static void trackLegacyCarrier(View view) {
        if (view == null) return;
        synchronized (carriers) { legacyCarriers.put(view, true); }
        trackCarrier(view);
    }

    private static void setCarrierVisibility(View view, int visibility) {
        Boolean previous = applyingCarrierVisibility.get();
        applyingCarrierVisibility.set(true);
        try { view.setVisibility(visibility); }
        finally {
            if (previous == null) applyingCarrierVisibility.remove();
            else applyingCarrierVisibility.set(previous);
        }
    }

    private static void trackCarrier(View view) {
        if (view == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            if (view instanceof ViewGroup group) {
                QsCarrierRows.track(group, readBoolCached(KEY_QS_CARRIER_ENABLED, false));
                for (int i = 0; i < group.getChildCount(); i++) trackCarrier(group.getChildAt(i));
                return;
            }
            if (!QsCarrierTextTargets.isCarrierText(view)) return;
        }
        synchronized (carriers) {
            if (!carriers.containsKey(view)) carriers.put(view, view.getVisibility());
        }
        if (readBoolCached(KEY_QS_CARRIER_ENABLED, false)) setCarrierVisibility(view, View.GONE);
        if (android.os.Build.VERSION.SDK_INT >= 37)
            QsCarrierRows.discoverParents(view, readBoolCached(KEY_QS_CARRIER_ENABLED, false));
    }

    public static void hookPercent(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> binder = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.binder.BatteryViewBinder", lp.classLoader);
            XposedBridge.hookAllMethods(binder, "bind$updatePercentOutView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof TextView)) return;
                    Object percentOutIcon = findPercentOutIcon(p.args);
                    if (percentOutIcon == null || !XposedHelpers.getBooleanField(percentOutIcon, "isVisible")) return;
                    TextView view = (TextView) p.args[0];
                    CharSequence original = view.getText();
                    synchronized (percents) { percents.put(view, original); }
                    applyPercent(view, original, readBoolCached(KEY_HIDE_BATTERY_PERCENT_SIGN_ENABLED, false));
                }
            });
        } catch (Throwable t) { log("battery percent sign hook failed: " + t); }
    }

    private static Object findPercentOutIcon(Object[] args) {
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                XposedHelpers.getBooleanField(arg, "isVisible");
                return arg;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void applyPercent(TextView view, CharSequence original, boolean hide) {
        // 保留系统数字格式，不影响电池内数字与无障碍电量描述。
        CharSequence text = hide ? original.toString().replace("%", "").replace("\u066a", "")
                .replace("\uff05", "").trim() : original;
        if (!android.text.TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    public static void refresh() { main.post(() -> apply(false)); }

    private static void apply(boolean restore) {
        boolean hideCarrier = !restore && readBoolCached(KEY_QS_CARRIER_ENABLED, false);
        boolean hidePercent = !restore && readBoolCached(KEY_HIDE_BATTERY_PERCENT_SIGN_ENABLED, false);
        synchronized (carriers) {
            for (Map.Entry<View, Integer> entry : carriers.entrySet()) {
                boolean label = android.os.Build.VERSION.SDK_INT < 37 || QsCarrierTextTargets.isCarrierText(entry.getKey());
                int visibility = hideCarrier && label ? View.GONE : entry.getValue();
                if (entry.getKey().getVisibility() != visibility) setCarrierVisibility(entry.getKey(), visibility);
            }
        }
        synchronized (percents) {
            for (Map.Entry<TextView, CharSequence> entry : percents.entrySet()) {
                applyPercent(entry.getKey(), entry.getValue(), hidePercent);
            }
        }
        QsCarrierRows.refresh(hideCarrier);
    }

    public static Object captureHotReloadHosts() {
        ArrayList<Object[]> views = new ArrayList<>();
        synchronized (carriers) {
            for (Map.Entry<View, Integer> entry : carriers.entrySet())
                views.add(new Object[]{entry.getKey(), entry.getValue(), legacyCarriers.containsKey(entry.getKey())});
        }
        synchronized (percents) {
            for (Map.Entry<TextView, CharSequence> entry : percents.entrySet())
                views.add(new Object[]{entry.getKey(), entry.getValue()});
        }
        views.add(new Object[]{"carrier_rows", QsCarrierRows.capture()});
        return views;
    }

    public static void restoreHotReloadHosts(Object state) {
        if (!(state instanceof java.util.List<?>)) return;
        for (Object item : (java.util.List<?>) state) {
            Object[] entry = (Object[]) item;
            if ("carrier_rows".equals(entry[0])) {
                QsCarrierRows.restore(entry[1], readBoolCached(KEY_QS_CARRIER_ENABLED, false));
                continue;
            }
            if (entry[1] instanceof Integer) {
                if (android.os.Build.VERSION.SDK_INT >= 37) {
                    // Earlier versions saved whole carrier rows, which may also contain the date.
                    View view = (View) entry[0];
                    setCarrierVisibility(view, (Integer) entry[1]);
                    if (QsCarrierTextTargets.isCarrierText(view)) {
                        synchronized (carriers) { carriers.put(view, (Integer) entry[1]); }
                    }
                    trackCarrier(view);
                    continue;
                }
                synchronized (carriers) {
                    carriers.put((View) entry[0], (Integer) entry[1]);
                    if (entry.length > 2 && Boolean.TRUE.equals(entry[2])) legacyCarriers.put((View) entry[0], true);
                }
            } else {
                synchronized (percents) { percents.put((TextView) entry[0], (CharSequence) entry[1]); }
            }
        }
        refresh();
    }

    public static void cleanupForHotReload() {
        main.removeCallbacksAndMessages(null);
        apply(true);
        QsCarrierRows.cleanup();
        synchronized (carriers) { carriers.clear(); legacyCarriers.clear(); }
        synchronized (percents) { percents.clear(); }
    }
}
