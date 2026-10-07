package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** 只改变 QS 运营商视图和状态栏外置电量文字，不修改共享的时钟容器。 */
public final class StatusBarExtrasHooks {
    private static final WeakHashMap<View, Integer> carriers = new WeakHashMap<>();
    private static final WeakHashMap<TextView, CharSequence> percents = new WeakHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void hookCarrier(XC_LoadPackage.LoadPackageParam lp) {
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
            XposedHelpers.findAndHookDeclaredMethod(
                    "com.oplus.systemui.qs.widget.OplusSecondCarrierText", lp.classLoader,
                    "onAttachedToWindow", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            trackCarrier((View) p.thisObject);
                        }
                    });
        } catch (Throwable t) { log("QS carrier hook failed: " + t); }
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.oplus.systemui.plugins.qs.seamless.SeparateQSFakeStatusController", lp.classLoader);
            XposedBridge.hookAllMethods(controller, "onInit", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    // carrierContainer 实际是 clockContainer，不能整体隐藏。
                    Object entrance = XposedHelpers.getObjectField(p.thisObject, "quickEntranceContainerViewController");
                    trackCarrier((View) XposedHelpers.callMethod(entrance, "getCarrierView"));
                    trackCarrier((View) XposedHelpers.getObjectField(p.thisObject, "fakeCarrierContainer"));
                    trackCarrier((View) XposedHelpers.getObjectField(p.thisObject, "fakeKeyguardCarrierContainer"));
                }
            });
        } catch (Throwable t) { log("separate QS carrier hook failed: " + t); }
    }

    private static void trackCarrier(View view) {
        if (view == null) return;
        synchronized (carriers) {
            if (!carriers.containsKey(view)) carriers.put(view, view.getVisibility());
        }
        if (readBoolCached(KEY_QS_CARRIER_ENABLED, false)) view.setVisibility(View.GONE);
    }

    public static void hookPercent(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> binder = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.binder.BatteryViewBinder", lp.classLoader);
            XposedBridge.hookAllMethods(binder, "bind$updatePercentOutView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.args[0] instanceof TextView) || p.args[2] == null) return;
                    if (!XposedHelpers.getBooleanField(p.args[2], "isVisible")) return;
                    TextView view = (TextView) p.args[0];
                    CharSequence original = view.getText();
                    synchronized (percents) { percents.put(view, original); }
                    applyPercent(view, original, readBoolCached(KEY_HIDE_BATTERY_PERCENT_SIGN_ENABLED, false));
                }
            });
        } catch (Throwable t) { log("battery percent sign hook failed: " + t); }
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
                int visibility = hideCarrier ? View.GONE : entry.getValue();
                if (entry.getKey().getVisibility() != visibility) entry.getKey().setVisibility(visibility);
            }
        }
        synchronized (percents) {
            for (Map.Entry<TextView, CharSequence> entry : percents.entrySet()) {
                applyPercent(entry.getKey(), entry.getValue(), hidePercent);
            }
        }
    }

    public static Object captureHotReloadHosts() {
        ArrayList<Object[]> views = new ArrayList<>();
        synchronized (carriers) {
            for (Map.Entry<View, Integer> entry : carriers.entrySet())
                views.add(new Object[]{entry.getKey(), entry.getValue()});
        }
        synchronized (percents) {
            for (Map.Entry<TextView, CharSequence> entry : percents.entrySet())
                views.add(new Object[]{entry.getKey(), entry.getValue()});
        }
        return views;
    }

    public static void restoreHotReloadHosts(Object state) {
        if (!(state instanceof java.util.List<?>)) return;
        for (Object item : (java.util.List<?>) state) {
            Object[] entry = (Object[]) item;
            if (entry[1] instanceof Integer) {
                synchronized (carriers) { carriers.put((View) entry[0], (Integer) entry[1]); }
            } else {
                synchronized (percents) { percents.put((TextView) entry[0], (CharSequence) entry[1]); }
            }
        }
        refresh();
    }

    public static void cleanupForHotReload() {
        main.removeCallbacksAndMessages(null);
        apply(true);
        synchronized (carriers) { carriers.clear(); }
        synchronized (percents) { percents.clear(); }
    }
}
