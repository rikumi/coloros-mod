package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.rikumi.colorosmod.xposed.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import static com.rikumi.colorosmod.XposedInit.*;

/** Flyme's icon contour, applied to the final themed bitmap in the ColorOS launcher. */
final class LauncherIconContourHooks {
    private static final String ENABLED = "launcher_icon_contour_enabled";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Drawable> DRAWABLES = Collections.newSetFromMap(new WeakHashMap<>());
    // Keep shader inputs bounded even when the all-apps drawer contains hundreds of icons.
    private static final LinkedHashMap<Drawable, Frame> FRAMES = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Drawable, Frame> entry) {
            return size() > 96;
        }
    };
    private static final Map<Class<?>, IconAccess> ACCESS = new HashMap<>();
    private static volatile boolean active = true;
    private static Boolean lastEnabled;
    private static boolean reported;
    private static final ThreadLocal<Boolean> CAPTURING = ThreadLocal.withInitial(() -> false);
    private static final Runnable REFRESH = () -> {
        if (!active) return;
        Drawable[] drawables;
        synchronized (DRAWABLES) { drawables = DRAWABLES.toArray(new Drawable[0]); }
        for (Drawable drawable : drawables) drawable.invalidateSelf();
    };

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT < 33) return;
        Class<?> type = XposedHelpers.findClass("com.android.launcher3.icons.FastBitmapDrawable", lp.classLoader);
        XposedHelpers.findAndHookMethod(type, "draw", Canvas.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!active || p.hasThrowable() || CAPTURING.get()
                        || LauncherIconSurfaceContourHooks.isDrawing()) return;
                Drawable drawable = (Drawable) p.thisObject;
                synchronized (DRAWABLES) { DRAWABLES.add(drawable); }
                Canvas canvas = (Canvas) p.args[0];
                // Software snapshots must remain usable: Android RuntimeShader requires HWUI.
                if (!canvas.isHardwareAccelerated() || !readBoolCached(ENABLED, false)) return;
                Context context = sAppContext != null ? sAppContext : currentApplication();
                if (context == null) return;
                try { draw(context, drawable, canvas); }
                catch (Exception | LinkageError error) {
                    if (!reported) {
                        reported = true;
                        log("launcher icon contour rendering failed: " + error);
                    }
                }
            }
        });
        LauncherIconSurfaceContourHooks.hook(lp);
    }

    private static void draw(Context context, Drawable drawable, Canvas canvas) throws Exception {
        Rect bounds = drawable.getBounds();
        if (bounds.isEmpty() || drawable.getAlpha() == 0) return;
        float density = context.getResources().getDisplayMetrics().density;
        synchronized (FRAMES) {
            IconAccess access = ACCESS.computeIfAbsent(drawable.getClass(), IconAccess::new);
            Bitmap source = access.bitmap(drawable);
            if (source == null || source.isRecycled()) return;
            Frame frame = FRAMES.get(drawable);
            if (frame == null || !frame.matches(source, bounds, density)) {
                frame = new Frame(context, source, bounds, density);
                FRAMES.put(drawable, frame);
            }
            int saved = canvas.save();
            try {
                // FastBitmapDrawable restores its press-animation transform before returning.
                float scale = access.scale(drawable);
                canvas.scale(scale, scale, bounds.exactCenterX(), bounds.exactCenterY());
                frame.draw(context, canvas, bounds, drawable.getAlpha());
            } finally { canvas.restoreToCount(saved); }
        }
    }

    /** Called while the ROM records the icon into the surface used throughout the home animation. */
    static void drawSurfaceIcon(Context context, Drawable drawable, Canvas canvas) throws Exception {
        if (!active || !canvas.isHardwareAccelerated() || !readBoolCached(ENABLED, false)) return;
        Rect bounds = drawable.getBounds();
        if (bounds.isEmpty() || drawable.getAlpha() <= 0) return;
        float density = context.getResources().getDisplayMetrics().density;
        synchronized (FRAMES) {
            Bitmap source = drawable instanceof BitmapDrawable image ? image.getBitmap()
                    : ACCESS.computeIfAbsent(drawable.getClass(), IconAccess::new).bitmap(drawable);
            Frame frame = FRAMES.get(drawable);
            if (source != null && source.isRecycled()) return;
            if (frame == null || (source != null && !frame.matches(source, bounds, density))
                    || frame.width != bounds.width() || frame.height != bounds.height() || frame.density != density) {
                boolean captured = source == null;
                if (captured) source = capture(drawable);
                try { frame = new Frame(context, source, bounds, density); }
                finally { if (captured) source.recycle(); }
                FRAMES.put(drawable, frame);
            }
            frame.draw(context, canvas, bounds, drawable.getAlpha());
        }
    }

    private static Bitmap capture(Drawable drawable) {
        Rect saved = new Rect(drawable.getBounds());
        int width = Math.max(1, Math.min(512, saved.width()));
        int height = Math.max(1, Math.min(512, Math.round(saved.height()*(float)width/saved.width())));
        Bitmap pixels = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        CAPTURING.set(true);
        try {
            drawable.setBounds(0, 0, width, height);
            drawable.draw(new Canvas(pixels));
            return pixels;
        } catch (RuntimeException | Error error) { pixels.recycle(); throw error; }
        finally { drawable.setBounds(saved); CAPTURING.remove(); }
    }

    private static final class IconAccess {
        final Method bitmapGetter, scaleGetter;
        final Field bitmapField, infoField, infoIcon, scaleField;

        IconAccess(Class<?> type) {
            bitmapGetter = method(type, "getBitmap");
            bitmapField = field(type, "mBitmap");
            infoField = field(type, "mBitmapInfo");
            infoIcon = infoField == null ? null : field(infoField.getType(), "icon");
            scaleGetter = method(type, "getIconScale");
            scaleField = field(type, "mScale");
        }

        Bitmap bitmap(Drawable drawable) throws ReflectiveOperationException {
            Object result = bitmapGetter != null ? bitmapGetter.invoke(drawable)
                    : bitmapField != null ? bitmapField.get(drawable)
                    : infoIcon != null && infoField.get(drawable) != null
                        ? infoIcon.get(infoField.get(drawable)) : null;
            if (result instanceof Bitmap bitmap) return bitmap;
            return null;
        }

        float scale(Drawable drawable) throws ReflectiveOperationException {
            Object value = scaleGetter != null ? scaleGetter.invoke(drawable)
                    : scaleField != null ? scaleField.get(drawable) : 1f;
            return value instanceof Number number ? number.floatValue() : 1f;
        }

        private static Method method(Class<?> type, String name) {
            try { return XposedHelpers.findMethodExact(type, name); }
            catch (NoSuchMethodError absent) { return null; }
        }

        private static Field field(Class<?> type, String name) {
            for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
                try {
                    Field field = owner.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException absent) { /* Continue through the native base classes. */ }
            }
            return null;
        }
    }

    static void onSettingsSnapshotPublished(Map<String, Integer> settings) {
        if (!active) return;
        boolean enabled = settings.getOrDefault(ENABLED, 0) == 1;
        synchronized (DRAWABLES) {
            if (lastEnabled != null && lastEnabled == enabled) return;
            lastEnabled = enabled;
        }
        MAIN.removeCallbacks(REFRESH);
        MAIN.post(REFRESH);
    }

    static void cleanup() {
        active = false;
        MAIN.removeCallbacks(REFRESH);
        synchronized (DRAWABLES) { DRAWABLES.clear(); }
        synchronized (FRAMES) { FRAMES.clear(); ACCESS.clear(); }
        LauncherIconSurfaceContourHooks.cleanup();
        CAPTURING.remove();
    }

    private static final class Frame {
        final Bitmap source, mask, shadow;
        final LauncherContourRenderer renderer;
        final int generation, width, height;
        final float density;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF drawBounds = new RectF();

        Frame(Context context, Bitmap source, Rect bounds, float density) throws Exception {
            this.source = source;
            generation = source.getGenerationId();
            width = bounds.width(); height = bounds.height(); this.density = density;
            Bitmap readable = source.getConfig() == Bitmap.Config.HARDWARE
                    ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            if (readable == null) throw new IllegalStateException("Icon bitmap is unreadable");
            // Icon-pack clarity can retain a much larger bitmap than the on-screen icon.
            // Bound effect masks independently without changing the native icon pixels.
            int limit = Math.max(64, Math.min(256, Math.max(width, height) * 2));
            int largest = Math.max(readable.getWidth(), readable.getHeight());
            if (largest > limit) {
                Bitmap scaled = Bitmap.createScaledBitmap(readable,
                        Math.max(1, Math.round(readable.getWidth() * (float)limit / largest)),
                        Math.max(1, Math.round(readable.getHeight() * (float)limit / largest)), true);
                if (readable != source) readable.recycle();
                readable = scaled;
            }
            int w = readable.getWidth(), h = readable.getHeight();
            int[] pixels = new int[w*h];
            try { readable.getPixels(pixels, 0, w, 0, 0, w, h); }
            finally { if (readable != source) readable.recycle(); }
            mask = Bitmap.createBitmap(IconContourPixels.mask(pixels, w, h, 2f*density*w/width),
                    w, h, Bitmap.Config.ARGB_8888);
            for (int i = 0; i < pixels.length; i++) pixels[i] = (pixels[i] >>> 24) >= 64 ? 0xff000000 : 0;
            shadow = Bitmap.createBitmap(IconInnerShadow.pixels(pixels, w, h, 3f*density*w/width),
                    w, h, Bitmap.Config.ARGB_8888);
            renderer = new LauncherContourRenderer(context);
        }

        RectF bounds(Rect bounds) { drawBounds.set(bounds); return drawBounds; }

        void draw(Context context, Canvas canvas, Rect bounds, int alpha) {
            paint.setAlpha(alpha);
            canvas.drawBitmap(shadow, null, bounds, paint);
            renderer.drawIcon(context, canvas, bounds(bounds), alpha/255f, mask);
        }

        boolean matches(Bitmap bitmap, Rect bounds, float density) {
            return source == bitmap && generation == bitmap.getGenerationId()
                    && width == bounds.width() && height == bounds.height() && this.density == density;
        }
    }

    private LauncherIconContourHooks() {}
}
