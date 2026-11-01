package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.widget.FrameLayout;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class LegacyFrameworkNotificationHooks {
    static final String ASSET = "legacy_framework_notifications_15.zip";
    private static volatile LegacyFrameworkNotificationTemplates templates;
    private static boolean errorLogged;
    private static final java.util.concurrent.ConcurrentHashMap<Integer, String> virtualLayouts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Integer> missingIds = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger(0x01fe0000);

    private static int missingResource(Resources current, int oldId, LegacyFrameworkNotificationTemplates.Resource resource) {
        if (resource.type().equals("id")) return missingIds.computeIfAbsent(oldId, ignored -> nextId.getAndIncrement());
        if (resource.type().equals("layout")) {
            int id = missingIds.computeIfAbsent(oldId, ignored -> nextId.getAndIncrement());
            virtualLayouts.put(id, resource.name());
            return id;
        }
        if (resource.name().equals("notificationHeaderAppNameVisibility")
                || resource.name().equals("Theme.DeviceDefault.Notification")) return 0;
        if (resource.name().equals("TextAppearance.DeviceDefault.Notification.BigTitle"))
            return current.getIdentifier("TextAppearance.DeviceDefault.Notification.Title", "style", "android");
        throw new IllegalArgumentException("Missing framework resource " + resource.type() + "/" + resource.name());
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        LegacyNotificationGeometry.hook(lp);
        hookHeaderParentCompatibility(lp);
        LegacyMessagingSpacingHooks.hook(lp);
        RelaxedNotificationHooks.hook(lp);
        RelaxedGroupHeaderHooks.hook(lp);
        try {
            // Enter getLayout explicitly: the platform inflater may inline it or
            // return a pre-inflated ColorOS view before the resource hook runs.
            XposedHelpers.findAndHookMethod(android.view.LayoutInflater.class, "inflate", int.class,
                    android.view.ViewGroup.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            int mode = readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT);
                            if (mode != 1 && mode != 2) return;
                            int id = (Integer) p.args[0];
                            if ((id >>> 24) != 1) return;
                            android.view.LayoutInflater inflater = (android.view.LayoutInflater) p.thisObject;
                            Resources resources = inflater.getContext().getResources();
                            if (!resources.getResourceEntryName(id).startsWith("notification_")) return;
                            try (android.content.res.XmlResourceParser parser = resources.getLayout(id)) {
                                p.setResult(inflater.inflate(parser, (android.view.ViewGroup) p.args[1], (Boolean) p.args[2]));
                            }
                        }
                    });
            // getLayout() is inlined into include inflation on Android 17. Re-enter
            // its hook for nested legacy templates as well as the outer RemoteViews.
            com.rikumi.colorosmod.xposed.XposedBridge.hookAllMethods(android.view.LayoutInflater.class,
                    "parseInclude", new XC_MethodHook() {});
            XposedHelpers.findAndHookMethod(Resources.class, "getLayout", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 1) return;
                    int id = (Integer) p.args[0];
                    if ((id >>> 24) != 1) return;
                    try {
                        Resources current = (Resources) p.thisObject;
                        String name = virtualLayouts.get(id);
                        if (name == null) name = NotificationLayoutCompat.legacyLayout(current.getResourceEntryName(id));

                        if ("notification_expand_button".equals(name)) return;
                        if (!name.startsWith("notification_") && !name.equals("conversation_face_pile_layout")) return;
                        LegacyFrameworkNotificationTemplates bundle = templates();
                        if (bundle == null) return;
                        byte[] xml = bundle.layout(name, current.getConfiguration().orientation == 2);
                        if (xml == null) return;
                        byte[] mapped = LegacyNotificationXml.remap(xml, oldId -> {
                            if (oldId == 0) return 0;
                            LegacyFrameworkNotificationTemplates.Resource resource = bundle.resources.get(oldId);
                            if (resource == null) throw new IllegalArgumentException("Unknown old framework ID " + oldId);
                            int mappedId = current.getIdentifier(resource.name(), resource.type(), "android");
                            if (mappedId == 0) mappedId = missingResource(current, oldId, resource);
                            return mappedId;
                        }, oldId -> {
                            var resource = bundle.resources.get(oldId);
                            if (resource != null && resource.name().equals("notificationHeaderAppNameVisibility")
                                    && current.getIdentifier(resource.name(), resource.type(), "android") == 0)
                                return 16L << 32; // Removed theme attribute: View.VISIBLE, not unresolved ?0.
                            return bundle.dimension(oldId);
                        });
                        Object parser = LegacyNotificationLayoutHooks.parseXml(mapped);
                        LegacyNotificationGeometry.mark(parser);
                        p.setResult(parser);
                    } catch (Throwable error) {
                        if (!errorLogged) {
                            errorLogged = true;
                            log("legacy framework notification layout failed: " + android.util.Log.getStackTraceString(error));
                        }
                    }
                }
            });

            Class<?> flags = XposedHelpers.findClassIfExists("android.app.Flags", lp.classLoader);
            if (flags != null) NotificationLayoutCompat.optionalHook(flags,
                    "notificationsRedesignTemplates", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 1) p.setResult(false);
                        }
                    });
            Class<?> templateParams = XposedHelpers.findClass("android.app.Notification$StandardTemplateParams", lp.classLoader);
            int publicViewType = (Integer) XposedHelpers.getStaticObjectField(templateParams, "VIEW_TYPE_PUBLIC");
            XposedHelpers.findAndHookMethod(templateParams,
                    "headerless", boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            int mode = readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT);
                            // Public inflation keeps the native collapsed XML. Its body
                            // follows the top line in a vertical column, so expanded
                            // header spacing would add a second, redundant top inset.
                            if (mode == 2 && XposedHelpers.getIntField(p.thisObject, "mViewType") == publicViewType) return;
                            if (mode == 1 || mode == 2) p.args[0] = false;
                        }
                    });

            XposedHelpers.findAndHookMethod("android.app.Notification$Builder", lp.classLoader,
                    "applyStandardTemplate", int.class,
                    XposedHelpers.findClass("android.app.Notification$StandardTemplateParams", lp.classLoader),
                    XposedHelpers.findClass("android.app.Notification$TemplateBindResult", lp.classLoader),
                    new XC_MethodHook() { });
            String wrapper = "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationTemplateViewWrapperExImpl";
            NotificationLayoutCompat.optionalHook(XposedHelpers.findClass(wrapper, lp.classLoader), "resolveContentMargin", boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {

                            if (legacyRoot(p.thisObject) != null) p.setResult(null);
                        }
                    });
            XposedHelpers.findAndHookMethod(wrapper, lp.classLoader, "resolveActionsMargin", boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View root = legacyRoot(p.thisObject);
                            if (root != null) alignActions(root);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.wrapper.NotificationTemplateViewWrapper", lp.classLoader,
                    "onContentUpdated", XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 1) return;
                            Object view = XposedHelpers.getObjectField(p.thisObject, "mView");
                            if (view instanceof View root) alignActions(root);
                        }
                    });
            XposedHelpers.findAndHookMethod(wrapper, lp.classLoader, "resolveTitleAndText",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            android.view.View root = legacyRoot(p.thisObject);
                            if (root == null) return;
                            for (String name : new String[]{"title", "text", "big_text"}) {
                                int id = root.getResources().getIdentifier(name, "id", "android");
                                android.view.View view = id == 0 ? null : root.findViewById(id);
                                if (view instanceof android.widget.TextView text) {
                                    text.setIncludeFontPadding(false);
                                    text.setLineSpacing(0f, 1f);
                                    text.setElegantTextHeight(false);
                                }
                            }
                        }
                    });
            LegacyExpandAlignmentHooks.hook(lp);
            NotificationExpansionTouchHooks.hook(lp);
            LegacyCollapsedGroupHooks.hook(lp);
            LegacyGroupExpansionHooks.hook(lp);
            LegacyGroupContentHooks.hook(lp);
            LegacyGroupAppearanceHooks.hook(lp);
            log("HOOK OK framework notification legacy templates");
        } catch (Throwable error) { log("HOOK FAIL framework notification legacy templates: " + error); }
    }

    private static void hookHeaderParentCompatibility(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp",
                    lp.classLoader, "updateExpandButtonContainerParams", int.class, int.class,
                    int.class, View.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 0
                                    || Boolean.TRUE.equals(p.args[4])) return;
                            View target = (View) p.args[3];

                            if (target != null && !(target.getLayoutParams() instanceof FrameLayout.LayoutParams))
                                p.setResult(null);
                        }
                    });
        } catch (Throwable error) { log("legacy notification header compatibility hook failed: " + error); }
    }

    private static void alignActions(View root) {
        for (String name : new String[]{"actions_container", "actions_container_layout", "actions"}) {
            int id = root.getResources().getIdentifier(name, "id", "android");
            View view = id == 0 ? null : root.findViewById(id);
            if (view == null) continue;
            if (view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams params
                    && (params.getMarginStart() != 0 || params.leftMargin != 0)) {
                // These are owned RemoteViews layout params, recreated on mode change.
                params.setMarginStart(0); params.leftMargin = 0;
                view.setLayoutParams(params);
            }
            if (view.getPaddingStart() != 0)
                view.setPaddingRelative(0, view.getPaddingTop(), view.getPaddingEnd(), view.getPaddingBottom());
        }
    }

    private static android.view.View legacyRoot(Object wrapper) {
        if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 1) return null;
        try {
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(wrapper, "isDecoratedCustomViewExpended"))) return null;
            Object base = XposedHelpers.callMethod(wrapper, "getBase");
            Object view = base == null ? null : XposedHelpers.getObjectField(base, "mView");
            if (!(view instanceof android.view.View root)) return null;
            int header = root.getResources().getIdentifier("notification_header", "id", "android");
            return header != 0 && root.findViewById(header) != null ? root : null;
        } catch (Throwable error) {
            if (!errorLogged) { errorLogged = true; log("legacy notification spacing failed: " + error); }
            return null;
        }
    }

    static LegacyFrameworkNotificationTemplates templates() throws Exception {
        LegacyFrameworkNotificationTemplates cached = templates;
        if (cached != null) return cached;
        synchronized (LegacyFrameworkNotificationHooks.class) {
            if (templates != null) return templates;
            Context app = currentApplication();
            if (app == null) return null;
            Context module = app.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            try (java.io.InputStream input = module.getAssets().open(ASSET)) {
                templates = LegacyFrameworkNotificationTemplates.load(input);
            }
            return templates;
        }
    }

    static synchronized void cleanup() {
        LegacyCollapsedGroupHooks.cleanup();
        LegacyMessagingSpacingHooks.cleanup();
        RelaxedGroupHeaderHooks.cleanup();
        RelaxedNotificationHooks.cleanup();
        LegacyExpandAlignmentHooks.cleanup();
        LegacyGroupAppearanceHooks.cleanup();
        templates = null; errorLogged = false;
    }
}
