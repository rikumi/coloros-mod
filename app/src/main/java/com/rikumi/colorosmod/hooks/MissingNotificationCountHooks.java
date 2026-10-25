package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.log;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class MissingNotificationCountHooks {
    private static final String BASE = "com.oplus.systemui.statusbar.icon.";
    private static final String BADGE = "com.oplus.systemui.statusbar.widget.NotificationBadgeView";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, Area> AREAS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Area> BADGES = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Set<?> representatives = Collections.emptySet();
    private static WeakReference<Object> iconsInteractor = new WeakReference<>(null);
    private static WeakReference<Object> areaInteractor = new WeakReference<>(null);
    private static ClassLoader loader;
    private static boolean active = true, ownWrite;
    private static int missing;
    private static volatile boolean showSilent = true;

    private static final class Area {
        final WeakReference<View> badge, icons;
        final int index;
        WeakReference<Object> collector = new WeakReference<>(null);
        Object nativeData;
        WeakReference<Object> isolatedCollector = new WeakReference<>(null);
        Object nativeIsolatedData;
        WeakReference<Object> colorCollector = new WeakReference<>(null);
        Object nativeColors;
        int nativeText, nativeVisibility;
        Area(View badge, View icons, int index) {
            this.badge = new WeakReference<>(badge); this.icons = new WeakReference<>(icons);
            this.index = index;
            nativeText = XposedHelpers.getIntField(badge, "mTextNum");
            nativeVisibility = badge.getVisibility();
        }
    }

    public static void hook(XC_LoadPackage.LoadPackageParam param) {
        loader = param.classLoader;
        try {
            XposedBridge.hookAllConstructors(XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.icon.domain.interactor.NotificationIconsInteractor", loader),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            iconsInteractor = new WeakReference<>(p.thisObject);
                        }
                    });
            XposedBridge.hookAllConstructors(XposedHelpers.findClass(BASE + "domain.OplusNotificationIconAreaInteractor", loader),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            areaInteractor = new WeakReference<>(p.thisObject);
                        }
                    });
            Class<?> filter = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.icon.domain.interactor.NotificationIconsInteractor", loader);
            // DEX, not JADX's reconstructed default arguments: status-bar call uses mask 96.
            XposedHelpers.findAndHookMethod(filter, "filteredNotifSet$default", filter,
                    boolean.class, boolean.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if ((Integer) p.args[3] == 96) showSilent = (Boolean) p.args[1];
                        }
                    });
            // Input is the native filtered SET, before the domestic ROM's package deduplication.
            XposedHelpers.findAndHookMethod(BASE + "domain.OplusNotificationIconAreaInteractor$special$$inlined$map$1$2",
                    loader, "emit", Object.class, XposedHelpers.findClass("kotlin.coroutines.Continuation", loader),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (active && p.args[0] instanceof Set) {
                                representatives = new LinkedHashSet<>((Set<?>) p.args[0]);
                                refresh();
                            }
                        }
                    });
            XposedBridge.hookAllMethods(XposedHelpers.findClass(BASE + "ui.binder.OplusNotificationIconAreaBinder", loader),
                    "bindWhileAttached", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            ViewGroup root = (ViewGroup) p.args[0];
                            int badgeId = root.getResources().getIdentifier("notification_num_view", "id", "com.android.systemui");
                            int iconsId = root.getResources().getIdentifier("notificationIcons", "id", "com.android.systemui");
                            View badge = root.findViewById(badgeId), icons = root.findViewById(iconsId);
                            if (badge != null && icons != null && !AREAS.containsKey(icons)) {
                                Area area = new Area(badge, icons, ((ViewGroup) badge.getParent()).indexOfChild(badge));
                                AREAS.put(icons, area); BADGES.put(badge, area);
                            }
                            refresh();
                        }
                    });
            // This APK's emit method contains no suspend calls and does not read Continuation.
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder$bindIcons$5$invokeSuspend$$inlined$collectTracingEach$1",
                    loader, "emit", Object.class, XposedHelpers.findClass("kotlin.coroutines.Continuation", loader),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!active) return;
                            try {
                                Area area = areaForCollector(p.thisObject);
                                if (area == null) return; // excludes shelf/AOD binders
                                area.collector = new WeakReference<>(p.thisObject);
                                area.nativeData = p.args[0];
                                if (hybrid()) p.args[0] = data(p.args[0]);
                            } catch (Throwable t) { log("missing notification bind failed: " + t); }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) { updateBadges(); }
                    });
            XposedHelpers.findAndHookMethod(BADGE, loader, "setText", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Area area = areaForBadge(p.thisObject);
                    if (!active || ownWrite || area == null) return;
                    area.nativeText = (Integer) p.args[0];
                    if (hybrid()) p.args[0] = missing;
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { updateBadges(); }
            });
            XposedHelpers.findAndHookMethod(BASE + "ui.binder.OplusNotificationIconAreaBinder$bindWhileAttached$1$1$3$1",
                    loader, "emit", Object.class, XposedHelpers.findClass("kotlin.coroutines.Continuation", loader),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!active || XposedHelpers.getIntField(p.thisObject, "$r8$classId") != 2) return;
                            Area area = areaForBadge(XposedHelpers.getObjectField(p.thisObject, "$notificationBadgeView"));
                            if (area != null) {
                                area.colorCollector = new WeakReference<>(p.thisObject); area.nativeColors = p.args[0];
                            }
                        }
                    });
            XposedHelpers.findAndHookDeclaredMethod(BADGE, loader, "onLayout",
                    boolean.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Area area = areaForBadge(p.thisObject);
                            if (active && area != null) recolor(area);
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder$bindIsolatedIcon$2$2$invokeSuspend$$inlined$collectTracingEach$1",
                    loader, "emit", Object.class, XposedHelpers.findClass("kotlin.coroutines.Continuation", loader),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!active || XposedHelpers.getIntField(p.thisObject, "$r8$classId") != 0) return;
                            Area area = AREAS.get(XposedHelpers.getObjectField(p.thisObject, "$view$inlined"));
                            if (area == null) return;
                            area.isolatedCollector = new WeakReference<>(p.thisObject);
                            area.nativeIsolatedData = p.args[0];
                            if (!hybrid()) return;
                            Object info = XposedHelpers.callMethod(p.args[0], "getValue");
                            if (info == null) return;
                            Object entry = MonochromeNotificationHooks.entryForKey(
                                    (String) XposedHelpers.callMethod(info, "getNotifKey"));
                            if (MonochromeNotificationHooks.hasUsableIcon(entry)) return;
                            // Preserve the native animation completion callback while suppressing the missing icon.
                            if (p.args[0].getClass().getName().endsWith("$Animating"))
                                p.args[0] = XposedHelpers.newInstance(p.args[0].getClass(), null,
                                        XposedHelpers.callMethod(p.args[0], "getOnStopAnimating"));
                            else p.args[0] = XposedHelpers.newInstance(p.args[0].getClass(), (Object) null);
                        }
                    });
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Area area = areaForBadge(p.thisObject);
                    if (!active || ownWrite || area == null) return;
                    area.nativeVisibility = (Integer) p.args[0];
                    if (hybrid()) p.args[0] = missing > 0 ? View.VISIBLE : View.GONE;
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (active && !ownWrite && areaForBadge(p.thisObject) != null) updateBadges();
                }
            });
            log("HOOK OK native notification badge (missing monochrome icon count)");
        } catch (Throwable t) { log("HOOK FAIL missing notification count: " + t); }
    }

    private static List<Area> areas() {
        synchronized (AREAS) { return new ArrayList<>(AREAS.values()); }
    }

    private static Area areaForBadge(Object view) {
        return BADGES.get(view);
    }

    private static Area areaForCollector(Object collector) throws IllegalAccessException {
        // Only the captured host NotificationIconContainer is accepted. No field-name guess.
        for (Field field : collector.getClass().getDeclaredFields()) {
            if (!View.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            Area area = AREAS.get(field.get(collector));
            if (area != null) return area;
        }
        return null;
    }

    static boolean hybridBadge(View badge) { return BADGES.containsKey(badge) && hybrid(); }

    private static boolean hybrid() {
        if (!active || !MonochromeNotificationHooks.enabled()) return false;
        Object interactor = areaInteractor.get();
        if (interactor == null) return false;
        Object type = XposedHelpers.callMethod(XposedHelpers.callMethod(interactor, "getNotificationIconAreaType"), "getValue");
        // NUMBER (including lyric mode) remains the native all-notification count. NOT_SHOW stays hidden.
        return "NOTIFICATION_SHOW_ICON".equals(String.valueOf(type));
    }

    private static Object data(Object nativeData) {
        Object interactor = iconsInteractor.get();
        if (interactor == null) return nativeData;
        Object activeInteractor = XposedHelpers.getObjectField(interactor, "activeNotificationsInteractor");
        Object repository = XposedHelpers.getObjectField(activeInteractor, "repository");
        Object store = XposedHelpers.callMethod(XposedHelpers.callMethod(repository, "getActiveNotifications"), "getValue");
        Map<?, ?> groups = (Map<?, ?>) XposedHelpers.callMethod(store, "getGroups");
        Object headsUp = XposedHelpers.getObjectField(interactor, "headsUpNotificationIconInteractor");
        Object isolated = XposedHelpers.callMethod(XposedHelpers.callMethod(headsUp, "getIsolatedNotification"), "getValue");
        Object keyguard = XposedHelpers.getObjectField(interactor, "keyguardViewStateRepository");
        boolean fullyHidden = (Boolean) XposedHelpers.callMethod(
                XposedHelpers.callMethod(keyguard, "getAreNotificationsFullyHidden"), "getValue");
        Map<String, Object> children = new LinkedHashMap<>();
        java.util.Set<Object> candidates = new LinkedHashSet<>(representatives);

        for (Object group : groups.values()) {
            Object summary = XposedHelpers.callMethod(group, "getSummary");
            if (SystemNotificationGroupHooks.localSummaryKey((String) XposedHelpers.callMethod(summary, "getKey")))
                candidates.add(summary);
        }
        for (Object representative : candidates) {
            String key = (String) XposedHelpers.callMethod(representative, "getKey");
            List<?> leaves = Collections.emptyList();
            for (Object group : groups.values()) {
                Object summary = XposedHelpers.callMethod(group, "getSummary");
                if (!key.equals(XposedHelpers.callMethod(summary, "getKey"))) continue;
                leaves = (List<?>) XposedHelpers.callMethod(group, "getChildren");
                break;
            }
            for (Object child : NotificationIconPartition.leaves(representative, leaves)) {
                // Native mask-96 flags, current silent preference, isolated key and CTS exception.
                boolean eligible = child == representative || (Boolean) XposedHelpers.callStaticMethod(interactor.getClass(),
                        "access$shouldShowNotificationIcon", interactor, child,
                        true, false, showSilent || XposedHelpers.getBooleanField(child, "isCtsTest"),
                        false, false, true, isolated, null, fullyHidden);
                if (eligible) children.put((String) XposedHelpers.callMethod(child, "getKey"), child);
            }
        }
        ArrayList<NotificationIconPartition.Item<Object>> items = new ArrayList<>();
        Class<?> viewData = nativeData.getClass();
        Class<?> toInfo = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewDataKt", loader);
        for (Map.Entry<String, Object> item : children.entrySet()) {
            Object entry = MonochromeNotificationHooks.entryForKey(item.getKey());
            if (entry == null) continue; // already canceled or absent from the active host collection
            boolean usable;
            try { usable = MonochromeNotificationHooks.hasUsableIcon(entry); }
            catch (Throwable ignored) { usable = false; }
            if (!usable) {
                items.add(new NotificationIconPartition.Item<>(item.getKey(), "", false, null));
                continue;
            }
            StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.callMethod(entry, "getSbn");
            // Preserve one icon per package, while keeping work/personal users independent.
            Object pack = XposedHelpers.callMethod(entry, "getIcons");
            Object iconView = XposedHelpers.callMethod(pack, "getStatusBarIcon");
            Object icon = XposedHelpers.callMethod(iconView, "getSourceIcon");
            Object info = XposedHelpers.callStaticMethod(toInfo, "toIconInfo", item.getValue(), icon);
            items.add(new NotificationIconPartition.Item<>(item.getKey(),
                    sbn.getUserId() + ":" + sbn.getPackageName(), info != null, info));
        }
        NotificationIconPartition.Result<Object> result = NotificationIconPartition.partition(items);
        missing = result.missing;
        int limit = (Integer) XposedHelpers.callMethod(nativeData, "getIconLimit");
        // Reserve one existing icon slot for the native badge; colored/missing icons never reach NIC.
        if (missing > 0) limit = Math.max(0, limit - 1);
        return XposedHelpers.newInstance(viewData, result.icons, limit,
                XposedHelpers.callMethod(nativeData, "getLimitType"));
    }

    public static void refresh() {
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }
    private static final Runnable REFRESH = () -> {
        if (!active) return;
        for (Area area : areas()) {
            Object collector = area.collector.get();
            View icons = area.icons.get();
            if (collector == null || area.nativeData == null || icons == null || !icons.isAttachedToWindow()) continue;
            try { XposedHelpers.callMethod(collector, "emit", area.nativeData, null); }
            catch (Throwable t) { log("missing notification refresh failed: " + t); }
            Object isolated = area.isolatedCollector.get();
            if (isolated != null && area.nativeIsolatedData != null) {
                try { XposedHelpers.callMethod(isolated, "emit", area.nativeIsolatedData, null); }
                catch (Throwable t) { log("missing isolated notification refresh failed: " + t); }
            }
        }
        updateBadges();
    };

    private static void updateBadges() {
        if (!active || ownWrite) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(MissingNotificationCountHooks::updateBadges); return; }
        ownWrite = true;
        try {
            boolean hybrid = hybrid();
            for (Area area : areas()) {
                View badge = area.badge.get(), icons = area.icons.get();
                if (badge == null || icons == null || !(badge.getParent() instanceof ViewGroup)) continue;
                ViewGroup parent = (ViewGroup) badge.getParent();
                int desiredIndex = area.index;
                if (hybrid) {
                    int iconIndex = parent.indexOfChild(icons);
                    int badgeIndex = parent.indexOfChild(badge);
                    if (badgeIndex < iconIndex) iconIndex--;
                    desiredIndex = iconIndex + (parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 0 : 1);
                }
                if (parent.indexOfChild(badge) != desiredIndex) {
                    ViewGroup.LayoutParams params = badge.getLayoutParams();
                    parent.removeView(badge); parent.addView(badge, desiredIndex, params);
                }
                NotificationBadgeGeometryHooks.update(badge, hybrid);
                XposedHelpers.callMethod(badge, "setText", hybrid ? missing : area.nativeText);
                badge.setVisibility(hybrid ? (missing > 0 ? View.VISIBLE : View.GONE) : area.nativeVisibility);
            }
        } finally { ownWrite = false; }
    }

    private static void recolor(Area area) {
        Object collector = area.colorCollector.get();
        if (collector != null && area.nativeColors != null)
            XposedHelpers.callMethod(collector, "emit", area.nativeColors, null);
    }

    public static Object capture() {
        ArrayList<Object> rows = new ArrayList<>();
        for (Area area : areas()) rows.add(new Object[] { area.badge.get(), area.icons.get(), area.index,
                area.collector.get(), area.nativeData, area.nativeText, area.nativeVisibility,
                area.isolatedCollector.get(), area.nativeIsolatedData, area.colorCollector.get(), area.nativeColors });
        return new Object[] { rows.toArray(), representatives.toArray(), iconsInteractor.get(), areaInteractor.get(), showSilent };
    }

    public static void restore(Object saved) {
        if (!(saved instanceof Object[]) || ((Object[]) saved).length < 4) return;
        Object[] state = (Object[]) saved;
        representatives = new LinkedHashSet<>(java.util.Arrays.asList((Object[]) state[1]));
        iconsInteractor = new WeakReference<>(state[2]); areaInteractor = new WeakReference<>(state[3]);
        if (state.length > 4) showSilent = (Boolean) state[4];
        for (Object row : (Object[]) state[0]) {
            Object[] data = (Object[]) row;
            if (!(data[0] instanceof View) || !(data[1] instanceof View)) continue;
            Area area = new Area((View) data[0], (View) data[1], (Integer) data[2]);
            area.collector = new WeakReference<>(data[3]); area.nativeData = data[4];
            area.nativeText = (Integer) data[5]; area.nativeVisibility = (Integer) data[6];
            if (data.length > 8) {
                area.isolatedCollector = new WeakReference<>(data[7]); area.nativeIsolatedData = data[8];
            }
            if (data.length > 10) {
                area.colorCollector = new WeakReference<>(data[9]); area.nativeColors = data[10];
            }
            AREAS.put((View) data[1], area);
            BADGES.put((View) data[0], area);
        }
    }

    public static void cleanup() {
        active = false;
        ownWrite = true;
        try {
            for (Area area : areas()) {
                Object collector = area.collector.get();
                if (collector != null && area.nativeData != null) {
                    try { XposedHelpers.callMethod(collector, "emit", area.nativeData, null); }
                    catch (Throwable t) { log("missing notification cleanup failed: " + t); }
                }
                Object isolated = area.isolatedCollector.get();
                if (isolated != null && area.nativeIsolatedData != null) {
                    try { XposedHelpers.callMethod(isolated, "emit", area.nativeIsolatedData, null); }
                    catch (Throwable t) { log("missing isolated notification cleanup failed: " + t); }
                }
            }
        } finally { ownWrite = false; }
        // Disable our binder interception before restoring the native model and badge.
        active = false;
        MAIN.removeCallbacksAndMessages(null);
        ownWrite = true;
        try {
            for (Area area : areas()) {
                View badge = area.badge.get();
                if (badge == null || !(badge.getParent() instanceof ViewGroup)) continue;
                ViewGroup parent = (ViewGroup) badge.getParent();
                if (parent.indexOfChild(badge) != area.index) {
                    ViewGroup.LayoutParams params = badge.getLayoutParams();
                    parent.removeView(badge); parent.addView(badge, area.index, params);
                }
                XposedHelpers.callMethod(badge, "setText", area.nativeText);
                badge.setVisibility(area.nativeVisibility);
            }
        } finally { ownWrite = false; }
        AREAS.clear(); BADGES.clear(); representatives = Collections.emptySet(); iconsInteractor.clear(); areaInteractor.clear();
    }
}
