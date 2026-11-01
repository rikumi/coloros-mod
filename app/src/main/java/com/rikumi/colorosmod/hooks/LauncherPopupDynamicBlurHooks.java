package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Bitmap;
import android.view.View;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.*;

/** Launcher 17.3.12's inlined Bitmap and HardwareBuffer blur paths. */
final class LauncherPopupDynamicBlurHooks {
    private static final ThreadLocal<Integer> loading = new ThreadLocal<>();
    private static final ThreadLocal<Integer> delivering = new ThreadLocal<>();
    private static final WeakHashMap<Object, SourceRequest> clearCallbacks = new WeakHashMap<>();
    private static final ThreadLocal<SourceRequest> sourceRequest = new ThreadLocal<>();

    private static final class SourceRequest {
        final java.lang.ref.WeakReference<View> view;
        int pending;
        boolean loadingFinished;
        SourceRequest(View view) { this.view = new java.lang.ref.WeakReference<>(view); }
        void notifyIfReady() {
            View target = view.get();
            if (loadingFinished && pending == 0 && target != null) {
                target.post(() -> LauncherHooks.onPopupBlurSourceReady(target, this));
            }
        }
    }

    static boolean hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            ClassLoader loader = lp.classLoader;
            Class<?> helper = XposedHelpers.findClass("com.android.launcher3.popup.PopupBlurHelper", loader);
            Class<?> launcher = XposedHelpers.findClass("com.android.launcher.Launcher", loader);
            Class<?> popup = XposedHelpers.findClass("com.android.launcher3.popup.PopupBlurView", loader);
            Class<?> defaults = XposedHelpers.findClass("hl.a$a", loader);
            Class<?> renderer = XposedHelpers.findClass("hl.a", loader);
            Class<?> cache = XposedHelpers.findClass("com.android.launcher.wallpaper.d", loader);
            Class<?> callback = XposedHelpers.findClass("com.android.launcher3.popup.EffectResultCallbackImp", loader);
            Class<?> effect = XposedHelpers.findClass("fl.b", loader);
            // These obfuscated names are specific to the inspected APK. Validate the
            // whole path before installing; older Launchers retain their legacy hooks.
            helper.getDeclaredMethod("loadPopupBlurBg", launcher, popup);
            defaults.getDeclaredMethod("a", renderer, float.class, float.class, float.class, int.class);
            cache.getDeclaredMethod("a");
            cache.getDeclaredMethod("d", Bitmap.class, boolean.class);
            callback.getDeclaredMethod("setBlurEffect", effect);
            callback.getDeclaredMethod("onEffectDone", Bitmap.class);

            XposedHelpers.findAndHookMethod(helper, "loadPopupBlurBg", launcher, popup, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!readBoolCached(KEY_POPUP_DYNAMIC_BLUR_ENABLED, false)) return;
                    SourceRequest request = new SourceRequest((View) p.args[1]);
                    p.setObjectExtra("previousPopupRequest", sourceRequest.get());
                    p.setObjectExtra("popupRequest", request);
                    sourceRequest.set(request);
                    LauncherHooks.onPopupBlurSourceLoading((View) p.args[1], request);
                    enter(p, loading);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    leave(p, loading);
                    Object value = p.getObjectExtra("popupRequest");
                    if (!(value instanceof SourceRequest request)) return;
                    sourceRequest.set((SourceRequest) p.getObjectExtra("previousPopupRequest"));
                    synchronized (clearCallbacks) {
                        request.loadingFinished = true;
                        request.notifyIfReady();
                    }
                }
            });
            XposedHelpers.findAndHookMethod(defaults, "a", renderer, float.class, float.class,
                    float.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (depth(loading) == 0) return;
                    // Covers wallpaper (0.7) and both drag-layer inputs (4.0).
                    // Keep native capture sizing, mirror scale and result ownership.
                    p.args[1] = 0f;
                    p.args[2] = 0f;
                    p.args[3] = 0f;
                }
            });
            XposedHelpers.findAndHookMethod(cache, "a", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // Do not start the animation from an already blurred wallpaper.
                    if (depth(loading) > 0) p.setResult(null);
                }
            });
            XposedHelpers.findAndHookMethod(callback, "setBlurEffect", effect, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (depth(loading) > 0) synchronized (clearCallbacks) {
                        SourceRequest request = sourceRequest.get();
                        if (request != null && !clearCallbacks.containsKey(p.thisObject)) {
                            clearCallbacks.put(p.thisObject, request);
                            request.pending++;
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(callback, "onEffectDone", Bitmap.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    SourceRequest request;
                    synchronized (clearCallbacks) { request = clearCallbacks.remove(p.thisObject); }
                    p.setObjectExtra("completedPopupRequest", request);
                    // Rendering completes asynchronously, possibly after switch-off.
                    // Carry the request's identity rather than re-reading the switch.
                    if (request != null) enter(p, delivering);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    leave(p, delivering);
                    Object value = p.getObjectExtra("completedPopupRequest");
                    if (value instanceof SourceRequest request) synchronized (clearCallbacks) {
                        request.pending--;
                        request.notifyIfReady();
                    }
                }
            });
            XposedHelpers.findAndHookMethod(cache, "d", Bitmap.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // A clear popup snapshot must never replace the shared static
                    // blur cache, notify its listeners, or be saved as blurred wallpaper.
                    if (depth(delivering) > 0) p.setResult(null);
                }
            });
            log("HOOK OK launcher 17 popup clear sources and isolated wallpaper cache");
            return true;
        } catch (Throwable error) {
            log("launcher 17 popup source path unavailable: " + error);
            return false;
        }
    }

    private static int depth(ThreadLocal<Integer> scope) {
        Integer value = scope.get();
        return value == null ? 0 : value;
    }

    private static void enter(XC_MethodHook.MethodHookParam p, ThreadLocal<Integer> scope) {
        int previous = depth(scope);
        p.setObjectExtra("popupSourceScope", previous);
        scope.set(previous + 1);
    }

    private static void leave(XC_MethodHook.MethodHookParam p, ThreadLocal<Integer> scope) {
        Object previous = p.getObjectExtra("popupSourceScope");
        if (!(previous instanceof Integer value)) return;
        if (value == 0) scope.remove();
        else scope.set(value);
    }

    private LauncherPopupDynamicBlurHooks() {}
}
