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

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 密码输入界面相关的 SystemUI hook: 控件光效、背景亮度、滑动输入、纯色背景绘制。
 */
public final class PasswordInputHooks {
    // 取消解锁界面控件光效。COUI 给锁屏密码控件叠了三类"非纯色"绘制(与主题/壁纸无关): 1) 径向渐变光晕
    // LightEffectHelper#drawLightEffect(LIGHTEN); 2) InnerShadowHelper 生成的内阴影 Bitmap; 3) 高光描边
    // drawInnerBorder(LUMINOSITY)。另: 已输入圆点的光晕是 drawGlowEffect, 缩放动画取 mCircleScales[index](上限 1.2f)。
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
        hookSimpleLockNoGlowEffect(lpparam);
        hookPwdInputNoLightEffect(lpparam);
    }

    /** PIN/密码键盘(含 SIM 卡界面键盘): 去光晕、去内阴影、去高光描边。 */
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

        // 1) 内阴影 Bitmap: 这层是按键唯一的可见背景(delegate 为 null 时),
        //    跳过前先用本方法的入参画纯色圆 —— 圆心/进退出位移/淡入 alpha 全部由系统传入, 天然跟随动画。
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

    // 2) 按键边框: 整段跳过。drawInnerBorder 里先画 mInnerLightAlpha 触发的高光描边(LUMINOSITY), 再无条件
    //    画一道 mBorderLineColor 常规描边, 两者都不要。侧边键走 drawSide 时也会调这里, 但它传的 alpha 是
    //    0.0f, 常规描边 alpha = mBorderLineAlpha * 0 本就不可见, 一并跳过不影响。
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

    // 自定义密码界面背景亮度。壁纸纯黑时锁屏为纯黑, 而 bouncer 为均匀的 (26,26,26)。来源: 设备属性
    // persist.sys.oplus.anim_level=1 而 isLowGaussianLevel() 要求 >=3, 故本机走 **AutoBlurDrawable** 分支
    // (之前 hook WallpaperBlurDrawable#draw 无效 —— 该类在本机根本没被实例化)。
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
        }
    }

    // 密码支持滑动输入。系统原生是"抬起与按下同键才输入", 本功能改为"进入即输入": DOWN 圆形命中即按下并输入,
    // MOVE 换键时取消旧键、新键输入, 移出则取消, UP 仅取消按下态。命中区域 = 以按键中心为圆心、"按键圆半径*2/3"
    // 为半径的圆; 只认数字键(0-8 -> 1-9、10 -> 0), 排除 9/11 侧键以保留删除/确定的点击语义。
    private static final float SLIDE_HIT_RADIUS_RATIO = 2.0f / 3.0f;
    // 运行时键盘实例是子类 NumericKeyboardWidget, 但被反射调用的方法都声明在父类
    // COUINumericKeyboard, 故缓存父类 Class 用于 getDeclaredMethod(见 invokeExact)。
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

    /** action: 0=DOWN 1=MOVE 2=UP。三个方法签名同为 (FFI)V。 */
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
                                // 删除(9) / 确定(11) 等侧键保持系统原生点击语义:
                                // 只要手指正落在侧键上、或当前按下的是侧键, 就完全交还原生处理
                                // (不 setResult), 否则原生输入路径会被跳过导致点击失效。
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

    // 滑动输入时的按键缩小: 滑动期间把所有数字键(不含 9/11 侧键)的绘制半径缩到与圆形命中区一致
    // (mNumberBackgroundRadius * SLIDE_HIT_RADIUS_RATIO), 数字同时淡出; 松手/取消后动画还原。
    // 实现上只在 drawCell(Canvas, int column, int row) 绘制单格的前后临时替换两个字段:
    // cell.mButtonScale —— style 1 下 drawInnerShadowLayer / drawInnerBorder 取圆半径、drawCell
    // 取字号的唯一来源; mKeyboardNumberTextAlpha —— 只作用于数字文本透明度(int, 见 initPaint)。
    // 替换只发生在一次绘制内, 按下动画/spring 写入的真实值不受影响, 松手后自然是系统自己的值。
    private static final long SLIDE_SHRINK_DURATION_MS = 150L;
    private static final String EXTRA_SLIDE_CELL = "cm_ss_cell";
    private static final String EXTRA_SLIDE_SCALE = "cm_ss_scale";
    private static final String EXTRA_SLIDE_ALPHA = "cm_ss_alpha";

    /** 每个键盘实例一份: 缩小进度、正在滑动的 pointerId 位掩码、当前动画。 */
    private static final class SlideState {
        float progress;   // 0 = 原尺寸, 1 = 缩到命中区尺寸。
        long pointers;
        ValueAnimator animator;
    }

    /** 绘制期缩放: 只改绘制读取的字段, 不参与命中判定(命中半径与 mButtonScale 无关)。 */
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

    /** 临时替换(apply=true) / 还原(apply=false) 单格的缩放与数字透明度。 */
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

    /** 绘制期只读, 不创建状态。 */
    private static float slideProgress(Object kb) {
        SlideState st = (SlideState) XposedHelpers.getAdditionalInstanceField(kb, "cm_slideState");
        return st == null ? 0f : st.progress;
    }

    private static void slidePointerDown(Object kb, int pid) {
        SlideState st = slideState(kb);
        if (pid >= 0 && pid < 64) st.pointers |= (1L << pid);
        animateSlideShrink(kb, st, 1f);
    }

    private static void slidePointerUp(Object kb, int pid) {
        SlideState st = slideState(kb);
        if (pid >= 0 && pid < 64) st.pointers &= ~(1L << pid);
        if (st.pointers == 0L) animateSlideShrink(kb, st, 0f);
    }

    /** 所有手指都失效: 立即取消动画并复位(无需动画, 状态本身已丢弃)。 */
    private static void slideResetPointers(Object kb) {
        SlideState st = slideState(kb);
        st.pointers = 0L;
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

    // 恢复时机必须看真实 MotionEvent, 不能看 handleActionCancel(int): 手指滑到侧键(9/11)上时我们
    // 会交还原生 handleActionMove, 而它一旦判定"落点不再是原格"就调 handleActionCancel —— 斜着从 0
    // 滑到 7 途经侧键/间隙时会误触发, 导致中途弹回原尺寸。因此只在 UP / POINTER_UP(且无其它手指)
    // / CANCEL 时恢复, 中途移出按键区域(甚至移出键盘)都保持缩小, 直到手指抬起。
    private static void hookSlideTouchTracking(Class<?> kbCls) {
        try {
            XposedHelpers.findAndHookMethod(kbCls, "onTouchEvent", MotionEvent.class,
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

    /** 键盘重新做入场动画(bouncer 再次出现)时复位, 避免上一次滑动残留的缩小状态。 */
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

    /** DOWN: 命中数字键则显示按下态并立即输入该字符。 */
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

    /** MOVE: 命中键变化时取消旧键、按下并输入新键; 移出所有数字键则取消按下态。 */
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

    /** UP: 仅取消按下态(字符在进入时已输入, 抬起不再重复输入)。 */
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

    // 圆形命中测试: 遍历 sCells 找中心与 (x,y) 距离不超过"按键圆半径*2/3"的数字键。刻意不反射调用键盘实例方法:
    // getTouchIndex 是 private 且有无参重载; checkForNewHit 定义在父类, 装箱查找会抛 NoSuchMethodError。
    // 中心按系统公式算(paddingLeft + mCellWidth/2 + col*(mCellWidth+spacing)), 索引按 row*3+column。
    private static Object findSlideHitCell(Object kb, float x, float y) {
        try {
            Object[][] sCells = (Object[][]) XposedHelpers.getObjectField(kb, "sCells");
            float cellW = XposedHelpers.getIntField(kb, "mCellWidth");
            float cellH = XposedHelpers.getIntField(kb, "mCellHeight");
            float hSp = XposedHelpers.getIntField(kb, "mHorizontalSpacing");
            float vSp = XposedHelpers.getIntField(kb, "mVerticalSpacing");
            float pl = ((android.view.View) kb).getPaddingLeft();
            float pt = ((android.view.View) kb).getPaddingTop();
            // 命中半径 = 按键圆半径的 2/3。用 mNumberBackgroundRadius 而不乘 mButtonScale:
            // mButtonScale 随按下动画缩放, 用它会导致按下后命中区缩小、误触发取消。
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

    /** 仅数字键参与滑动输入(0-8 -> 1-9, 10 -> 0); 左键 9 / 右键 11 保持点击语义。 */
    private static boolean isSlideNumberCell(Object cell) {
        int idx = slideTouchIndex(cell);
        return (idx >= 0 && idx <= 8) || idx == 10;
    }

    // 左键(索引 9, 删除) / 右键(索引 11, 确定) 标记为侧键。这里用 row*3+column 的原始位置判断, 而非系统的
    // getTouchIndex(): 后者在侧键样式为空时返回 -1, 但我们仍需把该位置识别为侧键以便交还原生处理。
    private static boolean isSideKeyCell(Object cell) {
        if (cell == null) return false;
        int idx = slideTouchIndex(cell);
        return idx == 9 || idx == 11;
    }

    /** 索引 = row*3+column, 与系统 private getTouchIndex(Cell) 实现一致, 无需反射。 */
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

    /** 扫描 sCells 找 pointerId 匹配的格(避免反射调用 private findCellByPointerId)。 */
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

    // 用精确签名调用键盘实例方法。不可用 XposedHelpers.callMethod: 它会把基础类型参数装箱后再匹配, 而运行时实例是
    // 子类 NumericKeyboardWidget, 实测抛 NoSuchMethodError; callback(int)、executeLightEffectAnimator(Cell, boolean)
    // 必须显式按 int.class/boolean.class 查。调父类方法须在声明类上 getDeclaredMethod(findMethodExact 不搜索父类)。
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

    /** 显示按下态: 兼容 mPressEffectStyle 0(传统圆圈) 与 1(光效)。 */
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

    /** 取消按下态: 播放收起动画并清除 pointerId。 */
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

    /** 立即输入该键字符(走系统 callback, 索引到字符的映射与原生完全一致)。 */
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

    /** 已输入圆点: 去掉叠在圆点外的光晕 Drawable。 */
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

        // 圆点缩放动画: drawFilledRectangleWithScale 是唯一对圆点做 canvas.scale 的地方,
        // 缩放值取 mCircleScales[index](spring 动画输出, 0 -> 1 带回弹, 上限 1.2f)。
        // 绘制前临时置 1.0f 即等价于恒不缩放, 其余(透明度淡入、位移、save/restore)完全交给系统。
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

    /** 把圆点缩放值临时固定为 1.0f(不缩放)再换回, 从而去掉缩放动画但保留系统其余绘制逻辑。 */
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

    // SIM 卡界面输入框(COUILockScreenPwdInputView)与确定按钮(COUILockScreenPwdInputLayout)的边框由 mBorderPaint
    // 以 mBorderLineColor 描边。开启时把颜色置全透明并强制重建 paint -> 不可见; 关闭时还原, 门控即时生效。
    // 注意 COUILockScreenPwdInputLayout 的 mBorderLineColor 为 final, 但反射写入运行时仍生效(值来自资源非编译期常量)。
    private static void setBorderMode(Object view, String colorField, boolean isLayout, boolean enabled) {
        try {
            int cached = isLayout ? sBorderColorLayout : sBorderColorInput;
            if (cached == Integer.MIN_VALUE) {
                cached = XposedHelpers.getIntField(view, colorField);
                if (isLayout) sBorderColorLayout = cached; else sBorderColorInput = cached;
            }
            XposedHelpers.setIntField(view, colorField, enabled ? 0 : cached);
            // 置空 paint, 下次绘制按最新 mBorderLineColor 重建; 既保证即时生效, 也避免持有已被替换的透明 paint。
            XposedHelpers.setObjectField(view, "mBorderPaint", null);
        } catch (Throwable t) {
            log("no_light_effect border error: " + t);
        }
    }

    /** SIM 卡界面: 输入框与确定按钮的内阴影 + 确定按钮的光晕, 均先补纯色背景再去掉。 */
    private static void hookPwdInputNoLightEffect(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
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
            XposedHelpers.findAndHookMethod(
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

    /** 把内阴影位图临时换成 1x1 全透明位图(apply=true)或换回原图(apply=false)。 */
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

    // 纯色背景。新界面(mPressEffectStyle==1)下这些控件没有背景色, 故去掉光效后必须补一层:
    // 取系统配置的**完全不透明实色**, 取不到则常态 10% 白、按下 20% 白。只认不透明是因为键盘的
    // mNumberBackgroundColor 实际为 #33ffffff(20% 白), 用它按下态就恒不变(上一版"按下不变色"的原因)。
    private static final int SOLID_BG_NORMAL = 0x1AFFFFFF;   // 10% 白
    private static final int SOLID_BG_PRESSED = 0x33FFFFFF;  // 20% 白

    // 绘制只在主线程, 复用同一个 Paint 即可。
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

    /** ScenesMode: 只有 1(桌面/新界面)时这些控件才走光效绘制分支。 */
    private static int scenesMode(Object view) {
        try {
            return XposedHelpers.getIntField(view, "mScenesMode");
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 控件自身被设置的纯色背景(传统代码路径设置的值), 没有则返回兜底色。 */
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

    /** 只有完全不透明的实色才算"系统给了有效背景"。 */
    private static boolean isOpaqueColor(int color) {
        return (color >>> 24) == 0xFF;
    }

    // 密码按键的纯色背景。hook 点 drawInnerShadowLayer(Canvas, float cx, float cy, Cell, int tx, int ty, float alpha):
    // 系统自己在该方法里按 (cx+tx, cy+ty) 定位、按 mNumberBackgroundRadius*mButtonScale 取半径、按 alpha*255
    // 取透明度, 故全部沿用入参不做二次推算, 动画天然同步; 9/11 侧边键走 drawSide, 不进此方法。
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

    /** 与 drawInnerShadowLayer / drawInnerBorder 同口径: mNumberBackgroundRadius * mButtonScale。 */
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

    /** SIM 输入框: 圆角矩形背景(mBackgroundPath 在 onSizeChanged 中构建)。 */
    private static void drawPwdViewSolidBackground(Object view, Canvas canvas) {
        android.graphics.Path path = (android.graphics.Path)
                XposedHelpers.getObjectField(view, "mBackgroundPath");
        if (path == null) return;
        canvas.drawPath(path, fillPaint(resolveSolidBgColor(view, false)));
    }

    // SIM 确定按钮: 圆形背景, 圆心与半径的计算与系统 dispatchDraw(147-155 行)完全一致。
    // 按下判定不能用 nextIcon.isPressed(): mNextIcon 是 ImageView 且系统从不对它 setPressed, 恒 false。
    // 改用 mLightEffectAlpha —— 它由按下动画拉起、抬起回落为 0, 是本控件唯一的按下态信号。
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
}
