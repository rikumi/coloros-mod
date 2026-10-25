package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class LegacyGroupContentHooks {
    private static final ThreadLocal<Integer> selectionDepth = ThreadLocal.withInitial(() -> 0);
    private static boolean enabled() { return readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 1; }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            String content = "com.android.systemui.statusbar.notification.row.NotificationContentView";
            String row = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
            String extension = "com.oplus.systemui.statusbar.notification.row.OplusExpandableNotificationRowExImpl";
            XC_MethodHook selection = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    selectionDepth.set(selectionDepth.get() + 1);
                    p.setObjectExtra("legacyGroupSelection", true);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(p.getObjectExtra("legacyGroupSelection"))) {
                        int depth = selectionDepth.get() - 1;
                        if (depth == 0) selectionDepth.remove();
                        else selectionDepth.set(depth);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(content, lp.classLoader, "selectLayout", boolean.class, boolean.class, selection);
            XposedHelpers.findAndHookMethod(content, lp.classLoader, "getVisualTypeForHeight", float.class, selection);
            XposedHelpers.findAndHookMethod(extension, lp.classLoader, "isChildrenExpandedAnimating", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {

                    if (enabled() && selectionDepth.get() > 0) p.setResult(false);
                }
            });
            XposedHelpers.findAndHookMethod(row, lp.classLoader, "setChildrenExpanded", boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled() || !Boolean.TRUE.equals(XposedHelpers.callMethod(p.thisObject, "isChildInGroup"))) return;
                    select(p.thisObject, true);
                }
            });
            XposedHelpers.findAndHookMethod(extension, lp.classLoader, "setChildrenExpandedAnimating", boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled() || Boolean.TRUE.equals(p.args[0])) return;
                    Object parent = XposedHelpers.callMethod(p.thisObject, "getView");
                    if (parent == null || !XposedHelpers.getBooleanField(parent, "mIsSummaryWithChildren")) return;
                    Object container = XposedHelpers.getObjectField(parent, "mChildrenContainer");
                    if (container == null || XposedHelpers.getBooleanField(container, "mUserLocked")) return;
                    java.util.List<?> children = (java.util.List<?>) XposedHelpers.callMethod(container, "getAttachedChildren");

                    for (Object child : children) select(child, false);
                }
            });
        } catch (Throwable error) { log("legacy group content hook failed: " + error); }
    }

    private static void select(Object row, boolean animate) {
        if (XposedHelpers.getBooleanField(row, "mUserLocked")) return;
        Object content = XposedHelpers.getObjectField(row, "mPrivateLayout");
        if (content != null) XposedHelpers.callMethod(content, "selectLayout", animate, !animate);
    }
}
