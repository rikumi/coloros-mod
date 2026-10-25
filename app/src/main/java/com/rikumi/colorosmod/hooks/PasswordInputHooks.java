package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.TextSwitcher;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

public final class PasswordInputHooks {

    private static final String CLS_NUMERIC_KEYBOARD =
            "com.coui.appcompat.lockview.COUINumericKeyboard";
    private static final String CLS_NUMERIC_KEYBOARD_CELL =
            "com.coui.appcompat.lockview.COUINumericKeyboard$Cell";
    private static final String CLS_SIMPLE_LOCK =
            "com.coui.appcompat.lockview.COUISimpleLock";
    private static final String CLS_PWD_INPUT_LAYOUT =
            "com.coui.appcompat.input.COUILockScreenPwdInputLayout";
    private static final String CLS_PWD_INPUT_VIEW =
            "com.coui.appcompat.input.COUILockScreenPwdInputView";

    public static void hookKeyguardNoLightEffect(final XC_LoadPackage.LoadPackageParam lpparam) {
        hookNumericKeyboardNoLightEffect(lpparam);
        hookLegacyNumericKeyboardNoBlurRipple(lpparam);
        hookSimpleLockNoGlowEffect(lpparam);
        hookPwdInputNoLightEffect(lpparam);
    }

    private static void hookLegacyNumericKeyboardNoBlurRipple(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> keyboard = XposedHelpers.findClass(CLS_NUMERIC_KEYBOARD, lpparam.classLoader);
            try {
                XposedHelpers.findMethodExact(keyboard, "drawLightEffect", Canvas.class, int.class, int.class);
                return;
            } catch (Throwable oldImplementation) { }
            XposedHelpers.findAndHookDeclaredMethod(keyboard, "onDraw", Canvas.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED, false)) return;
                            try {
                                Object cells = XposedHelpers.getObjectField(param.thisObject, "sCells");
                                int rows = java.lang.reflect.Array.getLength(cells);
                                for (int row = 0; row < rows; row++) {
                                    Object columns = java.lang.reflect.Array.get(cells, row);
                                    for (int col = 0; col < java.lang.reflect.Array.getLength(columns); col++) {
                                        Object cell = java.lang.reflect.Array.get(columns, col);
                                        if (cell == null) continue;
                                        XposedHelpers.setFloatField(cell, "blurAlpha", -1f);
                                    }
                                }
                            } catch (Throwable error) {
                                log("legacy no_light_effect ripple update failed: " + error);
                            }
                        }
                    });
            log("HOOK OK COUINumericKeyboard#onDraw (legacy no_light_effect)");
        } catch (Throwable t) {
            log("HOOK FAIL legacy COUINumericKeyboard blur ripple :: " + Log.getStackTraceString(t));
        }
    }

    private static void hookNumericKeyboardNoLightEffect(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        // 0) 径向渐变光晕。
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_NUMERIC_KEYBOARD, lpparam.classLoader, "drawLightEffect",
                    Canvas.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED, false)) {
                                param.setResult(null);
                            }
                        }
                    });
            log("HOOK OK COUINumericKeyboard#drawLightEffect (no_light_effect)");
        } catch (Throwable t) {
            log("HOOK FAIL COUINumericKeyboard#drawLightEffect :: " + Log.getStackTraceString(t));
        }

        final Class<?> cellClass;
        try {
            cellClass = XposedHelpers.findClass(CLS_NUMERIC_KEYBOARD_CELL, lpparam.classLoader);
        } catch (Throwable t) {
            log("HOOK FAIL find COUINumericKeyboard$Cell :: " + Log.getStackTraceString(t));
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    CLS_NUMERIC_KEYBOARD, lpparam.classLoader, "drawInnerShadowLayer",
                    Canvas.class, float.class, float.class, cellClass,
                    int.class, int.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED, false)) return;
                            try {
                                drawKeySolidBackground(param);
                            } catch (Throwable t) {
                                log("no_light_effect key background error: " + t);
                            }
                            param.setResult(null);
                        }
                    });
            log("HOOK OK COUINumericKeyboard#drawInnerShadowLayer (no_light_effect)");
        } catch (Throwable t) {
            log("HOOK FAIL COUINumericKeyboard#drawInnerShadowLayer :: " + Log.getStackTraceString(t));
        }

        try {
            XposedHelpers.findAndHookMethod(
                    CLS_NUMERIC_KEYBOARD, lpparam.classLoader, "drawInnerBorder",
                    Canvas.class, float.class, float.class, cellClass,
                    int.class, int.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED, false)) {
                                param.setResult(null);
                            }
                        }
                    });
            log("HOOK OK COUINumericKeyboard#drawInnerBorder (no_light_effect)");
        } catch (Throwable t) {
            log("HOOK FAIL COUINumericKeyboard#drawInnerBorder :: " + Log.getStackTraceString(t));
        }
    }

    private static final String CLS_PANEL_BLUR_EX_KT =
            "com.oplusos.systemui.common.util.NotifiAndQsPlatformBlurExKt";
    // LUMINOSITY 混色模式(mode 5)。
    private static final int MIX_MODE_LUMINOSITY = 5;

    public static void hookBouncerBackgroundBrightness(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_PANEL_BLUR_EX_KT, lpparam.classLoader,
                    "panelBouncerMixConfig", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_KEYGUARD_BOUNCER_BRIGHTNESS_ENABLED, false)) return;
                                Object cfg = param.getResult();
                                if (cfg == null) return;
                                Object mixColor = XposedHelpers.getObjectField(cfg, "mixColor");
                                if (mixColor == null) return;
                                int mode = XposedHelpers.getIntField(mixColor, "mode");
                                if (mode != MIX_MODE_LUMINOSITY) return;
                                int top = XposedHelpers.getIntField(mixColor, "topLayerColor");
                                int bottom = XposedHelpers.getIntField(mixColor, "bottomLayerColor");

                                int brightness = readInt(KEY_KEYGUARD_BOUNCER_BRIGHTNESS,
                                        KEYGUARD_BOUNCER_BRIGHTNESS_DEFAULT);
                                brightness = Math.max(0,
                                        Math.min(KEYGUARD_BOUNCER_BRIGHTNESS_MAX, brightness));
                                float k = brightness / (float) KEYGUARD_BOUNCER_BRIGHTNESS_MAX;
                                int gray = Math.round(Color.red(top) * k);
                                int newTop = Color.argb(Color.alpha(top), gray, gray, gray);

                                // 整体替换: 不写任何 final 字段。
                                Object newMixColor = XposedHelpers.newInstance(
                                        mixColor.getClass(), mode, newTop, bottom);
                                Object newCfg = XposedHelpers.newInstance(cfg.getClass(), newMixColor);
                                // 还原系统在该方法内做的设置: blurMixSingle.setAlphaWithBlurAmount(!z)。
                                XposedHelpers.callMethod(newCfg, "setAlphaWithBlurAmount",
                                        !((Boolean) param.args[0]));
                                param.setResult(newCfg);
                            } catch (Throwable t) {
                                log("bouncer_brightness panelBouncerMixConfig error: " + t);
                            }
                        }
                    });
            log("HOOK OK NotifiAndQsPlatformBlurExKt#panelBouncerMixConfig (bouncer_brightness)");
        } catch (Throwable t) {
            log("HOOK FAIL bouncer_brightness :: " + Log.getStackTraceString(t));

            try {
                XposedHelpers.findAndHookMethod(
                        "com.oplus.systemui.statusbar.phone.ScrimControllerExImp",
                        lpparam.classLoader, "getPanelPlatformMixConfig",
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!readBool(KEY_KEYGUARD_BOUNCER_BRIGHTNESS_ENABLED, false)
                                            || !isLegacyBouncerMix(param.thisObject)) return;
                                    Object config = param.getResult();
                                    if (config == null) return;
                                    Object mixColor = XposedHelpers.callMethod(config, "getMixColor");
                                    if (mixColor == null || XposedHelpers.getIntField(mixColor, "mode")
                                            != MIX_MODE_LUMINOSITY) return;
                                    int top = XposedHelpers.getIntField(mixColor, "topLayerColor");
                                    int bottom = XposedHelpers.getIntField(mixColor, "bottomLayerColor");
                                    int brightness = readInt(KEY_KEYGUARD_BOUNCER_BRIGHTNESS,
                                            KEYGUARD_BOUNCER_BRIGHTNESS_DEFAULT);
                                    brightness = Math.max(0, Math.min(KEYGUARD_BOUNCER_BRIGHTNESS_MAX, brightness));
                                    int gray = Math.round(Color.red(top)
                                            * (brightness / (float) KEYGUARD_BOUNCER_BRIGHTNESS_MAX));
                                    Object replacementMix = XposedHelpers.newInstance(mixColor.getClass(),
                                            MIX_MODE_LUMINOSITY,
                                            Color.argb(Color.alpha(top), gray, gray, gray), bottom);
                                    Object replacement = XposedHelpers.newInstance(config.getClass(), replacementMix);
                                    XposedHelpers.callMethod(replacement, "setAlphaWithBlurAmount",
                                            XposedHelpers.callMethod(config, "getAlphaWithBlurAmount"));
                                    param.setResult(replacement);
                                } catch (Throwable error) {
                                    log("legacy bouncer_brightness mix update failed: " + error);
                                }
                            }
                        });
                log("HOOK OK ScrimControllerExImp#getPanelPlatformMixConfig (legacy bouncer_brightness)");
            } catch (Throwable legacyError) {
                log("HOOK FAIL legacy bouncer_brightness :: " + Log.getStackTraceString(legacyError));
            }
        }
    }

    private static boolean isLegacyBouncerMix(Object scrimExtension) {
        try {
            if (XposedHelpers.getBooleanField(scrimExtension, "isEnteringBouncer")) return true;
        } catch (Throwable ignored) { }
        try {
            Object controller = XposedHelpers.callMethod(scrimExtension, "getScrimController");
            Object state = XposedHelpers.callMethod(controller, "getState");
            Class<?> scrimState = XposedHelpers.findClass("com.android.systemui.statusbar.phone.ScrimState",
                    scrimExtension.getClass().getClassLoader());
            return state == XposedHelpers.getStaticObjectField(scrimState, "BOUNCER");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final float SLIDE_HIT_RADIUS_RATIO = 2.0f / 3.0f;

    private static Class<?> sKeyboardClass = null;

    public static void hookKeyguardSlideInput(final XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> kbCls;
        try {
            kbCls = XposedHelpers.findClass(CLS_NUMERIC_KEYBOARD, lpparam.classLoader);
        } catch (Throwable t) {
            log("HOOK FAIL slide_input findClass :: " + Log.getStackTraceString(t));
            return;
        }
        sKeyboardClass = kbCls; // 供 invokeExact 在父类上查找声明的方法。
        hookSlideAction(kbCls, "handleActionDown", 0);
        hookSlideAction(kbCls, "handleActionMove", 1);
        hookSlideAction(kbCls, "handleActionUp", 2);
        hookSlideDrawCell(kbCls);
        hookSlideTouchTracking(kbCls);
        hookSlideReset(kbCls);
    }

    private static void hookSlideAction(Class<?> kbCls, String method, int action) {
        try {
            XposedHelpers.findAndHookMethod(kbCls, method,
                    float.class, float.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_KEYGUARD_SLIDE_INPUT_ENABLED, false)) return;
                                Object kb = param.thisObject;
                                // 无障碍触摸探索模式下保持系统原生行为。
                                if (isTouchExplorationEnabled(kb)) return;
                                float x = (Float) param.args[0];
                                float y = (Float) param.args[1];
                                int pid = (Integer) param.args[2];
                                // 移出当前数字键时才开始缩小，包括移向侧键后交还原生处理的情况。
                                if (action == 1) {
                                    Object pressed = findPressedCell(kb, pid);
                                    if (pressed != null && isSlideNumberCell(pressed)
                                            && findSlideHitCell(kb, x, y) != pressed) {
                                        slideStartShrink(kb);
                                    }
                                }

                                if (isSideKeyCell(invokeKeyboard(kb, "checkForNewHit",
                                        new Class<?>[]{float.class, float.class}, x, y))
                                        || isSideKeyCell(findPressedCell(kb, pid))) {
                                    return;
                                }
                                param.setResult(null); // 数字键: 完全接管该动作。
                                if (action == 0) {
                                    slideDown(kb, x, y, pid);
                                } else if (action == 1) {
                                    slideMove(kb, x, y, pid);
                                } else {
                                    slideUp(kb, pid);
                                }
                            } catch (Throwable t) {
                                log("slide_input " + method + " error: " + t);
                            }
                        }
                    });
            log("HOOK OK COUINumericKeyboard#" + method + "(FFI) (slide_input)");
        } catch (Throwable t) {
            log("HOOK FAIL COUINumericKeyboard#" + method + " :: " + Log.getStackTraceString(t));
        }
    }

    private static final long SLIDE_SHRINK_DURATION_MS = 150L;
    private static final String EXTRA_SLIDE_CELL = "cm_ss_cell";
    private static final String EXTRA_SLIDE_SCALE = "cm_ss_scale";
    private static final String EXTRA_SLIDE_ALPHA = "cm_ss_alpha";

    private static final class SlideState {
        float progress;   // 0 = 原尺寸, 1 = 缩到命中区尺寸。
        long pointers;
        boolean shrinkTriggered;
        ValueAnimator animator;
    }

    private static void hookSlideDrawCell(Class<?> kbCls) {
        try {
            XposedHelpers.findAndHookMethod(kbCls, "drawCell",
                    Canvas.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            swapSlideShrink(param, true);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            swapSlideShrink(param, false);
                        }
                    });
            log("HOOK OK COUINumericKeyboard#drawCell (slide_input)");
        } catch (Throwable t) {
            log("HOOK FAIL COUINumericKeyboard#drawCell :: " + Log.getStackTraceString(t));
        }
    }

    private static void swapSlideShrink(XC_MethodHook.MethodHookParam param, boolean apply) {
        if (!apply) {
            restoreSlideShrink(param);
            return;
        }
        try {
            if (!readBool(KEY_KEYGUARD_SLIDE_INPUT_ENABLED, false)) return;
            Object kb = param.thisObject;
            float progress = slideProgress(kb);
            if (progress <= 0f) return;
            int col = (Integer) param.args[1];
            int row = (Integer) param.args[2];
            int idx = row * 3 + col;
            if (idx == 9 || idx == 11) return; // 侧键(删除/确定)保持原样。
            Object[][] sCells = (Object[][]) XposedHelpers.getObjectField(kb, "sCells");
            Object cell = sCells[row][col];
            if (cell == null) return;

            float scale = 1f + (SLIDE_HIT_RADIUS_RATIO - 1f) * progress; // 1 -> 命中区尺寸
            int alpha = XposedHelpers.getIntField(kb, "mKeyboardNumberTextAlpha");
            param.setObjectExtra(EXTRA_SLIDE_CELL, cell);
            param.setObjectExtra(EXTRA_SLIDE_SCALE,
                    Float.valueOf(XposedHelpers.getFloatField(cell, "mButtonScale")));
            param.setObjectExtra(EXTRA_SLIDE_ALPHA, Integer.valueOf(alpha));
            XposedHelpers.setFloatField(cell, "mButtonScale", scale);
            XposedHelpers.setIntField(kb, "mKeyboardNumberTextAlpha",
                    Math.round(alpha * (1f - progress))); // 数字随缩小一起淡出。
        } catch (Throwable t) {
            log("slide_input swapSlideShrink error: " + t);
        }
    }

    private static void restoreSlideShrink(XC_MethodHook.MethodHookParam param) {
        Object cell = param.getObjectExtra(EXTRA_SLIDE_CELL);
        if (cell == null) return;
        try {
            Object scale = param.getObjectExtra(EXTRA_SLIDE_SCALE);
            if (scale instanceof Float) {
                XposedHelpers.setFloatField(cell, "mButtonScale", ((Float) scale).floatValue());
            }
            Object alpha = param.getObjectExtra(EXTRA_SLIDE_ALPHA);
            if (alpha instanceof Integer) {
                XposedHelpers.setIntField(param.thisObject, "mKeyboardNumberTextAlpha",
                        ((Integer) alpha).intValue());
            }
        } catch (Throwable t) {
            log("slide_input restoreSlideShrink error: " + t);
        }
    }

    private static SlideState slideState(Object kb) {
        SlideState st = (SlideState) XposedHelpers.getAdditionalInstanceField(kb, "cm_slideState");
        if (st == null) {
            st = new SlideState();
            XposedHelpers.setAdditionalInstanceField(kb, "cm_slideState", st);
        }
        return st;
    }

    private static float slideProgress(Object kb) {
        SlideState st = (SlideState) XposedHelpers.getAdditionalInstanceField(kb, "cm_slideState");
        return st == null ? 0f : st.progress;
    }

    private static void slidePointerDown(Object kb, int pid) {
        SlideState st = slideState(kb);
        if (pid >= 0 && pid < 64) st.pointers |= (1L << pid);
    }

    private static void slideStartShrink(Object kb) {
        SlideState st = slideState(kb);
        if (st.shrinkTriggered) return;
        st.shrinkTriggered = true;
        animateSlideShrink(kb, st, 1f);
    }

    private static void slidePointerUp(Object kb, int pid) {
        SlideState st = slideState(kb);
        if (pid >= 0 && pid < 64) st.pointers &= ~(1L << pid);
        if (st.pointers == 0L) {
            st.shrinkTriggered = false;
            animateSlideShrink(kb, st, 0f);
        }
    }

    private static void slideResetPointers(Object kb) {
        SlideState st = slideState(kb);
        st.pointers = 0L;
        st.shrinkTriggered = false;
        if (st.animator != null) {
            st.animator.cancel();
            st.animator = null;
        }
        if (st.progress == 0f) return;
        st.progress = 0f;
        invalidate(kb);
    }

    private static void animateSlideShrink(final Object kb, final SlideState st, float target) {
        if (st.animator != null) {
            st.animator.cancel();
            st.animator = null;
        }
        if (Math.abs(st.progress - target) < 0.001f) return;
        ValueAnimator animator = ValueAnimator.ofFloat(st.progress, target);
        animator.setDuration(SLIDE_SHRINK_DURATION_MS);
        animator.setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                st.progress = ((Float) animation.getAnimatedValue()).floatValue();
                invalidate(kb);
            }
        });
        st.animator = animator;
        animator.start();
    }

    private static void hookSlideTouchTracking(Class<?> kbCls) {
        try {
            // 限定本类声明: onTouchEvent 在 View 里有实现, 上溯会命中所有 View。
            XposedHelpers.findAndHookDeclaredMethod(kbCls, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_KEYGUARD_SLIDE_INPUT_ENABLED, false)) return;
                                Object kb = param.thisObject;
                                // 键盘被禁用时原生会对所有手指走 handleActionCancel, 同样需要复位。
                                if (!(kb instanceof android.view.View)
                                        || !((android.view.View) kb).isEnabled()) {
                                    slideResetPointers(kb);
                                    return;
                                }
                                MotionEvent ev = (MotionEvent) param.args[0];
                                if (ev == null) return;
                                int action = ev.getActionMasked();
                                if (action == MotionEvent.ACTION_CANCEL) {
                                    slideResetPointers(kb);
                                } else if (action == MotionEvent.ACTION_UP
                                        || action == MotionEvent.ACTION_POINTER_UP) {
                                    int index = ev.getActionIndex();
                                    if (index >= 0 && index < ev.getPointerCount()) {
                                        slidePointerUp(kb, ev.getPointerId(index));
                                    }
                                }
                            } catch (Throwable t) {
                                log("slide_input onTouchEvent error: " + t);
                            }
                        }
                    });
            log("HOOK OK COUINumericKeyboard#onTouchEvent (slide_input)");
        } catch (Throwable t) {
            log("HOOK FAIL COUINumericKeyboard#onTouchEvent :: " + Log.getStackTraceString(t));
        }
    }

    private static void hookSlideReset(Class<?> kbCls) {
        try {
            XposedHelpers.findAndHookMethod(kbCls, "getEnterAnim", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        SlideState st = (SlideState) XposedHelpers.getAdditionalInstanceField(
                                param.thisObject, "cm_slideState");
                        if (st == null) return;
                        if (st.animator != null) {
                            st.animator.cancel();
                            st.animator = null;
                        }
                        st.pointers = 0L;
                        st.shrinkTriggered = false;
                        st.progress = 0f;
                    } catch (Throwable t) {
                        log("slide_input getEnterAnim error: " + t);
                    }
                }
            });
            log("HOOK OK COUINumericKeyboard#getEnterAnim (slide_input)");
        } catch (Throwable t) {
            log("HOOK FAIL COUINumericKeyboard#getEnterAnim :: " + Log.getStackTraceString(t));
        }
    }

    private static void slideDown(Object kb, float x, float y, int pid) {
        slidePointerDown(kb, pid);
        Object hit = findSlideHitCell(kb, x, y);
        if (hit == null) return;
        XposedHelpers.setIntField(hit, "pointerId", pid);
        showPressCell(kb, hit);
        slideVibrate(kb);
        inputCell(kb, hit);
        invalidate(kb);
    }

    private static void slideMove(Object kb, float x, float y, int pid) {
        Object cur = findPressedCell(kb, pid);
        Object hit = findSlideHitCell(kb, x, y);
        if (hit == cur) return; // 仍在同一键上, 无需处理。
        if (cur != null) cancelPressCell(kb, cur);
        if (hit != null) {
            XposedHelpers.setIntField(hit, "pointerId", pid);
            showPressCell(kb, hit);
            slideVibrate(kb);
            inputCell(kb, hit);
        }
        invalidate(kb);
    }

    private static void slideUp(Object kb, int pid) {
        slidePointerUp(kb, pid);
        Object cur = findPressedCell(kb, pid);
        if (cur == null) return;
        int idx = slideTouchIndex(cur);
        cancelPressCell(kb, cur);
        if (kb instanceof android.view.View) {
            android.view.View v = (android.view.View) kb;
            if (idx != -1 && v.isEnabled() && !v.hasOnClickListeners()) {
                invokeExact(kb, "setTouchSoundFeedBack", new Class<?>[0]);
            }
        }
        invalidate(kb);
    }

    private static Object findSlideHitCell(Object kb, float x, float y) {
        try {
            Object[][] sCells = (Object[][]) XposedHelpers.getObjectField(kb, "sCells");
            float cellW = XposedHelpers.getIntField(kb, "mCellWidth");
            float cellH = XposedHelpers.getIntField(kb, "mCellHeight");
            float hSp = XposedHelpers.getIntField(kb, "mHorizontalSpacing");
            float vSp = XposedHelpers.getIntField(kb, "mVerticalSpacing");
            float pl = ((android.view.View) kb).getPaddingLeft();
            float pt = ((android.view.View) kb).getPaddingTop();

            float rr = XposedHelpers.getIntField(kb, "mNumberBackgroundRadius") * SLIDE_HIT_RADIUS_RATIO;

            float best = rr * rr;
            Object bestCell = null;
            for (Object[] rowArr : sCells) {
                if (rowArr == null) continue;
                for (Object cell : rowArr) {
                    if (cell == null || !isSlideNumberCell(cell)) continue;
                    int col = XposedHelpers.getIntField(cell, "column");
                    int row = XposedHelpers.getIntField(cell, "row");
                    float cx = pl + cellW / 2f + col * (cellW + hSp);
                    float cy = pt + cellH / 2f + row * (cellH + vSp);
                    float dx = x - cx;
                    float dy = y - cy;
                    float d2 = dx * dx + dy * dy;
                    if (d2 <= best) {
                        best = d2;
                        bestCell = cell;
                    }
                }
            }
            return bestCell;
        } catch (Throwable t) {
            log("slide_input findSlideHitCell error: " + t);
            return null;
        }
    }

    private static boolean isSlideNumberCell(Object cell) {
        int idx = slideTouchIndex(cell);
        return (idx >= 0 && idx <= 8) || idx == 10;
    }

    private static boolean isSideKeyCell(Object cell) {
        if (cell == null) return false;
        int idx = slideTouchIndex(cell);
        return idx == 9 || idx == 11;
    }

    private static int slideTouchIndex(Object cell) {
        if (cell == null) return -1;
        try {
            return XposedHelpers.getIntField(cell, "row") * 3
                    + XposedHelpers.getIntField(cell, "column");
        } catch (Throwable t) {
            log("slide_input slideTouchIndex error: " + t);
            return -1;
        }
    }

    private static Object findPressedCell(Object kb, int pid) {
        if (pid == -1) return null;
        try {
            Object[][] sCells = (Object[][]) XposedHelpers.getObjectField(kb, "sCells");
            for (Object[] rowArr : sCells) {
                if (rowArr == null) continue;
                for (Object cell : rowArr) {
                    if (cell != null && XposedHelpers.getIntField(cell, "pointerId") == pid) {
                        return cell;
                    }
                }
            }
        } catch (Throwable t) {
            log("slide_input findPressedCell error: " + t);
        }
        return null;
    }

    private static Object invokeKeyboard(Object target, String name,
            Class<?>[] paramTypes, Object... args) {
        if (sKeyboardClass == null) return null;
        try {
            java.lang.reflect.Method m = sKeyboardClass.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            log("slide_input invoke " + name + " error: " + t);
            return null;
        }
    }

    private static boolean invokeExact(Object target, String name,
            Class<?>[] paramTypes, Object... args) {
        if (sKeyboardClass == null) return false;
        try {
            java.lang.reflect.Method m = sKeyboardClass.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            log("slide_input invoke " + name + " error: " + t);
            return false;
        }
    }

    private static void showPressCell(Object kb, Object cell) {
        try {
            int style = XposedHelpers.getIntField(kb, "mPressEffectStyle");
            if (style == 0) {
                invokeExact(kb, "initShowAnimator", new Class<?>[]{cell.getClass()}, cell);
            } else if (style == 1) {
                invokeExact(kb, "executeLightEffectAnimator",
                        new Class<?>[]{cell.getClass(), boolean.class}, cell, true);
            }
        } catch (Throwable t) {
            log("slide_input showPressCell error: " + t);
        }
    }

    private static void cancelPressCell(Object kb, Object cell) {
        if (cell == null) return;
        try {
            int style = XposedHelpers.getIntField(kb, "mPressEffectStyle");
            if (style == 0) {
                invokeExact(kb, "initFadeAnimator", new Class<?>[]{cell.getClass()}, cell);
            } else if (style == 1) {
                invokeExact(kb, "executeLightEffectAnimator",
                        new Class<?>[]{cell.getClass(), boolean.class}, cell, false);
            }
            XposedHelpers.setIntField(cell, "pointerId", -1);
        } catch (Throwable t) {
            log("slide_input cancelPressCell error: " + t);
        }
    }

    private static void inputCell(Object kb, Object cell) {
        try {
            int idx = slideTouchIndex(cell);
            if (idx < 0 || idx == 9 || idx == 11) return;
            invokeExact(kb, "callback", new Class<?>[]{int.class}, idx);
        } catch (Throwable t) {
            log("slide_input inputCell error: " + t);
        }
    }

    private static void slideVibrate(Object kb) {
        try {
            if (XposedHelpers.getBooleanField(kb, "mEnableHapticFeedback")) {
                invokeExact(kb, "setTouchFeedback", new Class<?>[0]);
            }
        } catch (Throwable t) {
            log("slide_input vibrate error: " + t);
        }
    }

    private static boolean isTouchExplorationEnabled(Object kb) {
        try {
            Object am = XposedHelpers.getObjectField(kb, "mAccessibilityManagerService");
            return (Boolean) XposedHelpers.callMethod(am, "isTouchExplorationEnabled");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void invalidate(Object kb) {
        if (kb instanceof android.view.View) {
            ((android.view.View) kb).invalidate();
        }
    }

    private static void hookSimpleLockNoGlowEffect(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_SIMPLE_LOCK, lpparam.classLoader, "drawGlowEffect",
                    Canvas.class, int.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED, false)) {
                                param.setResult(null);
                            }
                        }
                    });
            log("HOOK OK COUISimpleLock#drawGlowEffect (no_light_effect)");
        } catch (Throwable t) {
            log("HOOK FAIL COUISimpleLock#drawGlowEffect :: " + Log.getStackTraceString(t));
        }

        try {
            XposedHelpers.findAndHookMethod(
                    CLS_SIMPLE_LOCK, lpparam.classLoader, "drawFilledRectangleWithScale",
                    Canvas.class, int.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED, false)) return;
                            swapCircleScale(param, true);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            swapCircleScale(param, false);
                        }
                    });
            log("HOOK OK COUISimpleLock#drawFilledRectangleWithScale (no_light_effect)");
        } catch (Throwable t) {
            log("HOOK FAIL COUISimpleLock#drawFilledRectangleWithScale :: "
                    + Log.getStackTraceString(t));
        }
    }

    private static void swapCircleScale(XC_MethodHook.MethodHookParam param, boolean apply) {
        try {
            float[] scales = (float[]) XposedHelpers.getObjectField(param.thisObject, "mCircleScales");
            int index = (Integer) param.args[5];
            if (scales == null || index < 0 || index >= scales.length) return;
            if (apply) {
                param.setObjectExtra("cm_cs", Float.valueOf(scales[index]));
                scales[index] = 1.0f;
            } else {
                Object prev = param.getObjectExtra("cm_cs");
                if (prev instanceof Float) {
                    scales[index] = ((Float) prev).floatValue();
                }
            }
        } catch (Throwable t) {
            log("no_light_effect swapCircleScale error: " + t);
        }
    }

    // 输入框 / 确定按钮边框原色缓存(首次见到时读取一次, 用于关闭开关时还原)。
    private static int sBorderColorInput = Integer.MIN_VALUE;
    private static int sBorderColorLayout = Integer.MIN_VALUE;
    private static final java.util.Map<Object, Integer> sBorderOriginalColors =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Integer>());

    private static void setBorderMode(Object view, String colorField, boolean isLayout, boolean enabled) {
        try {
            int cached = isLayout ? sBorderColorLayout : sBorderColorInput;
            if (cached == Integer.MIN_VALUE) {
                cached = XposedHelpers.getIntField(view, colorField);
                if (isLayout) sBorderColorLayout = cached; else sBorderColorInput = cached;
            }
            if (!sBorderOriginalColors.containsKey(view)) {
                sBorderOriginalColors.put(view, cached);
            }
            XposedHelpers.setIntField(view, colorField, enabled ? 0 : cached);
            // 置空 paint, 下次绘制按最新 mBorderLineColor 重建; 既保证即时生效, 也避免持有已被替换的透明 paint。
            XposedHelpers.setObjectField(view, "mBorderPaint", null);
        } catch (Throwable t) {
            log("no_light_effect border error: " + t);
        }
    }

    private static void hookPwdInputNoLightEffect(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 限定本类声明: onDraw 在 View 里有实现, 上溯会命中所有 View。
            XposedHelpers.findAndHookDeclaredMethod(
                    CLS_PWD_INPUT_VIEW, lpparam.classLoader, "onDraw", Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean enabled = readBool(KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED, false);
                            if (scenesMode(param.thisObject) != 1) return;
                            // 边框: 开启去边框(透明), 关闭还原系统原色。门控即时生效。
                            setBorderMode(param.thisObject, "mBorderLineColor", false, enabled);
                            if (!enabled) return;
                            // 输入框背景色在 ScenesMode==1 时被 setBackgroundColor(0), 只剩内阴影可见。
                            drawPwdViewSolidBackground(param.thisObject, (Canvas) param.args[0]);
                            // drawBitmap 不接受 null, 换成全透明位图等价于不绘制。
                            swapInnerShadowBitmap(param, true);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            swapInnerShadowBitmap(param, false);
                        }
                    });
            log("HOOK OK COUILockScreenPwdInputView#onDraw (no_light_effect)");
        } catch (Throwable t) {
            log("HOOK FAIL COUILockScreenPwdInputView#onDraw :: " + Log.getStackTraceString(t));
        }

        try {
            // 限定本类声明: dispatchDraw 在 ViewGroup 里有实现, 上溯会命中所有容器。
            XposedHelpers.findAndHookDeclaredMethod(
                    CLS_PWD_INPUT_LAYOUT, lpparam.classLoader, "dispatchDraw", Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean enabled = readBool(KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED, false);
                            if (scenesMode(param.thisObject) != 1) return;
                            // 确认按钮边框: 开启去边框(透明), 关闭还原系统原色。门控即时生效。
                            setBorderMode(param.thisObject, "mBorderLineColor", true, enabled);
                            if (!enabled) return;
                            drawNextIconSolidBackground(param.thisObject, (Canvas) param.args[0]);
                            swapInnerShadowBitmap(param, true);
                            // mLightEffectAlpha > 0 才走光晕分支(内联展开的 LightEffectHelper)。
                            try {
                                param.setObjectExtra("cm_la",
                                        XposedHelpers.getFloatField(param.thisObject, "mLightEffectAlpha"));
                                XposedHelpers.setFloatField(param.thisObject, "mLightEffectAlpha", 0f);
                            } catch (Throwable t) {
                                log("no_light_effect mLightEffectAlpha error: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object prev = param.getObjectExtra("cm_la");
                            if (prev instanceof Float) {
                                try {
                                    XposedHelpers.setFloatField(param.thisObject, "mLightEffectAlpha",
                                            ((Float) prev).floatValue());
                                } catch (Throwable ignored) {
                                    // 字段不存在时无需恢复。
                                }
                            }
                            swapInnerShadowBitmap(param, false);
                        }
                    });
            log("HOOK OK COUILockScreenPwdInputLayout#dispatchDraw (no_light_effect)");
        } catch (Throwable t) {
            log("HOOK FAIL COUILockScreenPwdInputLayout#dispatchDraw :: " + Log.getStackTraceString(t));
        }
    }

    private static void swapInnerShadowBitmap(XC_MethodHook.MethodHookParam param, boolean apply) {
        try {
            if (apply) {
                param.setObjectExtra("cm_isb",
                        XposedHelpers.getObjectField(param.thisObject, "mInnerShadowBitmap"));
                XposedHelpers.setObjectField(param.thisObject, "mInnerShadowBitmap",
                        transparentBitmap());
            } else {
                Object prev = param.getObjectExtra("cm_isb");
                if (prev != null) {
                    XposedHelpers.setObjectField(param.thisObject, "mInnerShadowBitmap", prev);
                }
            }
        } catch (Throwable t) {
            log("no_light_effect swapInnerShadowBitmap error: " + t);
        }
    }

    private static final int SOLID_BG_NORMAL = 0x1AFFFFFF;   // 10% 白
    private static final int SOLID_BG_PRESSED = 0x33FFFFFF;

    private static final android.graphics.Paint sFillPaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);

    private static android.graphics.Paint fillPaint(int color) {
        android.graphics.Paint paint = sFillPaint;
        paint.setShader(null);
        try {
            paint.setBlendMode(null);
        } catch (Throwable ignored) {
            // 低版本无 BlendMode 时忽略。
        }
        paint.setStyle(android.graphics.Paint.Style.FILL);
        paint.setColor(color);
        return paint;
    }

    private static int scenesMode(Object view) {
        try {
            return XposedHelpers.getIntField(view, "mScenesMode");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int resolveSolidBgColor(Object view, boolean pressed) {
        if (view instanceof android.view.View) {
            android.graphics.drawable.Drawable bg = ((android.view.View) view).getBackground();
            if (bg instanceof android.graphics.drawable.ColorDrawable) {
                int color = ((android.graphics.drawable.ColorDrawable) bg).getColor();
                if (isOpaqueColor(color)) {
                    return color;
                }
            }
        }
        return pressed ? SOLID_BG_PRESSED : SOLID_BG_NORMAL;
    }

    private static boolean isOpaqueColor(int color) {
        return (color >>> 24) == 0xFF;
    }

    private static void drawKeySolidBackground(XC_MethodHook.MethodHookParam param) {
        Object keyboard = param.thisObject;
        Canvas canvas = (Canvas) param.args[0];
        float cx = (Float) param.args[1] + (Integer) param.args[4];
        float cy = (Float) param.args[2] + (Integer) param.args[5];
        Object cell = param.args[3];
        float alpha = clamp01((Float) param.args[6]);

        float scale;
        boolean pressed;
        try {
            scale = XposedHelpers.getFloatField(cell, "mButtonScale");
        } catch (Throwable t) {
            scale = 1f;
        }
        try {
            // pointerId 在 touch down 时置为触点 id, up/cancel 时复位 -1。
            pressed = XposedHelpers.getIntField(cell, "pointerId") != -1;
        } catch (Throwable t) {
            pressed = false;
        }

        float radius = keyRadius(keyboard, scale);
        int color;
        try {
            int sysColor = XposedHelpers.getIntField(keyboard, "mNumberBackgroundColor");
            color = isOpaqueColor(sysColor) ? sysColor : (pressed ? SOLID_BG_PRESSED : SOLID_BG_NORMAL);
        } catch (Throwable t) {
            color = pressed ? SOLID_BG_PRESSED : SOLID_BG_NORMAL;
        }

        android.graphics.Paint paint = fillPaint(color);
        paint.setAlpha((int) (Color.alpha(color) * alpha));
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private static float keyRadius(Object keyboard, float scale) {
        try {
            return XposedHelpers.getIntField(keyboard, "mNumberBackgroundRadius") * scale;
        } catch (Throwable t) {
            try {
                // 无障碍虚拟节点用的半径, 数值与按键半径一致。
                return XposedHelpers.getIntField(keyboard, "mCircleRadius") * scale;
            } catch (Throwable t2) {
                return 0f;
            }
        }
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    private static void drawPwdViewSolidBackground(Object view, Canvas canvas) {
        android.graphics.Path path = (android.graphics.Path)
                XposedHelpers.getObjectField(view, "mBackgroundPath");
        if (path == null) return;
        canvas.drawPath(path, fillPaint(resolveSolidBgColor(view, false)));
    }

    private static void drawNextIconSolidBackground(Object layout, Canvas canvas) {
        android.view.View nextIcon = (android.view.View)
                XposedHelpers.getObjectField(layout, "mNextIcon");
        if (nextIcon == null || nextIcon.getVisibility() != android.view.View.VISIBLE) return;
        float cx = (nextIcon.getRight() + nextIcon.getLeft()) / 2f;
        float cy = (nextIcon.getBottom() + nextIcon.getTop()) / 2f;
        float radius = (nextIcon.getScaleY() * nextIcon.getHeight()) / 2f;
        boolean pressed;
        try {
            pressed = XposedHelpers.getFloatField(layout, "mLightEffectAlpha") > 0f;
        } catch (Throwable t) {
            pressed = false;
        }
        canvas.drawCircle(cx, cy, radius, fillPaint(resolveSolidBgColor(nextIcon, pressed)));
    }

    private static volatile android.graphics.Bitmap sTransparentBitmap;

    private static android.graphics.Bitmap transparentBitmap() {
        if (sTransparentBitmap == null) {
            synchronized (PasswordInputHooks.class) {
                if (sTransparentBitmap == null) {
                    sTransparentBitmap = android.graphics.Bitmap.createBitmap(1, 1,
                            android.graphics.Bitmap.Config.ARGB_8888);
                }
            }
        }
        return sTransparentBitmap;
    }
    public static void cleanupForHotReload() {

        synchronized (sBorderOriginalColors) {
            for (java.util.Map.Entry<Object, Integer> entry :
                    new java.util.ArrayList<>(sBorderOriginalColors.entrySet())) {
                try {
                    XposedHelpers.setIntField(entry.getKey(), "mBorderLineColor", entry.getValue());
                    XposedHelpers.setObjectField(entry.getKey(), "mBorderPaint", null);
                } catch (Throwable t) {
                    log("no_light_effect border cleanup error: " + t);
                }
            }
            sBorderOriginalColors.clear();
        }
        sKeyboardClass = null;
        sTransparentBitmap = null;
        sBorderColorInput = Integer.MIN_VALUE;
        sBorderColorLayout = Integer.MIN_VALUE;
    }

}
