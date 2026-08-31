package com.rikumi.colorosmod

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.DataOutputStream
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                MiuixTheme(
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
                    textStyles = couixTextStyles(),
                ) {
                    CouixStatusBar()
                    CouixOverscrollHost { SettingsScreen() }
                }
            }
        }
    }
}

// 设置页条目: 分组小标题或设置项。
internal sealed interface SettingsItem

// 分组小标题: 表示其后(到下一个 GroupTitleItem 或列表末尾)的 SwitchItem 开启一个新分组,
// 可放在其它 item 之间或首个 item 前面。title 为空字符串表示仅分隔不显示标题。
internal data class GroupTitleItem(val title: String) : SettingsItem

// 单个设置项: key 用于持久化与 Xposed 读取, label 取自原 strings.xml 中的名称。sliderKey 非空时
// 开关打开后下方显示滑条(范围 0..sliderMax 整数步进, 默认 sliderDefault, sliderUnit 为值后缀)。
internal data class SwitchItem(
    val key: String,
    val label: String,
    val subtitle: String? = null,
    val sliderKey: String? = null,
    val sliderMax: Int = 0,
    val sliderDefault: Int = 0,
    val sliderUnit: String = "dp",
    val sliderMin: Int = 0,
) : SettingsItem

// 下拉选项设置项。options 为静态选项; dynamicOptions 非空时进入页面后于 IO 线程重拉(如"新应用添加到"
// 需读桌面文件夹名), 失败则沿用 options。valueKey 非空时选中项除存下标外还把选项文本写到 valueKey ——
// 选项会随桌面文件夹增删变化, 只存下标会在删文件夹后错位, 存文本则能自然回落到第 0 项。
internal data class SelectItem(
    val key: String,
    val label: String,
    val options: List<String>,
    val defaultValue: Int = 0,
    val valueKey: String? = null,
    val dynamicOptions: ((Context) -> List<String>)? = null,
)

private val DESKTOP: List<SettingsItem> = listOf(
    GroupTitleItem("桌面布局"),
    SwitchItem("icon_gap_enabled", "增加图标与名称间距", sliderKey = "icon_gap_dp", sliderMax = 8, sliderDefault = 4),
    SwitchItem("indicator_enabled", "减小页面与 Dock 间距", sliderKey = "indicator_dp", sliderMax = 32, sliderDefault = 16, sliderUnit = "dp"),
    SwitchItem("edit_mode_bg_transparent_enabled", "取消编辑模式背景遮罩"),
    GroupTitleItem("长按菜单"),
    SwitchItem("shrink_popup_menu", "缩小图标长按菜单", sliderKey = "popup_scale_percent", sliderMax = 20, sliderDefault = 10, sliderUnit = "%"),
    SwitchItem("popup_dynamic_blur_enabled", "长按菜单背景动态模糊"),
    SwitchItem("desktop_popup_bg_brightness_enabled", "自定义长按菜单背景亮度", sliderKey = "desktop_popup_bg_brightness", sliderMax = 10, sliderDefault = 0, sliderUnit = ""),
    GroupTitleItem("文件夹"),
    SwitchItem("folder_bg_transparent_enabled", "文件夹展开背景透明"),
    SwitchItem("folder_anim_duration_enabled", "调整文件夹动画持续时间", sliderKey = "folder_anim_duration_ms", sliderMax = 500, sliderDefault = 300, sliderUnit = "ms", sliderMin = 100),
)

private val QS: List<SettingsItem> = listOf(
    GroupTitleItem("通控中心通用设置"),
    SwitchItem("qs_scrim_translucent_enabled", "自定义背景亮度", sliderKey = "qs_scrim_brightness", sliderMax = 20, sliderDefault = 0, sliderUnit = "%"),
    SwitchItem("qs_blur_radius_enabled", "自定义背景模糊半径", sliderKey = "qs_blur_radius", sliderMax = 80, sliderDefault = 40, sliderUnit = ""),
    SwitchItem("qs_blur_scale_enabled", "自定义背景缩小幅度", sliderKey = "qs_blur_scale", sliderMax = 100, sliderDefault = 50, sliderUnit = "%"),
    SwitchItem("qs_carrier_enabled", "去除运营商显示"),
    SwitchItem("qs_topmargin_enabled", "隐藏顶部状态图标簇"),
    SwitchItem("qs_panel_switch_no_cut_enabled", "分离版左右平移切换"),
    GroupTitleItem("控制中心设置"),
    SwitchItem("qs_tile_name_ellipsis_enabled", "Wi-Fi / 蓝牙名称单行省略"),
    SwitchItem("qs_normal_corner_radius_enabled", "OxygenOS 恢复正常圆角"),
    SwitchItem("qs_clock_no_expand_anim_enabled", "合并版时间日期固定单行"),
)
private val NOTIF: List<SettingsItem> = listOf(
    GroupTitleItem("通知中心设置"),
    SwitchItem("notification_swipe_to_dismiss_enabled", "通知左滑直接清除"),
    SwitchItem("notification_pull_expand_enabled", "通知下滑展开"),
    SwitchItem("notification_subtitle_enabled", "缩小通知静默区域副标题", sliderKey = "notification_subtitle_sp", sliderMax = 16, sliderDefault = 8, sliderUnit = "sp"),
    SwitchItem("notification_padding_enabled", "增加通知上下内边距", sliderKey = "notification_padding_dp", sliderMax = 8, sliderDefault = 4),
    GroupTitleItem("状态栏设置"),
    SwitchItem("statusbar_lyric_enabled", "状态栏显示歌词", "需播放器支持 MediaSession metadata.lyricInfo ColorOS 歌词能力，暂不支持魅族歌词能力"),
    SwitchItem("fluid_cloud_keep_percent_enabled", "流体云出现时不隐藏电量百分比"),
)
private val HIDDEN: List<SettingsItem> = listOf(
    GroupTitleItem("隐藏应用逻辑简化"),
    SwitchItem("recents_show_hidden_enabled", "多任务显示已隐藏应用"),
    SwitchItem("hide_apps_noverify_enabled", "打开隐藏应用文件夹免验证"),
    SwitchItem("pinch_out_open_hide_apps_enabled", "桌面双指张开打开隐藏应用"),
    SwitchItem("hide_apps_title_folder_enabled", "应用隐藏标题显示文件夹名"),
    GroupTitleItem("特殊应用隐藏"),
    SwitchItem("hide_contacts_enabled", "彻底隐藏电话本图标"),
    SwitchItem("hide_gboard_enabled", "彻底隐藏 Gboard 图标"),
    SwitchItem("hide_ghostlock_enabled", "彻底隐藏 GhostLock 图标", subtitle = "显然已经有 root 的时候不需要再 root"),
)

// 小窗相关设置: 改动需重启 system_server(框架) 才生效(本模块该作用域为 android/system_server)。
private val FLOATWINDOW: List<SettingsItem> = listOf(
    GroupTitleItem("小窗行为"),
    SwitchItem("recents_hide_freeform_enabled", "多任务隐藏小窗应用"),
    SwitchItem("float_window_edge_hang_enabled", "悬浮小窗贴边挂机"),
    SwitchItem("float_window_edge_hang_mute_enabled", "小窗贴边挂机静音"),
    GroupTitleItem("小窗视觉"),
    SwitchItem("float_window_edge_hang_white_bar_enabled", "小窗贴边显示为白色竖条"),
    SwitchItem("float_window_landscape_keep_ratio_enabled", "横屏应用小窗保持比例", "横屏应用小窗的宽高比等于屏幕高宽比"),
    SwitchItem("float_window_edge_size_optimize_enabled", "优化小窗贴边位置及最大尺寸"),
)

private val NAV: List<SettingsItem> = listOf(
    GroupTitleItem("手势行为"),
    SwitchItem("gesture_bar_height_enabled", "增大底部手势区高度", "缓解屏幕底部圆角区域吃掉应用内容", "gesture_bar_height_dp", 24, 12),
    SwitchItem("mback_enabled", "启用 mBack", "点击手势条返回，长按回桌面"),
    SwitchItem("gesture_touch_through_enabled", "避免手势区域点击穿透"),
    GroupTitleItem("手势视觉"),
    SwitchItem("gesture_bar_width_enabled", "调整手势滑动条宽度", sliderKey = "gesture_bar_width_dp", sliderMax = 120, sliderDefault = 100, sliderUnit = "dp", sliderMin = 80),
    SwitchItem("gesture_bar_long_press_disable_enabled", "禁止手势条动画效果", "理论可解决 OxygenOS 关不掉助手动画的问题"),
)

private val LOCKSCREEN: List<SettingsItem> = listOf(
    GroupTitleItem("锁屏行为"),
    SwitchItem("unlocked_shutdown_noverify_enabled", "解锁时关机无需校验密码", "仅开启关机校验密码功能时生效"),
    SwitchItem("keyguard_slide_input_enabled", "密码支持滑动输入"),
    SwitchItem("keyguard_bouncer_swipe_back_enabled", "密码界面支持侧滑/下滑返回", "解决误触上滑还要再上滑的奇怪交互"),
    GroupTitleItem("锁屏视觉"),
    SwitchItem("keyguard_no_light_effect_enabled", "取消密码界面控件光效", "模拟恢复 ColorOS 15 效果"),
    SwitchItem("keyguard_bouncer_brightness_enabled", "自定义密码界面背景亮度", sliderKey = "keyguard_bouncer_brightness", sliderMax = 5, sliderDefault = 0, sliderUnit = "%"),
    SwitchItem("keyguard_notification_offset_enabled", "锁屏通知区域下移", sliderKey = "keyguard_notification_offset_dp", sliderMax = 40, sliderDefault = 20),
)

// 首页的一个分类入口: id 用于页面栈定位, title 为首页/子页面标题, icon 取 miuix 扩展图标。
// subtitle 配置后显示在首页入口行右侧(不单独占第二行); hint 作为该分类子页面 group 的 header 说明。
private data class Category(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val items: List<SettingsItem>,
    val subtitle: String? = null,
    val hint: String? = null,
)

// 首页分组: 每组一张卡片。锁屏紧随通知中心, 其余三个另开一组。
private val CATEGORY_GROUPS: List<List<Category>> = listOf(
    listOf(
        Category("desktop", "桌面", MiuixIcons.GridView, DESKTOP),
        Category("quick_settings", "控制中心", MiuixIcons.Tune, QS),
        Category("notification", "通知中心与状态栏", MiuixIcons.Community, NOTIF),
        Category("lockscreen", "锁屏", MiuixIcons.Lock, LOCKSCREEN),
    ),
    listOf(
        Category("hidden_apps", "隐藏应用", MiuixIcons.Hide, HIDDEN),
        Category("float_window", "应用小窗", MiuixIcons.Copy, FLOATWINDOW, hint = "更改小窗设置需重启 Zygote 生效"),
        Category("navigation", "导航与手势", MiuixIcons.Backup, NAV),
    ),
    listOf(
        Category(DISABLED_APPS_ID, "停用应用", MiuixIcons.Blocklist, emptyList()),
    ),
)
private val CATEGORIES = CATEGORY_GROUPS.flatten()

// "停用应用"分类 id: 该分类没有开关项, 子页面在路由里特判渲染为只读列表。
private const val DISABLED_APPS_ID = "disabled_apps"

// 主开关切换后、落盘前的临时视觉覆盖。
// scope = null 表示首页的全局主开关(覆盖所有分类), 否则只覆盖该 categoryId 的分类。
private data class MasterOverride(val scope: String?, val value: Boolean)

/** 该覆盖是否作用于指定 scope(null = 首页全局): 全局覆盖对所有分类生效。 */
private fun MasterOverride?.valueFor(scope: String?): Boolean? =
    this?.takeIf { it.scope == null || it.scope == scope }?.value

// 设置分组: desc 为分组小标题(空字符串仅分隔不显示标题), items 为该组全部设置项。
private data class SwitchGroup(val desc: String?, val items: List<SwitchItem>)

// 过滤出实际设置项(GroupTitleItem 只是分组标题, 无 key 不参与开关)。
private val List<SettingsItem>.switches: List<SwitchItem> get() = filterIsInstance<SwitchItem>()

// 按 GroupTitleItem 把条目切成连续的若干段, 每段渲染为一张独立卡片。
// GroupTitleItem 可放在其它 item 之间或首个 item 前面; 连续多个时以后一个为准, 不会产生空段。
private fun List<SettingsItem>.splitByDivider(): List<SwitchGroup> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<SwitchGroup>()
    var desc: String? = null
    var current = mutableListOf<SwitchItem>()
    forEach { item ->
        when (item) {
            is GroupTitleItem -> {
                if (current.isNotEmpty()) result.add(SwitchGroup(desc, current))
                desc = item.title
                current = mutableListOf()
            }
            is SwitchItem -> current.add(item)
        }
    }
    if (current.isNotEmpty()) result.add(SwitchGroup(desc, current))
    return result
}

// 主开关标题: 未启用时是引导点击的"一键启用"; 启用后首页为"启用模块", 子页面为"启用功能"。
private const val MASTER_TITLE_OFF = "一键启用"
private const val HOME_MASTER_TITLE_ON = "启用模块"
private const val CATEGORY_MASTER_TITLE_ON = "启用功能"

// 首页主开关副标题(针对全部功能)。
private const val HOME_MASTER_HINT = "信任开发者启用全部，请注意隐藏应用的设置"

// 子页面设置组(第二个 group)的 header: 说明滑块两端值的含义。
private const val SLIDER_GROUP_HINT = "滑块最左/最右为系统值，中间通常为推荐值"

// 主开关"启用模块"切换动画的时长(ms): 先更新 UI 播放动画, 动画结束后才落盘设置。
private const val MASTER_TOGGLE_ANIM_MS = 350L

// 首页/子页面切换动画: ease 曲线(CSS ease: cubic-bezier(0.25, 0.1, 0.25, 1)), 300ms。
private const val PAGE_TRANSITION_MS = 300
private val PAGE_TRANSITION_EASING: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

// 模块设置根界面: 首页为全局一键启用 + 分类入口, 点击进入对应子页面。首页主开关作用于全部功能,
// 子页面主开关只作用于该分类; 两者的覆盖状态与 prefs 版本号提升到此处, 保证各页面共享同一份状态。
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.settingsPrefs() }

    val scope = rememberCoroutineScope()
    // version 变化时强制重读 prefs 同步所有开关状态。
    var version by remember { mutableStateOf(0) }
    // masterOverride: 主开关切换后、落盘前的临时视觉覆盖(null = 无覆盖, 直接读 prefs)。
    // 带 scope, 保证只有触发它的那个主开关(首页全局 / 某个分类)受影响。
    var masterOverride by remember { mutableStateOf<MasterOverride?>(null) }

    // 当前打开的分类 id; null 表示停在首页。
    var openCategoryId by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = openCategoryId != null) { openCategoryId = null }

    // 主开关: scope = null 作用于全部功能(首页), 否则只作用于该分类的设置项。
    val onMasterChange: (String?, Boolean) -> Unit = { targetScope, target ->
        val items = targetScope?.let { id -> CATEGORIES.first { it.id == id }.items.switches }
            ?: CATEGORIES.flatMap { it.items.switches }
        masterOverride = MasterOverride(targetScope, target)
        version++
        scope.launch {
            delay(MASTER_TOGGLE_ANIM_MS)
            // 动画结束后再真正写入设置; IO 线程执行, 避免阻塞 UI。
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    setBool(ctx, item.key, target)
                    if (!target) item.sliderKey?.let { setInt(ctx, it, item.sliderDefault) }
                }
            }
            // 清除临时覆盖, 各开关回到以 prefs 为准(此时已与 target 一致, 无跳变)。
            // 仅当覆盖未被其它主开关的后续操作取代时才清除。
            if (masterOverride == MasterOverride(targetScope, target)) {
                masterOverride = null
                version++
            }
        }
    }

    AnimatedContent(
        targetState = openCategoryId,
        transitionSpec = {
            // 进入子页面时新页自右滑入, 返回首页时新页自左滑入; 一律走 ease 曲线。
            val enter = if (targetState != null) 1 else -1
            val spec = tween<IntOffset>(durationMillis = PAGE_TRANSITION_MS, easing = PAGE_TRANSITION_EASING)
            (fadeIn(animationSpec = tween(durationMillis = PAGE_TRANSITION_MS, easing = PAGE_TRANSITION_EASING)) +
                slideInHorizontally(animationSpec = spec) { enter * it / 5 })
                .togetherWith(
                    fadeOut(animationSpec = tween(durationMillis = PAGE_TRANSITION_MS, easing = PAGE_TRANSITION_EASING)) +
                        slideOutHorizontally(animationSpec = spec) { -enter * it / 5 }
                )
        },
        label = "settings_pages",
    ) { targetId ->
        val category = CATEGORIES.firstOrNull { it.id == targetId }
        if (targetId == DISABLED_APPS_ID) {
            // 只读列表页, 不走开关列表的 CategoryScreen。
            DisabledAppsScreen(ctx = ctx, onBack = { openCategoryId = null })
        } else if (category == null) {
            val anyEnabled = remember(version) {
                CATEGORIES.any { c -> c.items.switches.any { prefs.getBoolean(it.key, false) } }
            }
            HomeScreen(
                masterChecked = masterOverride.valueFor(null) ?: anyEnabled,
                onMasterChange = { onMasterChange(null, it) },
                onOpenCategory = { openCategoryId = it },
            )
        } else {
            val override = masterOverride.valueFor(category.id)
            val anyEnabled = remember(version) { category.items.switches.any { prefs.getBoolean(it.key, false) } }
            CategoryScreen(
                category = category,
                prefs = prefs,
                ctx = ctx,
                version = version,
                overrideValue = override,
                masterChecked = override ?: anyEnabled,
                onMasterChange = { onMasterChange(category.id, it) },
                onItemChanged = { version++ },
                onBack = { openCategoryId = null },
            )
        }
    }
}

/** 首页: 分组标题 + 一键启用(全局) + 分类入口列表（每个分组一张卡片）。 */
@Composable
private fun HomeScreen(
    masterChecked: Boolean,
    onMasterChange: (Boolean) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    // 回弹位移: 共享给标题栏分割线, 让不可滚动页面上滑回弹时分割线也出现。
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    // 系统名要读 getprop(root shell), 不能阻塞首帧: 先空着, IO 线程取到后再补上。
    var system by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        system = withContext(Dispatchers.IO) { systemLabel() }
    }
    Scaffold(
        topBar = {
            CouixLargeTitle(
                title = "ColorOS Mod",
                dividerProgress = couixTopBarDividerProgress(listState, overscrollOffset),
                actions = { RestartMenu(ctx) },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .couixOverscroll(listState, overscrollOffset),
        ) {
            item {
                CouixMasterToggle(
                    checked = masterChecked,
                    title = if (masterChecked) HOME_MASTER_TITLE_ON else MASTER_TITLE_OFF,
                    // 启用后无需再解释, 仅未启用时提示。
                    subtitle = if (masterChecked) null else HOME_MASTER_HINT,
                    onCheckedChange = onMasterChange,
                    // 机型横幅作为第一张卡片的首项, 只裁顶部两角(下方与开关行相接)。
                    aboveContent = {
                        CouixDeviceHeader(
                            model = deviceName(ctx),
                            system = system,
                            shape = RoundedCornerShape(
                                topStart = COUIX_CARD_CORNER,
                                topEnd = COUIX_CARD_CORNER,
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
                    CouixCard {
                        group.forEachIndexed { index, category ->
                            if (index > 0) CouixItemDivider(startInset = COUIX_CATEGORY_TEXT_START)
                            CouixCategoryRow(
                                icon = category.icon,
                                title = category.title,
                                subtitle = category.subtitle,
                                onClick = { onOpenCategory(category.id) },
                            )
                        }
                    }
                }
            }
            item { Box(Modifier.height(20.dp)) }
        }
    }
}

// 子页面的"一键启用"主开关: 文案与绘制集中在此一处配置, 各分类子页面复用;
// 只控制当前分类内的设置项, 不带副标题(滑块说明在该页第二个 group 的 header 上)。
@Composable
private fun CategoryMasterToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    CouixMasterToggle(
        checked = checked,
        title = if (checked) CATEGORY_MASTER_TITLE_ON else MASTER_TITLE_OFF,
        onCheckedChange = onCheckedChange,
    )
}

/** 分类子页面: 带返回的标题栏 + 该分类的一键启用 + 该分类的全部设置项。 */
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
) {
    val listState = rememberLazyListState()
    // 按 GroupTitleItem 切分: 每段一张卡片, 段与段之间自然留出 group 间距。
    val groups = remember(category) { category.items.splitByDivider() }
    // 回弹位移: 共享给标题栏分割线, 让不可滚动页面上滑回弹时分割线也出现。
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    Scaffold(
        topBar = {
            CouixTopAppBar(
                title = category.title,
                dividerProgress = couixTopBarDividerProgress(listState, overscrollOffset),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(COUIX_BACK_ICON),
                        )
                    }
                },
                actions = { RestartMenu(ctx) },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .couixOverscroll(listState, overscrollOffset),
        ) {
            // 第一个 group 的 header: 说明滑块两端值的含义。
            item { CouixSmallTitle(text = SLIDER_GROUP_HINT) }
            item {
                CategoryMasterToggle(
                    checked = masterChecked,
                    onCheckedChange = onMasterChange,
                )
            }
            // 该分类专属说明: 作为设置项 group 的 header 显示(仅配置了 hint 的分类)。
            val hint = category.hint
            if (hint != null) {
                item { CouixSmallTitle(text = hint) }
            }
            groups.forEach { group ->
                // 分组小标题: GroupTitleItem 的 title 非空(且非空字符串)时在卡片上方显示。
                val desc = group.desc
                if (!desc.isNullOrEmpty()) {
                    item { CouixSmallTitle(text = desc) }
                }
                item {
                    CouixGroup(
                        items = group.items,
                        prefs = prefs,
                        ctx = ctx,
                        version = version,
                        overrideValue = overrideValue,
                        onItemChanged = onItemChanged,
                    )
                }
            }

            item { Box(Modifier.height(24.dp)) }
        }
    }
}

// 隐藏模块桌面图标: 通过禁用 LAUNCHER 别名(.MainActivityLauncher)实现,
// 状态由 PackageManager 持有(不写入模块 prefs, 不受一键启用影响), 图标隐藏后只能从 LSPosed 进入。
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

/** 首页首个 group 顶部的"隐藏模块桌面图标"开关行。 */
@Composable
private fun HideLauncherIconRow(ctx: Context) {
    var hidden by remember { mutableStateOf(isLauncherIconHidden(ctx)) }
    CouixSwitchPreference(
        checked = hidden,
        onCheckedChange = {
            hidden = it
            setLauncherIconHidden(ctx, it)
        },
        title = "隐藏模块桌面图标",
        subtitle = "需在 LSPosed 中关闭强制显示模块图标",
    )
}

// 设备名称: 用户在设置里改过的名字存在 Settings.Global["device_name"](该机为 "Rikumi X8s"),
// 未设置时退回 Build.MODEL(如 PKT110)。
private fun deviceName(ctx: Context): String {
    val name = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
    return if (name.isNullOrBlank()) Build.MODEL else name
}

// ROM 大版本: Oplus 系把版本写在这几个只读属性里(该机 oplusrom.display=16.0.10、
// oplusrom=V16.1.0), 取前两个数字段作为大版本(16.0)。属性只能通过 getprop 读, 故走 root shell。
// 逐个尝试: 新版 oplusrom.display -> oplusrom -> 旧版 opporom -> realme 分支的 realmeui。
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

// 是否国行: persist.sys.oplus.region 是 Oplus 全系通用的销售区域属性(该机实测 CN);
// 取不到时退一步看 ota 版本号里的地区段(该机 PKT110_16.0.10.500(CN01)), 仍取不到按国行处理。
private fun isCnRegion(): Boolean {
    val region = getProp("persist.sys.oplus.region")
    if (region != null) return region.equals("CN", ignoreCase = true)
    val ota = getProp("persist.sys.oplus.ota_ver_display")
    return ota == null || ota.contains("CN01", ignoreCase = true)
}

// 系统名: 名称取自 ro.product.brand(公开 API Build.BRAND, 该机为 OPPO), 版本取自系统属性(见上),
// 都不做其它推测; 品牌不在 Oplus 三家里则不显示该行。
// 一加国内版刷的是 ColorOS、只有海外版才是 OxygenOS, 故一加额外看销售区域。
private fun systemName(): String? = when {
    Build.BRAND.contains("oneplus", ignoreCase = true) -> if (isCnRegion()) "ColorOS" else "OxygenOS"
    Build.BRAND.contains("oppo", ignoreCase = true) -> "ColorOS"
    Build.BRAND.equals("realme", ignoreCase = true) -> "realmeUI"
    else -> null
}

// 首页机型横幅的系统行: "<系统名> <地区标识> <版本>", 如 "ColorOS CN 16.0"。
// 地区标识只在版本取到时才插入 —— 单独一个 "ColorOS CN" 既没有信息量也不好看,
// 且 romVersion() 取不到时说明属性读取整体失败, 地区判断同样不可信。
private fun systemLabel(): String? {
    val name = systemName() ?: return null
    val version = romVersion() ?: return name
    val region = regionLabel(name) ?: return "$name $version"
    return "$name $region $version"
}

// 销售地区标识: 国行 CN、海外 EX。判定复用一加分支那套 isCnRegion(见上)。
// OxygenOS 本身就只有海外版(一加国内版刷的是 ColorOS), 再标 EX 是废话, 故返回 null 省略。
private fun regionLabel(name: String): String? {
    if (name == "OxygenOS") return null
    return if (isCnRegion()) "CN" else "EX"
}

/**
 * 首页首个 group 下方的两个快捷启动行: 拉起 KernelSU / LSPosed 管理器。
 * 两者均需在 root shell 中启动 —— 普通 app 进程受后台启动限制, 也无权发 SECRET_CODE 广播。
 */
@Composable
private fun LaunchAppsRows(ctx: Context) {
    // root shell 启动会阻塞到命令结束(冷启动管理器可达秒级), 放到 IO 线程避免卡住 UI。
    val scope = rememberCoroutineScope()
    CouixItemDivider()
    CouixActionPairRow(
        leftTitle = "启动 KernelSU",
        onLeftClick = { scope.launch(Dispatchers.IO) { launchApp(ctx, KERNELSU_LAUNCH) } },
        rightTitle = "启动 LSPosed",
        onRightClick = { scope.launch(Dispatchers.IO) { launchApp(ctx, lsposedLaunchCmd()) } },
    )
}

// KernelSU 管理器有正常的应用入口, 直接拉起其主界面。
private const val KERNELSU_LAUNCH = "am start -n me.weishu.kernelsu/.ui.MainActivity"

// LSPosed 管理器不常驻应用列表, 由模块 action.sh 以 SECRET_CODE 5776733 唤起(Android 10 起
// 广播 action 从 android.provider.Telephony 迁到 android.telephony.action)。
private fun lsposedLaunchCmd(): String {
    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "android.telephony.action.SECRET_CODE"
    } else {
        "android.provider.Telephony.SECRET_CODE"
    }
    return "am broadcast -a $action -d android_secret_code://5776733 android"
}

private fun launchApp(ctx: Context, command: String) {
    if (runRoot(command) == null) {
        // Toast 需回主线程: 本函数在 IO 线程被调用。
        Handler(ctx.mainLooper).post {
            android.widget.Toast.makeText(ctx, "未授予 root 权限", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/** 标题栏右侧的重启菜单（首页与子页面共用）。 */
@Composable
internal fun RestartMenu(ctx: Context) {
    CouixActionMenu(
        icon = {
            Icon(
                painter = rememberVectorPainter(MiuixIcons.Refresh),
                contentDescription = "重启",
            )
        },
        items = listOf(
            ActionMenuItem("重启作用域", { restartScope(ctx) }),
            ActionMenuItem("重启 Zygote", { softRebootSystem(ctx) }),
        ),
    )
}

// 读取桌面上全部文件夹名, 供"新应用添加到"下拉使用。桌面数据库为 launcher.db(4x4 布局为
// launcher_4x4.db), 条目表按桌面模式分三张, 文件夹 itemType=3、堆栈 105; 三张表都查并按 _id 去重。
// 只读: 连同 -wal/-shm 拷到本应用 cache 后以 OPEN_READONLY 打开, 让 SQLite 自己合并 WAL。
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

// 设置存到设备加密(DE)存储: 开机到首次解锁前 SystemUI 就在读设置, 而 CE 存储此时尚未挂载,
// 读到的是空设置(表现为"重启后模块失效, 重启作用域才恢复")。DE 存储在锁定态即可读写。
internal fun Context.settingsPrefs(): SharedPreferences {
    val de = createDeviceProtectedStorageContext()
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
    if (de.all.isEmpty()) migrateLegacyPrefs(this, de)
    return de
}

// 旧版本把设置写在 CE 存储, 升级后首次读取时搬一次, 避免用户设置丢失。
private fun migrateLegacyPrefs(ctx: Context, de: SharedPreferences) {
    val legacy = runCatching { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).all }
        .getOrDefault(emptyMap())
    if (legacy.isEmpty()) return
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
    e.commit()
}

internal fun setBool(ctx: Context, key: String, value: Boolean) {
    ctx.settingsPrefs().edit().putBoolean(key, value).commit()
}

internal fun setInt(ctx: Context, key: String, value: Int) {
    ctx.settingsPrefs().edit().putInt(key, value).commit()
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
        android.widget.Toast.makeText(ctx, "未授予 root 权限", android.widget.Toast.LENGTH_SHORT).show()
    }
}

// 重启 Zygote（对齐 KernelSU 实现）：通过 init 的 ctl.restart 属性重启 zygote 服务。
// 只重启 Android 用户空间，Linux 内核不重启，因此 KernelSU 与 LSPosed Zygisk 依然生效。
private fun softRebootSystem(ctx: Context) {
    runCatching {
        Runtime.getRuntime().exec("su").also { p ->
            val os = DataOutputStream(p.outputStream)
            os.writeBytes("setprop ctl.restart zygote\n")
            os.writeBytes("exit\n")
            os.flush()
            p.waitFor()
        }
    }.onFailure {
        android.widget.Toast.makeText(ctx, "未授予 root 权限", android.widget.Toast.LENGTH_SHORT).show()
    }
}

// 读取 ColorOS 主题色。SystemUI 在 OpUtils#getThemeAccentColor 里 resolve R.attr.couiColorPrimary
// 并落盘到 Settings.Secure["sysui_type_accent_color"], 直接读该 key 即可(与莫奈动态色是两套体系)。
// 兜底退而读 theme_customization_overlay_packages JSON 里的 accent_color / system_palette。
private fun colorOSAccentColor(context: Context, fallback: Long): Color {
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

// 兜底: 从 theme_customization_overlay_packages JSON 里取 monet 强调色(通常与 couiColorPrimary 一致)。
// 返回可直接交给 Color.parseColor 的 "#RRGGBB"/"#AARRGGBB" 字符串。
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

// 以 root 执行 shell 命令并返回 stdout(无 root / su 不存在 / 出错时返回 null)。
// 全程静默: 任何异常都吞掉、绝不向上抛, 保证首次安装无权限时不崩溃。
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

/** 是否有可用的 root(su 可用且以 uid=0 运行)。 */
private fun hasRootAccess(): Boolean {
    return runRoot("id -u")?.trim() == "0"
}

// 模块是否已在 LSPosed 中启用。状态存在 SQLite 库 modules_config.db 的 modules_state 表,
// 但变更常驻留在 -wal 里未 checkpoint, 故不能只读主 .db(会读到旧的 enabled=1), 必须连同
// -wal/-shm 一起拷出后只读打开, 让 SQLite 自动合并 WAL。判定: 有本模块记录且 enabled=1。
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
                    "请在 LSPosed 中启用模块",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }.start()
}
