package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import com.rikumi.colorosmod.xposed.*;
import java.io.InputStream;

/** Preserve source pixels before the launcher's native icon-pack wrapping and rasterization. */
final class LauncherIconClarityHooks {
    private static final String KEY = "launcher_icon_pack_clarity_enabled";
    private static final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private static volatile boolean active = true;
    private static final java.util.concurrent.atomic.AtomicInteger traceBudget =
            new java.util.concurrent.atomic.AtomicInteger(24);
    private static final LruCache<String, BitmapDrawable> sources = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, BitmapDrawable value) {
            return value.getBitmap().getAllocationByteCount();
        }
    };

    private static boolean enabled() { return active && readBoolCached(KEY, false); }
    private static boolean loadingPack() { return enabled() && depth.get() > 0; }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        IconPackBicubic.hook(LauncherIconClarityHooks::enabled);
        XC_MethodHook scope = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled()) return;
                p.setObjectExtra("clarityDepth", depth.get());
                depth.set(depth.get() + 1);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (enabled() && !p.hasThrowable()) IconPackBicubic.track(p.getResult());
                if (p.getObjectExtra("clarityDepth") instanceof Integer previous) {
                    if (previous == 0) depth.remove(); else depth.set(previous);
                }
            }
        };
        Class<?> loader = XposedHelpers.findClassIfExists("com.oplus.uxicon.ui.util.UxIconPackLoader", lp.classLoader);
        if (loader != null) {
            XposedBridge.hookAllMethods(loader, "loadDrawableFromIconPack", scope);
            XposedBridge.hookAllMethods(loader, "generateIconPackDrawable", scope);
        }
        Class<?> morph = XposedHelpers.findClassIfExists("com.oplus.uxicon.ui.morphicon.IconPackLoaderFactory", lp.classLoader);
        if (morph != null) XposedBridge.hookAllMethods(morph, "assembleDrawable", scope);
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getDrawable", int.class, Resources.Theme.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!loadingPack()) return;
                            try {
                                BitmapDrawable source = loadSource((Resources) p.thisObject, (Integer) p.args[0]);
                                if (source != null) p.setResult(source);
                            } catch (Exception ignored) { /* Unsupported resources retain the native drawable. */ }
                        }
                    });
        } catch (Throwable error) { log("launcher icon clarity resource hook failed: " + error); }
        // Normal LauncherIcons.createAppIconBitmap uses PackageManager.getActivityIcon,
        // whose icon-pack implementation is in the boot framework, not the APK utility.
        Class<?> framework = XposedHelpers.findClassIfExists("android.app.OplusIconPackUtil", lp.classLoader);
        if (framework != null) {
            for (String method : new String[]{"getDrawableIconForPackage", "generateIconPackDrawable",
                    "loadDrawable", "getIconResMapFromXml", "loadResourcesFromXmlParserIfNeeded"})
                XposedBridge.hookAllMethods(framework, method, scope);
            hookCompositor(framework, "createIconBitmap");
            hookCompositor(framework, "createAdaptivePathIcon");
            hookGeneratedCache(framework, "generateIconPackDrawable", "mGenerateCache");
        }
        // The APK copy is still used by previews and selected launcher-specific paths.
        Class<?> compositor = XposedHelpers.findClassIfExists("com.oplus.uxicon.ui.util.d", lp.classLoader);
        if (compositor != null) {
            hookCompositor(compositor, "a");
            hookGeneratedCache(compositor, "a", "m");
        }
        // Merely restarting the launcher otherwise reloads the old, already rasterized
        // pixels from IconDB. Use a separate native icon DB, preserving the original
        // cache for switch-off and leaving the workspace database untouched.
        Class<?> cache = XposedHelpers.findClassIfExists("com.android.launcher3.icons.cache.BaseIconCache", lp.classLoader);
        if (cache != null) XposedBridge.hookAllConstructors(cache, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled() || p.args.length != 7 || !(p.args[1] instanceof String name)
                        || name.isEmpty() || name.endsWith(".clarity-v3-bicubic")) return;
                p.args[1] = name + ".clarity-v3-bicubic";
                trace("icon cache=" + p.args[1]);
            }
        });
        Class<?> icons = XposedHelpers.findClassIfExists("com.android.launcher3.icons.LauncherIcons", lp.classLoader);
        if (icons != null) XposedBridge.hookAllMethods(icons, "createAppIconBitmap", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!enabled() || p.hasThrowable() || p.getResult() == null || p.args.length != 3
                        || traceBudget.get() <= 0) return;
                try {
                    Object pixels = XposedHelpers.getObjectField(p.getResult(), "icon");
                    if (pixels instanceof Bitmap bitmap)
                        trace("launcher raster requested=" + p.args[2] + " actual="
                                + bitmap.getWidth() + "x" + bitmap.getHeight());
                } catch (Throwable error) { trace("launcher raster inspection failed: " + error); }
            }
        });
        log("launcher icon clarity: framework=" + (framework != null) + ", apk="
                + (compositor != null) + ", cache=" + (cache != null));
    }

    private static void hookCompositor(Class<?> type, String name) {
        XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!loadingPack() || !(p.method instanceof java.lang.reflect.Method method)
                        || method.getReturnType() != Bitmap.class) return;
                if (p.args.length > 0) IconPackBicubic.track(p.args[0]);
                int last = p.args.length - 1;
                if ((last == 2 || last == 7) && p.args[0] instanceof android.graphics.drawable.Drawable
                        && p.args[last] instanceof Integer size && size > 0 && size < 400) {
                    // Native adaptive_path uses integer division by 100. Keep an exact
                    // multiple so a 512px canvas does not accidentally crop a 500px mask.
                    p.args[last] = 400;
                    trace(type.getName() + "." + name + " raster=" + size + "->400");
                }
            }
        });
    }

    private static void hookGeneratedCache(Class<?> type, String name, String cacheField) {
        XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!enabled() || p.hasThrowable() || p.args.length != 2
                        || !(p.args[0] instanceof android.content.ComponentName)
                        || !(p.args[1] instanceof android.graphics.drawable.Drawable)) return;
                Object generated = XposedHelpers.getObjectField(p.thisObject, cacheField);
                if (generated instanceof java.util.Map<?, ?> cache) synchronized (cache) {
                    var keys = cache.keySet().iterator();
                    while (cache.size() > 24 && keys.hasNext()) { keys.next(); keys.remove(); }
                }
            }
        });
    }

    private static void trace(String message) {
        if (traceBudget.getAndDecrement() > 0) log("launcher icon clarity: " + message);
    }

    private static BitmapDrawable loadSource(Resources resources, int id) throws Exception {
        TypedValue value = new TypedValue();
        resources.getValueForDensity(id, Math.max(DisplayMetrics.DENSITY_XXXHIGH,
                resources.getDisplayMetrics().densityDpi), value, true);
        if (value.string == null) return null;
        String path = value.string.toString();
        if (!(path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".jpg") || path.endsWith(".jpeg"))
                || path.endsWith(".9.png")) return null;
        String key = System.identityHashCode(resources) + ":" + resources.getConfiguration().hashCode()
                + ':' + value.assetCookie + ':' + path + ':' + value.density;
        synchronized (sources) {
            BitmapDrawable cached = sources.get(key);
            if (cached != null) return (BitmapDrawable) cached.getConstantState().newDrawable(resources).mutate();
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = open(resources, value)) { BitmapFactory.decodeStream(stream, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) > 1024L * options.inSampleSize)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap pixels;
        try (InputStream stream = open(resources, value)) { pixels = BitmapFactory.decodeStream(stream, null, options); }
        if (pixels == null) return null;
        int density = value.density == TypedValue.DENSITY_NONE ? 0
                : value.density == TypedValue.DENSITY_DEFAULT ? DisplayMetrics.DENSITY_DEFAULT : value.density;
        pixels.setDensity(density == 0 ? 0 : Math.max(1, density / options.inSampleSize));
        trace("source=" + path + " pixels=" + pixels.getWidth() + "x" + pixels.getHeight()
                + " density=" + pixels.getDensity());
        BitmapDrawable source = new BitmapDrawable(resources, pixels);
        IconPackBicubic.track(source);
        source.setFilterBitmap(true);
        source.setAntiAlias(true);
        synchronized (sources) { sources.put(key, source); }
        return (BitmapDrawable) source.getConstantState().newDrawable(resources).mutate();
    }

    private static InputStream open(Resources resources, TypedValue value) {
        return (InputStream) XposedHelpers.callMethod(resources.getAssets(), "openNonAsset",
                value.assetCookie, value.string.toString(), 2);
    }

    static void cleanup() { active = false; sources.evictAll(); depth.remove(); IconPackBicubic.cleanup(); }
    private LauncherIconClarityHooks() {}
}
