package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.app.Notification;
import java.lang.ref.WeakReference;
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

final class MessagesNotificationIconHooks {
    private static final String KEY = "notification_center_monochrome_icons_enabled";
    private static final WeakHashMap<ImageView, Boolean> observed = new WeakHashMap<>();
    static boolean enabled() { return active && readBoolCached(KEY, false); }

    private static final WeakHashMap<ImageView, State> views = new WeakHashMap<>();
    private static final WeakHashMap<ImageView, Selection> selections = new WeakHashMap<>();
    private record Selection(WeakReference<Notification> notification, String key, Drawable glyph) { }
    private static Class<?> cachingIcon, rowClass;
    private static boolean active = true, failed;

    private static final class State {
        Drawable original, background;
        final Drawable replacement;
        final Selection selection;
        Integer tint;
        State(ImageView view, Drawable glyph, Selection selection) {
            original = view.getDrawable(); background = view.getBackground();
            replacement = glyph.mutate();
            this.selection = selection;
        }
    }

    static Icon iconFor(StatusBarNotification sbn) {
        if (SystemNotificationGroupHooks.androidSummary(sbn))
            return Icon.createWithResource(MODULE_PACKAGE, R.drawable.ic_android_debug_monochrome);
        if (("com.android.shell".equals(sbn.getPackageName()) || MODULE_PACKAGE.equals(sbn.getPackageName()))
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
                    observed.put(view, Boolean.TRUE);
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
                return owner;
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
            boolean monochrome = enabled();
            View row = (monochrome || SystemIconPackHooks.enabled()) ? notificationRow(view) : null;
            if (row == null) {
                if (state != null) { restore(view, state); views.remove(view); }
                return;
            }
            Object entry = XposedHelpers.callMethod(row, "getEntry");
            StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.callMethod(entry, "getSbn");
            Icon source = SystemNotificationIconMasks.source(sbn);
            String key = monochrome + ":" + SystemIconPackHooks.enabled() + ":"
                    + SystemNotificationGroupHooks.androidSummary(sbn) + ":" + sbn.getPackageName() + ':'
                    + source + ':' + sbn.getNotification().iconLevel + ':'
                    + SystemIconPackHooks.configurationKey(view.getContext());
            Selection selection = selections.get(view);
            if (selection == null || selection.notification().get() != sbn.getNotification()
                    || !selection.key().equals(key)) {
                Drawable glyph = null;
                try {
                    if (monochrome) {
                        Icon mask = MonochromeNotificationHooks.notificationMask(view.getContext(), sbn);
                        if (mask != null) glyph = mask.loadDrawable(view.getContext());
                    } else {
                        Drawable original = state == null ? view.getDrawable() : state.original;
                        Drawable icon = SystemIconPackHooks.notificationIcon(view.getContext(), sbn, original);
                        if (icon != original) glyph = icon;
                    }
                } catch (Throwable error) {
                    if (!failed) { failed = true; log("notification panel glyph resolve failed: " + error); }
                }
                selection = new Selection(new WeakReference<>(sbn.getNotification()), key, glyph);
                selections.put(view, selection);
            }
            if (selection.glyph() == null) {
                if (state != null) { restore(view, state); views.remove(view); }
                return;
            }
            if (state != null && state.selection != selection) { restore(view, state); state = null; }
            if (state == null) { state = new State(view, selection.glyph(), selection); views.put(view, state); }
            if (view.getDrawable() != state.replacement) view.setImageDrawable(state.replacement);

            if (!monochrome) return;
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
        for (ImageView view : new ArrayList<>(observed.keySet())) {
            update(view);
            view.invalidate();
        }
        SystemNotificationGroupIconHooks.refresh();
    }

    static void cleanup() {
        active = false;
        for (var entry : views.entrySet()) restore(entry.getKey(), entry.getValue());
        views.clear();
        selections.clear();
        observed.clear();
    }
}
