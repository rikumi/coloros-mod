package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

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
    // ---------------------------------------------------------------------------------------------
    // 取消解锁界面控件光效
    //
    // COUI 给锁屏密码控件叠了三类"非纯色"绘制, 全部在 SystemUI 进程内完成, 与主题/壁纸无关:
    //   1. 径向渐变光晕: lockview.LightEffectHelper#drawLightEffect, RadialGradient + BlendMode.LIGHTEN;
    //      PIN/密码键盘按键在 COUINumericKeyboard#drawLightEffect 调用, SIM 确定按钮在
    //      COUILockScreenPwdInputLayout#dispatchDraw 内联展开(受 mLightEffectAlpha > 0 控制)。
    //   2. 内阴影: InnerShadowHelper 生成的 Bitmap, 输入控件与按键各有一张。
    //   3. 高光描边: COUINumericKeyboard#drawInnerBorder 中 cell.mInnerLightAlpha > 0 时
    //      用 mBorderLineHighLightAlpha + BlendMode.LUMINOSITY 再描一圈。
    //   另: 已输入圆点的光晕是 COUISimpleLock#drawGlowEffect 画的 mGlowEffectDrawable;
    //       圆点的缩放动画在 COUISimpleLock#drawFilledRectangleWithScale(唯一对圆点做
    //       canvas.scale 的地方), 缩放值取 mCircleScales[index](spring 输出, 上限 1.2f)。
    // 去掉这些后, 控件只剩纯色背景 + 纯色圆点; 按下时的缩放/变色反馈(
    // COUIPressFeedbackHelper / drawPressCircle)完全保留, 不是光效。
    //
    // 涉及控件(均已在设备上用 uiautomator 布局树核对):
    //   PIN/密码键盘按键 ... com.oplus.keyguard.security.widget.NumericKeyboardWidget extends COUINumericKeyboard
    //   已输入圆点 ......... com.oplus.keyguard.security.widget.PinSimpleLockInputWidget extends COUISimpleLock
    //   SIM 输入框 ......... com.coui.appcompat.input.COUILockScreenPwdInputView
    //   SIM 确定按钮 ....... com.coui.appcompat.input.COUILockScreenPwdInputLayout#dispatchDraw(mNextIcon)
    // 两个 Widget 子类均未覆写绘制方法, 私有方法 hook 父类即可生效。
    // ---------------------------------------------------------------------------------------------
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

        // 2) 按键边框: 整段跳过。drawInnerBorder 里先画 mInnerLightAlpha 触发的高光描边
        //    (LUMINOSITY), 再无条件画一道 mBorderLineColor 常规描边, 两者都不要。
        //    侧边键(删除/确定)走 drawSide 时也会调这里, 但它传的 alpha 是 0.0f, 常规描边
        //    alpha = mBorderLineAlpha * 0 本就不可见, 一并跳过不影响。
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

    // ---------------------------------------------------------------------------------------------
    // 自定义密码界面背景亮度
    //
    // 现象: 本机壁纸纯黑 (0,0,0), 锁屏渲染为纯黑, 而密码界面(bouncer)渲染为均匀的 (26,26,26)。
    //
    // 真正来源(设备属性 + dexdump 双重确认):
    //   ScrimControllerExImp#refreshBehindDrawable() 决定背后 scrim 用哪种 drawable:
    //     if (!isWallpaperBlurDisable() && ScrimUtil.isLowGaussianLevel(context)) -> WallpaperBlurDrawable
    //     else                                                                    -> AutoBlurDrawable
    //   设备属性 persist.sys.oplus.anim_level = 1, 而 isLowGaussianLevel() 要求 ANIM_LEVEL >= 3,
    //   故本机返回 false -> 走 **AutoBlurDrawable** 分支。
    //   => 之前 hook WallpaperBlurDrawable#draw 完全无效: 该类在本机根本没被实例化。
    //
    //   AutoBlurDrawable 分支下混色来自 getPanelPlatformMixConfig():
    //     ScrimControllerExImp line 1315:
    //       new BlurConfig(panelBlurRadius, 0, null, true, getPanelPlatformMixConfig(), ...)
    //     bouncer 时 getPanelPlatformMixConfig() 返回
    //       NotifiAndQsPlatformBlurExKt.panelBouncerMixConfig(z)
    //         = new BlurMixConfig.BlurMixSingle(BOUNCER_MIX_COLOR)
    //       BOUNCER_MIX_COLOR = new MixColor(5 /* LUMINOSITY */, #99262626, #66A6A6A6)
    //   LUMINOSITY(mode 5) 把亮度归一化到 top 层 RGB(0x26 = 38), 即给模糊壁纸加了一个"最低亮度",
    //   所以纯黑壁纸也被抬成 (26,26,26)。
    //
    // 实现(必须避开 final 字段写入):
    //   dexdump 确认 BlurMixSingle.mixColor 为 PRIVATE FINAL, MixColor 的 mode/topLayerColor/
    //   bottomLayerColor 均为 PUBLIC FINAL。对 final 字段反射写入会被 ART 内联而失效
    //   (上一版改 mixColor / topLayerColor 无效即因此)。
    //   故直接 hook panelBouncerMixConfig(boolean) 的 afterHook, 用新对象整体替换返回值。
    //   该方法只在 bouncer 时被调用, 天然只作用于密码界面, 无需额外维护 bouncer 状态。
    //
    //   新 top 层 RGB = 系统 RGB(0x26) * (brightness / 5): 5=系统默认, 0=纯黑(去掉最低亮度)。
    //   按比例缩放而非硬编码目标亮度, 对深浅色壁纸与不同 alpha 都成立。
    // ---------------------------------------------------------------------------------------------
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

    // ---------------------------------------------------------------------------------------------
    // 密码支持滑动输入
    //
    // 系统原生行为(COUINumericKeyboard, 签名均经 dexdump 核对):
    //   checkForNewHit(FF) -> getRowHit/getColumnHit: 按 **矩形**(cell 宽高 + mAdditionalPressableArea)命中。
    //   handleActionDown(FFI): cell.pointerId = pointerId, 显示按下态, **不输入**。
    //   handleActionUp(FFI):   仅当命中的 cell.pointerId == 该 pointerId 才 callback(输入), 再取消按下态。
    //   handleActionMove(FFI): 一旦移出原 cell 就 handleActionCancel(pointerId) 取消按下态。
    //   => 原生是"按下与抬起落在同一键才输入", 滑动经过其它键不会输入。
    //
    // 本功能改为"进入即输入", 接管上述三个私有方法(均为 (FFI)V):
    //   DOWN: 圆形命中某数字键 -> 显示按下态 + 立即输入。
    //   MOVE: 命中键变化       -> 取消旧键按下态, 新键显示按下态 + 立即输入。
    //         移出所有数字键   -> 取消按下态。
    //   UP:   仅取消按下态(字符在进入时已输入, 抬起不再重复输入)。
    //
    // 命中区域 = 以按键中心为圆心、"按键圆半径 * 2/3" 为半径的圆。按键圆半径取
    // mNumberBackgroundRadius * cell.mButtonScale(与 refreshNumberPaths / drawInnerShadowLayer
    // 绘制按键背景圆时所用的半径一致), 故 2/3 即需求所说的"中间 2/3 半径范围"。
    //
    // 只认数字键: callback(i) 中 0-8 -> 数字 1-9, 10 -> 数字 0, 9 -> 左键, 11 -> 右键;
    // 排除 9 / 11, 保留删除 / 确定等左右键的点击语义, 避免滑动误触。
    //
    // 按下态对两种 mPressEffectStyle 均兼容: 0 -> initShowAnimator/initFadeAnimator(传统圆圈),
    // 1 -> executeLightEffectAnimator(cell, boolean)(光效)。若同时开启"取消解锁界面控件光效",
    // 绘制已被替换为按 cell.pointerId != -1 判定的纯色底, 只要正确维护 pointerId 即显示按下态。
    // ---------------------------------------------------------------------------------------------
    // CLS_NUMERIC_KEYBOARD 复用上方已声明的同名常量。
    // 滑动输入的有效命中区域: 按键圆半径的 2/3。
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

    /** DOWN: 命中数字键则显示按下态并立即输入该字符。 */
    private static void slideDown(Object kb, float x, float y, int pid) {
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

    /**
     * 圆形命中测试: 直接遍历 sCells(字段类型 [[Lcom/.../COUINumericKeyboard$Cell;, dexdump 确认),
     * 找出中心与 (x, y) 距离不超过"按键圆半径 * 2/3"的数字键。
     *
     * 刻意完全不通过反射调用任何键盘实例方法:
     *  - getTouchIndex(Cell) 是 private, 且存在无参重载, 反射易失败;
     *  - checkForNewHit 虽为 public, 但定义在父类 COUINumericKeyboard, callMethod 在子类实例上
     *    按 (Float,Float) 自动装箱查找会抛 NoSuchMethodError(实测) —— 这是"点击和滑动双双失效"的根因。
     * 中心坐标改为按系统公式直接计算(与 getCenterXForColumn/getCenterYForRow 实现一致):
     *   cx = paddingLeft + mCellWidth/2 + col * (mCellWidth + mHorizontalSpacing)
     *   cy = paddingTop  + mCellHeight/2 + row * (mCellHeight + mVerticalSpacing)
     * 索引按 row*3+column(与系统 private getTouchIndex(Cell) 实现一致)。
     */
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

    /**
     * 左键(索引 9, 删除) / 右键(索引 11, 确定) 标记为侧键。
     * 这里用 row*3+column 的原始位置判断, 而非系统的 getTouchIndex(): 后者在侧键样式为空时会
     * 返回 -1, 但我们仍需要把该位置识别为侧键以便交还原生处理。
     */
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

    /**
     * 用精确签名调用键盘实例方法。
     *
     * 不可用 XposedHelpers.callMethod: 它会把基础类型参数自动装箱后再匹配, 而运行时实例是子类
     * NumericKeyboardWidget, 实测抛 NoSuchMethodError(如
     * "checkForNewHit[class java.lang.Float, class java.lang.Float]")。
     * callback(int)、executeLightEffectAnimator(Cell, boolean) 都带基础类型参数,
     * 必须显式按 int.class / boolean.class 查找。
     * Cell 类型直接取 cell.getClass(), 无需预先解析内部类。
     */
    /**
     * 在父类 COUINumericKeyboard 上按精确签名调用实例方法, 返回其返回值。
     *
     * 必须在**声明这些方法的类**上查找: XposedHelpers.findMethodExact 不搜索父类, 在子类实例
     * (NumericKeyboardWidget).getClass() 上查会抛 NoSuchMethodError(实测)。
     * getDeclaredMethod + setAccessible 可调用父类方法(含 private)。
     */
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

    /**
     * SIM 卡界面输入框(COUILockScreenPwdInputView)与确定按钮(COUILockScreenPwdInputLayout)的边框:
     * 边框由 mBorderPaint 以 mBorderLineColor 描边绘制。开启时把颜色置全透明并强制下次重绘重建
     * paint(读最新色) -> 不可见; 关闭时还原系统原色, 门控即时生效。
     * 注意 COUILockScreenPwdInputLayout 的 mBorderLineColor 为 final, 反射写入运行时仍生效
     * (值来自资源, 非编译期常量, 绘制时按字段值读取)。
     */
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

    // ---------------------------------------------------------------------------------------------
    // 纯色背景
    //
    // 新界面(mScenesMode==1 / mPressEffectStyle==1)下这些控件都没有背景色:
    //   - COUILockScreenPwdInputLayout 构造里: mNextIcon.setBackgroundColor(0) + mInputView.setBackgroundColor(0),
    //     因为内阴影 + 光晕会提供视觉; 传统代码路径(ScenesMode!=1)才给 coui_input_lock_screen_pwd_view_bg_color_desktop
    //     (#33ffffff) 或 R.attr.couiColorCard。
    //   - COUINumericKeyboard: 背景由 mDrawDelegate.getCustomKeyboardPaint() 提供, 但 SystemUI 里
    //     没有任何 setKeyboardDrawDelegate 调用者 -> delegate 为 null, 传统路径的 mNumberBackgroundColor
    //     也不会走(pressEffectStyle==1)。
    // 所以去掉光效后必须补一层纯色。取色顺序:
    //   1) 系统/传统代码为该控件配置的**完全不透明实色**背景(半透明的一律不用, 见下);
    //   2) 取不到 -> 常态 10% 白, 按下 20% 白。
    //
    // 为什么只认完全不透明: 键盘的 mNumberBackgroundColor 实际解析得到 #33ffffff(20% 白,
    // 来自 ?numericKeyboardStyle -> LauncherNumericKeyboardStyle -> kgd_color_numeric_keyboard
    // _setting_background_color), 如果"非透明就用它", 按下态会被这个恒定值吃掉而永远不变化 ——
    // 这正是上一版"按下不变色"的原因。该色同时在 pressEffectStyle==1 下根本不会被系统使用,
    // 所以这里按"系统没给有效背景"处理, 走兜底。
    // ---------------------------------------------------------------------------------------------
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

    /**
     * 密码按键的纯色背景。
     *
     * hook 点是 drawInnerShadowLayer(Canvas, float cx, float cy, Cell, int tx, int ty, float alpha),
     * 系统自己在这方法里就是按 (cx+tx, cy+ty) 定位、按 mNumberBackgroundRadius*mButtonScale 取半径、
     * 按 alpha*255 取透明度, 所以这里全部沿用入参, 不做任何二次推算:
     * 圆心/进退出位移/淡入淡出 alpha 均由系统给出, 动画天然同步; 9/11 号侧边键走 drawSide,
     * 那里自带 drawBackground(mSideBackgroundColor), 不会进这个方法, 无需处理。
     */
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

    /**
     * SIM 确定按钮: 圆形背景, 圆心与半径的计算与系统 dispatchDraw(147-155 行)完全一致。
     *
     * 按下判定不能用 nextIcon.isPressed(): mNextIcon 是个 ImageView, 系统从不对它 setPressed,
     * 触摸由 layout 自己处理, 所以 isPressed() 恒为 false。
     * 改用 mLightEffectAlpha —— 它由按下动画拉起、抬起后回落为 0, 是本控件唯一的按下态信号
     * (调用方随后才把它清零以去掉光晕, 所以这里读到的是原始值)。
     */
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
