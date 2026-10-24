package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Restores the Android 15 SystemUI single-line and conversation notification templates. */
public final class LegacyNotificationLayoutHooks {
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String TEMPLATE_ASSET = "legacy_notification_templates_15.99.13.apk";
    private static final String TEMPLATE_CACHE_FILE = "legacy_notification_templates_15.99.13.apk";
    private static final int ANDROID_PACKAGE_ID = 0x01;
    private static final int SYSTEMUI_PACKAGE_ID = 0x7f;
    private static final Map<Integer, String[]> LEGACY_ANDROID_RESOURCES;

    private static volatile AssetManager sLegacyAssets;
    private static Class<?> sRowClass;
    private static Boolean sAppliedEnabled;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable REFRESH = LegacyNotificationLayoutHooks::refreshRows;

    static {
        Map<Integer, String[]> names = new HashMap<>();
        // The old HybridConversationNotificationView expects colemak and clip_to_padding_tag;
        // those Android resource ids are conversation_icon and conversation_face_pile today.
        names.put(0x01020288, new String[] { "id", "conversation_icon" });
        names.put(0x01020283, new String[] { "id", "conversation_face_pile" });
        names.put(0x01090068, new String[] { "layout", "conversation_face_pile_layout" });
        names.put(0x01050119, new String[] {
                "dimen", "conversation_icon_container_top_padding_small_avatar" });
        names.put(0x01030392, new String[] { "style", "TextAppearance.Holo.Light.Small.Inverse" });
        names.put(0x01030483, new String[] { "style", "Widget.Holo.CompoundButton" });
        LEGACY_ANDROID_RESOURCES = Collections.unmodifiableMap(names);
    }

    private LegacyNotificationLayoutHooks() { }

    public static void hook(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            sRowClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_NOTIFICATION_LEGACY_LAYOUT_ENABLED, false)) return;

                                LayoutInflater inflater = (LayoutInflater) param.thisObject;
                                Context context = inflater.getContext();
                                Resources currentResources = context.getResources();
                                int currentLayoutId = (Integer) param.args[0];
                                if (!SYSTEMUI_PACKAGE.equals(
                                        currentResources.getResourcePackageName(currentLayoutId))) return;

                                String layoutName = currentResources.getResourceEntryName(currentLayoutId);
                                if (!"hybrid_notification".equals(layoutName)
                                        && !"hybrid_conversation_notification".equals(layoutName)) return;

                                AssetManager assets = getLegacyAssets(context);
                                Resources legacyResources = new Resources(assets,
                                        currentResources.getDisplayMetrics(),
                                        currentResources.getConfiguration());
                                int legacyLayoutId = legacyResources.getIdentifier(
                                        layoutName, "layout", SYSTEMUI_PACKAGE);
                                if (legacyLayoutId == 0) return;

                                XmlResourceParser legacyParser = mappedLayout(context, layoutName,
                                        legacyResources, currentResources);
                                try {
                                    ViewGroup root = (ViewGroup) param.args[1];
                                    boolean attachToRoot = (Boolean) param.args[2];
                                    Object view = inflater.inflate(
                                            (XmlPullParser) legacyParser, root, attachToRoot);
                                    param.setResult(view);
                                } finally {
                                    legacyParser.close();
                                }
                            } catch (Throwable t) {
                                log("legacy_notification_layout inflate fail: " + t);
                            }
                        }
                    });
            log("HOOK OK android.view.LayoutInflater#inflate (legacy notification layouts)");
            onSettingsSnapshotPublished();
        } catch (Throwable t) {
            log("HOOK FAIL LayoutInflater#inflate (legacy notification layouts) :: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    public static synchronized void onSettingsSnapshotPublished() {
        if (sRowClass == null) return;
        boolean enabled = readBoolCached(KEY_NOTIFICATION_LEGACY_LAYOUT_ENABLED, false);
        if (java.util.Objects.equals(sAppliedEnabled, enabled)) return;
        sAppliedEnabled = enabled;
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }

    private static void refreshRows() {
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object manager = XposedHelpers.callStaticMethod(global, "getInstance");
            String[] names = (String[]) XposedHelpers.callMethod(manager, "getViewRootNames");
            java.util.Set<android.view.View> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (String name : names) {
                Object root = XposedHelpers.callMethod(manager, "getRootView", name);
                if (root instanceof android.view.View view) rebindRows(view, visited);
            }
        } catch (Throwable error) { log("legacy notification rebind failed: " + error); }
    }

    private static void rebindRows(android.view.View view, java.util.Set<android.view.View> visited) {
        if (!visited.add(view)) return;
        if (sRowClass != null && sRowClass.isInstance(view)) {
            try {
                Object entry = XposedHelpers.getObjectField(view, "mEntry");
                Object stage = XposedHelpers.getObjectField(view, "mRowContentBindStage");
                if (entry != null && stage != null) {
                    Object params = XposedHelpers.callMethod(stage, "getStageParams", entry);
                    XposedHelpers.callMethod(params, "setNeedsReinflation", true);
                    XposedHelpers.callMethod(stage, "requestRebind", entry, null);
                }
            } catch (Throwable error) { log("legacy notification row rebind failed: " + error); }
        }
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) rebindRows(group.getChildAt(i), visited);
    }

    public static synchronized void cleanupForHotReload() {
        MAIN.removeCallbacksAndMessages(null);
        sRowClass = null;
        sAppliedEnabled = null;
        AssetManager assets = sLegacyAssets;
        sLegacyAssets = null;
        if (assets != null) assets.close();
    }

    private static AssetManager getLegacyAssets(Context systemUiContext) throws Exception {
        AssetManager cached = sLegacyAssets;
        if (cached != null) return cached;
        synchronized (LegacyNotificationLayoutHooks.class) {
            cached = sLegacyAssets;
            if (cached != null) return cached;

            Context moduleContext = systemUiContext.createPackageContext(
                    MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            File cacheFile = new File(systemUiContext.getCodeCacheDir(), TEMPLATE_CACHE_FILE);
            if (!cacheFile.isFile() || cacheFile.length() == 0) {
                File temporary = new File(systemUiContext.getCodeCacheDir(),
                        TEMPLATE_CACHE_FILE + ".tmp");
                try (InputStream input = moduleContext.getAssets().open(TEMPLATE_ASSET);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.getFD().sync();
                }
                if (!temporary.renameTo(cacheFile)) {
                    temporary.delete();
                    throw new IllegalStateException("cannot cache legacy notification templates");
                }
            }

            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            int cookie = (Integer) addAssetPath.invoke(assets, cacheFile.getAbsolutePath());
            if (cookie == 0) throw new IllegalStateException("cannot load legacy notification resources");
            sLegacyAssets = assets;
            return assets;
        }
    }

    private static XmlResourceParser mappedLayout(Context context, String name,
            Resources legacy, Resources current) throws Exception {
        byte[] xml;
        try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(
                new File(context.getCodeCacheDir(), TEMPLATE_CACHE_FILE));
             InputStream input = apk.getInputStream(apk.getEntry("res/layout/" + name + ".xml"))) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            xml = LegacyNotificationXml.remap(output.toByteArray(), id -> mapResourceId(id, legacy, current));
        }
        Class<?> blockClass = Class.forName("android.content.res.XmlBlock");
        java.lang.reflect.Constructor<?> constructor = blockClass.getDeclaredConstructor(byte[].class);
        constructor.setAccessible(true);
        Object block = constructor.newInstance((Object) xml);
        try {
            Method newParser = blockClass.getDeclaredMethod("newParser");
            newParser.setAccessible(true);
            return (XmlResourceParser) newParser.invoke(block);
        } finally {
            // The parser retains its own XmlBlock open count until parser.close().
            Method close = blockClass.getDeclaredMethod("close");
            close.setAccessible(true);
            close.invoke(block);
        }
    }

    private static int mapResourceId(int resourceId, Resources legacyResources,
            Resources currentResources) {
        String packageName;
        String typeName;
        String entryName;
        try {
            int packageId = (resourceId >>> 24) & 0xff;
            if (packageId == SYSTEMUI_PACKAGE_ID) {
                packageName = legacyResources.getResourcePackageName(resourceId);
                typeName = legacyResources.getResourceTypeName(resourceId);
                entryName = legacyResources.getResourceEntryName(resourceId);
            } else if (packageId == ANDROID_PACKAGE_ID) {
                String[] name = LEGACY_ANDROID_RESOURCES.get(resourceId);
                if (name == null) return resourceId;
                packageName = "android";
                typeName = name[0];
                entryName = name[1];
            } else {
                return resourceId;
            }
            int currentId = currentResources.getIdentifier(entryName, typeName, packageName);
            return currentId != 0 ? currentId : resourceId;
        } catch (Throwable ignored) {
            return resourceId;
        }
    }
}
