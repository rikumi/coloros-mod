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

public final class QsHooks {
    private static final java.util.WeakHashMap<Object, Boolean> nameHosts = new java.util.WeakHashMap<>();
    private static final android.os.Handler radiusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.Map<Object, Object[]> radiusSliders = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<Class<?>, Object[]> radiusOutlines = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.WeakHashMap<View, View.OnLayoutChangeListener> radiusSizeListeners = new java.util.WeakHashMap<>();
    private static final ThreadLocal<Boolean> replayingRadius = new ThreadLocal<>();
    private static Integer appliedRadius;
    private static boolean radiusApplied;
    private static final Runnable radiusRefresh = () -> refreshCornerRadiusOnMain();

    public static void hookQsHideCarrier(final XC_LoadPackage.LoadPackageParam lpparam) {
        StatusBarExtrasHooks.hookCarrier(lpparam);
    }

    public static void hookQsTopMargin(final XC_LoadPackage.LoadPackageParam lpparam,
                                        final int footerPx) {

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
            // SystemUI 15 uses the header itself as the expansion listener host.
            try {
                XposedHelpers.findAndHookMethod(
                        "com.oplus.systemui.qs.OplusQuickStatusBarHeader",
                        lpparam.classLoader, "setExpansion", float.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                applyLegacyQsStatusVisibility(param.thisObject, (Float) param.args[0]);
                            }
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                applyLegacyQsStatusVisibility(param.thisObject, (Float) param.args[0]);
                            }
                        });
                log("HOOK OK OplusQuickStatusBarHeader#setExpansion (legacy qs status cluster)");
            } catch (Throwable legacyError) {
                log("HOOK FAIL legacy QS status cluster :: " + Log.getStackTraceString(legacyError));
            }
        }
        // 经典控制中心: 设置按钮/页脚日期 (仅 collapsed 时 updateResources$15 重置 padding 为 0 后再叠加 footerPx)
        try {
            boolean ok = false;
            for (String m : new String[]{"updateResources$15", "updateResources$16", "updateResources"}) {
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

    private static void applyLegacyQsStatusVisibility(Object header, float fraction) {
        try {
            Object statusIcons = XposedHelpers.getObjectField(header, "mStatusIconsView");
            if (statusIcons instanceof View view) {
                boolean hide = readBool(KEY_QS_TOPMARGIN_ENABLED, false) && fraction > 0f;
                view.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
            }
        } catch (Throwable t) {
            log("legacy QS status cluster apply failed: " + t);
        }
    }

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

    static void resetFooterClockAndDate(Object footer) {
        Object clock = XposedHelpers.getObjectField(footer, "mClockView");
        Object date = XposedHelpers.getObjectField(footer, "mQsDateView");
        if (clock instanceof View) {
            View v = (View) clock;
            float scale = ((Number) XposedHelpers.callMethod(footer, "getFontScale")).floatValue();
            v.setScaleX(scale);
            v.setScaleY(scale);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        }
        if (date instanceof View) {
            View v = (View) date;
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        }
    }

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
                                    // 两类 ROM 的单层混色配置类名都含 BlurMixSingle。
                                    if (!cfg.getClass().getName().contains("BlurMixSingle")) return;
                                    // 字段名随版本不同: 新版 backgroundShaderParam, 旧版 mixColor。
                                    Object bg = null;
                                    String field = null;
                                    try {
                                        bg = XposedHelpers.getObjectField(cfg, "backgroundShaderParam");
                                        field = "backgroundShaderParam";
                                    } catch (Throwable ignored) {
                                    }
                                    if (bg == null) {
                                        try {
                                            bg = XposedHelpers.getObjectField(cfg, "mixColor");
                                            field = "mixColor";
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    if (bg == null) return;

                                    int brightness = readInt(KEY_QS_SCRIM_BRIGHTNESS, QS_SCRIM_BRIGHTNESS_DEFAULT);
                                    brightness = Math.max(0, Math.min(20, brightness));
                                    int gray = Math.round(brightness * QS_SCRIM_LUMIN_MAX / 20f);
                                    int darkTop = Color.argb(0x99, gray, gray, gray);
                                    Object dark = newQsMixColor(bg.getClass(), darkTop);
                                    if (dark == null) return;
                                    XposedHelpers.setObjectField(cfg, field, dark);
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

    public static void hookQsBackgroundBlurRadius(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> exImpClass = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.phone.ScrimControllerExImp", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(exImpClass, "refreshBehindDrawable",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_QS_BLUR_RADIUS_ENABLED, false)) return;

                                int value = readInt(KEY_QS_BLUR_RADIUS, QS_BLUR_RADIUS_DEFAULT);
                                value = Math.max(0, Math.min(QS_BLUR_RADIUS_MAX, value));
                                int radius = value * QS_BLUR_RADIUS_SCALE;
                                Object controller = XposedHelpers.callMethod(param.thisObject, "getScrimController");
                                if (controller == null) return;
                                Object scrimBehind = XposedHelpers.callMethod(controller, "getScrimBehind");
                                if (scrimBehind == null) return;
                                Object drawable = XposedHelpers.callMethod(scrimBehind, "getDrawable");
                                if (drawable == null) return;
                                // 壁纸模糊分支(WallpaperBlurDrawable)不承载平台模糊半径, 跳过。
                                if (!drawable.getClass().getName().endsWith("AutoBlurDrawable")) return;
                                Object proxy = XposedHelpers.callMethod(drawable, "getViewBlurProxy");
                                if (proxy == null) return;
                                Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
                                if (config == null) return;
                                XposedHelpers.setIntField(config, "blurRadius", radius);
                                XposedHelpers.callMethod(proxy, "applyBlurConfig");
                            } catch (Throwable t) {
                                log("qs_blur_radius apply error: " + t);
                            }
                        }
                    });
            log("HOOK OK ScrimControllerExImp#refreshBehindDrawable (qs_blur_radius)");
        } catch (Throwable t) {
            log("HOOK FAIL refreshBehindDrawable :: " + Log.getStackTraceString(t));
        }
    }

    public static void hookQsBackgroundScale(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> proxyClass = XposedHelpers.findClass(
                    "com.oplusos.systemui.common.blurability.ViewBlurProxy", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.oplusos.systemui.common.util.NotifiAndQsPlatformBlurExKt", lpparam.classLoader,
                    "applyPanelMirrorScale", boolean.class, float.class, proxyClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_QS_BLUR_SCALE_ENABLED, false)) return;
                                Object proxy = param.args[2];
                                if (proxy == null) return;
                                Object config = XposedHelpers.callMethod(proxy, "getBlurConfig");
                                if (config == null) return;
                                Object mixConfig = XposedHelpers.callMethod(config, "getPlatformMixConfig");
                                if (mixConfig == null) return;
                                // 取系统刚写入的值, 免得重复实现 lerp, 也自动跟随不同版本的 0.9 常量。
                                float systemScale = (Float) XposedHelpers.callMethod(mixConfig, "getMirrorScale");
                                int percent = readInt(KEY_QS_BLUR_SCALE, QS_BLUR_SCALE_DEFAULT);
                                percent = Math.max(0, Math.min(QS_BLUR_SCALE_MAX, percent));
                                float ratio = percent / 100f;
                                float newScale = 1f - ratio * (1f - systemScale);
                                XposedHelpers.callMethod(mixConfig, "setMirrorScale", newScale);
                            } catch (Throwable t) {
                                log("qs_blur_scale apply error: " + t);
                            }
                        }
                    });
            log("HOOK OK NotifiAndQsPlatformBlurExKt#applyPanelMirrorScale (qs_blur_scale)");
        } catch (Throwable t) {
            log("HOOK FAIL applyPanelMirrorScale :: " + Log.getStackTraceString(t));
        }
    }

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
                                    nameHosts.put(param.thisObject, true);
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
                                nameHosts.put(param.thisObject, true);
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
        // 合并版下方普通磁贴走另一条渲染链路，测量时还会重新决定名称的行数。
        final String normalCls = "com.oplus.systemui.qs.tileimpl.OplusQSTileViewImpl";
        java.util.function.Consumer<Object> applyNormalLabel = tile -> {
            if (!readBool(KEY_QS_TILE_NAME_ELLIPSIS_ENABLED, false)) return;
            Object state = XposedHelpers.getObjectField(tile, "mTempState");
            if (state == null) return;
            String spec = (String) XposedHelpers.getObjectField(state, "spec");
            if (!"wifi".equals(spec) && !"bt".equals(spec)) return;
            nameHosts.put(tile, true);
            forceSingleLineEllipsis(XposedHelpers.getObjectField(tile, "mLabel"));
            forceSingleLineEllipsis(XposedHelpers.getObjectField(tile, "mSecondLine"));
        };
        try {
            XposedHelpers.findAndHookMethod(normalCls, lpparam.classLoader, "handleStateChanged",
                    "com.android.systemui.plugins.qs.QSTile$State", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            applyNormalLabel.accept(param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(normalCls, lpparam.classLoader, "updateLabelTextLine", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { applyNormalLabel.accept(param.thisObject); }
            });
        } catch (Throwable t) {
            log("HOOK FAIL " + normalCls + " tile name ellipsis :: " + Log.getStackTraceString(t));
        }
        // 热重载不会重建原生磁贴或重新发送状态；每轮测量从磁贴保留的系统状态恢复。
        for (String tileClass : new String[]{normalCls, cls}) {
            try {
                XposedHelpers.findAndHookDeclaredMethod(tileClass, lpparam.classLoader, "onMeasure", int.class, int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { applyExistingNameTile(p.thisObject); }
                    @Override protected void afterHookedMethod(MethodHookParam p) { applyExistingNameTile(p.thisObject); }
                });
            } catch (Throwable t) { log("tile ellipsis measure hook failed: " + tileClass + " " + t); }
        }

    }

    public static Object captureHotReloadNames() {
        java.util.List<Object[]> sliders = new java.util.ArrayList<>();
        synchronized (radiusSliders) {
            for (java.util.Map.Entry<Object, Object[]> entry : radiusSliders.entrySet())
                sliders.add(new Object[]{entry.getKey(), entry.getValue()});
        }
        java.util.List<Object[]> outlines = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Class<?>, Object[]> entry : radiusOutlines.entrySet())
            outlines.add(new Object[]{entry.getKey(), entry.getValue()});
        return new Object[]{new java.util.ArrayList<>(nameHosts.keySet()), sliders, outlines};
    }
    public static void restoreHotReloadNames(Object state) {
        if (state instanceof Object[] saved && saved.length == 3) {
            if (saved[1] instanceof Iterable<?> rows) for (Object row : rows) if (row instanceof Object[] pair)
                { radiusSliders.put(pair[0], (Object[]) pair[1]);
                  if (pair[0] instanceof View view) watchRadiusSize(view); }
            if (saved[2] instanceof Iterable<?> rows) for (Object row : rows) if (row instanceof Object[] pair)
                radiusOutlines.put((Class<?>) pair[0], (Object[]) pair[1]);
            state = saved[0];
            onCornerRadiusSettingsChanged();
        }
        if (state instanceof Iterable<?> tiles) for (Object tile : tiles) if (tile != null) {
            nameHosts.put(tile, true);
            applyExistingNameTile(tile);
            if (tile instanceof View view) view.requestLayout();
        }
    }

    public static void restoreNamesInRoots(Object state) {
        if (state instanceof Iterable<?> roots) for (Object root : roots) if (root instanceof View view) restoreNamesInView(view);
    }
    private static void restoreNamesInView(View view) {
        String name = view.getClass().getName();
        if (name.contains("OplusQSTileViewImpl") || name.contains("OplusQSHighlightTileView") || name.contains("OplusQSResizeableTileViewTwoXOne"))
            applyExistingNameTile(view);
        if (view instanceof android.view.ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) restoreNamesInView(group.getChildAt(i));
    }

    private static void applyExistingNameTile(Object tile) {
        if (!readBool(KEY_QS_TILE_NAME_ELLIPSIS_ENABLED, false)) return;
        try {
            if (tile.getClass().getName().contains("Resizeable")) {
                Object state = XposedHelpers.callMethod(tile, "getTileState");
                if (!wifiOrBluetooth(state)) return;
                nameHosts.put(tile, true);
                forceSingleLineEllipsis(XposedHelpers.getObjectField(tile, "labelTitle"));
                forceSingleLineEllipsis(XposedHelpers.getObjectField(tile, "labelDesc"));
            } else {
                if (!wifiOrBluetooth(XposedHelpers.getObjectField(tile, "mTempState"))) return;
                nameHosts.put(tile, true);
                forceSingleLineEllipsis(XposedHelpers.getObjectField(tile, "mLabel"));
                if (tile.getClass().getName().contains("OplusQSTileViewImpl"))
                    forceSingleLineEllipsis(XposedHelpers.getObjectField(tile, "mSecondLine"));
            }
        } catch (Throwable ignored) { }
    }
    private static boolean wifiOrBluetooth(Object state) {
        if (state == null) return false;
        Object spec = XposedHelpers.getObjectField(state, "spec");
        return "wifi".equals(spec) || "bt".equals(spec);
    }

    public static void hookQsNormalCornerRadius(final XC_LoadPackage.LoadPackageParam lpparam) {

        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.qs.base.seek.OplusQsBaseToggleSliderLayout",
                    lpparam.classLoader, "setCornerRadius", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Boolean.TRUE.equals(replayingRadius.get()))
                                    radiusSliders.put(param.thisObject, new Object[]{"setCornerRadius", param.args[0], null});
                                if (param.thisObject instanceof View view) watchRadiusSize(view);
                                Float px = resolveQsCornerRadiusPx(param.thisObject);
                                if (px != null) param.args[0] = px;
                                else if (param.thisObject instanceof View view)
                                    param.args[0] = MergedCardRatioHooks.scaleRadius(view, (Float) param.args[0]);
                            } catch (Throwable t) {
                                log("qs_radius slider fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQsBaseToggleSliderLayout#setCornerRadius");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQsBaseToggleSliderLayout#setCornerRadius :: " + Log.getStackTraceString(t));
        }

        try {
            Class<?> baseSliderClass = XposedHelpers.findClass(
                    "com.oplus.systemui.qs.base.seek.OplusQsBaseToggleSliderLayout", lpparam.classLoader);
            Class<?> seekBarClass = XposedHelpers.findClass(
                    "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(baseSliderClass, "updateRadius", seekBarClass, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Boolean.TRUE.equals(replayingRadius.get()))
                                    radiusSliders.put(param.thisObject, new Object[]{"updateRadius", param.args[1],
                                            new java.lang.ref.WeakReference<>(param.args[0])});
                                if (qsCornerRadiusPercent() == null) return;
                                Float px = resolveQsCornerRadiusPx(param.thisObject);
                                if (px == null) return;
                                param.args[1] = px;
                            } catch (Throwable t) {
                                log("qs_radius slider fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQsBaseToggleSliderLayout#updateRadius");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQsBaseToggleSliderLayout#updateRadius :: " + Log.getStackTraceString(t));
        }

        hookQsHighlightTileOutline(lpparam, "com.oplus.systemui.qs.res.domain.interactor."
                + "StdQSTileResInteractor$startHighlightTileOutlineCollection$2");
        hookQsHighlightTileOutline(lpparam, "com.oplus.systemui.qs.res.domain.interactor."
                + "SepQSTileResInteractor$startHighlightTileOutlineCollection$2");

        hookQsHighlightRadius(lpparam);
    }

    // 旧版 ROM 的磁贴圆角入口: 直接改返回的半径(px), 比替换 outline provider 更靠上游。
    static void hookQsHighlightRadius(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.qs.personality.PersonalityManager", lpparam.classLoader,
                    "getHighlightRadius", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (qsCornerRadiusPercent() == null) return;
                                Context context = (Context) param.args[0];
                                if (context == null) return;
                                Float radius = resolveQsCornerRadiusPx(context.getResources());
                                if (radius != null) param.setResult(radius);
                            } catch (Throwable t) {
                                log("qs_radius outline fail: " + t);
                            }
                        }
                    });
            log("HOOK OK PersonalityManager#getHighlightRadius");
        } catch (Throwable t) {
            log("HOOK FAIL PersonalityManager#getHighlightRadius :: " + Log.getStackTraceString(t));
        }
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
                                Object result = param.getResult();
                                if (result == null) return;
                                if (!QS_OUTLINE_PROVIDER_CLASS.equals(result.getClass().getName())) return;
                                Object interactor = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                Context context = (Context) XposedHelpers.getObjectField(interactor, "context");
                                Class<?> pool = XposedHelpers.findClass("com.oplus.systemui.qs.base.res."
                                        + (clsName.contains("StdQSTileResInteractor") ? "StdQSResPool" : "SepQSResPool"), lpparam.classLoader);
                                Class<?> constant = XposedHelpers.findClass(QS_CONSTANT_CLASS, lpparam.classLoader);
                                radiusOutlines.put(pool, new Object[]{new java.lang.ref.WeakReference<>(context), result, constant});
                                Float radius = resolveQsCornerRadiusPx(context.getResources());
                                if (radius == null) return;
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

    static Object newQsMixColor(Class<?> cls, int darkTop) throws Exception {
        for (java.lang.reflect.Constructor<?> c : cls.getConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            boolean allInt = true;
            for (Class<?> t : types) {
                if (t != int.class) allInt = false;
            }
            if (!allInt) continue;
            c.setAccessible(true);
            if (types.length == 4) return c.newInstance(5, darkTop, 2, 0);
            if (types.length == 3) return c.newInstance(5, darkTop, 0);
        }
        return null;
    }

    static Object nativeMergedHighlightOutline() {
        for (java.util.Map.Entry<Class<?>, Object[]> entry : radiusOutlines.entrySet())
            if (entry.getKey().getName().equals("com.oplus.systemui.qs.base.res.StdQSResPool"))
                return entry.getValue()[1];
        return null;
    }

    private static void watchRadiusSize(View view) {
        if (radiusSizeListeners.containsKey(view)) return;
        View.OnLayoutChangeListener listener = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                radiusApplied = false;
                onCornerRadiusSettingsChanged();
            }
        };
        radiusSizeListeners.put(view, listener);
        view.addOnLayoutChangeListener(listener);
    }

    public static void onCornerRadiusSettingsChanged() {
        radiusHandler.removeCallbacks(radiusRefresh);
        radiusHandler.post(radiusRefresh);
    }

    private static void refreshCornerRadiusOnMain() {
        Integer radius = qsCornerRadiusPercent();
        if (radiusApplied && java.util.Objects.equals(appliedRadius, radius)) return;
        appliedRadius = radius;
        radiusApplied = true;
        // Publish through the native StateFlow so every bound tile redraws its path.
        for (java.util.Map.Entry<Class<?>, Object[]> entry : radiusOutlines.entrySet()) {
            Object[] saved = entry.getValue();
            Context context = (Context) ((java.lang.ref.WeakReference<?>) saved[0]).get();
            if (context == null) continue;
            try {
                Object outline = radius == null ? saved[1] : XposedHelpers.callStaticMethod((Class<?>) saved[2],
                        "getSmoothRoundRectOutlineProvider", context, resolveQsCornerRadiusPx(context.getResources()));
                XposedHelpers.callStaticMethod(entry.getKey(), "updateHighLightTileOutline", outline);
            } catch (Throwable t) { log("qs_radius live outline fail: " + t); }
        }
        java.util.List<Object[]> sliders = new java.util.ArrayList<>();
        synchronized (radiusSliders) {
            for (java.util.Map.Entry<Object, Object[]> entry : radiusSliders.entrySet())
                sliders.add(new Object[]{entry.getKey(), entry.getValue()});
        }
        replayingRadius.set(true);
        try {
            for (Object[] pair : sliders) {
                Object[] saved = (Object[]) pair[1];
                try {
                    if ("setCornerRadius".equals(saved[0]))
                        XposedHelpers.callMethod(pair[0], "setCornerRadius", saved[1]);
                    else {
                        Object seekbar = ((java.lang.ref.WeakReference<?>) saved[2]).get();
                        if (seekbar != null) XposedHelpers.callMethod(pair[0], "updateRadius", seekbar, saved[1]);
                    }
                } catch (Throwable t) { log("qs_radius live slider fail: " + t); }
            }
        } finally { replayingRadius.remove(); }
    }

    public static void cleanupCornerRadiusForHotReload() {
        radiusHandler.removeCallbacksAndMessages(null);
        for (java.util.Map.Entry<View, View.OnLayoutChangeListener> entry : radiusSizeListeners.entrySet())
            entry.getKey().removeOnLayoutChangeListener(entry.getValue());
        radiusSizeListeners.clear();
        radiusSliders.clear();
        radiusOutlines.clear();
        radiusApplied = false;
    }

    // 旧 16dp / 32dp 分别迁移到 25% / 50%；已有百分比优先。
    static Integer qsCornerRadiusPercent() {
        int mode = readInt(KEY_QS_CORNER_RADIUS_MODE,
                readBool(KEY_QS_NORMAL_CORNER_RADIUS_ENABLED, false) ? 2 : 0);
        if (!readBool(KEY_QS_CORNER_RADIUS_ENABLED, mode == 1 || mode == 2)) return null;
        int oldDp = readInt(KEY_QS_CORNER_RADIUS_DP, -1);
        int fallback = oldDp >= 0 ? Math.round(Math.min(32, oldDp) * 50f / 32f) : mode == 1 ? 50 : 25;
        return Math.max(0, Math.min(50, readInt(KEY_QS_CORNER_RADIUS_PERCENT, fallback)));
    }

    static Float resolveQsCornerRadiusPx(Object object) {
        if (!(object instanceof View view)) return null;
        Integer percent = qsCornerRadiusPercent();
        if (percent == null) return null;
        View target = view;

        try {
            Object slider = XposedHelpers.getObjectField(view, "slider");
            if (slider instanceof View child) target = child;
        } catch (Throwable ignored) { }
        try {
            Object frame = XposedHelpers.getObjectField(view, "mIconFrame");
            if (frame instanceof View child) target = child;
        } catch (Throwable ignored) { }
        int shortSide = Math.min(target.getWidth(), target.getHeight());
        try {
            Object background = XposedHelpers.getObjectField(view, "mBgDrawable");
            if (background instanceof android.graphics.drawable.Drawable drawable) {
                android.graphics.Rect bounds = drawable.getBounds();
                if (shortSide <= 0 && bounds.width() > 0 && bounds.height() > 0) shortSide = Math.min(bounds.width(), bounds.height());
            }
        } catch (Throwable ignored) { }
        return shortSide > 0 ? shortSide * percent / 100f : resolveQsCornerRadiusPx(view.getResources());
    }

    private static Float resolveQsCornerRadiusPx(Resources resources) {
        Integer percent = qsCornerRadiusPercent();
        if (percent == null) return null;
        int height = resources.getIdentifier("qs_highlight_tile_height", "dimen", "com.android.systemui");
        return height == 0 ? null : resources.getDimension(height) * percent / 100f;
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
        if (tv.getMaxLines() != 1) { tv.setSingleLine(true); tv.setMaxLines(1); }
        if (tv.getEllipsize() != TextUtils.TruncateAt.END) tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setHorizontallyScrolling(false); // 关闭横向滚动/跑马灯, 仅静态行尾省略
    }

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
