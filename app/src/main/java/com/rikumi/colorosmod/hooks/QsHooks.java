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

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

/**
 * 控制中心(Quick Settings)相关的 SystemUI hook。
 */
public final class QsHooks {
    // 经典(合并)控制中心: 隐藏运营商名。OplusQuickStatusBarHeader#onFinishInflate 中
    // R.id.qs_carrier_text(位于 qs_clock_container 内) / R.id.carrier_group 显示运营商名, 直接 GONE。
    // 不再 hook 分离模式的 SeparateQSFakeStatusController, 以免与经典模式叠加。
    public static void hookQsHideCarrier(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 限定本类声明: onFinishInflate 在 View 里有实现, 上溯会命中所有 View。
            XposedHelpers.findAndHookDeclaredMethod(
                    "com.oplus.systemui.qs.OplusQuickStatusBarHeader",
                    lpparam.classLoader, "onFinishInflate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.view.View header = (android.view.View) param.thisObject;
                                android.content.res.Resources res = header.getResources();
                                int id = res.getIdentifier("qs_carrier_text", "id", "com.android.systemui");
                                int id2 = res.getIdentifier("carrier_group", "id", "com.android.systemui");
                                // 运行时动态门控: 关闭则还原为显示。
                                if (!readBool(KEY_QS_CARRIER_ENABLED, false)) {
                                    if (id != 0) {
                                        android.view.View c = header.findViewById(id);
                                        if (c != null) c.setVisibility(android.view.View.VISIBLE);
                                    }
                                    if (id2 != 0) {
                                        android.view.View g = header.findViewById(id2);
                                        if (g != null) g.setVisibility(android.view.View.VISIBLE);
                                    }
                                    return;
                                }
                                if (id != 0) {
                                    android.view.View carrier = header.findViewById(id);
                                    if (carrier != null) carrier.setVisibility(android.view.View.GONE);
                                }
                                if (id2 != 0) {
                                    android.view.View g = header.findViewById(id2);
                                    if (g != null) g.setVisibility(android.view.View.GONE);
                                }
                                log("qs_carrier(classic) applied");
                            } catch (Throwable t) {
                                log("qs_carrier(classic) apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK com.oplus.systemui.qs.OplusQuickStatusBarHeader#onFinishInflate");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQuickStatusBarHeader#onFinishInflate :: " + Log.getStackTraceString(t));
        }
    }

    // 控制中心顶栏间距(经典/合并模式): 右侧状态图标簇 quick_qs_status_icons 在
    // OplusQSFakeStatusController 展开进度回调中原地渐隐(避免先执行原生位移动画);
    // 页脚 OplusQSFooterImpl#mSettingsContainer 让日期/设置按钮小幅下沉。
    public static void hookQsTopMargin(final XC_LoadPackage.LoadPackageParam lpparam,
                                        final int footerPx) {
        // 真正执行顶部图标簇位移动画的是 OplusQSFakeStatusController 的展开进度监听器，
        // 不是 OplusQuickStatusBarHeader#setExpansion（该版本没有这个方法）。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.qs.fake.OplusQSFakeStatusController$qsPanelExpandFractionListener$1",
                    lpparam.classLoader, "onFractionChanged", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                float fraction = ((Float) param.args[0]).floatValue();
                                applyQsStatusClusterFade(param.thisObject, fraction);
                            } catch (Throwable t) {
                                log("qs_status fade apply fail: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                float fraction = ((Float) param.args[0]).floatValue();
                                applyQsStatusClusterFade(param.thisObject, fraction);
                            } catch (Throwable t) {
                                log("qs_status fade after apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQSFakeStatusController$qsPanelExpandFractionListener#onFractionChanged");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSFakeStatusController fraction listener :: " + Log.getStackTraceString(t));
        }
        // 经典控制中心: 设置按钮/页脚日期 (仅 collapsed 时 updateResources$15 重置 padding 为 0 后再叠加 footerPx)
        try {
            boolean ok = false;
            for (String m : new String[]{"updateResources$15", "updateResources"}) {
                if (ok) break;
                try {
                    XposedHelpers.findAndHookMethod(
                            "com.oplus.systemui.qs.OplusQSFooterImpl",
                            lpparam.classLoader, m,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    // 运行时动态门控: 关闭则不叠加页脚顶部间距。
                                    if (!readBool(KEY_QS_TOPMARGIN_ENABLED, false)) return;
                                    addFooterTopPadding(param.thisObject, footerPx);
                                }
                            });
                    ok = true;
                    log("HOOK OK com.oplus.systemui.qs.OplusQSFooterImpl#" + m);
                } catch (Throwable t) {
                    // 尝试下一个候选方法名
                }
            }
            if (!ok) log("HOOK FAIL OplusQSFooterImpl (no updateResources method matched)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSFooterImpl :: " + Log.getStackTraceString(t));
        }

        // 分离模式 - 以下三个 hook 针对控制中心(QS)页的 fake 状态图标簇
        // (fakeStatusIconContainer / statusIconsView)。通知中心页的图标簇不在 fake 容器里
        // (通知中心展开时系统把 separateQsFakeLayout 置 GONE), 而由 OplusQSSimpleHeader 顶部
        // 的 quick_qs_status_icons 承载, 见下方 OplusQSSimpleHeader#onInit hook。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.plugins.qs.seamless.SeparateQSFakeStatusController$qsPanelExpandFractionListener$1",
                    lpparam.classLoader, "onFractionChanged", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                float fraction = ((Float) param.args[0]).floatValue();
                                Object controller = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                applySeparateStatusClusterFade(controller, fraction);
                            } catch (Throwable t) {
                                log("qs_status(separate) fade apply fail: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                float fraction = ((Float) param.args[0]).floatValue();
                                Object controller = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                applySeparateStatusClusterFade(controller, fraction);
                            } catch (Throwable t) {
                                log("qs_status(separate) fade after apply fail: " + t);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.plugins.qs.seamless.SeparateQSFakeStatusController",
                    lpparam.classLoader, "resetState", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                float fraction = ((Float) param.args[0]).floatValue();
                                applySeparateStatusClusterFade(param.thisObject, fraction);
                            } catch (Throwable t) {
                                log("qs_status(separate) resetState apply fail: " + t);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.plugins.qs.seamless.SeparateQSFakeStatusController",
                    lpparam.classLoader, "access$showOrHideView",
                    "com.oplus.systemui.plugins.qs.seamless.SeparateQSFakeStatusController",
                    boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_QS_TOPMARGIN_ENABLED, false)) return;
                                hideSeparateStatusIconsView(param.args[0]);
                            } catch (Throwable t) {
                                log("qs_status(separate) showOrHide apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK SeparateQSFakeStatusController status cluster hide");
        } catch (Throwable t) {
            log("HOOK FAIL SeparateQSFakeStatusController status cluster :: " + Log.getStackTraceString(t));
        }

        // 分离模式通知中心头部真实状态图标簇: OplusQSSimpleHeader#getStatusIconsContainer()
        // (quick_qs_status_icons) 由 OplusSimpleQSFakeController 注入真实 StatusBarIconView。
        // 通知中心展开时系统把 separateQsFakeLayout 置 GONE, fake 容器已不可见; 真正显示在
        // 右上角的是这里 quick_qs_status_icons 里的真实图标。系统从不在展开时切换其可见性
        // (仅做 translationX 动画), 故 onInit 后 INVISIBLE 一次即持久生效。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.separate.OplusQSSimpleHeader",
                    lpparam.classLoader, "onInit",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object container = XposedHelpers.callMethod(
                                        param.thisObject, "getStatusIconsContainer");
                                if (container instanceof android.view.View) {
                                    ((android.view.View) container)
                                            .setVisibility(android.view.View.INVISIBLE);
                                }
                            } catch (Throwable t) {
                                log("qs_status(separate) simpleHeader hide fail: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSSimpleHeader#onInit :: " + Log.getStackTraceString(t));
        }
    }

    // 展开回调中原生会同时移动两个不同节点: mStatusIconsView(状态栏) 与 quickStatus(QS 顶栏)。
    // 因此在 fraction 第一次大于 0 时直接 INVISIBLE, 在原生 translation 执行前阻止移动动画可见。
    static void applyQsStatusClusterFade(Object fractionListener, float expansionFraction) {
        Object controller = XposedHelpers.getObjectField(fractionListener, "this$0");
        boolean enabled = readBool(KEY_QS_TOPMARGIN_ENABLED, false);
        boolean hide = enabled && expansionFraction > 0f;

        setQsStatusVisibility(controller, "quickStatus", hide);
        setQsStatusVisibility(controller, "fakeStatusIconContainer", hide);
        try {
            Object headerController = XposedHelpers.getObjectField(controller, "statusBarHeader");
            Object icons = XposedHelpers.callMethod(headerController, "getMStatusIconsView");
            if (icons instanceof android.view.View) {
                ((android.view.View) icons).setVisibility(
                        hide ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }

    static void setQsStatusVisibility(Object controller, String fieldName, boolean hide) {
        try {
            Object value = XposedHelpers.getObjectField(controller, fieldName);
            if (value instanceof android.view.View) {
                ((android.view.View) value).setVisibility(
                        hide ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }

    // 分离模式图标簇隐藏: 控制中心 fakeStatusIconContainer 与通知中心 fakeNotificationIconContainer
    // 跟经典模式同理在展开进度回调里原地渐隐; 展开态真实状态图标在 statusIconsView, 由
    // access$showOrHideView 的弹簧动画重新显示, 故单独取消动画后隐藏。
    static void applySeparateStatusClusterFade(Object controller, float expansionFraction) {
        boolean enabled = readBool(KEY_QS_TOPMARGIN_ENABLED, false);
        boolean hide = enabled && expansionFraction > 0f;
        setQsStatusVisibility(controller, "fakeStatusIconContainer", hide);
        setQsStatusVisibility(controller, "fakeNotificationIconContainer", hide);
        if (hide) {
            hideSeparateStatusIconsView(controller);
        }
    }

    static void hideSeparateStatusIconsView(Object controller) {
        try {
            Object v = XposedHelpers.getObjectField(controller, "statusIconsView");
            if (!(v instanceof android.view.View)) return;
            android.view.View view = (android.view.View) v;
            try {
                Object am = XposedHelpers.getObjectField(controller, "qsPanelAnimatorManager");
                if (am != null) {
                    Object ea = XposedHelpers.callMethod(am, "fetchElementAnimator", view);
                    if (ea != null) XposedHelpers.callMethod(ea, "cancelAll");
                }
            } catch (Throwable ignored) {
            }
            view.setAlpha(0.0f);
            view.setVisibility(android.view.View.INVISIBLE);
        } catch (Throwable ignored) {
        }
    }

    static void addFooterTopPadding(Object footer, int footerPx) {        try {
            Object container = XposedHelpers.getObjectField(footer, "mSettingsContainer");
            if (container instanceof android.view.View) {
                android.view.View v = (android.view.View) container;
                v.setPaddingRelative(v.getPaddingStart(), v.getPaddingTop() + footerPx,
                        v.getPaddingEnd(), v.getPaddingBottom());
            }
        } catch (Throwable t) {
            log("qs_footer top padding fail: " + t);
        }
    }

    // 合并控制中心时间日期取消展开动画(com.android.systemui)。
    // 页脚 OplusQSFooterImpl 承载时间(mClockView, R.id.qs_footer_clock)与日期(mQsDateView,
    // R.id.oplus_date)。createAndUpdateExpandAnimators 里建好的动画按 QS 展开进度 fraction 驱动:
    //   mPortAndFoldAnimator       -> 时间 scaleX/scaleY: getFontScale() ~ min(2+fontScale-1, 2.2),
    //                                 translationX: 0 ~ qs_footer_clock_expand_translation_x,
    //                                 translationY: 0 ~ (设置按钮半高 - mClockBaseline)(同一 builder 还带
    //                                 设置按钮/多用户/搜索按钮的位移, 不在本功能范围内);
    //   mDateX/YExpandTranslationAnimator -> 日期平移到放大后的时间下方。
    // fraction=0 即"一次下拉"的初始态(小字号、未位移), fraction=1 为完全展开态(放大并移动)。
    // 开启动能后, 每次 updateExpand(float) 之后把时间/日期按 fraction=0 的取值复位即可:
    // 时间 scaleX/scaleY = getFontScale()(动画起点, 随系统字体缩放变化故现取), 位移归零; 日期位移归零。
    public static void hookQsClockNoExpandAnim(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.qs.OplusQSFooterImpl",
                    lpparam.classLoader, "updateExpand", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_QS_CLOCK_NO_EXPAND_ANIM_ENABLED, false)) return;
                                resetFooterClockAndDate(param.thisObject);
                            } catch (Throwable t) {
                                log("qs_clock_no_expand_anim apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK com.oplus.systemui.qs.OplusQSFooterImpl#updateExpand");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSFooterImpl#updateExpand :: " + Log.getStackTraceString(t));
        }
    }

    // getFontScale() 是 OplusQSFooterImpl 的 private ()F, 反射结果缓存在静态字段,
    // 避免展开过程中逐帧做方法查找。取不到时返回 null(跳过缩放复位, 只复位位移)。
    private static volatile java.lang.reflect.Method sFooterFontScaleMethod;

    static Float footerFontScale(Object footer) {
        try {
            java.lang.reflect.Method m = sFooterFontScaleMethod;
            if (m == null) {
                m = footer.getClass().getDeclaredMethod("getFontScale");
                m.setAccessible(true);
                sFooterFontScaleMethod = m;
            }
            return (Float) m.invoke(footer);
        } catch (Throwable t) {
            return null;
        }
    }

    static void resetFooterClockAndDate(Object footer) {
        Object clock = XposedHelpers.getObjectField(footer, "mClockView");
        Object date = XposedHelpers.getObjectField(footer, "mQsDateView");
        Float scale = footerFontScale(footer);
        if (clock instanceof View) {
            View v = (View) clock;
            if (scale != null) {
                v.setScaleX(scale.floatValue());
                v.setScaleY(scale.floatValue());
            }
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        }
        if (date instanceof View) {
            View v = (View) date;
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        }
    }

    // 合并控制中心背景压暗, 承载背景的是"背后 scrim"。为什么"纯黑背景反而变灰": behind scrim 默认
    // top=LUMINOSITY+#99333333 把亮度归一化到 ~0.2, 且该混色在 AGSL shader 里合成、位于窗口内容之下。
    // 故 hook getPanelPlatformMixConfig 改 top 为 LUMINOSITY+近黑、bottom OVERLAY 置 0。
    public static void hookQsBackgroundDim(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            try {
                Class<?> exImpClass = XposedHelpers.findClass(
                        "com.oplus.systemui.statusbar.phone.ScrimControllerExImp", lpparam.classLoader);
                XposedHelpers.findAndHookMethod(exImpClass, "getPanelPlatformMixConfig",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!readBool(KEY_QS_SCRIM_TRANSLUCENT_ENABLED, false)) return;
                                    Object cfg = param.getResult();
                                    if (cfg == null) return;
                                    if (!cfg.getClass().getName().contains("BlurMixSingleWithShader")) return;
                                    Object bg = XposedHelpers.getObjectField(cfg, "backgroundShaderParam");
                                    if (bg == null) return;
                                    // top=LUMINOSITY(5) 把亮度归一化到该层 RGB 亮度; bottom=OVERLAY(2) 关闭。
                                    // alpha=0x99(与系统默认同强度)使结果亮度直接等于 top 层 RGB 亮度。
                                    // 亮度由滑条 qs_scrim_brightness 控制: 0=全黑, 20=系统默认(不压暗), 10≈50%。
                                    int brightness = readInt(KEY_QS_SCRIM_BRIGHTNESS, QS_SCRIM_BRIGHTNESS_DEFAULT);
                                    brightness = Math.max(0, Math.min(20, brightness));
                                    int gray = Math.round(brightness * QS_SCRIM_LUMIN_MAX / 20f);
                                    int darkTop = Color.argb(0x99, gray, gray, gray);
                                    Object dark = XposedHelpers.newInstance(bg.getClass(), 5, darkTop, 2, 0);
                                    XposedHelpers.setObjectField(cfg, "backgroundShaderParam", dark);
                                } catch (Throwable t) {
                                    log("qs_scrim mixconfig error: " + t);
                                }
                            }
                        });
                log("HOOK OK ScrimControllerExImp#getPanelPlatformMixConfig (qs_scrim_translucent)");
            } catch (Throwable t) {
                log("HOOK FAIL getPanelPlatformMixConfig :: " + Log.getStackTraceString(t));
            }

            log("HOOK OK ScrimView background-dim (qs_scrim_translucent)");
        } catch (Throwable t) {
            log("HOOK FAIL qs_scrim_translucent :: " + Log.getStackTraceString(t));
        }
    }

    // 控制中心 WLAN/蓝牙 名称单行省略, 分离版与合并版都生效。
    // 分离版(2x1 可伸缩磁贴 OplusQSResizeableTileViewTwoXOne): WLAN 的 SSID 写在主标题
    // labelTitle(R.id.tile_label), 由 handleTileStateChange 经 TextSwitcherExtKt.setContent 写入;
    // 蓝牙设备名写在副标题 labelDesc(R.id.tile_label_desc), 由 updateLabelDescText 写入。
    // 合并版(高亮磁贴 OplusQSHighlightTileView, 子类 OplusQSHighlightTileViewImpl): WLAN 的 SSID
    // 与蓝牙设备名都经 handleStateChanged 落到 mLabel(TextSwitcher, R.id.tile_label)。
    // 在这些方法之后对相应 TextView 强制单行+省略号。
    public static void hookQsTileNameEllipsis(final XC_LoadPackage.LoadPackageParam lpparam) {
        // WLAN/BT 等可伸缩磁贴(2x1)字段名一致; 本类没有 updateLabelText(标题在 handleTileStateChange 里直接写)。
        final String cls = "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne";
        // WLAN 的 SSID 写在主标题 labelTitle(handleTileStateChange 设置), 蓝牙设备名写在副标题 labelDesc。
        final String[] methods = { "handleTileStateChange", "updateLabelDescText" };
        final String[] labelFields = { "labelTitle", "labelDesc" };
        for (final String m : methods) {
            try {
                XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, m,
                        "com.android.systemui.plugins.qs.QSTile$State",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!readBool(KEY_QS_TILE_NAME_ELLIPSIS_ENABLED, false)) return;
                                    for (String f : labelFields) {
                                        Object v = XposedHelpers.getObjectField(param.thisObject, f);
                                        forceSingleLineEllipsis(v);
                                    }
                                } catch (Throwable ignored) {
                                    // 按规则: 不靠日志排错, 忽略
                                }
                            }
                        });
            } catch (Throwable t) {
                log("HOOK FAIL " + cls + "#" + m + " :: " + Log.getStackTraceString(t));
            }
        }

        // 合并版控制中心: 高亮磁贴(含 Wi-Fi/蓝牙)的标签统一由基类 handleStateChanged 写入 mLabel,
        // SSID 写 state.label, 蓝牙设备名(spec=bt 且 state==2)写 state.labelDesc, 都落在 mLabel 上。
        final String stdCls = "com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView";
        try {
            XposedHelpers.findAndHookMethod(stdCls, lpparam.classLoader, "handleStateChanged",
                    "com.android.systemui.plugins.qs.QSTile$State",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_QS_TILE_NAME_ELLIPSIS_ENABLED, false)) return;
                                Object state = param.args[0];
                                String spec = (String) XposedHelpers.getObjectField(state, "spec");
                                if (!"wifi".equals(spec) && !"bt".equals(spec)) return;
                                Object label = XposedHelpers.getObjectField(param.thisObject, "mLabel");
                                forceSingleLineEllipsis(label);
                            } catch (Throwable ignored) {
                                // 按规则: 不靠日志排错, 忽略
                            }
                        }
                    });
        } catch (Throwable t) {
            log("HOOK FAIL " + stdCls + "#handleStateChanged :: " + Log.getStackTraceString(t));
        }
    }

    // 控制中心 Wi-Fi / 蓝牙 / 音量 / 亮度 的圆角。
    // 系统判定: FlavorTwoFeatureOption.isFlavorTwoDeviceExp() = (一加品牌) && (海外 exp 区域)。
    // 命中(OxygenOS)时高亮磁贴(Wi-Fi/蓝牙)与滑条(音量/亮度)用 60dp 大圆角, 否则用 16dp。
    // 开关开启后统一强制到 QS_CORNER_RADIUS_DIMEN 那一档, 合并式与分离式都生效。
    public static void hookQsNormalCornerRadius(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 音量 / 亮度滑条: 合并式(com.oplus.systemui.qs.widget.OplusQsToggleSliderLayout)与分离式
        // (com.oplus.systemui.plugins.qs.widget.OplusQsToggleSliderLayout)共同继承
        // OplusQsBaseToggleSliderLayout, 半径最终都经它的 setCornerRadius(float) 落到 seekbar;
        // 该方法在基类里是 final, 两处调用都会走到, 一次 hook 覆盖两种模式。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.qs.base.seek.OplusQsBaseToggleSliderLayout",
                    lpparam.classLoader, "setCornerRadius", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_QS_NORMAL_CORNER_RADIUS_ENABLED, false)) return;
                                Float px = resolveQsCornerRadiusPx(param.thisObject);
                                if (px == null) return;
                                param.args[0] = px;
                            } catch (Throwable t) {
                                log("qs_radius slider fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQsBaseToggleSliderLayout#setCornerRadius");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQsBaseToggleSliderLayout#setCornerRadius :: " + Log.getStackTraceString(t));
        }

        // 高亮磁贴(Wi-Fi / 蓝牙)轮廓: 合并式 StdQSTileResInteractor 与分离式 SepQSTileResInteractor
        // 各自产出一个 RoundRectOutlineProvider, 分别写入 StdQSResPool / SepQSResPool,
        // 再被 OplusQSHighlightTileView#onOutlineUpdate / OplusQSResizeableTileView*
        // 当作背景 drawable 的 PathProvider, 决定磁贴可见圆角。
        hookQsHighlightTileOutline(lpparam, "com.oplus.systemui.qs.res.domain.interactor."
                + "StdQSTileResInteractor$startHighlightTileOutlineCollection$2");
        hookQsHighlightTileOutline(lpparam, "com.oplus.systemui.qs.res.domain.interactor."
                + "SepQSTileResInteractor$startHighlightTileOutlineCollection$2");
    }

    static void hookQsHighlightTileOutline(final XC_LoadPackage.LoadPackageParam lpparam,
                                           final String clsName) {
        try {
            XposedHelpers.findAndHookMethod(clsName, lpparam.classLoader,
                    "invokeSuspend", Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_QS_NORMAL_CORNER_RADIUS_ENABLED, false)) return;
                                Object result = param.getResult();
                                if (result == null) return;
                                if (!QS_OUTLINE_PROVIDER_CLASS.equals(result.getClass().getName())) return;
                                Object interactor = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                Context context = (Context) XposedHelpers.getObjectField(interactor, "context");
                                int dimenId = context.getResources()
                                        .getIdentifier(QS_CORNER_RADIUS_DIMEN, "dimen", "com.android.systemui");
                                if (dimenId == 0) return;
                                float radius = context.getResources().getDimension(dimenId);
                                // 与系统同口径构造, 保留"平滑圆角"权重, 避免 60dp 这类大半径被降级成方角。
                                param.setResult(XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass(QS_CONSTANT_CLASS, lpparam.classLoader),
                                        "getSmoothRoundRectOutlineProvider", context, radius));
                            } catch (Throwable t) {
                                log("qs_radius outline fail: " + t);
                            }
                        }
                    });
            log("HOOK OK " + clsName + "#invokeSuspend");
        } catch (Throwable t) {
            log("HOOK FAIL " + clsName + " :: " + Log.getStackTraceString(t));
        }
    }

    // 解析要强制的圆角(px); 资源不存在时返回 null, 保持系统原值。
    static Float resolveQsCornerRadiusPx(Object view) {
        if (!(view instanceof View)) return null;
        Resources res = ((View) view).getResources();
        int dimenId = res.getIdentifier(QS_CORNER_RADIUS_DIMEN, "dimen", "com.android.systemui");
        if (dimenId == 0) return null;
        return Float.valueOf(res.getDimension(dimenId));
    }

    // 对任意 View(TextSwitcher / ViewGroup / TextView) 递归对其下所有 TextView 强制单行省略。
    static void forceSingleLineEllipsis(Object view) {
        if (view instanceof TextView) {
            applyEllipsis((TextView) view);
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                forceSingleLineEllipsis(vg.getChildAt(i));
            }
            // TextSwitcher 复用两个子视图, 动画切换时 getNextView() 指向尚未显示的那个, 也覆盖到。
            if (view instanceof TextSwitcher) {
                android.view.View next = ((TextSwitcher) view).getNextView();
                if (next != null) forceSingleLineEllipsis(next);
            }
        }
    }

    static void applyEllipsis(TextView tv) {
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setHorizontallyScrolling(false); // 关闭横向滚动/跑马灯, 仅静态行尾省略
    }

    // 分离版控制中心左右切换取消切入效果。
    // 系统默认在通知中心/控制中心之间左右滑动切换时, 离场页会做 alpha 渐隐 + scale(0.85~1.0) 的"切变",
    // 入场页做位移, 形成"淡入缩放"过渡。开启后改为两页直接随手指平移:
    // 入场页保持原位移 initTranslationX + f; 离场页由"原地淡隐/缩放"改为位移 f(与入场页同量级), 并固定 alpha=1 scale=1。
    // 切变只发生在 OplusPanelViewPagerController 的合成访问器 access$setAlphaAndTranslationXForScrollX
    // (onScrollX 各阶段以 (controller, 离场view, 入场view, f, z) 调用), before 整体替换即可。
    public static void hookQsPanelSwitchNoCut(final XC_LoadPackage.LoadPackageParam lpparam) {
        final String cls = "com.oplus.systemui.separate.OplusPanelViewPagerController";
        try {
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader,
                    "access$setAlphaAndTranslationXForScrollX",
                    "com.oplus.systemui.separate.OplusPanelViewPagerController",
                    android.view.View.class, android.view.View.class, float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_QS_PANEL_SWITCH_NO_CUT_ENABLED, false)) return;
                                Object controller = param.args[0];
                                android.view.View leaving = (android.view.View) param.args[1];
                                android.view.View entering = (android.view.View) param.args[2];
                                float f = (Float) param.args[3];
                                float initTranslationX = XposedHelpers.getFloatField(controller, "initTranslationX");
                                // 入场页: 保持原位移(从 off-screen 滑入)。
                                entering.setTranslationX(initTranslationX + f);
                                // 离场页: 改为随手指位移(原 0 且只做 alpha/scale 切变), 去掉切变。
                                leaving.setTranslationX(f);
                                leaving.setAlpha(1.0f);
                                leaving.setScaleX(1.0f);
                                leaving.setScaleY(1.0f);
                                param.setResult(null);
                            } catch (Throwable t) {
                                log("qs_panel_switch_no_cut apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusPanelViewPagerController#access$setAlphaAndTranslationXForScrollX");
        } catch (Throwable t) {
            log("HOOK FAIL OplusPanelViewPagerController#access$setAlphaAndTranslationXForScrollX :: " + Log.getStackTraceString(t));
        }
    }
}
