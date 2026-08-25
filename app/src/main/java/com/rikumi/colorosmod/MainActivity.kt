package com.rikumi.colorosmod

import android.content.Context
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            MiuixTheme(
                controller = remember {
                    ThemeController(
                        colorSchemeMode = ColorSchemeMode.System,
                        lightColors = lightColorScheme(
                            primary = colorOSAccentColor(context, "system_accent1_500", 0xFF00B4D8)
                        ),
                        darkColors = darkColorScheme(
                            primary = colorOSAccentColor(context, "system_accent1_200", 0xFF00B4D8)
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

/**
 * 单个设置项: key 用于持久化与 Xposed 读取, label 取自原 strings.xml 中的名称。
 * sliderKey 非空时, 开关打开后下方显示滑条: 范围 0..sliderMax(整数步进), 默认 sliderDefault,
 * sliderUnit 为值后缀(如 dp/sp/%)。sliderMax 统一取对应功能硬编码值的两倍, 0 即系统默认(不改变)。
 */
internal data class SwitchItem(
    val key: String,
    val label: String,
    val sliderKey: String? = null,
    val sliderMax: Int = 0,
    val sliderDefault: Int = 0,
    val sliderUnit: String = "dp",
)

private val DESKTOP = listOf(
    SwitchItem("icon_gap_enabled", "增加图标与名称间距", sliderKey = "icon_gap_dp", sliderMax = 8, sliderDefault = 4),
    SwitchItem("indicator_enabled", "减小页面与 Dock 间距", sliderKey = "indicator_dp", sliderMax = 32, sliderDefault = 16),
    SwitchItem("shrink_popup_menu", "缩小图标长按菜单", sliderKey = "popup_scale_percent", sliderMax = 20, sliderDefault = 10, sliderUnit = "%"),
    SwitchItem("hide_contacts_enabled", "隐藏电话本图标"),
    SwitchItem("hide_gboard_enabled", "隐藏 Gboard 图标"),
    SwitchItem("folder_bg_transparent_enabled", "文件夹展开背景透明"),
)
private val QS = listOf(
    SwitchItem("qs_scrim_translucent_enabled", "自定义控制中心背景亮度", sliderKey = "qs_scrim_brightness", sliderMax = 20, sliderDefault = 10, sliderUnit = ""),
    SwitchItem("qs_carrier_enabled", "去除控制中心运营商显示"),
    SwitchItem("qs_topmargin_enabled", "隐藏控制中心顶部状态图标簇"),
    SwitchItem("qs_tile_name_ellipsis_enabled", "Wi-Fi / 蓝牙名称单行省略"),
)
private val NOTIF = listOf(
    SwitchItem("notification_subtitle_enabled", "缩小通知静默区域副标题", sliderKey = "notification_subtitle_sp", sliderMax = 16, sliderDefault = 8, sliderUnit = "sp"),
    SwitchItem("notification_padding_enabled", "非静默通知增加上下内边距", sliderKey = "notification_padding_dp", sliderMax = 8, sliderDefault = 4),
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
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_WORLD_READABLE) }
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
        masterOverride ?: allItems.any { prefs.getBoolean(it.key, true) }
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
                            .background(Color.White.copy(alpha = 0.16f * dividerProgress)),
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
                    title = "启用模块",
                    subtitle = if (anyEnabled) "所有滑块设置最左/最右为系统值，中间为默认值" else "点击无脑启用全部，注意下方隐藏应用的设置",
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
    ctx.getSharedPreferences("settings", Context.MODE_WORLD_READABLE)
        .edit().putBoolean(key, value).commit()
    makePrefsWorldReadable(ctx)
}

internal fun setInt(ctx: Context, key: String, value: Int) {
    ctx.getSharedPreferences("settings", Context.MODE_WORLD_READABLE)
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
 * ColorOS 把用户选择的主题色(含"跟随壁纸"莫奈色与手动预设色)通过主题 overlay 合成到
 * Android 12+ 的系统动态色资源 android.R.color.system_accent1_* 上(launcher 的
 * DropZoneView / ThemedIconDrawable / IconThemedUtil 等官方代码均直接读这些资源)。
 * 因此直接读 system_accent1_500(浅色主色) / system_accent1_200(深色主色) 即为用户当前
 * 主题色, 无需解析 Settings.Secure 里的 theme_customization_overlay_packages JSON
 * (那里面只有 color_source / color_index 索引, 不是具体颜色值)。
 *
 * @param name     系统颜色资源名(如 "system_accent1_500"), 用 getIdentifier 兼容 API < 31。
 * @param fallback 资源不存在或读取失败时的兜底颜色(0xAARRGGBB)。
 */
private fun colorOSAccentColor(context: Context, name: String, fallback: Long): Color {
    return try {
        val id = context.resources.getIdentifier(name, "color", "android")
        if (id == 0) Color(fallback) else Color(context.getColor(id))
    } catch (t: Throwable) {
        Color(fallback)
    }
}
