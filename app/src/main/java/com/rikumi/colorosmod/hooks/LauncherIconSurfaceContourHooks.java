package com.rikumi.colorosmod.hooks;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.rikumi.colorosmod.xposed.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import static com.rikumi.colorosmod.XposedInit.*;

/** Records the same contour into ColorOS's floating icon surface before it is animated. */
final class LauncherIconSurfaceContourHooks {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static volatile boolean active = true;
    private static boolean reported, loggedDraw;
    private static final String PREFIX = "com.android.launcher3.anim.floatingdrawable.";
    private static final Map<View, ClipFrame> CLIPS = new WeakHashMap<>();

    static boolean isDrawing() { return DEPTH.get() > 0; }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        int installed = 0;
        for (String[] target : new String[][] {
                {"IconDrawableView", "onDraw", "originalIconDrawable"},
                {"MorphIconDrawableView", "onDraw", "originalIconDrawable"},
                {"DefaultIconDrawable", "draw", "originalIconDrawable"},
                {"InspirationClockDrawableView", "draw", "clockDrawable"}}) {
            Class<?> type = XposedHelpers.findClassIfExists(PREFIX+target[0], lp.classLoader);
            if (type == null) continue;
            try {
                Field source = field(type, target[2]);
                XposedHelpers.findAndHookDeclaredMethod(type, target[1], Canvas.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!active) return;
                        int depth = DEPTH.get();
                        p.setObjectExtra("iconContour.depth", depth);
                        DEPTH.set(depth+1);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object previous = p.getObjectExtra("iconContour.depth");
                        if (!(previous instanceof Integer depth)) return;
                        try {
                            if (!active || depth != 0 || p.hasThrowable()) return;
                            View host = (View) p.thisObject;
                            Object drawable = source.get(host);
                            Canvas canvas = (Canvas) p.args[0];
                            if (drawable instanceof Drawable icon && canvas.isHardwareAccelerated()
                                    && readBoolCached("launcher_icon_contour_enabled", false)) {
                                LauncherIconContourHooks.drawSurfaceIcon(host.getContext(), icon, canvas);
                                if (!loggedDraw) {
                                    loggedDraw = true;
                                    log("launcher icon contour recorded into animation surface: "
                                            + host.getClass().getSimpleName() + ", " + icon.getClass().getSimpleName());
                                }
                            }
                        } catch (Exception | LinkageError error) {
                            if (!reported) { reported = true; log("launcher animation icon contour failed: " + error); }
                        } finally {
                            if (depth == 0) DEPTH.remove(); else DEPTH.set(depth);
                        }
                    }
                });
                installed++;
            } catch (ReflectiveOperationException | LinkageError error) {
                log("launcher icon surface contour unavailable for " + type.getSimpleName() + ": " + error);
            }
        }
        Class<?> clip = XposedHelpers.findClassIfExists("com.android.launcher3.views.OplusClipIconView", lp.classLoader);
        if (clip != null) try {
            Field adaptive = field(clip, "mIsAdaptiveIcon");
            Field background = field(clip, "mBackground");
            Field clipPath = field(clip, "mClipPath");
            Field antiAlias = field(clip, "mDoAntiAlias");
            XposedHelpers.findAndHookDeclaredMethod(clip, "draw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!active) return;
                    p.setObjectExtra("iconContour.depth", DEPTH.get());
                    DEPTH.set(DEPTH.get()+1);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object previous = p.getObjectExtra("iconContour.depth");
                    if (!(previous instanceof Integer depth)) return;
                    try {
                        if (!active || depth != 0 || p.hasThrowable()
                                || !readBoolCached("launcher_icon_contour_enabled", false)) return;
                        View host = (View) p.thisObject;
                        Canvas canvas = (Canvas) p.args[0];
                        if (!canvas.isHardwareAccelerated()) return;
                        if (!adaptive.getBoolean(host)) {
                            Drawable icon = host.getBackground();
                            if (icon != null) {
                                int saved = canvas.save();
                                try {
                                    Path reveal = (Path) clipPath.get(host);
                                    if (reveal != null && !antiAlias.getBoolean(host)) canvas.clipPath(reveal);
                                    LauncherIconContourHooks.drawSurfaceIcon(host.getContext(), icon, canvas);
                                } finally { canvas.restoreToCount(saved); }
                            }
                        } else if (background.get(host) instanceof Drawable bg) {
                            Path path = (Path) clipPath.get(host);
                            if (path == null || path.isEmpty()) return;
                            path = new Path(path);
                            if (antiAlias.getBoolean(host)) {
                                // Match OplusClipIconView's 150-unit anti-aliased icon path.
                                Object launcher = XposedHelpers.getObjectField(host, "mLauncher");
                                Object profile = XposedHelpers.callMethod(launcher, "getDeviceProfile");
                                Object params = XposedHelpers.callMethod(profile, "icon");
                                // IconParam.d is the actual dex field (jadx displays a synthetic alias).
                                int size = XposedHelpers.getIntField(params, "d");
                                Class<?> shape = XposedHelpers.findClass("com.android.launcher3.graphics.IconShape", lp.classLoader);
                                float normalization = ((Number) XposedHelpers.callStaticMethod(shape, "getNormalizationScale")).floatValue();
                                Matrix transform = new Matrix();
                                transform.setScale(size/150f, size/150f);
                                float offset = (1f-normalization)*size/2f;
                                transform.postTranslate(offset, offset);
                                path.transform(transform);
                            }
                            Path area = new Path();
                            area.addRect(new RectF(bg.getBounds()), Path.Direction.CW);
                            if (!path.op(area, Path.Op.INTERSECT) || path.isEmpty()) return;
                            ClipFrame frame = CLIPS.get(host);
                            if (frame == null) { frame = new ClipFrame(host); CLIPS.put(host, frame); }
                            frame.draw(host, canvas, path);
                        }
                    } catch (Exception | LinkageError error) {
                        if (!reported) { reported = true; log("launcher clipped icon contour failed: " + error); }
                    } finally { if (depth == 0) DEPTH.remove(); else DEPTH.set(depth); }
                }
            });
            installed++;
        } catch (ReflectiveOperationException | LinkageError error) {
            log("launcher clipped icon contour unavailable: " + error);
        }
        if (installed > 0) {
            // These callers can inline the tiny drawable-view methods before the module is loaded.
            for (Method method : View.class.getDeclaredMethods())
                if (method.getName().equals("draw")) XposedBridge.deoptimize(method);
            for (Method method : View.DragShadowBuilder.class.getDeclaredMethods())
                if (method.getName().equals("onDrawShadow")) XposedBridge.deoptimize(method);
            Class<?> renderer = XposedHelpers.findClassIfExists(
                    "com.android.launcher3.anim.iconsurfacemanager.surface.IconSurfaceRenderer", lp.classLoader);
            if (renderer != null) for (Method method : renderer.getDeclaredMethods())
                XposedBridge.deoptimize(method);
        }
        log("launcher icon surface contour hooks=" + installed);
    }

    private static final class ClipFrame {
        final LauncherContourRenderer renderer;
        final ContourMaskCache cache = new ContourMaskCache();
        final RectF bounds = new RectF();
        final Paint coverage = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap mask, shadow;

        ClipFrame(View host) throws Exception { renderer = new LauncherContourRenderer(host); }

        void draw(View host, Canvas canvas, Path path) {
            path.computeBounds(bounds, true);
            int width = Math.max(1, (int)Math.ceil(bounds.width()));
            int height = Math.max(1, (int)Math.ceil(bounds.height()));
            Path local = new Path(path);
            local.offset(-bounds.left, -bounds.top);
            float density = host.getResources().getDisplayMetrics().density;
            if (!cache.matches(local, width, height, density)) {
                float scale = Math.min(1f, 512f/Math.max(width, height));
                int w = Math.max(1, Math.round(width*scale)), h = Math.max(1, Math.round(height*scale));
                Bitmap sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas raster = new Canvas(sample);
                raster.scale(scale, scale);
                coverage.setColor(Color.WHITE);
                raster.drawPath(local, coverage);
                int[] pixels = new int[w*h];
                sample.getPixels(pixels, 0, w, 0, 0, w, h);
                sample.recycle();
                mask = Bitmap.createBitmap(IconContourPixels.mask(pixels, w, h, 2f*density*scale),
                        w, h, Bitmap.Config.ARGB_8888);
                shadow = Bitmap.createBitmap(IconInnerShadow.pixels(pixels, w, h, 3f*density*scale),
                        w, h, Bitmap.Config.ARGB_8888);
                cache.remember(local, width, height, density);
            }
            canvas.drawBitmap(shadow, null, bounds, shade);
            renderer.drawIcon(host, canvas, bounds, 1f, mask);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) { /* The morph view inherits its source drawable. */ }
        }
        throw new NoSuchFieldException(type.getName()+'.'+name);
    }

    static void cleanup() {
        active = false;
        DEPTH.remove();
        CLIPS.clear();
    }

    private LauncherIconSurfaceContourHooks() {}
}
