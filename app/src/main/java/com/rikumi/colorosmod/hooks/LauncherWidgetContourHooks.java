package com.rikumi.colorosmod.hooks;

import android.appwidget.AppWidgetHostView;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.rikumi.colorosmod.xposed.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import static com.rikumi.colorosmod.XposedInit.*;

/** Adapts Flyme's widget background contour to Android/ColorOS widget hosts. */
final class LauncherWidgetContourHooks {
    private static final String ENABLED = "launcher_widget_contour_enabled";
    private static final String OUTER = "colorosmod.widgetContour.outer";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Class<?>> TYPES = new HashSet<>();
    private static final Set<Method> METHODS = new HashSet<>();
    private static final Set<AppWidgetHostView> HOSTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<AppWidgetHostView, Entry> ENTRIES = new WeakHashMap<>();
    private static final ThreadLocal<Set<Object>> DRAWING = ThreadLocal.withInitial(HashSet::new);
    private static volatile boolean active = true;
    private static Boolean lastEnabled;
    private static boolean reported;
    private static final View.OnLayoutChangeListener LAYOUT = (view, l, t, r, b, ol, ot, or, ob) -> {
        if (active) ENTRIES.remove(view);
    };
    private static final Runnable REFRESH = () -> {
        if (!active) return;
        ENTRIES.clear();
        AppWidgetHostView[] hosts;
        synchronized (HOSTS) { hosts = HOSTS.toArray(new AppWidgetHostView[0]); }
        for (AppWidgetHostView host : hosts) host.invalidate();
    };

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT < 33) return;
        hookType(AppWidgetHostView.class);
        Class<?> launcher = XposedHelpers.findClassIfExists(
                "com.android.launcher3.widget.LauncherAppWidgetHostView", lp.classLoader);
        if (launcher != null) hookType(launcher);
        XposedBridge.hookAllConstructors(AppWidgetHostView.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (active && p.thisObject instanceof AppWidgetHostView host) {
                    track(host);
                    // Register OEM overrides too; base constructors expose the final runtime class.
                    hookType(host.getClass());
                }
            }
        });
        XposedBridge.hookAllMethods(AppWidgetHostView.class, "updateAppWidget", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (active && p.thisObject instanceof AppWidgetHostView host) {
                    ENTRIES.remove(host);
                    host.invalidate();
                }
            }
        });
    }

    private static synchronized void hookType(Class<?> type) {
        if (!TYPES.add(type)) return;
        for (Class<?> owner = type; owner != null && ViewGroup.class.isAssignableFrom(owner);
                owner = owner.getSuperclass()) {
            Method method;
            try { method = owner.getDeclaredMethod("dispatchDraw", Canvas.class); }
            catch (NoSuchMethodException absent) { continue; }
            if (!METHODS.add(method)) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (active && p.thisObject instanceof AppWidgetHostView)
                        p.setObjectExtra(OUTER, DRAWING.get().add(p.thisObject));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getObjectExtra(OUTER))) return;
                    try {
                        if (!active || p.hasThrowable()) return;
                        AppWidgetHostView host = (AppWidgetHostView) p.thisObject;
                        track(host);
                        Canvas canvas = (Canvas) p.args[0];
                        if (canvas.isHardwareAccelerated() && readBoolCached(ENABLED, false)) draw(host, canvas);
                    } catch (Exception | LinkageError error) {
                        if (!reported) {
                            reported = true;
                            log("launcher widget contour rendering failed: " + error);
                        }
                    } finally { DRAWING.get().remove(p.thisObject); }
                }
            });
        }
    }

    private static void track(AppWidgetHostView host) {
        synchronized (HOSTS) {
            if (HOSTS.add(host)) host.addOnLayoutChangeListener(LAYOUT);
        }
    }

    private static void draw(AppWidgetHostView host, Canvas canvas) throws Exception {
        Entry entry = ENTRIES.get(host);
        float density = host.getResources().getDisplayMetrics().density;
        if (entry == null) { entry = new Entry(); ENTRIES.put(host, entry); }
        View target = entry.target.get();
        if (!entry.matches(target, density)) {
            if (target == null && SystemClock.uptimeMillis() < entry.nextScan) return;
            entry.target.clear(); entry.mask = null;
            List<View> candidates = new ArrayList<>();
            collect(host, candidates);
            candidates.sort((a, b) -> Long.compare((long)b.getWidth()*b.getHeight(), (long)a.getWidth()*a.getHeight()));
            for (View candidate : candidates) {
                Bitmap mask = backgroundMask(candidate);
                if (mask != null) { entry.remember(candidate, mask, density); break; }
            }
            target = entry.target.get();
            if (target == null) { entry.nextScan = SystemClock.uptimeMillis()+500L; return; }
        }
        if (!target.isShown()) return;
        Matrix transform = new Matrix(), inverse = new Matrix();
        target.transformMatrixToGlobal(transform);
        host.transformMatrixToGlobal(inverse);
        if (!inverse.invert(inverse)) return;
        transform.postConcat(inverse);
        float alpha = 1f;
        for (View view = target; view != host; ) {
            alpha *= view.getAlpha();
            if (!(view.getParent() instanceof View parent)) return;
            view = parent;
        }
        if (entry.renderer == null) entry.renderer = new LauncherContourRenderer(host);
        int saved = canvas.save();
        try {
            canvas.concat(transform);
            entry.renderer.draw(host, canvas, entry.bounds, 0f, alpha, entry.mask);
        } finally { canvas.restoreToCount(saved); }
    }

    private static void collect(View view, List<View> candidates) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0) return;
        if (surface(view) != null && view.getWidth() > 0 && view.getHeight() > 0) candidates.add(view);
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), candidates);
    }

    private static Drawable surface(View view) {
        Drawable background = view.getBackground();
        return background != null ? background : view instanceof ImageView image ? image.getDrawable() : null;
    }

    private static Bitmap backgroundMask(View view) {
        Drawable drawable = surface(view);
        float scale = Math.min(1f, 512f/Math.max(view.getWidth(), view.getHeight()));
        int w = Math.max(1, Math.round(view.getWidth()*scale));
        int h = Math.max(1, Math.round(view.getHeight()*scale));
        Bitmap sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sample);
        canvas.scale(scale, scale);
        Rect saved = new Rect(drawable.getBounds());
        int[] pixels = new int[w*h];
        try {
            if (view.getBackground() == null && view instanceof ImageView image) {
                canvas.translate(view.getPaddingLeft(), view.getPaddingTop());
                canvas.concat(image.getImageMatrix());
                drawable.draw(canvas);
            } else {
                drawable.setBounds(0, 0, view.getWidth(), view.getHeight());
                drawable.draw(canvas);
            }
            sample.getPixels(pixels, 0, w, 0, 0, w, h);
        } finally { drawable.setBounds(saved); sample.recycle(); }
        int filled = 0;
        for (int pixel : pixels) if ((pixel >>> 24) >= 8) filled++;
        if (filled < pixels.length*.5) return null;
        return Bitmap.createBitmap(WidgetContourPixels.mask(pixels, w, h,
                2f*view.getResources().getDisplayMetrics().density*scale), w, h, Bitmap.Config.ARGB_8888);
    }

    static void onSettingsSnapshotPublished(Map<String, Integer> settings) {
        if (!active) return;
        boolean enabled = settings.getOrDefault(ENABLED, 0) == 1;
        synchronized (HOSTS) {
            if (lastEnabled != null && lastEnabled == enabled) return;
            lastEnabled = enabled;
        }
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }

    static void cleanup() {
        active = false;
        MAIN.removeCallbacks(REFRESH);
        synchronized (HOSTS) {
            for (AppWidgetHostView host : HOSTS) host.removeOnLayoutChangeListener(LAYOUT);
            HOSTS.clear();
        }
        ENTRIES.clear();
        DRAWING.remove();
    }

    private static final class Entry {
        WeakReference<View> target = new WeakReference<>(null);
        Drawable background;
        int width, height, level, state;
        float density;
        long nextScan;
        Bitmap mask;
        final RectF bounds = new RectF();
        LauncherContourRenderer renderer;

        boolean matches(View view, float density) {
            return view != null && mask != null && surface(view) == background
                    && width == view.getWidth() && height == view.getHeight() && this.density == density
                    && level == background.getLevel() && state == Arrays.hashCode(background.getState());
        }

        void remember(View view, Bitmap mask, float density) {
            target = new WeakReference<>(view); background = surface(view);
            width = view.getWidth(); height = view.getHeight(); this.mask = mask; this.density = density;
            level = background.getLevel(); state = Arrays.hashCode(background.getState());
            bounds.set(0, 0, width, height);
        }
    }

    private LauncherWidgetContourHooks() {}
}
