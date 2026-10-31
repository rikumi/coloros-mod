package com.rikumi.colorosmod

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.rikumi.colorosmod.hooks.AboutCardStyleResolver
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardColors
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.ColorPalette
import io.github.suqi8.coui.kmp.basic.DropdownDefaults.dropdownColors
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.VerticalDivider
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Slider
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.popup.WindowDropdownPopup
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.preference.WindowDropdownPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 首页机型横幅与关于本机卡片共享原厂背景素材。
private val SETTINGS_HEADER_HEIGHT = 200.dp
// 横幅内系统名: 相对文字色的不透明度, 以及贴底时离底边的距离。
private const val SETTINGS_HEADER_SYSTEM_ALPHA = 0.9f
private val SETTINGS_HEADER_SYSTEM_BOTTOM = 24.dp
// 设备名自横幅正中再上移的距离。
private val SETTINGS_HEADER_MODEL_LIFT = 16.dp

// 横幅长按时触发连续振动的阈值(ms): 按住超过阈值后开始连续细微震动, 松手即停。
private const val BANNER_LONG_PRESS_MS = 350L
// 长按期间震动间隔(ms): 越小越频繁。
private const val BANNER_TICK_INTERVAL_MS = 35L
// 单次震动时长(ms)与振幅(0~255): 极短 + 低振幅 = 更细微的连续震感。
private const val BANNER_VIBRATE_MS = 8L
private const val BANNER_VIBRATE_AMPLITUDE = 100

// 首页机型横幅与分组卡片使用相同圆角。
internal val SETTINGS_CARD_CORNER = 12.dp

// 分组左右外边距（相对屏幕边缘）。
internal val SETTINGS_GROUP_HMARGIN = 16.dp

// 卡片下方留白 = 相邻 section 之间的间距（首页卡片→卡片、子页面卡片→下一节小标题）。
private val SETTINGS_CARD_BOTTOM_GAP = 16.dp

// 分割线两端距离 group 边界的内缩。
private val SETTINGS_DIVIDER_INSET = 16.dp

// 列表项内部水平/垂直内边距。
private val SETTINGS_ROW_HPADDING = 16.dp
private val SETTINGS_ROW_VPADDING = 13.dp

internal val SETTINGS_BACK_ICON = 26.dp
private val SETTINGS_CATEGORY_ICON_SIZE = 22.dp
// COUI BasicComponent places the title 8dp after the start-action slot.
internal val SETTINGS_CATEGORY_TEXT_START = BasicComponentDefaults.InsideMargin
    .calculateLeftPadding(LayoutDirection.Ltr) + SETTINGS_CATEGORY_ICON_SIZE + 8.dp

@Composable
fun SettingsSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    SmallTitle(text = text, modifier = modifier)
}

@Composable
fun SettingsLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrollBehavior: ScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    SmallTopAppBar(
        title = title, subtitle = subtitle.orEmpty(), modifier = modifier,
        scrollBehavior = scrollBehavior, actions = actions,
    )
}

@Composable
fun SettingsStatusBar() {
    val view = LocalView.current
    val surface = COUITheme.colorScheme.surface
    // 亮底配深色图标, 暗底配浅色图标: 以感知亮度判定, 不依赖主题模式枚举。
    val lightIcons = surface.luminance() > 0.5f
    DisposableEffect(view, surface, lightIcons) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = surface.toArgb()
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = lightIcons
        }
        onDispose { }
    }
}


@Composable
fun SettingsTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    SmallTopAppBar(
        title = title, modifier = modifier, scrollBehavior = scrollBehavior,
        navigationIcon = { navigationIcon?.invoke() }, actions = actions,
    )
}

// 项目分组间距与首页自定义内容共用的 COUI 卡片。
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val baseCard = CardDefaults.defaultColors()
    val cardColors: CardColors = baseCard.copy(color = backgroundColor ?: baseCard.color)
    Card(
        modifier = modifier.padding(
            start = SETTINGS_GROUP_HMARGIN,
            top = 2.dp,
            end = SETTINGS_GROUP_HMARGIN,
            bottom = SETTINGS_CARD_BOTTOM_GAP,
        ),
        cornerRadius = SETTINGS_CARD_CORNER,
        colors = cardColors,
        content = content,
    )
}

@Composable
internal fun SettingsCardRow(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val baseCard = CardDefaults.defaultColors()
    val cardColor = baseCard.color
    val shape = when {
        first && last -> RoundedCornerShape(SETTINGS_CARD_CORNER)
        first -> RoundedCornerShape(
            topStart = SETTINGS_CARD_CORNER,
            topEnd = SETTINGS_CARD_CORNER,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        )
        last -> RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = SETTINGS_CARD_CORNER,
            bottomEnd = SETTINGS_CARD_CORNER,
        )
        else -> RectangleShape
    }
    Column(
        modifier = modifier
            .padding(
                start = SETTINGS_GROUP_HMARGIN,
                end = SETTINGS_GROUP_HMARGIN,
                top = if (first) 2.dp else 0.dp,
                bottom = if (last) SETTINGS_CARD_BOTTOM_GAP else 0.dp,
            )
            .background(cardColor, shape)
            // 只有带圆角的首尾行需要裁剪(水波纹不溢出圆角); 中间行是矩形, 免掉一层。
            .then(if (first || last) Modifier.clip(shape) else Modifier),
        content = content,
    )
}

@Composable
internal fun SettingsCategoryRow(
    icon: ImageVector?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean? = null,
) {
    ArrowPreference(
        title = title, onClick = onClick,
        modifier = modifier.then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        holdDownState = selected == true,
        startAction = icon?.let { image -> { Icon(imageVector = image, contentDescription = null, modifier = Modifier.size(SETTINGS_CATEGORY_ICON_SIZE)) } },
        endActions = { if (subtitle != null) Text(text = subtitle, color = settingsSummaryTextColor()) },
    )
}

internal data class HomeBannerInfo(val style: Int, val model: String, val system: String?)

/** One cache per selected style, owned by SettingsScreen rather than a lazy/page item. */
internal class HomeBannerCache {
    var info by mutableStateOf<HomeBannerInfo?>(null)
    var bitmap by mutableStateOf<ImageBitmap?>(null)
}

@Composable
internal fun SettingsDeviceHeader(
    backgroundStyle: Int?,
    model: String,
    system: String?,
    cache: HomeBannerCache,
    contentReady: Boolean = true,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(SETTINGS_CARD_CORNER),
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var bannerTickJob by remember { mutableStateOf<Job?>(null) }
    val longPressModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            val vibrator =
                view.context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val tick =
                VibrationEffect.createOneShot(BANNER_VIBRATE_MS, BANNER_VIBRATE_AMPLITUDE)
            val startJob = scope.launch {
                delay(BANNER_LONG_PRESS_MS)
                bannerTickJob = scope.launch {
                    while (isActive) {
                        vibrator.vibrate(tick)
                        delay(BANNER_TICK_INTERVAL_MS)
                    }
                }
            }
            tryAwaitRelease()
            startJob.cancel()
            bannerTickJob?.cancel()
            bannerTickJob = null
        })
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SETTINGS_HEADER_HEIGHT)
            .clip(shape)
            .then(longPressModifier),
    ) {
        val bitmap = cache.bitmap
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = listOfNotNull(model, system).joinToString("\n"),
                contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
        } else {
            androidx.compose.runtime.key(cache) {
                LoadHomeBanner(backgroundStyle, model, system, contentReady, cache)
            }
        }
    }
}

@Composable
private fun LoadHomeBanner(
    backgroundStyle: Int?,
    model: String,
    system: String?,
    contentReady: Boolean,
    cache: HomeBannerCache,
) {
    val light = !isSystemInDarkTheme()
    val textColor = if (backgroundStyle == 5 && light) Color.Black else Color.White
    val configuration = LocalConfiguration.current
    val background = backgroundStyle?.let {
        AboutCardStyleResolver.backgroundResource(
            it,
            configuration.smallestScreenWidthDp >= 600 && configuration.orientation == 2,
        )
    }
    // painterResource 返回时已完成资源解码；隐藏期间仍保留横幅的固定尺寸。
    val painter = background?.let { painterResource(it) }
    var placement by remember(background, configuration, contentReady) { mutableStateOf<Rect?>(null) }
    var ready by remember(background, configuration, contentReady) { mutableStateOf(false) }
    LaunchedEffect(painter, placement, contentReady) {
        if (contentReady && painter != null && placement?.isEmpty == false && !ready) {
            // 布局连续两帧未变化再显示；位置变化会取消当前等待。
            withFrameNanos { }
            withFrameNanos { }
            ready = true
        }
    }

    val layer = rememberGraphicsLayer()
    var captureStarted by remember { mutableStateOf(false) }
    val captureScope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        if (!ready) placement = coordinates.boundsInWindow()
    }.drawWithContent {
        if (ready) {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            if (!captureStarted) {
                captureStarted = true
                captureScope.launch { cache.bitmap = layer.toImageBitmap() }
            }
        }
    }) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // 设备名在横幅正中, 再上移 SETTINGS_HEADER_MODEL_LIFT。
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = -SETTINGS_HEADER_MODEL_LIFT),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = model,
                style = COUITheme.textStyles.title1.copy(
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        // 系统名贴横幅底边。
        if (!system.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(bottom = SETTINGS_HEADER_SYSTEM_BOTTOM),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = system,
                    style = COUITheme.textStyles.body1.copy(
                        color = textColor.copy(alpha = SETTINGS_HEADER_SYSTEM_ALPHA),
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun SettingsActionPairRow(
    leftTitle: String,
    onLeftClick: () -> Unit,
    rightTitle: String,
    onRightClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dividerWidth: Dp = (1f / density.density).dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsActionCell(
            title = leftTitle,
            onClick = onLeftClick,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = SETTINGS_ROW_VPADDING)
                .width(dividerWidth)
                .background(COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)),
        )
        SettingsActionCell(
            title = rightTitle,
            onClick = onRightClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SettingsActionCell(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ArrowPreference(
        title = title, onClick = onClick, modifier = modifier,
    )
}

@Composable
fun SettingsSwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    leftTrailingContent: @Composable RowScope.() -> Unit = {},
    showDivider: Boolean = false,
    enabled: Boolean = true,
) {
    if (onTitleClick == null) {
        SwitchPreference(
            checked = checked, onCheckedChange = onCheckedChange, title = title,
            summary = subtitle, summaryColor = settingsSummaryColors(), modifier = modifier, enabled = enabled,
            endActions = leftTrailingContent,
        )
    } else {
        // Expandable settings keep title clicks separate from the switch action.
        BasicComponent(
            title = title, summary = subtitle, summaryColor = settingsSummaryColors(), modifier = modifier, enabled = enabled,
            onClick = onTitleClick,
            endActions = {
                leftTrailingContent()
                if (showDivider) {
                    VerticalDivider(
                        modifier = Modifier.padding(start = 12.dp).height(24.dp),
                        thickness = with(LocalDensity.current) { 1.toDp() },
                        color = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f),
                    )
                }
                Switch(
                    checked = checked, onCheckedChange = onCheckedChange,
                    enabled = enabled, modifier = Modifier.padding(start = 12.dp),
                )
            },
        )
    }
}

@Composable
fun SettingsItemDivider(modifier: Modifier = Modifier, startInset: Dp = SETTINGS_DIVIDER_INSET) {
    HorizontalDivider(
        modifier = modifier.padding(start = startInset, end = 16.dp),
    )
}

@Composable
internal fun SettingsGroup(
    items: List<SettingsItem>,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int = 0,
    overrideValue: Boolean? = null,
    onItemChanged: () -> Unit = {},
    onOpenNavigationApps: () -> Unit = {},
    onOpenFontApps: () -> Unit = {},
    modifier: Modifier = Modifier,
    footerContent: (@Composable () -> Unit)? = null,
) {
    SettingsCard(modifier = modifier) {
        items.forEachIndexed { index, item ->
            if (index > 0) SettingsItemDivider()
            androidx.compose.runtime.key(item.keyForCompose) {
                when (item) {
                    is SwitchItem -> {
                        SettingsSwitchRow(item = item, prefs = prefs, ctx = ctx, version = version,
                            overrideValue = overrideValue.takeUnless { it == true && !item.masterEnableAllowed },
                            onItemChanged = onItemChanged)
                    }
                    is FolderBlockItem -> SettingsFolderBlockRow(item = item, prefs = prefs, ctx = ctx, version = version, overrideValue = overrideValue, onItemChanged = onItemChanged)
                    is SelectItem -> {
                        SettingsSelectRow(item = item, prefs = prefs, ctx = ctx, version = version,
                            onItemChanged = onItemChanged)
                        if (item.key == XposedInit.KEY_NOTIFICATION_LAYOUT_STYLE) {
                            SettingsItemDivider()
                            NotificationLayoutTestRow(ctx)
                        }
                    }
                    is GroupTitleItem -> Unit
                    is FontAppsItem -> SettingsCategoryRow(
                        icon = null,
                        title = ctx.getString(item.title),
                        onClick = onOpenFontApps,
                    )
                    is NavigationAppsItem -> SettingsCategoryRow(
                        icon = null,
                        title = ctx.getString(item.title),
                        onClick = onOpenNavigationApps,
                    )
                }
            }
        }
        if (footerContent != null) {
            if (items.isNotEmpty()) SettingsItemDivider()
            footerContent()
        }
    }
}

private val SettingsItem.keyForCompose: String
    get() = when (this) {
        is SwitchItem -> key
        is FolderBlockItem -> key
        is SelectItem -> key
        is GroupTitleItem -> "group:$title"
        is NavigationAppsItem -> "navigation_apps"
        is FontAppsItem -> "font_mount_apps"
    }

@Composable
private fun SettingsFolderBlockRow(
    item: FolderBlockItem,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int,
    overrideValue: Boolean?,
    onItemChanged: () -> Unit,
) {
    var checked by remember(item.key, version) {
        mutableStateOf(overrideValue ?: prefs.getBoolean(item.key, false))
    }
    val scope = rememberCoroutineScope()
    SettingsSwitchPreference(
        checked = checked,
        onCheckedChange = {
            checked = it
            setBool(ctx, item.key, it)
            if (it) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                deleteEmptyMediaFolder(item.folderName)
            } else {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    createMediaFolder(item.folderName)
                }
            }
            onItemChanged()
        },
        title = item.label,
    )
}

@Composable
internal fun SettingsSelectGroup(
    items: List<SelectItem>,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int = 0,
    modifier: Modifier = Modifier,
) {
    SettingsCard(modifier = modifier) {
        items.forEachIndexed { index, item ->
            if (index > 0) SettingsItemDivider()
            SettingsSelectRow(item = item, prefs = prefs, ctx = ctx, version = version)
        }
    }
}

@Composable
private fun NotificationLayoutTestRow(ctx: Context) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    fun send() {
        if (sending) return
        sending = true
        scope.launch {
            try {
                val sent = withContext(Dispatchers.IO) { NotificationLayoutPreview.send(ctx) }
                if (!sent) android.widget.Toast.makeText(ctx,
                    R.string.ui_notification_layout_test_failed, android.widget.Toast.LENGTH_SHORT).show()
            } finally { sending = false }
        }
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) send()
        else android.widget.Toast.makeText(ctx,
            R.string.ui_notification_layout_test_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
    SettingsActionCell(
        title = androidx.compose.ui.res.stringResource(R.string.ui_send_test_notification),
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        onClick = {
            if (!sending) {
                if (android.os.Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else send()
            }
        },
    )
}

@Composable
private fun SettingsSelectRow(
    item: SelectItem,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int,
    onItemChanged: () -> Unit = {},
) {
    var selected by remember(item.key, version) {
        val stored = prefs.getInt(item.key, item.defaultValue)
        mutableStateOf(item.optionValues?.indexOf(stored)?.takeIf { it >= 0 }
            ?: if (item.optionValues == null) stored.coerceIn(0, item.options.lastIndex) else 0)
    }
    DisposableEffect(prefs, item.key, version) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == item.key) {
                val stored = prefs.getInt(item.key, item.defaultValue)
                selected = item.optionValues?.indexOf(stored)?.takeIf { it >= 0 }
                    ?: if (item.optionValues == null) stored.coerceIn(0, item.options.lastIndex) else 0
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var pendingSelection by remember(item.key) { mutableStateOf<Int?>(null) }
    val displayedSelection = pendingSelection ?: selected
    val fontPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            importing = true
            val custom = SystemFontStore.customIndex(item.key)
            pendingSelection = item.optionValues?.indexOf(custom) ?: custom
            val operation = SystemFontStore.enqueue { SystemFontStore.import(ctx.applicationContext, item.key, uri) }
            scope.launch {
                try {
                    operation.await()
                    val custom = SystemFontStore.customIndex(item.key)
                    selected = item.optionValues?.indexOf(custom) ?: custom
                    onItemChanged()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.widget.Toast.makeText(ctx, if (error is SystemFontStore.NotMonospaceFontException) ctx.getString(R.string.ui_font_not_monospace)
                        else ctx.getString(R.string.ui_font_import_failed), android.widget.Toast.LENGTH_LONG).show()
                    AppLog.log("font import failed: $error")
                } finally { importing = false; pendingSelection = null }
            }
        }
    }
    WindowDropdownPreference(
        items = item.options.map { androidx.compose.ui.res.stringResource(it) },
        selectedIndex = displayedSelection,
        title = androidx.compose.ui.res.stringResource(item.label),
        summary = item.subtitle?.let { androidx.compose.ui.res.stringResource(it) },
        summaryColor = settingsSummaryColors(),
        enabled = !importing,
        onSelectedIndexChange = { index ->
            if (SystemFontStore.isFont(item.key)) {
                val value = item.optionValues?.get(index) ?: index
                if (!importing && value == SystemFontStore.customIndex(item.key)) {
                    fontPicker.launch(arrayOf("*/*"))
                } else if (!importing && index != selected) {
                    importing = true
                    pendingSelection = index
                    val operation = SystemFontStore.enqueue { SystemFontStore.select(ctx.applicationContext, item.key, value) }
                    scope.launch {
                        try {
                            operation.await()
                            selected = index
                            onItemChanged()
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_font_import_failed), android.widget.Toast.LENGTH_LONG).show()
                            AppLog.log("font selection failed: $error")
                        } finally { importing = false; pendingSelection = null }
                    }
                }
            } else if (index != selected) {
                selected = index
                setInt(ctx, item.key, item.optionValues?.get(index) ?: index)
                onItemChanged()

            }
        },
    )
}

@Composable
fun SettingsMasterToggle(
    checked: Boolean,
    title: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    // 主开关上方追加的内容(如首页的机型横幅), 与主开关同卡片、以分割线隔开。
    aboveContent: (@Composable ColumnScope.() -> Unit)? = null,
    // 主开关下方追加的内容(如首页的隐藏桌面图标开关), 与主开关同卡片、以分割线隔开。
    belowContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    SettingsCard(modifier = modifier) {
        aboveContent?.invoke(this)
        SettingsSwitchPreference(checked = checked, onCheckedChange = onCheckedChange, title = title, subtitle = subtitle)
        if (belowContent != null) {
            SettingsItemDivider()
            belowContent()
        }
    }
}

@Composable
fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value, onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished, modifier = modifier,
    )
}

// 右上角动作按钮: 点击弹出下拉菜单，选择菜单项后收起菜单并直接执行。
data class ActionMenuItem(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
internal fun SettingsRowActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<ActionMenuItem>,
) {
    WindowDropdownPopup(
        entry = DropdownEntry(items.map { item ->
            DropdownItem(text = item.label, onClick = { onDismiss(); item.onClick() })
        }),
        show = expanded, onDismiss = onDismiss, onDismissFinished = {},
        maxHeight = null, dropdownColors = dropdownColors(),
    )
}

@Composable
fun SettingsActionMenu(
    icon: @Composable () -> Unit,
    items: List<ActionMenuItem>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) { icon() }
        SettingsRowActionMenu(expanded = expanded, onDismiss = { expanded = false }, items = items)
    }
}

@Composable
private fun SettingsSwitchRow(
    item: SwitchItem,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int,
    overrideValue: Boolean?,
    onItemChanged: () -> Unit,
) {
    if (item.key == InstallerSettings.RESTORE) {
        InstallerRestoreRow(ctx, onItemChanged)
        return
    }
    val label = androidx.compose.ui.res.stringResource(item.label)
    val scope = rememberCoroutineScope()

    val rowVersion = if (item.rootBacked) 0 else version
    val rowOverride = if (item.rootBacked) null else overrideValue
    var checked by remember(item.key, rowVersion, rowOverride) {
        mutableStateOf(overrideValue ?: if (item.rootBacked) false else prefs.getBoolean(item.key, item.defaultEnabled))
    }
    var rootValue by remember(item.key) { mutableStateOf<Int?>(null) }
    LaunchedEffect(item.key, overrideValue) {
        if (item.rootBacked) {
            if (overrideValue != null) {
                checked = overrideValue
                rootValue = item.sliderDefault
                return@LaunchedEffect
            }
            val result = withContext(Dispatchers.IO) { readSystemSetting(item.key) }
            if (result != null) {
                checked = result.first
                rootValue = result.second
            }
        }
    }
    if (item.colorKey != null) {
        val colorKey = item.colorKey
        val defaultColor = XposedInit.DEFAULT_QS_ACTIVE_COLOR
        var selected by remember(colorKey, version) { mutableStateOf(Color(prefs.getInt(colorKey, defaultColor))) }
        var hex by remember(colorKey, version) { mutableStateOf(selected.toArgb().toUInt().toString(16).padStart(8, '0').uppercase()) }
        var expanded by remember(item.key) { mutableStateOf(false) }
        Column {
            SettingsSwitchPreference(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    expanded = it
                    setBool(ctx, item.key, it)
                    onItemChanged()
                },
                title = label,
                onTitleClick = if (checked) ({ expanded = !expanded }) else null,
                leftTrailingContent = { if (checked) Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(selected)) },
            )
            AnimatedVisibility(visible = checked && expanded) {
                Column(Modifier.fillMaxWidth().padding(horizontal = SETTINGS_ROW_HPADDING, vertical = SETTINGS_ROW_VPADDING)) {
                    ColorPalette(color = selected, onColorChanged = {
                        selected = it
                        hex = it.toArgb().toUInt().toString(16).padStart(8, '0').uppercase()
                    })
                    TextField(
                        value = hex,
                        onValueChange = { value ->
                            hex = value.removePrefix("#").filter { it in "0123456789abcdefABCDEF" }.take(8).uppercase()
                            if (hex.length == 6 || hex.length == 8) {
                                val argb = hex.toLong(16) or (if (hex.length == 6) 0xff000000L else 0L)
                                selected = Color(argb.toInt())
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        label = "ARGB / RGB",
                        singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = ctx.getString(R.string.ui_cancel), modifier = Modifier.weight(1f), onClick = {
                            selected = Color(prefs.getInt(colorKey, defaultColor))
                            hex = selected.toArgb().toUInt().toString(16).padStart(8, '0').uppercase()
                            expanded = false
                        })
                        TextButton(text = ctx.getString(R.string.ui_confirm), modifier = Modifier.weight(1f), onClick = {
                            if (hex.length == 6 || hex.length == 8) {
                                setInt(ctx, colorKey, selected.toArgb())
                                expanded = false
                                onItemChanged()
                            } else android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_invalid_color), android.widget.Toast.LENGTH_SHORT).show()
                        })
                    }
                }
            }
        }
        return
    }
    if (item.sliderKey == null) {
        val available = item.key != "qs_merged_radiant"
                || (rowOverride ?: prefs.getBoolean("qs_merged_hollow", false))
        SettingsSwitchPreference(
            checked = checked && available,
            enabled = available,
            onCheckedChange = {
                checked = it
                if (!item.rootBacked) setBool(ctx, item.key, it)
                scope.launch(Dispatchers.IO) {
                    applySystemSetting(item.key, it, item.sliderDefault)
                }
                onItemChanged()
            },
            title = label,
            subtitle = item.subtitle?.let { androidx.compose.ui.res.stringResource(it) },
        )
        return
    }
    // 带滑条的设置项: 数值显示在标题行右侧, 滑条默认折叠; 单独开启功能时自动展开。
    var expanded by remember(item.key) { mutableStateOf(false) }
    var intVal by remember(item.sliderKey, item.sliderMin, item.sliderMax, rowVersion, rowOverride) {
        mutableStateOf((rootValue ?: prefs.getInt(item.sliderKey, item.sliderDefault)).coerceIn(item.sliderMin, item.sliderMax))
    }
    LaunchedEffect(rootValue) {
        if (item.rootBacked && rootValue != null) intVal = rootValue!!
    }
    val stepCount = ((item.sliderMax - item.sliderMin) / item.sliderStep).coerceAtLeast(1)
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSwitchPreference(
            checked = checked,
            onCheckedChange = {
                checked = it
                expanded = it
                if (!item.rootBacked) setBool(ctx, item.key, it)
                if (item.rootBacked) rootValue = item.sliderDefault
                if (!it) {
                    intVal = item.sliderDefault
                    if (!item.rootBacked) setInt(ctx, item.sliderKey, item.sliderDefault)
                }
                scope.launch(Dispatchers.IO) {
                    applySystemSetting(item.key, it, item.sliderDefault)
                }
                onItemChanged()
            },
            title = label,
            subtitle = item.subtitle?.let { androidx.compose.ui.res.stringResource(it) },
            onTitleClick = if (checked) {
                { expanded = !expanded }
            } else {
                null
            },
            showDivider = checked,
            leftTrailingContent = {
                if (checked) {
                    Text(
                        text = if (item.sliderDisplayScale == 1f) {
                            "${intVal}${if (item.sliderUnitRes != 0) ctx.getString(item.sliderUnitRes) else item.sliderUnit}"
                        } else {
                            String.format(java.util.Locale.US, if (item.sliderDisplayScale >= 0.1f) "%.1f%s" else "%.2f%s", intVal * item.sliderDisplayScale, if (item.sliderUnitRes != 0) ctx.getString(item.sliderUnitRes) else item.sliderUnit)
                        },
                        style = COUITheme.textStyles.body2.copy(
                            color = settingsSummaryTextColor(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontWeight = if (intVal != item.sliderDefault) FontWeight.Medium else null,
                        ),
                        modifier = Modifier
                            .padding(start = 12.dp),
                    )
                }
            },
        )
        AnimatedVisibility(
            visible = checked && expanded,
            enter = expandVertically(expandFrom = Alignment.Bottom),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SETTINGS_ROW_HPADDING,
                        end = SETTINGS_ROW_HPADDING,
                        bottom = SETTINGS_ROW_VPADDING,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsSlider(
                    value = ((intVal - item.sliderMin).toFloat() /
                            (item.sliderMax - item.sliderMin).coerceAtLeast(1)).coerceIn(0f, 1f),
                    onValueChange = { f ->
                        val nv = (item.sliderMin + (f * stepCount).roundToInt() * item.sliderStep)
                            .coerceIn(item.sliderMin, item.sliderMax)
                        if (nv != intVal) {
                            intVal = nv
                            if (!item.rootBacked) {
                                setInt(ctx, item.sliderKey, nv)
                            }
                        }
                    },
                    onValueChangeFinished = if (item.rootBacked) {
                        {
                            val value = intVal
                            rootValue = value
                            scope.launch(Dispatchers.IO) { applySystemSetting(item.key, true, value) }
                        }
                    } else {
                        {}
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}
