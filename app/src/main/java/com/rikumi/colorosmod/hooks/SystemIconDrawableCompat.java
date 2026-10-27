package com.rikumi.colorosmod.hooks;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;

/** Keep public icon API contracts; never return a generic live wrapper. */
final class SystemIconDrawableCompat {
    static Drawable replace(Resources resources, Drawable original, Drawable themed) {
        if (original == null || themed == null || original == themed) return original;
        Drawable result;
        if (original.getClass() == BitmapDrawable.class) {
            Bitmap source = ((BitmapDrawable) original).getBitmap();
            if (source == null || source.isRecycled()) return original;
            Bitmap pixels = render(themed, source);
            result = new BitmapDrawable(resources, pixels);
            int density = source.getDensity() > 0 && original.getIntrinsicWidth() > 0
                    ? Math.round(original.getIntrinsicWidth() * (float) source.getDensity() / source.getWidth()) : 0;
            ((BitmapDrawable) result).setTargetDensity(density);
        } else if (original.getClass() == themed.getClass()) {
            Drawable.ConstantState state = themed.getConstantState();
            if (state == null) return original;
            result = state.newDrawable(resources).mutate();
        } else if (original.getClass() == AdaptiveIconDrawable.class && themed instanceof BitmapDrawable) {
            // AdaptiveIconDrawable expands layers by 25% on each side. Compensate that
            // expansion so the complete already-themed image stays inside its viewport.
            Drawable.ConstantState state = themed.getConstantState();
            if (state == null) return original;
            result = new AdaptiveIconDrawable(new ColorDrawable(0),
                    new InsetDrawable(state.newDrawable(resources).mutate(), 1f / 6f));
        } else {
            // Vendor subclasses, vectors and animated icons can have extra contracts.
            return original;
        }
        result.setBounds(original.getBounds());
        result.setLevel(original.getLevel());
        result.setState(original.getState());
        result.setLayoutDirection(original.getLayoutDirection());
        result.setVisible(original.isVisible(), false);
        result.setAlpha(original.getAlpha());
        return result;
    }

    static Bitmap render(Drawable themed, Bitmap original) {
        Bitmap.Config config = original.getConfig();
        if (config == null || config == Bitmap.Config.HARDWARE) config = Bitmap.Config.ARGB_8888;
        Bitmap pixels = original.getColorSpace() != null && config != Bitmap.Config.ALPHA_8
                ? Bitmap.createBitmap(original.getWidth(), original.getHeight(), config, original.hasAlpha(), original.getColorSpace())
                : Bitmap.createBitmap(original.getWidth(), original.getHeight(), config);
        pixels.setDensity(original.getDensity());
        Rect bounds = new Rect(themed.getBounds());
        try {
            themed.setBounds(0, 0, pixels.getWidth(), pixels.getHeight());
            themed.draw(new Canvas(pixels));
        } catch (RuntimeException | Error failure) {
            pixels.recycle();
            throw failure;
        } finally { themed.setBounds(bounds); }
        if (original.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap hardware = pixels.copy(Bitmap.Config.HARDWARE, false);
            if (hardware != null) { hardware.setDensity(original.getDensity()); pixels.recycle(); return hardware; }
            pixels.recycle();
            return original;
        }
        if (!original.isMutable()) {
            Bitmap immutable = pixels.copy(config, false);
            pixels.recycle();
            if (immutable == null) return original;
            immutable.setDensity(original.getDensity());
            return immutable;
        }
        return pixels;
    }

    private SystemIconDrawableCompat() {}
}
