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
 * 状态栏相关的 SystemUI hook。
 */
public final class StatusBarHooks {
    // 流体云出现时不隐藏电量百分比: 流体云胶囊出现时 BatteryStyleModel.capsuleShowing=true,
    // 令 BatteryViewBinder.bind$updatePercentOutView 中的 PercentOutIcon.isVisible=false。
    // 这里 hook 该方法, 在 beforeHook 中把 isVisible 强制置 true。
    public static void hookFluidCloudKeepPercent(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> percentOutIconClass = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.model.PercentOutIcon",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.binder.BatteryViewBinder",
                    lpparam.classLoader,
                    "bind$updatePercentOutView",
                    TextView.class,
                    XposedHelpers.findClass(
                            "com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView",
                            lpparam.classLoader),
                    percentOutIconClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED, false)) return;
                            try {
                                Object percentOutIcon = param.args[2];
                                if (percentOutIcon == null) return;
                                // 强制 isVisible = true, 使电量百分比不被流体云隐藏。
                                XposedHelpers.setBooleanField(percentOutIcon, "isVisible", true);
                            } catch (Throwable t) {
                                log("fluid_cloud_keep_percent hook error: " + t);
                            }
                        }
                    });
            log("HOOK OK BatteryViewBinder#bind$updatePercentOutView (fluid cloud keep percent)");
        } catch (Throwable t) {
            log("HOOK FAIL BatteryViewBinder#bind$updatePercentOutView: " + t);
        }
    }
}
