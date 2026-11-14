package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.lang.ref.WeakReference;

/** Replace only the decorative icon; the optical sensor's illumination window stays native. */
public final class FingerprintRingHooks {
    private static final String ICON =
            "com.oplus.systemui.biometrics.finger.udfps.OnScreenFingerprintIcon";
    private static final String MECH =
            "com.oplus.systemui.biometrics.finger.udfps.OnScreenFingerprintUiMech";
    private static final String SHADOW = "colorosmod.fingerprintShadow";
    private static final String RING = "colorosmod.fingerprintRing";
    private static WeakReference<Object> sMech = new WeakReference<>(null);
    private static volatile boolean sEnabled;
    private static FingerprintIconFields sIconFields;

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> iconClass = XposedHelpers.findClass(ICON, lp.classLoader);
            sIconFields = new FingerprintIconFields(XposedHelpers.findClass(MECH, lp.classLoader));
            XposedBridge.hookAllConstructors(iconClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    sMech = new WeakReference<>(p.args[1]);
                    // The two shadow ImageViews are created before the main icon.
                    ViewGroup parent = (ViewGroup) p.args[4];
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        XposedHelpers.setAdditionalInstanceField(parent.getChildAt(i), SHADOW, true);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(ImageView.class, "onDraw", Canvas.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            ImageView view = (ImageView) p.thisObject;
                            boolean icon = iconClass.isInstance(view);
                            if (!icon && XposedHelpers.getAdditionalInstanceField(view, SHADOW) == null) return;
                            if (!readBool(KEY_KEYGUARD_FINGERPRINT_RING_ENABLED, false)) return;
                            if (icon && view.getDrawable() != null) {
                                RingDrawable ring = (RingDrawable) XposedHelpers.getAdditionalInstanceField(view, RING);
                                if (ring == null) {
                                    ring = new RingDrawable();
                                    XposedHelpers.setAdditionalInstanceField(view, RING, ring);
                                }
                                ring.setBounds(view.getPaddingLeft(), view.getPaddingTop(),
                                        view.getWidth() - view.getPaddingRight(),
                                        view.getHeight() - view.getPaddingBottom());
                                ring.draw((Canvas) p.args[0]);
                            }
                            // Do not draw native dispersion, animated artwork, or either shadow.
                            // Keep null drawables blank so native hide/cleanup transitions survive.
                            p.setResult(null);
                        }
                    });
            XposedHelpers.findAndHookMethod(MECH, lp.classLoader, "loadAnimDrawables",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException {
                            sMech = new WeakReference<>(p.thisObject);
                            if (!readBool(KEY_KEYGUARD_FINGERPRINT_RING_ENABLED, false)) return;
                            // Missing overlays may leave either field null. Native hit testing also
                            // requires these fields, so a drawing-only replacement is insufficient.
                            sIconFields.fillMissing(p.thisObject, RingDrawable::new);
                            ImageView view = (ImageView) XposedHelpers.getObjectField(p.thisObject, "fpIcon");
                            if (view != null && view.getDrawable() == null) {
                                Object drawable = sIconFields.first(p.thisObject);
                                view.setImageDrawable(drawable instanceof Drawable art ? art : new RingDrawable());
                            }
                        }
                    });
            log("HOOK OK fingerprint ring drawing and overlay fallback");
        } catch (Throwable t) {
            log("HOOK FAIL fingerprint ring: " + t);
        }
    }

    public static void refresh() {
        boolean enabled = readBool(KEY_KEYGUARD_FINGERPRINT_RING_ENABLED, false);
        if (enabled == sEnabled) return;
        sEnabled = enabled;
        new Handler(Looper.getMainLooper()).post(() -> {
            Object mech = sMech.get();
            if (mech == null) return;
            try {
                // Discard only our missing-resource placeholders before restoring native art.
                sIconFields.clearMatching(mech, drawable -> drawable instanceof RingDrawable);
                ImageView icon = (ImageView) XposedHelpers.getObjectField(mech, "fpIcon");
                if (icon != null && icon.getDrawable() instanceof RingDrawable) icon.setImageDrawable(null);
                XposedHelpers.callMethod(mech, "loadAnimDrawables");
                for (String field : new String[]{"fpIcon", "fpIconShadow", "fpIconShadowOther"}) {
                    ImageView view = (ImageView) XposedHelpers.getObjectField(mech, field);
                    if (view != null) view.invalidate();
                }
            } catch (Throwable t) {
                log("fingerprint ring refresh: " + t);
            }
        });
    }

    private static final class RingDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RingDrawable() {
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(8f);
        }
        @Override public void draw(Canvas canvas) {
            // Native icon artwork uses a 1080px reference canvas; the view applies
            // KeyguardFingerprintUtils.zoomOut for the current display resolution.
            float diameter = Math.min(160f, Math.min(getBounds().width(), getBounds().height()) - 8f);
            if (diameter > 0) canvas.drawCircle(getBounds().exactCenterX(),
                    getBounds().exactCenterY(), diameter / 2f, paint);
        }
        @Override public int getIntrinsicWidth() { return 196; }
        @Override public int getIntrinsicHeight() { return 196; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
