package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.widget.TextView;
import com.rikumi.colorosmod.R;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Change only the presentation of the ROM's About device specifications. */
public final class AboutDeviceMarketingHooks {
    public static final String KEY = "about_device_hide_marketing_enabled";
    private static final String INFO = "com.oplus.settings.feature.deviceinfo.";
    private static final String[] PLATFORM_KEYS = {
            "tidal_architecture", "tidal_architecture_other", "tidal_architecture_humming" };
    private static final Pattern POWER = Pattern.compile("(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s*[wWＷ](?![A-Za-z])");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Boolean> preferences = new WeakHashMap<>();
    private static final Map<Object, Boolean> platforms = new WeakHashMap<>();
    private static final Map<Object, CharSequence> softwareVersions = new WeakHashMap<>();
    private static final Map<View, Binding> bindings = new WeakHashMap<>();
    private static boolean active = true, settingVisibility;
    private static final Runnable REFRESH = AboutDeviceMarketingHooks::refresh;

    private static boolean enabled() { return active && readBoolCached(KEY, false); }

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        for (String name : new String[]{ "DeviceBatteryCapacityItemPreference", "DeviceCameraInfoItemPreference",
                "DeviceChargeInfoItemPreference", "DeviceScreenInfoItemPreference" }) {
            try {
                Class<?> preference = XposedHelpers.findClass(INFO + name, lp.classLoader);
                XposedBridge.hookAllMethods(preference, "onBindViewHolder", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        View root = (View) XposedHelpers.getObjectField(p.args[0], "itemView");
                        Binding old = bindings.remove(root);
                        if (old != null) old.restore();
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable()) return;
                        preferences.put(p.thisObject, Boolean.TRUE);
                        if (!enabled()) return;
                        View root = (View) XposedHelpers.getObjectField(p.args[0], "itemView");
                        Binding binding = new Binding();
                        try {
                            apply(name, root, lp.classLoader, binding);
                            bindings.put(root, binding);
                        } catch (Throwable error) {
                            binding.restore();
                            log("about device marketing bind: " + error);
                        }
                    }
                });
            } catch (Throwable error) { log("about device marketing unavailable: " + name + ": " + error); }
        }
        try {
            Class<?> controller = XposedHelpers.findClass(INFO + "controller.OplusBuildNumberPreferenceController", lp.classLoader);
            XposedBridge.hookAllMethods(controller, "putBuildNumber", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !"phone_build_number".equals(p.args[1]) || p.args[0] == null) return;
                    try {
                        Object preference = p.args[0];
                        CharSequence original = (CharSequence) XposedHelpers.callMethod(preference, "getAssignment");
                        softwareVersions.put(preference, original);
                        if (enabled()) XposedHelpers.callMethod(preference, "setAssignment", singleLine(original));
                    } catch (Throwable error) { log("about device software version formatting: " + error); }
                }
            });
        } catch (Throwable error) { log("about device software version unavailable: " + error); }
        try {
            Class<?> fragment = XposedHelpers.findClass(INFO + "aboutphone.DeviceInfoFragment", lp.classLoader);
            XC_MethodHook page = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object screen = XposedHelpers.callMethod(p.thisObject, "getPreferenceScreen");
                    if (screen == null) return;
                    for (String key : PLATFORM_KEYS) {
                        Object preference = XposedHelpers.callMethod(screen, "findPreference", key);
                        if (preference == null) continue;
                        if (!platforms.containsKey(preference))
                            platforms.put(preference, (Boolean) XposedHelpers.callMethod(preference, "isVisible"));
                        applyVisibility(preference);
                    }
                }
            };
            XposedBridge.hookAllMethods(fragment, "onCreate", page);
            XposedBridge.hookAllMethods(fragment, "onResume", page);
            XposedHelpers.findAndHookMethod("androidx.preference.Preference", lp.classLoader,
                    "setVisible", boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (settingVisibility || !platforms.containsKey(p.thisObject)) return;
                            platforms.put(p.thisObject, (Boolean) p.args[0]);
                            if (enabled()) p.args[0] = false;
                        }
                    });
        } catch (Throwable error) { log("about device marketing platform visibility: " + error); }
    }

    private static void apply(String name, View root, ClassLoader loader, Binding binding) throws Exception {
        switch (name) {
            case "DeviceBatteryCapacityItemPreference" -> {
                binding.hide(find(root, "battery_type"));
                // 原生右侧区域嵌套两层 LinearLayout；去掉名称后按整行中心对齐，
                // 避免最小行高与默认基线对齐仍把容量留在上方。
                View capacity = find(root, "battery_capacity");
                for (View view = capacity; view != null; ) {
                    if (view instanceof LinearLayout layout) binding.centerVertically(layout);
                    if (view == root) break;
                    view = view.getParent() instanceof View parent ? parent : null;
                }
            }
            case "DeviceCameraInfoItemPreference" -> {
                binding.hide(find(root, "device_camera_lumo_lin"));
                TextView front = text(root, "device_camera_info_front_content");
                TextView back = text(root, "device_camera_info_back_content");
                View frontRow = find(root, "device_camera_info_front_lin");
                View backRow = find(root, "device_camera_info_ly");
                String first = cameraValue(front, frontRow), second = cameraValue(back, backRow);
                if (first != null && second != null) {
                    int rearId = root.getResources().getIdentifier("device_camera_info_back", "string", root.getContext().getPackageName());
                    String rearLabel = rearId == 0 ? null : root.getContext().getString(rearId);
                    // 部分机型原生会交换两个 TextView 的内容，按本地化的后置标题判断。
                    binding.text(front, rearFirst(first, second, rearLabel));
                    binding.hide(backRow != null ? backRow : back);
                } else if (first != null) binding.text(front, first);
                else if (second != null) binding.text(back, second);
                for (String row : new String[]{"device_camera_info_front_lin", "device_camera_info_ly"}) {
                    View firstVisibleRow = find(root, row);
                    if (firstVisibleRow != null && firstVisibleRow.getVisibility() == View.VISIBLE) {
                        binding.topMargin(firstVisibleRow, 0);
                        break;
                    }
                }
            }
            case "DeviceChargeInfoItemPreference" -> {
                TextView wired = text(root, "text_wired_charging"), wireless = text(root, "text_wireless_charging");
                String first = visiblePower(wired), second = visiblePower(wireless);
                if (first == null && second == null) return;
                Context module = root.getContext().createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                        .createConfigurationContext(root.getResources().getConfiguration());
                ArrayList<String> parts = new ArrayList<>();
                if (first != null) parts.add(module.getString(R.string.ui_about_device_wired_power, first));
                if (second != null) parts.add(module.getString(R.string.ui_about_device_wireless_power, second));
                TextView target = first != null ? wired : wireless;
                // Preserve an unfamiliar or redacted native value instead of silently dropping it.
                TextView other = target == wired ? wireless : wired;
                if (other != null && other.getVisibility() == View.VISIBLE && visiblePower(other) == null) return;
                binding.text(target, String.join(" / ", parts));
                binding.hide(other);
            }
            case "DeviceScreenInfoItemPreference" -> {
                Context host = root.getContext();
                Class<?> settings = XposedHelpers.findClass("com.oplus.settings.utils.SettingsUtils", loader);
                if (Boolean.TRUE.equals(XposedHelpers.callStaticMethod(settings, "isPhoneInfoEncryption", host))) return;
                Class<?> info = XposedHelpers.findClass(INFO + "DeviceInfoUtils", loader);
                Object size = XposedHelpers.callStaticMethod(info, "getDisplayScreenPhysicString", host);
                if (size instanceof String value && !value.isBlank()) {
                    binding.text(text(root, "text_size"), value);
                    binding.hide(find(root, "text_eye"));
                }
            }
        }
    }

    static String wattage(CharSequence value) {
        if (value == null) return null;
        Matcher match = POWER.matcher(value);
        return match.find() ? match.group(1) + "W" : null;
    }

    static CharSequence singleLine(CharSequence value) {
        return value == null ? null : value.toString().replaceAll("\\h*\\R[\\h\\v]*", " ");
    }

    static String cameraDescription(CharSequence original) {
        CharSequence line = singleLine(original);
        if (line == null) return null;
        String value = line.toString().replaceAll("(?<=\\d)00\\h*[万萬](?:像素|畫素)", "MP");
        Matcher repeated = Pattern.compile("(?<![\\d.])(\\d+(?:\\.\\d+)?MP)(?:\\h*[+＋]\\h*\\1(?=\\h*(?:[+＋]|$)))+").matcher(value);
        StringBuffer compact = new StringBuffer();
        while (repeated.find()) {
            String replacement = repeated.group(1) + "×" + repeated.group().split("[+＋]").length;
            repeated.appendReplacement(compact, Matcher.quoteReplacement(replacement));
        }
        repeated.appendTail(compact);
        return compact.toString();
    }

    static String rearFirst(String first, String second, String rearLabel) {
        return rearLabel != null && !rearLabel.isEmpty() && first.startsWith(rearLabel)
                ? first + " / " + second : second + " / " + first;
    }

    private static String cameraValue(TextView label, View row) {
        if (label == null || label.getVisibility() != View.VISIBLE
                || (row != null && row.getVisibility() != View.VISIBLE)) return null;
        String value = cameraDescription(label.getText());
        return value == null || value.isBlank() ? null : value;
    }

    private static String visiblePower(TextView view) {
        return view != null && view.getVisibility() == View.VISIBLE ? wattage(view.getText()) : null;
    }

    private static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }
    private static TextView text(View root, String name) {
        View view = find(root, name);
        return view instanceof TextView ? (TextView) view : null;
    }

    private static final class Change {
        final WeakReference<View> view;
        final int visibility;
        final CharSequence text;
        Integer topMargin, gravity;
        Boolean baselineAligned;
        Change(View target) {
            view = new WeakReference<>(target);
            visibility = target.getVisibility();
            text = target instanceof TextView label ? label.getText() : null;
        }
        void restore() {
            View target = view.get();
            if (target == null) return;
            if (target instanceof TextView label) label.setText(text);
            target.setVisibility(visibility);
            if (gravity != null && target instanceof LinearLayout layout) {
                layout.setGravity(gravity);
                layout.setBaselineAligned(baselineAligned);
            }
            if (topMargin != null && target.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
                margins.topMargin = topMargin;
                target.setLayoutParams(margins);
            }
        }
    }
    private static final class Binding {
        final ArrayList<Change> changes = new ArrayList<>();
        Change remember(View view) {
            for (Change change : changes) if (change.view.get() == view) return change;
            Change change = new Change(view);
            changes.add(change);
            return change;
        }
        void hide(View view) { if (view != null) { remember(view); view.setVisibility(View.GONE); } }
        void text(TextView view, String value) { if (view != null) { remember(view); view.setText(value); } }
        void centerVertically(LinearLayout view) {
            Change change = remember(view);
            if (change.gravity == null) {
                change.gravity = view.getGravity();
                change.baselineAligned = view.isBaselineAligned();
            }
            view.setBaselineAligned(false);
            view.setGravity((view.getGravity() & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL);
        }
        void topMargin(View view, int value) {
            if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins && margins.topMargin != value) {
                Change change = remember(view);
                if (change.topMargin == null) change.topMargin = margins.topMargin;
                margins.topMargin = value;
                view.setLayoutParams(margins);
            }
        }
        void restore() { for (Change change : changes) change.restore(); }
    }

    private static void applyVisibility(Object preference) {
        Boolean original = platforms.get(preference);
        if (original == null) return;
        settingVisibility = true;
        try { XposedHelpers.callMethod(preference, "setVisible", enabled() ? false : original); }
        finally { settingVisibility = false; }
    }
    public static void onSettingsSnapshotPublished() {
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }
    private static void refresh() {
        for (Binding binding : bindings.values()) binding.restore();
        bindings.clear();
        for (var entry : new ArrayList<>(softwareVersions.entrySet())) {
            try {
                CharSequence value = entry.getValue();
                XposedHelpers.callMethod(entry.getKey(), "setAssignment", enabled() ? singleLine(value) : value);
            } catch (Throwable error) { log("about device software version refresh: " + error); }
        }
        for (Object preference : new ArrayList<>(platforms.keySet())) {
            try { applyVisibility(preference); }
            catch (Throwable error) { log("about device marketing visibility refresh: " + error); }
        }
        for (Object preference : new ArrayList<>(preferences.keySet())) {
            try { XposedHelpers.callMethod(preference, "notifyChanged"); }
            catch (Throwable error) { log("about device marketing refresh: " + error); }
        }
    }
    public static void cleanupForHotReload() {
        active = false;
        MAIN.removeCallbacks(REFRESH);
        refresh();
        platforms.clear(); preferences.clear(); softwareVersions.clear();
    }
    private AboutDeviceMarketingHooks() {}
}
