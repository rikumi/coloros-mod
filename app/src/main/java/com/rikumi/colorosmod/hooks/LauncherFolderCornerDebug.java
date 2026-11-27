package com.rikumi.colorosmod.hooks;

import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;
import com.rikumi.colorosmod.xposed.*;
import java.util.*;
import java.util.function.Predicate;
import static com.rikumi.colorosmod.XposedInit.*;

/** Temporary, folder-scoped comparison against ordinary round-rectangle corners. */
final class LauncherFolderCornerDebug {
    static final String KEY = "launcher_folder_disable_smooth_corners";
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final Set<Object> HOSTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, DebugOutline> OUTLINES = new WeakHashMap<>();
    private static boolean active = true;

    static boolean enabled() { return active && readBoolCached(KEY, false); }

    static void hook(ClassLoader loader, Class<?> preview, Class<?> folder) {
        active = true;
        Predicate<Object> folderPreview = bg -> folder.isInstance(
                XposedHelpers.getObjectField(bg, "mInvalidateDelegate"));
        for (String name : new String[] { "setup", "updateParamsBeforeSetup", "initBgDrawable",
                "setBackground", "drawBackground", "setRadius", "clipViewSmoothRound" })
            scope(preview, name, folderPreview);
        try {
            Class<?> image = XposedHelpers.findClass("com.android.launcher3.folder.FolderRoundImageView", loader);
            scope(image, "onDraw", HOSTS::contains);
            Class<?> layer = XposedHelpers.findClass(
                    "com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable", loader);
            scope(layer, "draw", HOSTS::contains);
            scope(layer, "setCornerRadius", HOSTS::contains);
            // The older renderer chooses smooth corners with this boolean.
            XposedBridge.hookAllMethods(layer, "setCornerRadius", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (enabled() && HOSTS.contains(p.thisObject) && p.args.length == 2)
                        p.args[1] = false;
                }
            });
        } catch (XposedHelpers.ClassNotFoundError ignored) { /* Optional renderer. */ }
        try {
            Class<?> smooth = XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager", loader);
            XC_MethodHook path = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!inScope() || p.args.length < 4 || !(p.args[0] instanceof Path target)
                            || !(p.args[1] instanceof RectF bounds) || !(p.args[2] instanceof Float x)) return;
                    float y = x;
                    String name = p.method.getName();
                    if ((name.equals("createIconFolderClipPath") || p.args.length == 6)
                            && p.args[3] instanceof Float value) y = value;
                    Path.Direction direction = Path.Direction.CW;
                    for (Object arg : p.args) if (arg instanceof Path.Direction d) direction = d;
                    target.reset();
                    target.addRoundRect(bounds, x, y, direction);
                    p.setResult(null);
                }
            };
            for (String name : new String[] { "createIconFolderClipPath", "createNormalPath", "createSmoothPath" })
                XposedBridge.hookAllMethods(smooth, name, path);
        } catch (XposedHelpers.ClassNotFoundError ignored) { /* Older launcher. */ }
        try {
            Class<?> transition = XposedHelpers.findClass(
                    "com.android.launcher3.uioverrides.states.blurdrawable.BlurTransitionDrawable", loader);
            Class<?> corner = XposedHelpers.findClass("com.oplus.posteffect.params.CornerType", loader);
            Object conic = XposedHelpers.callStaticMethod(corner, "valueOf", "CONIC");
            Class<?> pair = XposedHelpers.findClass("kotlin.Pair", loader);
            XposedBridge.hookAllMethods(transition, "resolveCornerTypeAndWeight", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (inScope()) p.setResult(XposedHelpers.newInstance(pair, conic, 1f));
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) { /* Legacy blur uses a boolean. */ }
    }

    private static boolean inScope() { return enabled() && DEPTH.get() > 0; }

    private static void scope(Class<?> type, String name, Predicate<Object> supported) {
        XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled() || !supported.test(p.thisObject)) return;
                p.setObjectExtra("folder_corner_debug_scope", true);
                DEPTH.set(DEPTH.get() + 1);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(p.getObjectExtra("folder_corner_debug_scope")))
                    DEPTH.set(Math.max(0, DEPTH.get() - 1));
            }
        });
    }

    static void track(Object drawable, Object image, float radius) {
        if (drawable != null) HOSTS.add(drawable);
        if (!(image instanceof View view)) return;
        HOSTS.add(view);
        DebugOutline saved = OUTLINES.get(view);
        boolean changed = false;
        if (enabled()) {
            if (saved == null || view.getOutlineProvider() != saved) {
                saved = new DebugOutline(view.getOutlineProvider());
                OUTLINES.put(view, saved);
                view.setOutlineProvider(saved);
                changed = true;
            }
            changed |= Float.compare(saved.radius, radius) != 0;
            saved.radius = radius;
        } else if (saved != null) {
            if (view.getOutlineProvider() == saved) view.setOutlineProvider(saved.original);
            OUTLINES.remove(view);
            changed = true;
        }
        if (changed) view.invalidateOutline();
    }

    static void cleanup() {
        active = false;
        for (Map.Entry<View, DebugOutline> entry : new ArrayList<>(OUTLINES.entrySet())) {
            View view = entry.getKey();
            if (view.getOutlineProvider() == entry.getValue()) view.setOutlineProvider(entry.getValue().original);
        }
        OUTLINES.clear();
        HOSTS.clear();
        DEPTH.remove();
    }

    private static final class DebugOutline extends ViewOutlineProvider {
        final ViewOutlineProvider original;
        float radius;
        DebugOutline(ViewOutlineProvider original) { this.original = original; }
        @Override public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }
}
