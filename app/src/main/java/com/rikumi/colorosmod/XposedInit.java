package com.rikumi.colorosmod;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.TextSwitcher;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ColorOS (Oplus) launcher tweaks, applied via LSPosed.
 *
 * Feature 1 — icon / label gap:
 *   The vertical gap between a launcher icon and its label is computed by
 *     - {@code IconParam.getIconDrawablePaddingPx()}            (home + folder icons)
 *     - {@code AllAppsParam.getAllAppsIconDrawablePaddingPx()}  (app drawer icons)
 *   and later applied through {@code BubbleTextView.setCompoundDrawablePadding(...)}.
 *   We add a pixel offset (0-8dp, configurable via slider, default 4dp, scaled by density)
 *   to enlarge the gap. The dp value is read at runtime from settings (KEY_ICON_GAP_DP).
 *
 * Feature 2 — page indicator gaps (no height change):
 *   The desktop page indicator (dots) is drawn centered inside a fixed-height region
 *   ({@code PageIndicatorParam.getWorkspacePageIndicatorHeight()}). The launcher computes
 *   Workspace top padding in {@code WorkspaceParam.updatePaddingTopAndBottom}: a hotseat height
 *   reduction is multiplied by {@code workspaceTopPercentage}, so the visible page-to-Dock gap
 *   shrinks by only {@code delta * (1 - workspaceTopPercentage)}. The hook therefore divides
 *   the requested dp reduction by that ratio before changing
 *   {@code HotseatParam.getHotseatBarSizePx()}, compensating the launcher’s extra processing.
 *
 * Feature 3 — control center (SystemUI / com.android.systemui):
 *   用户使用经典(合并)下拉面板，由 {@code com.oplus.systemui.qs.OplusQuickStatusBarHeader} 驱动。
 *   (分离式 SeparateQSFakeStatusController 不再 hook，避免在经典模式下叠加造成重复下沉。)
 *     - 隐藏运营商: OplusQuickStatusBarHeader#onFinishInflate 中 R.id.qs_carrier_text
 *       (位于 qs_clock_container 内) / R.id.carrier_group 显示运营商名，afterHook 直接 GONE。
 *     - 隐藏状态图标簇: 用户不需要控制中心展开后顶部的状态图标簇(R.id.quick_qs_status_icons, 内含
 *       icons + batteryRemainingIcon), 在展开进度回调中原地渐隐。注意: 通知栏(CombinedShadeHeader)
 *       的状态图标不在本模块处理范围, 不受影响。
 *     - 页脚: OplusQSFooterImpl#updateResources$15(仅 collapsed 时触发) 把 mSettingsContainer 顶部 padding
 *       重置为 0 后叠加 QS_FOOTER_MARGIN_DP(较小, 让日期/设置按钮小幅下沉, 不过深)。
 *
 * Feature 6 — 多任务(quickstep)显示被系统隐藏的应用 (launcher / com.android.launcher):
 *   系统"隐藏应用"由 {@code com.oplus.quickstep.privacy.OplusPrivacyManager.isHiddenPkg(pkg, userId)}
 *   判定。最近任务列表在 {@code OplusRecentTasksFilter.filterTaskInfo} 中据此把隐藏任务剔除；
 *   {@code OplusRecentsViewImpl} 又在 shouldAddStubTaskView / onGestureAnimationStart 中据此
 *   跳过隐藏应用的 stub 卡片与手势概览分支。任务本身会到达桌面进程, 只是被二次过滤。
 *   在 isHiddenPkg 上加 beforeHook: 当且仅当调用方属于 quickstep 多任务渲染/手势路径时返回 false,
 *   从而让隐藏应用照常出现在最近任务列表与手势概览; 应用锁(locksetting/applock)等其它隐藏态判断不受影响。
 *
 * Feature 5 — notification vertical padding (SystemUI / com.android.systemui):
 *   非静默(未被最小化, mIsMinimized==false)普通通知：给其通知子视图(contracted/expanded/headsUp)的
 *   上下内边距各加 NOTIFICATION_PADDING_DP(4dp)；合并通知则给整个 NotificationChildrenContainer
 *   增加上下外侧留白，并同步下移合并通知标题中的时间与展开箭头，避免只有合并通知最后一行被撑开。
 *   NotificationContentView#getViewHeight 计入高度(它取子视图自身 getHeight, 不含 NotificationContentView
 *   自身 padding), 使整张卡片随之增高、内部上下留白增加。静默(最小化)通知不处理。
 *
 * Feature 4 — notification section subtitle tweak (SystemUI / com.android.systemui):
 *   通知面板分组标题（如"静默"/"对话"等）由 SectionHeaderView 布局驱动，
 *   其内部 TextView(@id/header_label) 原始 textSize=24sp。hook SectionHeaderView#onFinishInflate,
 *   在 afterHook 中按滑条缩减量(KEY_NOTIFICATION_SUBTITLE_SP, 默认 8sp -> 16sp)缩小 mLabelView 字体,
 *   并把内容容器 paddingTop 减少、translationX 右移(偏移/内边距随缩减量等比缩放)。
 *
 * Each feature is gated by its own switch in the module's shared preferences.
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "ColorOSMod";
    private static final String MODULE_PACKAGE = "com.rikumi.colorosmod";
    private static final String PREF_NAME = "settings";

    private static final String KEY_ICON_GAP_ENABLED = "icon_gap_enabled";
    private static final String KEY_ICON_GAP_DP = "icon_gap_dp";
    private static final String KEY_INDICATOR_DP = "indicator_dp";
    private static final String KEY_POPUP_SCALE_PERCENT = "popup_scale_percent";
    private static final String KEY_NOTIFICATION_SUBTITLE_SP = "notification_subtitle_sp";
    private static final String KEY_NOTIFICATION_PADDING_DP = "notification_padding_dp";
    private static final String KEY_INDICATOR_ENABLED = "indicator_enabled";
    private static final String KEY_QS_CARRIER_ENABLED = "qs_carrier_enabled";
    private static final String KEY_QS_TOPMARGIN_ENABLED = "qs_topmargin_enabled";
    private static final String KEY_NOTIFICATION_SUBTITLE_ENABLED = "notification_subtitle_enabled";
    private static final String KEY_NOTIFICATION_PADDING_ENABLED = "notification_padding_enabled";
    private static final String KEY_RECENTS_SHOW_HIDDEN_ENABLED = "recents_show_hidden_enabled";
    private static final String KEY_RECENTS_HIDE_FREEFORM_ENABLED = "recents_hide_freeform_enabled";
    private static final String KEY_HIDE_APPS_NOVERIFY_ENABLED = "hide_apps_noverify_enabled";
    private static final String KEY_HIDE_APPS_TITLE_FOLDER_ENABLED = "hide_apps_title_folder_enabled";
    // 通用设置 — 设置首页图标样式: 0=系统默认, 1=不规则图标, 2=圆形图标。
    private static final String KEY_SETTINGS_HOME_ICON_STYLE = "settings_home_icon_style";
    private static final int SETTINGS_HOME_ICON_STYLE_DEFAULT = 0;
    private static final int SETTINGS_HOME_ICON_STYLE_IRREGULAR = 1;
    private static final int SETTINGS_HOME_ICON_STYLE_CIRCLE = 2;
    // Feature 12 — 缩小桌面图标长按菜单(com.android.launcher):
    // 长按菜单(深度快捷方式 / 系统快捷方式)的尺寸由一组 @dimen 资源决定,
    // 这里在资源层按比例整体缩放图标、文字、宽高与内边距/外边距, 使菜单整体变小。
    private static final String KEY_SHRINK_POPUP_MENU = "shrink_popup_menu";
    // 长按菜单缩小比例的默认值(百分比, 0=系统原始大小)。实际值由滑条 KEY_POPUP_SCALE_PERCENT
    // 在运行时读取(0..2*默认值), 缩放系数 = 1 - pct/100。
    private static final int POPUP_SHRINK_PERCENT_DEFAULT = 10;
    // Feature 9 — 桌面双指张开(pinch-out)手势打开隐藏应用文件夹 (com.android.launcher)
    private static final String KEY_PINCH_OUT_OPEN_HIDE_APPS_ENABLED = "pinch_out_open_hide_apps_enabled";
    // Feature 14 — 桌面文件夹展开背景透明化 (com.android.launcher):
    // 文件夹展开时的"全屏灰色背景"实为启动器对壁纸施加的高斯模糊+暗色调和
    // (OplusDepthController: getFolderBlur()/getFolderDepth() 恒为 1.0f, 打开文件夹时把壁纸 blur 拉到 1.0,
    // 并叠加 mBlurBlendColor 暗色, 看起来像一层灰)。
    // 所有壁纸模糊最终都汇入私有方法 OplusDepthController.setBlur(float, boolean)
    // (BLUR/MIRROR_BLUR 属性、setBlurWithoutAnim、各 blur 动画均走它)。
    // 这里 hook 该方法: 当有文件夹处于打开(含打开/关闭动画)状态时, 把模糊值强制为 0,
    // 从而整屏不再变灰, 文件夹直接浮在清晰壁纸上; 不影响多任务/应用抽屉等其它场景的模糊。
    private static final String KEY_FOLDER_BG_TRANSPARENT_ENABLED = "folder_bg_transparent_enabled";
    // Feature 16 — 桌面编辑模式背景遮罩透明化 (com.android.launcher):
    // ToggleBarState/PagePreviewState 原生把编辑态壁纸 blur 固定为 1.0f, 同时可能叠加页面背景 alpha。
    private static final String KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED = "edit_mode_bg_transparent_enabled";
    // Feature 10 — 合并控制中心背景 scrim 亮度 (com.android.systemui)
    private static final String KEY_QS_SCRIM_TRANSLUCENT_ENABLED = "qs_scrim_translucent_enabled";
    // 背景亮度滑条键(0-20, 默认 10): 0=全黑, 20=系统默认 lumin(不压暗)。
    private static final String KEY_QS_SCRIM_BRIGHTNESS = "qs_scrim_brightness";
    // Feature 13 — 控制中心 WLAN/蓝牙 名称单行省略 (com.android.systemui):
    // 可伸缩 tile(OplusQSResizeableTileViewTwoXOne/TwoXTwo, 即 WLAN/蓝牙 这类磁贴)的次级名称
    // (已连接 SSID / 蓝牙设备名)承载在 labelDesc(TextSwitcher, R.id.tile_label_desc),
    // 由 updateLabelDescText(QSTile.State) 经 TextSwitcherExtKt.setContent 写入。
    // 默认该 TextView 可能换行/截断方式不合预期, 这里在其每次 setContent 之后强制单行 + 行尾省略号。
    private static final String KEY_QS_TILE_NAME_ELLIPSIS_ENABLED = "qs_tile_name_ellipsis_enabled";
    // Feature 17 — 流体云出现时不隐藏电量百分比 (com.android.systemui):
    // 系统在流体云胶囊出现时会把 BatteryStyleModel.capsuleShowing=true,
    // 进而令 PercentOutIcon.isVisible=false, 隐藏状态栏电量百分比数字。
    // hook BatteryViewBinder.bind$updatePercentOutView, 强制 isVisible=true。
    private static final String KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED = "fluid_cloud_keep_percent_enabled";
    // Feature 18 — 悬浮小窗贴边挂机(com.android.systemui): 允许悬浮小窗(自由窗口 FlexibleWindow)拖动到屏幕侧边时,
    // 不触发"应用切到后台/贴边最小化", 让小窗停在手指松开处(可拖出屏幕)、保持前台。
    // 根因: 拖动到侧边时系统把该自由窗口任务 moveTaskToBack(最小化到边缘小条、应用切后台)。该 decision 由框架层
    // 用 WindowContainerTransaction(WCT) 承载, WCT 经 SystemUI 的 android.window.TaskOrganizer#applyTransaction(WCT)
    // 这个"总出口"发往 system_server 才真正生效。
    // 实现(见 hookFloatWindowEdgeHang): 在 SystemUI 进程内 hook TaskOrganizer#applyTransaction(before), 当该 WCT 是
    // "贴边挂机": 悬浮小窗拖到屏幕边缘松手时, 系统原生会把窗口缩成边缘竖条/图标把手、并把任务切到后台(可点击把手呼出)。
    // 本功能在系统原生 to-float 的基础上, 于 to-float 完成(任务已切后台)之后主动把该任务 moveToFront 拉回前台,
    // 使游戏/应用恢复前台持续运行(挂机 = 前台运行, 不暂停), 而不是最小化到后台。实现见 hookFloatWindowEdgeHangSystemServer。
    // 调用链(simpleperf 抓取确认, 在 system_server 即 package "android" 内, 不在 SystemUI):
    //   FlexibleFloatHandleAnimationSpec.defaultCallAnimationEnd
    //     -> FlexiblePointerHandler$2.onAnimationEnd
    //       -> FlexibleTaskController.exitFlexibleTaskWindowInnerLocked(AbsFlexibleTaskExitStrategy)
    //         -> exitFlexibleTaskWindowInner -> ExitFlexibleTaskToFloatStrategy.handleEvent()
    //             ① floatHandleController.addFloatHandle(...) —— 把任务加入浮窗列表
    //             ② 跑最小化动画把窗口缩成边缘竖条/图标形态(同时把真实窗口 hide)
    //             ③ 动画结束 -> exitFlexibleTask(task, needExitTask, 16, ...)
    //                  -> TaskExtImpl.moveTaskToBackForPanorama -> Task.moveTaskToBack -> nativeApplyTransaction(切后台)
    // 竖条/图标形态由 handleEvent 内部的 ②③ 独立建立, 与"松手前是否按住等成形"无关 —— 任意时机松手都能可靠成形。
    // ▲ 重要教训: ② 会把真实窗口 hide(隐藏), 而 moveTaskToBack 才让任务失焦。若只取消提交或只跳过后台化,
    //   会出现"真实窗口已隐藏、任务却仍前台 focused" -> "Application does not have a focused window"
    //   -> 音量条不出 + Input dispatching timed out -> ANR/crash(本功能两个早期版本都踩过)。
    //   因此不能在 to-float 提交中途拦截; 正确做法是在 to-float 完全结束(任务已切后台)之后, 再 moveToFront 把任务拉回前台 ——
    //   此时窗口由系统 bringToFront 重新显示(不再 hidden), 任务前台且可见, 无 ANR。仅对"贴边成浮窗"一路(isInFloatingList)生效,
    //   普通最小化不介入。
    // 需要把模块作用域加入 "android"(system_server) 才能让本模块在该进程注入(否则 hook 不生效); 旧版 SystemUI 内 hook 路径已废弃。
    private static final String KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED = "float_window_edge_hang_enabled";
    // Feature 22 — 横屏应用小窗保持比例(com.android.server.wm.FlexibleTaskController, android/system_server):
    // 横屏应用进入小窗(含竖屏下查看横屏应用小窗)时, 让小窗的 宽:高 = 屏幕 高:宽(如 19.5:9),
    // 即小窗高度/宽度 = 屏幕宽度/屏幕高度。系统在 fillFlexibleTaskInfo 中对横屏应用硬编码
    // ratio=0.5625f(高/宽=9:16), 与设备真实屏幕比例不符, 故在 system_server 内接管该 ratio 与 launchBounds。
    private static final String KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED =
            "float_window_landscape_keep_ratio_enabled";
    private static final String KEY_GESTURE_BAR_HEIGHT_ENABLED = "gesture_bar_height_enabled";
    private static final String KEY_GESTURE_BAR_HEIGHT_DP = "gesture_bar_height_dp";
    private static final String KEY_GESTURE_BAR_WIDTH_ENABLED = "gesture_bar_width_enabled";
    private static final String KEY_GESTURE_BAR_WIDTH_DP = "gesture_bar_width_dp";
    private static final String KEY_MBACK_ENABLED = "mback_enabled";
    // 判定为向左/右/上划动的阈值（dp），超过则放弃 MBack 接管。
    private static final int MBACK_SWIPE_DP = 20;
    private static final String KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED =
            "gesture_bar_long_press_disable_enabled";
    // Feature 11 — 从桌面隐藏指定的单个 LAUNCHER 活动 (com.android.launcher):
    // 某些应用一个包内有多个 LAUNCHER 入口(如电话本+拨号), 系统"隐藏应用"按包隐藏会误伤,
    // 故在 launcher 进程内拦截 LauncherApps.getActivityList / PackageManager.queryIntentActivities,
    // 仅过滤掉目标组件, 使其不出现在应用抽屉/添加应用列表。
    // 配置表: 每项 = { 门控偏好键, 包名, 活动类名 }; 门控关闭则保留该项。
    private static final String KEY_HIDE_CONTACTS_ENABLED = "hide_contacts_enabled";
    private static final String KEY_HIDE_GBOARD_ENABLED = "hide_gboard_enabled";
    // Feature 15 — 隐藏 GhostLock 图标(com.ghostlock.app): 已有 root 时无需再 root。
    private static final String KEY_HIDE_GHOSTLOCK_ENABLED = "hide_ghostlock_enabled";
    // Feature 11b — 修改安全中心"隐藏应用"对电话本的处理逻辑(非独立模块开关):
    // 系统原生隐藏是整包禁用(会连拨号 DialtactsActivityAlias 一起失效), 故 hook 安全中心隐藏流程,
    // 让 com.android.contacts 走"只加入隐藏应用列表、不整包 PMS 禁用"的 path
    // (PMSHideAppListUtil#t 返回 true 使其归入 s3.c.D 的 map4 分支), 从而联系人进入隐藏应用
    // (需验证打开文件夹)但拨号保持可用; 同时让 UI 回读显示已隐藏。
    // 桌面侧在 OplusAppFilter#shouldShowApp 做组件级特例: 拨号始终显示、电话本随包隐藏(见上方 launcher 段)。
    // 用户直接在系统"隐藏应用"里勾选电话本即可, 模块不再提供独立开关。
    private static final android.content.ComponentName CONTACTS_DIALER =
            new android.content.ComponentName("com.android.contacts",
                    "com.android.contacts.DialtactsActivityAlias");
    private static final String[][] HIDDEN_LAUNCHER_TARGETS = {
            // 电话本(保留同包拨号 DialtactsActivityAlias)
            {KEY_HIDE_CONTACTS_ENABLED, "com.android.contacts",
                    "com.android.contacts.PeopleActivityAlias"},
            // Gboard 启动入口
            {KEY_HIDE_GBOARD_ENABLED, "com.google.android.inputmethod.latin",
                    "com.google.android.libraries.inputmethod.launcher.LauncherActivity"},
            // GhostLock 启动入口(已有 root 时无需再 root)
            {KEY_HIDE_GHOSTLOCK_ENABLED, "com.ghostlock.app",
                    "com.ghostlock.app.MainActivity"},
    };

    // 运行时根据门控偏好键, 计算当前需要隐藏的组件集合。
    private static java.util.Set<android.content.ComponentName> getHiddenLauncherComponents() {
        java.util.Set<android.content.ComponentName> set = new java.util.HashSet<>();
        for (String[] t : HIDDEN_LAUNCHER_TARGETS) {
            if (readBool(t[0], false)) {
                set.add(new android.content.ComponentName(t[1], t[2]));
            }
        }
        return set;
    }
    // 以下为检测"桌面是否处于正常状态(NORMAL)"所需的反射缓存; 仅在 NORMAL 状态才响应手势,
    // 编辑状态(长按桌面进入)下的 pinch-out 交由系统处理(回到正常状态), 不触发本功能。
    private static volatile Class<?> sLauncherClass;
    private static volatile Class<?> sLauncherStateClass;
    private static volatile Object sNormalState;

    // 跨进程读取开关用的应用 Context(被 hook 进程自身)与 ContentProvider 通道所需的字段。
    private static volatile android.content.Context sAppContext;
    private static final String SETTINGS_AUTHORITY = "com.rikumi.colorosmod.settings";
    // 开关值缓存 TTL。长按图标/拖拽期间会以触摸事件频率反复 readBool(尤其 pinch-out 的
    // dispatchTouchEvent), 500ms 的短 TTL 会在手势过程中反复过期、触发同步 ContentProvider IPC,
    // 造成主线程卡顿。提高到 5s 后, 一次交互内的连续读取全部命中内存缓存(零 IPC); 对"改设置后
    // 即时生效"的影响仅为最多 5s 延迟, 实际切回桌面验证通常已超过该时长, 无感。
    private static final long CACHE_TTL_MS = 5000;
    private static final java.util.concurrent.ConcurrentHashMap<String, Object[]> sCache =
            new java.util.concurrent.ConcurrentHashMap<String, Object[]>(); // key -> {Long ts, Boolean val}

    private static final int ICON_GAP_DP = 4;
    private static final int INDICATOR_REDUCE_DP = 32; // requested page-to-Dock gap reduction in dp
    private static final int INDICATOR_REDUCE_MAX_DP = 128;
    private static final int QS_FOOTER_MARGIN_DP = 8; // smaller top gap for footer (date/settings) so it sinks a little
    // 状态图标簇在展开第一帧直接隐藏，避免出现原生移动动画。
    private static final float SUBTITLE_ORIG_SP = 24f; // system default subtitle text size
    private static final int SUBTITLE_REDUCE_SP_DEFAULT = 8; // default reduction (24sp -> 16sp); slider 0..2x
    private static final float SUBTITLE_OFFSET_DP = 8f; // move subtitle up & right by 8dp each (at default reduction)
    private static final int SUBTITLE_PAD_DP = 4; // extra top & bottom padding for the subtitle tv (at default reduction)
    private static final int NOTIFICATION_PADDING_DP = 4; // extra top & bottom padding for non-minimized (non-silent) notifications
    // 控制中心背景亮度: 默认 10(对应约 50% 系统默认 lumin); 系统默认 lumin 的 RGB 值为 0x33(51)。
    private static final int QS_SCRIM_BRIGHTNESS_DEFAULT = 10;
    private static final int QS_SCRIM_LUMIN_MAX = 0x33;

    // 调试日志: 仅用 Log.e(error 级别), 因为 ColorOS 会丢弃 Log.d/v/i/w 等非 error 日志。
    // 不触碰外部存储, 避免被 hook 的第三方进程(如桌面 com.android.launcher)因无存储权限而
    // 触发 MediaProvider(FUSE) 的 SecurityException 刷屏。
    private static void dbg(String msg) {
        Log.e(TAG, msg);
    }

    // 真实错误日志: Log.e 立即输出, 文件写入异步执行, 避免阻塞 Launcher/SystemUI 主线程。
    // 仅输出到 logcat, 不写文件, 避免 IO 卡顿。
    private static void log(String msg) {
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        log("handleLoadPackage pkg=" + lpparam.packageName);
        // 缓存被 hook 进程自身的 Application Context, 供 readBool 通过 ContentResolver 跨进程查询设置。
        if (sAppContext == null) {
            sAppContext = currentApplication();
        }
        if ("com.android.launcher".equals(lpparam.packageName)) {
            hookLauncher(lpparam);
        } else if ("com.android.systemui".equals(lpparam.packageName)) {
            hookSystemUi(lpparam);
        } else if ("com.oplus.safecenter".equals(lpparam.packageName)) {
            hookSafecenter(lpparam);
        } else if ("com.android.settings".equals(lpparam.packageName)) {
            hookSettings(lpparam);
        } else if ("android".equals(lpparam.packageName)) {
            // system_server: 承载"贴边最小化"的真正提交逻辑(com.android.server.wm.FlexibleTaskController)
            hookFloatWindowEdgeHangSystemServer(lpparam);
            // system_server: 横屏应用小窗保持比例(com.android.server.wm.FlexibleTaskController)
            hookFloatWindowLandscapeKeepRatio(lpparam);
        }
    }

    // Feature 12 — 缩小桌面图标长按菜单(com.android.launcher)。
    // 该菜单的尺寸(图标 / 文字 / 宽高 / 内边距 / 外边距)由布局与主题属性决定,
    // 并不在运行时通过 Resources.getDimension* 解析(已实测确认: 长按时无菜单相关 dimen 被读取),
    // 故资源钩子 / getDimension 钩子都无法整体缩放。
    // 改为监听长按菜单根容器(deep_shortcuts_container)的 onAttachedToWindow, 在其整棵子树
    // attach 完成后, 按运行时缩小百分比(KEY_POPUP_SCALE_PERCENT, 默认 POPUP_SHRINK_PERCENT_DEFAULT)
    // 对容器做整体 scaleX/scaleY 变换(绝对赋值, 幂等不叠加)。已应用的百分比记录在实例附加字段,
    // 滑条值变化后下次弹出会按新值重新缩放。
    // 缓存 popup 容器类, 避免重复查找
    private static volatile Class<?> sPopupContainerClass = null;

    // 缩小桌面图标长按菜单:
    // 直接对 OplusPopupContainerWithArrow 内部的卡片容器(mAllPopupShortcutContainer)做整体
    // scaleX/scaleY 变换。该容器承载卡片背景与所有菜单项(图标/文字/行高/内边距都画在它里面),
    // 而 popup 的打开动画只缩放外层容器, 不会触碰它, 所以变换恒定生效、不被布局重写覆盖。
    // 轴心设在箭头一侧, 缩放后箭头仍精确指向图标。
    private static void hookPopupMenuDimens(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> popupClass = XposedHelpers.findClass(
                    "com.android.launcher3.popup.OplusPopupContainerWithArrow",
                    lpparam.classLoader);
            sPopupContainerClass = popupClass;
            XposedHelpers.findAndHookMethod(popupClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_SHRINK_POPUP_MENU, false)) {
                                return;
                            }
                            android.view.View v = (android.view.View) param.thisObject;
                            int pct = readInt(KEY_POPUP_SCALE_PERCENT, POPUP_SHRINK_PERCENT_DEFAULT);
                            Object applied = XposedHelpers.getAdditionalInstanceField(v, "colorosmod_popup_pct");
                            if (applied instanceof Integer && (Integer) applied == pct) {
                                return;
                            }
                            XposedHelpers.setAdditionalInstanceField(v, "colorosmod_popup_pct", pct);
                            v.post(() -> scalePopupContainer(v));
                        }
                    });
            log("hooked popup menu container scaling");
        } catch (Throwable t) {
            log("hook popup menu container failed: " + t);
        }
    }

    private static void scalePopupContainer(android.view.View popupContainer) {
        if (!readBool(KEY_SHRINK_POPUP_MENU, false)) {
            return;
        }
        int pct = readInt(KEY_POPUP_SCALE_PERCENT, POPUP_SHRINK_PERCENT_DEFAULT);
        float scale = 1f - pct / 100f;
        android.view.View target;
        try {
            Object inner = XposedHelpers.getObjectField(popupContainer, "mAllPopupShortcutContainer");
            target = (inner instanceof android.view.View) ? (android.view.View) inner : popupContainer;
        } catch (Throwable t) {
            target = popupContainer; // 兜底: 直接缩放整个 popup 容器
        }
        if (target == null) {
            return;
        }
        int w = target.getWidth();
        int h = target.getHeight();

        // 轴心 = 箭头位置。直接用 launcher 自带的 calculatePivotX() 得到"外层容器坐标系"下箭头的 x,
        // 再换算到被缩放的内层卡片坐标系(用屏幕坐标差, 对任意嵌套都鲁棒), 保证无论左/右/居中弹出,
        // 菜单都精确围绕箭头缩放, 不会整体偏移。
        // 垂直方向: 弹出在图标上方(mIsAboveIcon)→箭头在卡片底边→pivotY=h; 否则在顶边→0。
        boolean above = false;
        float pivotX = w / 2.0f;
        float pivotY = h / 2.0f;
        try {
            above = XposedHelpers.getBooleanField(popupContainer, "mIsAboveIcon");
        } catch (Throwable ignored) {
        }
        try {
            Object pxObj = XposedHelpers.callMethod(popupContainer, "calculatePivotX");
            float pxOuter = ((Number) pxObj).floatValue();
            int[] outer = new int[2];
            int[] inner = new int[2];
            popupContainer.getLocationOnScreen(outer);
            target.getLocationOnScreen(inner);
            float offX = inner[0] - outer[0];
            float offY = inner[1] - outer[1];
            pivotX = pxOuter - offX;
            pivotY = above ? (h - offY) : (0 - offY);
        } catch (Throwable t) {
            log("popup pivot calc failed, using fallback: " + t);
            pivotX = w / 2.0f;
            pivotY = above ? h : 0.0f;
        }
        target.setPivotX(pivotX);
        target.setPivotY(pivotY);
        target.setScaleX(scale);
        target.setScaleY(scale);
        fixPopupDividerThickness(target, scale);
        log("popup menu scaled: scale=" + scale + " w=" + w + " h=" + h + " pivotX=" + pivotX
                + " pivotY=" + pivotY + " above=" + above);
    }

    // 每个 DeepShortcutView 内的 R.id.divider 是列表项之间的分割线, 其高度来自
    // @dimen/coui_list_divider_height(物理 1px)。整体被 scaleX/Y 缩小 scale 后渲染成 sub-pixel 不可见。
    // 把它改大为 1px / scale(向上取整), 缩小后恰好渲染成约 1px 的细线。
    private static void fixPopupDividerThickness(android.view.View root, float scale) {
        int dividerId;
        try {
            dividerId = root.getResources().getIdentifier("divider", "id", "com.android.launcher");
        } catch (Throwable t) {
            return;
        }
        if (dividerId <= 0) {
            return;
        }
        // 目标: 整体缩小 scale 后分割线仍渲染出 1px。
        // 故预先把高度设为 1px / scale, 向上取整保证缩小后至少 1px(整数布局高度)。
        final int oneDp = Math.max(1, (int) Math.ceil(1.0f / Math.max(scale, 0.01f)));
        fixPopupDividerRecursive(root, dividerId, oneDp);
    }

    private static void fixPopupDividerRecursive(android.view.View v, int dividerId, int oneDp) {
        if (v == null) {
            return;
        }
        if (v.getId() == dividerId) {
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != oneDp) {
                lp.height = oneDp;
                v.requestLayout();
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                fixPopupDividerRecursive(vg.getChildAt(i), dividerId, oneDp);
            }
        }
    }

    private static void hookLauncher(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched launcher, classLoader=" + lpparam.classLoader);
        float density = readDensity();

        // Feature 12 — 缩小长按菜单: 在 launcher 进程内拦截 Resources.getDimension*, 对菜单 dimen 缩放。
        hookPopupMenuDimens(lpparam);

        // Feature 1 — 图标间距: 始终注入, 运行时按 KEY_ICON_GAP_ENABLED 门控(关闭返回原值),
        // 间距值由 KEY_ICON_GAP_DP(0-8dp, 默认 4dp) 在运行时读取, App 内拖滑条即时生效。
        hookPxRuntime(lpparam, "com.android.launcher.layoutparam.IconParam",
                "getIconDrawablePaddingPx", density, KEY_ICON_GAP_ENABLED, KEY_ICON_GAP_DP, ICON_GAP_DP, 8, 1);
        hookPxRuntime(lpparam, "com.android.launcher.layoutparam.AllAppsParam",
                "getAllAppsIconDrawablePaddingPx", density, KEY_ICON_GAP_ENABLED, KEY_ICON_GAP_DP, ICON_GAP_DP, 8, 1);

        // Feature 2 — 页面与 Dock 间距: 始终注入, 运行时按 KEY_INDICATOR_ENABLED 门控。
        // 系统会把 hotseat 高度变化按 workspaceTopPercentage 分摊到页面位置，
        // 因此不能直接减 requestedDp；hook 中会反推实际需要的 hotseat 高度变化。
        hookIndicatorHotseatSize(lpparam, density);

        // Feature 4 — 多任务显示隐藏应用: 始终注入, 运行时按 KEY_RECENTS_SHOW_HIDDEN_ENABLED 门控。
        hookRecentsShowHidden(lpparam);
        // Feature 19 — 多任务不显示小窗应用: 始终注入, 运行时按 KEY_RECENTS_HIDE_FREEFORM_ENABLED 门控。
        hookRecentsHideFreeform(lpparam);

        // Feature 8 — 隐藏应用文件夹标题显示用户自定义文件夹名 (com.android.launcher):
        // 桌面隐藏应用入口打开后, 启动器渲染一个 "虚拟文件夹" 来承载隐藏的应用。
        // 该文件夹的标题由 com.android.launcher.filter.DeepProtectedAppsManager
        // #createVirtualFolder() 硬编码为 R.string.app_hidden_title ("应用隐藏")。
        // LauncherModel 加载时 (以及打开隐藏应用广播触发时) 调用此方法生成 FolderInfo,
        // 随后 bindVirtualFolder(folderInfo) 用 folderInfo.title 渲染文件夹标题。
        // 此处 hook createVirtualFolder, 在返回后把 folderInfo.title 替换为用户在
        // launcher OplusFavoritesProvider/desktopappedit 中为该入口自定义的文件夹名。
        // Feature 8 — 隐藏应用文件夹标题显示用户自定义文件夹名: 始终注入, 运行时按开关门控。
        try {
            Class<?> mgrClass = XposedHelpers.findClass(
                    "com.android.launcher.filter.DeepProtectedAppsManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(mgrClass, "createVirtualFolder", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // 运行时动态门控: 关闭则保持系统原标题("应用隐藏")。
                        if (!readBool(KEY_HIDE_APPS_TITLE_FOLDER_ENABLED, false)) return;
                        Object folderInfo = param.getResult();
                        if (folderInfo == null) return;
                        Object ctx = XposedHelpers.getObjectField(param.thisObject, "context");
                        if (!(ctx instanceof android.content.Context)) return;
                        String name = readAppHideFolderName((android.content.Context) ctx);
                        if (name == null || name.isEmpty()) return;
                        XposedHelpers.setObjectField(folderInfo, "title", name);
                        log("launcher virtual folder title -> " + name);
                    } catch (Throwable t) {
                        log("launcher virtual folder hook error: " + t);
                    }
                }
            });
            log("HOOK OK launcher DeepProtectedAppsManager#createVirtualFolder");
        } catch (Throwable t) {
            log("HOOK FAIL launcher createVirtualFolder: " + t);
        }

        // Feature 11 — 从桌面隐藏指定的单个 LAUNCHER 活动(见 HIDDEN_LAUNCHER_TARGETS 配置表):
        // hook LauncherApps.getActivityList, 在结果中剔除已开启门控的目标组件。
        try {
            Class<?> launcherAppsClass = XposedHelpers.findClass(
                    "android.content.pm.LauncherApps", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(launcherAppsClass, "getActivityList",
                    String.class, android.os.UserHandle.class, new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                java.util.Set<android.content.ComponentName> targets =
                                        getHiddenLauncherComponents();
                                if (targets.isEmpty()) return;
                                Object result = param.getResult();
                                if (!(result instanceof java.util.List)) return;
                                java.util.List<Object> list = (java.util.List<Object>) result;
                                java.util.Iterator<Object> it = list.iterator();
                                int removed = 0;
                                while (it.hasNext()) {
                                    Object info = it.next();
                                    if (!(info instanceof android.content.pm.LauncherActivityInfo)) continue;
                                    android.content.ComponentName cn =
                                            ((android.content.pm.LauncherActivityInfo) info).getComponentName();
                                    if (targets.contains(cn)) {
                                        it.remove();
                                        removed++;
                                    }
                                }
                                if (removed > 0) dbg("[DBG] hide launcher activities removed=" + removed);
                            } catch (Throwable t) {
                                log("hide launcher activities hook error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher LauncherApps#getActivityList (hide launcher activities)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher getActivityList: " + t);
        }

        // Feature 11(互补) — 若 launcher 直接走 PackageManager.queryIntentActivities 取 LAUNCHER 列表,
        // 同样过滤目标组件。仅对标准 MAIN+LAUNCHER 查询生效, 不影响分享/解析等其它查询; 幂等。
        try {
            Class<?> pmClass = XposedHelpers.findClass(
                    "android.content.pm.PackageManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(pmClass, "queryIntentActivities",
                    android.content.Intent.class, int.class, new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                java.util.Set<android.content.ComponentName> targets =
                                        getHiddenLauncherComponents();
                                if (targets.isEmpty()) return;
                                android.content.Intent intent = (android.content.Intent) param.args[0];
                                if (intent == null) return;
                                // 仅处理标准 LAUNCHER 查询(MAIN + LAUNCHER)。
                                if (!android.content.Intent.ACTION_MAIN.equals(intent.getAction())) return;
                                if (!intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)) return;
                                Object result = param.getResult();
                                if (!(result instanceof java.util.List)) return;
                                java.util.List<Object> list = (java.util.List<Object>) result;
                                java.util.Iterator<Object> it = list.iterator();
                                int removed = 0;
                                while (it.hasNext()) {
                                    Object ri = it.next();
                                    if (ri == null) continue;
                                    Object ai = XposedHelpers.getObjectField(ri, "activityInfo");
                                    if (ai == null) continue;
                                    String pkg = (String) XposedHelpers.getObjectField(ai, "packageName");
                                    String cls = (String) XposedHelpers.getObjectField(ai, "name");
                                    if (pkg == null || cls == null) continue;
                                    if (targets.contains(new android.content.ComponentName(pkg, cls))) {
                                        it.remove();
                                        removed++;
                                    }
                                }
                                if (removed > 0) dbg("[DBG] hide launcher activities via PM removed=" + removed);
                            } catch (Throwable t) {
                                log("hide launcher activities PM hook error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher PackageManager#queryIntentActivities (hide launcher activities)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher queryIntentActivities: " + t);
        }

        // Feature 11b — 修改系统隐藏行为在桌面的呈现: 当用户在安全中心"隐藏应用"里勾选电话本时,
        // 安全中心侧已改为只把 com.android.contacts 加入隐藏列表、不整包禁用(见 hookSafecenterHideContacts),
        // 故拨号保持可用。此处仅在桌面显示层做组件级特例: 拨号(DialtactsActivityAlias)始终显示,
        // 电话本(PeopleActivityAlias)随包隐藏状态由系统判定(被隐藏则不显示)。即"只藏电话本图标、露拨号"。
        try {
            Class<?> filterClass = XposedHelpers.findClass(
                    "com.android.launcher3.OplusAppFilter", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(filterClass, "shouldShowApp",
                    android.content.ComponentName.class, android.os.UserHandle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object cnObj = param.args[0];
                                if (!(cnObj instanceof android.content.ComponentName)) return;
                                android.content.ComponentName cn = (android.content.ComponentName) cnObj;
                                // 拨号: 无论联系人是否被系统隐藏, 都强制显示(只藏电话本、露拨号)
                                if (CONTACTS_DIALER.equals(cn)) {
                                    param.setResult(true);
                                }
                            } catch (Throwable t) {
                                log("hide contacts shouldShowApp error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher OplusAppFilter#shouldShowApp (hide contacts system)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher OplusAppFilter#shouldShowApp: " + t);
        }

        // Feature 9 — 桌面双指张开(pinch-out)手势打开隐藏应用文件夹 (com.android.launcher):
        // ColorOS 自带 "双指捏合(pinch-in)" 打开隐私文件夹。此处额外监听 "双指张开(pinch-out)",
        // 在启动器桌面 DragLayer 上挂一个被动的 ScaleGestureDetector(不消费事件, 仅观测),
        // 当双指实际张开距离(当前 span - 起始 span)超过阈值时才触发, 而非按累计缩放比例,
        // 避免轻微张开即误触发; 同时要求累计放大比例 > 1.2 作为方向(张开)校验。
        // 仅当桌面处于 NORMAL(正常)状态才响应; 处于编辑等其它状态时跳过事件(交由系统处理),
        // 这样在编辑态下 pinch-out 仍按系统默认回到正常状态, 而不会打开隐藏文件夹。
        // 张开与系统捏合方向相反, 不会与系统手势冲突。
        // Feature 9 — 桌面双指张开手势: 始终注入, 运行时按 KEY_PINCH_OUT_OPEN_HIDE_APPS_ENABLED 门控。
        try {
            Class<?> dragLayerClass = XposedHelpers.findClass(
                    "com.android.launcher3.dragndrop.DragLayer", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(dragLayerClass, "dispatchTouchEvent",
                    android.view.MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 运行时动态门控: 关闭则不响应手势。
                                if (!readBool(KEY_PINCH_OUT_OPEN_HIDE_APPS_ENABLED, false)) return;
                                Object dragLayer = param.thisObject;
                                if (!(dragLayer instanceof android.view.View)) return;
                                    android.view.ScaleGestureDetector detector =
                                            (android.view.ScaleGestureDetector) XposedHelpers
                                                    .getAdditionalInstanceField(dragLayer, "colorosmod_pinch");
                                    if (detector == null) {
                                        final android.content.Context ctx =
                                                ((android.view.View) dragLayer).getContext();
                                        // 双指需实际张开的最小距离(按 dp 折算, 适配不同密度屏幕)。
                                        final float minSpreadPx =
                                                100f * ctx.getResources().getDisplayMetrics().density;
                                        final float[] accum = new float[1];
                                        final float[] beginSpan = new float[1];
                                        final boolean[] fired = new boolean[1];
                                        android.view.ScaleGestureDetector.OnScaleGestureListener listener =
                                                new android.view.ScaleGestureDetector.OnScaleGestureListener() {
                                                    @Override
                                                    public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                                                        accum[0] = 1.0f;
                                                        beginSpan[0] = d.getCurrentSpan();
                                                        fired[0] = false;
                                                        return true;
                                                    }
                                                    @Override
                                                    public boolean onScale(android.view.ScaleGestureDetector d) {
                                                        accum[0] *= d.getScaleFactor();
                                                        // 仅当明显张开(累计比例 > 1.2)且实际张开距离达标才触发。
                                                        float spread = d.getCurrentSpan() - beginSpan[0];
                                                        if (!fired[0] && accum[0] > 1.2f
                                                                && spread > minSpreadPx) {
                                                            fired[0] = true;
                                                            openHideAppsFolder(ctx);
                                                        }
                                                        return false;
                                                    }
                                                    @Override
                                                    public void onScaleEnd(android.view.ScaleGestureDetector d) {}
                                                };
                                        detector = new android.view.ScaleGestureDetector(ctx, listener);
                                        XposedHelpers.setAdditionalInstanceField(
                                                dragLayer, "colorosmod_pinch", detector);
                                    }
                                    // 仅在桌面正常(NORMAL)状态响应手势; 编辑等其它状态跳过, 交给系统处理。
                                    if (!isLauncherInNormalState(((android.view.View) dragLayer).getContext())) {
                                        return;
                                    }
                                    android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                                    if (ev != null) detector.onTouchEvent(ev);
                                } catch (Throwable t) {
                                    log("pinch-out hook error: " + t);
                                }
                            }
                        });
                log("HOOK OK launcher DragLayer#dispatchTouchEvent (pinch-out)");
            } catch (Throwable t) {
                log("HOOK FAIL launcher pinch-out: " + t);
            }

        // Feature 14 — 桌面文件夹展开背景透明化: 始终注入, 运行时按 KEY_FOLDER_BG_TRANSPARENT_ENABLED 门控。
        hookFolderOpenBgBlur(lpparam);

        // Feature 16 — 编辑模式背景遮罩透明化: 始终注入, 运行时按开关门控。
        hookEditModeBgBlur(lpparam);
    }

    /** Feature 9 — 通过 launcher 内部 API 打开隐藏应用(深度保护)文件夹。 */
    private static void openHideAppsFolder(android.content.Context ctx) {
        try {
            Class<?> mgr = XposedHelpers.findClass(
                    "com.android.launcher.filter.DeepProtectedAppsManager", ctx.getClassLoader());
            Object instance = XposedHelpers.callStaticMethod(mgr, "getInstance", ctx);
            if (instance == null) return;
            XposedHelpers.callMethod(instance, "showHideApps", ctx, false);
            log("pinch-out -> open hide apps folder");
        } catch (Throwable t) {
            log("openHideAppsFolder error: " + t);
        }
    }

    /**
     * 判断桌面是否处于 NORMAL(正常)状态。DragLayer 的 context 即 Launcher 实例,
     * 通过 {@code Launcher#isInState(LauncherState.NORMAL)} 判定。
     * 反射结果做缓存; 任何异常(无法确定状态)均保守返回 false, 即不响应手势。
     */
    private static boolean isLauncherInNormalState(android.content.Context ctx) {
        try {
            if (sLauncherClass == null) {
                sLauncherClass = XposedHelpers.findClass(
                        "com.android.launcher3.Launcher", ctx.getClassLoader());
            }
            if (sLauncherStateClass == null) {
                sLauncherStateClass = XposedHelpers.findClass(
                        "com.android.launcher3.LauncherState", ctx.getClassLoader());
            }
            if (sNormalState == null) {
                sNormalState = XposedHelpers.getStaticObjectField(sLauncherStateClass, "NORMAL");
            }
            if (!sLauncherClass.isInstance(ctx)) return false;
            return (Boolean) XposedHelpers.callMethod(ctx, "isInState", sNormalState);
        } catch (Throwable t) {
            return false;
        }
    }

    // Feature 14 — 桌面文件夹展开背景透明化 (com.android.launcher):
    // 文件夹展开时的"全屏灰色背景"实为启动器对壁纸施加的高斯模糊+暗色调和
    // (OplusDepthController: getFolderBlur()/getFolderDepth() 恒为 1.0f, 打开文件夹时把壁纸 blur 拉到 1.0,
    // 并叠加 mBlurBlendColor 暗色, 看起来像一层灰)。
    // 所有壁纸模糊最终都汇入私有方法 OplusDepthController.setBlur(float, boolean)
    // (BLUR/MIRROR_BLUR 属性、setBlurWithoutAnim、各 blur 动画均走它), 这是唯一收口点。
    // hook 该方法: 当有文件夹处于打开(含打开/关闭动画)状态时把模糊值强制为 0,
    // 整屏不再变灰, 文件夹直接浮在清晰壁纸上; 多任务/应用抽屉等其它场景的模糊不受影响
    // (它们没有打开的文件夹)。运行时按 KEY_FOLDER_BG_TRANSPARENT_ENABLED 门控。
    private static void hookFolderOpenBgBlur(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> depthClass = XposedHelpers.findClass(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(depthClass, "setBlur", float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FOLDER_BG_TRANSPARENT_ENABLED, false)) return;
                                Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                                if (launcher == null) return;
                                if (!isLauncherFolderOpen(launcher, lpparam.classLoader)) return;
                                param.args[0] = 0f;
                            } catch (Throwable t) {
                                log("folder bg blur hook error: " + t);
                            }
                        }
                    });
            log("HOOK OK launcher OplusDepthController#setBlur (transparent folder bg)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher OplusDepthController#setBlur: " + t);
        }
    }

    private static Class<?> sAbstractFloatingViewClass;
    private static boolean isLauncherFolderOpen(Object launcher, ClassLoader cl) {
        try {
            if (sAbstractFloatingViewClass == null) {
                sAbstractFloatingViewClass = XposedHelpers.findClass(
                        "com.android.launcher3.AbstractFloatingView", cl);
            }
            // AbstractFloatingView.getOpenFolder(ActivityContext): 当前打开的文件夹(含打开/关闭动画期间)。
            return XposedHelpers.callStaticMethod(sAbstractFloatingViewClass, "getOpenFolder", launcher) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Feature 16 — 取消桌面编辑模式的背景遮罩。
     * 系统桌面的 ToggleBarState 与 PagePreviewState 会把编辑态壁纸 blur 固定为 1.0f，
     * 并在部分设备上给桌面根视图/页面背景附加 alpha。只改最终 setBlur 会错过状态切换动画，
     * 因此直接在状态提供目标值的三个方法上返回 0，进入和退出编辑态都保持幂等。
     */
    private static void hookEditModeBgBlur(final XC_LoadPackage.LoadPackageParam lpparam) {
        String[] stateClasses = {
                "com.android.launcher3.states.ToggleBarState",
                "com.android.launcher3.states.PagePreviewState"
        };
        Class<?> launcherClass = XposedHelpers.findClass(
                "com.android.launcher3.Launcher", lpparam.classLoader);
        for (String stateClass : stateClasses) {
            hookEditModeFloatMethod(stateClass, lpparam, "getBlurUnchecked",
                    android.content.Context.class);
            hookEditModeIntMethod(stateClass, lpparam, "getLauncherRootViewBgAlpha",
                    android.content.Context.class);
            hookEditModeIntMethod(stateClass, lpparam, "getCellLayoutBgAlpha", launcherClass);
        }
        hookEditModeDepthBlur(lpparam);
    }

    private static void hookEditModeDepthBlur(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> depthClass = XposedHelpers.findClass(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", lpparam.classLoader);
            XC_MethodHook forceZero = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!readBool(KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED, false)) return;
                    try {
                        Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                        if (isLauncherEditMode(launcher)) param.args[0] = 0f;
                    } catch (Throwable t) {
                        log("edit mode depth blur hook error: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(depthClass, "setBlur", float.class, forceZero);
            XposedHelpers.findAndHookMethod(depthClass, "setBlur", float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED, false)) return;
                            try {
                                Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                                if (isLauncherEditMode(launcher)) param.args[0] = 0f;
                            } catch (Throwable t) {
                                log("edit mode depth blur hook error: " + t);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(depthClass, "setBlurWithoutAnim", float.class, forceZero);
            log("HOOK OK launcher OplusDepthController blur paths (edit bg transparent)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher OplusDepthController blur paths (edit bg transparent): " + t);
        }
    }

    private static boolean isLauncherEditMode(Object launcher) {
        if (launcher == null) return false;
        try {
            Object stateManager = XposedHelpers.callMethod(launcher, "getStateManager");
            Object state = XposedHelpers.callMethod(stateManager, "getState");
            if (state == null) return false;
            String name = state.getClass().getName();
            return name.endsWith("ToggleBarState") || name.endsWith("PagePreviewState");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void hookEditModeFloatMethod(String className,
                                                 XC_LoadPackage.LoadPackageParam lpparam,
                                                 String methodName, Class<?> argType) {
        try {
            XposedHelpers.findAndHookMethod(className, lpparam.classLoader, methodName, argType,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED, false)) {
                                param.setResult(0f);
                            }
                        }
                    });
            log("HOOK OK launcher " + className + "#" + methodName + " (edit bg transparent)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher " + className + "#" + methodName + ": " + t);
        }
    }

    private static void hookEditModeIntMethod(String className,
                                               XC_LoadPackage.LoadPackageParam lpparam,
                                               String methodName, Class<?> argType) {
        try {
            XposedHelpers.findAndHookMethod(className, lpparam.classLoader, methodName, argType,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED, false)) {
                                param.setResult(0);
                            }
                        }
                    });
            log("HOOK OK launcher " + className + "#" + methodName + " (edit bg transparent)");
        } catch (Throwable t) {
            log("HOOK FAIL launcher " + className + "#" + methodName + ": " + t);
        }
    }

    /**
     * Feature 7 — 隐藏应用免验证打开 (安全中心 / com.oplus.safecenter):
     * 点击桌面隐藏应用图标 / 拨号盘输入隐藏号码后, 系统会启动
     * {@code com.oplus.safecenter.privacy.view.space.AppHideLauncherActivity}
     * (extends AppHideNewCheckActivity) 作为校验闸门。其 onCreate 调用私有方法 d0()
     * (checkPrivacyPwd): 若实例字段 I (noNeedCheckPrivacyPwd) 为 true, 则直接走 e0() 的
     * "已验证" 分支打开隐藏应用界面, 跳过密码/指纹校验。
     * 此处在 d0() 执行前把字段 I 置 true, 即无需密码或指纹即可打开隐藏应用界面。
     * (方法名 d0 与字段名 I 均为该版本 SafeCenter.apk 内真实混淆名, 已用 dexdump 核对。)
     */
    private static void hookSafecenter(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched com.oplus.safecenter");
        // 两个 feature 始终注入, 运行时门控见各自 hook 内部。
        try {
            hookSafecenterNoverify(lpparam);
            hookSafecenterTitleFolder(lpparam);
            hookSafecenterHideContacts(lpparam);
        } catch (Throwable t) {
            log("hookSafecenter failed: " + t);
        }
    }

    /** Feature 7 实现: 在 AppHideNewCheckActivity#d0() 前把字段 I(noNeedCheckPrivacyPwd) 置 true。 */
    private static void hookSafecenterNoverify(final XC_LoadPackage.LoadPackageParam lpparam) {
        log("hide_apps_noverify enabled=" + readBool(KEY_HIDE_APPS_NOVERIFY_ENABLED, false));
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.safecenter.privacy.view.space.AppHideNewCheckActivity",
                    lpparam.classLoader, "d0",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 运行时动态门控: 关闭则不跳过密码校验。
                                if (!readBool(KEY_HIDE_APPS_NOVERIFY_ENABLED, false)) return;
                                Object obj = param.thisObject;
                                // noNeedCheckPrivacyPwd = true -> 跳过密码/指纹, 直接进入已验证流程
                                Class<?> c = obj.getClass();
                                while (c != null && c != Object.class) {
                                    try {
                                        java.lang.reflect.Field f = c.getDeclaredField("I");
                                        f.setAccessible(true);
                                        f.setBoolean(obj, true);
                                        break;
                                    } catch (NoSuchFieldException ignored) {
                                        c = c.getSuperclass();
                                    }
                                }
                                log("safecenter hide-apps: forced noNeedCheckPrivacyPwd=true");
                            } catch (Throwable t) {
                                log("safecenter hide-apps hook error: " + t);
                            }
                        }
                    });
            log("safecenter hide-apps hook installed");
        } catch (Throwable t) {
            log("hookSafecenterNoverify failed: " + t);
        }
    }

    /**
     * Feature 8 — 应用隐藏界面标题改为隐藏文件夹的实际名称 (安全中心 / com.oplus.safecenter):
     * 隐藏应用入口(AppHideLauncherActivity)在桌面显示的名称由用户自定义, 存于 launcher 的
     *   content://com.android.launcher.OplusFavoritesProvider/desktopappedit
     * 表, 列 title, 行键 componentName="com.oplus.safecenter_<AppHideLauncherActivity 类名>_<userId>"。
     * 系统自身在 {@code AppProtectListActivity#l0(boolean)} 中 setTitle(R.string.privacy_app_hide_name)
     * (即"应用隐藏")。此处 hook l0 的 after: 仅当参数为 false(表示"应用隐藏"界面, 非应用锁)时,
     * 读取上述自定义文件夹名并以 setTitle 覆盖, 让用户自定义名称成为界面标题;
     * 读取失败或为空则保持原标题("应用隐藏")。
     * (该 provider 查询逻辑与安全中心内部 {@code com.oplus.safecenter.privacy.utils.k#b} 一致。)
     */
    private static void hookSafecenterTitleFolder(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // hook setTitle(CharSequence) 而非 l0(): 覆盖 Activity 自身与内部 fragment 的任意 setTitle 调用,
            // 确保标题稳定为自定义文件夹名。l0() 通过 setTitle(int) -> 内部 setTitle(CharSequence), 同样被拦截。
            // setTitle 为 Activity 继承方法, findAndHookMethod(exact) 找不到, 故用 getMethod + XposedBridge.hookMethod。
            Class<?> actClass = XposedHelpers.findClass(
                    "com.oplus.safecenter.privacy.view.AppProtectListActivity", lpparam.classLoader);
            java.lang.reflect.Method m = actClass.getMethod("setTitle", CharSequence.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 运行时动态门控: 关闭则保持系统原标题("应用隐藏")。
                        if (!readBool(KEY_HIDE_APPS_TITLE_FOLDER_ENABLED, false)) return;
                        Object obj = param.thisObject;
                        if (!(obj instanceof android.app.Activity)) return;
                        android.app.Activity act = (android.app.Activity) obj;
                        // 仅"应用隐藏"界面(type != 1)替换; 应用锁界面(type == 1)保持原"应用锁"标题
                        int type = 0;
                        try {
                            java.lang.reflect.Field f = obj.getClass().getDeclaredField("P");
                            f.setAccessible(true);
                            type = f.getInt(obj);
                        } catch (Throwable ignored) {}
                        if (type == 1) return;
                        String name = readAppHideFolderName(act);
                        if (name == null || name.isEmpty()) return;
                        param.args[0] = name;
                        log("safecenter title -> folder name: " + name);
                    } catch (Throwable t) {
                        log("safecenter title hook error: " + t);
                    }
                }
            });
            log("HOOK OK com.oplus.safecenter.privacy.view.AppProtectListActivity#setTitle (title folder)");
        } catch (Throwable t) {
            log("HOOK FAIL AppProtectListActivity#setTitle :: " + Log.getStackTraceString(t));
        }
    }

    // Feature 11b — 安全中心特殊处理: 修改"隐藏应用"对电话本的处理逻辑。
    // 1) hook PMSHideAppListUtil#t(ctx, pkg): 对 com.android.contacts 返回 true,
    //    使 s3.c.D 将其归入 map4 分支 —— 只写隐藏应用列表(setAccessControlAppsInfo)、
    //    并 resetPmsHideApp(清除整包 PMS 禁用), 从而联系人进入隐藏应用(需验证打开文件夹)但不被禁用, 拨号保持可用。
    // 2) hook OplusPmsHiddeManager#isApplicationOplusHiddenAsUser(ctx, pkg, userId): 对 com.android.contacts
    //    返回 true, 使安全中心 UI 回读(如 AppProtectManager.i0/g)将其显示为已隐藏。
    private static void hookSafecenterHideContacts(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> utilClass = XposedHelpers.findClass(
                    "com.oplus.safecenter.privacy.utils.PMSHideAppListUtil", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(utilClass, "t",
                    android.content.Context.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String pkg = (String) param.args[1];
                                if ("com.android.contacts".equals(pkg)) {
                                    param.setResult(true);
                                }
                            } catch (Throwable t) {
                                log("hide contacts safecenter t error: " + t);
                            }
                        }
                    });
            log("HOOK OK safecenter PMSHideAppListUtil#t (hide contacts system)");
        } catch (Throwable t) {
            log("HOOK FAIL safecenter PMSHideAppListUtil#t: " + t);
        }
        try {
            Class<?> pmhClass = XposedHelpers.findClass(
                    "com.oplus.safecenter.privacy.sdk.OplusPmsHiddeManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(pmhClass, "isApplicationOplusHiddenAsUser",
                    android.content.Context.class, String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String pkg = (String) param.args[1];
                                if ("com.android.contacts".equals(pkg)) {
                                    param.setResult(true);
                                }
                            } catch (Throwable t) {
                                log("hide contacts safecenter isAppHidden error: " + t);
                            }
                        }
                    });
            log("HOOK OK safecenter OplusPmsHiddeManager#isApplicationOplusHiddenAsUser (hide contacts system)");
        } catch (Throwable t) {
            log("HOOK FAIL safecenter OplusPmsHiddeManager#isApplicationOplusHiddenAsUser: " + t);
        }
    }

    /**
     * 读取桌面隐藏应用入口文件夹的自定义名称。与安全中心内部
     * {@code com.oplus.safecenter.privacy.utils.k#b} 逻辑一致:
     *   Uri      = content://com.android.launcher.OplusFavoritesProvider/desktopappedit
     *   column   = title
     *   selection= componentName=? , arg = "com.oplus.safecenter_<AppHideLauncherActivity 类名>_<userId>"
     * 查询不到或为空时返回 null(调用方保持原标题"应用隐藏")。
     */
    private static String readAppHideFolderName(android.content.Context context) {
        try {
            android.net.Uri uri = android.net.Uri.parse(
                    "content://com.android.launcher.OplusFavoritesProvider/desktopappedit");
            int userId = 0;
            try {
                java.lang.reflect.Method m = android.os.UserHandle.class.getDeclaredMethod("myUserId");
                m.setAccessible(true);
                userId = (Integer) m.invoke(null);
            } catch (Throwable ignored) {}
            String comp = "com.oplus.safecenter_com.oplus.safecenter.privacy.view.space.AppHideLauncherActivity_" + userId;
            android.database.Cursor c = context.getContentResolver().query(
                    uri, new String[]{"title"}, "componentName=?", new String[]{comp}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String t = c.getString(c.getColumnIndex("title"));
                        if (t != null && !t.isEmpty()) return t;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            log("readAppHideFolderName fail: " + t);
        }
        return null;
    }

    /**
     * 设置首页图标样式：复用 Settings/Oplus 已有的 COUIRoundImageView 绘制路径。
     * Oplus 首页来自 top_level_settings_oplus.xml，不经过 DashboardFeatureProviderImpl；
     * COUIPreference 的 couiIconStyle=0 是圆形，=1 是不规则圆角图标。
     */
    private static void hookSettingsHomeIconStyle(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> oplusTopLevelClass = XposedHelpers.findClass(
                    "com.oplus.settings.feature.homepage.OplusTopLevelSettings",
                    lpparam.classLoader);
            final Class<?> preferenceScreenClass = XposedHelpers.findClass(
                    "androidx.preference.PreferenceScreen", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.android.settings.dashboard.DashboardFragment",
                    lpparam.classLoader,
                    "displayResourceTilesToScreen",
                    preferenceScreenClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!oplusTopLevelClass.isInstance(param.thisObject)) return;
                            int style = readInt(KEY_SETTINGS_HOME_ICON_STYLE,
                                    SETTINGS_HOME_ICON_STYLE_DEFAULT);
                            if (style == SETTINGS_HOME_ICON_STYLE_DEFAULT) return;
                            int couiStyle = style == SETTINGS_HOME_ICON_STYLE_CIRCLE ? 0 : 1;
                            applySettingsHomeIconStyle(param.args[0], couiStyle,
                                    lpparam.classLoader);
                        }
                    });
            log("HOOK OK settings native COUI homepage icon style");
        } catch (Throwable t) {
            log("HOOK FAIL settings native COUI homepage icon style: " + t);
        }
    }

    private static void applySettingsHomeIconStyle(Object preferenceGroup,
            int couiStyle, ClassLoader classLoader) {
        if (preferenceGroup == null) return;
        try {
            Class<?> couiPreferenceClass = XposedHelpers.findClass(
                    "com.coui.appcompat.preference.COUIPreference", classLoader);
            if (couiPreferenceClass.isInstance(preferenceGroup)) {
                Object icon = XposedHelpers.callMethod(preferenceGroup, "getIcon");
                if (icon != null) {
                    int current = (Integer) XposedHelpers.callMethod(
                            preferenceGroup, "getIconStyle");
                    if (current != couiStyle) {
                        XposedHelpers.callMethod(preferenceGroup, "setIconStyle", couiStyle);
                    }
                }
            }
            Class<?> preferenceGroupClass = XposedHelpers.findClass(
                    "androidx.preference.PreferenceGroup", classLoader);
            if (!preferenceGroupClass.isInstance(preferenceGroup)) return;
            int count = (Integer) XposedHelpers.callMethod(
                    preferenceGroup, "getPreferenceCount");
            for (int i = 0; i < count; i++) {
                Object child = XposedHelpers.callMethod(
                        preferenceGroup, "getPreference", i);
                applySettingsHomeIconStyle(child, couiStyle, classLoader);
            }
        } catch (Throwable t) {
            log("settings homepage COUI icon style error: " + t);
        }
    }

    /**
     * Feature 7 (fallback / 更直接的入口) — 在设置(密码/指纹校验)界面直接以"已验证"返回:
     * 点击桌面隐藏应用图标 / 拨号盘输入隐藏号码后, 安全中心会让本进程(com.android.settings)启动
     * {@code com.oplus.settings.privacy.ConfirmNumberPrivacy}(或其指纹变体 ConfirmBiometricInfo) 作为校验闸门。
     * 该界面校验成功时本就会 setResult(-1) 并把结果交回安全中心的 AppHideNewCheckActivity.onActivityResult,
     * 后者直接调用 e0() 打开隐藏应用界面(并不读取校验 challenge)。因此这里在 onCreate 后立刻
     * setResult(-1)+finish(), 即可在不输入密码/指纹的情况下打开隐藏应用。
     * 此 hook 只需把本模块作用域加入 com.android.settings 即可生效, 不依赖 com.oplus.safecenter 作用域。
     */
    private static void hookSettings(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched com.android.settings");
        hookSettingsHomeIconStyle(lpparam);
        // 始终注入, 运行时按 KEY_HIDE_APPS_NOVERIFY_ENABLED 门控(见 afterHook)。
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.settings.privacy.ConfirmAbstractPrivacy",
                    lpparam.classLoader, "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 运行时动态门控: 关闭则不绕过校验。
                                if (!readBool(KEY_HIDE_APPS_NOVERIFY_ENABLED, false)) return;
                                Object obj = param.thisObject;
                                String name = obj.getClass().getName();
                                // 仅对隐藏应用相关的校验界面生效, 避免误伤其它隐私确认流程
                                if (!"com.oplus.settings.privacy.ConfirmNumberPrivacy".equals(name)
                                        && !"com.oplus.settings.privacy.ConfirmBiometricInfo".equals(name)) {
                                    return;
                                }
                                if (obj instanceof android.app.Activity) {
                                    android.app.Activity act = (android.app.Activity) obj;
                                    act.setResult(-1);
                                    act.finish();
                                    log("settings confirm-privacy bypassed: " + name);
                                }
                            } catch (Throwable t) {
                                log("settings hide-apps hook error: " + t);
                            }
                        }
                    });
            log("settings hide-apps hook installed");
        } catch (Throwable t) {
            log("hookSettings failed: " + t);
        }
    }

    private static void hookSystemUi(final XC_LoadPackage.LoadPackageParam lpparam) {
        log(">>> matched systemui, classLoader=" + lpparam.classLoader);
        float density = readDensity();

        // Feature 5 — 隐藏运营商名: 始终注入, 运行时按 KEY_QS_CARRIER_ENABLED 门控(见内部)。
        hookQsHideCarrier(lpparam);

        // Feature 6 — 控制中心顶栏间距: 始终注入, 运行时按 KEY_QS_TOPMARGIN_ENABLED 门控。
        {
            final int footerPx = Math.round(QS_FOOTER_MARGIN_DP * density);
            log("qs_topmargin footerPx(fixed " + QS_FOOTER_MARGIN_DP + "dp)=" + footerPx);
            hookQsTopMargin(lpparam, footerPx);
        }

        // Feature 3 — 通知分组副标题: 始终注入, 运行时按 KEY_NOTIFICATION_SUBTITLE_ENABLED 门控,
        // 字号缩减量由 KEY_NOTIFICATION_SUBTITLE_SP(0-16sp, 默认 8sp) 运行时读取, 偏移/内边距随其等比缩放。
        hookNotificationSubtitle(lpparam, density);

        // Feature 3b — 通知内边距: 始终注入, 运行时按 KEY_NOTIFICATION_PADDING_ENABLED 门控,
        // 内边距由 KEY_NOTIFICATION_PADDING_DP(0-8dp, 默认 4dp) 运行时读取。
        hookNotificationPadding(lpparam, density);

        // Feature 10 — 合并控制中心背景压暗(半透明黑): 始终注入, 运行时按 KEY_QS_SCRIM_TRANSLUCENT_ENABLED 门控。
        hookQsBackgroundDim(lpparam);
        // Feature 13 — 控制中心 WLAN/蓝牙 名称单行省略: 始终注入, 运行时按 KEY_QS_TILE_NAME_ELLIPSIS_ENABLED 门控。
        hookQsTileNameEllipsis(lpparam);
        // Feature 17 — 流体云出现时不隐藏电量百分比: 始终注入, 运行时按 KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED 门控。
        hookFluidCloudKeepPercent(lpparam);
        // Feature 18 — 悬浮小窗贴边挂机: 真正的提交逻辑在 system_server(android 作用域), 见 hookFloatWindowEdgeHangSystemServer。
        hookGestureBarHeight(lpparam);
        hookGestureBarLongPressDisable(lpparam);
        hookMBack(lpparam);
    }

    private static final java.util.WeakHashMap<Object, Boolean> sBarExtraApplied =
            new java.util.WeakHashMap<>();

    private static void hookGestureBarLongPressDisable(
            final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> controllerClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.animator.OplusHandleAnimatorController",
                    lpparam.classLoader);
            Class<?> animTypeClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.animator.OplusHandleAnimType",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(controllerClass, "doHandleAnimator",
                    animTypeClass, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED, true)
                                    && "StartLongPressAnim".equals(String.valueOf(param.args[0]))) {
                                param.setResult(null);
                            }
                        }
                    });

            Class<?> oldAnimTypeClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.animator.OplusHandleOldAnimType",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(controllerClass, "doHandleOldAnimator",
                    oldAnimTypeClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED, true)
                                    && "StartOldLongPressAnim".equals(String.valueOf(param.args[0]))) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("gesture_bar long press hook failed: " + t);
        }
    }

    private static final long MBACK_RIPPLE_HIDE_DELAY_MS = 280L;

    private static void hookMBack(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(handleClass, "handleValidTouchEvent",
                    android.view.MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_MBACK_ENABLED, false)) return;
                            android.view.View handle = (android.view.View) param.thisObject;
                            handleMBackTouch(handle, (android.view.MotionEvent) param.args[0]);
                            param.setResult(null);
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (readBool(KEY_MBACK_ENABLED, false)) {
                                ensureMBackSurface((android.view.View) param.thisObject);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onLayout", boolean.class,
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            updateMBackSurface((android.view.View) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            removeMBackSurface((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("mback hook failed: " + t);
        }
    }

    private static void handleMBackTouch(final android.view.View handle,
            android.view.MotionEvent event) {
        if (event == null) return;
        MBackSurface surface = ensureMBackSurface(handle);
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            cancelMBackLongPress(handle);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down", Boolean.TRUE);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_long", Boolean.FALSE);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_cancelled", Boolean.FALSE);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down_x", event.getX());
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down_y", event.getY());
            if (surface != null) {
                surface.update();
                surface.showAnimated();
            }
            final Runnable longPress = new Runnable() {
                @Override
                public void run() {
                    if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                            handle, "mback_down"))
                            || Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                            handle, "mback_cancelled"))) {
                        return;
                    }
                    XposedHelpers.setAdditionalInstanceField(handle, "mback_long", Boolean.TRUE);
                    android.view.View feedback = handle;
                    feedback.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    triggerNavigation(handle, true);
                }
            };
            XposedHelpers.setAdditionalInstanceField(handle, "mback_runnable", longPress);
            handle.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
            return;
        }
        if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(handle, "mback_down"))) {
            return;
        }
        if (action == android.view.MotionEvent.ACTION_MOVE) {
            // 从白条向左/右/上划动时放弃接管（不震动、不触发导航），交由系统手势处理。
            float dx = event.getX() - getMBackFloat(handle, "mback_down_x");
            float dy = event.getY() - getMBackFloat(handle, "mback_down_y");
            float swipe = Math.round(MBACK_SWIPE_DP * readDensity());
            if (Math.abs(dx) > swipe || dy < -swipe) {
                XposedHelpers.setAdditionalInstanceField(handle, "mback_cancelled", Boolean.TRUE);
                cancelMBackLongPress(handle);
                hideMBackSurface(handle);
            }
            return;
        }
        if (action == android.view.MotionEvent.ACTION_UP) {
            boolean cancelled = Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                    handle, "mback_cancelled"));
            boolean longPressed = Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                    handle, "mback_long"));
            cancelMBackLongPress(handle);
            if (!cancelled && !longPressed) {
                handle.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                triggerNavigation(handle, false);
            }
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down", Boolean.FALSE);
            hideMBackSurface(handle);
            return;
        }
        if (action == android.view.MotionEvent.ACTION_CANCEL
                || action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            XposedHelpers.setAdditionalInstanceField(handle, "mback_cancelled", Boolean.TRUE);
            cancelMBackLongPress(handle);
            XposedHelpers.setAdditionalInstanceField(handle, "mback_down", Boolean.FALSE);
            hideMBackSurface(handle);
        }
    }

    private static float getMBackFloat(android.view.View view, String key) {
        Object value = XposedHelpers.getAdditionalInstanceField(view, key);
        return value instanceof Number ? ((Number) value).floatValue() : 0.0f;
    }

    private static void cancelMBackLongPress(android.view.View handle) {
        Object runnable = XposedHelpers.getAdditionalInstanceField(handle, "mback_runnable");
        if (runnable instanceof Runnable) handle.removeCallbacks((Runnable) runnable);
        XposedHelpers.setAdditionalInstanceField(handle, "mback_runnable", null);
    }

    private static MBackSurface ensureMBackSurface(android.view.View handle) {
        Object existing = XposedHelpers.getAdditionalInstanceField(handle, "mback_surface");
        if (existing instanceof MBackSurface) {
            ((MBackSurface) existing).update();
            return (MBackSurface) existing;
        }
        android.widget.FrameLayout host = findMBackHost(handle);
        if (host == null) return null;
        MBackSurface surface = new MBackSurface(handle, host);
        surface.setVisibility(android.view.View.INVISIBLE);
        surface.setClickable(true);
        surface.setFocusable(true);
        surface.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View view, android.view.MotionEvent event) {
                return true;
            }
        });
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(1, 1);
        host.addView(surface, 0, lp);
        XposedHelpers.setAdditionalInstanceField(handle, "mback_surface", surface);
        surface.update();
        return surface;
    }

    private static android.widget.FrameLayout findMBackHost(android.view.View view) {
        android.view.ViewParent parent = view.getParent();
        android.widget.FrameLayout fallback = null;
        while (parent instanceof android.view.View) {
            if (parent instanceof android.widget.FrameLayout) {
                if (fallback == null) fallback = (android.widget.FrameLayout) parent;
                if (parent.getClass().getName().contains("NavigationBarFrame")) {
                    return (android.widget.FrameLayout) parent;
                }
            }
            parent = parent.getParent();
        }
        return fallback;
    }

    private static void updateMBackSurface(android.view.View handle) {
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "mback_surface");
        if (surface instanceof MBackSurface) ((MBackSurface) surface).update();
    }

    private static void hideMBackSurface(android.view.View handle) {
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "mback_surface");
        if (surface instanceof MBackSurface) ((MBackSurface) surface).hideAnimated();
    }

    private static void removeMBackSurface(android.view.View handle) {
        cancelMBackLongPress(handle);
        Object surface = XposedHelpers.getAdditionalInstanceField(handle, "mback_surface");
        if (surface instanceof MBackSurface && ((MBackSurface) surface).getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) ((MBackSurface) surface).getParent()).removeView((MBackSurface) surface);
        }
        XposedHelpers.setAdditionalInstanceField(handle, "mback_surface", null);
    }

    private static void triggerNavigation(android.view.View handle, boolean home) {
        try {
            android.view.View parent = handle;
            Class<?> navViewClass = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBarView",
                    handle.getContext().getClassLoader());
            while (parent != null && !navViewClass.isInstance(parent)) {
                parent = parent.getParent() instanceof android.view.View
                        ? (android.view.View) parent.getParent() : null;
            }
            if (parent == null) return;
            if (home) {
                if (injectHomeKey()) return;
                Object homeDispatcher = XposedHelpers.callMethod(parent, "getHomeButton");
                Object homeView = XposedHelpers.callMethod(homeDispatcher, "getCurrentView");
                if (homeView != null) XposedHelpers.callMethod(homeView, "performClick");
                return;
            }
            Object dispatcher = XposedHelpers.callMethod(parent, "getBackButton");
            Object keyView = XposedHelpers.callMethod(dispatcher, "getCurrentView");
            if (keyView == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            XposedHelpers.callMethod(keyView, "sendEvent", 0, 0, now);
            XposedHelpers.callMethod(keyView, "sendEvent", 1, 0);
        } catch (Throwable t) {
            log("mback navigation failed: " + t);
        }
    }

    private static boolean injectHomeKey() {
        try {
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Object inputManager = XposedHelpers.callStaticMethod(inputManagerClass, "getInstance");
            long now = android.os.SystemClock.uptimeMillis();
            int flags = android.view.KeyEvent.FLAG_FROM_SYSTEM
                    | android.view.KeyEvent.FLAG_VIRTUAL_HARD_KEY;
            android.view.KeyEvent down = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_HOME, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            android.view.KeyEvent up = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_HOME, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            XposedHelpers.callMethod(inputManager, "injectInputEvent", down, 0);
            XposedHelpers.callMethod(inputManager, "injectInputEvent", up, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class MBackSurface extends android.view.View {
        private final android.view.View source;
        private final android.view.ViewGroup host;

        MBackSurface(android.view.View source, android.view.ViewGroup host) {
            super(source.getContext());
            this.source = source;
            this.host = host;
            android.graphics.drawable.GradientDrawable mask =
                    new android.graphics.drawable.GradientDrawable();
            mask.setColor(android.graphics.Color.WHITE);
            mask.setCornerRadius(1000.0f);
            android.content.res.ColorStateList rippleColor =
                    android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.argb(64, 255, 255, 255));
            setBackground(new android.graphics.drawable.RippleDrawable(
                    rippleColor, null, mask));
            setAlpha(1.0f);
            setWillNotDraw(true);
        }

        void showAnimated() {
            removeCallbacks(hideRunnable);
            setAlpha(1.0f);
            setVisibility(android.view.View.VISIBLE);
            setPressed(true);
            if (getBackground() != null) {
                getBackground().setHotspot(getWidth() / 2.0f, getHeight() / 2.0f);
            }
        }

        void hideAnimated() {
            setPressed(false);
            removeCallbacks(hideRunnable);
            postDelayed(hideRunnable, MBACK_RIPPLE_HIDE_DELAY_MS);
        }

        private final Runnable hideRunnable = new Runnable() {
            @Override
            public void run() {
                setVisibility(android.view.View.INVISIBLE);
            }
        };

        // MBackSurface 基于手势白条尺寸，上下各固定留白 8dp，高度 = 白条高度 + 2*留白。
        private static final float MBACK_PADDING_DP = 8f;
        private static final float MBACK_MARGIN_BOTTOM_DP = -4f;

        void update() {
            if (getParent() != host) return;
            int[] sourceLocation = new int[2];
            int[] hostLocation = new int[2];
            source.getLocationInWindow(sourceLocation);
            host.getLocationInWindow(hostLocation);
            int barHeight = getSourceInt("mHeight", source.getHeight());
            float density = readDensity();
            int padding = Math.round(MBACK_PADDING_DP * density);
            int gestureZoneHeight = barHeight + padding * 2;
            int width = source.getWidth() + padding * 2;
            int height = gestureZoneHeight;
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) getLayoutParams();
            if (lp.width != width || lp.height != height) {
                lp.width = width;
                lp.height = height;
                setLayoutParams(lp);
            }
            // 从下方（贴手势区底）布局，底部避开手势区加高的一半（extraPx/2）。
            int extraPx = 0;
            if (readInt(KEY_GESTURE_BAR_HEIGHT_ENABLED, 1) == 1) {
                int extraDp = Math.max(0, Math.min(20, readInt(KEY_GESTURE_BAR_HEIGHT_DP, 10)));
                extraPx = Math.round(extraDp * density);
            }
            int barCenterXInHost = (sourceLocation[0] - hostLocation[0]) + source.getWidth() / 2;
            setX(barCenterXInHost - width / 2);
            setY(Math.max(0, host.getHeight() - gestureZoneHeight - Math.max(0, extraPx / 2 + Math.round(MBACK_MARGIN_BOTTOM_DP * density))));
            invalidate();
        }

        private int getSourceInt(String field, int fallback) {
            try {
                return XposedHelpers.getIntField(source, field);
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    private static void hookGestureBarHeight(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> navBarClass = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.views.NavigationBar", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(navBarClass, "getBarLayoutParams", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (readInt(KEY_GESTURE_BAR_HEIGHT_ENABLED, 1) != 1) return;
                                int dp = Math.max(0, Math.min(20,
                                        readInt(KEY_GESTURE_BAR_HEIGHT_DP, 10)));
                                int extraPx = Math.round(dp * readDensity());
                                android.view.WindowManager.LayoutParams lp =
                                        (android.view.WindowManager.LayoutParams) param.getResult();
                                if (lp == null || extraPx <= 0) return;
                                applyBarExtra(lp, extraPx);
                            } catch (Throwable t) {
                                log("gesture_bar layout hook err: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("gesture_bar hook (layout) failed: " + t);
        }
        try {
            Class<?> handleClass = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(handleClass, "onDraw", android.graphics.Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int dp = 10;
                                if (dp <= 0) return;
                                int half = Math.round(dp * readDensity() * 0.5f);
                                android.graphics.Canvas c = (android.graphics.Canvas) param.args[0];
                                c.save();
                                c.translate(0, -half);
                                param.setObjectExtra("gb_handle", Boolean.TRUE);
                            } catch (Throwable t) {
                                log("gesture_bar handle before err: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (Boolean.TRUE.equals(param.getObjectExtra("gb_handle"))) {
                                    ((android.graphics.Canvas) param.args[0]).restore();
                                }
                            } catch (Throwable t) {
                                log("gesture_bar handle after err: " + t);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "setVertical", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyGestureBarWidth((android.view.View) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(handleClass, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyGestureBarWidth((android.view.View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            log("gesture_bar hook (handle) failed: " + t);
        }
    }

    private static void applyGestureBarWidth(android.view.View view) {
        try {
            if (readInt(KEY_GESTURE_BAR_WIDTH_ENABLED, 1) != 1) return;
            int dp = Math.max(80, Math.min(120,
                    readInt(KEY_GESTURE_BAR_WIDTH_DP, 100)));
            int width = Math.round(dp * readDensity());
            android.view.ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (!(raw instanceof android.widget.LinearLayout.LayoutParams)) return;
            android.widget.LinearLayout.LayoutParams lp =
                    (android.widget.LinearLayout.LayoutParams) raw;
            if (lp.width == width && lp.gravity == android.view.Gravity.CENTER) return;
            lp.width = width;
            lp.gravity = android.view.Gravity.CENTER;
            view.setLayoutParams(lp);
        } catch (Throwable t) {
            log("gesture_bar width hook err: " + t);
        }
    }

    private static void applyBarExtra(android.view.WindowManager.LayoutParams lp, int extraPx) {
        lp.height += extraPx;
        bumpInsetsIfPresent(lp, extraPx);
        try {
            java.lang.reflect.Field f = lp.getClass().getField("paramsForRotation");
            Object arr = f.get(lp);
            if (arr instanceof Object[]) {
                for (Object o : (Object[]) arr) {
                    if (o instanceof android.view.WindowManager.LayoutParams) {
                        android.view.WindowManager.LayoutParams p = (android.view.WindowManager.LayoutParams) o;
                        p.height += extraPx;
                        bumpInsetsIfPresent(p, extraPx);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void bumpInsetsIfPresent(android.view.WindowManager.LayoutParams lp, int extraPx) {
        try {
            Object raw = XposedHelpers.getObjectField(lp, "providedInsets");
            if (!(raw instanceof Object[])) return;
            for (Object provider : (Object[]) raw) {
                if (provider == null) continue;
                try {
                    Object current;
                    try {
                        current = XposedHelpers.callMethod(provider, "getInsetsSize");
                    } catch (Throwable ignored) {
                        current = XposedHelpers.getObjectField(provider, "mInsetsSize");
                    }
                    if (current == null) continue;
                    int left = XposedHelpers.getIntField(current, "left");
                    int top = XposedHelpers.getIntField(current, "top");
                    int right = XposedHelpers.getIntField(current, "right");
                    int bottom = XposedHelpers.getIntField(current, "bottom");
                    if (bottom <= 0) continue;
                    Object updated = android.graphics.Insets.of(left, top, right, bottom + extraPx);
                    try {
                        XposedHelpers.callMethod(provider, "setInsetsSize", updated);
                    } catch (Throwable ignored) {
                    }
                    XposedHelpers.setObjectField(provider, "mInsetsSize", updated);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 经典(合并)控制中心: 隐藏运营商名。OplusQuickStatusBarHeader#onFinishInflate 中
     * R.id.qs_carrier_text (位于 qs_clock_container 内) / R.id.carrier_group 显示运营商名, 直接 GONE。
     * (用户使用经典模式, 不再 hook 分离模式的 SeparateQSFakeStatusController, 以免与经典模式叠加。)
     */
    private static void hookQsHideCarrier(final XC_LoadPackage.LoadPackageParam lpparam) {
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

    /**
     * 控制中心顶栏间距(经典/合并模式, 用户所用模式)。
     *  - 右侧状态图标簇 quick_qs_status_icons: 在 OplusQSFakeStatusController 的展开进度回调中
     *    原地渐隐，避免先执行系统原生位移动画再消失。
     *  - 页脚 OplusQSFooterImpl#mSettingsContainer(updateResources$15 仅在 collapsed 时把顶部 padding 重置为 0
     *    后再叠加 footerPx): 让日期/设置按钮小幅下沉(footerPx 较小, 避免之前"过于偏下")。
     */
    private static void hookQsTopMargin(final XC_LoadPackage.LoadPackageParam lpparam,
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
    private static void applyQsStatusClusterFade(Object fractionListener, float expansionFraction) {
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

    private static void setQsStatusVisibility(Object controller, String fieldName, boolean hide) {
        try {
            Object value = XposedHelpers.getObjectField(controller, fieldName);
            if (value instanceof android.view.View) {
                ((android.view.View) value).setVisibility(
                        hide ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addFooterTopPadding(Object footer, int footerPx) {        try {
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

    /**
     * 通知面板分组副标题(如"静默"/"对话"等)：布局由 SectionHeaderView 驱动，其内部
     * TextView(@id/header_label) 原始 textSize=24sp，被包在 FrameLayout(@id/content, paddingTop=12dp) 中。
     * hook SectionHeaderView#onFinishInflate(after):
     *   - 缩小字体至 fontSizePx(16sp)；
     *   - 把 content 的 paddingTop 减少 offsetPx(8dp)：文字因此自然上移 8dp，且仍在父布局范围内，
     *     不会被 FrameLayout 的 clipChildren 裁掉(若改用 translationY(-8dp) 上移, 文字顶端会越过 content
     *     上边界而被裁切, 这正是之前“上半部分被裁切”的原因)；
     *   - 用 translationX(+offsetPx) 把文字右移 8dp(水平位移不会被父布局裁切)。
     */
    private static void hookNotificationSubtitle(final XC_LoadPackage.LoadPackageParam lpparam,
                                                 final float density) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.notification.stack.SectionHeaderView",
                    lpparam.classLoader, "onFinishInflate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 运行时动态门控: 关闭则保持系统默认样式。
                                if (!readBool(KEY_NOTIFICATION_SUBTITLE_ENABLED, false)) return;
                                // 滑条 = 字号缩减量(0=系统默认 24sp, 默认 8sp -> 16sp, 最大 16sp -> 8sp);
                                // 偏移与额外内边距按缩减比例等比缩放, 缩减为 0 时整体即系统默认样式。
                                int reduceSp = readInt(KEY_NOTIFICATION_SUBTITLE_SP, SUBTITLE_REDUCE_SP_DEFAULT);
                                float t = reduceSp / (float) SUBTITLE_REDUCE_SP_DEFAULT;
                                float fontSizePx = (SUBTITLE_ORIG_SP - reduceSp) * density;
                                float offsetPx = SUBTITLE_OFFSET_DP * t * density;
                                int padPx = Math.round(SUBTITLE_PAD_DP * t * density);
                                Object view = param.thisObject;
                                Object label = XposedHelpers.getObjectField(view, "mLabelView");
                                if (label instanceof android.widget.TextView) {
                                    android.widget.TextView tv = (android.widget.TextView) label;
                                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx);
                                    tv.setTranslationX(offsetPx);
                                    tv.setTranslationY(0f);
                                    tv.setPaddingRelative(tv.getPaddingStart(),
                                            tv.getPaddingTop() + padPx,
                                            tv.getPaddingEnd(),
                                            tv.getPaddingBottom() + padPx);
                                }
                                Object contents = XposedHelpers.getObjectField(view, "mContents");
                                if (contents instanceof android.view.ViewGroup) {
                                    android.view.ViewGroup c = (android.view.ViewGroup) contents;
                                    c.setPaddingRelative(c.getPaddingStart(),
                                            Math.max(0, c.getPaddingTop() - (int) offsetPx),
                                            c.getPaddingEnd(), c.getPaddingBottom());
                                }
                                log("notification_subtitle applied");
                            } catch (Throwable t) {
                                log("notification_subtitle apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK com.android.systemui.statusbar.notification.stack.SectionHeaderView#onFinishInflate");
        } catch (Throwable t) {
            log("HOOK FAIL SectionHeaderView#onFinishInflate :: " + Log.getStackTraceString(t));
        }
    }

    // 记录通知子视图原始上下 padding 的 tag, 用于幂等叠加 / 还原
    private static final int TAG_NOTIF_PAD_TOP = 0x4E0F0001;
    private static final int TAG_NOTIF_PAD_BOTTOM = 0x4E0F0002;
    private static final int TAG_NOTIF_GROUP_HEADER_TRANSLATION = 0x4E0F0003;

    // 高频路径缓存: 由 onNotificationUpdated (低频) 刷新, onLayout/onMeasure/applyState 直接读。
    private static volatile boolean sNotifPadEnabled = false;
    private static volatile int sNotifPadPx = 0;

    /**
     * 非静默(未被最小化, mIsMinimized==false)通知：给其通知子视图(contracted/expanded/headsUp/singleLine)的
     * 上下内边距各加 padPx。合并通知折叠时使用 singleLine 子视图，必须一并处理。直接改子视图 padding 才能被 NotificationContentView#getViewHeight 计入高度
     * (它取子视图自身 getHeight, 不含 NotificationContentView 自身 padding), 从而整张卡片随之增高、
     * 内部上下留白增加。静默(最小化)通知则还原到原始 padding(不改动)。
     * 在 onNotificationUpdated(after) 中施加; 若同一子视图重复更新, 以首次记录的原始 padding 为基准叠加,
     * 保证幂等(不会每帧累加)。
     */
    private static void hookNotificationPadding(final XC_LoadPackage.LoadPackageParam lpparam,
                                                final float density) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    lpparam.classLoader, "onNotificationUpdated",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 低频路径: 刷新缓存值供高频 hook 读取。
                                boolean enabled = readBool(KEY_NOTIFICATION_PADDING_ENABLED, false);
                                int padPx = Math.round(
                                        readInt(KEY_NOTIFICATION_PADDING_DP, NOTIFICATION_PADDING_DP) * density);
                                sNotifPadEnabled = enabled;
                                sNotifPadPx = padPx;

                                Object row = param.thisObject;
                                if (!enabled) {
                                    applyNotificationChildPadding(
                                            XposedHelpers.getObjectField(row, "mPrivateLayout"), true, padPx);
                                    applyNotificationChildPadding(
                                            XposedHelpers.getObjectField(row, "mPublicLayout"), true, padPx);
                                    return;
                                }
                                boolean minimized = XposedHelpers.getBooleanField(row, "mIsMinimized");
                                Object privateLayout = XposedHelpers.getObjectField(row, "mPrivateLayout");
                                Object publicLayout = XposedHelpers.getObjectField(row, "mPublicLayout");
                                applyNotificationChildPadding(privateLayout, minimized, padPx);
                                applyNotificationChildPadding(publicLayout, minimized, padPx);
                            } catch (Throwable t) {
                                log("notification_padding apply fail: " + t);
                            }
                        }
                    });

            // 合并通知由 NotificationChildrenContainer 统一布局；给每个子通知加 padding 会只撑开某一行。
            // 这里增加容器总高度，并在布局完成后把整个容器内容整体下移 padPx，形成上下外侧留白。
            // 高频路径优化: 直接读 volatile 变量, 不走 IPC/HashMap。
            final String groupContainerClass =
                    "com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer";
            try {
                XposedHelpers.findAndHookMethod(
                        groupContainerClass, lpparam.classLoader, "getIntrinsicHeight",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!sNotifPadEnabled) return;
                                if (isMinimizedGroup(param.thisObject)) return;
                                Object result = param.getResult();
                                if (result instanceof Integer) {
                                    param.setResult((Integer) result + sNotifPadPx * 2);
                                }
                            }
                        });

                XposedHelpers.findAndHookMethod(
                        groupContainerClass, lpparam.classLoader, "onMeasure", int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!sNotifPadEnabled) return;
                                if (isMinimizedGroup(param.thisObject)) return;
                                int extra = sNotifPadPx * 2;
                                android.view.View container = (android.view.View) param.thisObject;
                                XposedHelpers.callMethod(container, "setMeasuredDimension",
                                        container.getMeasuredWidth(), container.getMeasuredHeight() + extra);
                                XposedHelpers.setIntField(container, "mRealHeight",
                                        XposedHelpers.getIntField(container, "mRealHeight") + extra);
                            }
                        });

                XposedHelpers.findAndHookMethod(
                        groupContainerClass, lpparam.classLoader, "onLayout",
                        boolean.class, int.class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!sNotifPadEnabled) return;
                                if (isMinimizedGroup(param.thisObject)) return;
                                int padPx = sNotifPadPx;
                                android.view.ViewGroup container = (android.view.ViewGroup) param.thisObject;
                                for (int i = 0; i < container.getChildCount(); i++) {
                                    container.getChildAt(i).offsetTopAndBottom(padPx);
                                }
                            }
                        });

                XposedHelpers.findAndHookMethod(
                        groupContainerClass, lpparam.classLoader, "applyState",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                boolean enabled = sNotifPadEnabled && !isMinimizedGroup(param.thisObject);
                                int padPx = sNotifPadPx;
                                android.view.ViewGroup container =
                                        (android.view.ViewGroup) param.thisObject;
                                applyGroupHeaderOffset(container, "mGroupHeader", enabled, padPx);
                                applyGroupHeaderOffset(container, "mMinimizedGroupHeader", enabled, padPx);
                            }
                        });

                try {
                    XposedHelpers.findAndHookMethod(
                            "com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp",
                            lpparam.classLoader,
                            "layoutOplusHeader",
                            groupContainerClass,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        if (!sNotifPadEnabled) return;
                                        android.view.ViewGroup container =
                                                (android.view.ViewGroup) param.args[0];
                                        if (isMinimizedGroup(container)) return;
                                        Object groupExt = XposedHelpers.getObjectField(
                                                param.thisObject, "oplusNotificationGroupExtImpl");
                                        Object wrapper = XposedHelpers.getObjectField(
                                                groupExt, "oplusHeaderWrapper");
                                        Object headerView = XposedHelpers.getObjectField(wrapper, "mView");
                                        applyViewTranslation(headerView, true, sNotifPadPx);
                                    } catch (Throwable ignored) {}
                                }
                            });
                } catch (Throwable ignored) {}

                try {
                    XposedHelpers.findAndHookMethod(
                            "com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupExtImpl",
                            lpparam.classLoader,
                            "layoutOplusHeader",
                            groupContainerClass,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        if (!sNotifPadEnabled) return;
                                        android.view.ViewGroup container =
                                                (android.view.ViewGroup) param.args[0];
                                        if (isMinimizedGroup(container)) return;
                                        Object wrapper = XposedHelpers.getObjectField(
                                                param.thisObject, "oplusHeaderWrapper");
                                        Object headerView = XposedHelpers.getObjectField(wrapper, "mView");
                                        applyViewTranslation(headerView, true, sNotifPadPx);
                                    } catch (Throwable ignored) {}
                                }
                            });
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            log("HOOK OK ExpandableNotificationRow#onNotificationUpdated + group container hooks");
        } catch (Throwable t) {
            log("HOOK FAIL ExpandableNotificationRow#onNotificationUpdated :: " + t);
        }
    }

    /** 快速判断分组容器是否处于最小化状态 (无 IPC) */
    private static boolean isMinimizedGroup(Object container) {
        try {
            Object row = XposedHelpers.getObjectField(container, "mContainingNotification");
            return row != null && XposedHelpers.getBooleanField(row, "mIsMinimized");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyGroupHeaderOffset(android.view.ViewGroup container,
                                                String fieldName, boolean enabled, int padPx) {
        try {
            Object header = XposedHelpers.getObjectField(container, fieldName);
            applyViewTranslation(header, enabled, padPx);
        } catch (Throwable ignored) {}
    }

    private static void applyViewTranslation(Object object, boolean enabled, int padPx) {
        if (!(object instanceof android.view.View)) return;
        android.view.View view = (android.view.View) object;
        Object tag = view.getTag(TAG_NOTIF_GROUP_HEADER_TRANSLATION);
        float original = tag instanceof Float ? (Float) tag : view.getTranslationY();
        if (!(tag instanceof Float)) {
            view.setTag(TAG_NOTIF_GROUP_HEADER_TRANSLATION, original);
        }
        float target = enabled ? original + padPx : original;
        if (view.getTranslationY() != target) {
            view.setTranslationY(target);
        }
    }

    private static void applyNotificationChildPadding(Object contentView, boolean minimized, int padPx) {
        if (!(contentView instanceof android.view.ViewGroup)) return;
        String[] fields = { "mContractedChild", "mExpandedChild", "mHeadsUpChild" };
        for (String f : fields) {
            Object child = XposedHelpers.getObjectField(contentView, f);
            if (!(child instanceof android.view.View)) continue;
            android.view.View v = (android.view.View) child;
            Integer origTop = (Integer) v.getTag(TAG_NOTIF_PAD_TOP);
            Integer origBottom = (Integer) v.getTag(TAG_NOTIF_PAD_BOTTOM);
            if (origTop == null) { origTop = v.getPaddingTop(); v.setTag(TAG_NOTIF_PAD_TOP, origTop); }
            if (origBottom == null) { origBottom = v.getPaddingBottom(); v.setTag(TAG_NOTIF_PAD_BOTTOM, origBottom); }
            int top = minimized ? origTop : origTop + padPx;
            int bottom = minimized ? origBottom : origBottom + padPx;
            if (v.getPaddingTop() != top || v.getPaddingBottom() != bottom) {
                v.setPaddingRelative(v.getPaddingStart(), top, v.getPaddingEnd(), bottom);
            }
        }
    }

    /**
     * Feature 10 — 合并(经典)控制中心背景压暗("控制中心背景变暗")。
     *
     * 真正承载控制中心(及通知栏)背景的是 "背后 scrim" —— CentralSurfacesImpl 把 scrimView 赋给
     * ScrimController.mScrimBehind, 并随后调用 scrimView.setScrimName(scrimController.getScrimName(scrimView)),
     * 其中 getScrimName(mScrimBehind) 返回常量 "behind_scrim"。
     *
     * 做法: 直接修正系统原生的平台模糊混色配置(在 SurfaceFlinger 的 AGSL shader 里合成, 位于窗口内容之下),
     * 把"背后背景"压暗, 不再叠加任何半透明黑叠层。
     *
     * 为什么"纯黑背景反而变灰 / 只压暗一半"(根因不在本模块):
     *   控制中心背景是实时平台模糊, 其"背后 scrim"平台混色(MixColorWithShader)默认
     *   top=LUMINOSITY+#99333333、bottom=OVERLAY+#80999999 ——
     *   LUMINOSITY 会把结果亮度"归一化"到该层亮度的 ~0.2(把黑底提亮成灰), OVERLAY 再抬暗部下限。
     *   这层混色合在窗口内容之下, 所以任何"半透明黑叠加"都必然残留那层 ~20% 归一化灰, 即"纯黑变灰"。
     *   hook getPanelPlatformMixConfig 把 behind scrim 的混色改为 top=LUMINOSITY+近黑(bottom=OVERLAY 置 0),
     *   从源头消除提亮, 让"压暗"真正生效、不残留灰, 同时保留模糊壁纸纹理。
     *   仅替换 behind scrim 配置对象内的 backgroundShaderParam 字段, 不动共享的 PANEL_*_MIX_COLOR 常量
     *   (否则会连累通知/磁贴), 且前台 scrim 走独立的 WindowBlurConfig、不受影响。
     *
     * @param lpparam loadPackage 参数
     */
    private static void hookQsBackgroundDim(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 1) 修正系统原生"亮度归一化(LUMINOSITY)把黑底提亮成灰"的混色配置:
            //    getPanelPlatformMixConfig() 返回 behind scrim 的平台模糊混色(MixColorWithShader),
            //    系统默认 top=LUMINOSITY+#99333333(亮: 把结果亮度抬到 ~0.2 灰)、bottom=OVERLAY+#80999999(再抬暗部)。
            //    这层混色在 SurfaceFlinger 的 AGSL shader 里合成, 位于窗口内容之下, 正是"纯黑变灰"的根因。
            //    这里把 top 换成 LUMINOSITY+近黑色(把亮度归一化到接近 0, 保留模糊纹理), bottom OVERLAY 置 0,
            //    从而让背后背景真正压暗而非被提亮。仅当返回 BlurMixSingleWithShader(behind scrim;
            //    前台 scrim 用的是独立 WindowBlurConfig 不受影响)时替换其 backgroundShaderParam 字段,
            //    不动共享的 PANEL_LIGHT/NIGHT_MIX_COLOR 常量(否则会连累通知/磁贴)。
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

    /**
     * Feature 13 — 控制中心 WLAN/蓝牙 名称单行省略。
     * 可伸缩 tile(2x1, 即 WLAN/蓝牙 这类磁贴) 的名称分两种情况:
     *   - WLAN: SSID 写在主标题 labelTitle(TextSwitcher, R.id.tile_label) 里,
     *     由 handleTileStateChange(QSTile.State) 直接经 TextSwitcherExtKt.setContent 写入;
     *   - 蓝牙: 设备名写在副标题 labelDesc(TextSwitcher, R.id.tile_label_desc) 里,
     *     由 updateLabelDescText(QSTile.State) 写入。
     * 在这两个方法之后, 对 labelTitle 与 labelDesc 下所有 TextView 强制: 单行 + 行尾省略号,
     * 使过长的名称在一行内以 "…" 结尾, 不换行。幂等; 仅运行时开关开启时生效。
     * (注: 本类没有 updateLabelText 方法, 标题是在 handleTileStateChange 内直接设置的。)
     */
    private static void hookQsTileNameEllipsis(final XC_LoadPackage.LoadPackageParam lpparam) {
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
    private static void forceSingleLineEllipsis(Object view) {
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

    private static void applyEllipsis(TextView tv) {
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setHorizontallyScrolling(false); // 关闭横向滚动/跑马灯, 仅静态行尾省略
    }

    /**
     * Feature 17 — 流体云出现时不隐藏电量百分比 (com.android.systemui):
     * 系统在流体云胶囊出现时会把 BatteryStyleModel.capsuleShowing=true,
     * 从而令 BatteryViewBinder.bind$updatePercentOutView 中的 PercentOutIcon.isVisible=false,
     * 状态栏电量百分比数字被 setVisibility(GONE)。
     * 此处 hook 该方法, 在 beforeHook 中将 PercentOutIcon.isVisible 强制置 true,
     * 使电量百分比始终显示。
     */
    private static void hookFluidCloudKeepPercent(final XC_LoadPackage.LoadPackageParam lpparam) {
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

    // Feature 18 — 悬浮小窗贴边挂机(com.android.systemui)
    // 机制(已用 android-app-mods 反编译确认, 直板手机路径): 自由窗口(小窗)拖到屏幕边缘 ->
    // 系统把该任务最小化成"贴边小条"。该决策由拖拽动画控制器
    // com.android.wm.shell.fullscreen.OplusDragTaskFullAnimation#getChangeStateByPoint(x,y) 完成:
    //   - 落点在 mFlexibleTaskRect 内 -> 返回 8(保持自由浮动窗口, 停原位)
    //   - 落点在矩形外(拖到边缘/角落) -> 返回 5(最小化成贴边小条)   <-- 这就是"贴边最小化"
    //   - 全屏应用拖到顶部(mIsFullTaskDrag) -> 也返回 5, 但那是另一手势, 不动
    // 之前两版分别误 hook 了 tablet 专用的 showMinimizedWindow(直板不执行) 与分屏用的
    // ShellTaskOrganizerExt#adjustChangeForFlexibleMinimizeIfNeed(分屏最小化, 自由窗拖边走不到这里), 故"没有效果"。
    // 实现: hook getChangeStateByPoint(after), 当结果==5 且非全屏拖拽(mIsFullTaskDrag 为 false)时,
    // 把结果改成 8(保持自由窗口), 使自由窗口拖到边缘不再最小化、保持前台自由浮动(贴边挂机)。
    // 该动画控制器由 SystemUI 进程的拖拽手势触发, 仅 hook SystemUI, 无需重启整机; 运行时门控。
    /**
     * Feature 18 — 悬浮小窗贴边挂机。
     * 调用链(直板手机路径, 已用 simpleperf 火焰图确认): 贴边松手 ->
     *   FlexibleFloatHandleAnimationSpec.defaultCallAnimationEnd
     *     -> FlexiblePointerHandler$2.onAnimationEnd
     *       -> FlexibleTaskController.exitFlexibleTaskWindowInnerLocked(AbsFlexibleTaskExitStrategy)
     *         -> exitFlexibleTask -> TaskExtImpl.moveTaskToBackForPanorama -> Task.moveTaskToBack (切后台 -> 边缘迷你条)
     * 正常"拖到非边缘松手保持浮窗"走 finishMovingTask -> Task.resize, 不经过 exitFlexibleTask*, 故本 hook 不影响它。
     * 做法: beforeHook 中开关开启时调用系统已有的 updateFocusWhenExitFlexibleTask，
     * 由其内部通过 AOSP ActivityTaskManagerService#setFocusedTask 聚焦小窗下方任务，
     * 但绝不拦截原生退出提交。系统继续完成缩成图标、隐藏窗口和切后台，挂机进程仍可在后台运行。
     * 关键点: 不能在 exitFlexibleTaskWindowInnerLocked 上 setResult(null)，否则迅速贴边时
     * 原生 ToFloat 收尾会被截断，窗口就会卡在拖拽位置而不会变成图标。
     * 用 ContentProvider 通道(readBool)实时读取开关, 改设置即时生效, 无需重启/重载进程。
     */
    private static void hookFloatWindowEdgeHangSystemServer(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> ftc = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskController", lpparam.classLoader);
            final Class<?> strategyCls = XposedHelpers.findClass(
                    "com.android.server.wm.AbsFlexibleTaskExitStrategy", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(ftc, "exitFlexibleTaskWindowInnerLocked", strategyCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED, false)) return;
                                final Object strategy = param.args[0];
                                if (strategy == null) return;
                                if (((Number) XposedHelpers.callMethod(strategy, "getExitTo")).intValue() != 5) return;
                                final Object flexibleTask = XposedHelpers.getObjectField(strategy, "mTask");
                                if (flexibleTask != null) {
                                    // 设备框架真实公开方法；内部执行 mAtms.setFocusedTask(nextTask.mTaskId)。
                                    XposedHelpers.callMethod(param.thisObject,
                                            "updateFocusWhenExitFlexibleTask", flexibleTask);
                                }
                                // 禁止 setResult(null)：必须让系统完成 ToFloat 图标动画和后台收尾。
                            } catch (Throwable t) {
                                // 聚焦失败不能影响原生 ToFloat 收尾。
                                log("!!! edge_hang focus-behind failed, keep native exit: " + t);
                            }
                        }
                    });
            log(">>> matched android (system_server): float_window_edge_hang (focus behind, native exit)");
        } catch (Throwable t) {
            log("!!! float_window_edge_hang system_server hook failed: " + t);
        }
    }

    /**
     * 横屏应用小窗保持比例(system_server, com.android.server.wm.FlexibleTaskController)。
     * 系统 fillFlexibleTaskInfo 对横屏应用(isLandScapeOriention)硬编码 ratio=0.5625f(高/宽=9:16),
     * 与设备真实屏幕比例不符。本功能让横屏应用的小窗 宽:高 = 屏幕 高:宽(如 19.5:9),
     * 即 高/宽 = 屏幕宽/屏幕高 = 1 / getFlexibleTaskFullScreenRatio(屏高, 屏宽)。
     * 做法:
     *  - afterHook fillFlexibleTaskInfo: 开关开启且为横屏应用时, 读取返回的 FlexibleTaskInfo,
     *    将 ratio 改为目标值, 按系统同款公式重算 scale, 并据目标比例重算 launchBounds(保持系统选定的高度与居中)。
     *  - afterHook getFlexibleTaskAvailableRatioByActivity: 把目标比例加入可选比例列表, 使拖拽缩放可达该比例。
     * 运行时门控, 关闭立即恢复系统默认。仅 android/system_server 作用域。
     */
    private static void hookFloatWindowLandscapeKeepRatio(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> ftc = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskController", lpparam.classLoader);
            // FlexibleTaskInfo 与 Builder 在同一包内, 用同 ClassLoader 取。
            final Class<?> ftiClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskInfo", lpparam.classLoader);

            // 1) fillFlexibleTaskInfo: 修正横屏应用小窗的 ratio / scale / launchBounds
            XposedHelpers.findAndHookMethod(ftc, "fillFlexibleTaskInfo",
                    "com.android.server.wm.FlexibleTaskInfo$Builder",
                    android.graphics.Rect.class,
                    android.content.Intent.class,
                    android.content.pm.ActivityInfo.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED, false)) return;
                                if (!((Boolean) param.args[4])) return; // 非横屏应用不处理
                                Object result = param.getResult();
                                if (result == null) return;
                                android.graphics.Rect windowBounds = (android.graphics.Rect) param.args[1];
                                if (windowBounds == null || windowBounds.isEmpty()) return;
                                final int wW = windowBounds.width();
                                final int wH = windowBounds.height();
                                if (wW <= 0 || wH <= 0) return;
                                // 目标 ratio = 高/宽 = 屏幕宽/屏幕高 = 1 / getFlexibleTaskFullScreenRatio(wH, wW)
                                float fullScreenRatio = ((Number) XposedHelpers.callMethod(
                                        param.thisObject, "getFlexibleTaskFullScreenRatio", wH, wW)).floatValue();
                                if (fullScreenRatio <= 0f) return;
                                final float targetRatio = 1.0f / fullScreenRatio; // 高/宽
                                final float scale = targetRatio / (wW * 1.0f / wH);
                                XposedHelpers.callMethod(result, "setRatio", targetRatio);
                                XposedHelpers.callMethod(result, "setScale", scale);
                                // 重算 launchBounds: 保持系统选定的高度, 按目标比例求宽, 在 windowBounds 内居中
                                Object oldBounds = XposedHelpers.callMethod(result, "getLaunchBounds");
                                if (oldBounds instanceof android.graphics.Rect) {
                                    android.graphics.Rect ob = (android.graphics.Rect) oldBounds;
                                    final int h = ob.height();
                                    if (h > 0) {
                                        final int nw = (int) (h / targetRatio + 0.5f);
                                        final int left = ob.centerX() - nw / 2;
                                        final int top = ob.top;
                                        android.graphics.Rect nb = new android.graphics.Rect(left, top, left + nw, top + h);
                                        // 约束在 windowBounds 内(避免越界)
                                        if (nb.right > windowBounds.right) nb.offset(-(nb.right - windowBounds.right), 0);
                                        if (nb.left < windowBounds.left) nb.offset(windowBounds.left - nb.left, 0);
                                        XposedHelpers.callMethod(result, "setLaunchBounds", nb);
                                    }
                                }
                            } catch (Throwable t) {
                                log("!!! landscape_keep_ratio fillFlexibleTaskInfo failed: " + t);
                            }
                        }
                    });

            // 2) getFlexibleTaskAvailableRatioByActivity: 把目标比例加入可选列表(拖拽缩放可达)
            XposedHelpers.findAndHookMethod(ftc, "getFlexibleTaskAvailableRatioByActivity",
                    "com.android.server.wm.ActivityRecord",
                    String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED, false)) return;
                                Object result = param.getResult();
                                if (!(result instanceof java.util.List)) return;
                                @SuppressWarnings("unchecked")
                                java.util.List<Float> list = (java.util.List<Float>) result;
                                android.graphics.Rect windowBounds = (android.graphics.Rect) param.args[1];
                                int wW = 0, wH = 0;
                                if (windowBounds != null && !windowBounds.isEmpty()) {
                                    wW = windowBounds.width(); wH = windowBounds.height();
                                } else {
                                    android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
                                    wW = dm.widthPixels; wH = dm.heightPixels;
                                }
                                if (wW <= 0 || wH <= 0) return;
                                float fullScreenRatio = ((Number) XposedHelpers.callMethod(
                                        param.thisObject, "getFlexibleTaskFullScreenRatio", wH, wW)).floatValue();
                                if (fullScreenRatio <= 0f) return;
                                final float targetRatio = 1.0f / fullScreenRatio;
                                boolean has = false;
                                for (Float f : list) {
                                    if (Math.abs(f - targetRatio) < 0.001f) { has = true; break; }
                                }
                                if (!has) list.add(targetRatio);
                            } catch (Throwable t) {
                                log("!!! landscape_keep_ratio getFlexibleTaskAvailableRatioByActivity failed: " + t);
                            }
                        }
                    });

            log(">>> matched android (system_server): float_window_landscape_keep_ratio (FlexibleTaskController)");
        } catch (Throwable t) {
            log("!!! float_window_landscape_keep_ratio system_server hook failed: " + t);
        }
    }

    /**
     * 多任务(quickstep)显示被系统隐藏的应用。
     * 系统"隐藏应用"经由 OplusPrivacyManager.isHiddenPkg(pkg, userId) 判定; 最近任务列表在
     * OplusRecentTasksFilter.filterTaskInfo 中据此剔除隐藏任务, OplusRecentsViewImpl 又在
     * shouldAddStubTaskView / onGestureAnimationStart 中据此跳过隐藏应用的 stub 卡片与手势概览。
     * 这里在 isHiddenPkg 上加 beforeHook: 当调用方位于 com.android.quickstep 多任务渲染/手势路径时
     * 返回 false, 让隐藏任务进入最近任务列表。应用锁走独立 API(isAppLocked 等), 不调 isHiddenPkg, 不受影响。
     */
    private static final java.util.concurrent.atomic.AtomicInteger sRecentsBypassLogCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static void hookRecentsShowHidden(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.quickstep.privacy.OplusPrivacyManager",
                    lpparam.classLoader, "isHiddenPkg", String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 运行时动态门控: 关闭则保持系统默认(隐藏应用不出现在多任务)。
                            if (!readBool(KEY_RECENTS_SHOW_HIDDEN_ENABLED, false)) return;
                            // 仅当调用方来自 quickstep 多任务渲染/手势路径时, 绕过"隐藏应用"判定
                            if (callerInQuickstepPath()) {
                                Object pkg = param.args[0];
                                param.setResult(false);
                                if (sRecentsBypassLogCount.getAndIncrement() < 30) {
                                    Log.e("ColorOSMod", "recents bypass isHiddenPkg pkg=" + pkg);
                                }
                            }
                        }
                    });
            log("HOOK OK com.oplus.quickstep.privacy.OplusPrivacyManager#isHiddenPkg");
        } catch (Throwable t) {
            log("HOOK FAIL OplusPrivacyManager#isHiddenPkg :: " + Log.getStackTraceString(t));
        }
    }

    // Feature 19 — 多任务不显示小窗应用(com.android.launcher):
    // 最近任务列表(多任务切换)里隐藏处于自由窗口/小窗(FlexibleWindow)状态的任务卡片。
    // 机制: launcher 多任务列表加载时由 com.oplus.quickstep.data.OplusRecentTasksFilter#filterTaskInfo
    // 逐任务过滤(返回 true 即剔除该卡片)。hook 该方法 before: 开关开启且任务为小窗(isFlexibleFloatingWindow)时,
    // 直接 setResult(true) 剔除, 使小窗应用不出现在多任务卡片中(应用本身仍在前台运行, 不受影响)。
    // 运行时门控, 关闭开关立即恢复系统默认。仅在 launcher 作用域注入。
    private static void hookRecentsHideFreeform(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final String flag = KEY_RECENTS_HIDE_FREEFORM_ENABLED;
            XposedHelpers.findAndHookMethod(
                    "com.oplus.quickstep.data.OplusRecentTasksFilter",
                    lpparam.classLoader, "filterTaskInfo",
                    int.class, int.class,
                    "com.android.wm.shell.shared.GroupedTaskInfo",
                    "java.util.ArrayList",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!readBool(flag, false)) return;
                                Object gti = param.args[2];
                                if (gti == null) return;
                                Object taskInfo = XposedHelpers.callMethod(gti, "getTaskInfo1");
                                if (taskInfo == null) return;
                                if (isFlexibleFloatingWindow(lpparam.classLoader, taskInfo)) {
                                    param.setResult(true); // 剔除该小窗任务卡片
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
            log("HOOK OK OplusRecentTasksFilter#filterTaskInfo (recents hide freeform)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusRecentTasksFilter#filterTaskInfo :: " + Log.getStackTraceString(t));
        }
    }

    // 复刻系统 TaskUtils.isFlexibleFloatingWindow(TaskInfo): 判断任务是否处于小窗/自由窗口状态。
    private static boolean isFlexibleFloatingWindow(ClassLoader cl, Object taskInfo) {
        try {
            Class<?> taskUtils = XposedHelpers.findClass(
                    "com.android.systemui.shared.recents.utilities.TaskUtils", cl);
            Object r = XposedHelpers.callStaticMethod(taskUtils, "isFlexibleFloatingWindow",
                    new Class[]{android.app.TaskInfo.class}, taskInfo);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) { }
        // 兜底: 直接按窗口模式判定(WINDOWING_MODE_FREEFORM=5)
        try {
            Object wm = XposedHelpers.callMethod(taskInfo, "getWindowingMode");
            if (wm instanceof Integer) return (Integer) wm == 5;
        } catch (Throwable ignored) { }
        try {
            Object cfg = XposedHelpers.callMethod(taskInfo, "getConfiguration");
            Object wc = XposedHelpers.callMethod(cfg, "getWindowConfiguration");
            Object wm = XposedHelpers.callMethod(wc, "getWindowingMode");
            return wm instanceof Integer && (Integer) wm == 5;
        } catch (Throwable ignored) { }
        return false;
    }

    // 判断本次 isHiddenPkg 的调用方是否位于 quickstep 多任务渲染/手势路径
    // (最近任务列表过滤与 recents 视图均在 com.android.quickstep 包下; 应用锁不调此方法)
    private static boolean callerInQuickstepPath() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String cn = e.getClassName();
            if (cn == null) continue;
            if (cn.startsWith("com.android.quickstep")
                    && !cn.toLowerCase().contains("lock")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 系统布局会把 hotseat 高度变化按 workspaceTopPercentage 分摊到 Workspace 顶部 padding：
     * hotseat 缩短 x 像素时，页面实际向 Dock 移动的距离只有 x * (1 - percentage)。
     * 这里反推 hotseat 缩短量，使设置中的 dp 值对应真实页面到 Dock 的间距变化。
     */
    private static void hookIndicatorHotseatSize(final XC_LoadPackage.LoadPackageParam lpparam,
                                                  final float density) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher.layoutparam.HotseatParam", lpparam.classLoader,
                    "getHotseatBarSizePx", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBool(KEY_INDICATOR_ENABLED, false)) return;
                            Object result = param.getResult();
                            if (!(result instanceof Integer)) return;
                            int configuredDp = Math.max(0, Math.min(
                                    32, readInt(KEY_INDICATOR_DP, INDICATOR_REDUCE_DP)));
                            int requestedDp = Math.min(INDICATOR_REDUCE_MAX_DP, configuredDp);
                            if (requestedDp == 0) return;
                            try {
                                Object workspace = XposedHelpers.getObjectField(
                                        param.thisObject, "mWorkspace");
                                float topPercentage = ((Number) XposedHelpers.callMethod(
                                        workspace, "getWorkspaceTopPercentage")).floatValue();
                                topPercentage = Math.max(0f, Math.min(0.95f, topPercentage));
                                float pageMoveRatio = 1f - topPercentage;
                                int requestedPx = Math.round(requestedDp * density);
                                int hotseatDeltaPx = Math.round(requestedPx / pageMoveRatio);
                                int originalPx = (Integer) result;
                                param.setResult(Math.max(1, originalPx - hotseatDeltaPx));
                            } catch (Throwable t) {
                                // 布局字段不可用时退回直接 dp->px，避免影响桌面正常布局。
                                param.setResult((Integer) result
                                        - Math.round(requestedDp * density));
                            }
                        }
                    });
            log("HOOK OK HotseatParam#getHotseatBarSizePx (workspace compensation)");
        } catch (Throwable t) {
            log("HOOK FAIL HotseatParam#getHotseatBarSizePx (workspace compensation): " + t);
        }
    }

    /**
     * 通用像素增量 hook: delta 在运行时按 dpKey 对应的滑条值(默认 dpDef)计算,
     * sign 为 +1 叠加 / -1 缩减; 开关(gateKey)关闭则返回原值。
     * 与 hookPx 的区别是增量值不在注入时固定, App 内拖滑条即时生效。
     */
    private static void hookPxRuntime(XC_LoadPackage.LoadPackageParam lpparam,
                                      String className, String methodName, final float density,
                                      final String gateKey, final String dpKey, final int dpDef,
                                      final int dpMax, final int sign) {
        try {
            XposedHelpers.findAndHookMethod(className, lpparam.classLoader, methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!readBool(gateKey, false)) return;
                            Object ret = param.getResult();
                            if (ret instanceof Integer) {
                                int dp = Math.max(0, Math.min(dpMax, readInt(dpKey, dpDef)));
                                param.setResult((Integer) ret + sign * Math.round(dp * density));
                            }
                        }
                    });
            log("HOOK OK " + className + "#" + methodName);
        } catch (Throwable t) {
            log("HOOK FAIL " + className + "#" + methodName + " :: " + Log.getStackTraceString(t));
        }
    }

    // 跨进程读取开关, 双通道 + 诊断:
    // 1) 直接读 App 的 644 prefs 文件(优先; 若 launcher 进程能读 app_data_file 则绕开 LSPosed 守护进程)。
    // 2) XSharedPreferences 兜底(经 LSPosed 守护进程)。
    // 跨进程读取开关, 走 Binder(ContentProvider) 通道, 不受 SELinux 对 app_data_file 的限制。
    // 1) 优先查缓存(TTL 内直接返回, 减少 IPC)。
    // 2) 通过 getContentResolver().query(content://<authority>/<key>) 向模块 App 的 Provider 取真实值。
    // 3) 失败(模块 App 尚未就绪等)时回退 XSharedPreferences; 再失败返回默认 def。
    private static boolean readBool(String key, boolean def) {
        // 1) 新鲜缓存(有效期内)直接返回, 避免每次交互都走 IPC 造成卡顿
        Object[] cached = sCache.get(key);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < CACHE_TTL_MS) {
            return (Boolean) cached[1];
        }
        // 2) ContentProvider 通道
        try {
            if (sAppContext == null) {
                sAppContext = currentApplication();
            }
            if (sAppContext != null) {
                ContentResolver cr = sAppContext.getContentResolver();
                Uri uri = Uri.parse("content://" + SETTINGS_AUTHORITY + "/" + key);
                Cursor c = cr.query(uri, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst() && c.getColumnCount() > 0) {
                            int v = c.getInt(0);
                            boolean result = v == 1;
                            sCache.put(key, new Object[]{System.currentTimeMillis(), result});
                            return result;
                        }
                    } finally {
                        c.close();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 3) Provider 取不到(模块 App 未运行等): 回退到上一次成功取到的值(粘性, 即使已过期),
        //    保证设置一旦被写入就会"记住", 不受 App 被杀/重启影响; 仅从未取到过才用默认或 XSP。
        if (cached != null) {
            return (Boolean) cached[1];
        }
        try {
            XSharedPreferences pref = new XSharedPreferences(MODULE_PACKAGE, PREF_NAME);
            return pref.getBoolean(key, def);
        } catch (Throwable ignored) {
        }
        return def;
    }

    // 跨进程读取 int 设置(如滑条值), 通道与 readBool 相同: 缓存 -> ContentProvider -> 粘性缓存 -> 默认。
    // Provider 对不存在的键返回空游标, 此时回落到 def。
    private static int readInt(String key, int def) {
        Object[] cached = sCache.get(key);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < CACHE_TTL_MS) {
            return (Integer) cached[1];
        }
        try {
            if (sAppContext == null) {
                sAppContext = currentApplication();
            }
            if (sAppContext != null) {
                ContentResolver cr = sAppContext.getContentResolver();
                Uri uri = Uri.parse("content://" + SETTINGS_AUTHORITY + "/" + key);
                Cursor c = cr.query(uri, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst() && c.getColumnCount() > 0) {
                            int v = c.getInt(0);
                            sCache.put(key, new Object[]{System.currentTimeMillis(), v});
                            return v;
                        }
                    } finally {
                        c.close();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (cached != null) {
            return (Integer) cached[1];
        }
        return def;
    }

    private static float readDensity() {
        try {
            return android.content.res.Resources.getSystem().getDisplayMetrics().density;
        } catch (Throwable ignored) {
            return 3.0f; // common ColorOS density fallback
        }
    }

    // 反射获取当前进程 Application(Context), 用于 ContentResolver 跨进程查询设置。
    private static android.content.Context currentApplication() {
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread",
                    java.lang.ClassLoader.getSystemClassLoader());
            return (android.content.Context) XposedHelpers.callStaticMethod(at, "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }
}
