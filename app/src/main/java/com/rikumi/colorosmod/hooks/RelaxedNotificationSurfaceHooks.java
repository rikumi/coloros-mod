package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import com.rikumi.colorosmod.xposed.*;

/** Surface-specific exceptions; the notification-center template path stays unchanged. */
final class RelaxedNotificationSurfaceHooks {
    private static final ThreadLocal<Integer> publicInflation = new ThreadLocal<>();

    private static boolean enabled() {
        return readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 2;
    }

    static boolean nativePublicInflation() { return publicInflation.get() != null; }

    static boolean isPublicContent(View view) {
        if (view == null) return false;
        int id = NotificationResourceIds.get(view.getResources(), "expandedPublic", "id", "com.android.systemui");
        if (id == 0) return false;
        for (View current = view; current != null;) {
            if (current.getId() == id) return true;
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return false;
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Notification$Builder", lp.classLoader,
                    "createCompactHeadsUpContentView", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled()) return;
                    // Compact binding moves the body into the header and clears its
                    // normal text field. Swapping XML alone cannot undo those actions.
                    Object result = XposedHelpers.callMethod(p.thisObject, "createHeadsUpContentView");
                    // Plain notifications without actions have no full heads-up view.
                    if (!(result instanceof RemoteViews))
                        result = XposedHelpers.callMethod(p.thisObject, "createContentView");
                    if (result instanceof RemoteViews) p.setResult(result);
                }
            });
        } catch (Throwable error) { log("relaxed compact heads-up compatibility failed: " + error); }
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate", int.class,
                    ViewGroup.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || !(p.args[1] instanceof View parent) || !isPublicContent(parent)) return;
                    Integer previous = publicInflation.get();
                    p.setObjectExtra("relaxedPublicInflation", previous == null ? 0 : previous);
                    publicInflation.set(previous == null ? 1 : previous + 1);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object previous = p.getObjectExtra("relaxedPublicInflation");
                    if (!(previous instanceof Integer depth)) return;
                    if (depth == 0) publicInflation.remove();
                    else publicInflation.set(depth);
                }
            });
        } catch (Throwable error) { log("relaxed public notification compatibility failed: " + error); }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.statusbar.notification.row.NotificationContentViewExtImp", lp.classLoader,
                    "shouldPreferContractedWhenHeightEqualsExpanded",
                    XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader),
                    View.class, View.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled() || p.args[0] == null || p.args[1] == null || p.args[2] == null) return;
                    Object row = p.args[0];
                    // Native's equal-height shortcut runs before lockscreen selection.
                    // Preserve explicit expansion, drag transitions and all shade rows.
                    if (XposedHelpers.getBooleanField(row, "mOnKeyguard")
                            && !XposedHelpers.getBooleanField(row, "mIgnoreLockscreenConstraints")
                            && !NotificationLayoutCompat.userLocked(row)
                            && !Boolean.TRUE.equals(XposedHelpers.callMethod(row, "isExpanded", false)))
                        p.setResult(true);
                }
            });
        } catch (Throwable error) { log("relaxed lockscreen content selection failed: " + error); }
    }

    private RelaxedNotificationSurfaceHooks() {}
}
