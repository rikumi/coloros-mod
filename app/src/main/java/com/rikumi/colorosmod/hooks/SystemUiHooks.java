package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;

// SystemUI(com.android.systemui) 作用域的 hook 入口, 只做转发。
// 各功能按类别拆分到同包独立文件: QsHooks / NotificationHooks / StatusBarHooks /
// KeyguardHooks / PasswordInputHooks / StatusBarLyricHooks。
public final class SystemUiHooks {

    public static void hookSystemUi(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched systemui, classLoader=" + lpparam.classLoader);
        float density = readDensity();

        // Feature 5 — 隐藏运营商名: 始终注入, 运行时按 KEY_QS_CARRIER_ENABLED 门控(见内部)。
        QsHooks.hookQsHideCarrier(lpparam);

        // Feature 6 — 控制中心顶栏间距: 始终注入, 运行时按 KEY_QS_TOPMARGIN_ENABLED 门控。
        {
            final int footerPx = Math.round(QS_FOOTER_MARGIN_DP * density);
            log("qs_topmargin footerPx(fixed " + QS_FOOTER_MARGIN_DP + "dp)=" + footerPx);
            QsHooks.hookQsTopMargin(lpparam, footerPx);
        }

        // Feature 3 — 通知分组副标题: 始终注入, 运行时按 KEY_NOTIFICATION_SUBTITLE_ENABLED 门控,
        // 字号缩减量由 KEY_NOTIFICATION_SUBTITLE_SP(0-16sp, 默认 8sp) 运行时读取, 偏移/内边距随其等比缩放。
        NotificationHooks.hookNotificationSubtitle(lpparam, density);

        // Feature 3b — 通知内边距: 始终注入, 运行时按 KEY_NOTIFICATION_PADDING_ENABLED 门控,
        // 内边距由 KEY_NOTIFICATION_PADDING_DP(0-8dp, 默认 4dp) 运行时读取。
        NotificationHooks.hookNotificationPadding(lpparam, density);

        // 通知左滑直接清除: 始终注入, 运行时按 KEY_NOTIFICATION_SWIPE_TO_DISMISS_ENABLED 门控。
        NotificationHooks.hookNotificationSwipeToDismiss(lpparam);
        // 通知下滑展开: 始终注入, 运行时按 KEY_NOTIFICATION_PULL_EXPAND_ENABLED 门控。
        NotificationHooks.hookNotificationPullExpand(lpparam);
        // 通知图标区仓库(状态栏歌词显示时切"显示数字"要用): 由 NotificationHooks 统一下发显示模式,
        // 两处各写一份会互相拉扯(图标 <-> 数字来回跳)。
        NotificationHooks.hookNotificationIconAreaRepository(lpparam);

        // Feature 10 — 合并控制中心背景压暗(半透明黑): 始终注入, 运行时按 KEY_QS_SCRIM_TRANSLUCENT_ENABLED 门控。
        QsHooks.hookQsBackgroundDim(lpparam);
        // 控制中心背景模糊半径: 始终注入, 运行时按 KEY_QS_BLUR_RADIUS_ENABLED 门控。
        QsHooks.hookQsBackgroundBlurRadius(lpparam);
        // 控制中心背景缩小幅度: 始终注入, 运行时按 KEY_QS_BLUR_SCALE_ENABLED 门控。
        QsHooks.hookQsBackgroundScale(lpparam);
        // Feature 13 — 控制中心 WLAN/蓝牙 名称单行省略: 始终注入, 运行时按 KEY_QS_TILE_NAME_ELLIPSIS_ENABLED 门控。
        QsHooks.hookQsTileNameEllipsis(lpparam);
        // 控制中心 Wi-Fi/蓝牙/音量/亮度 圆角: 始终注入, 运行时按 KEY_QS_NORMAL_CORNER_RADIUS_ENABLED 门控。
        QsHooks.hookQsNormalCornerRadius(lpparam);
        // Feature 19 — 分离版控制中心左右切换取消切入效果: 始终注入, 运行时按 KEY_QS_PANEL_SWITCH_NO_CUT_ENABLED 门控。
        QsHooks.hookQsPanelSwitchNoCut(lpparam);
        // 合并控制中心时间日期取消展开动画: 始终注入, 运行时按 KEY_QS_CLOCK_NO_EXPAND_ANIM_ENABLED 门控。
        QsHooks.hookQsClockNoExpandAnim(lpparam);
        // Feature 17 — 流体云出现时不隐藏电量百分比: 始终注入, 运行时按 KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED 门控。
        StatusBarHooks.hookFluidCloudKeepPercent(lpparam);
        // Feature 18 — 悬浮小窗贴边挂机: 真正的提交逻辑在 system_server(android 作用域), 见 hookFloatWindowEdgeHangSystemServer。
        GestureHooks.hookGestureBarHeight(lpparam);
        GestureHooks.hookGestureBarLongPressDisable(lpparam);
        GestureHooks.hookMBack(lpparam);
        // 旋转建议按钮恢复原生位置(当前屏幕底边与建议方向下屏幕底边的夹角): 运行时按独立开关门控。
        GestureHooks.hookRotationButtonNativePosition(lpparam);
        // 独立功能 — 避免手势区域点击穿透: 与 mBack 解耦, 各自独立开关。
        GestureHooks.hookGestureTouchThrough(lpparam);
        // 解锁时关机无需校验密码: 与手势无关, 始终注入, 运行时门控。
        KeyguardHooks.hookUnlockedShutdownNoVerify(lpparam);
        // 取消解锁界面控件光效: 与手势无关, 始终注入, 运行时门控。
        PasswordInputHooks.hookKeyguardNoLightEffect(lpparam);
        // 自定义密码界面背景亮度: 与手势无关, 始终注入, 运行时门控。
        PasswordInputHooks.hookBouncerBackgroundBrightness(lpparam);
        // 锁屏通知区域下移: 运行时按 KEY_KEYGUARD_NOTIFICATION_OFFSET_ENABLED 门控,
        // 下移量由 KEY_KEYGUARD_NOTIFICATION_OFFSET_DP(0-80dp, 默认 40dp) 运行时读取。
        KeyguardHooks.hookKeyguardNotificationOffset(lpparam, density);
        // 输入密码界面支持侧滑或下滑返回: 运行时按 KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED 门控。
        KeyguardHooks.hookBouncerSwipeBack(lpparam);
        // 密码支持滑动输入: 运行时按 KEY_KEYGUARD_SLIDE_INPUT_ENABLED 门控。
        PasswordInputHooks.hookKeyguardSlideInput(lpparam);
        // 支持魅族状态栏歌词: 运行时按 KEY_STATUSBAR_LYRIC_ENABLED 门控。
        StatusBarLyricHooks.hookStatusBarLyric(lpparam);
    }
}
