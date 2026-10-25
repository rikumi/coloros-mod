package com.rikumi.colorosmod.hooks;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.*;

final class LegacyMessagingSpacingHooks {
    private record Avatar(int width, int start) { }
    private static final WeakHashMap<View, Avatar> avatars = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> headers = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> groupLayouts = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> avatarVisibility = new WeakHashMap<>();
    private static Class<?> groupClass;
    private static Boolean poolLegacy;
    private static boolean enabled() {
        return readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 1;
    }
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            groupClass = XposedHelpers.findClass("com.android.internal.widget.MessagingGroup", lp.classLoader);
            XposedHelpers.findAndHookMethod("android.app.Notification$MessagingStyle", lp.classLoader,
                    "getMessagingLayoutResource", boolean.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object builder = XposedHelpers.getObjectField(p.thisObject, "mBuilder");
                            android.content.Context context = (android.content.Context) XposedHelpers.getObjectField(builder, "mContext");
                            String name = Boolean.TRUE.equals(p.args[0]) ? "notification_template_material_conversation"
                                    : "notification_template_material_big_messaging";
                            int id = context.getResources().getIdentifier(name, "layout", "android");
                            if (id != 0) p.setResult(id);
                        }
                    });
            XposedHelpers.findAndHookMethod(groupClass, "getMessagingGroupLayoutResource", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    int id = android.content.res.Resources.getSystem().getIdentifier(
                            "notification_template_messaging_group", "layout", "android");
                    if (id != 0) p.setResult(id);
                }
            });
            XposedHelpers.findAndHookMethod(groupClass, "createGroup",
                    XposedHelpers.findClass("com.android.internal.widget.MessagingLinearLayout", lp.classLoader), new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            boolean legacy = enabled();
                            if (poolLegacy == null || poolLegacy != legacy) {
                                XposedHelpers.callStaticMethod(groupClass, "dropCache");
                                poolLegacy = legacy;
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(groupClass, "setShowingAvatar", boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    View avatar = (View) XposedHelpers.getObjectField(p.thisObject, "mMessagingIconContainer");
                    avatarVisibility.putIfAbsent(avatar, avatar.getVisibility());
                    int visible = Boolean.TRUE.equals(p.args[0]) ? View.VISIBLE : View.GONE;
                    if (avatar.getVisibility() != visible) avatar.setVisibility(visible);
                }
            });
            XposedHelpers.findAndHookMethod(groupClass, "onFinishInflate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    groupLayouts.put((View) p.thisObject, enabled());
                }
            });
            XposedHelpers.findAndHookMethod(groupClass, "recycle", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Boolean legacy = groupLayouts.get((View) p.thisObject);
                    if (legacy == null || legacy != enabled()) XposedHelpers.callStaticMethod(groupClass, "dropCache");
                }
            });
            XposedHelpers.findAndHookMethod("com.android.internal.widget.MessagingGroup", lp.classLoader,
                    "setIsInConversation", boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            View avatar = (View) XposedHelpers.getObjectField(p.thisObject, "mMessagingIconContainer");
                            if (Boolean.TRUE.equals(p.args[0])) {
                                avatars.remove(avatar); // Native conversation geometry is already restored.
                                return;
                            }
                            int inset = avatar.getPaddingStart();
                            ViewGroup.LayoutParams params = avatar.getLayoutParams();
                            if (inset <= 0 || params == null || params.width <= inset) return;

                            avatars.put(avatar, new Avatar(params.width, inset));
                            params.width -= inset;
                            avatar.setLayoutParams(params);
                            avatar.setPaddingRelative(0, avatar.getPaddingTop(), avatar.getPaddingEnd(), avatar.getPaddingBottom());
                        }
                    });
            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationMessagingTemplateViewWrapperExImpl",
                    lp.classLoader, "onContentUpdated", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {

                            if (enabled()) p.setResult(null);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object base = XposedHelpers.callMethod(p.thisObject, "getBase");
                            Object value = XposedHelpers.getObjectField(base, "mNotificationHeader");
                            if (!(value instanceof View header)) return;
                            View root = (View) XposedHelpers.getObjectField(base, "mView");
                            int id = NotificationResourceIds.get(root.getResources(), "notification_main_column", "id", "android");
                            View column = id == 0 ? null : root.findViewById(id);
                            int end = column != null && column.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params
                                    ? params.getMarginEnd() : 0;
                            if (end <= 0) end = Math.round(16f * header.getResources().getDisplayMetrics().density);
                            headers.putIfAbsent(header, header.getPaddingEnd());
                            if (header.getPaddingEnd() != end)
                                header.setPaddingRelative(header.getPaddingStart(), header.getPaddingTop(), end, header.getPaddingBottom());
                        }
                    });
        } catch (Throwable error) { log("legacy messaging spacing hook failed: " + error); }
    }
    static void cleanup() {
        if (groupClass != null) XposedHelpers.callStaticMethod(groupClass, "dropCache");
        poolLegacy = null;
        for (View avatar : new ArrayList<>(avatars.keySet())) {
            Avatar original = avatars.get(avatar);
            ViewGroup.LayoutParams params = avatar.getLayoutParams();
            if (params != null) { params.width = original.width; avatar.setLayoutParams(params); }
            avatar.setPaddingRelative(original.start, avatar.getPaddingTop(), avatar.getPaddingEnd(), avatar.getPaddingBottom());
        }
        avatars.clear();
        for (var item : avatarVisibility.entrySet()) item.getKey().setVisibility(item.getValue());
        avatarVisibility.clear();
        for (View header : new ArrayList<>(headers.keySet()))
            header.setPaddingRelative(header.getPaddingStart(), header.getPaddingTop(), headers.get(header), header.getPaddingBottom());
        headers.clear();
    }
}
