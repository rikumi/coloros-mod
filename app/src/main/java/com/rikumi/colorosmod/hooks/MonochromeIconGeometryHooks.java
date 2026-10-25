package com.rikumi.colorosmod.hooks;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.log;

final class MonochromeIconGeometryHooks {
    private static final WeakHashMap<View, Boolean> widths = new WeakHashMap<>();

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.StatusBarIconView", lp.classLoader,
                    "onDraw", Canvas.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            ImageView icon = (ImageView) p.thisObject;
                            if (!widths.containsKey(icon) || !MonochromeNotificationHooks.enlargedIcon(icon)) return;
                            float scale = drawingScale(icon);
                            if (scale <= 0f) return;
                            Canvas canvas = (Canvas) p.args[0];
                            p.setObjectExtra("monoCanvasSave", canvas.save());
                            canvas.scale(scale, scale, icon.getWidth() / 2f, icon.getHeight() / 2f);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.getObjectExtra("monoCanvasSave") instanceof Integer save)
                                ((Canvas) p.args[0]).restoreToCount(save);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.NotificationIconContainer", lp.classLoader,
                    "onMeasure", int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            ViewGroup container = (ViewGroup) p.thisObject;
                            for (int i = 0; i < container.getChildCount(); i++) {
                                View icon = container.getChildAt(i);
                                if (!icon.getClass().getName().equals("com.android.systemui.statusbar.StatusBarIconView")) continue;
                                int baseline = nativeWidth(icon);
                                if (baseline <= 0 || icon.getLayoutParams() == null) continue;
                                boolean enlarged = MonochromeNotificationHooks.enlargedIcon(icon);
                                widths.put(icon, Boolean.TRUE);
                                int target = enlarged ? cellWidth(icon) : baseline;
                                setWidth(icon, target);
                                XposedHelpers.callMethod(icon, "maybeUpdateIconScaleDimens");
                            }
                        }
                    });
        } catch (Throwable error) { log("monochrome icon geometry hook failed: " + error); }
    }

    static float drawingSize(View view) {
        int id = NotificationResourceIds.get(view.getResources(), "status_bar_icon_drawing_size", "dimen", "com.android.systemui");
        float nativeSize = id == 0 ? 15f * density(view) : view.getResources().getDimensionPixelSize(id);
        return Math.max(1f, nativeSize - 1.5f * density(view));
    }

    static int cellWidth(View view) {

        int id = NotificationResourceIds.get(view.getResources(), "status_bar_icon_size_sp", "dimen", "android");
        int content = id == 0 ? nativeWidth(view) : view.getResources().getDimensionPixelSize(id);
        return Math.max((int) Math.ceil(drawingSize(view)), content - Math.round(3f * density(view)));
    }

    private static float density(View view) { return view.getResources().getDisplayMetrics().density; }

    private static float drawingScale(ImageView icon) {

        if (icon.getScaleType() != ImageView.ScaleType.CENTER_INSIDE) return 1f;
        Drawable drawable = icon.getDrawable();
        if (drawable == null) return 0f;
        int dw = drawable.getIntrinsicWidth(), dh = drawable.getIntrinsicHeight();
        int vertical = icon.getHeight() - icon.getPaddingTop() - icon.getPaddingBottom();
        int horizontal = icon.getPaddingLeft() + icon.getPaddingRight();
        if (dw <= 0 || dh <= 0 || icon.getWidth() <= horizontal || vertical <= 0) return 0f;
        float actualFit = Math.min(1f, Math.min((icon.getWidth() - horizontal) / (float) dw, vertical / (float) dh));
        float nativeScale = XposedHelpers.getFloatField(icon, "mIconScale");
        float drawing = drawingSize(icon);
        float rendered = Math.max(dw, dh) * actualFit * nativeScale;
        return drawing > 0 && rendered > 0f ? drawing / rendered : 0f;
    }

    static void refresh() {
        for (View icon : new java.util.ArrayList<>(widths.keySet())) {
            icon.invalidate();
            if (icon.getParent() instanceof View parent) parent.requestLayout();
        }
    }

    private static int nativeWidth(View icon) {

        android.content.res.Resources resources = icon.getResources();
        int size = NotificationResourceIds.get(resources, "status_bar_icon_size_sp", "dimen", "android");
        int margin = NotificationResourceIds.get(resources, "status_bar_icon_horizontal_margin", "dimen", "com.android.systemui");
        return size == 0 ? 0 : resources.getDimensionPixelSize(size)
                + 2 * (margin == 0 ? 0 : resources.getDimensionPixelSize(margin));
    }

    private static void setWidth(View icon, int width) {
        ViewGroup.LayoutParams current = icon.getLayoutParams();
        if (current == null || current.width == width) return;

        FrameLayout.LayoutParams own = current instanceof FrameLayout.LayoutParams frame
                ? new FrameLayout.LayoutParams(frame) : new FrameLayout.LayoutParams(current);
        own.width = width;
        icon.setLayoutParams(own);
    }

    static void cleanup() {
        for (View icon : widths.keySet()) {
            int baseline = nativeWidth(icon);
            if (baseline > 0) setWidth(icon, baseline);
            XposedHelpers.callMethod(icon, "maybeUpdateIconScaleDimens");
        }
        widths.clear();
    }
}
