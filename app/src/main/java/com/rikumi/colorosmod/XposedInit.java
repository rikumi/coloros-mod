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
import com.rikumi.colorosmod.hooks.SafecenterHooks;
import com.rikumi.colorosmod.hooks.SettingsHooks;
import com.rikumi.colorosmod.hooks.StatusBarLyricHooks;
import com.rikumi.colorosmod.hooks.SystemServerHooks;
import com.rikumi.colorosmod.hooks.SystemUiHooks;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
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

    public static final String TAG = "ColorOSMod";
    public static final String MODULE_PACKAGE = "com.rikumi.colorosmod";
    public static final String PREF_NAME = "settings";

    public static final String KEY_ICON_GAP_ENABLED = "icon_gap_enabled";
    public static final String KEY_ICON_GAP_DP = "icon_gap_dp";
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
    public static final String KEY_GESTURE_TOUCH_THROUGH_ENABLED =
            "gesture_touch_through_enabled";
    public static final String KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED =
            "gesture_bar_long_press_disable_enabled";
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

    // 跨进程读取开关用的应用 Context(被 hook 进程自身)与 ContentProvider 通道所需的字段。
    public static volatile android.content.Context sAppContext;
    public static final String SETTINGS_AUTHORITY = "com.rikumi.colorosmod.settings";
    // 开关值缓存 TTL。长按/拖拽期间会以触摸事件频率反复 readBool, TTL 过短会频繁触发同步
    // ContentProvider IPC 造成主线程卡顿; 5s 内连续读取全部命中内存缓存(零 IPC), 代价是最多 5s 延迟。
    public static final long CACHE_TTL_MS = 5000;
    public static final java.util.concurrent.ConcurrentHashMap<String, Object[]> sCache =
            new java.util.concurrent.ConcurrentHashMap<String, Object[]>(); // key -> {Long ts, Boolean val}

    // ---- 后台设置预热 ----
    // 开机早期(模块 App 尚未被拉起 / 仍处于锁定态)同步查询拿不到值, 而部分 hook 只在初始化时读一次
    // (手势条高度在导航栏创建时读取一次), 默认值一旦被固化, 解锁后也不会纠正 —— 即"重启后失效,
    // 重启作用域才恢复"。故注入后立刻起后台线程周期性全量拉取: 首成功前每 SETTINGS_RETRY_MS 重试,
    // 之后每 SETTINGS_REFRESH_MS(与原 CACHE_TTL_MS 同口径)刷新, 改设置仍在 5s 内生效。
    public static final String SETTINGS_ALL_KEY = "__all__";
    private static final Object sLoadLock = new Object();
    private static volatile java.util.Map<String, Integer> sSnapshot =
            java.util.Collections.emptyMap();
    private static volatile boolean sLoaderStarted = false;
    private static volatile boolean sSettingsLoaded = false;
    private static volatile boolean sFirstWaitDone = false;
    private static final long SETTINGS_REFRESH_MS = CACHE_TTL_MS;
    private static final long SETTINGS_RETRY_MS = 500;
    // 首个 readBool/readInt 到达时后台预热可能还没完成, 最多等这么久; 整个进程只等一次。
    private static final long FIRST_LOAD_WAIT_MS = 5000;

    public static void startSettingsLoader() {
        synchronized (sLoadLock) {
            if (sLoaderStarted) return;
            sLoaderStarted = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    long sleep;
                    try {
                        java.util.Map<String, Integer> all = fetchAllSettings();
                        if (all != null) {
                            boolean first = !sSettingsLoaded;
                            sSnapshot = all;
                            if (first) {
                                synchronized (sLoadLock) {
                                    sSettingsLoaded = true;
                                    sLoadLock.notifyAll();
                                }
                                log("settings loaded: " + all.size() + " keys");
                            }
                            sleep = SETTINGS_REFRESH_MS;
                        } else {
                            sleep = sSettingsLoaded ? SETTINGS_REFRESH_MS : SETTINGS_RETRY_MS;
                        }
                    } catch (Throwable ignored) {
                        sleep = SETTINGS_REFRESH_MS;
                    }
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "ColorOSMod-Settings");
        t.setDaemon(true);
        t.start();
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

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam();
        lpparam.packageName = param.getPackageName();
        lpparam.processName = sProcessName;
        lpparam.classLoader = param.getDefaultClassLoader();
        lpparam.appInfo = param.getApplicationInfo();
        lpparam.isFirstApplication = param.isFirstPackage();
        if ("android".equals(lpparam.packageName)) {
            // 普通应用进程里也会加载 "android" 包, 那里的 system_server 钩子是找不到类的;
            // 真正的 system_server 一律走 onSystemServerStarting, 这里只做兜底去重。
            if (!sIsSystemServer || sSystemServerHooked) return;
            sSystemServerHooked = true;
        }
        handleLoadPackage(lpparam);
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
        } else if ("com.oplus.safecenter".equals(lpparam.packageName)) {
            SafecenterHooks.hookSafecenter(lpparam);
        } else if ("com.android.settings".equals(lpparam.packageName)) {
            SettingsHooks.hookSettings(lpparam);
        } else if ("android".equals(lpparam.packageName)) {
            // system_server: 承载"贴边最小化"的真正提交逻辑(com.android.server.wm.FlexibleTaskController)
            SystemServerHooks.hookFloatWindowEdgeHangSystemServer(lpparam);
            // system_server: 贴边挂机静音, 走系统多应用音量通道
            SystemServerHooks.hookFloatWindowEdgeHangMute(lpparam);
            // system_server: 横屏应用小窗保持比例(com.android.server.wm.FlexibleTaskController)
            SystemServerHooks.hookFloatWindowLandscapeKeepRatio(lpparam);
            // system_server: 小窗缩到最小贴边不留边距 + 最大可调宽度 = 屏幕宽度
            SystemServerHooks.hookFloatWindowSizeLimits(lpparam);
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
