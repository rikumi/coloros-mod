package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class LegacyGroupAppearanceHooks {
    private record Background(float alpha, int visibility) { }
    private static final WeakHashMap<View, Background> originalBackgrounds = new WeakHashMap<>();

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> containerType = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer", lp.classLoader);
            XposedHelpers.findAndHookMethod("android.view.NotificationHeaderView", lp.classLoader,
                    "setHeaderBackgroundDrawable", Drawable.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 1) return;
                            View header = (View) p.thisObject;
                            if (header.getParent() instanceof View container
                                    && containerType.isInstance(container)
                                    && XposedHelpers.getObjectField(container, "mGroupHeader") == header
                                    && !Boolean.TRUE.equals(XposedHelpers.callMethod(container, "showingAsLowPriority")))
                            {
                                p.args[0] = null;

                                if (XposedHelpers.getObjectField(header, "mBackground") == null) p.setResult(null);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader,
                    "updateBackgroundForGroupState", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 1) return;
                            Object parent = p.thisObject;
                            if (Boolean.TRUE.equals(XposedHelpers.callMethod(parent, "isChildInGroup")))
                                parent = XposedHelpers.callMethod(parent, "getNotificationParent");
                            if (parent == null || !XposedHelpers.getBooleanField(parent, "mIsSummaryWithChildren")) return;
                            Object container = XposedHelpers.getObjectField(parent, "mChildrenContainer");
                            if (container != null) apply(container, progress(container));
                        }
                    });
        } catch (Throwable error) { log("legacy group appearance hook failed: " + error); }
    }

    static float progress(Object container) {
        Object row = XposedHelpers.callMethod(container, "getContainingNotification");
        if (row == null) return XposedHelpers.getBooleanField(container, "mChildrenExpanded") ? 1f : 0f;
        Object extension = XposedHelpers.getObjectField(row, "mRowEx");
        if (extension == null) return XposedHelpers.getBooleanField(container, "mChildrenExpanded") ? 1f : 0f;
        return Math.max(0f, Math.min(1f, ((Number) XposedHelpers.callMethod(extension, "getGroupExpansionProgress")).floatValue()));
    }

    static void apply(Object container, float fraction) {
        try {
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(container, "showingAsLowPriority"))) return;
            float progress = Math.max(0f, Math.min(1f, fraction));
            Object parent = XposedHelpers.callMethod(container, "getContainingNotification");
            if (parent == null) return;
            int height = LegacyGroupGeometry.headerHeight(progress, ((View) container).getResources().getDisplayMetrics().density);
            if (XposedHelpers.getIntField(container, "mHeaderHeight") != height) {
                XposedHelpers.setIntField(container, "mHeaderHeight", height);
                ((View) container).requestLayout();
            }
            fadeBackground(parent, 1f - progress);
            java.util.List<?> children = (java.util.List<?>) XposedHelpers.callMethod(container, "getAttachedChildren");
            for (Object child : children) fadeBackground(child, progress);
            Object original = XposedHelpers.callMethod(container, "getNotificationHeaderWrapper");
            if (original == null) return;
            View header = (View) XposedHelpers.getObjectField(original, "mView");
            if (header instanceof android.view.ViewGroup group)
                LegacyCollapsedGroupHooks.applyHeaderProgress(container, group, progress);

            if (XposedHelpers.getObjectField(header, "mBackground") != null)
                XposedHelpers.callMethod(header, "setHeaderBackgroundDrawable", (Object) null);
        } catch (Throwable error) { log("legacy group appearance failed: " + error); }
    }

    private static void fadeBackground(Object row, float alpha) {
        Object object = XposedHelpers.getObjectField(row, "mBackgroundNormal");
        if (object instanceof View background) {
            originalBackgrounds.putIfAbsent(background, new Background(background.getAlpha(), background.getVisibility()));
            background.setAlpha(alpha);
            background.setVisibility(alpha > 0f ? View.VISIBLE : View.INVISIBLE);
        }
    }

    static void cleanup() {
        for (var entry : originalBackgrounds.entrySet()) {
            entry.getKey().setAlpha(entry.getValue().alpha());
            entry.getKey().setVisibility(entry.getValue().visibility());
        }
        originalBackgrounds.clear();
    }
}
