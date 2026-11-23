package com.rikumi.colorosmod.hooks;

import android.os.Build;
import android.content.ComponentName;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Method;
import java.util.HashMap;
import com.rikumi.colorosmod.xposed.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import static com.rikumi.colorosmod.XposedInit.*;

/** Match composed, unmapped icons to the icon pack's own canvas on ColorOS 15. */
final class LauncherIconPackFallbackHooks {
    private static final String KEY = "launcher_icon_pack_fallback_enabled";
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final Map<Object, Boolean> MODES = new WeakHashMap<>();
    private static volatile boolean active = true;
    private static Boolean lastEnabled;
    private static final Map<Class<?>, String> CACHE_FIELDS = new HashMap<>();

    private static boolean enabled() {
        return active && Build.VERSION.SDK_INT == 35 && readBoolCached(KEY, false);
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT != 35) return;
        LegacyHiddenAppIconHooks.hook(lp);
        try {
            hookUtility(XposedHelpers.findClass("android.app.OplusIconPackUtil", lp.classLoader),
                    "mGenerateCache", "mFactor");
        } catch (Throwable error) { log("framework icon pack fallback unavailable: " + error); }
        try {
            // ColorOS 15 Launcher has a second, obfuscated copy of the compositor.
            // Resolve its type from the loader, and overloads from their signatures.
            Class<?> loader = XposedHelpers.findClass("com.oplus.uxicon.ui.util.UxIconPackLoader", lp.classLoader);
            hookUtility(loader.getDeclaredField("mIconPackUtil").getType(), "l", "b");
        } catch (Throwable error) { log("launcher icon pack fallback unavailable: " + error); }
    }

    private static void hookUtility(Class<?> utility, String cacheField, String factorField) throws Exception {
        if (!Map.class.isAssignableFrom(utility.getDeclaredField(cacheField).getType())
                || utility.getDeclaredField(factorField).getType() != float.class) return;
        Method lookup = null, generate = null, bitmap = null;
        for (Method method : utility.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Drawable.class.isAssignableFrom(method.getReturnType())) {
                if (java.util.Arrays.equals(parameters, new Class<?>[]{ComponentName.class})) lookup = method;
                if (java.util.Arrays.equals(parameters, new Class<?>[]{ComponentName.class, Drawable.class})) generate = method;
            }
            if (method.getReturnType() == Bitmap.class && java.util.Arrays.equals(parameters,
                    new Class<?>[]{Drawable.class, Resources.class, BitmapDrawable.class,
                            BitmapDrawable.class, BitmapDrawable.class, float.class, float.class, int.class})) bitmap = method;
        }
        if (lookup == null || generate == null || bitmap == null)
            throw new NoSuchMethodException("Icon pack composition contract: " + utility.getName());
        CACHE_FIELDS.put(utility, cacheField);
        XposedBridge.hookMethod(lookup, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (active) prepareCache(p.thisObject);
            }
        });
        XposedBridge.hookMethod(generate, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!active) return;
                prepareCache(p.thisObject);
                if (!enabled()) return;
                int depth = DEPTH.get();
                p.setObjectExtra("fallbackDepth", depth);
                DEPTH.set(depth + 1);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getObjectExtra("fallbackDepth") instanceof Integer depth) {
                    if (depth == 0) DEPTH.remove(); else DEPTH.set(depth);
                }
            }
        });
        XposedBridge.hookMethod(bitmap, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled() || DEPTH.get() == 0 || p.args.length != 8
                        || !(p.args[5] instanceof Float) || !(p.args[6] instanceof Float)) return;
                float factor = XposedHelpers.getFloatField(p.thisObject, factorField);
                if (!Float.isFinite(factor) || factor <= 0f) return;
                // Native normalization shrinks both the app and the pack's back/mask
                // to Android's keyline. Mapped assets bypass that extra normalization.
                // Keep the pack's declared foreground scale and composite its own
                // back, mask and upon layers at their original canvas size.
                p.args[5] = factor;
                p.args[6] = 1f;
            }
        });
    }

    private static void prepareCache(Object utility) {
        boolean mode = enabled();
        synchronized (MODES) {
            Boolean previous = MODES.put(utility, mode);
            if (previous == null ? mode : previous != mode) clearGenerated(utility);
        }
    }

    private static void clearGenerated(Object utility) {
        String field = CACHE_FIELDS.get(utility.getClass());
        if (field == null) return;
        Object value = XposedHelpers.getObjectField(utility, field);
        if (value instanceof Map<?, ?> cache) synchronized (cache) { cache.clear(); }
    }

    static void onSettingsSnapshotPublished(Map<String, Integer> settings) {
        if (!active || Build.VERSION.SDK_INT != 35) return;
        boolean enabled = settings.getOrDefault(KEY, 0) == 1;
        Boolean previous = lastEnabled;
        lastEnabled = enabled;
        if (previous == null ? !enabled : previous == enabled) return;
        synchronized (MODES) {
            for (Object utility : new ArrayList<>(MODES.keySet())) clearGenerated(utility);
            MODES.clear();
        }
        LauncherIconCacheRefreshHooks.refreshIcons();
    }

    static void onLauncherReady() {
        if (enabled()) LauncherIconCacheRefreshHooks.refreshIcons();
    }

    static void cleanup() {
        active = false;
        LegacyHiddenAppIconHooks.cleanup();
        synchronized (MODES) {
            for (Object utility : new ArrayList<>(MODES.keySet())) clearGenerated(utility);
            MODES.clear();
        }
        lastEnabled = null;
    }
    private LauncherIconPackFallbackHooks() {}
}
