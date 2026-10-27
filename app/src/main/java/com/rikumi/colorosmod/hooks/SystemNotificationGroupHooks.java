package com.rikumi.colorosmod.hooks;

import android.app.Notification;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import java.lang.ref.WeakReference;
import java.util.*;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.*;

final class SystemNotificationGroupHooks {
    private static final String TAG = "coloros_mod_android_summary";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WeakReference<Object> collection = new WeakReference<>(null);
    private static final Map<Integer, Object> summaries = new HashMap<>();
    private static final Map<Integer, Object> visibleSummaries = new HashMap<>();
    private static boolean active = true, syncing;
    private static StatusBarNotification sbn(Object entry) {
        return (StatusBarNotification) XposedHelpers.callMethod(entry, "getSbn");
    }
    private static int user(StatusBarNotification sbn) {
        return sbn.getUserId() == -1 ? (Integer) XposedHelpers.callMethod(android.os.Process.myUserHandle(), "getIdentifier") : sbn.getUserId();
    }
    static boolean own(StatusBarNotification sbn) { return TAG.equals(sbn.getTag()) && "android".equals(sbn.getPackageName()); }
    static boolean localSummaryKey(String key) {
        if (!enabled()) return false;
        for (Object entry : summaries.values()) if (sbn(entry).getKey().equals(key)) return true;
        return false;
    }
    static boolean androidSummary(StatusBarNotification sbn) {
        return active && NotificationAppNameHooks.enabled() && "android".equals(sbn.getPackageName())
                && (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }
    private static boolean enabled() { return active && NotificationAppNameHooks.enabled(); }
    private static String group(int user) { return user + "|android|g:" + TAG; }
    private static boolean leaf(Object entry) {
        StatusBarNotification sbn = sbn(entry);
        return "android".equals(sbn.getPackageName()) && (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(entry, "isCanceled"))
                && "NOT_DISMISSED".equals(String.valueOf(XposedHelpers.callMethod(entry, "getDismissState")));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> entries(Object owner) {
        return (Map<String, Object>) XposedHelpers.getObjectField(owner, "mNotificationSet");
    }
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> type = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.NotifCollection", lp.classLoader);
            XposedHelpers.findAndHookMethod(type, "dispatchEventsAndRebuildList", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    collection = new WeakReference<>(p.thisObject);
                    sync(p.thisObject);
                }
            });

            XposedHelpers.findAndHookMethod(type, "applyRanking", RankingMap.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Map<String, Object> hidden = new LinkedHashMap<>();
                    Iterator<Map.Entry<String, Object>> it = entries(p.thisObject).entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, Object> item = it.next();
                        if (own(sbn(item.getValue()))) { hidden.put(item.getKey(), item.getValue()); it.remove(); }
                    }
                    p.setObjectExtra("moduleSystemSummaries", hidden);
                }
                @Override @SuppressWarnings("unchecked") protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("moduleSystemSummaries") instanceof Map hidden)
                        entries(p.thisObject).putAll(hidden);
                }
            });

            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.collection.ShadeListBuilder", lp.classLoader,
                    "maybeSuppressGroupChange", XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.NotificationEntry", lp.classLoader),
                    List.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            StatusBarNotification n = sbn(p.args[0]);
                            if (!"android".equals(n.getPackageName())) return;
                            Object previous = XposedHelpers.callMethod(XposedHelpers.callMethod(p.args[0], "getPreviousAttachState"), "getParent");
                            if (enabled() || (previous != null && String.valueOf(XposedHelpers.callMethod(previous, "getKey")).endsWith("g:" + TAG)))
                                p.setResult(false);
                        }
                    });
            String force = "com.oplus.systemui.notification.data.forcegroup.OplusNotificationForceGroupExImpl";
            XposedHelpers.findAndHookMethod(force, lp.classLoader, "startGroupNotifs", List.class, new XC_MethodHook() {
                @Override @SuppressWarnings("unchecked") protected void beforeHookedMethod(MethodHookParam p) {
                    visibleSummaries.clear();
                    if (!enabled()) return;
                    List<Object> list = (List<Object>) p.args[0];
                    Map<Integer, Integer> counts = new HashMap<>();
                    for (Object entry : list) if (leaf(entry)) counts.merge(user(sbn(entry)), 1, Integer::sum);
                    for (var item : counts.entrySet()) if (item.getValue() >= 2 && summaries.containsKey(item.getKey()))
                        visibleSummaries.put(item.getKey(), summaries.get(item.getKey()));

                    list.removeIf(entry -> {
                        StatusBarNotification n = sbn(entry);
                        return "android".equals(n.getPackageName()) && ((n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0)
                                && !own(n) && visibleSummaries.containsKey(user(n));
                    });
                }
            });
            XposedHelpers.findAndHookMethod(force, lp.classLoader, "getOplusAutoGroupSummary", StatusBarNotification.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    StatusBarNotification n = (StatusBarNotification) p.args[0];
                    if (enabled() && "android".equals(n.getPackageName()) && visibleSummaries.containsKey(user(n)))
                        p.setResult(visibleSummaries.get(user(n)));
                }
            });
            XposedHelpers.findAndHookMethod(force, lp.classLoader, "getGroupKey", StatusBarNotification.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    StatusBarNotification n = (StatusBarNotification) p.args[0];
                    if (enabled() && "android".equals(n.getPackageName()) && visibleSummaries.containsKey(user(n)))
                        p.setResult(group(user(n)));
                }
            });
            XposedHelpers.findAndHookMethod(StatusBarNotification.class, "getGroupKey", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    StatusBarNotification n = (StatusBarNotification) p.thisObject;
                    if (enabled() && "android".equals(n.getPackageName()) && visibleSummaries.containsKey(user(n)))
                        p.setResult(group(user(n)));
                }
            });
            XposedHelpers.findAndHookMethod(force, lp.classLoader, "includeOplusAutoGroupToDismiss", List.class, Collection.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    List<?> requested = (List<?>) p.args[0];
                    if (!enabled()) return;
                    Map<String, Object> output = new LinkedHashMap<>();
                    for (Object request : requested) {
                        Object entry = XposedHelpers.callMethod(request, "getEntry");
                        if (entry == null) continue;
                        if (!own(sbn(entry))) { output.putIfAbsent(sbn(entry).getKey(), request); continue; }
                        for (Object child : (Collection<?>) p.args[1]) {
                            if (leaf(child) && user(sbn(child)) == user(sbn(entry)) && sbn(child).isClearable()
                                    && !output.containsKey(sbn(child).getKey()))
                                output.put(sbn(child).getKey(), XposedHelpers.callMethod(request, "copyForEntry", child));
                        }
                    }
                    // Never send the local summary key to system_server for dismissal.
                    p.args[0] = new ArrayList<>(output.values());
                }
            });
        } catch (Throwable error) { log("system notification grouping hook failed: " + error); }
    }

    private static void sync(Object owner) {
        if (syncing) return;
        syncing = true;
        try {
            Map<Integer, List<Object>> groups = new LinkedHashMap<>();
            if (enabled()) for (Object entry : new ArrayList<>(entries(owner).values()))
                if (leaf(entry)) groups.computeIfAbsent(user(sbn(entry)), ignored -> new ArrayList<>()).add(entry);
            for (var it = summaries.entrySet().iterator(); it.hasNext();) {
                var item = it.next();
                if (groups.getOrDefault(item.getKey(), Collections.emptyList()).size() >= 2) continue;
                Object entry = item.getValue();
                XposedHelpers.setIntField(entry, "mCancellationReason", 2);
                XposedHelpers.callMethod(owner, "tryRemoveNotification", entry);
                it.remove(); visibleSummaries.remove(item.getKey());
            }
            for (var item : groups.entrySet()) {
                if (item.getValue().size() < 2) continue;
                boolean allOngoing = true;
                for (Object child : item.getValue()) allOngoing &= !sbn(child).isClearable();
                Object existing = summaries.get(item.getKey());
                if (existing != null) {
                    Notification current = sbn(existing).getNotification();
                    boolean wasOngoing = (current.flags & Notification.FLAG_ONGOING_EVENT) != 0;
                    if (wasOngoing != allOngoing) {
                        if (allOngoing) current.flags |= Notification.FLAG_ONGOING_EVENT;
                        else current.flags &= ~Notification.FLAG_ONGOING_EVENT;
                        XposedHelpers.callMethod(owner, "postNotification", sbn(existing), XposedHelpers.callMethod(existing, "getRanking"));
                    }
                    continue;
                }
                Object source = item.getValue().get(0);
                StatusBarNotification original = sbn(source);
                Application app = (Application) XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication");
                Notification n = new Notification.Builder(app.createPackageContext("android", 0), original.getNotification().getChannelId())
                        .setSmallIcon(original.getNotification().getSmallIcon())
                        .setContentTitle(app.getPackageManager().getApplicationLabel(
                                app.getPackageManager().getApplicationInfo("android", 0)))
                        .setGroup(TAG).setGroupSummary(true).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                        .setOnlyAlertOnce(true).setLocalOnly(true)
                        .setShowWhen(false).build();
                if (allOngoing) n.flags |= Notification.FLAG_ONGOING_EVENT;
                StatusBarNotification local = (StatusBarNotification) XposedHelpers.newInstance(StatusBarNotification.class, "android", "android", 0x434f534d,
                        TAG, original.getUid(), XposedHelpers.callMethod(original, "getInitialPid"), n,
                        (UserHandle) XposedHelpers.callStaticMethod(UserHandle.class, "of", item.getKey()), null, original.getPostTime());
                Ranking sourceRanking = (Ranking) XposedHelpers.callMethod(source, "getRanking");
                Parcel parcel = Parcel.obtain(); Ranking ranking;
                try { XposedHelpers.callMethod(sourceRanking, "writeToParcel", parcel, 0); parcel.setDataPosition(0); ranking = (Ranking) XposedHelpers.newInstance(Ranking.class, parcel); }
                finally { parcel.recycle(); }
                XposedHelpers.setObjectField(ranking, "mKey", local.getKey());
                XposedHelpers.callMethod(owner, "postNotification", local, ranking);
                Object entry = entries(owner).get(local.getKey());
                if (entry != null) summaries.put(item.getKey(), entry);
            }
        } catch (Throwable error) { log("system notification grouping update failed: " + error); }
        finally { syncing = false; }
    }

    static void refresh() {
        main.post(() -> {
            Object owner = collection.get();
            if (owner == null && active) {
                try {
                    Object app = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
                    Object graph = XposedHelpers.getObjectField(app, "mSysUIComponent");
                    if (graph != null) owner = XposedHelpers.callMethod(
                            XposedHelpers.getObjectField(graph, "notifCollectionProvider"), "get");
                    if (owner != null) collection = new WeakReference<>(owner);
                } catch (Throwable error) { log("system notification collection lookup failed: " + error); }
            }
            if (owner != null) XposedHelpers.callMethod(owner, "dispatchEventsAndRebuildList", "systemNotificationGroupingSetting");
        });
    }
    static void cleanup() {
        active = false;
        Object owner = collection.get();
        if (owner != null) {
            sync(owner);
            visibleSummaries.clear();
            XposedHelpers.callMethod(owner, "dispatchEventsAndRebuildList", "systemNotificationGroupingCleanup");
        }
        SystemNotificationGroupIconHooks.cleanup();
        summaries.clear(); collection.clear();
    }
}
