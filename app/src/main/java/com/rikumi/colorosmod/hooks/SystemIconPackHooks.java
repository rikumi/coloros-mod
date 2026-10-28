package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.app.Application;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.util.LruCache;
import com.rikumi.colorosmod.xposed.*;

/** ColorOS-native equivalent of Flyme's system application icon pack feature. */
public final class SystemIconPackHooks {
    public static final String KEY = "system_application_icon_pack";
    private static boolean installed, active = true, lastEnabled;
    private static final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> themed = ThreadLocal.withInitial(() -> false);
    private static Class<?> uxManager;
    private static final LruCache<String, ApplicationInfo> applications = new LruCache<>(64);
    private static final LruCache<String, java.util.Map<Integer, ActivityInfo>> entrances = new LruCache<>(64);
    private record Cached(Drawable.ConstantState state, int size) {}
    private static final LruCache<String, Cached> cache = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Cached value) { return value.size(); }
    };
    private static boolean launcherProcess() {
        String process = Application.getProcessName();
        return "com.android.launcher".equals(process)
                || (process != null && process.startsWith("com.android.launcher:"));
    }
    static boolean enabled() { return active && !launcherProcess() && readBoolCached(KEY, false); }
    private static boolean eligible() { return enabled() && depth.get() == 0; }

    public static void hook(ClassLoader loader) {
        try { installHooks(loader); }
        catch (Throwable error) { log("system icon hooks unavailable: " + error); }
    }
    private static void installHooks(ClassLoader loader) {
        if (installed || launcherProcess()) return;
        uxManager = XposedHelpers.findClassIfExists("android.app.UxIconPackageManagerExt", loader);
        Class<?> helper = XposedHelpers.findClassIfExists("android.app.OplusUXIconLoadHelper", loader);
        if (uxManager == null || helper == null) return;
        installed = true;
        // Only the explicit themed load bypasses the caller whitelist. Native loads,
        // including those performed with this option off, retain their original behavior.
        XposedBridge.hookAllMethods(helper, "supportUxOnline", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (themed.get()) p.setResult(true);
            }
        });
        XposedBridge.hookAllMethods(uxManager, "getCachedIconForThemeHelper", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (themed.get()) p.setResult(null);
            }
        });
        XposedBridge.hookAllMethods(uxManager, "putCachedIconForThemeHelper", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // Do not leak decorated results into PackageManager's native cache.
                if (themed.get()) p.setResult(null);
            }
        });
        Class<?> pm = XposedHelpers.findClass("android.app.ApplicationPackageManager", loader);
        install(pm, "loadUnbadgedItemIcon", p -> {
            PackageItemInfo item = (PackageItemInfo) p.args[0];
            ApplicationInfo app = (ApplicationInfo) p.args[1];
            if (app == null || special(item)) return p.getResult();
            if (!(item instanceof ApplicationInfo || item instanceof ActivityInfo)) {
                if (item.icon != 0 && item.icon != app.icon) return p.getResult();
                item = app;
            }
            Drawable original = (Drawable) p.getResult();
            Context context = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
            return replace(context, (PackageManager) p.thisObject, item, app, 0, original);
        }, PackageItemInfo.class, ApplicationInfo.class);
        install(pm, "getDrawable", p -> {
            Drawable original = (Drawable) p.getResult();
            Context context = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
            PackageManager manager = (PackageManager) p.thisObject;
            ApplicationInfo app = (ApplicationInfo) p.args[2];
            if (app == null) app = application(manager, (String) p.args[0]);
            PackageItemInfo item = resourceItem(manager, app, (Integer) p.args[1]);
            return item == null ? original : replace(context, manager, item, app, 0, original);
        }, String.class, int.class, ApplicationInfo.class);
        XposedBridge.hookAllMethods(pm, "configurationChanged", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { cache.evictAll(); applications.evictAll(); entrances.evictAll(); }
        });
        XposedBridge.hookAllMethods(pm, "handlePackageBroadcast", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { cache.evictAll(); applications.evictAll(); entrances.evictAll(); }
        });
        install(android.content.res.Resources.class, "getDrawableForDensity", p -> {
            if (!(p.getResult() instanceof Drawable original)) return p.getResult();
            Context context = currentContext();
            if (context == null) return original;
            var resources = (android.content.res.Resources) p.thisObject;
            ResourceIcon icon = resourceIcon(context, resources, (Integer) p.args[0]);
            return icon == null ? original : replace(context, context.getPackageManager(), icon.item(), icon.app(), (Integer) p.args[1], original);
        }, int.class, int.class, android.content.res.Resources.Theme.class);
        install(android.graphics.BitmapFactory.class, "decodeResource", p -> {
            if (!(p.getResult() instanceof Bitmap original)) return p.getResult();
            if (p.args[2] instanceof android.graphics.BitmapFactory.Options options && options.inBitmap != null)
                return original; // Explicit bitmap reuse is an identity contract.
            Context context = currentContext();
            if (context == null) return original;
            ResourceIcon source = resourceIcon(context, (android.content.res.Resources) p.args[0], (Integer) p.args[1]);
            if (source == null) return original;
            Drawable icon = themed(context, context.getPackageManager(), source.item(), source.app(), 0);
            return icon == null ? original : SystemIconDrawableCompat.render(icon, original);
        }, android.content.res.Resources.class, int.class, android.graphics.BitmapFactory.Options.class);
        install(ResolveInfo.class, "loadIcon", p -> {
            ResolveInfo info = (ResolveInfo) p.thisObject;
            android.content.pm.ComponentInfo activity = info.activityInfo != null ? info.activityInfo : info.serviceInfo;
            if (activity == null || special(activity)) return p.getResult();
            PackageItemInfo item = activity;
            if (!(activity instanceof ActivityInfo)) {
                if (activity.icon != 0 && activity.icon != activity.applicationInfo.icon) return p.getResult();
                item = activity.applicationInfo;
            }
            Drawable original = (Drawable) p.getResult();
            PackageManager manager = (PackageManager) p.args[0];
            Context context = (Context) XposedHelpers.getObjectField(manager, "mContext");
            Drawable replacement = replace(context, manager, item, activity.applicationInfo, 0, original);
            if (replacement == original) return original;
            // ResolveInfo's result is already badged; restore that badge on the new source.
            return manager.getUserBadgedIcon(replacement, UserHandle.getUserHandleForUid(activity.applicationInfo.uid));
        }, PackageManager.class);
        install(LauncherActivityInfo.class, "getIcon", p -> {
            LauncherActivityInfo info = (LauncherActivityInfo) p.thisObject;
            ActivityInfo activity = info.getActivityInfo();
            if (special(activity)) return p.getResult();
            Drawable original = (Drawable) p.getResult();
            PackageManager manager = (PackageManager) XposedHelpers.getObjectField(info, "mPm");
            Context context = (Context) XposedHelpers.getObjectField(manager, "mContext");
            // getBadgedIcon adds the user badge after getIcon, as on the native path.
            return replace(context, manager, activity, info.getApplicationInfo(), (Integer) p.args[0], original);
        }, int.class);
        install(Icon.class, "loadDrawableInner", p -> {
            Icon icon = (Icon) p.thisObject;
            if (icon.getType() != Icon.TYPE_RESOURCE) return p.getResult();
            Drawable original = (Drawable) p.getResult();
            Context context = (Context) p.args[0];
            String pkg = icon.getResPackage();
            if (pkg == null || pkg.isEmpty()) pkg = context.getPackageName();
            ApplicationInfo app = application(context.getPackageManager(), pkg);
            // Only launcher/application resources, never arbitrary notification symbols.
            PackageItemInfo item = resourceItem(context.getPackageManager(), app, icon.getResId());
            return item == null ? original : replace(context, context.getPackageManager(), item, app, 0, original);
        }, Context.class);
        Class<?> extension = XposedHelpers.findClassIfExists("android.app.ApplicationPackageManagerExtImpl", loader);
        if (extension != null) install(extension, "getAppIconBitmap", p -> {
            Object result = p.getResult();
            if (!(result instanceof Bitmap pixels)) return result;
            PackageManager manager = (PackageManager) XposedHelpers.getObjectField(p.thisObject, "mApplicationPackageManager");
            Context context = (Context) XposedHelpers.getObjectField(manager, "mContext");
            ApplicationInfo app = application(manager, (String) p.args[0]);
            if (app == null) return result;
            Drawable icon = themed(context, manager, app, app, 0);
            return icon == null ? result : SystemIconDrawableCompat.render(icon, pixels);
        }, String.class);
        log("HOOK OK ColorOS system application theme/icon pack APIs");
    }

    private static final class Scope implements AutoCloseable {
        final int oldDepth = depth.get();
        final boolean oldThemed = themed.get();
        Scope(boolean loadTheme) { depth.set(oldDepth + 1); themed.set(loadTheme); }
        @Override public void close() {
            if (oldDepth == 0) depth.remove(); else depth.set(oldDepth);
            if (oldThemed) themed.set(true); else themed.remove();
        }
    }
    private static boolean special(PackageItemInfo item) {
        try { if (XposedHelpers.getIntField(item, "showUserIcon") != -10000) return true; }
        catch (Throwable ignored) { return true; }
        try { return XposedHelpers.getBooleanField(item, "isArchived"); }
        catch (Throwable ignored) { return false; } // Older releases do not have archived apps.
    }
    private static ApplicationInfo application(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        String key = System.identityHashCode(pm) + ":" + pkg;
        ApplicationInfo cached = applications.get(key);
        if (cached != null) return cached;
        try (Scope ignored = new Scope(false)) {
            ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
            applications.put(key, app);
            return app;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { return null; }
    }
    private static Context currentContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Context app = (Context) XposedHelpers.callStaticMethod(thread, "currentApplication");
            if (app != null) return app;
            Object current = XposedHelpers.callStaticMethod(thread, "currentActivityThread");
            return current == null ? null : (Context) XposedHelpers.callMethod(current, "getSystemContext");
        } catch (Throwable ignored) { return null; }
    }
    private record ResourceIcon(PackageItemInfo item, ApplicationInfo app) {}
    private static ResourceIcon resourceIcon(Context context, android.content.res.Resources resources, int id) {
        if (resources == null || id == 0) return null;
        try {
            ApplicationInfo app = application(context.getPackageManager(), resources.getResourcePackageName(id));
            PackageItemInfo item = resourceItem(context.getPackageManager(), app, id);
            return item == null ? null : new ResourceIcon(item, app);
        } catch (android.content.res.Resources.NotFoundException ignored) { return null; }
    }
    private static PackageItemInfo resourceItem(PackageManager manager, ApplicationInfo app, int id) {
        if (app == null || id == 0) return null;
        if (app.icon == id) return app;
        String key = System.identityHashCode(manager) + ":" + app.uid + ':' + app.packageName + ':' + app.sourceDir;
        java.util.Map<Integer, ActivityInfo> icons = entrances.get(key);
        if (icons == null) {
            icons = new java.util.HashMap<>();
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(app.packageName);
            for (ResolveInfo resolve : manager.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                ActivityInfo info = resolve.activityInfo;
                if (info != null && info.icon != 0) icons.putIfAbsent(info.icon, info);
            }
            entrances.put(key, icons);
        }
        return icons.get(id);
    }
    private static Drawable replace(Context context, PackageManager manager, PackageItemInfo item,
                                    ApplicationInfo app, int density, Drawable original) {
        if (original == null || app == null) return original;
        try {
            Drawable icon = themed(context, manager, item, app, density);
            return SystemIconDrawableCompat.replace(context.getResources(), original, icon);
        } catch (RuntimeException | LinkageError error) {
            log("system icon replacement retained native result: " + error);
            return original;
        }
    }
    private static Drawable themed(Context context, PackageManager manager, PackageItemInfo item,
                                   ApplicationInfo app, int density) {
        if (special(item)) return null;
        if (item.icon == 0 && item instanceof ActivityInfo activity && app.icon != 0) {
            ActivityInfo copy = new ActivityInfo(activity);
            copy.icon = app.icon;
            item = copy;
        }
        if (item.icon == 0) return null;
        String key = app.uid + ":" + app.sourceDir + ':' + item.packageName + ':' + item.name + ':' + item.icon
                + ':' + density + ':' + configurationKey(context);
        Cached cached = cache.get(key);
        if (cached != null) return cached.state().newDrawable(context.getResources()).mutate();
        Drawable result;
        try (Scope ignored = new Scope(true)) {
            Object ux = XposedHelpers.newInstance(uxManager, manager, context);
            result = (Drawable) XposedHelpers.callMethod(ux, "loadItemIcon", item, app, true);
        } catch (Throwable error) { return null; }
        if (result == null) return null;
        if (density > 0 && result instanceof android.graphics.drawable.BitmapDrawable bitmap) bitmap.setTargetDensity(density);
        Drawable.ConstantState state = result.getConstantState();
        if (state != null) {
            int width = Math.max(1, Math.min(2048, result.getIntrinsicWidth()));
            int height = Math.max(1, Math.min(2048, result.getIntrinsicHeight()));
            cache.put(key, new Cached(state, Math.max(1024, width * height * 4)));
        }
        return result;
    }
    static String configurationKey(Context context) {
        var config = context.getResources().getConfiguration();
        try { return config + ":" + XposedHelpers.callMethod(config, "getOplusExtraConfiguration"); }
        catch (Throwable ignored) { return config.toString(); }
    }
    static Drawable notificationIcon(Context context, android.service.notification.StatusBarNotification sbn, Drawable original) {
        if (!enabled() || original == null) return original;
        try (Scope ignored = new Scope(false)) {
            Context user = sbn.getUserId() == -1 ? context : (Context) XposedHelpers.callMethod(context,
                    "createContextAsUser", sbn.getUser(), 0);
            PackageManager manager = user.getPackageManager();
            ApplicationInfo app = application(manager, sbn.getPackageName());
            return replace(user, manager, app, app, 0, original);
        } catch (Throwable ignored) { return original; }
    }
    private interface Decorator { Object apply(XC_MethodHook.MethodHookParam p) throws Throwable; }
    private static final java.util.Set<String> failures = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static void install(Class<?> type, String name, Decorator decorator, Class<?>... parameters) {
        try {
            XposedHelpers.findAndHookMethod(type, name, append(parameters, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (eligible()) p.setObjectExtra("systemIconOriginal", new Scope(false));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object saved = p.getObjectExtra("systemIconOriginal");
                    if (!(saved instanceof Scope scope)) return;
                    scope.close();
                    if (p.hasThrowable() || !enabled()) return;
                    try (Scope ignored = new Scope(false)) { p.setResult(decorator.apply(p)); }
                    catch (Throwable error) {
                        if (failures.add(type.getName() + '#' + name))
                            log("system icon API retained native result: " + type.getName() + '#' + name + ": " + error);
                    }
                }
            }));
        } catch (Throwable error) { log("system icon API unavailable: " + type.getName() + '#' + name); }
    }
    private static Object[] append(Class<?>[] parameters, XC_MethodHook hook) {
        Object[] result = java.util.Arrays.copyOf(parameters, parameters.length + 1, Object[].class);
        result[parameters.length] = hook;
        return result;
    }
    public static void onSettingsSnapshotPublished() {
        boolean current = enabled();
        if (current != lastEnabled) { lastEnabled = current; cache.evictAll(); }
    }
    public static void cleanup() { active = false; cache.evictAll(); applications.evictAll(); entrances.evictAll(); }
    private SystemIconPackHooks() {}
}
