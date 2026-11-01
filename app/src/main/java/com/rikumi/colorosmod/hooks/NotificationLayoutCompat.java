package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class NotificationLayoutCompat {
    private static final ConcurrentHashMap<Class<?>, Set<String>> FIELD_NAMES = new ConcurrentHashMap<>();
    static boolean userLocked(Object owner) {
        return XposedHelpers.getBooleanField(owner, hasField(owner.getClass(), "mUserLocked")
                ? "mUserLocked" : "mIsUserSwipingToExpandRow");
    }

    static void stateValue(Object state, String method, float value) {
        if (android.os.Build.VERSION.SDK_INT >= 37)
            XposedHelpers.callMethod(state, method, value, "ColorOSMod.notificationLayout");
        else XposedHelpers.callMethod(state, method, value);
    }
    static boolean hasField(Class<?> owner, String name) {
        // 此查询会随通知布局逐帧调用。新版已删除的字段不能每帧通过异常探测，
        // 按运行时类型一次收集继承链，同时缓存存在和不存在的结果。
        return FIELD_NAMES.computeIfAbsent(owner, type -> {
            Set<String> names = new HashSet<>();
            for (Class<?> current = type; current != null; current = current.getSuperclass())
                for (var field : current.getDeclaredFields()) names.add(field.getName());
            return names;
        }).contains(name);
    }

    static boolean hasMethod(Class<?> owner, String name) {
        for (var method : owner.getDeclaredMethods()) if (method.getName().equals(name)) return true;
        return false;
    }
    static String initDimens(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            for (var method : current.getDeclaredMethods())
                if (method.getName().startsWith("initDimens$") && method.getParameterCount() == 0)
                    return method.getName();
        throw new IllegalStateException("Notification children dimension initializer not found");
    }

    static void optionalHook(Class<?> owner, String name, Object... args) {
        for (var method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            XposedHelpers.findAndHookMethod(owner, name, args);
            return;
        }
    }

    static Object groupHeader(Object owner) {
        try { return XposedHelpers.getObjectField(owner, "opusGroupView"); }
        catch (NoSuchFieldError renamed) { return XposedHelpers.callMethod(owner, "getOplusExpandGroupHeaderView"); }
    }

    static void deoptimizeInflater(ClassLoader loader) {
        Class<?> legacy = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.row.NotificationContentInflater$$ExternalSyntheticLambda3", loader);
        if (legacy != null) XposedHelpers.findAndHookMethod(legacy, "invoke", new XC_MethodHook() {});
        else XposedBridge.hookAllMethods(XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.NotificationRowContentBinderImpl$Companion", loader),
                "beginInflationAsync", new XC_MethodHook() {});
    }

    static String legacyLayout(String name) {
        return switch (name) {
            case "notification_2025_template_header" -> "notification_template_header";
            case "notification_2025_template_collapsed_base" -> "notification_template_material_base";
            case "notification_2025_template_heads_up_base" -> "notification_template_material_heads_up_base";
            case "notification_2025_template_compact_heads_up_base" -> "notification_template_material_compact_heads_up_base";
            case "notification_2025_template_expanded_base" -> "notification_template_material_big_base";
            case "notification_2025_template_expanded_big_text" -> "notification_template_material_big_text";
            case "notification_2025_template_expanded_big_picture" -> "notification_template_material_big_picture";
            case "notification_2025_template_expanded_inbox" -> "notification_template_material_inbox";
            case "notification_2025_template_collapsed_messaging" -> "notification_template_material_messaging";
            case "notification_2025_template_expanded_messaging" -> "notification_template_material_big_messaging";
            case "notification_2025_template_compact_heads_up_messaging" -> "notification_template_material_messaging_compact_heads_up";
            case "notification_2025_template_collapsed_conversation", "notification_2025_template_expanded_conversation" -> "notification_template_material_conversation";
            case "notification_2025_template_collapsed_media" -> "notification_template_material_media";
            case "notification_2025_template_expanded_media" -> "notification_template_material_big_media";
            case "notification_2025_template_collapsed_call" -> "notification_template_material_call";
            case "notification_2025_template_expanded_call" -> "notification_template_material_big_call";
            case "notification_2025_right_icon" -> "notification_template_right_icon";
            case "notification_2025_conversation_face_pile_layout" -> "conversation_face_pile_layout";
            case "notification_2025_conversation_icon_container" -> "notification_template_conversation_icon_container";
            default -> name;
        };
    }
}
