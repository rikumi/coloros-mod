package com.rikumi.colorosmod

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
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
                    SettingsScreen()
                }
            }
        }
    }
}

/**
 * 单个设置项: key 用于持久化与 Xposed 读取, label 取自原 strings.xml 中的名称。
 * sliderKey 非空时, 开关打开后下方显示滑条: 范围 0..sliderMax(整数步进), 默认 sliderDefault,
 * sliderUnit 为值后缀(如 dp/sp/%)。sliderMax 统一取对应功能硬编码值的两倍, 0 即系统默认(不改变)。
 */
internal data class SwitchItem(
    val key: String,
    val label: String,
    val subtitle: String? = null,
    val sliderKey: String? = null,
    val sliderMax: Int = 0,
    val sliderDefault: Int = 0,
    val sliderUnit: String = "dp",
)

internal data class SelectItem(
    val key: String,
    val label: String,
    val options: List<String>,
    val defaultValue: Int = 0,
)

private val DESKTOP = listOf(
    SwitchItem("icon_gap_enabled", "增加图标与名称间距", sliderKey = "icon_gap_dp", sliderMax = 8, sliderDefault = 4),
    SwitchItem("indicator_enabled", "减小页面与 Dock 间距", sliderKey = "indicator_dp", sliderMax = 32, sliderDefault = 16, sliderUnit = "dp"),
    SwitchItem("shrink_popup_menu", "缩小图标长按菜单", sliderKey = "popup_scale_percent", sliderMax = 20, sliderDefault = 10, sliderUnit = "%"),
    SwitchItem("folder_bg_transparent_enabled", "文件夹展开背景透明"),
    SwitchItem("edit_mode_bg_transparent_enabled", "取消编辑模式背景遮罩"),
    SwitchItem("hide_contacts_enabled", "彻底隐藏电话本图标"),
    SwitchItem("hide_gboard_enabled", "彻底隐藏 Gboard 图标"),
    SwitchItem("hide_ghostlock_enabled", "彻底隐藏 GhostLock 图标", subtitle = "显然已经有 root 的时候不需要再 root"),
)
private val QS = listOf(
    SwitchItem("qs_scrim_translucent_enabled", "自定义控制中心背景亮度", sliderKey = "qs_scrim_brightness", sliderMax = 20, sliderDefault = 5, sliderUnit = ""),
    SwitchItem("qs_carrier_enabled", "去除控制中心运营商显示"),
    SwitchItem("qs_topmargin_enabled", "隐藏控制中心顶部状态图标簇"),
    SwitchItem("qs_tile_name_ellipsis_enabled", "分离控制中心 Wi-Fi / 蓝牙名称单行省略"),
    SwitchItem("fluid_cloud_keep_percent_enabled", "流体云出现时不隐藏电量百分比"),
)
private val NOTIF = listOf(
    SwitchItem("notification_subtitle_enabled", "缩小通知静默区域副标题", sliderKey = "notification_subtitle_sp", sliderMax = 16, sliderDefault = 8, sliderUnit = "sp"),
    SwitchItem("notification_padding_enabled", "增加通知上下内边距", sliderKey = "notification_padding_dp", sliderMax = 8, sliderDefault = 4),
)
private val HIDDEN = listOf(
    SwitchItem("recents_show_hidden_enabled", "多任务显示隐藏应用"),
    SwitchItem("hide_apps_noverify_enabled", "打开隐藏应用文件夹免验证"),
    SwitchItem("pinch_out_open_hide_apps_enabled", "桌面双指张开打开隐藏应用"),
    SwitchItem("hide_apps_title_folder_enabled", "应用隐藏标题显示文件夹名"),
)

// 标题栏底部分割线: 界面上滑时出现, 初始两端各内缩 16dp, 随滚动量在该距离内逐渐延长至通栏。
private val TOP_BAR_DIVIDER_INSET = 16.dp
private val TOP_BAR_DIVIDER_EXTEND_SCROLL = 48.dp

// 主开关"启用模块"切换动画的时长(ms): 先更新 UI 播放动画, 动画结束后才落盘设置。
private const val MASTER_TOGGLE_ANIM_MS = 350L

@Composable
fun SettingsScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var scrolledUpPx by remember { mutableStateOf(0f) }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 内容上滑时 consumed.y 为负, 取反累计即上滑距离; 滚回顶部时钳制到 0。
                scrolledUpPx = (scrolledUpPx - consumed.y).coerceAtLeast(0f)
                return Offset.Zero
            }
        }
    }
    val dividerProgress =
        (scrolledUpPx / with(density) { TOP_BAR_DIVIDER_EXTEND_SCROLL.toPx() }).coerceIn(0f, 1f)

    // 全部功能项(用于"启用模块"主开关), version 变化时强制重读 prefs 同步所有开关状态。
    val allItems = remember { DESKTOP + QS + NOTIF + HIDDEN }
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    // masterOverride: 主开关切换后、落盘前的临时视觉覆盖(null = 无覆盖, 直接读 prefs)。
    var masterOverride by remember { mutableStateOf<Boolean?>(null) }
    val anyEnabled = remember(version, masterOverride) {
        masterOverride ?: allItems.any { prefs.getBoolean(it.key, false) }
    }

    Scaffold(
        topBar = {
            Box {
                CouixLargeTitle(
                    title = "ColorOS Mod",
                    actions = {
                        IconButton(onClick = { restartScope(ctx) }) {
                            Icon(
                                painter = rememberVectorPainter(MiuixIcons.Refresh),
                                contentDescription = "重启作用域",
                            )
                        }
                    },
                )
                if (dividerProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = TOP_BAR_DIVIDER_INSET * (1f - dividerProgress))
                            .height((1f / density.density).dp)
                            .background(Color.White.copy(alpha = 0.2f * dividerProgress)),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollConnection),
        ) {
            item { CouixSmallTitle(text = "By Rikumi / Couix 基于 Miuix 魔改") }
            item {
                CouixMasterToggle(
                    checked = anyEnabled,
                    title = "一键启用",
                    subtitle = if (anyEnabled) "滑块设置通常最左/最右为系统值，中间为推荐值" else "点击无脑启用全部，注意下方隐藏应用的设置",
                    onCheckedChange = { target ->
                        // 先更新 UI(所有开关立即显示 target, 播放切换动画), 暂不落盘。
                        masterOverride = target
                        version++
                        scope.launch {
                            delay(MASTER_TOGGLE_ANIM_MS)
                            // 动画结束后再真正写入设置; IO 线程执行, 避免阻塞 UI。
                            withContext(Dispatchers.IO) {
                                allItems.forEach { setBool(ctx, it.key, target) }
                            }
                            // 清除临时覆盖, 各开关回到以 prefs 为准(此时已与 target 一致, 无跳变)。
                            masterOverride = null
                            version++
                        }
                    },
                )
            }
            item { CouixSmallTitle(text = "桌面") }
            item { CouixGroup(items = DESKTOP, prefs = prefs, ctx = ctx, version = version, overrideValue = masterOverride, onItemChanged = { version++ }) }
            item { CouixSmallTitle(text = "控制中心") }
            item { CouixGroup(items = QS, prefs = prefs, ctx = ctx, version = version, overrideValue = masterOverride, onItemChanged = { version++ }) }
            item { CouixSmallTitle(text = "通知中心") }
            item { CouixGroup(items = NOTIF, prefs = prefs, ctx = ctx, version = version, overrideValue = masterOverride, onItemChanged = { version++ }) }
            item { CouixSmallTitle(text = "隐藏应用") }
            item { CouixGroup(items = HIDDEN, prefs = prefs, ctx = ctx, version = version, overrideValue = masterOverride, onItemChanged = { version++ }) }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}


internal fun setBool(ctx: Context, key: String, value: Boolean) {
    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit().putBoolean(key, value).commit()
    makePrefsWorldReadable(ctx)
}

internal fun setInt(ctx: Context, key: String, value: Int) {
    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit().putInt(key, value).commit()
    makePrefsWorldReadable(ctx)
}

private fun makePrefsWorldReadable(ctx: Context) {
    runCatching {
        val f = File("/data/data/${ctx.packageName}/shared_prefs/settings.xml")
        if (f.exists()) {
            Runtime.getRuntime().exec("su").also { p ->
                val os = DataOutputStream(p.outputStream)
                os.writeBytes("chmod 644 ${f.absolutePath}\n")
                os.writeBytes("exit\n")
                os.flush()
                p.waitFor()
            }
        }
    }
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

/**
 * 读取 ColorOS 主题色(强调色)。
 *
 * ColorOS 的主题色是 COUI 主题系统里的 couiColorPrimary, 与 Android 莫奈动态色
 * (system_accent1_*) 是两套独立体系。SystemUI 在 OpUtils#getThemeAccentColor 里
 * 通过 ContextThemeWrapper(Theme_SystemUI) + COUIThemeOverlay.applyThemeOverlays()
 * resolve R.attr.couiColorPrimary 得到当前主题色, 并把它落盘到
 * Settings.Secure["sysui_type_accent_color"](格式 "#RRGGBB", 如 "#ff8c909f")。
 *
 * 因此直接读这个 key 即可拿到用户当前主题色(实测 #ff8c909f, 即略微偏蓝的灰色)。
 * 兜底: 若 SystemUI 尚未写入, 退而读 theme_customization_overlay_packages JSON 里的
 * accent_color / system_palette(值为无 "#" 前缀的 hex, 补前缀后 Color.parseColor)。
 *
 * @param fallback 所有来源都拿不到颜色时的兜底色(0xAARRGGBB)。
 */
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

/**
 * 兜底: 从 theme_customization_overlay_packages JSON 里取 monet 强调色(通常与上面的
 * couiColorPrimary 一致)。返回可直接交给 Color.parseColor 的 "#RRGGBB"/"#AARRGGBB" 字符串。
 */
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

/**
 * 以 root 执行一段 shell 命令, 返回其 stdout(无 root / su 不存在 / 命令出错时返回 null)。
 * 全程静默: 任何异常都被吞掉, 绝不向上抛, 保证首次安装无权限时不崩溃。
 */
private fun runRoot(command: String): String? {
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

/**
 * 模块是否已在 LSPosed 中启用。
 *
 * LSPosed 1.8+ 把模块启用状态存在 SQLite 库 /data/adb/lspd/config/modules_config.db 的
 * modules_state 表(enabled 字段), 且启用/禁用的变更常驻留在 -wal 文件里尚未 checkpoint。
 * 因此不能只读主 .db(会读到旧的 enabled=1), 必须连同 -wal/-shm 一起拷出后用只读方式打开,
 * 让 SQLite 自动合并 WAL 得到最新状态。
 *
 * 判定: modules_state 中存在本模块记录且 enabled=1 才算启用; 记录不存在(LSPosed 禁用时会
 * 删除该记录)或 enabled=0 都视为未启用。任何异常 / 无 root / 路径不存在一律返回 false。
 */
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

/**
 * 启动时后台自检环境:
 * - 无 root        -> 兼容运行, 不弹提示(刷新按钮点击时已有"未授予 root 权限"提示)。
 * - 有 root 但模块未在 LSPosed 启用 -> toast 提示去启用。
 */
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
