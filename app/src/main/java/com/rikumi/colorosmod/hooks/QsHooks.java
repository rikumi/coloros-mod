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
 * 控制中心(Quick Settings)相关的 SystemUI hook。
 */
public final class QsHooks {
    /**
     * 经典(合并)控制中心: 隐藏运营商名。OplusQuickStatusBarHeader#onFinishInflate 中
     * R.id.qs_carrier_text (位于 qs_clock_container 内) / R.id.carrier_group 显示运营商名, 直接 GONE。
     * (用户使用经典模式, 不再 hook 分离模式的 SeparateQSFakeStatusController, 以免与经典模式叠加。)
     */
    public static void hookQsHideCarrier(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
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

    // 控制中心顶栏间距(经典/合并模式)。
    //  - 右侧状态图标簇 quick_qs_status_icons: 在 OplusQSFakeStatusController 的展开进度回调中
    //    原地渐隐, 避免先执行系统原生位移动画再消失。
    //  - 页脚 OplusQSFooterImpl#mSettingsContainer: 让日期/设置按钮小幅下沉。
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
    }

    /**
     * 展开回调中原生会同时移动两个不同的节点：
     * mStatusIconsView 是状态栏节点，quickStatus 是 QS 顶栏节点；二者不是同一个 View。
     * 因此在 fraction 第一次大于 0 时直接 INVISIBLE，在原生 translation 执行前彻底阻止移动动画可见。
     */
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

    // 合并(经典)控制中心背景压暗。承载背景的是"背后 scrim"。为什么"纯黑背景反而变灰"(根因不在本模块):
    // behind scrim 的平台混色默认 top=LUMINOSITY+#99333333、bottom=OVERLAY+#80999999 —— LUMINOSITY 把
    // 亮度归一化到 ~0.2(黑底提亮成灰), 且这层混色在 SurfaceFlinger 的 AGSL shader 里合成、位于窗口
    // 内容之下, 任何半透明黑叠加都去不掉这层灰。故 hook getPanelPlatformMixConfig 把 top 改为
    // LUMINOSITY+近黑、bottom OVERLAY 置 0, 仅替换 backgroundShaderParam(不动 PANEL_*_MIX_COLOR)。
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
                                    // top=LUMINOSITY(5) 把亮度归一化到该层 RGB 的亮度; bottom=OVERLAY(2) 关闭。
                                    // alpha=0x99(与系统默认同强度)使结果亮度直接等于 top 层 RGB 亮度。
                                    // 亮度由滑条 qs_scrim_brightness(0-20, 默认 10)控制:
                                    //   0=全黑(RGB 0), 20=系统默认 lumin(0x33=51, 即不压暗), 10≈50% lumin。
                                    // LUMINOSITY 仍保留底层模糊色相/纹理。
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

    // 控制中心 WLAN/蓝牙 名称单行省略。WLAN 的 SSID 写在主标题 labelTitle(R.id.tile_label),
    // 由 handleTileStateChange 经 TextSwitcherExtKt.setContent 写入; 蓝牙设备名写在副标题
    // labelDesc(R.id.tile_label_desc), 由 updateLabelDescText 写入。在这两个方法之后对两个
    // TextSwitcher 下的所有 TextView 强制单行 + 行尾省略号。幂等。
    // (本类没有 updateLabelText, 标题是在 handleTileStateChange 内直接设置的。)
    public static void hookQsTileNameEllipsis(final XC_LoadPackage.LoadPackageParam lpparam) {
        // WLAN/BT 等可伸缩磁贴(2x1)实现 updateLabelText / updateLabelDescText, 字段名一致。
        final String cls = "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne";
        // WLAN 的 SSID 写在主标题 labelTitle, 由 handleTileStateChange(QSTile.State) 直接设置;
        // 蓝牙设备名/副标题写在 labelDesc, 由 updateLabelDescText(QSTile.State) 设置。
        // 注意: 本类没有 updateLabelText 方法(标题在 handleTileStateChange 里直接写), 故 hook 这两个入口。
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
}
