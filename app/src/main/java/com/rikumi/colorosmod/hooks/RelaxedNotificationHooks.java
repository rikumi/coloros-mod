package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.view.View;
import android.content.res.Resources;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.RelativeLayout;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Full native headers in both content states, without forcing group or long-content expansion. */
final class RelaxedNotificationHooks {
    private static boolean enabled() { return readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 2; }
    private static final WeakHashMap<View, IconAnchor> anchors = new WeakHashMap<>();
    private static final class IconAnchor {
        int top, bottom, gravity, parentGravity;
        int[] rules;
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp",
                    lp.classLoader, "resolveHeaderViewsPlus", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object wrapper = XposedHelpers.callMethod(p.thisObject, "getBase");
                            if (wrapper != null) anchorIcon(XposedHelpers.getObjectField(wrapper, "mIcon"));
                        }
                    });
            // Substitute only this firmware's standard collapsed shells. Keeping the
            // Builder's style binding preserves short/long content and group metadata.
            java.util.Map<String, String> shells = java.util.Map.of(
                    "notification_2025_template_collapsed_base", "notification_2025_template_expanded_base",
                    "notification_2025_template_heads_up_base", "notification_2025_template_expanded_base",
                    "notification_2025_template_collapsed_messaging", "notification_2025_template_expanded_messaging",
                    "notification_2025_template_collapsed_conversation", "notification_2025_template_expanded_conversation",
                    "notification_2025_template_collapsed_media", "notification_2025_template_expanded_media",
                    "notification_2025_template_collapsed_call", "notification_2025_template_expanded_call");
            XposedHelpers.findAndHookMethod(Resources.class, "getLayout", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || (((Integer) p.args[0]) >>> 24) != 1) return;
                    Resources resources = (Resources) p.thisObject;
                    String expanded = shells.get(resources.getResourceEntryName((Integer) p.args[0]));
                    if (expanded == null) return;
                    int id = resources.getIdentifier(expanded, "layout", "android");
                    if (id != 0) p.setResult(resources.getLayout(id));
                }
            });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.NotificationContentView", lp.classLoader,
                    "onMeasure", int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object object = XposedHelpers.getObjectField(p.thisObject, "mContractedChild");
                            if (!(object instanceof View child)) return;
                            int id = child.getResources().getIdentifier("notification_header", "id", "android");
                            if (id == 0 || child.findViewById(id) == null) return;
                            p.setObjectExtra("smallHeight", XposedHelpers.getIntField(p.thisObject, "mSmallHeight"));
                            p.setObjectExtra("headsUpHeight", XposedHelpers.getIntField(p.thisObject, "mHeadsUpHeight"));
                            int limit = XposedHelpers.getIntField(p.thisObject, "mNotificationMaxHeight");
                            // Let wrap_content include the real full header; retain native
                            // maximum bounds and restore these limits after measurement.
                            XposedHelpers.setIntField(p.thisObject, "mSmallHeight", limit);
                            XposedHelpers.setIntField(p.thisObject, "mHeadsUpHeight", limit);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.getObjectExtra("smallHeight") instanceof Integer height)
                                XposedHelpers.setIntField(p.thisObject, "mSmallHeight", height);
                            if (p.getObjectExtra("headsUpHeight") instanceof Integer height)
                                XposedHelpers.setIntField(p.thisObject, "mHeadsUpHeight", height);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper", lp.classLoader,
                    "updateExpandability", boolean.class, View.OnClickListener.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled()) return;
                            Object row = XposedHelpers.getObjectField(p.thisObject, "mRow");
                            if (row == null || XposedHelpers.getBooleanField(row, "mIsSummaryWithChildren")) return;
                            Object object = XposedHelpers.getObjectField(p.thisObject, "mExpandButton");
                            if (!(object instanceof View button)) return;
                            int id = button.getResources().getIdentifier("expand_button_pill", "id", "android");
                            View pill = id == 0 ? null : button.findViewById(id);
                            // z is computed by NotificationContentView from the actual
                            // contracted/expanded heights, not merely the presence of a big view.
                            if (pill != null) pill.setVisibility(Boolean.TRUE.equals(p.args[0]) ? View.VISIBLE : View.GONE);
                        }
                    });
        } catch (Throwable error) { log("relaxed notification hook failed: " + error); }
    }

    static void anchorIcon(Object object) {
        if (!enabled() || !(object instanceof View icon)) return;
        // Compact group icons live inside their own fixed-size FrameLayout;
        // the full notification template's outer margin would clip them.
        if (icon.getClass().getName().equals("com.oplus.systemui.notification.row.oplusgroup.OplusGroupNotificationRowIconView")) return;
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams margins)) return;
        IconAnchor saved = anchors.get(icon);
        if (saved == null) {
            saved = new IconAnchor();
            saved.top = margins.topMargin;
            saved.bottom = margins.bottomMargin;
            if (params instanceof RelativeLayout.LayoutParams relative)
                saved.rules = relative.getRules().clone();
            if (params instanceof FrameLayout.LayoutParams frame) saved.gravity = frame.gravity;
            if (params instanceof LinearLayout.LayoutParams line) saved.gravity = line.gravity;
            if (icon.getParent() instanceof RelativeLayout parent) saved.parentGravity = parent.getGravity();
            anchors.put(icon, saved);
        }
        int marginId = icon.getResources().getIdentifier("notification_2025_margin", "dimen", "android");
        int top = marginId == 0 ? Math.round(16 * icon.getResources().getDisplayMetrics().density)
                : icon.getResources().getDimensionPixelSize(marginId);
        boolean changed = margins.topMargin != top;
        if (params instanceof RelativeLayout.LayoutParams relative) {
            changed |= relative.getRule(RelativeLayout.CENTER_VERTICAL) != 0
                    || relative.getRule(RelativeLayout.CENTER_IN_PARENT) != 0
                    || relative.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0
                    || relative.getRule(RelativeLayout.ALIGN_PARENT_TOP) == 0;
            relative.removeRule(RelativeLayout.CENTER_VERTICAL);
            relative.removeRule(RelativeLayout.CENTER_IN_PARENT);
            relative.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            relative.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            if (icon.getParent() instanceof RelativeLayout parent)
                parent.setGravity((parent.getGravity() & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.TOP);
        } else if (params instanceof FrameLayout.LayoutParams frame) {
            changed |= frame.gravity != (Gravity.TOP | Gravity.START);
            frame.gravity = Gravity.TOP | Gravity.START;
        } else if (params instanceof LinearLayout.LayoutParams line) {
            changed |= line.gravity != Gravity.TOP;
            line.gravity = Gravity.TOP;
        }
        margins.topMargin = top;
        if (changed) icon.setLayoutParams(params);
    }

    static void cleanup() {
        for (var entry : anchors.entrySet()) {
            View icon = entry.getKey();
            IconAnchor saved = entry.getValue();
            ViewGroup.LayoutParams params = icon.getLayoutParams();
            if (!(params instanceof ViewGroup.MarginLayoutParams margins)) continue;
            margins.topMargin = saved.top;
            margins.bottomMargin = saved.bottom;
            if (params instanceof RelativeLayout.LayoutParams relative && saved.rules != null) {
                for (int rule : new int[]{RelativeLayout.CENTER_VERTICAL, RelativeLayout.CENTER_IN_PARENT,
                        RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_BOTTOM})
                    relative.addRule(rule, saved.rules[rule]);
                if (icon.getParent() instanceof RelativeLayout parent) parent.setGravity(saved.parentGravity);
            }
            if (params instanceof FrameLayout.LayoutParams frame) frame.gravity = saved.gravity;
            if (params instanceof LinearLayout.LayoutParams line) line.gravity = saved.gravity;
            icon.setLayoutParams(params);
        }
        anchors.clear();
    }
}
