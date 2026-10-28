package com.rikumi.colorosmod.hooks;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.util.LruCache;
import com.rikumi.colorosmod.graphics.BicubicPixels;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** Only bitmaps originating from the launcher's icon-pack loader participate. */
final class IconPackBicubic {
    private static final Map<Bitmap, Boolean> packBitmaps = Collections.synchronizedMap(new WeakHashMap<>());
    private static final LruCache<Key, Bitmap> scaled = new LruCache<>(16 * 1024 * 1024) {
        @Override protected int sizeOf(Key key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private record Key(Bitmap bitmap, int left, int top, int width, int height, int outWidth, int outHeight) {}

    static void track(Object drawable) {
        track(drawable, 0);
    }

    private static void track(Object drawable, int depth) {
        if (depth > 12) return;
        if (drawable instanceof BitmapDrawable bitmap) packBitmaps.put(bitmap.getBitmap(), Boolean.TRUE);
        else if (drawable instanceof AdaptiveIconDrawable adaptive) {
            track(adaptive.getBackground(), depth + 1);
            track(adaptive.getForeground(), depth + 1);
        } else if (drawable instanceof LayerDrawable layers) {
            for (int i = 0; i < layers.getNumberOfLayers(); i++) track(layers.getDrawable(i), depth + 1);
        } else if (drawable instanceof DrawableWrapper wrapper) track(wrapper.getDrawable(), depth + 1);
        else if (drawable instanceof Drawable current && current.getCurrent() != current)
            track(current.getCurrent(), depth + 1);
    }

    static void hook(BooleanSupplier enabled) {
        XC_MethodHook resample = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!enabled.getAsBoolean() || p.args.length != 4 || !(p.args[0] instanceof Bitmap original)
                        || !packBitmaps.containsKey(original) || original.isRecycled()) return;
                Rect crop = p.args[1] instanceof Rect rect ? rect : new Rect(0, 0, original.getWidth(), original.getHeight());
                if (crop.isEmpty() || crop.left < 0 || crop.top < 0
                        || crop.right > original.getWidth() || crop.bottom > original.getHeight()) return;
                float width, height;
                if (p.args[2] instanceof Rect target) { width = target.width(); height = target.height(); }
                else if (p.args[2] instanceof RectF target) { width = target.width(); height = target.height(); }
                else return;
                // The compositor also scales its canvas. Account for that transform,
                // so scaling within the native composition does not remain bilinear.
                float[] matrix = new float[9];
                ((Canvas) p.thisObject).getMatrix().getValues(matrix);
                if (matrix[Matrix.MPERSP_0] != 0 || matrix[Matrix.MPERSP_1] != 0) return;
                width *= (float) Math.hypot(matrix[Matrix.MSCALE_X], matrix[Matrix.MSKEW_Y]);
                height *= (float) Math.hypot(matrix[Matrix.MSKEW_X], matrix[Matrix.MSCALE_Y]);
                if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) return;
                int outWidth = (int) Math.ceil(width), outHeight = (int) Math.ceil(height);
                if ((outWidth <= crop.width() && outHeight <= crop.height())
                        || outWidth > 2048 || outHeight > 2048) return;
                Key key = new Key(original, crop.left, crop.top, crop.width(), crop.height(), outWidth, outHeight);
                Bitmap result;
                synchronized (scaled) { result = scaled.get(key); }
                if (result == null) {
                    Bitmap readable = original.getConfig() == Bitmap.Config.HARDWARE
                            ? original.copy(Bitmap.Config.ARGB_8888, false) : original;
                    if (readable == null) return;
                    int[] pixels = new int[Math.multiplyExact(crop.width(), crop.height())];
                    try { readable.getPixels(pixels, 0, crop.width(), crop.left, crop.top, crop.width(), crop.height()); }
                    finally { if (readable != original) readable.recycle(); }
                    int[] output = BicubicPixels.resize(pixels, crop.width(), crop.height(), outWidth, outHeight);
                    result = Bitmap.createBitmap(output, outWidth, outHeight, Bitmap.Config.ARGB_8888);
                    result.setDensity(original.getDensity());
                    synchronized (scaled) { scaled.put(key, result); }
                }
                // Keep the original destination, canvas transform and Paint (including
                // alpha, dark-mode filter and mask Porter-Duff operation) unchanged.
                p.args[0] = result;
                p.args[1] = null;
            }
        };
        XposedHelpers.findAndHookMethod(Canvas.class, "drawBitmap", Bitmap.class, Rect.class, Rect.class, Paint.class, resample);
        XposedHelpers.findAndHookMethod(Canvas.class, "drawBitmap", Bitmap.class, Rect.class, RectF.class, Paint.class, resample);
        // Hardware icon rasterization uses RecordingCanvas overrides, which bypass
        // Canvas's software implementations.
        for (String name : new String[]{"android.graphics.BaseRecordingCanvas", "android.graphics.RecordingCanvas"}) {
            Class<?> recording = XposedHelpers.findClassIfExists(name, Canvas.class.getClassLoader());
            if (recording != null) XposedBridge.hookAllMethods(recording, "drawBitmap", resample);
        }
    }

    static void cleanup() { packBitmaps.clear(); synchronized (scaled) { scaled.evictAll(); } }
    private IconPackBicubic() {}
}
