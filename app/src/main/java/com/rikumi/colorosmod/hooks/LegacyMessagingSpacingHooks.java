package com.rikumi.colorosmod.hooks;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.*;

/** Remove the current framework's extra non-conversation avatar inset on old templates. */
final class LegacyMessagingSpacingHooks {
    private record Avatar(int width, int start) { }
    private static final WeakHashMap<View, Avatar> avatars = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> headers = new WeakHashMap<>();
    private static boolean enabled() {
        return readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 1;
    }
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
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
                            // Native setIsInConversation resets both width and padding.
                            // Remove the same amount from both, preserving avatar size/end gap.
                            avatars.put(avatar, new Avatar(params.width, inset));
                            params.width -= inset;
                            avatar.setLayoutParams(params);
                            avatar.setPaddingRelative(0, avatar.getPaddingTop(), avatar.getPaddingEnd(), avatar.getPaddingBottom());
                        }
                    });
            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationMessagingTemplateViewWrapperExImpl",
                    lp.classLoader, "onContentUpdated", new XC_MethodHook() {
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
        for (View avatar : new ArrayList<>(avatars.keySet())) {
            Avatar original = avatars.get(avatar);
            ViewGroup.LayoutParams params = avatar.getLayoutParams();
            if (params != null) { params.width = original.width; avatar.setLayoutParams(params); }
            avatar.setPaddingRelative(original.start, avatar.getPaddingTop(), avatar.getPaddingEnd(), avatar.getPaddingBottom());
        }
        avatars.clear();
        for (View header : new ArrayList<>(headers.keySet()))
            header.setPaddingRelative(header.getPaddingStart(), header.getPaddingTop(), headers.get(header), header.getPaddingBottom());
        headers.clear();
    }
}
