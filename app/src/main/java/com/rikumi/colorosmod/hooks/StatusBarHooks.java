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

public final class StatusBarHooks {

    public static void hookFluidCloudKeepPercent(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> percentOutIconClass = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.model.PercentOutIcon",
                    lpparam.classLoader);
            Class<?> binder = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.binder.BatteryViewBinder",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(binder, "bind$updatePercentOutView", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED, false)) return;
                            try {
                                Object percentOutIcon = null;
                                for (Object arg : param.args) {
                                    if (arg != null && percentOutIconClass.isInstance(arg)) {
                                        percentOutIcon = arg;
                                        break;
                                    }
                                }
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
