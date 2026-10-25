package com.rikumi.colorosmod.hooks;

import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;

final class ColorOsNavigationEffect implements AutoCloseable {
    private final Handler main;
    private final SurfaceControl parent, layer;
    private final ColorOsNavigationPolicy policy;
    private final Method setBuffer, setColorSpace, metadata;
    private final Object listener;
    private final Runnable reduce;
    private volatile boolean closed;
    private boolean shortRefresh;
    private int frameRate = -1000;
    private float downX, downY;
    private Rect crop;

    ColorOsNavigationEffect(ClassLoader loader, Handler main, AppNavigationTaskSurface.Target target,
            Rect navigation, BiConsumer<Integer, Float> colors) throws ReflectiveOperationException {
        this.main = main;
        policy = target.policy;
        Class<?> transaction = SurfaceControl.Transaction.class;
        setBuffer = transaction.getMethod("setBuffer", SurfaceControl.class, HardwareBuffer.class);
        setColorSpace = transaction.getMethod("setColorSpace", SurfaceControl.class, ColorSpace.class);
        metadata = transaction.getMethod("setMetadata", SurfaceControl.class, int.class, int.class);
        Method trusted = transaction.getMethod("setTrustedOverlay", SurfaceControl.class, boolean.class);
        Method relative = transaction.getMethod("setRelativeLayer", SurfaceControl.class, SurfaceControl.class, int.class);
        Class<?> callback = loader.loadClass("android.view.SurfaceControl$OnColorPickedListener");
        Method addListener = transaction.getMethod("addOnColorPickedListener", SurfaceControl.class, callback);
        listener = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
            if ("onColorPicked".equals(method.getName())) {
                int color = ColorOsNavigationPolicy.toArgb((Integer) args[0]);
                float alpha = (Float) args[1];
                main.post(() -> { if (!closed) colors.accept(color, alpha); });
            } else if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            else if ("equals".equals(method.getName())) return proxy == args[0];
            else if ("toString".equals(method.getName())) return "ColorOSMod native navigation color listener";
            return null;
        });

        Method ownerMetadata = SurfaceControl.Builder.class.getMethod("setMetadata", int.class, int.class);
        SurfaceControl.Builder source = new SurfaceControl.Builder().setName("ColorOSMod navigation source")
                .setParent(target.surface).setBufferSize(target.bounds.width(), target.bounds.height());
        ownerMetadata.invoke(source, 1, target.uid);
        ownerMetadata.invoke(source, 2, 1);
        ownerMetadata.invoke(source, 3, target.id);
        parent = source.build();
        SurfaceControl created = null;
        try {
            SurfaceControl.Builder builder = new SurfaceControl.Builder().setName(ColorOsNavigationPolicy.LAYER_NAME)
                    .setParent(parent).setOpaque(true);
            ownerMetadata.invoke(builder, 1, target.uid);
            ownerMetadata.invoke(builder, 2, 1);
            ownerMetadata.invoke(builder, 3, target.id);
            builder.getClass().getMethod("setColorLayer").invoke(builder);
            created = builder.build();
            layer = created;
            crop = local(navigation, target.bounds);
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                trusted.invoke(t, layer, true);
                addListener.invoke(t, layer, listener);
                t.setLayer(parent, Integer.MIN_VALUE).setPosition(parent, 0, 0).setVisibility(parent, true);
                // Keep the native fill above the app while its sampling buffer stays below it.
                relative.invoke(t, layer, target.windowSurface, 1);
                t.setCrop(layer, crop).setVisibility(layer, true).apply();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (created != null) AppNavigationTaskSurface.remove(created);
            AppNavigationTaskSurface.remove(parent);
            throw error;
        }
        reduce = () -> {
            if (!closed) try { refreshRate(policy.minFrameRate); }
            catch (ReflectiveOperationException error) { com.rikumi.colorosmod.XposedInit.log("native navigation refresh failed: " + error); }
        };
        try { refresh(policy.startReduceTime); }
        catch (ReflectiveOperationException | RuntimeException error) { close(); throw error; }
    }

    synchronized void submit(HardwareBuffer buffer, ColorSpace colorSpace, Rect navigation, Rect bounds)
            throws ReflectiveOperationException {
        if (closed) return;
        Rect next = local(navigation, bounds);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            setBuffer.invoke(t, parent, buffer);
            if (colorSpace != null) setColorSpace.invoke(t, parent, colorSpace);
            if (!crop.equals(next)) t.setCrop(layer, next);
            t.apply();
        }
        if (!crop.equals(next)) { crop = next; refresh(200); }
    }

    synchronized void input(int action, float x, float y) throws ReflectiveOperationException {
        if (closed) return;
        float dx = Math.abs(x - downX), dy = Math.abs(y - downY);
        if (action == MotionEvent.ACTION_DOWN) {
            downX = x; downY = y; refresh(policy.moveReduceTime);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (dy < 32f || dx > dy) refresh(policy.moveReduceTime);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dx > dy || (dx < 32f && dy < 32f)) refresh(policy.inputReduceTime);
            else if (frameRate != 0 || shortRefresh) {
                shortRefresh = false; refreshRate(60); scheduleReduce(400);
            }
        }
    }

    private void refresh(long delay) throws ReflectiveOperationException {
        shortRefresh = delay == policy.moveReduceTime;
        refreshRate(0);
        scheduleReduce(delay);
    }

    private void scheduleReduce(long delay) {
        main.removeCallbacks(reduce);
        main.postDelayed(reduce, Math.max(0, delay));
    }

    private synchronized void refreshRate(int rate) throws ReflectiveOperationException {
        if (closed || frameRate == rate) return;
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            metadata.invoke(t, layer, ColorOsNavigationPolicy.FRAME_RATE, rate);
            metadata.invoke(t, layer, ColorOsNavigationPolicy.FULLSCREEN_VARIANCE, policy.fullscreenVariance);
            metadata.invoke(t, layer, ColorOsNavigationPolicy.VARIANCE, policy.variance);
            t.apply();
        }
        frameRate = rate;
    }

    synchronized SurfaceControl sourceCopy(AppNavigationTaskSurface surfaces) throws ReflectiveOperationException {
        return surfaces.copy(parent);
    }

    private static Rect local(Rect screen, Rect bounds) {
        Rect rect = new Rect(screen); rect.offset(-bounds.left, -bounds.top); return rect;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(reduce);
        try { AppNavigationTaskSurface.remove(layer); }
        finally { AppNavigationTaskSurface.remove(parent); }
    }
}
