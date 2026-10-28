package com.rikumi.colorosmod;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.rikumi.colorosmod.hooks.QsHooks;
import com.rikumi.colorosmod.hooks.WeatherHooks;
import com.rikumi.colorosmod.hooks.StorePurificationHooks;
import com.rikumi.colorosmod.hooks.GestureHooks;
import com.rikumi.colorosmod.hooks.AncTileHooks;
import com.rikumi.colorosmod.hooks.NotificationHooks;
import com.rikumi.colorosmod.hooks.MonochromeNotificationHooks;
import com.rikumi.colorosmod.hooks.ActiveTileOutlineHooks;
import com.rikumi.colorosmod.hooks.PasswordInputHooks;
import com.rikumi.colorosmod.hooks.StatusBarLyricHooks;
import com.rikumi.colorosmod.hooks.SignalHooks;
import com.rikumi.colorosmod.hooks.FoldIdleMediaHooks;
import com.rikumi.colorosmod.hooks.StatusBarFontHooks;
import com.rikumi.colorosmod.hooks.MergedCardRatioHooks;
import com.rikumi.colorosmod.hooks.MergedRadiantHooks;
import com.rikumi.colorosmod.hooks.MergedSettingsRotationHooks;
import com.rikumi.colorosmod.hooks.LauncherHooks;
import com.rikumi.colorosmod.hooks.StatusBarExtrasHooks;
import com.rikumi.colorosmod.hooks.KeyguardHooks;
import com.rikumi.colorosmod.hooks.MediaProviderHooks;
import com.rikumi.colorosmod.hooks.MultiWindowHooks;
import com.rikumi.colorosmod.hooks.SafecenterHooks;
import com.rikumi.colorosmod.hooks.AppStartConfirmHooks;
import com.rikumi.colorosmod.hooks.SettingsHooks;
import com.rikumi.colorosmod.hooks.SystemServerHooks;
import com.rikumi.colorosmod.hooks.SystemUiHooks;
import com.rikumi.colorosmod.hooks.WallpapersHooks;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class XposedInit extends XposedModule {

    private static volatile String sProcessName = "";
    private static volatile boolean sIsSystemServer = false;
    private static volatile boolean sSystemServerHooked = false;
    private static volatile boolean sAppProcessHooked = false;

    private static volatile ClassLoader sSystemServerClassLoader = null;

    private static volatile String sHookedPackageName = "";

    public static final String TAG = "ColorOSMod";
    public static final String MODULE_PACKAGE = "com.rikumi.colorosmod";
    public static final String PREF_NAME = "settings";

    public static final String KEY_ICON_GAP_ENABLED = "icon_gap_enabled";
    public static final String KEY_ICON_GAP_DP = "icon_gap_dp";

    public static final String KEY_DRAWER_LAYOUT_ENABLED = "drawer_columns_enabled";
    // 沿用原默认档的尺寸缩放比例(4/5)，仅作用于抽屉。
    public static final float DRAWER_ICON_SCALE = 0.8f;

    public static final String KEY_DRAWER_LETTER_SCROLL_ENABLED = "drawer_letter_scroll_enabled";
    public static final String KEY_INDICATOR_DP = "indicator_dp";
    public static final String KEY_POPUP_SCALE_PERCENT = "popup_scale_percent";
    public static final String KEY_NOTIFICATION_SUBTITLE_SP = "notification_subtitle_sp";
    public static final String KEY_NOTIFICATION_PADDING_DP = "notification_padding_dp";
    public static final String KEY_INDICATOR_ENABLED = "indicator_enabled";
    public static final String KEY_HIDE_BATTERY_PERCENT_SIGN_ENABLED = "hide_battery_percent_sign_enabled";
    public static final String KEY_HIDE_LAUNCHER_UPDATE_DOT_ENABLED = "hide_launcher_update_dot_enabled";
    public static final String KEY_DISABLE_LAUNCHER_SECONDARY_MENU_ENABLED = "disable_launcher_secondary_menu_enabled";
    public static final String KEY_FLOAT_WINDOW_COUNT_ENABLED = "float_window_count_enabled";
    public static final String KEY_FLOAT_WINDOW_COUNT = "float_window_count";
    public static final String KEY_QS_CARRIER_ENABLED = "qs_carrier_enabled";
    public static final String KEY_QS_TOPMARGIN_ENABLED = "qs_topmargin_enabled";
    public static final String KEY_NOTIFICATION_SUBTITLE_ENABLED = "notification_subtitle_enabled";
    public static final String KEY_NOTIFICATION_PADDING_ENABLED = "notification_padding_enabled";
    public static final String KEY_NOTIFICATION_LAYOUT_STYLE =
            "notification_layout_style";
    public static final int NOTIFICATION_LAYOUT_STYLE_DEFAULT = 2;

    public static final String KEY_NOTIFICATION_SWIPE_TO_DISMISS_ENABLED =
            "notification_swipe_to_dismiss_enabled";

    public static final String KEY_NOTIFICATION_PULL_EXPAND_ENABLED =
            "notification_pull_expand_enabled";
    public static final String KEY_RECENTS_SHOW_HIDDEN_ENABLED = "recents_show_hidden_enabled";
    public static final String KEY_RECENTS_HIDE_FREEFORM_ENABLED = "recents_hide_freeform_enabled";
    public static final String KEY_RECENTS_HIDE_CLOSED_FREEFORM_ENABLED = "recents_hide_closed_freeform_enabled";
    public static final String KEY_HIDE_APPS_NOVERIFY_ENABLED = "hide_apps_noverify_enabled";
    public static final String KEY_HIDE_APPS_TITLE_FOLDER_ENABLED = "hide_apps_title_folder_enabled";
    // 停用应用免密码: 设置里停用"受生物识别保护"的应用(config_biometric_protected_package_names)

    public static final String KEY_DISABLE_APPS_NOVERIFY_ENABLED = "disable_apps_noverify_enabled";

    public static final String KEY_HIDE_DISABLED_APPS_ENABLED = "hide_disabled_apps_enabled";
    // 通用设置 — 设置首页图标样式: 0=系统默认, 1=不规则图标, 2=圆形图标。
    public static final String KEY_SETTINGS_DISABLED_MODULE_SPACING = "settings_disabled_module_spacing";
    public static final String KEY_SETTINGS_HOME_ICON_STYLE = "settings_home_icon_style";
    public static final int SETTINGS_HOME_ICON_STYLE_DEFAULT = 0;
    public static final int SETTINGS_HOME_ICON_STYLE_IRREGULAR = 1;
    public static final int SETTINGS_HOME_ICON_STYLE_CIRCLE = 2;
    // 缩小桌面图标长按菜单: 在资源层按比例缩放菜单的图标、文字、宽高与内外边距。
    public static final String KEY_SHRINK_POPUP_MENU = "shrink_popup_menu";

    public static final int POPUP_SHRINK_PERCENT_DEFAULT = 10;
    // Feature 9 — 桌面双指张开(pinch-out)手势打开隐藏应用文件夹 (com.android.launcher)
    public static final String KEY_PINCH_OUT_OPEN_HIDE_APPS_ENABLED = "pinch_out_open_hide_apps_enabled";

    public static final String KEY_FOLDER_BG_TRANSPARENT_ENABLED = "folder_bg_transparent_enabled";

    public static final String KEY_FOLDER_ANIM_DURATION_ENABLED = "folder_anim_duration_enabled";
    public static final String KEY_FOLDER_ANIM_DURATION_MS = "folder_anim_duration_ms";

    public static final String KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED = "edit_mode_bg_transparent_enabled";

    public static final String KEY_WEAKEN_DESKTOP_CUSTOMIZATION_BG_ENABLED =
            "weaken_desktop_customization_bg_enabled";
    // Feature 10 — 合并控制中心背景 scrim 亮度 (com.android.systemui)
    public static final String KEY_QS_SCRIM_TRANSLUCENT_ENABLED = "qs_scrim_translucent_enabled";
    // 背景亮度滑条键(0-20, 默认 0): 0=全黑, 20=系统默认 lumin(不压暗)。
    public static final String KEY_QS_SCRIM_BRIGHTNESS = "qs_scrim_brightness";

    public static final String KEY_QS_BLUR_RADIUS_ENABLED = "qs_blur_radius_enabled";
    public static final String KEY_QS_BLUR_RADIUS = "qs_blur_radius";
    public static final int QS_BLUR_RADIUS_DEFAULT = 40;
    public static final int QS_BLUR_RADIUS_MAX = 80;
    public static final int QS_BLUR_RADIUS_SCALE = 10;

    public static final String KEY_QS_BLUR_SCALE_ENABLED = "qs_blur_scale_enabled";
    public static final String KEY_QS_BLUR_SCALE = "qs_blur_scale";
    public static final int QS_BLUR_SCALE_DEFAULT = 50;
    public static final int QS_BLUR_SCALE_MAX = 100;

    public static final String KEY_QS_TILE_NAME_ELLIPSIS_ENABLED = "qs_tile_name_ellipsis_enabled";

    public static final String KEY_QS_ACTIVE_OUTLINE_ENABLED = "qs_active_outline_enabled";
    public static final String KEY_QS_MERGED_RADIANT = "qs_merged_radiant";
    public static final String KEY_QS_MERGED_HOLLOW = "qs_merged_hollow";
    public static final String KEY_QS_MERGED_SETTINGS_ROTATION = "qs_merged_settings_rotation";
    public static final String KEY_QS_MERGED_TILE_SCALE_ANIMATION = "qs_merged_tile_scale_animation";
    public static final String KEY_QS_MERGED_TILE_CLIP_ANIMATION = "qs_merged_tile_clip_animation";
    public static final String KEY_QS_MERGED_TWO_FINGER_EXPAND = "qs_merged_two_finger_expand";
    public static final String KEY_QS_ACTIVE_COLOR_ENABLED = "qs_active_color_enabled";
    public static final String KEY_QS_ACTIVE_COLOR = "qs_active_color";
    public static final int DEFAULT_QS_ACTIVE_COLOR = 0xff00b4d8;
    public static final String KEY_QS_NORMAL_CORNER_RADIUS_ENABLED = "qs_normal_corner_radius_enabled";
    public static final String KEY_QS_CORNER_RADIUS_MODE = "qs_corner_radius_mode";
    public static final String KEY_QS_CORNER_RADIUS_ENABLED = "qs_corner_radius_enabled";
    public static final String KEY_QS_CORNER_RADIUS_DP = "qs_corner_radius_dp";
    public static final String KEY_QS_CORNER_RADIUS_PERCENT = "qs_corner_radius_percent";
    // 分离版控制中心左右切换取消切入效果: 通知中心/控制中心之间左右滑动时直接平移而非切变。
    public static final String KEY_QS_PANEL_SWITCH_NO_CUT_ENABLED = "qs_panel_switch_no_cut_enabled";

    public static final String KEY_QS_CLOCK_NO_EXPAND_ANIM_ENABLED = "qs_clock_no_expand_anim_enabled";
    // 圆角轮廓 provider 与构造入口(QSConstant#getSmoothRoundRectOutlineProvider)。
    public static final String QS_OUTLINE_PROVIDER_CLASS =
            "com.oplusos.systemui.common.outline.RoundRectOutlineProvider";
    public static final String QS_CONSTANT_CLASS = "com.oplus.systemui.qs.base.res.util.QSConstant";

    public static final String KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED = "fluid_cloud_keep_percent_enabled";
    public static final String KEY_MERGE_DUAL_SIGNAL = "merge_dual_sim_signal";
    public static final String KEY_FOLD_IDLE_MEDIA = "fold_idle_media";
    public static final String KEY_QS_MERGED_GAP_PERCENT_TENTHS = "qs_merged_gap_percent_tenths";
    public static final String KEY_QS_MERGED_FOUR_COLUMNS = "qs_merged_four_columns";
    public static final String KEY_QS_MERGED_GAP_ENABLED = "qs_merged_gap_enabled";
    public static final String KEY_QS_MERGED_CARD_RATIO = "qs_merged_card_ratio";
    public static final String KEY_QS_FILL_EMPTY = "qs_fill_empty";
    public static final String KEY_SEPARATE_NETWORK_TYPE = "separate_network_type";
    public static final String KEY_HIDE_ROAMING_ICON = "hide_roaming_icon";
    public static final String KEY_SIGNAL_NETWORK_TYPE_MODE = "signal_network_type_mode";
    public static final String KEY_STATUS_BAR_FONT = "status_bar_clock_font";
    public static final String KEY_STATUS_BAR_FONT_WEIGHT = "status_bar_clock_weight";
    // 恢复原生带“始终允许”复选框的应用间跳转确认对话框。
    public static final String KEY_APP_START_CONFIRM_ALWAYS_ALLOW_ENABLED =
            "app_start_confirm_always_allow_enabled";

    public static final String KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED = "float_window_edge_hang_enabled";
    // 贴边挂机静音: 挂机时经系统多应用音量通道把该应用音量置 0, 回到前台时恢复原值。
    public static final String KEY_FLOAT_WINDOW_EDGE_HANG_MUTE_ENABLED =
            "float_window_edge_hang_mute_enabled";

    public static final String KEY_FLOAT_WINDOW_EDGE_HANG_WHITE_BAR_ENABLED =
            "float_window_edge_hang_white_bar_enabled";

    public static final String KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED =
            "float_window_landscape_keep_ratio_enabled";

    public static final String KEY_FLOAT_WINDOW_EDGE_SIZE_OPTIMIZE_ENABLED =
            "float_window_edge_size_optimize_enabled";
    // 小窗放大到最大时, 左右各保留的边距(dp): 最大宽度 = 屏幕宽度 - 2 * FLOAT_WINDOW_SIDE_MARGIN_DP。
    public static final float FLOAT_WINDOW_SIDE_MARGIN_DP = 8f;
    public static final String KEY_GESTURE_BAR_HEIGHT_ENABLED = "gesture_bar_height_enabled";
    public static final String KEY_GESTURE_BAR_HEIGHT_DP = "gesture_bar_height_dp";
    public static final String KEY_GESTURE_BAR_WIDTH_ENABLED = "gesture_bar_width_enabled";
    public static final String KEY_GESTURE_BAR_WIDTH_DP = "gesture_bar_width_dp";
    public static final String KEY_MBACK_ENABLED = "mback_enabled";

    public static final String KEY_ROTATION_BUTTON_FIXED_POSITION_ENABLED =
            "rotation_button_fixed_position_enabled";
    public static final String KEY_GESTURE_TOUCH_THROUGH_ENABLED =
            "gesture_touch_through_enabled";
    public static final String KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED =
            "gesture_bar_long_press_disable_enabled";

    public static final String KEY_SHRINK_CAPTION_BAR_ENABLED = "shrink_caption_bar_enabled";
    public static final float COMPACT_CAPTION_BAR_HEIGHT_DP = 24f;

    public static final String KEY_RECENTS_SWIPE_UP_KILL_ENABLED = "recents_swipe_up_kill_enabled";

    public static final String KEY_RECENTS_BG_TRANSPARENT_ENABLED =
            "recents_bg_transparent_enabled";

    public static final String KEY_RECENTS_HIDE_NOT_RUNNING_ENABLED =
            "recents_hide_not_running_enabled";
    public static final String KEY_POWER_SAVE_KEEP_LOCKED_TASKS_ENABLED =
            "power_save_keep_locked_tasks_enabled";

    public static final String KEY_RECENTS_SWIPE_UP_KILL_SUBSIDIARY_ENABLED =
            "recents_swipe_up_kill_subsidiary_enabled";
    public static final String KEY_HIDDEN_LAUNCHER_APPS_ENABLED = HiddenLauncherApps.ENABLED;
    public static java.util.Set<String> navigationApplications() {
        java.util.Map<String, Integer> snapshot = sSnapshot;
        if (!snapshot.containsKey(AppNavigationSettings.CONFIGURED)) {
            return java.util.Collections.singleton("com.tencent.mm");
        }
        java.util.Set<String> packages = new java.util.HashSet<>();
        for (String key : snapshot.keySet()) {
            if (key.startsWith(AppNavigationSettings.PREFIX) && Integer.valueOf(1).equals(snapshot.get(key))) {
                packages.add(key.substring(AppNavigationSettings.PREFIX.length()));
            }
        }
        return packages;
    }
    private static volatile Object[] sHiddenLauncherSnapshot =
            new Object[]{null, java.util.Collections.emptySet()};

    @SuppressWarnings("unchecked")
    public static java.util.Set<android.content.ComponentName> hiddenLauncherComponents(
            java.util.Map<String, Integer> settings) {
        Object[] cached = sHiddenLauncherSnapshot;
        if (cached[0] == settings) return (java.util.Set<android.content.ComponentName>) cached[1];
        java.util.Set<android.content.ComponentName> components = new java.util.HashSet<>();
        if (Integer.valueOf(1).equals(settings.get(KEY_HIDDEN_LAUNCHER_APPS_ENABLED))) for (String key : settings.keySet()) {
            if (!key.startsWith(HiddenLauncherApps.PREFIX) || !Integer.valueOf(1).equals(settings.get(key))) continue;
            android.content.ComponentName component = android.content.ComponentName.unflattenFromString(
                    key.substring(HiddenLauncherApps.PREFIX.length()));
            if (component != null) components.add(component);
        }
        java.util.Set<android.content.ComponentName> result = java.util.Collections.unmodifiableSet(components);
        sHiddenLauncherSnapshot = new Object[]{settings, result};
        return result;
    }

    public static java.util.Set<android.content.ComponentName> getHiddenLauncherComponents() {
        if (!sSettingsLoaded && "com.android.launcher".equals(sProcessName)
                && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            requestRefresh();
            LauncherHooks.onHiddenLauncherSettingsUnavailable();
            return hiddenLauncherComponents(sSnapshot);
        }
        // 首次模型加载仍等待完整快照，避免把未过滤的列表缓存到 Launcher 模型。
        ensureFirstLoad();
        java.util.Map<String, Integer> settings = sSnapshot;
        if (!sSettingsLoaded) {
            java.util.Map<String, Integer> fetched = fetchAllSettings();
            if (fetched != null) {
                settings = fetched;
            } else {
                LauncherHooks.onHiddenLauncherSettingsUnavailable();
                settings = sSnapshot;
            }
        }
        return hiddenLauncherComponents(settings);
    }

    public static final String KEY_UNLOCKED_SHUTDOWN_NOVERIFY_ENABLED =
            "unlocked_shutdown_noverify_enabled";

    public static final String KEY_KEYGUARD_FINGERPRINT_FAILURE_DELAY_ENABLED =
            "keyguard_fingerprint_failure_delay_enabled";

    public static final String KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED =
            "keyguard_no_light_effect_enabled";

    public static final String KEY_KEYGUARD_BOUNCER_BRIGHTNESS_ENABLED =
            "keyguard_bouncer_brightness_enabled";
    // 亮度滑条键(0-5, 默认 0): 0=全黑(去掉系统抬的最低亮度), 5=系统默认 lumin(0x26=38)。
    public static final String KEY_KEYGUARD_BOUNCER_BRIGHTNESS = "keyguard_bouncer_brightness";

    public static final String KEY_DESKTOP_POPUP_BG_BRIGHTNESS_ENABLED =
            "desktop_popup_bg_brightness_enabled";
    // 长按菜单背景动态模糊: 把系统的"静态模糊壁纸 + ALPHA 渐显"改成"清晰壁纸 + 高斯模糊半径渐进"。
    public static final String KEY_POPUP_DYNAMIC_BLUR_ENABLED = "popup_dynamic_blur_enabled";

    // 亮度滑条键(0-10, 默认 0): 0=去掉系统抬的最低亮度, 10=系统默认效果。
    public static final String KEY_DESKTOP_POPUP_BG_BRIGHTNESS = "desktop_popup_bg_brightness";

    public static final String KEY_KEYGUARD_NOTIFICATION_OFFSET_ENABLED =
            "keyguard_notification_offset_enabled";
    public static final String KEY_KEYGUARD_NOTIFICATION_OFFSET_DP =
            "keyguard_notification_offset_dp";
    public static final int KEYGUARD_NOTIFICATION_OFFSET_DP_DEFAULT = 20;
    public static final int KEYGUARD_NOTIFICATION_OFFSET_DP_MAX = 40;

    public static final String KEY_STATUSBAR_LYRIC_MODE = "statusbar_lyric_mode";
    public static final int STATUSBAR_LYRIC_MODE_OFF = 0;
    public static final int STATUSBAR_LYRIC_MODE_NOTIFICATION = 1;
    public static final int STATUSBAR_LYRIC_MODE_LIVE_ALERTS = 2;
    // 旧版本状态栏歌词设置，升级迁移完成后不再写入。
    public static final String KEY_STATUSBAR_LYRIC_ENABLED = "statusbar_lyric_enabled";
    public static final String KEY_FLUID_CLOUD_LYRIC_ENABLED = "fluid_cloud_lyric_enabled";
    // 第三方状态栏歌词避让: 第三方悬浮窗左上角进入状态栏区域时隐藏系统时钟。
    public static final String KEY_STATUSBAR_LYRIC_AVOID_THIRD_PARTY_ENABLED =
            "statusbar_lyric_avoid_third_party_enabled";
    // system_server 窗口变化通知序号, 仅作为 SystemUI ContentObserver 的事件信号。
    public static final String KEY_STATUSBAR_OVERLAY_EVENT =
            "colorosmod_statusbar_overlay_event";

    public static final String KEY_ANC_TILE_ENABLED = "anc_tile_enabled";

    public static final String SETTINGS_KEY_NOTIFICATION_PROMPT_MODE = "notification_prompt_mode";
    public static final int NOTIFICATION_PROMPT_SHOW_ICON = 0;
    public static final int NOTIFICATION_PROMPT_SHOW_NUMBER = 1;

    public static final String KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED = "keyguard_bouncer_swipe_back_enabled";

    public static final String KEY_KEYGUARD_SLIDE_INPUT_ENABLED = "keyguard_slide_input_enabled";
    public static final String KEY_KEYGUARD_NO_CHARGE_ANIM_ENABLED =
            "keyguard_no_charge_anim_enabled";

    // 跨进程读取开关用的应用 Context(被 hook 进程自身)与 ContentProvider 通道所需的字段。
    public static volatile android.content.Context sAppContext;
    public static final String SETTINGS_AUTHORITY = "com.rikumi.colorosmod.settings";

    public static final long CACHE_TTL_MS = 5000;
    public static final java.util.concurrent.ConcurrentHashMap<String, Object[]> sCache =
            new java.util.concurrent.ConcurrentHashMap<String, Object[]>();

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
                refreshSettings();
            }
        });
    }

    // 必须在 worker 线程调用: 注册 ContentObserver, callback handler = sWorkerHandler。
    private static boolean registerSettingsObserverOnWorker() {
        if (!sSyncActive) return false;
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
            if (!sSyncActive) {
                try { cr.unregisterContentObserver(observer); } catch (Throwable ignored) { }
                return false;
            }
            sObserver = observer;
            log("settings content observer registered");
            return true;
        } catch (Throwable t) {
            log("registerContentObserver fail: " + t);
            sObserver = null;
            return false;
        }
    }

    private static boolean ensureAppContext() {
        if (sAppContext == null) {
            sAppContext = currentApplication();
        }
        return sAppContext != null;
    }

    private static void requestRefresh() {
        android.os.Handler h = sWorkerHandler;
        if (h == null || !sSyncActive || sRefreshQueued) return;
        sRefreshQueued = true;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                sRefreshQueued = false;
                if (!sSyncActive) return;
                refreshSettings();
            }
        }, ONCHANGE_COALESCE_MS);
    }

    private static void refreshSettings() {
        if (!sSyncActive) return;
        if (!ensureAppContext()) {
            scheduleRetry();
            return;
        }
        boolean observerReady = registerSettingsObserverOnWorker();
        java.util.Map<String, Integer> all = fetchAllSettings();
        if (all != null) {
            publishSnapshot(all);
        }
        if (sSyncActive) {
            if (all == null) {
                scheduleRetry();
            } else if (!observerReady) {
                scheduleObserverRetry();
            } else {
                sRetryMs = RETRY_MIN_MS;
            }
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
                    refreshSettings();
                }
            }
        }, delay);
    }

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
        com.rikumi.colorosmod.hooks.SystemIconPackHooks.onSettingsSnapshotPublished();
        if (sIsSystemServer) {
            com.rikumi.colorosmod.hooks.RecentFreeformHistoryHooks.onSettingsSnapshotPublished(all);
        }
        if ("com.android.settings".equals(sHookedPackageName)) {
            SettingsHooks.onSettingsSnapshotPublished();
        }
        if ("com.android.launcher".equals(sProcessName)) {
            LauncherHooks.onSettingsSnapshotPublished(all);
        }
        if ("com.android.systemui".equals(sHookedPackageName)) {
            com.rikumi.colorosmod.hooks.AppNavigationHooks.onSettingsChanged();
            com.rikumi.colorosmod.hooks.LegacyNotificationLayoutHooks.onSettingsSnapshotPublished();
            QsHooks.onCornerRadiusSettingsChanged();
            SignalHooks.onSettingsSnapshotPublished();
            StatusBarLyricHooks.onSettingsSnapshotPublished();
            FoldIdleMediaHooks.refresh();
            StatusBarFontHooks.refresh();
            MergedCardRatioHooks.refresh();
            ActiveTileOutlineHooks.refresh();
            MergedRadiantHooks.refresh();
            MergedSettingsRotationHooks.refresh();
            StatusBarExtrasHooks.refresh();
            MonochromeNotificationHooks.refresh();
            com.rikumi.colorosmod.hooks.KeyguardCarrierHooks.refresh();
            com.rikumi.colorosmod.hooks.KeyguardClockFontHooks.refresh();
        }
        if (first) {
            synchronized (sLoadLock) {
                sSettingsLoaded = true;
                sLoadLock.notifyAll();
            }
            log("settings loaded: " + all.size() + " keys");
        }
    }

    private static boolean stopSettingsLoader() {
        android.os.Handler wh = sWorkerHandler;
        android.os.HandlerThread ht = sWorkerThread;
        if (ht == null || wh == null) return true;
        sSyncActive = false;
        sRefreshQueued = false;
        sRetryQueued = false;
        sObserverRetryQueued = false;

        wh.removeCallbacksAndMessages(null);

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

            sSyncActive = true;
            // The observer was deliberately retained; only re-arm refresh work.
            wh.post(new Runnable() {
                @Override public void run() { refreshSettings(); }
            });
            log("ColorOSMod-SettingsWorker barrier timeout, rejecting reload");
            return false;
        }

        android.database.ContentObserver o = sObserver;
        if (o != null && sAppContext != null) {
            try {
                sAppContext.getContentResolver().unregisterContentObserver(o);
            } catch (Throwable t) {

                sSyncActive = true;
                wh.post(new Runnable() {
                    @Override public void run() { refreshSettings(); }
                });
                log("settings observer cleanup failed, rejecting reload: " + t);
                return false;
            }
        }
        sObserver = null;

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
            if (!ensureAppContext()) return null;
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
    public static final int NOTIFICATION_PADDING_DP = 4;

    public static final int QS_SCRIM_BRIGHTNESS_DEFAULT = 0;
    public static final int QS_SCRIM_LUMIN_MAX = 0x33;

    public static final int KEYGUARD_BOUNCER_BRIGHTNESS_DEFAULT = 0;
    public static final int KEYGUARD_BOUNCER_BRIGHTNESS_MAX = 5;

    public static final int DESKTOP_POPUP_BG_BRIGHTNESS_DEFAULT = 0;
    public static final int DESKTOP_POPUP_BG_BRIGHTNESS_MAX = 10;

    public static void dbg(String msg) {
        Log.e(TAG, msg);
    }

    public static void log(String msg) {
        Log.e(TAG, msg);
    }

    private static boolean preflightProcessResources() {
        if (sIsSystemServer) return SystemServerHooks.canHotReload();
        if ("com.android.systemui".equals(sHookedPackageName)) {
            if (!com.rikumi.colorosmod.hooks.AppNavigationHooks.prepareHotReload()) {
                log("hot reload preflight rejected: navigation workers");
                return false;
            }
            if (!AncTileHooks.canHotReload()) {
                log("hot reload preflight rejected: ANC worker/listeners");
                return false;
            }
            if (!StatusBarLyricHooks.prepareHotReloadListeners()) {
                log("hot reload preflight rejected: lyric listeners");
                AncTileHooks.cancelHotReloadPreflight();
                return false;
            }
            return true;
        }
        if ("com.android.launcher".equals(sHookedPackageName)) {
            return LauncherHooks.prepareHotReloadListeners();
        }
        if ("com.oplus.wallpapers".equals(sHookedPackageName)) {
            return WallpapersHooks.prepareHotReloadListeners();
        }
        return true;
    }

    private static void cancelProcessPreflight() {
        if ("com.android.systemui".equals(sHookedPackageName)) {
            com.rikumi.colorosmod.hooks.AppNavigationHooks.cancelHotReload();
            StatusBarLyricHooks.cancelHotReloadPreflight();
            AncTileHooks.cancelHotReloadPreflight();
        } else if ("com.android.launcher".equals(sHookedPackageName)) {
            LauncherHooks.cancelHotReloadPreflight();
        } else if ("com.oplus.wallpapers".equals(sHookedPackageName)) {
            WallpapersHooks.cancelHotReloadPreflight();
        }
    }

    private static void cleanupProcessResourcesOnMain() {
        com.rikumi.colorosmod.hooks.AppNavigationHooks.cleanupForHotReload();
        if (sIsSystemServer) {
            try { SystemServerHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("system_server hot reload cleanup failed: " + t); }
            return;
        }
        if ("com.android.settings".equals(sHookedPackageName)) {
            SettingsHooks.cleanupForHotReload();
        }
        if ("com.android.systemui".equals(sHookedPackageName)) {
            com.rikumi.colorosmod.hooks.LegacyNotificationLayoutHooks.cleanupForHotReload();
            try { AncTileHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("anc hot reload cleanup failed: " + t); }
            try { StatusBarLyricHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("lyric hot reload cleanup failed: " + t); }
            try { NotificationHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("notification hot reload cleanup failed: " + t); }
            try { GestureHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("gesture hot reload cleanup failed: " + t); }
            try { PasswordInputHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("password hot reload cleanup failed: " + t); }
            try { SignalHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("signal hot reload cleanup failed: " + t); }
            try { FoldIdleMediaHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("fold media hot reload cleanup failed: " + t); }
            try { MergedRadiantHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("merged radiant cleanup failed: " + t); }
            MergedSettingsRotationHooks.cleanup();
            QsHooks.cleanupCornerRadiusForHotReload();
            try { MergedCardRatioHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("merged card ratio cleanup failed: " + t); }
            try { StatusBarExtrasHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("status extras hot reload cleanup failed: " + t); }
            try { StatusBarFontHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("status font hot reload cleanup failed: " + t); }
            try { KeyguardHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("keyguard hot reload cleanup failed: " + t); }
        } else if ("com.android.launcher".equals(sHookedPackageName)) {
            try { LauncherHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("launcher hot reload cleanup failed: " + t); }
        } else if ("com.oplus.wallpapers".equals(sHookedPackageName)) {
            try { WallpapersHooks.cleanupForHotReload(); }
            catch (Throwable t) { log("wallpapers hot reload cleanup failed: " + t); }
        }
        try { XposedHelpers.cancelTrackedCallbacksAndAnimators(); }
        catch (Throwable t) { log("tracked callback cleanup failed: " + t); }
    }

    private static boolean commitProcessCleanup() {

        if (!sIsSystemServer && sHookedPackageName.isEmpty()) return true;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            cleanupProcessResourcesOnMain();
            return true;
        }
        final int PENDING = 0, RUNNING = 1, CANCELLED = 2, DONE = 3;
        java.util.concurrent.atomic.AtomicInteger state =
                new java.util.concurrent.atomic.AtomicInteger(PENDING);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        Runnable commit = new Runnable() {
            @Override public void run() {
                if (!state.compareAndSet(PENDING, RUNNING)) {
                    started.countDown();
                    finished.countDown();
                    return;
                }
                started.countDown();
                try { cleanupProcessResourcesOnMain(); }
                finally { state.set(DONE); finished.countDown(); }
            }
        };
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        if (!main.post(commit)) return false;
        try {
            if (!started.await(3000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (state.compareAndSet(PENDING, CANCELLED)) {
                    main.removeCallbacks(commit);
                    return false;
                }
                // Commit started concurrently with timeout: rollback is no longer allowed.
            }
            finished.await();
            return state.get() == DONE;
        } catch (InterruptedException e) {
            if (state.compareAndSet(PENDING, CANCELLED)) {
                main.removeCallbacks(commit);
                Thread.currentThread().interrupt();
                return false;
            }
            while (state.get() != DONE) {
                try { finished.await(); } catch (InterruptedException ignored) { }
            }
            Thread.currentThread().interrupt();
            return true;
        }
    }

    // ---- 生命周期: 新版 API 把"包加载"与"system_server 启动"分成两个回调 ----

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        // 必须先拿到框架接口, 否则兼容层无法挂钩(见 XposedBridge#attachFramework)。
        XposedBridge.attachFramework(this);
        com.rikumi.colorosmod.hooks.SystemFontHooks.attach(this);
        sProcessName = param.getProcessName();
        sIsSystemServer = param.isSystemServer();
        if (sIsSystemServer) com.rikumi.colorosmod.hooks.SystemFontDistributionHooks.hook(ClassLoader.getSystemClassLoader());
        com.rikumi.colorosmod.hooks.SystemFontReplacementHooks.hook(ClassLoader.getSystemClassLoader());
        if (!sIsSystemServer) com.rikumi.colorosmod.hooks.NativeFontHooks.hook(this);
        // 尽早启动设置预热, 使 Launcher 首次加载应用列表时已能读取隐藏图标开关。
        if (!sIsSystemServer) {
            sAppContext = currentApplication();
            if (sAppContext != null && isAppHookTarget(sAppContext.getPackageName())) {
                startSettingsLoader();
            }
        }
        log("module loaded: framework=" + getFrameworkName() + " v" + getFrameworkVersion()
                + " api=" + getApiVersion() + " process=" + sProcessName);
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        log("onHotReloading: quiescing process=" + sProcessName);

        if (!XposedHelpers.beginQuiescing(2000L)) {
            log("onHotReloading: in-flight hook timeout, rejecting");
            return false;
        }
        if (!preflightProcessResources()) {
            cancelProcessPreflight();
            XposedHelpers.cancelQuiescing();
            return false;
        }
        try {
            Object[] state = new Object[21];
            state[0] = StatusBarLyricHooks.captureHotReloadHost();
            state[1] = NotificationHooks.captureHotReloadHost();
            state[2] = SystemServerHooks.captureHotReloadHost();
            state[3] = WallpapersHooks.captureHotReloadHosts();
            state[4] = KeyguardHooks.captureHotReloadHost();
            state[5] = NotificationHooks.captureHotReloadViews();
            state[6] = LauncherHooks.captureHotReloadHosts();
            state[7] = AncTileHooks.captureHotReloadHosts();
            state[8] = sSystemServerClassLoader;
            state[9] = LauncherHooks.captureHotReloadSettings();
            state[10] = SignalHooks.captureHotReloadHosts();
            state[11] = FoldIdleMediaHooks.captureHotReloadHosts();
            state[12] = StatusBarFontHooks.captureHotReloadHosts();
            state[13] = MergedCardRatioHooks.captureHotReloadHosts();
            state[14] = QsHooks.captureHotReloadNames();
            state[15] = StatusBarExtrasHooks.captureHotReloadHosts();
            state[16] = MergedRadiantHooks.captureHotReloadHosts();
            state[17] = MergedSettingsRotationHooks.capture();
            state[18] = com.rikumi.colorosmod.hooks.AppNavigationHooks.captureHosts();
            state[19] = com.rikumi.colorosmod.hooks.NativeFontHooks.captureHotReloadState();
            // Bootstrap String/Integer values only; do not carry old callbacks or caches.
            state[20] = sSettingsLoaded ? new java.util.HashMap<>(sSnapshot) : null;
            param.setSavedInstanceState(state);
        } catch (Throwable t) {
            cancelProcessPreflight();
            XposedHelpers.cancelQuiescing();
            log("onHotReloading: save host state failed: " + t);
            return false;
        }
        if (!stopSettingsLoader()) {
            cancelProcessPreflight();
            XposedHelpers.cancelQuiescing();
            return false;
        }
        if (!commitProcessCleanup()) {
            cancelProcessPreflight();
            XposedHelpers.cancelQuiescing();
            startSettingsLoader();
            log("onHotReloading: main cleanup did not start, old generation resumed");
            return false;
        }

        try { detach(); } catch (Throwable t) { log("hot reload detach failed: " + t); }
        try { XposedHelpers.clearGenerationState(); }
        catch (Throwable t) { log("hot reload generation state clear failed: " + t); }
        try { sCache.clear(); } catch (Throwable t) { log("hot reload cache clear failed: " + t); }
        sSnapshot = java.util.Collections.emptyMap();
        sSettingsLoaded = false;
        sFirstWaitDone = false;
        sAppContext = null;
        sAppProcessHooked = false;
        sSystemServerHooked = false;
        sHookedPackageName = "";
        try { XposedBridge.detachFramework(); }
        catch (Throwable t) { log("hot reload framework detach failed: " + t); }
        log("onHotReloading: old generation detached");
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        // 重要: 必须先卸载旧 hooks, 再装载新的, 否则同 method 叠加多套 callback。
        super.onHotReloaded(param);

        XposedBridge.attachFramework(this);
        com.rikumi.colorosmod.hooks.SystemFontHooks.attach(this);
        XposedHelpers.cancelQuiescing();
        sAppProcessHooked = false;
        sSystemServerHooked = false;
        // 重要: HotReloadedParam 就是 ModuleLoadedParam 的子类, 恢复进程身份, 否则新 hooks 挂不上。
        sProcessName = param.getProcessName();
        sIsSystemServer = param.isSystemServer();
        Object savedState = param.getSavedInstanceState();
        if (savedState instanceof Object[]) {
            Object[] state = (Object[]) savedState;
            if (state.length > 19) {
                com.rikumi.colorosmod.hooks.NativeFontHooks.restoreHotReloadState(state[19]);
            }
            if (state.length > 20 && state[20] instanceof java.util.Map<?, ?> previous) {
                java.util.Map<String, Integer> snapshot = new java.util.HashMap<>();
                for (java.util.Map.Entry<?, ?> entry : previous.entrySet()) {
                    if (entry.getKey() instanceof String key && entry.getValue() instanceof Integer value)
                        snapshot.put(key, value);
                }

                sSnapshot = snapshot;
                sSettingsLoaded = true;
                sFirstWaitDone = true;
            }
            if (sIsSystemServer && state.length > 8 && state[8] instanceof ClassLoader) {
                sSystemServerClassLoader = (ClassLoader) state[8];
            }
            // 必须早于 startSettingsLoader: 否则首个 snapshot 可能先发布, 导致无谓的 forceReload。
            if (!sIsSystemServer && state.length > 9) {
                try { LauncherHooks.restoreHotReloadSettings(state[9]); }
                catch (Throwable t) { log("onHotReloaded: restore launcher settings failed: " + t); }
            }
        }
        log("onHotReloaded: process=" + sProcessName + " isSystemServer=" + sIsSystemServer);

        if (sIsSystemServer) com.rikumi.colorosmod.hooks.SystemFontDistributionHooks.hook(sSystemServerClassLoader);
        com.rikumi.colorosmod.hooks.SystemFontReplacementHooks.hook(sIsSystemServer
                ? sSystemServerClassLoader : ClassLoader.getSystemClassLoader());
        android.content.Context app = sIsSystemServer ? null : currentApplication();
        if (app != null) sAppContext = app;
        if (app != null) com.rikumi.colorosmod.hooks.SystemIconPackHooks.hook(app.getClassLoader());
        if (sIsSystemServer || app != null) {
            startSettingsLoader();
        }

        if (sIsSystemServer) {
            com.rikumi.colorosmod.hooks.SystemIconPackHooks.hook(sSystemServerClassLoader);
            SystemServerHooks.hookFloatWindowEdgeHangSystemServer(systemServerLpparam());
            SystemServerHooks.hookFloatWindowEdgeHangMute(systemServerLpparam());
            com.rikumi.colorosmod.hooks.SystemFontHooks.hook(systemServerLpparam());
            SystemServerHooks.hookFloatWindowLandscapeKeepRatio(systemServerLpparam());
            SystemServerHooks.hookFloatWindowSizeLimits(systemServerLpparam());
            SystemServerHooks.hookFloatWindowCount(systemServerLpparam());
            SystemServerHooks.hookRecentsSwipeUpKillSystemServer(systemServerLpparam());
            com.rikumi.colorosmod.hooks.RecentFreeformHistoryHooks.hook(systemServerLpparam());
            SystemServerHooks.hookStatusBarThirdPartyOverlayEvents(systemServerLpparam());
            SystemServerHooks.hookCompactCanvasCaptionInsets(systemServerLpparam());
            SystemServerHooks.hookCompactFlexibleCaptionBar(systemServerLpparam());
            sSystemServerHooked = true;
        } else {
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
        try {
            if (savedState instanceof Object[]) {
                final Object[] state = (Object[]) savedState;
                if (state.length > 18) com.rikumi.colorosmod.hooks.AppNavigationHooks.restoreHosts(state[18]);
                // These restorers only replace Java references and do not touch host Views.
                try {
                    if (state.length > 1) NotificationHooks.restoreHotReloadHost(state[1]);
                } catch (Throwable t) {
                    log("onHotReloaded: restore notification host failed: " + t);
                }
                try {
                    if (state.length > 2) SystemServerHooks.restoreHotReloadHost(state[2]);
                } catch (Throwable t) {
                    log("onHotReloaded: restore system-server host failed: " + t);
                }
                try {
                    if (state.length > 4) KeyguardHooks.restoreHotReloadHost(state[4]);
                } catch (Throwable t) {
                    log("onHotReloaded: restore keyguard host failed: " + t);
                }

                Runnable restoreViews = new Runnable() {
                    @Override public void run() {
                        try {
                            if (state.length > 0) StatusBarLyricHooks.restoreHotReloadHost(state[0]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore lyric host failed: " + t);
                        }
                        try {
                            if (state.length > 3) WallpapersHooks.restoreHotReloadHosts(state[3]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore wallpaper hosts failed: " + t);
                        }
                        try {
                            if (state.length > 5) NotificationHooks.restoreHotReloadViews(state[5]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore notification views failed: " + t);
                        }
                        try {
                            if (state.length > 6) LauncherHooks.restoreHotReloadHosts(state[6]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore launcher hosts failed: " + t);
                        }
                        try {
                            if (state.length > 7) AncTileHooks.restoreHotReloadHosts(state[7]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore ANC hosts failed: " + t);
                        }
                        try {
                            if (state.length > 10) SignalHooks.restoreHotReloadHosts(state[10]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore signal hosts failed: " + t);
                        }
                        try {
                            if (state.length > 11) FoldIdleMediaHooks.restoreHotReloadHosts(state[11]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore fold media hosts failed: " + t);
                        }
                        try {
                            if (state.length > 13) MergedCardRatioHooks.restoreHotReloadHosts(state[13]);
                            if (state.length > 12) StatusBarFontHooks.restoreHotReloadHosts(state[12]);
                            if (state.length > 14) QsHooks.restoreHotReloadNames(state[14]);
                            if (state.length > 15) StatusBarExtrasHooks.restoreHotReloadHosts(state[15]);
                            if (state.length > 13) QsHooks.restoreNamesInRoots(state[13]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore status font hosts failed: " + t);
                        }
                        try {
                            if (state.length > 16) MergedRadiantHooks.restoreHotReloadHosts(state[16]);
                            if (state.length > 13) MergedRadiantHooks.restoreInRoots(state[13]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore merged tile styles failed: " + t);
                        }
                        try {
                            if (state.length > 17) MergedSettingsRotationHooks.restoreHosts(state[17]);
                        } catch (Throwable t) {
                            log("onHotReloaded: restore merged settings rotation failed: " + t);
                        }
                    }
                };
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    restoreViews.run();
                } else {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(restoreViews);
                }
            }
        } catch (Throwable t) {
            log("onHotReloaded: restore host state failed: " + t);
        }
    }

    private static XC_LoadPackage.LoadPackageParam systemServerLpparam() {
        XC_LoadPackage.LoadPackageParam lpp = new XC_LoadPackage.LoadPackageParam();
        lpp.packageName = "android";
        lpp.processName = sProcessName;

        lpp.classLoader = sSystemServerClassLoader;
        lpp.isFirstApplication = true;
        return lpp;
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {

        String packageName = param.getPackageName();
        if (!"com.android.launcher".equals(packageName)) return;
        synchronized (XposedInit.class) {
            if (sAppProcessHooked) return;
            sAppProcessHooked = true;
        }
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam();
        lpparam.packageName = packageName;
        lpparam.processName = sProcessName;
        lpparam.classLoader = param.getDefaultClassLoader();
        lpparam.appInfo = param.getApplicationInfo();
        lpparam.isFirstApplication = param.isFirstPackage();
        handleLoadPackage(lpparam);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!sIsSystemServer && sAppContext == null) {
            sAppContext = currentApplication();
        }
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam();
        lpparam.packageName = param.getPackageName();
        lpparam.processName = sProcessName;
        lpparam.classLoader = param.getClassLoader();
        lpparam.appInfo = param.getApplicationInfo();
        lpparam.isFirstApplication = param.isFirstPackage();
        // Common icon APIs also apply to additional caller apps selected in LSPosed.
        if (!MODULE_PACKAGE.equals(lpparam.packageName)) {
            com.rikumi.colorosmod.hooks.SystemIconPackHooks.hook(lpparam.classLoader);
            if (sAppContext != null) startSettingsLoader();
        }
        if ("android".equals(lpparam.packageName)) {

            if (!sIsSystemServer || sSystemServerHooked) return;
            sSystemServerHooked = true;
        } else {

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
                || "com.oplus.securitypermission".equals(packageName)
                || "com.android.settings".equals(packageName)
                || "com.oplus.wallpapers".equals(packageName)
                || "com.coloros.weather2".equals(packageName)
                || "net.oneplus.weather".equals(packageName)
                || "com.heytap.market".equals(packageName)
                || "com.android.packageinstaller".equals(packageName)
                || "com.android.providers.media.module".equals(packageName);
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        if (sSystemServerHooked) return;
        sSystemServerHooked = true;
        sSystemServerClassLoader = param.getClassLoader();
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam();
        lpparam.packageName = "android";
        lpparam.processName = sProcessName;
        lpparam.classLoader = param.getClassLoader();
        lpparam.isFirstApplication = true;
        handleLoadPackage(lpparam);
    }

    private void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        log("handleLoadPackage pkg=" + lpparam.packageName);
        sHookedPackageName = lpparam.packageName;
        // 缓存被 hook 进程自身的 Application Context, 供 readBool 通过 ContentResolver 跨进程查询设置。
        if (sAppContext == null) {
            sAppContext = currentApplication();
        }
        // 先取得 Application Context 再启动预热, 避免 worker 在 Context 尚未创建时进入退避重试。
        startSettingsLoader();
        com.rikumi.colorosmod.hooks.SystemIconPackHooks.hook(lpparam.classLoader);
        com.rikumi.colorosmod.hooks.SystemFontReplacementHooks.hook(lpparam.classLoader);
        if ("com.android.launcher".equals(lpparam.packageName)) {
            LauncherHooks.hookLauncher(lpparam);
        } else if ("com.android.systemui".equals(lpparam.packageName)) {
            SystemUiHooks.hookSystemUi(lpparam);
        } else if ("com.coloros.weather2".equals(lpparam.packageName)
                || "net.oneplus.weather".equals(lpparam.packageName)) {
            WeatherHooks.hookWeather(lpparam);
        } else if ("com.heytap.market".equals(lpparam.packageName)) {
            StorePurificationHooks.hookStore(lpparam);
        } else if ("com.android.packageinstaller".equals(lpparam.packageName)) {
            com.rikumi.colorosmod.hooks.InstallerHooks.hook(lpparam);
        } else if ("com.oplus.pscanvas".equals(lpparam.packageName)) {
            MultiWindowHooks.hookCanvasControlBar(lpparam);
        } else if ("com.oplus.safecenter".equals(lpparam.packageName)) {
            SafecenterHooks.hookSafecenter(lpparam);
        } else if ("com.oplus.securitypermission".equals(lpparam.packageName)) {
            AppStartConfirmHooks.hook(lpparam);
        } else if ("com.android.settings".equals(lpparam.packageName)) {
            SettingsHooks.hookSettings(lpparam);
        } else if ("com.oplus.wallpapers".equals(lpparam.packageName)) {
            WallpapersHooks.hookWallpapers(lpparam);
        } else if ("com.android.providers.media.module".equals(lpparam.packageName)) {
            MediaProviderHooks.hookMediaProvider(lpparam);
        } else if ("android".equals(lpparam.packageName)) {
            com.rikumi.colorosmod.hooks.SystemFontHooks.hook(lpparam);
            // system_server: 承载"贴边最小化"的真正提交逻辑(com.android.server.wm.FlexibleTaskController)
            SystemServerHooks.hookFloatWindowEdgeHangSystemServer(lpparam);
            // system_server: 贴边挂机静音, 走系统多应用音量通道
            SystemServerHooks.hookFloatWindowEdgeHangMute(lpparam);
            // system_server: 横屏应用小窗保持比例(com.android.server.wm.FlexibleTaskController)
            SystemServerHooks.hookFloatWindowLandscapeKeepRatio(lpparam);
            // system_server: 小窗缩到最小贴边不留边距 + 最大可调宽度 = 屏幕宽度
            SystemServerHooks.hookFloatWindowSizeLimits(lpparam);
            SystemServerHooks.hookFloatWindowCount(lpparam);
            // system_server: 多任务上划彻底结束进程, 配合 LauncherHooks 的 removeTask 补调
            SystemServerHooks.hookRecentsSwipeUpKillSystemServer(lpparam);
            com.rikumi.colorosmod.hooks.RecentFreeformHistoryHooks.hook(lpparam);
            // system_server: 第三方悬浮窗变化事件, 供状态栏歌词避让功能按事件刷新窗口信息。
            SystemServerHooks.hookStatusBarThirdPartyOverlayEvents(lpparam);
            // system_server: 将画布分屏嵌入应用的状态栏 inset 同步缩至三点控制栏的新高度。
            SystemServerHooks.hookCompactCanvasCaptionInsets(lpparam);
            // system_server: 同步缩小悬浮小窗顶部控制栏、系统上报高度与触摸区。
            SystemServerHooks.hookCompactFlexibleCaptionBar(lpparam);
        }

    }

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

    public static int readIntCached(String key, int def) {
        Integer snapshot = sSnapshot.get(key);
        if (snapshot != null) return snapshot;
        Object[] cached = sCache.get(key);
        if (cached != null && cached[1] instanceof Number) return ((Number) cached[1]).intValue();
        return def;
    }

    // 桌面与 system_server 只读本地值；其他进程保留首次等待和同步查询兜底。
    private static Object settingsValue(String key) {
        Integer v = sSnapshot.get(key);
        if (v != null) return v;

        if (sIsSystemServer || "com.android.launcher".equals(sProcessName)) {
            if (!sIsSystemServer && !sSettingsLoaded) requestRefresh();
            Object[] cached = sCache.get(key);
            return cached != null ? cached[1] : null;
        }
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
