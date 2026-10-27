package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

public final class MonochromeNotificationHooks {
    private static final String KEY = "statusbar_monochrome_notification_icons_enabled";
    private static final String MANAGER = "com.android.systemui.statusbar.notification.icon.IconManager";
    private static final Map<View, State> STATES = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean active = true;
    private static boolean replaying;
    private static boolean lastEnabled;
    private static WeakReference<Object> lastManager = new WeakReference<>(null);

    private static final class State {
        final WeakReference<Object> manager;
        final WeakReference<Object> entry;
        final Object original;
        Object replacement;
        boolean checked;
        int configuration;
        WeakReference<Notification> notification = new WeakReference<>(null);
        State(Object manager, Object entry, Object original, Object replacement) {
            this.manager = new WeakReference<>(manager);
            this.entry = new WeakReference<>(entry);
            this.original = original;
            this.replacement = replacement;
        }
    }

    public static void hook(XC_LoadPackage.LoadPackageParam param) {
        try {
            Class<?> entry = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.collection.NotificationEntry", param.classLoader);
            Class<?> descriptor = XposedHelpers.findClass("com.android.internal.statusbar.StatusBarIcon", param.classLoader);
            Class<?> view = XposedHelpers.findClass("com.android.systemui.statusbar.StatusBarIconView", param.classLoader);
            XposedHelpers.findAndHookMethod(MANAGER, param.classLoader, "setIcon", entry, descriptor, view,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!active || replaying) return;
                            try {
                                lastManager = new WeakReference<>(p.thisObject);
                                Object pack = XposedHelpers.callMethod(p.args[0], "getIcons");
                                if (p.args[2] != XposedHelpers.callMethod(pack, "getStatusBarIcon")
                                        && p.args[2] != XposedHelpers.callMethod(pack, "getStatusBarChipIcon")) return;
                                View target = (View) p.args[2];
                                State state = new State(p.thisObject, p.args[0], p.args[1], null);
                                STATES.put(target, state);
                                boolean enabled = readBool(KEY, false);
                                Object replacement = enabled ? replacementForView(p.thisObject, p.args[0], target) : null;
                                state.replacement = replacement;
                                if (enabled) rememberCheck(state, p.args[0], target);
                                if (enabled && replacement != null) p.args[1] = replacement;
                            } catch (Throwable t) { log("monochrome notification resolve failed: " + t); }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            State state = STATES.get(p.args[2]);
                            if (active && readBool(KEY, false) && state != null
                                    && state.replacement != null && p.args[1] == state.replacement) {
                                XposedHelpers.callMethod(p.args[2], "setShowsConversation", false);
                            }
                            if (active && !replaying && readBool(KEY, false)) MissingNotificationCountHooks.refresh();
                            if (state != null && p.args[2] instanceof View target) {
                                target.invalidate();
                                if (target.getParent() instanceof View parent) parent.requestLayout();
                            }
                        }
                    });
            // createIcons installs IconPack only AFTER the first four setIcon calls.
            XposedHelpers.findAndHookMethod(MANAGER, param.classLoader, "createIcons", entry,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.hasThrowable()) return;
                            try {
                                Object pack = XposedHelpers.callMethod(p.args[0], "getIcons");
                                for (String getter : new String[] { "getStatusBarIcon", "getStatusBarChipIcon" }) {
                                    Object target = XposedHelpers.callMethod(pack, getter);
                                    if (target != null) XposedHelpers.callMethod(p.thisObject, "setIcon", p.args[0],
                                            XposedHelpers.callMethod(target, "getStatusBarIcon"), target);
                                }
                            } catch (Throwable t) { log("monochrome notification create failed: " + t); }
                        }
                    });
            log("HOOK OK IconManager#createIcons/setIcon (status bar monochrome notification icons)");
        } catch (Throwable t) { log("HOOK FAIL status bar monochrome notification icons: " + t); }
        MessagesNotificationIconHooks.hook(param);
        MissingNotificationCountHooks.hook(param);
        MonochromeIconGeometryHooks.hook(param);
        MonochromeIconTintHooks.hook(param);
        NotificationBadgeGeometryHooks.hook(param);
    }

    static boolean enabled() { return active && readBool(KEY, false); }

    static boolean enlargedIcon(View view) {
        State state = STATES.get(view);
        return enabled() && state != null && state.replacement != null;
    }

    static Object entryForKey(String key) {
        Object manager = lastManager.get();
        if (manager == null) return null;
        return XposedHelpers.callMethod(XposedHelpers.getObjectField(manager, "notifCollection"), "getEntry", key);
    }

    static boolean hasUsableIcon(Object entry) {
        Object manager = lastManager.get();
        if (manager == null || entry == null) return false;
        Object pack = XposedHelpers.callMethod(entry, "getIcons");
        Object view = XposedHelpers.callMethod(pack, "getStatusBarIcon");
        if (!(view instanceof View)) return false;
        State state = STATES.get(view);
        Notification notification = ((StatusBarNotification) XposedHelpers.callMethod(entry, "getSbn")).getNotification();
        int config = ((View) view).getResources().getConfiguration().hashCode();
        if (state != null && state.entry.get() == entry && state.checked
                && state.notification.get() == notification && state.configuration == config)
            return state.replacement != null;
        Object icon = replacement(manager, entry, (View) view);
        if (state != null) {
            state.replacement = icon;
            rememberCheck(state, entry, (View) view);
            if (enabled() && icon != null) {
                boolean previousReplay = replaying;
                replaying = true;
                try { XposedHelpers.callMethod(manager, "setIcon", entry, icon, view); }
                finally { replaying = previousReplay; }
            }
        }
        return icon != null;
    }

    private static void rememberCheck(State state, Object entry, View view) {
        state.checked = true;
        state.notification = new WeakReference<>(((StatusBarNotification)
                XposedHelpers.callMethod(entry, "getSbn")).getNotification());
        state.configuration = view.getResources().getConfiguration().hashCode();
    }

    private static Object replacement(Object manager, Object entry, View view) {
        StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.callMethod(entry, "getSbn");
        Icon icon = notificationMask(view.getContext(), sbn);
        if (icon == null) return null;
        Class<?> type = XposedHelpers.findClass("com.android.internal.statusbar.StatusBarIcon$Type",
                manager.getClass().getClassLoader());
        return XposedHelpers.callMethod(manager, "toStatusBarIcon", icon, entry,
                XposedHelpers.getStaticObjectField(type, "NotifSmallIcon"));
    }

    static Icon notificationMask(Context host, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        boolean substituted = notification.extras != null
                && notification.extras.getBoolean("oplus_smallicon_use_app_icon", false);
        Icon traced = MessagesNotificationIconHooks.iconFor(sbn);
        Icon icon = traced != null ? traced : substituted
                ? notification.extras.getParcelable("oplus_small_icon", Icon.class)
                : notification.getSmallIcon();
        if (icon == null || icon.getType() == Icon.TYPE_ADAPTIVE_BITMAP
                || icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP) return null;
        ApplicationInfo info = notification.extras == null ? null
                : notification.extras.getParcelable("android.appInfo", ApplicationInfo.class);
        // Launcher resources remain launcher icons even when their pixels happen to be gray.
        if (info != null && icon.getType() == Icon.TYPE_RESOURCE && icon.getResId() == info.icon
                && sbn.getPackageName().equals(icon.getResPackage())) return null;
        Context context;
        try { context = sbn.getUserId() == -1 ? host.createPackageContext(sbn.getPackageName(), 0)
                : (Context) XposedHelpers.callMethod(host,
                        "createPackageContextAsUser", sbn.getPackageName(), 0, sbn.getUser()); }
        catch (Throwable t) { return null; }
        icon = nativeNotificationLayer(context, sbn, icon);
        Icon systemMask = SystemNotificationIconMasks.resolve(context, sbn, icon);
        if (systemMask != null) icon = systemMask;
        if (!isNotificationMask(icon.loadDrawable(context), notification.iconLevel)) return null;
        return icon;
    }

    private static Object replacementForView(Object manager, Object entry, View view) {
        Object icon = replacement(manager, entry, view);
        if (icon != null) return icon;
        Object pack = XposedHelpers.callMethod(entry, "getIcons");
        if (view != XposedHelpers.callMethod(pack, "getStatusBarChipIcon")) return null;
        // Call chips are controls, not a second missing-notification badge. Use their native fallback symbol.
        ClassLoader loader = manager.getClass().getClassLoader();
        Object phone = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(
                "com.android.systemui.statusbar.chips.call.ui.viewmodel.CallChipViewModel", loader), "phoneIcon");
        int resource = (Integer) XposedHelpers.callMethod(phone, "getRes");
        Class<?> type = XposedHelpers.findClass("com.android.internal.statusbar.StatusBarIcon$Type", loader);
        return XposedHelpers.callMethod(manager, "toStatusBarIcon",
                Icon.createWithResource((resource >>> 24) == 1 ? "android" : "com.android.systemui", resource), entry,
                XposedHelpers.getStaticObjectField(type, "NotifSmallIcon"));
    }

    private static Icon nativeNotificationLayer(Context context, StatusBarNotification sbn, Icon icon) {
        if (!"com.android.settings".equals(sbn.getPackageName()) || icon.getType() != Icon.TYPE_RESOURCE
                || !sbn.getPackageName().equals(icon.getResPackage())) return icon;
        String name = context.getResources().getResourceEntryName(icon.getResId());
        String layer;
        switch (name) {
            case "phone_anti_theft_no_icon": layer = "phone_anti_theft_icon"; break;
            case "breathing_light_multi_led_ic": layer = "breathing_light_multi_led_in"; break;
            default: return icon;
        }
        int id = context.getResources().getIdentifier(layer, "drawable", sbn.getPackageName());
        return id == 0 ? icon : Icon.createWithResource(sbn.getPackageName(), id);
    }

    private static boolean isNotificationMask(Drawable drawable, int level) {
        if (drawable == null || drawable instanceof AdaptiveIconDrawable) return false;
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        try {
            drawable = drawable.mutate();
            drawable.setLevel(level);
            drawable.setBounds(0, 0, 48, 48);
            drawable.draw(new Canvas(bitmap));
            int[] pixels = new int[48 * 48];
            bitmap.getPixels(pixels, 0, 48, 0, 0, 48, 48);
            return NotificationIconMask.accepts(pixels);
        } finally { bitmap.recycle(); }
    }

    public static void refresh() {
        MAIN.post(() -> {
            if (!active) return;
            MessagesNotificationIconHooks.refresh();
            boolean enabled = readBool(KEY, false);
            if (enabled == lastEnabled) { MonochromeIconTintHooks.refresh(); return; }
            lastEnabled = enabled;
            replay(enabled);
            MonochromeIconTintHooks.refresh();
            MissingNotificationCountHooks.refresh();
        });
    }

    private static void replay(boolean enabled) {
        replaying = true;
        try {
            ArrayList<Map.Entry<View, State>> snapshot;
            synchronized (STATES) { snapshot = new ArrayList<>(STATES.entrySet()); }
            for (Map.Entry<View, State> item : snapshot) {
                State state = item.getValue();
                Object manager = state.manager.get(), entry = state.entry.get();
                if (manager == null || entry == null || item.getKey() == null) continue;
                try {
                    if (enabled)
                        state.replacement = replacementForView(manager, entry, item.getKey());
                    if (enabled) rememberCheck(state, entry, item.getKey());
                    Object icon = enabled && state.replacement != null ? state.replacement : state.original;
                    XposedHelpers.callMethod(manager, "setIcon", entry, icon, item.getKey());
                    if (enabled && state.replacement != null)
                        XposedHelpers.callMethod(item.getKey(), "setShowsConversation", false);
                } catch (Throwable t) { log("monochrome notification refresh failed: " + t); }
            }
        } finally {
            replaying = false;
            MonochromeIconGeometryHooks.refresh();
        }
    }

    public static Object capture() {
        ArrayList<Object> saved = new ArrayList<>();
        synchronized (STATES) {
            for (Map.Entry<View, State> item : STATES.entrySet()) {
                State state = item.getValue();
                saved.add(new Object[] { item.getKey(), state.manager.get(), state.entry.get(),
                        state.original, state.replacement });
            }
        }
        return new Object[] { saved.toArray(), MissingNotificationCountHooks.capture() };
    }

    public static void restore(Object saved) {
        if (!(saved instanceof Object[])) return;
        Object[] savedRows = (Object[]) saved;
        if (savedRows.length == 2 && savedRows[0] instanceof Object[]) {
            MissingNotificationCountHooks.restore(savedRows[1]);
            savedRows = (Object[]) savedRows[0];
        }
        for (Object row : savedRows) {
            if (!(row instanceof Object[])) continue;
            Object[] data = (Object[]) row;
            if (data.length == 5 && data[0] instanceof View && data[1] != null && data[2] != null)
                STATES.put((View) data[0], new State(data[1], data[2], data[3], data[4]));
            if (data.length == 5 && data[1] != null) lastManager = new WeakReference<>(data[1]);
        }
        lastEnabled = readBool(KEY, false);
        replay(lastEnabled);
        MissingNotificationCountHooks.refresh();
    }

    public static void cleanup() {
        active = false;
        MAIN.removeCallbacksAndMessages(null);
        replay(false);
        MonochromeIconTintHooks.cleanup();
        MessagesNotificationIconHooks.cleanup();
        SystemNotificationIconMasks.cleanup();
        NotificationBadgeGeometryHooks.cleanup();
        MonochromeIconGeometryHooks.cleanup();
        MissingNotificationCountHooks.cleanup();
        STATES.clear();
        lastManager.clear();
    }
}
