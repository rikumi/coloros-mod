package com.rikumi.colorosmod.hooks;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.rikumi.colorosmod.xposed.*;
import java.util.*;
import java.lang.ref.WeakReference;
import static com.rikumi.colorosmod.XposedInit.*;

/** Desktop label size and the closed folder's background, including folder contents, independent of drawer/panel styling. */
final class LauncherIconAppearanceHooks {
    private static final String FONT_ENABLED = "launcher_icon_font_size_enabled";
    private static final String FONT_SIZE = "launcher_icon_font_size_sp_tenths";
    private static final String CORNER_ENABLED = "launcher_folder_corner_radius_enabled";
    private static final String CORNER_SIZE = "launcher_folder_corner_radius_dp";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<TextView, Float> FONTS = new WeakHashMap<>();
    private static final Map<Object, Background> BACKGROUNDS = new WeakHashMap<>();
    private static final Set<View> ROOTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);
    private static Class<?> bubbleClass, folderClass;
    private static volatile boolean active = true;
    private static List<Integer> lastSettings;
    private static boolean reported;
    private static final Runnable UPDATE = () -> {
        if (!active) return;
        for (View root : new ArrayList<>(ROOTS)) {
            try { discover(root); } catch (Throwable error) { report(error); }
        }
        for (TextView text : new ArrayList<>(FONTS.keySet())) applyFont(text);
        for (Object bg : new ArrayList<>(BACKGROUNDS.keySet())) applyBackground(bg);
    };

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        bubbleClass = XposedHelpers.findClass("com.android.launcher3.BubbleTextView", lp.classLoader);
        folderClass = XposedHelpers.findClass("com.android.launcher3.folder.FolderIcon", lp.classLoader);
        XposedBridge.hookAllConstructors(bubbleClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (active && !p.hasThrowable()) trackFont((TextView) p.thisObject);
            }
        });
        XposedHelpers.findAndHookMethod(TextView.class, "setTextSize", int.class, float.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!active || APPLYING.get() || !(p.thisObject instanceof TextView text)
                                || !FONTS.containsKey(text) || !supported(text)) return;
                        float nativePx = TypedValue.applyDimension((Integer) p.args[0], (Float) p.args[1],
                                text.getResources().getDisplayMetrics());
                        FONTS.put(text, nativePx);
                        if (readBoolCached(FONT_ENABLED, false)) {
                            p.args[0] = TypedValue.COMPLEX_UNIT_PX;
                            p.args[1] = fontPx(text);
                        }
                    }
                });
        XC_MethodHook label = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (active) trackFont((TextView) p.thisObject);
            }
        };
        XposedBridge.hookAllMethods(bubbleClass, "onMeasure", label);
        XposedBridge.hookAllMethods(bubbleClass, "onAttachedToWindow", label);
        try {
            Class<?> preview = XposedHelpers.findClass(
                    "com.android.launcher3.folder.OplusPreviewBackground", lp.classLoader);
            for (java.lang.reflect.Method method : preview.getDeclaredMethods())
                if (method.getName().equals("setup")) XposedBridge.deoptimize(method);
            // The ROM assigns mRadius directly here, before creating either the solid or blur drawable.
            XposedBridge.hookAllMethods(preview, "updateParamsBeforeSetup", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!active || p.hasThrowable() || owner(p.thisObject) == null) return;
                    float radius = XposedHelpers.getFloatField(p.thisObject, "mRadius");
                    BACKGROUNDS.put(p.thisObject, new Background(radius));
                    applyBackground(p.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(preview, "setRadius", float.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!active || APPLYING.get() || owner(p.thisObject) == null) return;
                    Background state = BACKGROUNDS.computeIfAbsent(p.thisObject,
                            ignored -> new Background((Float) p.args[0]));
                    state.nativeRadius = (Float) p.args[0];
                    if (readBoolCached(CORNER_ENABLED, false)) p.args[0] = cornerPx(owner(p.thisObject));
                    state.appliedRadius = Float.NaN;
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (active && !APPLYING.get() && !p.hasThrowable()) applyBackground(p.thisObject);
                }
            });
            for (String name : new String[] { "initBgDrawable", "setBackground", "drawBackground" })
                XposedBridge.hookAllMethods(preview, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (active && "drawBackground".equals(p.method.getName())) applyBackground(p.thisObject);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (active && !p.hasThrowable() && !"drawBackground".equals(p.method.getName()))
                            applyBackground(p.thisObject);
                    }
                });
        } catch (Throwable error) { log("launcher folder icon corners unavailable: " + error); }
    }

    private static boolean supported(TextView text) {
        if (bubbleClass == null || !bubbleClass.isInstance(text)) return false;
        Object parent = text.getParent();
        if (folderClass != null && folderClass.isInstance(parent))
            return XposedHelpers.callMethod(parent, "getFolderName") == text;
        // DISPLAY_WORKSPACE=0 and DISPLAY_FOLDER=2; exclude all-apps, search and taskbar.
        int display = XposedHelpers.getIntField(text, "mDisplay");
        return display == 0 || display == 2;
    }

    private static void trackFont(TextView text) {
        if (!supported(text)) return;
        FONTS.putIfAbsent(text, text.getTextSize());
        applyFont(text);
    }

    private static float fontPx(TextView text) {
        float sp = Math.max(80, Math.min(160, readIntCached(FONT_SIZE, 110))) / 10f;
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                text.getResources().getDisplayMetrics());
    }

    private static void applyFont(TextView text) {
        try { applyFontUnchecked(text); } catch (Throwable error) { report(error); }
    }

    private static void applyFontUnchecked(TextView text) {
        if (!supported(text)) return;
        Float original = FONTS.get(text);
        if (original == null) return;
        float px = readBoolCached(FONT_ENABLED, false) ? fontPx(text) : original;
        if (Math.abs(px - text.getTextSize()) < .01f) return;
        boolean disabled = XposedHelpers.getBooleanField(text, "mDisableRelayout");
        APPLYING.set(true);
        try {
            XposedHelpers.setBooleanField(text, "mDisableRelayout", false);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
            text.requestLayout();
            text.invalidate();
        } finally {
            XposedHelpers.setBooleanField(text, "mDisableRelayout", disabled);
            APPLYING.set(false);
        }
    }

    private static View owner(Object bg) {
        Object view = XposedHelpers.getObjectField(bg, "mInvalidateDelegate");
        return folderClass != null && folderClass.isInstance(view) ? (View) view : null;
    }

    private static float cornerPx(View view) {
        return Math.max(4, Math.min(36, readIntCached(CORNER_SIZE, 12)))
                * view.getResources().getDisplayMetrics().density;
    }

    private static void applyBackground(Object bg) {
        try { applyBackgroundUnchecked(bg); } catch (Throwable error) { report(error); }
    }

    private static void applyBackgroundUnchecked(Object bg) {
        if (bg == null) return;
        View view = owner(bg);
        if (view == null) return;
        Background state = BACKGROUNDS.computeIfAbsent(bg,
                ignored -> new Background(XposedHelpers.getFloatField(bg, "mRadius")));
        float radius = readBoolCached(CORNER_ENABLED, false) ? cornerPx(view) : state.nativeRadius;
        Object drawable = XposedHelpers.getObjectField(bg, "mBgDrawable");
        Object bgView = XposedHelpers.getObjectField(bg, "mBgView");
        if (Float.compare(state.appliedRadius, radius) == 0 && state.drawable.get() == drawable
                && state.bgView.get() == bgView && XposedHelpers.getFloatField(bg, "mRadius") == radius) return;
        APPLYING.set(true);
        try {
            XposedHelpers.callMethod(bg, "setRadius", radius);
            // Theme layers and the blur property also keep their own corner values.
            if (drawable instanceof GradientDrawable gradient) gradient.setCornerRadius(radius);
            else if (drawable != null) {
                try { XposedHelpers.callMethod(drawable, "setRadius", radius); }
                catch (NoSuchMethodError ignored) { /* LayerBlurDrawable handled by native setRadius. */ }
            }
            Object blur = XposedHelpers.getObjectField(bg, "mBlurProp");
            if (blur != null) XposedHelpers.callMethod(blur, "setBlurCornerRadius", radius, true);
            if (bgView instanceof View image) {
                XposedHelpers.setFloatField(image, "mRadius", radius);
                image.invalidate();
            }
            state.appliedRadius = radius;
            state.drawable = new WeakReference<>(drawable);
            state.bgView = new WeakReference<>(bgView);
            if (drawable instanceof Drawable d) d.invalidateSelf();
            view.invalidate();
        } finally { APPLYING.set(false); }
    }

    private static void discover(View view) {
        if (view instanceof TextView text && bubbleClass != null && bubbleClass.isInstance(text)) trackFont(text);
        if (folderClass != null && folderClass.isInstance(view))
            applyBackground(XposedHelpers.getObjectField(view, "mBackground"));
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) discover(group.getChildAt(i));
    }

    static void onSettingsSnapshotPublished(Map<String, Integer> settings) {
        List<Integer> values = Arrays.asList(settings.getOrDefault(FONT_ENABLED, 0),
                settings.getOrDefault(FONT_SIZE, 110), settings.getOrDefault(CORNER_ENABLED, 0),
                settings.getOrDefault(CORNER_SIZE, 12));
        if (values.equals(lastSettings)) return;
        lastSettings = values;
        refresh();
    }
    static void refresh() { if (active) { MAIN.removeCallbacks(UPDATE); MAIN.post(UPDATE); } }
    static void onWorkspaceBound(View root) { if (active && root != null) { ROOTS.add(root); refresh(); } }

    static Object captureViews() {
        List<Object> saved = new ArrayList<>();
        for (Map.Entry<TextView, Float> e : FONTS.entrySet()) saved.add(new Object[] { e.getKey(), e.getValue() });
        for (Map.Entry<Object, Background> e : BACKGROUNDS.entrySet())
            saved.add(new Object[] { e.getKey(), e.getValue().nativeRadius });
        return saved.toArray();
    }
    static void restoreViews(Object saved) {
        if (!(saved instanceof Object[] entries)) return;
        for (Object entry : entries) if (entry instanceof Object[] pair && pair.length == 2 && pair[1] instanceof Float value) {
            if (pair[0] instanceof TextView text) FONTS.put(text, value);
            else BACKGROUNDS.put(pair[0], new Background(value));
        }
        refresh();
    }
    static void cleanup() {
        active = false;
        MAIN.removeCallbacks(UPDATE);
        FONTS.clear(); BACKGROUNDS.clear(); ROOTS.clear(); lastSettings = null;
    }
    private static final class Background {
        float nativeRadius, appliedRadius = Float.NaN;
        WeakReference<Object> drawable = new WeakReference<>(null), bgView = new WeakReference<>(null);
        Background(float radius) { nativeRadius = radius; }
    }
    private static void report(Throwable error) {
        if (!reported) { reported = true; log("launcher icon appearance update failed: " + error); }
    }
    private LauncherIconAppearanceHooks() {}
}
