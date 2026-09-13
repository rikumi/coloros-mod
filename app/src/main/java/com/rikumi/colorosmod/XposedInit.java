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

import com.rikumi.colorosmod.hooks.GestureHooks;
import com.rikumi.colorosmod.hooks.LauncherHooks;
import com.rikumi.colorosmod.hooks.MediaProviderHooks;
import com.rikumi.colorosmod.hooks.CameraHooks;
import com.rikumi.colorosmod.hooks.MultiWindowHooks;
import com.rikumi.colorosmod.hooks.SafecenterHooks;
import com.rikumi.colorosmod.hooks.SettingsHooks;
import com.rikumi.colorosmod.hooks.StatusBarLyricHooks;
import com.rikumi.colorosmod.hooks.SystemServerHooks;
import com.rikumi.colorosmod.hooks.SystemUiHooks;
import com.rikumi.colorosmod.hooks.WallpapersHooks;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

// ColorOS (Oplus) 系统界面调整, 经 LSPosed 注入。
// 每个功能由 prefs 中各自的开关控制; 各 hook 的目标类/方法与逆向结论见对应方法上的注释。
//
// 采用新版(libxposed)API: 入口实现 io.github.libxposed.api.XposedModule, 声明见
// META-INF/xposed/java_init.list, 作用域见 META-INF/xposed/scope.list, 配置见
// META-INF/xposed/module.prop。新版框架不再提供 XposedHelpers 等旧接口, 本模块
// 在 com.rikumi.colorosmod.xposed 包里自建了等价的兼容层, hooks 下代码无需改动。
public class XposedInit extends XposedModule {

    // 当前进程信息, 由 onModuleLoaded 记录。system_server 里 "android" 包也会走
    // onPackageLoaded, 只有靠它区分"这是 system_server 而不是普通应用里的框架包"。
    private static volatile String sProcessName = "";
    private static volatile boolean sIsSystemServer = false;
    private static volatile boolean sSystemServerHooked = false;
    private static volatile boolean sAppProcessHooked = false;

    public static final String TAG = "ColorOSMod";
    public static final String MODULE_PACKAGE = "com.rikumi.colorosmod";
    public static final String PREF_NAME = "settings";

    public static final String KEY_ICON_GAP_ENABLED = "icon_gap_enabled";
    public static final String KEY_ICON_GAP_DP = "icon_gap_dp";
    // 调整抽屉每行图标数量: 只改 AllAppsParam(应用抽屉)的列数与图标尺寸,
    // 不碰 IconParam / 桌面网格。系统原生 getNumAllAppsColumns 在手机上会读
    // drawer_layout_columns(默认 4), 开启后按滑条 4-6 列强制, 并把抽屉图标按 4/列数缩放,
    // 使每个格子里图标占比与原来 4 列时一致。同时减小左侧 padding, 抵消右侧字母索引条
    // 造成的"左边空白看起来更大"。
    public static final String KEY_DRAWER_COLUMNS_ENABLED = "drawer_columns_enabled";
    public static final String KEY_DRAWER_COLUMNS = "drawer_columns";
    public static final int DRAWER_COLUMNS_MIN = 4;
    public static final int DRAWER_COLUMNS_MAX = 6;
    public static final int DRAWER_COLUMNS_DEFAULT = 5;
    // 抽屉右侧字母索引: 系统点字母会切到 ClusterAppsContainer, 弹出该字母的图标分组。
    // 开启后改为滚动列表到对应分区, 不再弹出分组。
    public static final String KEY_DRAWER_LETTER_SCROLL_ENABLED = "drawer_letter_scroll_enabled";
    public static final String KEY_INDICATOR_DP = "indicator_dp";
    public static final String KEY_POPUP_SCALE_PERCENT = "popup_scale_percent";
    public static final String KEY_NOTIFICATION_SUBTITLE_SP = "notification_subtitle_sp";
    public static final String KEY_NOTIFICATION_PADDING_DP = "notification_padding_dp";
    public static final String KEY_INDICATOR_ENABLED = "indicator_enabled";
    public static final String KEY_QS_CARRIER_ENABLED = "qs_carrier_enabled";
    public static final String KEY_QS_TOPMARGIN_ENABLED = "qs_topmargin_enabled";
    public static final String KEY_NOTIFICATION_SUBTITLE_ENABLED = "notification_subtitle_enabled";
    public static final String KEY_NOTIFICATION_PADDING_ENABLED = "notification_padding_enabled";
    // 通知左滑直接清除: ColorOS 国内版左滑通知会露出"设置/删除"侧边按钮(需滑到底才清除),
    // 海外版(exp)一个按钮都不生成、抬手即清除。区分点在 NotificationMenuRowExtImpl
    // #createMenuViewsExt 与 OplusSwipeHelperExImpl#shouldNotShowMenuExt 两处(见
    // NotificationHooks#hookNotificationSwipeToDismiss)。开启后强制走 exp 分支。
    public static final String KEY_NOTIFICATION_SWIPE_TO_DISMISS_ENABLED =
            "notification_swipe_to_dismiss_enabled";
    // 通知下滑展开: 国内版在 NotificationStackScrollLayout 构造末尾主动
    // setExpandingEnabled(false) 关掉 ExpandHelper, 并把 ext 层 setExpandingEnabled 整个短路,
    // 海外版(含一加 OxygenOS)保持 ExpandHelper 可用, 单指下拉通知即可展开。
    // 见 NotificationHooks#hookNotificationPullExpand。开启后按 exp 分支处理。
    public static final String KEY_NOTIFICATION_PULL_EXPAND_ENABLED =
            "notification_pull_expand_enabled";
    public static final String KEY_RECENTS_SHOW_HIDDEN_ENABLED = "recents_show_hidden_enabled";
    public static final String KEY_RECENTS_HIDE_FREEFORM_ENABLED = "recents_hide_freeform_enabled";
    public static final String KEY_HIDE_APPS_NOVERIFY_ENABLED = "hide_apps_noverify_enabled";
    public static final String KEY_HIDE_APPS_TITLE_FOLDER_ENABLED = "hide_apps_title_folder_enabled";
    // 停用应用免密码: 设置里停用"受生物识别保护"的应用(config_biometric_protected_package_names)
    // 前会强制做一次生物识别/锁屏验证。开启后跳过该验证(见 SettingsHooks#hookDisableAppsNoVerify)。
    public static final String KEY_DISABLE_APPS_NOVERIFY_ENABLED = "disable_apps_noverify_enabled";
    // 在设置的应用管理页隐藏已停用的应用。应用列表默认 filterType=4, 用的是 Oplus 自己的
    // FILTER_EVERYTHING_OPLUS, 它不像 AOSP 的"所有应用"(filterType=1)那样排除用户级停用的包,
    // 所以停用后仍会在列表里出现(见 SettingsHooks#hookHideDisabledApps)。
    public static final String KEY_HIDE_DISABLED_APPS_ENABLED = "hide_disabled_apps_enabled";
    // 通用设置 — 设置首页图标样式: 0=系统默认, 1=不规则图标, 2=圆形图标。
    public static final String KEY_SETTINGS_HOME_ICON_STYLE = "settings_home_icon_style";
    public static final int SETTINGS_HOME_ICON_STYLE_DEFAULT = 0;
    public static final int SETTINGS_HOME_ICON_STYLE_IRREGULAR = 1;
    public static final int SETTINGS_HOME_ICON_STYLE_CIRCLE = 2;
    // 缩小桌面图标长按菜单: 在资源层按比例缩放菜单的图标、文字、宽高与内外边距。
    public static final String KEY_SHRINK_POPUP_MENU = "shrink_popup_menu";
    // 长按菜单缩小比例的默认值(百分比, 0=系统原始大小)。实际值由滑条 KEY_POPUP_SCALE_PERCENT
    // 在运行时读取(0..2*默认值), 缩放系数 = 1 - pct/100。
    public static final int POPUP_SHRINK_PERCENT_DEFAULT = 10;
    // Feature 9 — 桌面双指张开(pinch-out)手势打开隐藏应用文件夹 (com.android.launcher)
    public static final String KEY_PINCH_OUT_OPEN_HIDE_APPS_ENABLED = "pinch_out_open_hide_apps_enabled";
    // 桌面文件夹展开背景透明化: 展开时系统对壁纸施加 blur=1.0 + 暗色, 看起来像一层灰。
    // 所有壁纸模糊都汇入 OplusDepthController.setBlur(float, boolean), hook 它并在有文件夹
    // 打开(含动画)时把模糊强制为 0; 不影响多任务/应用抽屉等其它场景。
    public static final String KEY_FOLDER_BG_TRANSPARENT_ENABLED = "folder_bg_transparent_enabled";
    // 调整桌面文件夹展开/收起动画持续时间: 时长来自 OplusFolderAnimationManager 构造时读取的
    // 4 个 integer 资源(850/800/600/600ms) + 基类的 config_materialFolderExpandDuration(200ms)。
    // 开启时统一替换为滑条值(100-500ms, 默认 300), 关闭时返回原值。
    public static final String KEY_FOLDER_ANIM_DURATION_ENABLED = "folder_anim_duration_enabled";
    public static final String KEY_FOLDER_ANIM_DURATION_MS = "folder_anim_duration_ms";
    // Feature 16 — 桌面编辑模式背景遮罩透明化 (com.android.launcher):
    // ToggleBarState/PagePreviewState 原生把编辑态壁纸 blur 固定为 1.0f, 同时可能叠加页面背景 alpha。
    public static final String KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED = "edit_mode_bg_transparent_enabled";
    // 弱化桌面个性化页背景(com.oplus.wallpapers): 从 uiautomator 可见页面为
    // com.oplus.wallpapers.themes.edit.ThemeEditActivity, 背景由 background_wallpaper 与
    // background_wallpaper_mask 两个全屏 ImageView 叠加; 开启后改为 #0A0C10 深蓝黑。
    public static final String KEY_WEAKEN_DESKTOP_CUSTOMIZATION_BG_ENABLED =
            "weaken_desktop_customization_bg_enabled";
    // Feature 10 — 合并控制中心背景 scrim 亮度 (com.android.systemui)
    public static final String KEY_QS_SCRIM_TRANSLUCENT_ENABLED = "qs_scrim_translucent_enabled";
    // 背景亮度滑条键(0-20, 默认 0): 0=全黑, 20=系统默认 lumin(不压暗)。
    public static final String KEY_QS_SCRIM_BRIGHTNESS = "qs_scrim_brightness";
    // 控制中心背景模糊半径: 界面滑条以 10 为刻度单位, 取 0-QS_BLUR_RADIUS_MAX,
    // 实际写入 BlurConfig.blurRadius 时乘 QS_BLUR_RADIUS_SCALE, 即 0-80 对应 0-800。
    // 最终半径 = blurRadius * blurAmount(展开进度), 故原生"随下拉逐渐变模糊"的行为保留。
    // 系统默认取自 R.integer: 旧版 blur_radius_platform = 800, 新版 blur_radius_platform_config
    // = 450。滑条默认 40(即 400)。
    public static final String KEY_QS_BLUR_RADIUS_ENABLED = "qs_blur_radius_enabled";
    public static final String KEY_QS_BLUR_RADIUS = "qs_blur_radius";
    public static final int QS_BLUR_RADIUS_DEFAULT = 40;
    public static final int QS_BLUR_RADIUS_MAX = 80;
    public static final int QS_BLUR_RADIUS_SCALE = 10;
    // 控制中心背景缩小幅度: 滑条是"相对系统默认缩小量的百分比", 100=系统默认, 50=系统的一半。
    // 系统原始缩小量为 1-mirrorScale(完全展开时 mirrorScale=0.9, 即缩小 10%),
    // 这里把缩小量乘以 ratio 后写回 mirrorScale, 从而保留"随下拉逐渐缩小"的动画。
    public static final String KEY_QS_BLUR_SCALE_ENABLED = "qs_blur_scale_enabled";
    public static final String KEY_QS_BLUR_SCALE = "qs_blur_scale";
    public static final int QS_BLUR_SCALE_DEFAULT = 50;
    public static final int QS_BLUR_SCALE_MAX = 100;
    // 控制中心 WLAN/蓝牙 名称单行省略: 可伸缩 tile 的次级名称(SSID / 蓝牙设备名)承载在
    // labelDesc(TextSwitcher, R.id.tile_label_desc), 由 updateLabelDescText 经 TextSwitcherExtKt
    // .setContent 写入; 这里在每次 setContent 之后强制单行 + 行尾省略号。
    public static final String KEY_QS_TILE_NAME_ELLIPSIS_ENABLED = "qs_tile_name_ellipsis_enabled";
    // 控制中心 Wi-Fi / 蓝牙 / 音量 / 亮度 圆角:
    // 系统用 FlavorTwoFeatureOption.isFlavorTwoDeviceExp()(= 一加品牌 && 海外 exp 区域)判定 OxygenOS,
    // 命中时把高亮磁贴(Wi-Fi/蓝牙)与滑条(音量/亮度)的圆角换成
    // R.dimen.qs_hl_tile_corner_radius_circle_oneplus(60dp), 其余用 qs_hl_tile_corner_radius_circle(16dp)。
    // 开关开启时统一强制到 QS_CORNER_RADIUS_DIMEN 指定的那一档(合并式与分离式都生效)。
    public static final String KEY_QS_NORMAL_CORNER_RADIUS_ENABLED = "qs_normal_corner_radius_enabled";
    // 分离版控制中心左右切换取消切入效果: 通知中心/控制中心之间左右滑动时直接平移而非切变。
    public static final String KEY_QS_PANEL_SWITCH_NO_CUT_ENABLED = "qs_panel_switch_no_cut_enabled";
    // 合并控制中心时间日期取消展开动画: 一次下拉(fraction=0)时页脚时间与日期处于"小字号 + 未位移"
    // 的初始态, 继续展开时系统把它们放大到约 2 倍并平移到新位置(见 QsHooks#hookQsClockNoExpandAnim)。
    public static final String KEY_QS_CLOCK_NO_EXPAND_ANIM_ENABLED = "qs_clock_no_expand_anim_enabled";
    // false = 强制普通圆角(默认, 即本功能的正常行为); 改为 true 可强制 OxygenOS 大圆角, 用于确认注入是否生效。
    public static final boolean QS_CORNER_RADIUS_FORCE_ONEPLUS = false;
    public static final String QS_CORNER_RADIUS_DIMEN = QS_CORNER_RADIUS_FORCE_ONEPLUS
            ? "qs_hl_tile_corner_radius_circle_oneplus" : "qs_hl_tile_corner_radius_circle";
    // 圆角轮廓 provider 与构造入口(QSConstant#getSmoothRoundRectOutlineProvider)。
    public static final String QS_OUTLINE_PROVIDER_CLASS =
            "com.oplusos.systemui.common.outline.RoundRectOutlineProvider";
    public static final String QS_CONSTANT_CLASS = "com.oplus.systemui.qs.base.res.util.QSConstant";
    // Feature 17 — 流体云出现时不隐藏电量百分比:
    // 系统在流体云胶囊出现时会令 PercentOutIcon.isVisible=false, 隐藏电量百分比数字。
    // hook BatteryViewBinder.bind$updatePercentOutView, 强制 isVisible=true。
    public static final String KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED = "fluid_cloud_keep_percent_enabled";
    // 悬浮小窗贴边挂机: 拖到边缘松手时系统把窗口缩成边缘竖条并把任务切后台, 这里在 to-float 结束后
    // moveToFront 拉回前台; 不能在提交中途拦截 —— 会触发 "Input dispatching timed out" ANR。
    // 需把模块作用域加入 "android"(system_server), 旧版 SystemUI 内 hook 路径已废弃。
    public static final String KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED = "float_window_edge_hang_enabled";
    // 贴边挂机静音: 挂机时经系统多应用音量通道把该应用音量置 0, 回到前台时恢复原值。
    public static final String KEY_FLOAT_WINDOW_EDGE_HANG_MUTE_ENABLED =
            "float_window_edge_hang_mute_enabled";
    // 小窗贴边显示为白色竖条: 浮窗贴边把手去掉应用图标, 只保留一个带圆角的白色竖条, 距屏幕边缘 8dp。
    // 作用于 system_server(android 作用域)内的 FloatHandleView, 需重启 zygote 才生效。
    public static final String KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED =
            "float_window_edge_hang_white_bar_enabled";
    // 横屏应用小窗保持比例: 系统对横屏应用硬编码 ratio=0.5625f(9:16), 与设备真实比例不符,
    // 这里在 system_server 内接管该 ratio 与 launchBounds, 让小窗 宽:高 = 屏幕 高:宽。
    public static final String KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED =
            "float_window_landscape_keep_ratio_enabled";
    // 优化小窗贴边位置及最大尺寸: 缩到最小贴边时只留 FLOAT_WINDOW_SIDE_MARGIN_DP, 放大上限 =
    // 屏幕宽度 - 2 * FLOAT_WINDOW_SIDE_MARGIN_DP(系统默认为 20dp 边距 + 屏宽 - 48dp 的上限)。
    public static final String KEY_FLOAT_WINDOW_EDGE_SIZE_OPTIMIZE_ENABLED =
            "float_window_edge_size_optimize_enabled";
    // 小窗放大到最大时, 左右各保留的边距(dp): 最大宽度 = 屏幕宽度 - 2 * FLOAT_WINDOW_SIDE_MARGIN_DP。
    public static final float FLOAT_WINDOW_SIDE_MARGIN_DP = 8f;
    public static final String KEY_GESTURE_BAR_HEIGHT_ENABLED = "gesture_bar_height_enabled";
    public static final String KEY_GESTURE_BAR_HEIGHT_DP = "gesture_bar_height_dp";
    public static final String KEY_GESTURE_BAR_WIDTH_ENABLED = "gesture_bar_width_enabled";
    public static final String KEY_GESTURE_BAR_WIDTH_DP = "gesture_bar_width_dp";
    public static final String KEY_MBACK_ENABLED = "mback_enabled";
    // 恢复原生旋转按钮位置: 按钮落在「当前屏幕底边」与「建议旋转方向下屏幕底边」的夹角
    // (当前屏幕的左下角或右下角), 而非系统恒定的左下角。
    public static final String KEY_ROTATION_BUTTON_FIXED_POSITION_ENABLED =
            "rotation_button_fixed_position_enabled";
    public static final String KEY_GESTURE_TOUCH_THROUGH_ENABLED =
            "gesture_touch_through_enabled";
    public static final String KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED =
            "gesture_bar_long_press_disable_enabled";
    // 缩小分屏应用顶部三点控制栏：pscanvas 侧缩小浮层本身，system_server 侧同步缩短嵌入任务
    // 收到的状态栏 inset，否则三点虽变小，应用内容仍会为原 40dp 控制栏留白。
    public static final String KEY_SHRINK_CAPTION_BAR_ENABLED = "shrink_caption_bar_enabled";
    public static final float COMPACT_CAPTION_BAR_HEIGHT_DP = 24f;
    // 多任务上划彻底结束进程: 上划卡片时系统只以 type=13(STOP) 请求 athena 停止任务,
    // 开启后改成 type=11(KILL_OR_STOP) 真正杀掉进程(见 LauncherHooks#hookRecentsSwipeUpKill)。
    public static final String KEY_RECENTS_SWIPE_UP_KILL_ENABLED = "recents_swipe_up_kill_enabled";
    // 多任务隐藏未在运行的应用: 只保留还在运行的任务卡片。判据是 android.app.TaskInfo#isRunning
    // (PUBLIC boolean, system_server 侧 Task#fillTaskInfo 里 info.isRunning = (top != null),
    //  即任务是否还有存活的 Activity), 见 LauncherHooks#hookRecentsHideNotRunning。
    public static final String KEY_RECENTS_HIDE_NOT_RUNNING_ENABLED =
            "recents_hide_not_running_enabled";
    // 划掉主任务时一并清空附属任务: 附属任务(如微信小程序)是同包下的另一个独立任务
    // (独立进程), 划掉主任务不会带走它们; 开启后连同任务一起移除并强杀。
    // 主/附属用该包全部 launcher 入口判定, 不硬编码任何应用(见 LauncherHooks#isMainTask)。
    public static final String KEY_RECENTS_SWIPE_UP_KILL_SUBSIDIARY_ENABLED =
            "recents_swipe_up_kill_subsidiary_enabled";
    // 从桌面隐藏指定的单个 LAUNCHER 活动: 系统"隐藏应用"按包隐藏会误伤多入口应用(如电话本+拨号),
    // 故只过滤目标组件。配置表见 HIDDEN_LAUNCHER_TARGETS: { 门控偏好键, 包名, 活动类名 }。
    public static final String KEY_HIDE_CONTACTS_ENABLED = "hide_contacts_enabled";
    public static final String KEY_HIDE_GBOARD_ENABLED = "hide_gboard_enabled";
    // Feature 15 — 隐藏 GhostLock 图标(com.ghostlock.app): 已有 root 时无需再 root。
    public static final String KEY_HIDE_GHOSTLOCK_ENABLED = "hide_ghostlock_enabled";
    // 解锁时关机无需校验密码(com.android.systemui): 系统"关机校验密码"(Settings.Secure
    // oplus_shutdown_need_verification_password) 开启后, 电源菜单里关机/重启都会先弹凭据校验;
    // 唯一闸门是 ShutdownBiometricPrompt.isEnable(Context), 设备已解锁时返回 false 跳过校验。
    public static final String KEY_UNLOCKED_SHUTDOWN_NOVERIFY_ENABLED =
            "unlocked_shutdown_noverify_enabled";
    // 取消解锁界面控件光效(com.android.systemui): COUI 给锁屏密码控件叠了三类非纯色绘制,
    // 去掉后只剩背景填充色与描边(纯色), 按下时的缩放/变色反馈不受影响。
    public static final String KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED =
            "keyguard_no_light_effect_enabled";
    // 自定义密码界面背景亮度: bouncer 背景 = 模糊壁纸 + 平台混色, 混色 top 为 LUMINOSITY+#99262626,
    // 把亮度归一化到 RGB 0x26, 相当于给模糊加"最低亮度", 表现为一层去不掉的遮罩。
    // 实测它并非遮罩 view(三块 scrim alpha 均为 0), 改这个 MixColor 才是正解。
    public static final String KEY_KEYGUARD_BOUNCER_BRIGHTNESS_ENABLED =
            "keyguard_bouncer_brightness_enabled";
    // 亮度滑条键(0-5, 默认 0): 0=全黑(去掉系统抬的最低亮度), 5=系统默认 lumin(0x26=38)。
    public static final String KEY_KEYGUARD_BOUNCER_BRIGHTNESS = "keyguard_bouncer_brightness";
    // 自定义桌面长按背景亮度(com.android.launcher): 长按图标弹出菜单时, 菜单后面的背景是
    // "模糊壁纸 ONLY_MASK 混入 popup_blur_blend_color(#4d1c2634)" 的结果, 相当于给背景加了
    // 一层去不掉的"最低亮度"(纯黑壁纸也被抬成约 (8.5,11.5,15.7))。
    public static final String KEY_DESKTOP_POPUP_BG_BRIGHTNESS_ENABLED =
            "desktop_popup_bg_brightness_enabled";
    // 长按菜单背景动态模糊: 把系统的"静态模糊壁纸 + ALPHA 渐显"改成"清晰壁纸 + 高斯模糊半径渐进"。
    public static final String KEY_POPUP_DYNAMIC_BLUR_ENABLED = "popup_dynamic_blur_enabled";

    // 亮度滑条键(0-10, 默认 0): 0=去掉系统抬的最低亮度, 10=系统默认效果。
    public static final String KEY_DESKTOP_POPUP_BG_BRIGHTNESS = "desktop_popup_bg_brightness";
    // 锁屏通知区域下移(com.android.systemui): 锁屏上通知区顶部位置有三个来源(见
    // SystemUiHooks#hookKeyguardNotificationOffset), 三处统一叠加同一下移量。
    public static final String KEY_KEYGUARD_NOTIFICATION_OFFSET_ENABLED =
            "keyguard_notification_offset_enabled";
    public static final String KEY_KEYGUARD_NOTIFICATION_OFFSET_DP =
            "keyguard_notification_offset_dp";
    public static final int KEYGUARD_NOTIFICATION_OFFSET_DP_DEFAULT = 20;
    public static final int KEYGUARD_NOTIFICATION_OFFSET_DP_MAX = 40;
    // 输入密码界面支持侧滑或下滑返回: 允许键盘区下滑手势穿透到 bouncer 容器收起返回锁屏,
    // 放行系统侧滑返回手势; 并把"上滑使用指纹解锁"提示改为"下滑返回指纹解锁"。
    // 状态栏歌词: 数据源是 ColorOS 媒体接口的 metadata.lyricInfo, 无需注入音乐软件/伪装机型。
    public static final String KEY_STATUSBAR_LYRIC_ENABLED = "statusbar_lyric_enabled";
    // 第三方状态栏歌词避让: 第三方悬浮窗左上角进入状态栏区域时隐藏系统时钟。
    public static final String KEY_STATUSBAR_LYRIC_AVOID_THIRD_PARTY_ENABLED =
            "statusbar_lyric_avoid_third_party_enabled";
    // system_server 窗口变化通知序号, 仅作为 SystemUI ContentObserver 的事件信号。
    public static final String KEY_STATUSBAR_OVERLAY_EVENT =
            "colorosmod_statusbar_overlay_event";
    // 控制中心蓝牙磁贴显示降噪控制: 有可控制的降噪耳机时把蓝牙磁贴改成三段式(降噪/关闭/通透),
    // 复用系统三段式静音磁贴的整套渲染, 状态走欢律 EarphoneControlProvider。见 AncTileHooks。
    public static final String KEY_ANC_TILE_ENABLED = "anc_tile_enabled";

    // 系统设置"通知栏显示方式"(Settings.Secure, StatusBarSettingsValueProxy#KEY_NOTIFICATION_PROMPT_MODE)。
    // 通知图标区显示模式的下发由 NotificationHooks 统一负责, 状态栏歌词显示时用它强制"显示数字"。
    public static final String SETTINGS_KEY_NOTIFICATION_PROMPT_MODE = "notification_prompt_mode";
    public static final int NOTIFICATION_PROMPT_SHOW_ICON = 0;
    public static final int NOTIFICATION_PROMPT_SHOW_NUMBER = 1;

    public static final String KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED = "keyguard_bouncer_swipe_back_enabled";
    // 密码支持滑动输入: 手指进入某数字键"中间 2/3 半径"的圆形区域即视为按下该键, 立即输入并
    // 显示按下态; 离开该键范围则取消(不重复输入)。见 SystemUiHooks#hookKeyguardSlideInput ——
    // 接管 COUINumericKeyboard 的 handleActionDown/Move/Up, 改"矩形命中+抬起才输入"为"圆形命中+进入即输入"。
    public static final String KEY_KEYGUARD_SLIDE_INPUT_ENABLED = "keyguard_slide_input_enabled";
    // 系统相机 Find 界面总开关: 0=不修改, 1=开启, 2=关闭。
    public static final String KEY_CAMERA_FIND_LIGHT_STYLE = "camera_find_light_style";
    // 系统相机哈苏橙色 UI 总开关: 0=不修改, 1=开启, 2=关闭。
    public static final String KEY_CAMERA_HASSELBLAD_ORANGE_UI =
            "camera_hasselblad_orange_ui";
    public static final int CAMERA_FIND_LIGHT_STYLE_DEFAULT = 0;
    public static final int CAMERA_FIND_LIGHT_STYLE_ENABLED = 1;
    public static final int CAMERA_FIND_LIGHT_STYLE_DISABLED = 2;
    public static final String KEY_KEYGUARD_NO_CHARGE_ANIM_ENABLED =
            "keyguard_no_charge_anim_enabled";

    // 跨进程读取开关用的应用 Context(被 hook 进程自身)与 ContentProvider 通道所需的字段。
    public static volatile android.content.Context sAppContext;
    public static final String SETTINGS_AUTHORITY = "com.rikumi.colorosmod.settings";
    // 开关值缓存 TTL。长按/拖拽期间会以触摸事件频率反复 readBool, TTL 过短会频繁触发同步
    // ContentProvider IPC 造成主线程卡顿; 5s 内连续读取全部命中内存缓存(零 IPC), 代价是最多 5s 延迟。
    public static final long CACHE_TTL_MS = 5000;
    public static final java.util.concurrent.ConcurrentHashMap<String, Object[]> sCache =
            new java.util.concurrent.ConcurrentHashMap<String, Object[]>(); // key -> {Long ts, Boolean val}

    // ---- 后台设置同步(受控 worker + ContentObserver push model) ----
    // 不再使用固定间隔轮询, 也不再为每次 onChange 临时 new Thread。改为一个受控的单 worker
    // (HandlerThread + ExecutorService 语义), 统一执行 "首次预热/失败重试" 与 "设置变更后的刷新",
    // 并对连续变更做 coalesce(合并, 见 ONCHANGE_COALESCE_MS), 避免 slider 连续写入时每次拉一次。
    //
    // 流程:
    //   startSettingsLoader:
    //     同步仅 registerContentObserver(不阻塞、不做查询)
    //     后台 worker 做 initial fetch; 失败则 SETTINGS_RETRY_MS 退避重试, 成功一次后停。
    //   onChange(observer 的回调, 已投递到 worker 线程):
    //     投递一次 refresh; 若已有 pending refresh 则合并, 不重复执行。
    //
    // hot reload 时:
    //   onHotReloading():
    //     active=false; unregister observer; worker.quitSafely(); join 等待真正结束;
    //     若等待超时/线程仍存活 -> 返回 false(拒绝 reload, 而不是带着存活线程继续)。
    public static final String SETTINGS_ALL_KEY = "__all__";
    private static final Object sLoadLock = new Object();
    private static volatile java.util.Map<String, Integer> sSnapshot =
            java.util.Collections.emptyMap();
    private static volatile boolean sSettingsLoaded = false;
    private static volatile boolean sFirstWaitDone = false;
    // 重试退避: 启动时 500ms, 指数增长到 30s, 避免失败情况下永久 2Hz 轮询。
    private static final long RETRY_MIN_MS = 500;
    private static final long RETRY_MAX_MS = 30_000;
    private static long sRetryMs = RETRY_MIN_MS;
    // 首个 readBool/readInt 到达时后台预热可能还没完成, 最多等这么久; 整个进程只等一次。
    private static final long FIRST_LOAD_WAIT_MS = 5000;
    // 连续 onChange 的合并窗口: 窗口内多次通知只触发一次 refresh, 防止 slider 高频写入刷屏。
    private static final long ONCHANGE_COALESCE_MS = 250;
    // 已注册的 ContentObserver。
    private static volatile android.database.ContentObserver sObserver = null;
    // 单 worker 的 HandlerThread; null 表示未创建(首次 startSettingsLoader 时才建)。
    private static volatile android.os.HandlerThread sWorkerThread = null;
    private static volatile android.os.Handler sWorkerHandler = null;
    // 当前这一轮 refresh 是否已在执行或排队; 用于合并连续通知。
    private static volatile boolean sRefreshQueued = false;
    // hot reload / stop 标记: 一旦置 false, 所有已投递的 refresh 都会立即放弃, 不再触碰旧 gen 状态。
    private static volatile boolean sSyncActive = false;
    private static final long STOP_JOIN_TIMEOUT_MS = 1500;

    public static void startSettingsLoader() {
        if (sWorkerThread != null && sWorkerThread.isAlive()) return;
        sSyncActive = true;
        sRefreshQueued = false;
        sRetryQueued = false;
        sObserverRetryQueued = false;
        sRetryMs = RETRY_MIN_MS;
        android.os.HandlerThread ht = new android.os.HandlerThread("ColorOSMod-SettingsWorker");
        ht.start();
        sWorkerThread = ht;
        sWorkerHandler = new android.os.Handler(ht.getLooper());
        sWorkerHandler.post(new Runnable() {
            @Override
            public void run() {
                registerSettingsObserverOnWorker();
                initialOrRetryFetch();
            }
        });
    }

    // 必须在 worker 线程调用: 注册 ContentObserver, callback handler = sWorkerHandler。
    private static boolean registerSettingsObserverOnWorker() {
        if (sObserver != null) return true;
        if (sAppContext == null) return false;
        try {
            ContentResolver cr = sAppContext.getContentResolver();
            Uri uri = Uri.parse("content://" + SETTINGS_AUTHORITY + "/" + SETTINGS_ALL_KEY);
            android.database.ContentObserver observer = new android.database.ContentObserver(
                    sWorkerHandler) {
                @Override
                public void onChange(boolean selfChange, android.net.Uri u) {
                    requestRefresh();
                }
            };
            cr.registerContentObserver(uri, true, observer);
            sObserver = observer;
            log("settings content observer registered");
            return true;
        } catch (Throwable t) {
            log("registerContentObserver fail: " + t);
            sObserver = null;
            return false;
        }
    }

    // 独立获取 Application Context。将"准备 Context"与"读取设置"分离, 使 observer 注册
    // 可以在 fetch 之前完成, 消除 register → fetch 之间的 lost-update window。
    private static boolean ensureAppContext() {
        if (sAppContext == null) {
            sAppContext = currentApplication();
        }
        return sAppContext != null;
    }

    // ---- 统一 fetch + retry 逻辑 ----
    // 两路调用:
    //   1) initialOrRetryFetch — 进程启动时的初始预热, allowRetry=true, coalesce=false
    //   2) requestRefresh    — ContentObserver 收到变更通知, allowRetry=true, coalesce=true
    // 两个路径走同一套 fetch / observer / retry 判断, 避免 behavior 产生差异。

    // 启动时首次预热 (post 至 worker queue), 无 coalesce。
    private static void initialOrRetryFetch() {
        refreshSettings(true);
    }

    // 收到设置变更通知 (已在 worker 线程): 合并多次通知为一次 refresh。
    private static void requestRefresh() {
        android.os.Handler h = sWorkerHandler;
        if (h == null || !sSyncActive || sRefreshQueued) return;
        sRefreshQueued = true;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                sRefreshQueued = false;
                if (!sSyncActive) return;
                refreshSettings(true);
            }
        }, ONCHANGE_COALESCE_MS);
    }

    // 统一 fetch/retry: 先 subscribe 再 snapshot; 失败时指数退避重试。
    // 区分"snapshot 成功但 observer 失败"与"snapshot 失败", 前者只重试 observer(无 full query)。
    private static void refreshSettings(boolean allowRetry) {
        if (!sSyncActive) return;
        if (!ensureAppContext()) {
            if (allowRetry) scheduleRetry();
            return;
        }
        boolean observerReady = registerSettingsObserverOnWorker();
        java.util.Map<String, Integer> all = allowRetry ? fetchAllSettings() : null;
        if (all != null) {
            publishSnapshot(all);
        }
        if (!allowRetry || !sSyncActive) return;
        if (all != null && !observerReady) {
            // snapshot 已有但 observer 注册失败: 仅重试 observer, 不重新 full query。
            scheduleObserverRetry();
        } else if (all == null || !observerReady) {
            // fetch 失败或 observer 失败(此时 all==null): 启动指数退避重试 full path。
            scheduleRetry();
        } else {
            // 全部成功, 重置退避。
            sRetryMs = RETRY_MIN_MS;
        }
    }

    // 单进程内同时只允许一个 retry timer, 防止 slider 连续失败时产生多条 retry chain。
    private static volatile boolean sRetryQueued = false;
    private static volatile boolean sObserverRetryQueued = false;

    private static void scheduleRetry() {
        android.os.Handler h = sWorkerHandler;
        if (h == null || !sSyncActive || sRetryQueued) return;
        sRetryQueued = true;
        long delay = sRetryMs;
        sRetryMs = Math.min(sRetryMs * 2, RETRY_MAX_MS);
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                sRetryQueued = false;
                if (sSyncActive) {
                    refreshSettings(true);
                }
            }
        }, delay);
    }

    // snapshot 已成功但 observer 仍失败: 只重试 observer 注册, 不再重新 full query。
    // 恢复成功后必须重新 fetch snapshot(恢复期间 notification 已丢失), 否则回到旧值。
    private static void scheduleObserverRetry() {
        android.os.Handler h = sWorkerHandler;
        if (h == null || !sSyncActive || sObserverRetryQueued) return;
        sObserverRetryQueued = true;
        long delay = sRetryMs;
        sRetryMs = Math.min(sRetryMs * 2, RETRY_MAX_MS);
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                sObserverRetryQueued = false;
                if (!sSyncActive) return;
                if (registerSettingsObserverOnWorker()) {
                    // observer 恢复成功: 重新拉全量, 补上恢复期间丢失的变更。
                    sRetryMs = RETRY_MIN_MS;
                    java.util.Map<String, Integer> all = fetchAllSettings();
                    if (all != null) {
                        publishSnapshot(all);
                    } else {
                        // fetch 也失败 → 切到 full retry
                        scheduleRetry();
                    }
                } else if (sSyncActive) {
                    scheduleObserverRetry();
                }
            }
        }, delay);
    }

    private static void publishSnapshot(java.util.Map<String, Integer> all) {
        if (!sSyncActive || all == null) return;
        boolean first = !sSettingsLoaded;
        sSnapshot = all;
        if (first) {
            synchronized (sLoadLock) {
                sSettingsLoaded = true;
                sLoadLock.notifyAll();
            }
            log("settings loaded: " + all.size() + " keys");
        }
    }

    // 停止所有 module-owned 线程与 observer。通过 post barrier 到 worker 队列来确认没有
    // 正在执行的 query/message; 若 barrier 在超时内执行则 worker quiescent, 可以安全 quit;
    // 若超时则拒绝 reload 且不伤害 worker(不调 quitSafely), 旧 gen 可完整恢复运行。
    private static boolean stopSettingsLoader() {
        android.os.Handler wh = sWorkerHandler;
        android.os.HandlerThread ht = sWorkerThread;
        if (ht == null || wh == null) return true;
        sSyncActive = false;
        sRefreshQueued = false;
        sRetryQueued = false;
        sObserverRetryQueued = false;
        android.database.ContentObserver o = sObserver;
        if (o != null && sAppContext != null) {
            try {
                sAppContext.getContentResolver().unregisterContentObserver(o);
            } catch (Throwable ignored) {
            }
        }
        sObserver = null;
        wh.removeCallbacksAndMessages(null);
        // barrier: 投递一个不可中断的标记到 worker 队列末尾; 只有当所有已执行或正在执行的
        // message(包括卡在 Binder query 之前的)结束后, barrier 才会执行。
        final java.util.concurrent.CountDownLatch idle =
                new java.util.concurrent.CountDownLatch(1);
        if (!wh.post(new Runnable() {
            @Override
            public void run() {
                idle.countDown();
            }
        })) {
            // Looper 已退出, 不应发生(我们没有调 quit)。
            return true;
        }
        boolean quiescent;
        try {
            quiescent = idle.await(STOP_JOIN_TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            quiescent = false;
        }
        if (!quiescent) {
            // worker 还卡在 in-flight query 中, barrier 未能执行 → 拒绝 reload。
            // 关键: 没有调 quitSafely, worker Looper 正常运行, 可完全恢复。
            sSyncActive = true;
            // 重新注册 observer + 重试初始 fetch
            wh.post(new Runnable() {
                @Override
                public void run() {
                    registerSettingsObserverOnWorker();
                    initialOrRetryFetch();
                }
            });
            log("ColorOSMod-SettingsWorker barrier timeout, rejecting reload");
            return false;
        }
        // barrier 已执行, 所有先前的 work 已完成; handler queue 已清空且无 in-flight query,
        // 直接 quit (不需要 quitSafely drain 剩余 message), 循环 join 直到线程真正死亡。
        ht.quit();
        boolean interrupted = false;
        while (ht.isAlive()) {
            try {
                ht.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        sWorkerThread = null;
        sWorkerHandler = null;
        return true;
    }

    // 一次性取回全部设置; 取不到(模块 App 未运行等)返回 null。
    private static java.util.Map<String, Integer> fetchAllSettings() {
        try {
            if (sAppContext == null) {
                sAppContext = currentApplication();
            }
            if (sAppContext == null) return null;
            ContentResolver cr = sAppContext.getContentResolver();
            Uri uri = Uri.parse("content://" + SETTINGS_AUTHORITY + "/" + SETTINGS_ALL_KEY);
            Cursor c = cr.query(uri, null, null, null, null);
            if (c == null) return null;
            try {
                int ki = c.getColumnIndex("k");
                int vi = c.getColumnIndex("v");
                if (ki < 0 || vi < 0) return null;
                java.util.Map<String, Integer> out = new java.util.HashMap<String, Integer>();
                while (c.moveToNext()) out.put(c.getString(ki), c.getInt(vi));
                return out;
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // 预热未完成时的有限等待: 只在进程内首次读取时发生, 且无论是否等到都不再等第二次。
    private static void ensureFirstLoad() {
        if (sSettingsLoaded || sFirstWaitDone) return;
        synchronized (sLoadLock) {
            if (sSettingsLoaded || sFirstWaitDone) return;
            long deadline = System.currentTimeMillis() + FIRST_LOAD_WAIT_MS;
            try {
                while (!sSettingsLoaded) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    sLoadLock.wait(left);
                }
            } catch (InterruptedException ignored) {
            } finally {
                sFirstWaitDone = true;
            }
        }
    }

    public static final int ICON_GAP_DP = 4;
    public static final int INDICATOR_REDUCE_DP = 16; // requested page-to-Dock gap reduction in dp
    public static final int QS_FOOTER_MARGIN_DP = 8; // smaller top gap for footer (date/settings) so it sinks a little
    public static final float SUBTITLE_ORIG_SP = 24f; // system default subtitle text size
    public static final int SUBTITLE_REDUCE_SP_DEFAULT = 8; // default reduction (24sp -> 16sp); slider 0..2x
    public static final float SUBTITLE_OFFSET_DP = 8f; // move subtitle up & right by 8dp each (at default reduction)
    public static final int SUBTITLE_PAD_DP = 4; // extra top & bottom padding for the subtitle tv (at default reduction)
    public static final int NOTIFICATION_PADDING_DP = 4; // extra top & bottom padding for non-minimized (non-silent) notifications
    // 控制中心背景亮度: 默认 0(全黑); 系统默认 lumin 的 RGB 值为 0x33(51), 对应滑条 20。
    public static final int QS_SCRIM_BRIGHTNESS_DEFAULT = 0;
    public static final int QS_SCRIM_LUMIN_MAX = 0x33;
    // 密码界面背景亮度: 默认 0(全黑, 即去掉系统给模糊加的最低亮度); 上限 5 = 系统默认效果。
    // 实现按 overColor RGB 的比例缩放, 无需硬编码目标亮度。
    public static final int KEYGUARD_BOUNCER_BRIGHTNESS_DEFAULT = 0;
    public static final int KEYGUARD_BOUNCER_BRIGHTNESS_MAX = 5;
    // 桌面长按背景亮度: 默认 0(去掉系统给模糊背景加的最低亮度); 上限 10 = 系统默认效果。
    // 系统用 popup_blur_blend_color(#4d1c2634) 以 ONLY_MASK 混入模糊壁纸, 亮度滑条缩放的是
    // 这层混合量, 与密码界面背景亮度同一套"缩放系统抬的最低亮度"口径。
    public static final int DESKTOP_POPUP_BG_BRIGHTNESS_DEFAULT = 0;
    public static final int DESKTOP_POPUP_BG_BRIGHTNESS_MAX = 10;

    // 调试日志: 仅用 Log.e(error 级别), 因为 ColorOS 会丢弃 Log.d/v/i/w 等非 error 日志。
    // 不触碰外部存储, 避免被 hook 的第三方进程(如桌面 com.android.launcher)因无存储权限而
    // 触发 MediaProvider(FUSE) 的 SecurityException 刷屏。
    public static void dbg(String msg) {
        Log.e(TAG, msg);
    }

    // 仅输出到 logcat(Log.e), 不写文件, 避免 IO 卡顿。
    // 注意: 这里必须是 Log.e —— 曾因被清空实现导致所有 HOOK OK/FAIL 与异常静默丢失,
    // 无法判断 hook 是否命中, 直接造成多轮盲改。禁止再把方法体清空。
    public static void log(String msg) {
        Log.e(TAG, msg);
    }

    // ---- 生命周期: 新版 API 把"包加载"与"system_server 启动"分成两个回调 ----

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        // 必须先拿到框架接口, 否则兼容层无法挂钩(见 XposedBridge#attachFramework)。
        XposedBridge.attachFramework(this);
        sProcessName = param.getProcessName();
        sIsSystemServer = param.isSystemServer();
        log("module loaded: framework=" + getFrameworkName() + " v" + getFrameworkVersion()
                + " api=" + getApiVersion() + " process=" + sProcessName);
    }

    // API 102 hot reload: 返回 true 之前必须停止所有 module-owned thread、注销 callback、释放旧
    // classloader 的引用, 否则旧 classloader 会被后台线程/observer 回调整代强引用住, 无法 GC。
    // 若无法在超时内干净停止所有 worker, 返回 false 拒绝 reload, 由框架保持旧 gen 继续运行。
    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        if (!stopSettingsLoader()) {
            log("onHotReloading: WARN worker refused to stop within timeout, rejecting reload");
            return false;
        }
        sSnapshot = java.util.Collections.emptyMap();
        sCache.clear();
        sAppContext = null;
        log("onHotReloading: clean stop, OK to reload");
        return true;
    }

    // hot reload 后框架不会自动 replay onModuleLoaded/onPackageReady。HotReloadedParam 继承
    // ModuleLoadedParam, 提供了 getProcessName() 和 isSystemServer() —— 这里从 param 恢复
    // 进程身份, 否则新 generation 的 sProcessName=""、sIsSystemServer=false, hooks 全挂不上。
    //
    // 必须调用 super.onHotReloaded(param) 卸载旧 generation 的 hooks, 否则旧 hooks 与新 hooks
    // 叠加同一方法, 产生重复 callback。
    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        // 重要: 必须先卸载旧 hooks, 再装载新的, 否则同 method 叠加多套 callback。
        super.onHotReloaded(param);

        XposedBridge.attachFramework(this);
        // 重要: HotReloadedParam 就是 ModuleLoadedParam 的子类, 恢复进程身份, 否则新 hooks 挂不上。
        sProcessName = param.getProcessName();
        sIsSystemServer = param.isSystemServer();
        log("onHotReloaded: process=" + sProcessName + " isSystemServer=" + sIsSystemServer);

        // 重新注册 settings observer + worker(新 gen 的 sWorkerThread==null, 可安全重新 start)。
        startSettingsLoader();

        // 重新执行当前进程的初始化。注意: app 进程的目标 packageName = app.getPackageName(),
        // 而非 sProcessName(process name 不保证等于 package name, 如 com.android.systemui 的
        // 进程名就是 com.android.systemui 本身, 但自定义进程或共享 uid 时可能不符)。
        if (sIsSystemServer) {
            SystemServerHooks.hookFloatWindowEdgeHangSystemServer(systemServerLpparam());
            SystemServerHooks.hookFloatWindowEdgeHangMute(systemServerLpparam());
            SystemServerHooks.hookFloatWindowLandscapeKeepRatio(systemServerLpparam());
            SystemServerHooks.hookFloatWindowSizeLimits(systemServerLpparam());
            SystemServerHooks.hookRecentsSwipeUpKillSystemServer(systemServerLpparam());
            SystemServerHooks.hookStatusBarThirdPartyOverlayEvents(systemServerLpparam());
            SystemServerHooks.hookCompactCanvasCaptionInsets(systemServerLpparam());
            SystemServerHooks.hookCompactFlexibleCaptionBar(systemServerLpparam());
            sSystemServerHooked = true;
        } else {
            android.content.Context app = currentApplication();
            if (app != null) {
                String pkg = app.getPackageName();
                if (isAppHookTarget(pkg)) {
                    XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam();
                    lpparam.packageName = pkg;
                    lpparam.processName = sProcessName;
                    lpparam.classLoader = app.getClassLoader();
                    lpparam.appInfo = app.getApplicationInfo();
                    lpparam.isFirstApplication = true;
                    handleLoadPackage(lpparam);
                    // 只在 hooks 真正安装后才标记, 避免 Application 未就绪时错误跳过后续 onPackageReady。
                    sAppProcessHooked = true;
                }
            }
        }
    }

    // 重建 system_server 用的 LoadPackageParam。
    private static XC_LoadPackage.LoadPackageParam systemServerLpparam() {
        XC_LoadPackage.LoadPackageParam lpp = new XC_LoadPackage.LoadPackageParam();
        lpp.packageName = "android";
        lpp.processName = sProcessName;
        lpp.classLoader = java.lang.ClassLoader.getSystemClassLoader();
        lpp.isFirstApplication = true;
        return lpp;
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam();
        lpparam.packageName = param.getPackageName();
        lpparam.processName = sProcessName;
        lpparam.classLoader = param.getClassLoader();
        lpparam.appInfo = param.getApplicationInfo();
        lpparam.isFirstApplication = param.isFirstPackage();
        if ("android".equals(lpparam.packageName)) {
            // 作用域里 system_server 的虚拟包名是 "system"(不是 "android"), 见 META-INF/xposed/scope.list。
            // 普通应用进程里也会加载 "android" 包(framework-res), 那里找不到 com.android.server.* 类;
            // 真正的 system_server 一律走 onSystemServerStarting, 这里只做兜底去重。
            if (!sIsSystemServer || sSystemServerHooked) return;
            sSystemServerHooked = true;
        } else {
            // API 102 热加载可能发生在宿主主包的 PackageReady 事件之后。后续事件的包名可能是
            // WebView/overlay 等依赖包，此时按进程名识别真正宿主，并使用已创建的 Application
            // ClassLoader 初始化；否则模块会显示 Loaded，却一直错过 SystemUI/Launcher hook。
            if (!isAppHookTarget(lpparam.packageName) && isAppHookTarget(sProcessName)) {
                android.content.Context application = currentApplication();
                if (application == null) return;
                lpparam.packageName = sProcessName;
                lpparam.classLoader = application.getClassLoader();
                lpparam.appInfo = application.getApplicationInfo();
                lpparam.isFirstApplication = true;
            }
            if (!isAppHookTarget(lpparam.packageName)) return;
            synchronized (XposedInit.class) {
                if (sAppProcessHooked) return;
                sAppProcessHooked = true;
            }
        }
        handleLoadPackage(lpparam);
    }

    private static boolean isAppHookTarget(String packageName) {
        return "com.android.launcher".equals(packageName)
                || "com.android.systemui".equals(packageName)
                || "com.oplus.pscanvas".equals(packageName)
                || "com.oplus.safecenter".equals(packageName)
                || "com.android.settings".equals(packageName)
                || "com.oplus.wallpapers".equals(packageName)
                || "com.oplus.camera".equals(packageName)
                || "com.android.providers.media.module".equals(packageName);
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        if (sSystemServerHooked) return;
        sSystemServerHooked = true;
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam();
        lpparam.packageName = "android";
        lpparam.processName = sProcessName;
        lpparam.classLoader = param.getClassLoader();
        lpparam.isFirstApplication = true;
        handleLoadPackage(lpparam);
    }

    private void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        log("handleLoadPackage pkg=" + lpparam.packageName);
        // 后台预热模块设置(见 startSettingsLoader 注释): 尽早开始, 让首次 readBool 通常已有值,
        // 避免开机早期把默认值固化下来。
        startSettingsLoader();
        // 缓存被 hook 进程自身的 Application Context, 供 readBool 通过 ContentResolver 跨进程查询设置。
        if (sAppContext == null) {
            sAppContext = currentApplication();
        }
        if ("com.android.launcher".equals(lpparam.packageName)) {
            LauncherHooks.hookLauncher(lpparam);
        } else if ("com.android.systemui".equals(lpparam.packageName)) {
            SystemUiHooks.hookSystemUi(lpparam);
        } else if ("com.oplus.pscanvas".equals(lpparam.packageName)) {
            MultiWindowHooks.hookCanvasControlBar(lpparam);
        } else if ("com.oplus.safecenter".equals(lpparam.packageName)) {
            SafecenterHooks.hookSafecenter(lpparam);
        } else if ("com.android.settings".equals(lpparam.packageName)) {
            SettingsHooks.hookSettings(lpparam);
        } else if ("com.oplus.wallpapers".equals(lpparam.packageName)) {
            WallpapersHooks.hookWallpapers(lpparam);
        } else if ("com.oplus.camera".equals(lpparam.packageName)) {
            CameraHooks.hookCamera(lpparam);
        } else if ("com.android.providers.media.module".equals(lpparam.packageName)) {
            MediaProviderHooks.hookMediaProvider(lpparam);
        } else if ("android".equals(lpparam.packageName)) {
            // system_server: 承载"贴边最小化"的真正提交逻辑(com.android.server.wm.FlexibleTaskController)
            SystemServerHooks.hookFloatWindowEdgeHangSystemServer(lpparam);
            // system_server: 贴边挂机静音, 走系统多应用音量通道
            SystemServerHooks.hookFloatWindowEdgeHangMute(lpparam);
            // system_server: 横屏应用小窗保持比例(com.android.server.wm.FlexibleTaskController)
            SystemServerHooks.hookFloatWindowLandscapeKeepRatio(lpparam);
            // system_server: 小窗缩到最小贴边不留边距 + 最大可调宽度 = 屏幕宽度
            SystemServerHooks.hookFloatWindowSizeLimits(lpparam);
            // system_server: 多任务上划彻底结束进程, 配合 LauncherHooks 的 removeTask 补调
            SystemServerHooks.hookRecentsSwipeUpKillSystemServer(lpparam);
            // system_server: 第三方悬浮窗变化事件, 供状态栏歌词避让功能按事件刷新窗口信息。
            SystemServerHooks.hookStatusBarThirdPartyOverlayEvents(lpparam);
            // system_server: 将画布分屏嵌入应用的状态栏 inset 同步缩至三点控制栏的新高度。
            SystemServerHooks.hookCompactCanvasCaptionInsets(lpparam);
            // system_server: 同步缩小悬浮小窗顶部控制栏、系统上报高度与触摸区。
            SystemServerHooks.hookCompactFlexibleCaptionBar(lpparam);
        }
        // 状态栏歌词只需在 SystemUI 侧实现: 直接读 MediaSession 的标题,
        // 无需在音乐软件进程注入, 也无需伪装机型(见 StatusBarLyricHooks 类注释)。
    }

    // 读取桌面隐藏应用入口文件夹的自定义名称, 与安全中心 com.oplus.safecenter.privacy.utils.k#b 一致:
    // content://com.android.launcher.OplusFavoritesProvider/desktopappedit, column=title,
    // selection="componentName=?", arg="com.oplus.safecenter_<AppHideLauncherActivity>_<userId>"。
    public static String readAppHideFolderName(android.content.Context context) {
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

    // 跨进程读取开关, 走 Binder(ContentProvider) 通道, 不受 SELinux 对 app_data_file 的限制。
    public static boolean readBool(String key, boolean def) {
        Object v = settingsValue(key);
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        return def;
    }

    // 仅读取已预热/曾成功读取的内存值，不等待、不访问 ContentProvider。
    // 持有 AMS/WMS 等系统全局锁的 hook 必须使用此方法，避免 Provider 获取反向请求
    // ActivityManager 锁而形成死锁。预热完成前暂用默认值，之后自动读取最新快照。
    public static boolean readBoolCached(String key, boolean def) {
        Integer snapshot = sSnapshot.get(key);
        if (snapshot != null) return snapshot == 1;
        Object[] cached = sCache.get(key);
        if (cached != null && cached[1] instanceof Number) {
            return ((Number) cached[1]).intValue() == 1;
        }
        return def;
    }

    // 跨进程读取 int 设置(如滑条值), 通道与 readBool 相同; Provider 里不存在的键回落到 def。
    public static int readInt(String key, int def) {
        Object v = settingsValue(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    // 取值顺序: 后台预热的全量快照(零 IPC) -> (首次)有限等待预热 -> 同步 Provider 查询 -> 粘性缓存 -> 无。
    private static Object settingsValue(String key) {
        Integer v = sSnapshot.get(key);
        if (v != null) return v;
        // 已加载过全量快照仍没有该键: 说明确实没写过, 直接回落到调用方的默认值, 不必再走 IPC。
        if (sSettingsLoaded) return null;
        ensureFirstLoad();
        v = sSnapshot.get(key);
        if (v != null) return v;
        return queryProviderSync(key);
    }

    // 同步 Provider 查询(旧通道), 仅在后台预热不可达时兜底; 结果按 TTL 写入 sCache 作粘性缓存。
    private static Object queryProviderSync(String key) {
        Object[] cached = sCache.get(key);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < CACHE_TTL_MS) {
            return cached[1];
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
        // Provider 取不到(模块 App 未运行等): 回退上一次成功取到的值(粘性, 即使已过期),
        // 保证设置一旦被写入就会"记住", 不受 App 被杀/重启影响。
        return cached != null ? cached[1] : null;
    }

    public static float readDensity() {
        try {
            return android.content.res.Resources.getSystem().getDisplayMetrics().density;
        } catch (Throwable ignored) {
            return 3.0f; // common ColorOS density fallback
        }
    }

    // 反射获取当前进程 Application(Context), 用于 ContentResolver 跨进程查询设置。
    public static android.content.Context currentApplication() {
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread",
                    java.lang.ClassLoader.getSystemClassLoader());
            return (android.content.Context) XposedHelpers.callStaticMethod(at, "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }
}
