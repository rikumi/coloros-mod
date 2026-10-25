package com.rikumi.colorosmod.hooks;

import android.view.View;
import java.util.ArrayList;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.log;

final class NotificationChildPaddingMeasureHooks {
    private record Padding(View view, int top, int bottom, int extra) {}

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.NotificationContentView",
                    lp.classLoader, "onMeasure", int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!NotificationHooks.sNotifPadEnabled
                                    || !XposedHelpers.getBooleanField(p.thisObject, "mIsChildInGroup")) return;
                            ArrayList<Padding> saved = new ArrayList<>();

                            p.setObjectExtra("childTemplatePadding", saved);
                            for (String field : new String[]{"mContractedChild", "mExpandedChild", "mHeadsUpChild"}) {
                                Object object = XposedHelpers.getObjectField(p.thisObject, field);
                                if (!(object instanceof View child)) continue;
                                Object topTag = child.getTag(NotificationHooks.TAG_NOTIF_PAD_TOP);
                                Object bottomTag = child.getTag(NotificationHooks.TAG_NOTIF_PAD_BOTTOM);
                                if (!(topTag instanceof Integer top) || !(bottomTag instanceof Integer bottom)) continue;
                                int extra = child.getPaddingTop() - top + child.getPaddingBottom() - bottom;
                                if (extra <= 0) continue;
                                saved.add(new Padding(child, child.getPaddingTop(), child.getPaddingBottom(), extra));
                                child.setPaddingRelative(child.getPaddingStart(), top, child.getPaddingEnd(), bottom);
                            }
                        }

                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!(p.getObjectExtra("childTemplatePadding") instanceof ArrayList<?> saved)) return;
                            View content = (View) p.thisObject;
                            int height = content.getMeasuredHeight();
                            // Restore every template first, including the failure path.
                            for (Object object : saved) {
                                Padding padding = (Padding) object;
                                View child = padding.view();
                                child.setPaddingRelative(child.getPaddingStart(), padding.top(),
                                        child.getPaddingEnd(), padding.bottom());
                            }
                            if (p.hasThrowable()) return;
                            for (Object object : saved) {
                                Padding padding = (Padding) object;
                                View child = padding.view();
                                int childHeight = child.getMeasuredHeight() + padding.extra();
                                child.measure(View.MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(childHeight, View.MeasureSpec.EXACTLY));
                                height = Math.max(height, child.getMeasuredHeight());
                            }

                            if (height != content.getMeasuredHeight())
                                XposedHelpers.callMethod(content, "setMeasuredDimension", content.getMeasuredWidth(), height);
                        }
                    });
        } catch (Throwable error) { log("group child padded measurement hook failed: " + error); }
    }
}
