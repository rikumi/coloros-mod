package com.rikumi.colorosmod.hooks;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.service.notification.StatusBarNotification;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.log;

final class SystemNotificationGroupIconHooks {
    private static final WeakHashMap<FrameLayout, State> pills = new WeakHashMap<>();
    private static final WeakHashMap<ImageView, WeakReference<State>> icons = new WeakHashMap<>();
    private static final class State {
        final WeakReference<Object> entry;
        final WeakReference<TextView> number;
        final WeakReference<ImageView> icon;
        GradientDrawable background;
        ColorStateList color, textColor;
        float textAlpha;
        State(Object entry, TextView number, ImageView icon) {
            this.entry = new WeakReference<>(entry); this.number = new WeakReference<>(number);
            this.icon = new WeakReference<>(icon);
        }
        void capture(FrameLayout pill) {
            if (pill.getBackground() instanceof GradientDrawable drawable) {
                background = drawable; color = drawable.getColor();
            }
            TextView text = number.get();
            if (text != null) { textColor = text.getTextColors(); textAlpha = text.getAlpha(); }
        }
        void restore() {
            if (background != null && color != null && !color.equals(background.getColor())) background.setColor(color);
            TextView text = number.get();
            if (text != null && textColor != null) {
                if (!textColor.equals(text.getTextColors())) text.setTextColor(textColor);
                if (text.getAlpha() != textAlpha) text.setAlpha(textAlpha);
            }
        }
        void apply() {
            Object target = entry.get();
            if (target == null) return;
            StatusBarNotification n = (StatusBarNotification) XposedHelpers.callMethod(target, "getSbn");
            if (!MessagesNotificationIconHooks.enabled()
                    || (!SystemNotificationGroupHooks.androidSummary(n)
                        && !MessagesNotificationIconHooks.hasReplacement(icon.get()))) {
                restore(); return;
            }
            if (background != null && (background.getColor() == null
                    || background.getColor().getDefaultColor() != 0x80000000)) background.setColor(0x80000000);
            TextView text = number.get();
            if (text != null) {
                if (text.getCurrentTextColor() != 0xffffffff) text.setTextColor(0xffffffff);
                if (text.getAlpha() != 1f) text.setAlpha(1f);
            }
        }
    }
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            String manager = "com.oplus.systemui.notification.row.oplusgroup.GroupIconManager";
            Class<?> entry = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.NotificationEntry", lp.classLoader);
            Class<?> icon = XposedHelpers.findClass("com.android.internal.widget.CachingIconView", lp.classLoader);
            Class<?> callback = XposedHelpers.findClass("kotlin.jvm.functions.Function1", lp.classLoader);
            Class<?> info = XposedHelpers.findClass(manager + "$IconInfo", lp.classLoader);
            XposedHelpers.findAndHookMethod(manager, lp.classLoader, "initEntryIconDrawable", entry, icon,
                    TextView.class, FrameLayout.class, boolean.class, callback, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!(p.args[3] instanceof FrameLayout pill)) return;
                            State old = pills.remove(pill);
                            if (old != null) old.restore();
                            State state = new State(p.args[0], (TextView) p.args[2], (ImageView) p.args[1]);
                            state.capture(pill); pills.put(pill, state);
                            icons.put((ImageView) p.args[1], new WeakReference<>(state));
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            State state = pills.get(p.args[3]);
                            if (state != null) state.apply();
                        }
                    });
            XposedHelpers.findAndHookMethod(manager, lp.classLoader, "access$initPillBgAndNumberColor",
                    XposedHelpers.findClass(manager, lp.classLoader), FrameLayout.class, info, TextView.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            State state = pills.get(p.args[1]);
                            if (state != null) { state.capture((FrameLayout) p.args[1]); state.apply(); }
                        }
                    });
        } catch (Throwable error) { log("system notification group icon palette hook failed: " + error); }
    }
    static void iconUpdated(ImageView icon) {
        WeakReference<State> reference = icons.get(icon);
        State state = reference == null ? null : reference.get();
        if (state != null) state.apply();
    }
    static void refresh() { for (State state : new ArrayList<>(pills.values())) state.apply(); }
    static void cleanup() {
        for (State state : new ArrayList<>(pills.values())) state.restore();
        pills.clear(); icons.clear();
    }
}
