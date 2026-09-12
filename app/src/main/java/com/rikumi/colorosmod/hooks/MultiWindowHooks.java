package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.COMPACT_CAPTION_BAR_HEIGHT_DP;
import static com.rikumi.colorosmod.XposedInit.KEY_SHRINK_CAPTION_BAR_ENABLED;
import static com.rikumi.colorosmod.XposedInit.log;
import static com.rikumi.colorosmod.XposedInit.readBool;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.util.Set;

/** 调整 ColorOS 分屏应用顶部三点菜单栏。 */
public final class MultiWindowHooks {
    private static final float ORIGINAL_CAPTION_BAR_HEIGHT_DP = 40f;
    // 系统三点 drawable 的路径中心在 21.5dp，而 40dp 画布的几何中心是 20dp。
    private static final float DOTS_CENTER_OFFSET_DP = 1.5f;
    private static final String CANVAS_CONTROL_BAR_TITLE = "CanvasControlBar";
    private static volatile boolean sResizeLogged;

    private MultiWindowHooks() {}

    public static void hookCanvasControlBar(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> flexibleTaskView = XposedHelpers.findClass(
                    "com.oplus.flexiblewindow.FlexibleTaskView", lpparam.classLoader);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    flexibleTaskView, "setExtraViewInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!readBool(KEY_SHRINK_CAPTION_BAR_ENABLED, false)) return;
                    if (param.args.length < 3 || !(param.args[0] instanceof View)
                            || !(param.args[1] instanceof WindowManager.LayoutParams)
                            || !(param.args[2] instanceof Region)) return;

                    WindowManager.LayoutParams layoutParams =
                            (WindowManager.LayoutParams) param.args[1];
                    if (!CANVAS_CONTROL_BAR_TITLE.contentEquals(layoutParams.getTitle())) return;

                    View bar = (View) param.args[0];
                    int oldHeight = layoutParams.height;
                    int originalHeight = Math.max(1, dpToPx(bar.getContext(),
                            ORIGINAL_CAPTION_BAR_HEIGHT_DP));
                    int compactHeight = Math.max(1, Math.round(
                            dpToPx(bar.getContext(), COMPACT_CAPTION_BAR_HEIGHT_DP)
                                    * oldHeight / (float) originalHeight));
                    if (oldHeight <= compactHeight) return;

                    layoutParams.height = compactHeight;
                    if (bar instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) bar;
                        float offset = dpToPx(bar.getContext(), DOTS_CENTER_OFFSET_DP)
                                * oldHeight / (float) originalHeight;
                        for (int i = 0; i < group.getChildCount(); i++) {
                            View child = group.getChildAt(i);
                            if (child instanceof ImageButton) {
                                child.setTranslationY(-offset);
                                break;
                            }
                        }
                    }
                    Rect touchBounds = ((Region) param.args[2]).getBounds();
                    param.args[2] = new Region(touchBounds.left, 0,
                            touchBounds.right, compactHeight);
                    if (!sResizeLogged) {
                        sResizeLogged = true;
                        log("canvas control bar height " + oldHeight + "px -> "
                                + compactHeight + "px");
                    }
                }
            });
            if (hooks.isEmpty()) {
                throw new NoSuchMethodError(flexibleTaskView.getName() + "#setExtraViewInfo");
            }
            log("HOOK OK FlexibleTaskView#setExtraViewInfo (canvas control bar)");
        } catch (Throwable t) {
            log("HOOK FAIL canvas control bar height: " + t);
        }
    }

    private static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()));
    }
}
