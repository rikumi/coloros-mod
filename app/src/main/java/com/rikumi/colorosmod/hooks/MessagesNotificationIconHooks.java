package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.R;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Notification-panel glyphs: the Messages trace and verified native system masks. */
final class MessagesNotificationIconHooks {
    private static final WeakHashMap<ImageView, State> views = new WeakHashMap<>();
    private static Class<?> cachingIcon, rowClass;
    private static boolean active = true, failed;

    private static final class State {
        Drawable original, background;
        final Drawable replacement;
        final String key;
        Integer tint;
        State(ImageView view, Icon glyph, String key) {
            original = view.getDrawable(); background = view.getBackground();
            replacement = glyph.loadDrawable(view.getContext()).mutate();
            this.key = key;
        }
    }

    static Icon iconFor(StatusBarNotification sbn) {
        if (SystemNotificationGroupHooks.androidSummary(sbn))
            return Icon.createWithResource(MODULE_PACKAGE, R.drawable.ic_android_debug_monochrome);
        if ("com.android.shell".equals(sbn.getPackageName())
                && "notification_layout_preview".equals(sbn.getTag()))
            return Icon.createWithResource(MODULE_PACKAGE, R.drawable.ic_test_notification_monochrome);
        return "com.android.mms".equals(sbn.getPackageName())
                ? Icon.createWithResource(MODULE_PACKAGE, R.drawable.ic_messages_monochrome) : null;
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            cachingIcon = XposedHelpers.findClass("com.android.internal.widget.CachingIconView", lp.classLoader);
            rowClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader);
            XposedHelpers.findAndHookMethod(ImageView.class, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ImageView view = (ImageView) p.thisObject;
                    if (!active || !cachingIcon.isInstance(view)) return;
                    update(view);
                }
            });
        } catch (Throwable error) { log("messages notification icon hook failed: " + error); }
    }

    private static View notificationRow(ImageView view) {
        int id = view.getId();
        if (id == View.NO_ID || !"icon".equals(view.getResources().getResourceEntryName(id))) return null;
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View owner; parent = owner.getParent()) {
            if (rowClass.isInstance(owner)) {
                Object entry = XposedHelpers.callMethod(owner, "getEntry");
                if (entry == null) return null;
                String pkg = ((StatusBarNotification) XposedHelpers.callMethod(entry, "getSbn")).getPackageName();
                return pkg.equals("com.android.mms") || pkg.equals("com.android.shell") || pkg.equals("android") || pkg.equals("com.android.systemui") ? owner : null;
            }
        }
        return null;
    }

    private static int foregroundColor(View row) {
        int id = NotificationResourceIds.get(row.getResources(), "app_name_text", "id", "android");
        View name = id == 0 ? null : row.findViewById(id);
        if (name instanceof TextView text) return text.getCurrentTextColor();
        View title = row.findViewById(android.R.id.title);
        if (title instanceof TextView text) return text.getCurrentTextColor();
        boolean dark = (row.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return dark ? 0xffffffff : 0xff000000;
    }

    private static void update(ImageView view) {
        try {
            State state = views.get(view);
            // Preserve new native bindings, including asynchronous group-icon results.
            if (state != null) {
                if (view.getDrawable() != state.replacement) state.original = view.getDrawable();
                if (view.getBackground() != null) state.background = view.getBackground();
            }
            View row = (MonochromeNotificationHooks.enabled() || NotificationAppNameHooks.enabled()) ? notificationRow(view) : null;
            if (row == null) {
                if (state != null) { restore(view, state); views.remove(view); }
                return;
            }
            Object entry = XposedHelpers.callMethod(row, "getEntry");
            StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.callMethod(entry, "getSbn");
            if (!MonochromeNotificationHooks.enabled() && !SystemNotificationGroupHooks.androidSummary(sbn)) {
                if (state != null) { restore(view, state); views.remove(view); }
                return;
            }
            Icon source = SystemNotificationIconMasks.source(sbn);
            Icon glyph = iconFor(sbn);
            if (glyph == null) glyph = SystemNotificationIconMasks.resolve(view.getContext(), sbn, source);
            if (glyph == null) {
                if (state != null) { restore(view, state); views.remove(view); }
                return;
            }
            String key = SystemNotificationGroupHooks.androidSummary(sbn) + ":" + sbn.getPackageName() + ':' + source + ':' + sbn.getNotification().iconLevel
                    + ':' + view.getResources().getConfiguration().hashCode();
            if (state != null && !state.key.equals(key)) { restore(view, state); state = null; }
            if (state == null) { state = new State(view, glyph, key); views.put(view, state); }
            if (view.getDrawable() != state.replacement) view.setImageDrawable(state.replacement);
            // Native app icons use a dark tint over a colored plate. Once the plate
            // is removed, follow the actual notification text, including OEM dark
            // cards shown independently of the system-wide night-mode setting.
            if (state.replacement.getColorFilter() != null) state.replacement.clearColorFilter();
            int tint = foregroundColor(row);
            if (state.tint == null || state.tint != tint) {
                state.replacement.setTint(tint);
                state.tint = tint;
            }
            if (view.getBackground() != null) view.setBackground(null);
        } catch (Throwable error) {
            if (!failed) { failed = true; log("messages notification icon apply failed: " + error); }
        } finally {
            SystemNotificationGroupIconHooks.iconUpdated(view);
        }
    }

    private static void restore(ImageView view, State state) {
        if (view.getDrawable() == state.replacement) view.setImageDrawable(state.original);
        if (view.getBackground() == null) view.setBackground(state.background);
        view.invalidate();
    }

    static boolean hasReplacement(ImageView view) { return view != null && views.containsKey(view); }

    static void refresh() {
        for (ImageView view : new ArrayList<>(views.keySet())) update(view);
    }

    static void cleanup() {
        active = false;
        for (var entry : views.entrySet()) restore(entry.getKey(), entry.getValue());
        views.clear();
    }
}
