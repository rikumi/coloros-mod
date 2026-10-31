package com.rikumi.colorosmod

import com.rikumi.colorosmod.hooks.AboutCardStyleResolver
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.toArgb
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Handler
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets as ComposeWindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Backup
import io.github.suqi8.coui.kmp.icon.extended.Blocklist
import io.github.suqi8.coui.kmp.icon.extended.Community
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.GridView
import io.github.suqi8.coui.kmp.icon.extended.Folder
import io.github.suqi8.coui.kmp.icon.extended.Hide
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Update
import io.github.suqi8.coui.kmp.icon.extended.Translate
import io.github.suqi8.coui.kmp.icon.extended.Tune
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

// 平板适配: 对话框等内容宽度不超过该值(480dp), 居中显示, 两侧留白用主题 surface 色填充。
internal val CONTENT_MAX_WIDTH_DP = 480.dp

// 平板两栏布局: 屏幕宽度超过 480dp 时启用; 左右栏按系统设置的 4:7 比例分配宽度。
private const val TABLET_MIN_WIDTH_DP = 640
private const val TABLET_LEFT_PANE_WEIGHT = 4f
private const val TABLET_RIGHT_PANE_WEIGHT = 7f

class MainActivity : ComponentActivity() {
    private var resumeRequest by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeRequest++
    }

    companion object {
        internal val scopeObservers = java.util.concurrent.CopyOnWriteArraySet<Runnable>()
        private val listenerRegistered = AtomicBoolean(false)
        internal val xposedServices: MutableSet<XposedService> =
            java.util.Collections.newSetFromMap(ConcurrentHashMap<XposedService, Boolean>())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val applied = view.onApplyWindowInsets(insets)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && applied.displayCutout == null) {
                // ColorOS may omit the cutout from dispatched insets while retaining it in window metrics.
                val metricsInsets = windowManager.currentWindowMetrics.windowInsets
                val cutout = metricsInsets.displayCutout
                    ?: return@setOnApplyWindowInsetsListener applied
                val type = WindowInsets.Type.displayCutout()
                WindowInsets.Builder(applied)
                    .setDisplayCutout(cutout)
                    .setInsets(type, metricsInsets.getInsets(type))
                    .setInsetsIgnoringVisibility(type, metricsInsets.getInsetsIgnoringVisibility(type))
                    .setVisible(type, metricsInsets.isVisible(type))
                    .build()
            } else {
                applied
            }
        }
        restoreRemovedThemeColor(this)
        if (listenerRegistered.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    xposedServices.add(service)
                    val context = applicationContext
                    SystemFontStore.enqueue { SystemFontStore.sync(context, service) }
                    scopeObservers.forEach { it.run() }
                }
                override fun onServiceDied(service: XposedService) {
                    xposedServices.remove(service)
                    scopeObservers.forEach { it.run() }
                    activeHotReloadBatches.forEach { it.serviceDied(service) }
                }
            })
        }
        checkEnvironment(this)
        setContent {
            val context = LocalContext.current
            val navigationEventOwner = remember {
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = NavigationEventDispatcher()
                }
            }
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navigationEventOwner,
            ) {
                COUITheme(
                    controller = remember {
                        ThemeController(
                            colorSchemeMode = ColorSchemeMode.System,
                            lightColors = lightColorScheme(
                                primary = colorOSAccentColor(context, 0xFF00B4D8)
                            ),
                            darkColors = darkColorScheme(
                                primary = colorOSAccentColor(context, 0xFF00B4D8)
                            ),
                        )
                    },
                ) {
                    SettingsStatusBar()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(COUITheme.colorScheme.surface),
                    ) {
                        // 两栏布局(平板)在 SettingsScreen 内部根据屏幕宽度决定。
                        SettingsScreen(resumeRequest)
                    }
                }
            }
        }
    }
}

// 设置页条目: 分组小标题或设置项。
internal sealed interface SettingsItem

internal data class GroupTitleItem(val title: Int) : SettingsItem

internal data class NavigationAppsItem(val title: Int) : SettingsItem
internal data class FontAppsItem(val title: Int) : SettingsItem

internal data class SwitchItem(
    val key: String,
    val label: Int,
    val subtitle: Int? = null,
    val sliderKey: String? = null,
    val sliderMax: Int = 0,
    val sliderDefault: Int = 0,
    val sliderUnit: String = "dp",
    val sliderMin: Int = 0,
    val sliderStep: Int = 1,
    val sliderDisplayScale: Float = 1f,
    val rootBacked: Boolean = false,
    val colorKey: String? = null,
    val sliderUnitRes: Int = 0,
    val masterManaged: Boolean = true,
    val masterEnableAllowed: Boolean = true,
    val defaultEnabled: Boolean = false,
) : SettingsItem

private fun SettingsItem.forDevice(isPad: Boolean): SettingsItem =
    if (isPad && this is SwitchItem) {
        when (key) {
            XposedInit.KEY_QS_MERGED_LANDSCAPE_SWAP -> copy(label = R.string.qs_merged_landscape_split_tablet)
            else -> this
        }
    } else this

internal data class FolderBlockItem(
    val key: String,
    val label: String,
    val folderName: String,
) : SettingsItem

internal data class SelectItem(
    val key: String,
    val label: Int,
    val options: List<Int>,
    val defaultValue: Int = 0,
    val valueKey: String? = null,
    val dynamicOptions: ((Context) -> List<String>)? = null,
    val masterManaged: Boolean = false,
    val masterEnabledValue: Int = 1,
    val optionValues: List<Int>? = null,
    val subtitle: Int? = null,
) : SettingsItem

private val DESKTOP: List<SettingsItem> = listOf(
    GroupTitleItem(R.string.ui_desktop_layout),
    SwitchItem("icon_gap_enabled", R.string.ui_icon_gap, sliderKey = "icon_gap_dp", sliderMax = 8, sliderDefault = 4),
    SwitchItem("drawer_columns_enabled", R.string.ui_drawer_columns),
    SwitchItem("drawer_install_time_sort_enabled", R.string.ui_drawer_install_time_sort),
    SwitchItem("drawer_letter_scroll_enabled", R.string.ui_drawer_letter_scroll, R.string.ui_drawer_letter_scroll_hint),
    SwitchItem("indicator_enabled", R.string.ui_indicator, sliderKey = "indicator_dp", sliderMax = 32, sliderDefault = 16, sliderUnit = "dp"),
    SwitchItem("edit_mode_bg_transparent_enabled", R.string.ui_edit_mode_bg),
    SwitchItem("weaken_desktop_customization_bg_enabled", R.string.ui_weaken_desktop_bg),
    SwitchItem("hide_launcher_update_dot_enabled", R.string.ui_hide_launcher_update_dot),
    GroupTitleItem(R.string.ui_icon_pack),
    SwitchItem("system_application_icon_pack", R.string.ui_system_application_icon_pack),
    SwitchItem("launcher_icon_pack_clarity_enabled", R.string.ui_launcher_icon_pack_clarity),
    GroupTitleItem(R.string.ui_long_press_menu),
    SwitchItem("disable_launcher_secondary_menu_enabled", R.string.ui_disable_secondary_menu),
    SwitchItem("shrink_popup_menu", R.string.ui_shrink_popup, sliderKey = "popup_scale_percent", sliderMax = 20, sliderDefault = 10, sliderUnit = "%"),
    SwitchItem("popup_dynamic_blur_enabled", R.string.ui_popup_blur),
    SwitchItem("desktop_popup_bg_brightness_enabled", R.string.ui_popup_brightness, sliderKey = "desktop_popup_bg_brightness", sliderMax = 10, sliderDefault = 0, sliderUnit = ""),
    GroupTitleItem(R.string.ui_folders),
    SwitchItem("folder_bg_transparent_enabled", R.string.ui_folder_transparent),
    SwitchItem("folder_anim_duration_enabled", R.string.ui_folder_duration, sliderKey = "folder_anim_duration_ms", sliderMax = 500, sliderDefault = 300, sliderUnit = "ms", sliderMin = 100),
)

private val QS: List<SettingsItem> = listOf(
    GroupTitleItem(R.string.ui_qs_general),
    SwitchItem("qs_scrim_translucent_enabled", R.string.ui_qs_brightness, sliderKey = "qs_scrim_brightness", sliderMax = 20, sliderDefault = 0, sliderUnit = "%"),
    SwitchItem("qs_blur_radius_enabled", R.string.ui_qs_blur_radius, sliderKey = "qs_blur_radius", sliderMax = 80, sliderDefault = 40, sliderUnit = ""),
    SwitchItem("qs_blur_scale_enabled", R.string.ui_qs_blur_scale, sliderKey = "qs_blur_scale", sliderMax = 100, sliderDefault = 50, sliderUnit = "%"),
    SwitchItem("qs_carrier_enabled", R.string.ui_qs_carrier),
    SwitchItem("qs_topmargin_enabled", R.string.ui_qs_topmargin),
    SwitchItem("qs_panel_switch_no_cut_enabled", R.string.ui_qs_panel_switch),
    GroupTitleItem(R.string.ui_qs_settings),
    SwitchItem("qs_tile_name_ellipsis_enabled", R.string.ui_qs_tile_ellipsis),
    SwitchItem("fold_idle_media", R.string.ui_fold_idle_media),
    SwitchItem("qs_fill_empty", R.string.ui_qs_fill_empty),
    SwitchItem("qs_corner_radius_enabled", R.string.ui_qs_normal_corner, subtitle = R.string.ui_qs_corner_hint, sliderKey = "qs_corner_radius_percent", sliderMin = 20, sliderMax = 50, sliderDefault = 25, sliderUnit = "%"),
    SwitchItem("qs_clock_no_expand_anim_enabled", R.string.ui_qs_clock_single_line),
    SwitchItem(HomeControlsTileSettings.KEY, R.string.ui_module_home_controls_tile, R.string.ui_module_home_controls_tile_hint),
    SwitchItem("anc_tile_enabled", R.string.ui_anc_tile),
    GroupTitleItem(R.string.qs_merged_modernization),
    SwitchItem("qs_merged_hollow", R.string.qs_merged_hollow),
    SwitchItem("qs_merged_radiant", R.string.qs_merged_radiant),
    SwitchItem(XposedInit.KEY_QS_MERGED_LANDSCAPE_SWAP, R.string.qs_merged_landscape_swap),
    SwitchItem("qs_merged_card_ratio", R.string.qs_merged_card_ratio),
    SwitchItem("qs_merged_gap_enabled", R.string.qs_merged_gap_enabled, sliderKey = "qs_merged_gap_percent_tenths", sliderMin = 40, sliderMax = 60, sliderStep = 5, sliderDefault = 50, sliderDisplayScale = 0.1f, sliderUnit = "%"),
    SwitchItem("qs_merged_four_columns", R.string.qs_merged_four_columns),
    SwitchItem(XposedInit.KEY_QS_MERGED_MEDIA_TILES_ON_TOP, R.string.qs_merged_media_tiles_on_top),
    SwitchItem("qs_merged_settings_rotation", R.string.qs_merged_settings_rotation),
    SwitchItem("qs_merged_tile_scale_animation", R.string.qs_merged_tile_scale_animation),
    SwitchItem("qs_merged_tile_clip_animation", R.string.qs_merged_tile_clip_animation),
    SwitchItem("qs_merged_two_finger_expand", R.string.qs_merged_two_finger_expand),
    SwitchItem("qs_active_outline_enabled", R.string.qs_active_outline_enabled),
    SwitchItem(XposedInit.KEY_QS_ACTIVE_COLOR_ENABLED, R.string.qs_active_color_enabled,
        colorKey = XposedInit.KEY_QS_ACTIVE_COLOR, masterEnableAllowed = false),
)
private val NOTIF: List<SettingsItem> = listOf(
    GroupTitleItem(R.string.ui_notif_settings),
    SwitchItem("notification_swipe_to_dismiss_enabled", R.string.ui_notification_swipe),
    SwitchItem("notification_pull_expand_enabled", R.string.ui_notification_pull),
    SwitchItem("notification_subtitle_enabled", R.string.ui_notification_subtitle, sliderKey = "notification_subtitle_sp", sliderMax = 16, sliderDefault = 8, sliderUnit = "sp"),
    SwitchItem("notification_padding_enabled", R.string.ui_notification_padding, sliderKey = "notification_padding_dp", sliderMax = 8, sliderDefault = 4),
    SwitchItem("notification_real_app_name_enabled", R.string.ui_notification_real_app_name),
    SwitchItem("notification_center_monochrome_icons_enabled", R.string.ui_notification_center_monochrome_icons,
        masterEnableAllowed = false),
    SelectItem(XposedInit.KEY_NOTIFICATION_LAYOUT_STYLE, R.string.ui_notification_layout,
        listOf(R.string.ui_notification_layout_default, R.string.ui_notification_layout_legacy, R.string.ui_notification_layout_relaxed),
        defaultValue = XposedInit.NOTIFICATION_LAYOUT_STYLE_DEFAULT, masterManaged = true, masterEnabledValue = 2),
    GroupTitleItem(R.string.ui_statusbar_settings),
    SwitchItem("statusbar_monochrome_notification_icons_enabled", R.string.ui_statusbar_monochrome_notification_icons),
    SwitchItem("merge_dual_sim_signal", R.string.ui_merge_dual_sim),
    SwitchItem("hide_battery_percent_sign_enabled", R.string.ui_hide_battery_percent),
    SwitchItem("hide_roaming_icon", R.string.ui_hide_roaming),
    SelectItem("signal_network_type_mode", R.string.ui_signal_network_type, listOf(R.string.ui_option_default_display, R.string.ui_option_separate_display, R.string.ui_option_hide)),
    SelectItem("status_bar_clock_font", R.string.ui_statusbar_font, listOf(R.string.ui_option_system_default, R.string.ui_font_inter, R.string.ui_font_manrope, R.string.ui_font_rubik, R.string.ui_font_lato)),
    SelectItem("status_bar_clock_weight", R.string.ui_statusbar_weight, listOf(R.string.ui_option_follow_system, R.string.ui_weight_100, R.string.ui_weight_200, R.string.ui_weight_300, R.string.ui_weight_400, R.string.ui_weight_500, R.string.ui_weight_600, R.string.ui_weight_700, R.string.ui_weight_800, R.string.ui_weight_900)),
    SelectItem("statusbar_lyric_mode", R.string.ui_statusbar_lyric,
        listOf(R.string.ui_lyric_mode_off, R.string.ui_lyric_mode_notification),
        masterManaged = true, masterEnabledValue = 1),
    SwitchItem("fluid_cloud_lyric_enabled", R.string.fluid_cloud_lyric, masterManaged = false),
    SwitchItem("statusbar_lyric_avoid_third_party_enabled", R.string.ui_statusbar_lyric_avoid, R.string.ui_statusbar_lyric_avoid_hint),
    SwitchItem("fluid_cloud_keep_percent_enabled", R.string.ui_fluid_cloud_percent),
)
private val HIDDEN: List<SettingsItem> = listOf(
    GroupTitleItem(R.string.ui_hidden_logic),
    SwitchItem("recents_show_hidden_enabled", R.string.ui_recents_show_hidden),
    SwitchItem("hide_apps_noverify_enabled", R.string.ui_hide_apps_noverify),
    SwitchItem("pinch_out_open_hide_apps_enabled", R.string.ui_pinch_open_hidden),
    SwitchItem("hide_apps_title_folder_enabled", R.string.ui_hide_apps_folder_title),
    GroupTitleItem(R.string.ui_fully_hidden_apps),
    SwitchItem("hidden_launcher_apps_enabled", R.string.ui_hidden_launcher_apps),
)

// 小窗相关设置: 改动需重启 system_server(框架) 才生效(本模块该作用域为 android/system_server)。
private val FLOATWINDOW: List<SettingsItem> = listOf(
    GroupTitleItem(R.string.ui_float_behavior),
    SwitchItem("float_window_count_enabled", R.string.ui_float_count, sliderKey = "float_window_count", sliderMin = 1, sliderMax = 10, sliderDefault = 3, sliderUnit = ""),
    SwitchItem("recents_hide_freeform_enabled", R.string.ui_recents_hide_freeform),
    SwitchItem("recents_hide_closed_freeform_enabled", R.string.ui_recents_hide_closed_freeform),
    SwitchItem("float_window_edge_hang_enabled", R.string.ui_float_edge_hang),
    SwitchItem("float_window_edge_hang_mute_enabled", R.string.ui_float_edge_mute),
    GroupTitleItem(R.string.ui_float_visual),
    SwitchItem("float_window_edge_hang_white_bar_enabled", R.string.ui_float_white_bar),
    SwitchItem("float_window_landscape_keep_ratio_enabled", R.string.ui_float_landscape_ratio, R.string.ui_float_landscape_ratio_hint),
    SwitchItem("float_window_edge_size_optimize_enabled", R.string.ui_float_edge_size),
    SwitchItem("shrink_caption_bar_enabled", R.string.ui_shrink_caption),
)

private val NAV: List<SettingsItem> = listOf(
    GroupTitleItem(R.string.ui_gesture_behavior),
    SwitchItem("gesture_bar_height_enabled", R.string.ui_gesture_bar_height, R.string.ui_gesture_bar_height_hint, "gesture_bar_height_dp", 24, 12),
    SwitchItem("mback_enabled", R.string.ui_mback, R.string.ui_mback_hint),
    SwitchItem("gesture_touch_through_enabled", R.string.ui_gesture_touch_through),
    SwitchItem("rotation_button_fixed_position_enabled", R.string.ui_rotation_button, R.string.ui_rotation_button_hint),
    SwitchItem("disable_rotation_suggestions", R.string.ui_disable_rotation_suggestions, rootBacked = true,
        masterEnableAllowed = false),
    SwitchItem("long_press_timeout_enabled", R.string.ui_long_press_timeout, sliderKey = "long_press_timeout_ms", sliderMax = 600, sliderDefault = 300, sliderUnit = "ms", sliderMin = 100, sliderStep = 50, rootBacked = true),
    SwitchItem("animation_duration_scale_enabled", R.string.ui_animation_scale, sliderKey = "animation_duration_scale", sliderMax = 20, sliderDefault = 15, sliderUnit = "", sliderUnitRes = R.string.ui_unit_multiplier, sliderDisplayScale = 0.05f, rootBacked = true),
    GroupTitleItem(R.string.ui_gesture_visual),
    SwitchItem(AppNavigationSettings.ENABLED, R.string.ui_app_immersive_navigation, masterManaged = false),
    NavigationAppsItem(R.string.ui_navigation_apps),
    SwitchItem("gesture_bar_width_enabled", R.string.ui_gesture_bar_width, sliderKey = "gesture_bar_width_dp", sliderMax = 120, sliderDefault = 100, sliderUnit = "dp", sliderMin = 80),
    SwitchItem("gesture_bar_long_press_disable_enabled", R.string.ui_gesture_bar_animation, R.string.ui_gesture_bar_animation_hint),
    GroupTitleItem(R.string.ui_recents_switching),
    SwitchItem("power_save_keep_locked_tasks_enabled", R.string.ui_power_save_locked),
    SwitchItem("recents_bg_transparent_enabled", R.string.ui_recents_transparent),
    SwitchItem("recents_hide_not_running_enabled", R.string.ui_recents_hide_not_running),
    SwitchItem("recents_swipe_up_kill_enabled", R.string.ui_recents_swipe_kill, R.string.ui_zygote_hint),
    SwitchItem("recents_swipe_up_kill_subsidiary_enabled", R.string.ui_recents_kill_subsidiary, R.string.ui_recents_kill_subsidiary_hint),
)

private val LOCKSCREEN: List<SettingsItem> = listOf(
    GroupTitleItem(R.string.ui_lock_behavior),
    SwitchItem("unlocked_shutdown_noverify_enabled", R.string.ui_shutdown_noverify, R.string.ui_shutdown_noverify_hint),
    SwitchItem("keyguard_fingerprint_failure_delay_enabled", R.string.ui_fingerprint_failure_delay),
    SwitchItem("keyguard_slide_input_enabled", R.string.ui_slide_input),
    SwitchItem("keyguard_bouncer_swipe_back_enabled", R.string.ui_bouncer_swipe_back, R.string.ui_bouncer_swipe_back_hint),
    GroupTitleItem(R.string.ui_lock_visual),
    SwitchItem("keyguard_hide_carrier_enabled", R.string.ui_keyguard_hide_carrier),
    SelectItem("keyguard_clock_font", R.string.ui_keyguard_clock_font,
        listOf(R.string.ui_option_system_default, R.string.ui_font_inter, R.string.ui_font_manrope, R.string.ui_font_rubik, R.string.ui_font_lato),
        defaultValue = 1, masterManaged = true, masterEnabledValue = 1),
    SwitchItem("keyguard_clock_monospace", R.string.ui_keyguard_clock_monospace, defaultEnabled = true),
    SwitchItem("keyguard_clock_spacing_enabled", R.string.ui_keyguard_clock_spacing,
        sliderKey = "keyguard_clock_spacing", sliderMin = -10, sliderMax = 10, sliderDefault = -4,
        sliderDisplayScale = 0.01f, sliderUnit = "em", defaultEnabled = true),
    SelectItem("keyguard_clock_weight", R.string.ui_keyguard_clock_weight,
        listOf(R.string.ui_option_follow_system, R.string.ui_weight_100, R.string.ui_weight_200, R.string.ui_weight_300,
            R.string.ui_weight_400, R.string.ui_weight_500, R.string.ui_weight_600, R.string.ui_weight_700, R.string.ui_weight_800, R.string.ui_weight_900),
        defaultValue = 3, masterManaged = true, masterEnabledValue = 3),
    SwitchItem("keyguard_no_light_effect_enabled", R.string.ui_keyguard_no_light, R.string.ui_keyguard_no_light_hint),
    SwitchItem("keyguard_bouncer_brightness_enabled", R.string.ui_keyguard_brightness, sliderKey = "keyguard_bouncer_brightness", sliderMax = 5, sliderDefault = 0, sliderUnit = "%"),
    SwitchItem("keyguard_notification_offset_enabled", R.string.ui_keyguard_notification_offset, sliderKey = "keyguard_notification_offset_dp", sliderMax = 40, sliderDefault = 20),
    SwitchItem("keyguard_no_charge_anim_enabled", R.string.ui_keyguard_no_charge),
)

private val PURIFICATION: List<SettingsItem> = listOf(
    GroupTitleItem(R.string.ui_system_settings_group),
    SelectItem("about_device_card_style", R.string.ui_about_device_card,
        listOf(R.string.ui_about_card_default, R.string.ui_about_card_coloros17, R.string.ui_about_card_coloros161, R.string.ui_about_card_coloros16, R.string.ui_about_card_coloros15,
            R.string.ui_about_card_oxygenos, R.string.ui_about_card_oxygenos15, R.string.ui_about_card_realme16, R.string.ui_about_card_realme15, R.string.ui_about_card_mod),
        optionValues = listOf(0, 9, 6, 1, 2, 3, 5, 8, 7, 4),
        subtitle = R.string.ui_about_device_card_hint),
    SwitchItem("about_device_hide_marketing_enabled", R.string.ui_about_device_hide_marketing),
    SwitchItem("settings_disabled_module_spacing", R.string.ui_settings_disabled_module_spacing),
    GroupTitleItem(R.string.installer_group),
    SwitchItem(InstallerSettings.RESTORE, R.string.installer_restore_legacy, rootBacked = true, masterManaged = false),
    SwitchItem("installer_disable_virus_scan", R.string.installer_disable_virus_scan),
    SwitchItem("installer_hide_finish_suggestions", R.string.installer_hide_finish_suggestions),
    SwitchItem("installer_show_first_header", R.string.installer_show_first_header),
    GroupTitleItem(R.string.store_group),
    SwitchItem("store_block_splash", R.string.store_block_splash),
    SwitchItem("store_hide_featured", R.string.store_hide_featured),
    SwitchItem("store_hide_games", R.string.store_hide_games),
    SwitchItem("store_hide_rank", R.string.store_hide_rank),
    SwitchItem("store_empty_software", R.string.store_empty_software),
    SwitchItem("store_disable_dynamic_icons", R.string.store_disable_dynamic_icons),
    SwitchItem("store_disable_special_styles", R.string.store_disable_special_styles),
    SwitchItem("store_empty_home", R.string.store_empty_home),
    SwitchItem("store_hide_mine_recommendations", R.string.store_hide_mine_recommendations),
    SwitchItem("store_hide_mine_booking", R.string.store_hide_mine_booking),
    SwitchItem("store_hide_mine_welfare_quick", R.string.store_hide_mine_welfare_quick),
    SwitchItem("store_hide_mine_clean", R.string.store_hide_mine_clean),
    SwitchItem("store_hide_detail_recommendations", R.string.store_hide_detail_recommendations),
    SwitchItem("store_hide_download_recommendations", R.string.store_hide_download_recommendations),
    SwitchItem("store_hide_update_recommendations", R.string.store_hide_update_recommendations),
    SwitchItem("store_clean_search", R.string.store_clean_search),
    GroupTitleItem(R.string.weather_group_weather),
    SwitchItem("weather_horizontal_forecast", R.string.weather_horizontal_forecast),
    SwitchItem("weather_hide_air_quality", R.string.weather_hide_air_quality),
    SwitchItem("weather_hide_sun", R.string.weather_hide_sun),
    SwitchItem("weather_hide_cctv", R.string.weather_hide_cctv),
    SwitchItem("weather_hide_life", R.string.weather_hide_life),
    SwitchItem("weather_hide_ads", R.string.weather_hide_ads),
    SwitchItem("weather_hide_pollen", R.string.weather_hide_pollen),
    SwitchItem("weather_hide_clothing", R.string.weather_hide_clothing),
    SwitchItem("weather_hide_sunscreen", R.string.weather_hide_sunscreen),
    SwitchItem("weather_hide_makeup", R.string.weather_hide_makeup),
    SwitchItem("weather_hide_flu", R.string.weather_hide_flu),
    SwitchItem("weather_hide_operations", R.string.weather_hide_operations),
    SwitchItem("weather_hide_meteorology_bg", R.string.weather_hide_meteorology_bg),
    SwitchItem("weather_navigation_padding", R.string.weather_navigation_padding),
    SwitchItem("weather_hide_voice", R.string.weather_hide_voice),
    SwitchItem("weather_hide_theme", R.string.weather_hide_theme),
    SwitchItem("weather_legacy_background", R.string.weather_legacy_background),
    SelectItem("weather_api_region", R.string.weather_api_region, listOf(R.string.weather_api_default, R.string.weather_api_china, R.string.weather_api_overseas)),
    GroupTitleItem(R.string.app_start_confirm_permission_group),
    SwitchItem("app_start_confirm_always_allow_enabled", R.string.app_start_confirm_always_allow),
)

private val SYSTEM_FONTS: List<SettingsItem> = listOf(
    SelectItem("system_font_zh", R.string.ui_system_font_zh, listOf(R.string.ui_font_default, R.string.ui_font_source_han, R.string.ui_font_custom)),
    SelectItem("system_font_latin", R.string.ui_system_font_latin, listOf(R.string.ui_font_default, R.string.ui_font_inter, R.string.ui_font_manrope, R.string.ui_font_custom)),
    SelectItem("system_font_mono", R.string.ui_system_font_mono, listOf(R.string.ui_font_default, R.string.ui_font_maple, R.string.ui_font_jetbrains, R.string.ui_font_fira, R.string.ui_font_cascadia, R.string.ui_font_custom), defaultValue = -1, optionValues = listOf(-1, 0, 1, 2, 3, 4)),
    SelectItem("system_font_emoji", R.string.ui_system_font_emoji, listOf(R.string.ui_font_default, R.string.ui_font_emoji_apple, R.string.ui_font_emoji_joypixels, R.string.ui_font_emoji_twitter, R.string.ui_font_emoji_samsung)),
    SwitchItem("system_font_disable_replacement", R.string.ui_system_font_disable_replacement, subtitle = R.string.ui_system_font_disable_replacement_hint),
    SwitchItem("system_font_mount_enabled", R.string.ui_system_font_mount_enabled, masterManaged = false),
    FontAppsItem(R.string.ui_system_font_mount_apps),
)

private val STORAGE: List<SettingsItem> = listOf(
    GroupTitleItem(R.string.ui_storage_folders),
    FolderBlockItem("media_folder_block_alarms", "Alarms", "Alarms"),
    FolderBlockItem("media_folder_block_audiobooks", "Audiobooks", "Audiobooks"),
    FolderBlockItem("media_folder_block_movies", "Movies", "Movies"),
    FolderBlockItem("media_folder_block_notifications", "Notifications", "Notifications"),
    FolderBlockItem("media_folder_block_podcasts", "Podcasts", "Podcasts"),
    FolderBlockItem("media_folder_block_recordings", "Recordings", "Recordings"),
    FolderBlockItem("media_folder_block_ringtones", "Ringtones", "Ringtones"),
)

private data class Category(
    val id: String,
    val title: Int,
    val icon: ImageVector,
    val items: List<SettingsItem>,
    val subtitle: Int? = null,
    val hint: Int? = null,
)

// 首页分组: 每组一张卡片。锁屏紧随通知中心, 其余三个另开一组。
private val CATEGORY_GROUPS: List<List<Category>> = listOf(
    listOf(
        Category("desktop", R.string.ui_category_desktop, COUIIcons.GridView, DESKTOP),
        Category("quick_settings", R.string.ui_category_qs, COUIIcons.Tune, QS),
        Category("notification", R.string.ui_category_notif, COUIIcons.Community, NOTIF),
        Category("lockscreen", R.string.ui_category_lock, COUIIcons.Lock, LOCKSCREEN),
    ),
    listOf(
        Category("hidden_apps", R.string.ui_category_hidden, COUIIcons.Hide, HIDDEN),
        Category("float_window", R.string.ui_category_float, COUIIcons.Copy, FLOATWINDOW, hint = R.string.ui_float_zygote_hint),
        Category("navigation", R.string.ui_category_navigation, COUIIcons.Backup, NAV),
        Category("system_fonts", R.string.ui_category_system_fonts, COUIIcons.Translate, SYSTEM_FONTS, hint = R.string.ui_system_font_zygote_hint),
    ),
    listOf(
        Category("purification", R.string.weather_category_purification, COUIIcons.Tune, PURIFICATION),
        Category("storage", R.string.ui_category_storage, COUIIcons.Folder, STORAGE),
        Category(DISABLED_APPS_ID, R.string.ui_category_disabled, COUIIcons.Blocklist, emptyList()),
        Category(HIDDEN_NOTIFICATIONS_ID, R.string.ui_category_hidden_notifications, COUIIcons.Hide, emptyList()),
    ),
)
private val CATEGORIES = CATEGORY_GROUPS.flatten()

// "停用应用"分类 id: 该分类没有开关项, 子页面在路由里特判渲染为只读列表。
private const val DISABLED_APPS_ID = "disabled_apps"
private const val HIDDEN_NOTIFICATIONS_ID = "hidden_notifications"
private const val HIDDEN_LAUNCHER_APPS_ID = "hidden_launcher_apps"
private const val NAVIGATION_APPS_ID = "navigation_apps"
private const val FONT_APPS_ID = "font_mount_apps"

private data class MasterOverride(val scope: String?, val value: Boolean)

private fun MasterOverride?.valueFor(scope: String?): Boolean? =
    this?.takeIf { it.scope == null || it.scope == scope }?.value

// 设置分组: desc 为分组小标题资源 ID(0 仅分隔不显示标题), items 为该组全部设置项。
private data class SwitchGroup(val desc: Int?, val items: List<SettingsItem>)

// 过滤出实际设置项(GroupTitleItem 只是分组标题, 无 key 不参与开关)。
private val List<SettingsItem>.switches: List<SwitchItem> get() = filterIsInstance<SwitchItem>()

// 分类/全局顶部“一键启用”所管理的项目；目录屏蔽项也属于可启用功能。
private val List<SettingsItem>.masterItems: List<SettingsItem>
    get() = filter {
        (it is SwitchItem && (it.masterManaged || it.key == AppNavigationSettings.ENABLED)) || it is FolderBlockItem
                || (it is SelectItem && it.masterManaged)
    }

// 一键启用开关的“已启用”判定只看显式落盘的值, 忽略 defaultEnabled/defaultValue,
// 这样全新安装时(默认开启项尚未写入)主开关保持关闭, 由用户主动一键启用。
private fun SettingsItem.isEnabledInMaster(prefs: SharedPreferences): Boolean = when (this) {
    is SwitchItem -> prefs.getBoolean(settingKey, false)
    is FolderBlockItem -> prefs.getBoolean(settingKey, false)
    is SelectItem -> prefs.getInt(key, 0) != 0
    is GroupTitleItem, is NavigationAppsItem, is FontAppsItem -> false
}

private val SettingsItem.settingKey: String
    get() = when (this) {
        is SwitchItem -> key
        is FolderBlockItem -> key
        is SelectItem -> key
        is GroupTitleItem, is NavigationAppsItem, is FontAppsItem -> ""
    }

private fun List<SettingsItem>.splitByDivider(): List<SwitchGroup> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<SwitchGroup>()
    var desc: Int? = null
    var current = mutableListOf<SettingsItem>()
    forEach { item ->
        when (item) {
            is GroupTitleItem -> {
                if (current.isNotEmpty()) result.add(SwitchGroup(desc, current))
                desc = item.title
                current = mutableListOf()
            }
            is SwitchItem -> current.add(item)
            is FolderBlockItem -> current.add(item)
            is SelectItem -> current.add(item)
            is NavigationAppsItem -> current.add(item)
            is FontAppsItem -> current.add(item)
        }
    }
    if (current.isNotEmpty()) result.add(SwitchGroup(desc, current))
    return result
}

// 主开关标题: 未启用时是引导点击的"一键启用"; 启用后首页为"启用模块", 子页面为"启用功能"。
private val MASTER_TITLE_OFF = R.string.ui_master_off
private val HOME_MASTER_TITLE_ON = R.string.ui_master_home_on
private val CATEGORY_MASTER_TITLE_ON = R.string.ui_master_category_on

// 首页主开关副标题(针对全部功能)。
private val HOME_MASTER_HINT = R.string.ui_master_hint

// 子页面设置组(第二个 group)的 header: 说明滑块两端值的含义。
private val SLIDER_GROUP_HINT = R.string.ui_slider_hint

// 主开关"启用模块"切换动画的时长(ms): 先更新 UI 播放动画, 动画结束后才落盘设置。
private const val MASTER_TOGGLE_ANIM_MS = 350L

internal fun parentSettingsPage(id: String?): String? = when (id) {
    HIDDEN_LAUNCHER_APPS_ID -> "hidden_apps"
    NAVIGATION_APPS_ID -> "navigation"
    FONT_APPS_ID -> "system_fonts"
    else -> null
}

@Composable
fun SettingsScreen(resumeRequest: Int = 0) {
    val missingScopes = rememberMissingScopes(resumeRequest)
    val ctx = LocalContext.current
    val prefs = remember { ctx.settingsPrefs() }
    val isPad = LocalConfiguration.current.smallestScreenWidthDp >= 600

    val scope = rememberCoroutineScope()
    // version 变化时强制重读 prefs 同步所有开关状态。
    var version by remember { mutableStateOf(0) }
    val cardStyle = prefs.getInt("about_device_card_style", 0)
    val bannerCache = remember(cardStyle) { HomeBannerCache() }
    LaunchedEffect(bannerCache) {
        bannerCache.info = withContext(Dispatchers.IO) {
            val properties = runRoot("getprop").orEmpty().lineSequence().mapNotNull { line ->
                val match = Regex("^\\[([^]]+)]: \\[(.*)]$").matchEntire(line)
                match?.let { it.groupValues[1] to it.groupValues[2] }
            }.toMap()
            val resolvedStyle = AboutCardStyleResolver.resolve(
                properties["ro.oplus.image.system_ext.area"],
                properties["ro.oplus.image.system_ext.brand"],
                properties["ro.build.version.ota"],
                properties["ro.build.version.oplusrom.display"],
                properties["ro.build.version.oplusrom"], Build.DISPLAY,
            )
            HomeBannerInfo(resolvedStyle, deviceName(ctx), systemLabel())
        }
    }

    var masterOverride by remember { mutableStateOf<MasterOverride?>(null) }

    // 当前打开的分类 id; null 表示停在首页。
    var openCategoryId by remember { mutableStateOf<String?>(null) }

    // 主开关: scope = null 作用于全部功能(首页), 否则只作用于该分类的设置项。
    val onMasterChange: (String?, Boolean) -> Unit = { targetScope, target ->
        val items = (targetScope?.let { id -> CATEGORIES.first { it.id == id }.items.masterItems }
            ?: CATEGORIES.flatMap { it.items.masterItems }).map { it.forDevice(isPad) }
        masterOverride = MasterOverride(targetScope, target)
        version++
        scope.launch {
            delay(MASTER_TOGGLE_ANIM_MS)
            // 动画结束后再真正写入设置; IO 线程执行, 避免阻塞 UI。
                withContext(Dispatchers.IO) {
                    val enableHomeControls = if (target && items.any { it is SwitchItem && it.key == HomeControlsTileSettings.KEY }) {
                        val region = getProp("persist.sys.oplus.region")
                        if (region != null) region.equals("CN", ignoreCase = true)
                        else getProp("persist.sys.oplus.ota_ver_display")?.contains("CN01", ignoreCase = true) == true
                    } else false
                    items.forEach { item ->
                    when (item) {
                        is SwitchItem -> {
                            // 与 flyme-mod 一致：一键关闭包含按应用功能，一键开启不替用户启用它。
                            if (target && (!item.masterEnableAllowed || item.key == AppNavigationSettings.ENABLED
                                    || (item.key == HomeControlsTileSettings.KEY && !enableHomeControls))) return@forEach
                            if (item.rootBacked) {
                                applySystemSetting(item.key, target, item.sliderDefault)
                            } else {
                                setBool(ctx, item.key, target)
                                if (!target) item.sliderKey?.let { setInt(ctx, it, item.sliderDefault) }
                                // 一键启用“彻底隐藏应用”时, 默认补上电话本与 Gboard(若已安装)。
                                if (target && item.key == HiddenLauncherApps.ENABLED) addDefaultHiddenApps(ctx)
                            }
                        }
                        is FolderBlockItem -> {
                            setBool(ctx, item.key, target)
                            if (target) deleteEmptyMediaFolder(item.folderName)
                            else createMediaFolder(item.folderName)
                        }
                        is SelectItem -> setInt(ctx, item.key, if (target) item.masterEnabledValue else 0)
                        else -> Unit
                    }
                }
            }

            if (masterOverride == MasterOverride(targetScope, target)) {
                masterOverride = null
                version++
            }
        }
    }

    val isTablet = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP
    val navigate: (String?) -> Unit = { openCategoryId = it }

    if (isTablet) {
        val cutout = ComposeWindowInsets.displayCutout
        val cutoutPadding = cutout.exclude(
            ComposeWindowInsets(left = SETTINGS_GROUP_HMARGIN, right = SETTINGS_GROUP_HMARGIN),
        )
        // 平板两栏: 左栏较窄显示首页, 右栏显示分类/子页面, 默认打开桌面设置。
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(cutoutPadding)
                .consumeWindowInsets(cutout),
        ) {
            val anyEnabled = remember(version) {
                CATEGORIES.any { c -> c.items.masterItems.any { it.isEnabledInMaster(prefs) } }
            }
            Column(
                Modifier
                    .weight(TABLET_LEFT_PANE_WEIGHT)
                    .fillMaxHeight(),
            ) {
                HomeScreen(
                    cardStyle = cardStyle,
                    bannerCache = bannerCache,
                    missingScopes = missingScopes,
                    masterChecked = masterOverride.valueFor(null) ?: anyEnabled,
                    onMasterChange = { onMasterChange(null, it) },
                    // 桌面设置是右栏默认页, 点击它回到 null 以保持返回键能退出。
                    onOpenCategory = { id -> navigate(if (id == "desktop") null else id) },
                    selectedCategoryId = when (openCategoryId) {
                        HIDDEN_LAUNCHER_APPS_ID -> "hidden_apps"
                        NAVIGATION_APPS_ID -> "navigation"
                        FONT_APPS_ID -> "system_fonts"
                        else -> openCategoryId ?: "desktop"
                    },
                    showRestartMenu = false,
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.16f)),
            )
            Column(Modifier.weight(TABLET_RIGHT_PANE_WEIGHT).fillMaxHeight()) {
                SettingsPageHost(openCategoryId, onBackCompleted = { openCategoryId = it }) { targetId ->
                    DetailPane(
                        targetId = targetId ?: "desktop",
                        ctx = ctx,
                        prefs = prefs,
                        version = version,
                        masterOverride = masterOverride,
                        onMasterChange = onMasterChange,
                        onItemChanged = { version++ },
                        navigate = navigate,
                        showBack = false,
                    )
                }
            }
        }
    } else {
        SettingsPageHost(openCategoryId, onBackCompleted = { openCategoryId = it }) { targetId ->
            if (targetId == null) {
                val anyEnabled = remember(version) {
                    CATEGORIES.any { c -> c.items.masterItems.any { it.isEnabledInMaster(prefs) } }
                }
                HomeScreen(
                    cardStyle = cardStyle,
                    bannerCache = bannerCache,
                    missingScopes = missingScopes,
                    masterChecked = masterOverride.valueFor(null) ?: anyEnabled,
                    onMasterChange = { onMasterChange(null, it) },
                    onOpenCategory = navigate,
                )
            } else {
                DetailPane(
                    targetId = targetId,
                    ctx = ctx,
                    prefs = prefs,
                    version = version,
                    masterOverride = masterOverride,
                    onMasterChange = onMasterChange,
                    onItemChanged = { version++ },
                    navigate = navigate,
                )
            }
        }
    }
}

// 二级/更内层页面路由: 分类、隐藏应用、停用应用列表。
@Composable
private fun DetailPane(
    targetId: String,
    ctx: Context,
    prefs: SharedPreferences,
    version: Int,
    masterOverride: MasterOverride?,
    onMasterChange: (String?, Boolean) -> Unit,
    onItemChanged: () -> Unit,
    navigate: (String?) -> Unit,
    showBack: Boolean = true,
) {
    when (targetId) {
        HIDDEN_LAUNCHER_APPS_ID -> HiddenAppsScreen(context = ctx, onBack = { navigate("hidden_apps") })
        NAVIGATION_APPS_ID -> HiddenAppsScreen(context = ctx, onBack = { navigate("navigation") }, navigationApplications = true)
        FONT_APPS_ID -> HiddenAppsScreen(context = ctx, onBack = { navigate("system_fonts") }, fontApplications = true)
        DISABLED_APPS_ID -> DisabledAppsScreen(ctx = ctx, onBack = { navigate(null) }, showBack = showBack)
        HIDDEN_NOTIFICATIONS_ID -> HiddenNotificationsScreen(ctx = ctx, onBack = { navigate(null) }, showBack = showBack)
        else -> {
            val category = CATEGORIES.firstOrNull { it.id == targetId } ?: return
            val override = masterOverride.valueFor(category.id)
            val anyEnabled = remember(version) { category.items.masterItems.any { it.isEnabledInMaster(prefs) } }
            CategoryScreen(
                category = category,
                prefs = prefs,
                ctx = ctx,
                version = version,
                overrideValue = override,
                masterChecked = override ?: anyEnabled,
                onMasterChange = { onMasterChange(category.id, it) },
                onItemChanged = onItemChanged,
                onBack = { navigate(null) },
                onOpenHiddenApps = { navigate(HIDDEN_LAUNCHER_APPS_ID) },
                onOpenNavigationApps = { navigate(NAVIGATION_APPS_ID) },
                onOpenFontApps = { navigate(FONT_APPS_ID) },
                showBack = showBack,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    cardStyle: Int,
    bannerCache: HomeBannerCache,
    missingScopes: Boolean,
    masterChecked: Boolean,
    onMasterChange: (Boolean) -> Unit,
    onOpenCategory: (String) -> Unit,
    selectedCategoryId: String? = null,
    showRestartMenu: Boolean = true,
) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    val listScroll = rememberSettingsListScroll(listState)
    val headerInfo = bannerCache.info
    val headerStyle = if (cardStyle in listOf(1, 2, 3, 5, 6, 7, 8, 9)) cardStyle else headerInfo?.style
    Scaffold(
        topBar = {
            SettingsLargeTitle(
                title = "ColorOS Mod",
                scrollBehavior = listScroll.appBar,
                actions = { if (showRestartMenu) RestartMenu(ctx) },
            )
        },
    ) { padding ->
        SettingsLazyColumn(
            scroll = listScroll,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                ,
        ) {
            if (missingScopes) {
                item(key = "scope_warning") { ScopeWarningCard() }
            }
            item {
                SettingsMasterToggle(
                    checked = masterChecked,
                    title = ctx.getString(if (masterChecked) HOME_MASTER_TITLE_ON else MASTER_TITLE_OFF),
                    // 启用后无需再解释, 仅未启用时提示。
                    subtitle = if (masterChecked) null else ctx.getString(HOME_MASTER_HINT),
                    onCheckedChange = onMasterChange,
                    // 机型横幅作为第一张卡片的首项, 只裁顶部两角(下方与开关行相接)。
                    aboveContent = {
                        SettingsDeviceHeader(
                            backgroundStyle = headerStyle,
                            model = headerInfo?.model.orEmpty(),
                            system = headerInfo?.system,
                            cache = bannerCache,
                            contentReady = headerInfo != null,
                            shape = RoundedCornerShape(
                                topStart = SETTINGS_CARD_CORNER,
                                topEnd = SETTINGS_CARD_CORNER,
                            ),
                        )
                    },
                    belowContent = {
                        HideLauncherIconRow(ctx)
                        LaunchAppsRows(ctx)
                    },
                )
            }
            CATEGORY_GROUPS.forEach { group ->
                item {
                    SettingsCard {
                        group.forEachIndexed { index, category ->
                            if (index > 0) SettingsItemDivider(startInset = SETTINGS_CATEGORY_TEXT_START)
                            SettingsCategoryRow(
                                icon = category.icon,
                                title = ctx.getString(category.title),
                                subtitle = category.subtitle?.let(ctx::getString),
                                onClick = { onOpenCategory(category.id) },
                                selected = selectedCategoryId?.let { it == category.id },
                            )
                        }
                    }
                }
            }
            item { Box(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun CategoryMasterToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsMasterToggle(
        checked = checked,
        title = androidx.compose.ui.res.stringResource(if (checked) CATEGORY_MASTER_TITLE_ON else MASTER_TITLE_OFF),
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun CategoryScreen(
    category: Category,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int,
    overrideValue: Boolean?,
    masterChecked: Boolean,
    onMasterChange: (Boolean) -> Unit,
    onItemChanged: () -> Unit,
    onBack: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenNavigationApps: () -> Unit,
    onOpenFontApps: () -> Unit,
    showBack: Boolean = true,
) {
    val listState = rememberLazyListState()
    val listScroll = rememberSettingsListScroll(listState)
    val isPad = LocalConfiguration.current.smallestScreenWidthDp >= 600
    // 按 GroupTitleItem 切分: 每段一张卡片, 段与段之间自然留出 group 间距。
    val groups = remember(category, version, overrideValue, isPad) {
        category.items.map { it.forDevice(isPad) }.filterNot {
            it is SwitchItem && it.key == "drawer_letter_scroll_enabled"
                    && prefs.getBoolean("drawer_install_time_sort_enabled", false)
        }.splitByDivider()
    }
    // 回弹位移: 共享给标题栏分割线, 让不可滚动页面上滑回弹时分割线也出现。
    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = ctx.getString(category.title),
                scrollBehavior = listScroll.appBar,
                navigationIcon = if (showBack) {
                    {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = COUIIcons.Back,
                                contentDescription = ctx.getString(R.string.ui_back),
                                tint = COUITheme.colorScheme.onSurface,
                                modifier = Modifier.size(SETTINGS_BACK_ICON),
                            )
                        }
                    }
                } else {
                    null
                },
                actions = { RestartMenu(ctx) },
            )
        },
    ) { padding ->
        SettingsLazyColumn(
            scroll = listScroll,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                ,
        ) {
            // 第一个 group 的 header: 说明滑块两端值的含义。
            if (category.id != "system_fonts") {
                item { SettingsSmallTitle(text = ctx.getString(SLIDER_GROUP_HINT)) }
            }
            if (category.items.masterItems.isNotEmpty()) {
                item {
                    CategoryMasterToggle(
                        checked = masterChecked,
                        onCheckedChange = onMasterChange,
                    )
                }
            }
            // 该分类专属说明: 作为设置项 group 的 header 显示(仅配置了 hint 的分类)。
            val hint = category.hint
            if (hint != null) {
                item { SettingsSmallTitle(text = ctx.getString(hint)) }
            }
            groups.forEach { group ->
                // 分组小标题: GroupTitleItem 的 title 资源 ID 非 0时在卡片上方显示。
                val desc = group.desc
                if (desc != null && desc != 0) {
                    item { SettingsSmallTitle(text = androidx.compose.ui.res.stringResource(desc)) }
                }
                item {
                    SettingsGroup(
                        items = group.items,
                        prefs = prefs,
                        ctx = ctx,
                        version = version,
                        overrideValue = overrideValue,
                        onItemChanged = onItemChanged,
                        onOpenNavigationApps = onOpenNavigationApps,
                        onOpenFontApps = onOpenFontApps,
                        footerContent = if (group.items.any { it is SwitchItem && it.key == "hidden_launcher_apps_enabled" }) {
                            { SettingsCategoryRow(null, ctx.getString(R.string.ui_hidden_apps_list), onOpenHiddenApps) }
                        } else null,
                    )
                }
            }

            item { Box(Modifier.height(24.dp)) }
        }
    }
}

private fun isLauncherIconHidden(ctx: Context): Boolean {
    return when (ctx.packageManager.getComponentEnabledSetting(
        ComponentName(ctx, "${ctx.packageName}.MainActivityLauncher")
    )) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> true
        else -> false
    }
}

private fun setLauncherIconHidden(ctx: Context, hidden: Boolean) {
    ctx.packageManager.setComponentEnabledSetting(
        ComponentName(ctx, "${ctx.packageName}.MainActivityLauncher"),
        if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}

@Composable
private fun HideLauncherIconRow(ctx: Context) {
    var hidden by remember { mutableStateOf(isLauncherIconHidden(ctx)) }
    SettingsSwitchPreference(
        checked = hidden,
        onCheckedChange = {
            hidden = it
            setLauncherIconHidden(ctx, it)
        },
        title = ctx.getString(R.string.ui_hide_module_icon),
        subtitle = ctx.getString(R.string.ui_hide_module_icon_hint),
    )
}

private fun deviceName(ctx: Context): String {
    val name = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
    return if (name.isNullOrBlank()) Build.MODEL else name
}

private fun romVersion(): String? {
    for (key in arrayOf(
        "ro.build.version.oplusrom.display",
        "ro.build.version.oplusrom",
        "ro.build.version.opporom",
        "ro.build.version.realmeui",
    )) {
        val major = runRoot("getprop $key")?.trim()?.let { Regex("\\d+\\.\\d+").find(it)?.value }
        if (major != null) return major
    }
    return null
}

// 系统属性只能通过 getprop 读, 故统一走 root shell(本文件的取值都在 IO 线程完成)。
private fun getProp(key: String): String? =
    runRoot("getprop $key")?.trim()?.takeIf { it.isNotEmpty() }

private fun isCnRegion(): Boolean {
    val region = getProp("persist.sys.oplus.region")
    if (region != null) return region.equals("CN", ignoreCase = true)
    val ota = getProp("persist.sys.oplus.ota_ver_display")
    return ota == null || ota.contains("CN01", ignoreCase = true)
}

private fun systemName(): String? = when {
    Build.BRAND.contains("oneplus", ignoreCase = true) -> if (isCnRegion()) "ColorOS" else "OxygenOS"
    Build.BRAND.contains("oppo", ignoreCase = true) -> "ColorOS"
    Build.BRAND.equals("realme", ignoreCase = true) -> "realmeUI"
    else -> null
}

private fun systemLabel(): String? {
    val name = systemName() ?: return null
    val version = romVersion() ?: return name
    val region = regionLabel(name) ?: return "$name $version"
    return "$name $region $version"
}

private fun regionLabel(name: String): String? {
    if (name == "OxygenOS") return null
    return if (isCnRegion()) "CN" else "EX"
}

@Composable
private fun LaunchAppsRows(ctx: Context) {
    // root shell 启动会阻塞到命令结束(冷启动管理器可达秒级), 放到 IO 线程避免卡住 UI。
    val scope = rememberCoroutineScope()
    SettingsItemDivider()
    SettingsActionPairRow(
        leftTitle = ctx.getString(R.string.ui_launch_kernelsu),
        onLeftClick = { scope.launch(Dispatchers.IO) { launchApp(ctx, KERNELSU_LAUNCH) } },
        rightTitle = ctx.getString(R.string.ui_launch_lsposed),
        onRightClick = { scope.launch(Dispatchers.IO) { launchApp(ctx, lsposedLaunchCmd()) } },
    )
}

// KernelSU 管理器有正常的应用入口, 直接拉起其主界面。
private const val KERNELSU_LAUNCH = "am start -n me.weishu.kernelsu/.ui.MainActivity"

internal fun lsposedLaunchCmd(): String {
    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "android.telephony.action.SECRET_CODE"
    } else {
        "android.provider.Telephony.SECRET_CODE"
    }
    return "am broadcast -a $action -d android_secret_code://5776733 android"
}

internal fun launchApp(ctx: Context, command: String) {
    if (runRoot(command) == null) {
        // Toast 需回主线程: 本函数在 IO 线程被调用。
        Handler(ctx.mainLooper).post {
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_root_denied), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

private const val HOT_RELOAD_TIMEOUT_MS = 30_000L
private val hotReloadBusy = AtomicBoolean(false)
private val activeHotReloadBatches: MutableSet<HotReloadBatch> =
    java.util.Collections.newSetFromMap(ConcurrentHashMap<HotReloadBatch, Boolean>())

private class HotReloadBatch(private val context: Context) {
    class Pending(val service: XposedService, val targetLabel: String) {
        val completed = AtomicBoolean(false)
    }

    private val lock = Any()
    private val handler = Handler(context.mainLooper)
    private val pendingByService = HashMap<XposedService, MutableList<Pending>>()
    private var remaining = 0
    private var succeeded = 0
    private var refused = 0
    private var frameworkFailed = 0
    private var unsupported = 0
    private var inProgress = 0
    private var processDied = 0
    private var submissionFailed = 0
    private var queryFailed = 0
    private var timedOut = 0
    private var submissionsClosed = false
    private var finished = false
    private val timeout = Runnable { finishTimedOut() }

    init {
        handler.postDelayed(timeout, HOT_RELOAD_TIMEOUT_MS)
    }

    fun token(service: XposedService, target: HookedTarget): Pending? = synchronized(lock) {
        if (finished || submissionsClosed) return@synchronized null
        val label = try {
            "${target.processName} uid=${target.uid} pid=${target.pid}"
        } catch (_: Throwable) {
            "opaque target"
        }
        Pending(service, label).also {
            pendingByService.getOrPut(service) { mutableListOf() }.add(it)
            remaining++
        }
    }

    fun recordQueryFailure(service: XposedService, error: Throwable) {
        synchronized(lock) { queryFailed++ }
        Log.e("ColorOSMod", "hot reload target query failed for service $service", error)
    }

    fun recordUnsupportedService(service: XposedService, apiVersion: Int) {
        synchronized(lock) { unsupported++ }
        Log.e("ColorOSMod", "hot reload outcome: unsupported service api=$apiVersion service=$service")
    }

    fun closeSubmissions() {
        val shouldRelease = synchronized(lock) {
            if (finished) return@synchronized false
            submissionsClosed = true
            if (remaining == 0) {
                finished = true
                true
            } else false
        }
        if (shouldRelease) release()
    }

    fun finishWithoutTargets() {
        val message = synchronized(lock) {
            if (finished || remaining != 0) return
            finished = true
            submissionsClosed = true
            if (queryFailed > 0 || unsupported > 0) {
                summaryLocked()
            } else {
                context.getString(R.string.ui_reload_no_targets)
            }
        }
        release(message)
    }

    fun failOperation(error: Throwable) {
        synchronized(lock) {
            if (finished) return
            finished = true
            submissionsClosed = true
            pendingByService.values.flatten().forEach { pending ->
                if (pending.completed.compareAndSet(false, true)) submissionFailed++
            }
        }
        Log.e("ColorOSMod", "hot reload operation failed", error)
        release(context.getString(R.string.ui_reload_failed))
    }

    fun complete(pending: Pending, result: HotReloadResult) {
        val status = result.status()
        val message = result.message()
        finishPending(
            pending,
            account = {
                when (status) {
                    HotReloadResult.Status.SUCCEEDED -> succeeded++
                    HotReloadResult.Status.FAILED -> {
                        if (message == null) refused++ else frameworkFailed++
                    }
                    HotReloadResult.Status.UNSUPPORTED -> unsupported++
                    HotReloadResult.Status.IN_PROGRESS -> inProgress++
                    HotReloadResult.Status.PROCESS_DIED -> processDied++
                }
            },
            logAccepted = {
                Log.e(
                    "ColorOSMod",
                    "hot reload outcome target=${pending.targetLabel} status=$status message=$message",
                )
            },
        )
    }

    fun submissionFailed(pending: Pending, error: Throwable) {
        finishPending(
            pending,
            account = { submissionFailed++ },
            logAccepted = {
                Log.e("ColorOSMod", "hot reload submit failed target=${pending.targetLabel}", error)
            },
        )
    }

    private fun finishPending(
        pending: Pending,
        account: () -> Unit,
        logAccepted: () -> Unit,
    ) {
        var accepted = false
        val shouldRelease = synchronized(lock) {
            if (!pending.completed.compareAndSet(false, true)) return@synchronized false
            accepted = true
            account()
            remaining--
            if (submissionsClosed && remaining == 0 && !finished) {
                finished = true
                true
            } else false
        }
        if (!accepted) return
        logAccepted()
        if (shouldRelease) release()
    }

    fun serviceDied(service: XposedService) {
        val servicePending = synchronized(lock) { pendingByService[service]?.toList() } ?: return
        servicePending.forEach { pending ->
            finishPending(
                pending,
                account = { processDied++ },
                logAccepted = {
                    Log.e("ColorOSMod", "hot reload outcome: service died target=${pending.targetLabel}")
                },
            )
        }
    }

    private fun finishTimedOut() {
        synchronized(lock) {
            if (finished) return
            finished = true
            submissionsClosed = true
            pendingByService.values.flatten().forEach { pending ->
                if (pending.completed.compareAndSet(false, true)) timedOut++
            }
        }
        release()
    }

    private fun release(message: String? = null) {
        handler.removeCallbacks(timeout)
        activeHotReloadBatches.remove(this)
        hotReloadBusy.set(false)
        val summary = synchronized(lock) { message ?: summaryLocked() }
        showHotReloadToast(context, summary)
    }

    private fun summaryLocked(): String {
        val parts = ArrayList<String>()
        if (succeeded > 0) parts += context.getString(R.string.ui_reload_succeeded, succeeded)
        if (refused > 0) parts += context.getString(R.string.ui_reload_refused, refused)
        if (frameworkFailed > 0) parts += context.getString(R.string.ui_reload_framework_failed, frameworkFailed)
        if (unsupported > 0) parts += context.getString(R.string.ui_reload_unsupported, unsupported)
        if (inProgress > 0) parts += context.getString(R.string.ui_reload_in_progress, inProgress)
        if (processDied > 0) parts += context.getString(R.string.ui_reload_process_died, processDied)
        if (submissionFailed > 0) parts += context.getString(R.string.ui_reload_submission_failed, submissionFailed)
        if (queryFailed > 0) parts += context.getString(R.string.ui_reload_query_failed, queryFailed)
        if (timedOut > 0) parts += context.getString(R.string.ui_reload_timed_out, timedOut)
        return context.getString(R.string.ui_reload_summary, if (parts.isEmpty()) context.getString(R.string.ui_reload_empty) else parts.joinToString(context.getString(R.string.ui_reload_separator)))
    }
}

private fun requestHotReloadAll(ctx: Context) {
    val appContext = ctx.applicationContext
    if (!hotReloadBusy.compareAndSet(false, true)) {
        Toast.makeText(appContext, appContext.getString(R.string.ui_reload_processing), Toast.LENGTH_SHORT).show()
        return
    }
    val services = MainActivity.xposedServices.toList()
    if (services.isEmpty()) {
        hotReloadBusy.set(false)
        Toast.makeText(appContext, appContext.getString(R.string.ui_xposed_unavailable), Toast.LENGTH_SHORT).show()
        return
    }
    val batch = HotReloadBatch(appContext)
    activeHotReloadBatches.add(batch)
    Thread({
        try {
            val work = ArrayList<Pair<XposedService, HookedTarget>>()
            services.forEach { service ->
                try {
                    val apiVersion = service.apiVersion
                    if (apiVersion >= XposedService.API_102) {
                        service.runningTargets.forEach { target -> work.add(service to target) }
                    } else {
                        batch.recordUnsupportedService(service, apiVersion)
                    }
                } catch (t: Throwable) {
                    batch.recordQueryFailure(service, t)
                }
            }
            if (work.isEmpty()) {
                batch.finishWithoutTargets()
                return@Thread
            }
            showHotReloadToast(appContext, appContext.getString(R.string.ui_reload_scope_count, work.size))
            work.forEach { (service, target) ->
                val token = batch.token(service, target) ?: return@forEach
                try {
                    // A service can die between the initial snapshot and submission.
                    if (!MainActivity.xposedServices.contains(service)) {
                        batch.serviceDied(service)
                    } else {
                        service.hotReloadModule(target, null) { _: HookedTarget, result: HotReloadResult ->
                            batch.complete(token, result)
                        }
                    }
                } catch (t: Throwable) {
                    batch.submissionFailed(token, t)
                }
            }
            batch.closeSubmissions()
        } catch (t: Throwable) {
            batch.failOperation(t)
        }
    }, "ColorOSMod-HotReload").start()
}

private fun showHotReloadToast(ctx: Context, text: String) {
    Handler(ctx.mainLooper).post { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
}

@Composable
internal fun RestartMenu(ctx: Context) {
    SettingsActionMenu(
        icon = {
            Icon(
                painter = rememberVectorPainter(COUIIcons.Refresh),
                contentDescription = ctx.getString(R.string.ui_restart),
            )
        },
        items = listOf(
            ActionMenuItem(ctx.getString(R.string.ui_hot_reload), { requestHotReloadAll(ctx) }),
            ActionMenuItem(ctx.getString(R.string.ui_restart_scope), { restartScope(ctx) }),
            ActionMenuItem(ctx.getString(R.string.ui_restart_zygote), { softRebootSystem(ctx) }),
        ),
    )
}

private fun desktopFolderNames(ctx: Context): List<String> {
    val db = File(ctx.cacheDir, "launcher_folders.db")
    val src = "/data/user_de/0/com.android.launcher/databases/launcher.db"
    val src4x4 = "/data/user_de/0/com.android.launcher/databases/launcher_4x4.db"
    val cmd = "rm -f \"${db.path}\" \"${db.path}-wal\" \"${db.path}-shm\" 2>/dev/null; " +
            "cp -f $src \"${db.path}\" 2>/dev/null || cp -f $src4x4 \"${db.path}\" 2>/dev/null; " +
            "cp -f $src-wal \"${db.path}-wal\" 2>/dev/null; " +
            "cp -f $src-shm \"${db.path}-shm\" 2>/dev/null; " +
            "chmod 666 \"${db.path}\" \"${db.path}-wal\" \"${db.path}-shm\" 2>/dev/null"
    if (runRoot(cmd) == null) return emptyList()
    return runCatching {
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            val names = LinkedHashSet<String>()
            for (table in arrayOf(
                "singledesktopitems",
                "singledesktopitems_draw",
                "singledesktopitems_simple",
            )) {
                val rows = runCatching {
                    d.rawQuery(
                        "SELECT title FROM \"$table\" WHERE itemType=3 OR itemType=105 ORDER BY _id",
                        null,
                    )
                }.getOrNull() ?: continue
                rows.use { c ->
                    while (c.moveToNext()) {
                        val title = c.getString(0)
                        if (!title.isNullOrEmpty()) names.add(title)
                    }
                }
            }
            names.toList()
        }
    }.getOrDefault(emptyList())
}

internal fun Context.settingsPrefs(): SharedPreferences {
    val de = createDeviceProtectedStorageContext()
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    if (de.all.isEmpty() && migrateLegacyPrefs(this, de)) {
        SettingsProvider.notifySettingsChanged(this)
    }
    SettingsProvider.migrateNotificationIconPrefs(this, de)
    if (!de.contains("statusbar_lyric_mode")
            && (de.contains("statusbar_lyric_enabled") || de.contains("fluid_cloud_lyric_enabled"))) {
        val mode = when {
            !de.getBoolean("statusbar_lyric_enabled", false) -> 0
            de.getBoolean("fluid_cloud_lyric_enabled", false) -> 2
            de.getBoolean("statusbar_lyric_enabled", false) -> 1
            else -> 0
        }
        if (de.edit().putInt("statusbar_lyric_mode", mode)
                .remove("statusbar_lyric_enabled").remove("fluid_cloud_lyric_enabled").commit()) {
            SettingsProvider.notifySettingsChanged(this)
        }
    }
    // 旧版下拉菜单的“显示在流体云”迁移为独立实验性开关；一键启用不管理该开关。
    if (de.getInt("statusbar_lyric_mode", -1) == 2) {
        val editor = de.edit().putInt("statusbar_lyric_mode", 1)
        if (!de.contains("fluid_cloud_lyric_enabled")) editor.putBoolean("fluid_cloud_lyric_enabled", true)
        if (editor.commit()) SettingsProvider.notifySettingsChanged(this)
    }
    if (!de.contains("signal_network_type_mode") && de.contains("separate_network_type")) {
        if (de.edit().putInt("signal_network_type_mode",
                if (de.getBoolean("separate_network_type", false)) 1 else 0).commit()) {
            SettingsProvider.notifySettingsChanged(this)
        }
    }
    if (!de.contains("qs_corner_radius_mode") && de.contains("qs_normal_corner_radius_enabled")) {
        if (de.edit().putInt("qs_corner_radius_mode",
                if (de.getBoolean("qs_normal_corner_radius_enabled", false)) 2 else 0).commit()) {
            SettingsProvider.notifySettingsChanged(this)
        }
    }
    if (!de.contains("qs_corner_radius_enabled") && de.contains("qs_corner_radius_mode")) {
        val mode = de.getInt("qs_corner_radius_mode", 0)
        val editor = de.edit().putBoolean("qs_corner_radius_enabled", mode == 1 || mode == 2)
        if (!de.contains("qs_corner_radius_dp")) editor.putInt("qs_corner_radius_dp", if (mode == 1) 60 else 16)
        if (editor.commit()) SettingsProvider.notifySettingsChanged(this)
    }
    if (!de.contains("qs_corner_radius_percent") && de.contains("qs_corner_radius_dp")) {
        val percent = (de.getInt("qs_corner_radius_dp", 16).coerceIn(0, 32) * 50f / 32f).roundToInt()
        if (de.edit().putInt("qs_corner_radius_percent", percent).commit()) SettingsProvider.notifySettingsChanged(this)
    }
    if (HiddenLauncherApps.migrate(this, de)) SettingsProvider.notifySettingsChanged(this)
    return de
}

private fun migrateLegacyPrefs(ctx: Context, de: SharedPreferences): Boolean {
    val legacy = runCatching { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).all }
        .getOrDefault(emptyMap())
    if (legacy.isEmpty()) return false
    val e = de.edit()
    for ((k, v) in legacy) {
        when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
            is Set<*> -> @Suppress("UNCHECKED_CAST") e.putStringSet(k, v as Set<String>)
        }
    }
    return e.commit()
}

internal fun setBool(ctx: Context, key: String, value: Boolean) {
    val prefs = ctx.settingsPrefs()
    if (key == "qs_merged_radiant" && value && !prefs.getBoolean("qs_merged_hollow", false)) return
    val editor = prefs.edit().putBoolean(key, value)
    if (key == "qs_merged_hollow" && !value) editor.putBoolean("qs_merged_radiant", false)
    editor.commit()
    if (key == "system_font_disable_replacement") {
        val context = ctx.applicationContext
        SystemFontStore.enqueue { SystemFontStore.syncReplacementSettings(context) }
    }
    if (key == "system_font_mount_enabled") {
        val context = ctx.applicationContext
        SystemFontStore.enqueue {
            if (value) {
                val service = MainActivity.xposedServices.firstOrNull() ?: error("Xposed service unavailable")
                SystemFontStore.sync(context, service)
            } else SystemFontMounts.scheduleRestore(context)
        }
    }
    if (key == HomeControlsTileSettings.KEY) HomeControlsTileSettings.sync(ctx)
    SettingsProvider.notifySettingsChanged(ctx)
}

internal fun setInt(ctx: Context, key: String, value: Int) {
    ctx.settingsPrefs().edit().putInt(key, value).commit()
    SettingsProvider.notifySettingsChanged(ctx)
}

// 一键启用“彻底隐藏应用”时默认隐藏的内置组件(电话本、Gboard), 仅在已安装且存在桌面入口时添加。
private fun addDefaultHiddenApps(context: Context) {
    val installed = HiddenLauncherApps.queryActivities(context)
        .mapNotNull { it.activityInfo?.let { ai -> ComponentName(ai.packageName, ai.name).flattenToString() } }
        .toSet()
    val defaults = listOf(
        "com.android.contacts/com.android.contacts.PeopleActivityAlias",
        "com.google.android.inputmethod.latin/com.google.android.libraries.inputmethod.launcher.LauncherActivity",
    )
    val prefs = context.settingsPrefs()
    val current = prefs.getStringSet(HiddenLauncherApps.COMPONENTS, emptySet()).orEmpty().toMutableSet()
    var changed = false
    for (component in defaults) {
        if (component in installed) changed = current.add(component) || changed
    }
    if (changed) {
        prefs.edit().putStringSet(HiddenLauncherApps.COMPONENTS, current).commit()
        SettingsProvider.notifySettingsChanged(context)
    }
}

internal fun applySystemSetting(key: String, enabled: Boolean, value: Int) {
    when (key) {
        "disable_rotation_suggestions" -> {
            runRoot("settings --user current put secure show_rotation_suggestions ${if (enabled) 0 else 1}")
        }
        "long_press_timeout_enabled" -> {
            val timeout = if (enabled) value.coerceIn(100, 600) else 500
            runRoot("settings put secure long_press_timeout $timeout")
        }
        "animation_duration_scale_enabled" -> {
            val scale = if (enabled) value.coerceIn(0, 20) * 0.05f else 1f
            val text = String.format(Locale.US, "%.2f", scale)
            runRoot(
                "settings put global window_animation_scale $text; " +
                    "settings put global transition_animation_scale $text; " +
                    "settings put global animator_duration_scale $text"
            )
        }
    }
}

internal fun readSystemSetting(key: String): Pair<Boolean, Int>? {
    return when (key) {
        "disable_rotation_suggestions" -> {
            val raw = runRoot("settings --user current get secure show_rotation_suggestions")?.trim()
                ?: return null
            // DisplayRotation uses 1 when this secure setting is absent.
            val value = if (raw == "null") 1 else raw.toIntOrNull() ?: return null
            Pair(value == 0, 0)
        }
        "long_press_timeout_enabled" -> {
            val value = runRoot("settings get secure long_press_timeout")?.trim()?.toIntOrNull()
                ?: return null
            Pair(value != 500, value.coerceIn(100, 600))
        }
        "animation_duration_scale_enabled" -> {
            val raw = runRoot("settings get global animator_duration_scale")?.trim()
                ?: return null
            val scale = raw.toFloatOrNull() ?: return null
            Pair(scale != 1f, (scale / 0.05f).toInt().coerceIn(0, 20))
        }
        else -> null
    }
}

// 只删除指定的标准一级目录，并使用 rmdir 保证目录非空时绝不删除任何内容。
internal fun deleteEmptyMediaFolder(folderName: String) {
    if (folderName !in setOf(
            "Music", "Podcasts", "Ringtones", "Alarms", "Notifications",
            "Movies", "Audiobooks", "Recordings",
        )
    ) return

    runRoot("rmdir /data/media/0/$folderName 2>/dev/null; " +
            "rmdir /storage/emulated/0/$folderName 2>/dev/null")
}

internal fun createMediaFolder(folderName: String) {
    if (folderName !in setOf(
            "Music", "Podcasts", "Ringtones", "Alarms", "Notifications",
            "Movies", "Audiobooks", "Recordings",
        )
    ) return
    // MediaProvider 默认目录由 media_rw(1023) 创建；root 仅负责执行命令，目录所有者仍设为 media_rw。
    runRoot("mkdir -p /data/media/0/$folderName 2>/dev/null; " +
            "chown 1023:1023 /data/media/0/$folderName 2>/dev/null; " +
            "chmod 0771 /data/media/0/$folderName 2>/dev/null")
}

private fun restartScope(ctx: Context) {
    runCatching {
        Runtime.getRuntime().exec("su").also { p ->
            val os = DataOutputStream(p.outputStream)
            os.writeBytes("for pid in \$(pidof com.android.systemui) \$(pidof com.android.launcher); do kill \$pid 2>/dev/null; done\n")
            os.writeBytes("exit\n")
            os.flush()
            p.waitFor()
        }
    }.onFailure {
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_root_denied), android.widget.Toast.LENGTH_SHORT).show()
    }
}

private val zygoteRestartPending = AtomicBoolean(false)

private fun softRebootSystem(ctx: Context) {
    if (!zygoteRestartPending.compareAndSet(false, true)) return
    val app = ctx.applicationContext
    val handler = Handler(app.mainLooper)
    val preparing = AtomicBoolean(true)
    val waitingHint = Runnable {
        if (preparing.get()) Toast.makeText(app, R.string.ui_font_restart_preparing, Toast.LENGTH_SHORT).show()
    }
    handler.postDelayed(waitingHint, 500)
    SystemFontStore.whenReady(app, action = {
        preparing.set(false)
        handler.removeCallbacks(waitingHint)
        try {
            val process = ProcessBuilder("su", "-c", "setprop ctl.restart zygote").start()
            check(process.waitFor() == 0) { "Zygote restart command failed" }
        } catch (error: Exception) {
            AppLog.log("Zygote restart failed: $error")
            handler.post { Toast.makeText(app, R.string.ui_root_denied, Toast.LENGTH_SHORT).show() }
        } finally {
            zygoteRestartPending.set(false)
        }
    }, onFailure = { error ->
        preparing.set(false)
        handler.removeCallbacks(waitingHint)
        zygoteRestartPending.set(false)
        AppLog.log("Zygote restart cancelled: font settings are not ready: $error")
        handler.post { Toast.makeText(app, R.string.ui_font_restart_failed, Toast.LENGTH_LONG).show() }
    })
}

internal fun colorOSAccentColor(context: Context, fallback: Long): Color {
    return try {
        // 首选: SystemUI 计算并缓存的 ColorOS 主题色。
        val accent = Settings.Secure.getString(context.contentResolver, "sysui_type_accent_color")
        val hex = when {
            !accent.isNullOrBlank() -> accent
            else -> colorOSMonetAccent(context)
        }
        if (hex.isNullOrBlank()) Color(fallback) else Color(android.graphics.Color.parseColor(hex))
    } catch (t: Throwable) {
        Color(fallback)
    }
}

private fun colorOSMonetAccent(context: Context): String? {
    return try {
        val raw = Settings.Secure.getString(context.contentResolver, "theme_customization_overlay_packages")
            ?: return null
        val json = JSONObject(raw)
        // 优先 accent_color, 其次 system_palette; 二者均是无 "#" 前缀的 hex。
        val value = json.optString("android.theme.customization.accent_color")
            .ifEmpty { json.optString("android.theme.customization.system_palette") }
        if (value.isBlank()) null else "#" + value.trimStart('#')
    } catch (t: Throwable) {
        null
    }
}

internal fun runRoot(command: String): String? {
    return runCatching {
        val p = Runtime.getRuntime().exec("su")
        DataOutputStream(p.outputStream).use { os ->
            os.writeBytes(command)
            os.writeBytes("\nexit\n")
            os.flush()
        }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    }.getOrNull()
}

private fun hasRootAccess(): Boolean {
    return runRoot("id -u")?.trim() == "0"
}

private fun isModuleEnabledInLsposed(ctx: Context, pkg: String): Boolean {
    val db = File(ctx.cacheDir, "lspd_modules_config.db")
    // 先删旧拷贝再重新拷出(连同 wal/shm), 避免上次残留的旧数据库造成误判。
    val cmd = "rm -f \"${db.path}\" \"${db.path}-wal\" \"${db.path}-shm\" 2>/dev/null; " +
            "cp -f /data/adb/lspd/config/modules_config.db \"${db.path}\" 2>/dev/null; " +
            "cp -f /data/adb/lspd/config/modules_config.db-wal \"${db.path}-wal\" 2>/dev/null; " +
            "cp -f /data/adb/lspd/config/modules_config.db-shm \"${db.path}-shm\" 2>/dev/null; " +
            "chmod 666 \"${db.path}\" \"${db.path}-wal\" \"${db.path}-shm\" 2>/dev/null"
    if (runRoot(cmd) == null) return false
    return runCatching {
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery(
                "SELECT enabled FROM modules_state WHERE module_pkg_name=? AND user_id=0",
                arrayOf(pkg),
            ).use { c -> c.moveToFirst() && c.getInt(0) == 1 }
        }
    }.getOrDefault(false)
}

// 启动时后台自检环境: 无 root 则兼容运行不弹提示; 有 root 但模块未在 LSPosed 启用则 toast 提示。
private fun checkEnvironment(activity: MainActivity) {
    Thread {
        val hasRoot = hasRootAccess()
        val enabled = if (hasRoot) isModuleEnabledInLsposed(activity, activity.packageName) else null
        activity.runOnUiThread {
            if (enabled == false) {
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.ui_enable_lsposed),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }.start()
}
