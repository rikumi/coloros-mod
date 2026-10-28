package com.rikumi.colorosmod

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.TextButton
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import com.rikumi.colorosmod.hooks.AboutCardStyleResolver
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalOverscrollFactory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import top.yukonga.miuix.kmp.theme.miuixShape
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

private val COUIX_SWITCH_W = 36.dp
private val COUIX_SWITCH_H = 22.dp
private val COUIX_SWITCH_RADIUS = 12.dp
private val COUIX_SWITCH_THUMB_R = 8.dp

// ColorOS 风格滑条
private val COUIX_SLIDER_TRACK_H = 20.dp
private val COUIX_SLIDER_GAP = 3.dp
private val COUIX_SLIDER_THUMB_R = 10.dp

// 首页机型横幅与关于本机卡片共享原厂背景素材。
private val COUIX_HEADER_HEIGHT = 200.dp
// 横幅内系统名: 相对文字色的不透明度, 以及贴底时离底边的距离。
private const val COUIX_HEADER_SYSTEM_ALPHA = 0.9f
private val COUIX_HEADER_SYSTEM_BOTTOM = 24.dp
// 设备名自横幅正中再上移的距离。
private val COUIX_HEADER_MODEL_LIFT = 16.dp

// 横幅长按时触发连续振动的阈值(ms): 按住超过阈值后开始连续细微震动, 松手即停。
private const val BANNER_LONG_PRESS_MS = 350L
// 长按期间震动间隔(ms): 越小越频繁。
private const val BANNER_TICK_INTERVAL_MS = 35L
// 单次震动时长(ms)与振幅(0~255): 极短 + 低振幅 = 更细微的连续震感。
private const val BANNER_VIBRATE_MS = 8L
private const val BANNER_VIBRATE_AMPLITUDE = 100

// 分组卡片圆角（miuix 默认 16dp -> 12dp）。首页机型横幅嵌在卡片顶部时需按此值裁顶部两角。
internal val COUIX_CARD_CORNER = 12.dp

// 分组左右外边距（相对屏幕边缘）。
private val COUIX_GROUP_HMARGIN = 16.dp

// 卡片下方留白 = 相邻 section 之间的间距（首页卡片→卡片、子页面卡片→下一节小标题）。
private val COUIX_CARD_BOTTOM_GAP = 16.dp

// 分割线两端距离 group 边界的内缩。
private val COUIX_DIVIDER_INSET = 16.dp

// 列表项内部水平/垂直内边距。
private val COUIX_ROW_HPADDING = 16.dp
private val COUIX_ROW_VPADDING = 13.dp

// 下拉菜单宽度。
private val COUIX_DROPDOWN_WIDTH = 160.dp
private val COUIX_DROPDOWN_TRANSFORM_ORIGIN = TransformOrigin(0.85f, 0f)

private val COUIX_DROPDOWN_ELEVATION = 12.dp

// 标题栏底部分割线: 界面上滑时出现, 初始两端各内缩 16dp, 随滚动量在该距离内逐渐延长至通栏。
private val TOP_BAR_DIVIDER_INSET = 16.dp
private val TOP_BAR_DIVIDER_EXTEND_SCROLL = 48.dp

// 子页面返回按钮图标尺寸: miuix 图标固有尺寸为 24dp, 此处略微放大。
internal val COUIX_BACK_ICON = 26.dp

private val COUIX_TOP_BAR_VPADDING = 8.dp
private val COUIX_TOP_BAR_END = 16.dp

// 子页面: 返回按钮左侧内缩, 以及返回按钮与标题之间的间距。
private val COUIX_TOP_BAR_START = 8.dp
private val COUIX_TOP_BAR_TITLE_GAP = 8.dp

// 首页: 没有返回按钮, 标题自身按此值内缩(只影响水平位置, 不影响高度)。
private val COUIX_LARGE_TITLE_START = 24.dp

// 分类入口行: 图标尺寸及图标与标题的间距（无底色容器，图标直接绘制）。
private val COUIX_CATEGORY_ICON = 22.dp
private val COUIX_CATEGORY_ICON_GAP = 14.dp

// 分类入口行右侧"进入子菜单"箭头尺寸: 明显小于左侧分类图标, 只作指示不抢视觉。
private val COUIX_CATEGORY_CHEVRON = 16.dp

// 该箭头在 onSurfaceVariantSummary 之上再压低不透明度, 进一步弱化。
private const val COUIX_CATEGORY_CHEVRON_ALPHA = 0.6f

// 分类入口行右侧副标题与标题/箭头之间的间隔。
private val COUIX_CATEGORY_SUBTITLE_GAP = 12.dp

// 分类入口行标题左边缘相对卡片左缘的距离: 带图标 item 的分割线从此处起，避开图标。
internal val COUIX_CATEGORY_TEXT_START = COUIX_ROW_HPADDING + COUIX_CATEGORY_ICON + COUIX_CATEGORY_ICON_GAP

// Read metrics from firmware files, independent of the selected/replaced system typeface.
private val couixDefaultTypefaces: List<Typeface> by lazy {
    runCatching {
        (SystemOppoFonts.find(false) + SystemOppoFonts.find(true)).map {
            Typeface.Builder(it).setWeight(500).build()
        }
    }.getOrDefault(emptyList())
}

private fun defaultFontLineHeight(size: Float, fallback: Float) =
    (couixDefaultTypefaces.maxOfOrNull { font ->
        val metrics = Paint().apply { typeface = font; textSize = size }.fontMetrics
        kotlin.math.ceil((metrics.descent - metrics.ascent + metrics.leading).toDouble()).toFloat()
    } ?: fallback).sp

private val couixTitleLineHeight by lazy { defaultFontLineHeight(16f, 24f) }
private val couixSubtitleLineHeight by lazy { defaultFontLineHeight(14f, 20f) }

fun couixTextStyles(): TextStyles {
    val base = defaultTextStyles()
    val fixedLines = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
        mode = LineHeightStyle.Mode.Fixed,
    )
    val platform = PlatformTextStyle(includeFontPadding = false)
    return base.copy(
        body1 = base.body1.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium,
            lineHeight = couixTitleLineHeight, platformStyle = platform, lineHeightStyle = fixedLines),
        body2 = base.body2.copy(fontSize = 14.sp,
            lineHeight = couixSubtitleLineHeight, platformStyle = platform, lineHeightStyle = fixedLines),
    )
}


@Composable
fun CouixSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        style = androidx.compose.ui.text.TextStyle(
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun couixTopBarTitleStyle(): TextStyle =
    MiuixTheme.textStyles.title3.copy(
        color = MiuixTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium,
    )

@Composable
private fun CouixTopBarFrame(
    startPadding: Dp,
    modifier: Modifier = Modifier,
    dividerProgress: Float = 0f,
    content: @Composable RowScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    start = startPadding,
                    end = COUIX_TOP_BAR_END,
                    top = COUIX_TOP_BAR_VPADDING,
                    bottom = COUIX_TOP_BAR_VPADDING,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
        CouixTopBarDivider(progress = dividerProgress)
    }
}

@Composable
fun CouixLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dividerProgress: Float = 0f,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CouixTopBarFrame(
        startPadding = COUIX_LARGE_TITLE_START,
        modifier = modifier,
        dividerProgress = dividerProgress,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = title, style = couixTopBarTitleStyle())
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = TextStyle(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
        actions()
    }
}

@Composable
internal fun couixTopBarDividerProgress(
    listState: LazyListState,
    overscrollOffset: MutableState<Float>? = null,
): Float {
    val density = LocalDensity.current
    val extendPx = with(density) { TOP_BAR_DIVIDER_EXTEND_SCROLL.toPx() }
    return remember(listState, density) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                val scrollPx = listState.firstVisibleItemScrollOffset.toFloat()
                // 上滑回弹时 offsetPx < 0, 取绝对值作为额外的"已上移距离"。
                val ov = overscrollOffset?.value ?: 0f
                val total = if (ov < 0f) scrollPx - ov else scrollPx
                (total / extendPx).coerceIn(0f, 1f)
            }
        }
    }.value
}

@Composable
fun CouixStatusBar() {
    val view = LocalView.current
    val surface = MiuixTheme.colorScheme.surface
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

// 回弹位移的上限: 手指拖动拉到该距离后停住(线性阻尼下必须有一个终点, 否则可无限拖动)。
private val COUIX_OVERSCROLL_MAX = 400.dp

private val COUIX_OVERSCROLL_FLING_MAX = 50.dp

private const val COUIX_OVERSCROLL_DRAG_FLING = 0.7f

private const val COUIX_OVERSCROLL_DRAG = 0.7f

// 松手回弹: 不过冲(DampingRatioNoBouncy), 刚度明显低于系统 MediumLow(400), 回弹过程更慢更柔和。
private const val COUIX_RELEASE_STIFFNESS = 120f

@Composable
fun CouixOverscrollHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides null) { content() }
}

@Composable
fun Modifier.couixOverscroll(
    listState: LazyListState,
    overscrollOffset: MutableState<Float>? = null,
): Modifier {
    val density = LocalDensity.current
    val maxPx = with(density) { COUIX_OVERSCROLL_MAX.toPx() }
    val flingPx = with(density) { COUIX_OVERSCROLL_FLING_MAX.toPx() }
    val scope = rememberCoroutineScope()
    val offsetState = overscrollOffset ?: remember { mutableFloatStateOf(0f) }
    var offsetPx by offsetState
    var releaseJob by remember { mutableStateOf<Job?>(null) }

    val connection = remember(maxPx, flingPx, scope) {
        object : NestedScrollConnection {

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (offsetPx == 0f || delta == 0f) return Offset.Zero
                if (delta * offsetPx > 0f) return Offset.Zero
                releaseJob?.cancel()
                releaseJob = null
                val next = if (offsetPx > 0f) {
                    (offsetPx + delta).coerceAtLeast(0f)
                } else {
                    (offsetPx + delta).coerceAtMost(0f)
                }
                val consumed = next - offsetPx
                offsetPx = next
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val delta = available.y
                if (delta == 0f) return Offset.Zero
                val atEdge = if (delta > 0f) {
                    !listState.canScrollBackward
                } else {
                    !listState.canScrollForward
                }
                if (!atEdge) return Offset.Zero
                releaseJob?.cancel()
                releaseJob = null
                if (source != NestedScrollSource.UserInput) {

                    val step = couixOverscrollFlingStep(delta, flingPx, offsetPx)
                    offsetPx += step

                    // 半像素没吃完就立刻取消惯性动画, 随后 onPostFling 起弹簧回弹。
                    if (abs(step) < 0.5f) return Offset.Zero
                    return Offset(0f, delta)
                }
                offsetPx = couixOverscrollStep(offsetPx, delta, maxPx)
                return Offset(0f, delta)
            }

            // 松手: 吃掉全部速度, 位移弹回 0。
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offsetPx == 0f) return Velocity.Zero
                releaseJob?.cancel()
                releaseJob = null
                startRelease()
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                startRelease()
                return Velocity.Zero
            }

            private fun startRelease() {
                if (offsetPx == 0f || releaseJob != null) return
                releaseJob = scope.launch {
                    animate(
                        offsetPx,
                        0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = COUIX_RELEASE_STIFFNESS,
                        ),
                    ) { value, _ -> offsetPx = value }
                }
            }
        }
    }

    return this
        // 位移后裁掉溢出部分, 避免上滑时内容盖住标题栏。
        .clipToBounds()
        .drawWithContent { translate(top = offsetPx) { this@drawWithContent.drawContent() } }
        .nestedScroll(connection)
}

private fun couixOverscrollStep(current: Float, delta: Float, maxPx: Float): Float {
    if (maxPx <= 0f) return 0f
    return (current + delta * COUIX_OVERSCROLL_DRAG).coerceIn(-maxPx, maxPx)
}

private fun couixOverscrollFlingStep(delta: Float, capPx: Float, current: Float = 0f): Float {
    if (capPx <= 0f) return 0f
    val remaining = (capPx - abs(current)).coerceIn(0f, capPx)
    return delta * COUIX_OVERSCROLL_DRAG_FLING * (remaining / capPx)
}

@Composable
internal fun BoxScope.CouixTopBarDivider(progress: Float) {
    val density = LocalDensity.current
    if (progress > 0f) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = TOP_BAR_DIVIDER_INSET * (1f - progress))
                .height((1f / density.density).dp)
                .background(Color.White.copy(alpha = 0.26f * progress)),
        )
    }
}

@Composable
fun CouixTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    dividerProgress: Float = 0f,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    CouixTopBarFrame(
        startPadding = COUIX_TOP_BAR_START,
        modifier = modifier,
        dividerProgress = dividerProgress,
    ) {
        navigationIcon()
        BasicText(
            text = title,
            style = couixTopBarTitleStyle(),
            modifier = Modifier
                .weight(1f)
                .padding(start = COUIX_TOP_BAR_TITLE_GAP),
        )
        actions()
    }
}

// 分组卡片容器: 与 CouixGroup 同款(更小圆角、略弱底色), 供首页分类列表等自定义内容复用。
@Composable
internal fun CouixCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val baseCard = CardDefaults.defaultColors()
    val cardColors: CardColors = baseCard.copy(color = backgroundColor ?: baseCard.color.copy(alpha = 0.8f))
    Card(
        modifier = modifier.padding(
            start = COUIX_GROUP_HMARGIN,
            top = 2.dp,
            end = COUIX_GROUP_HMARGIN,
            bottom = COUIX_CARD_BOTTOM_GAP,
        ),
        cornerRadius = COUIX_CARD_CORNER,
        colors = cardColors,
        content = content,
    )
}

@Composable
internal fun CouixCardRow(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val baseCard = CardDefaults.defaultColors()
    val cardColor = baseCard.color.copy(alpha = 0.8f)
    val shape = when {
        first && last -> RoundedCornerShape(COUIX_CARD_CORNER)
        first -> RoundedCornerShape(
            topStart = COUIX_CARD_CORNER,
            topEnd = COUIX_CARD_CORNER,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        )
        last -> RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = COUIX_CARD_CORNER,
            bottomEnd = COUIX_CARD_CORNER,
        )
        else -> RectangleShape
    }
    Column(
        modifier = modifier
            .padding(
                start = COUIX_GROUP_HMARGIN,
                end = COUIX_GROUP_HMARGIN,
                top = if (first) 2.dp else 0.dp,
                bottom = if (last) COUIX_CARD_BOTTOM_GAP else 0.dp,
            )
            .background(cardColor, shape)
            // 只有带圆角的首尾行需要裁剪(水波纹不溢出圆角); 中间行是矩形, 免掉一层。
            .then(if (first || last) Modifier.clip(shape) else Modifier),
        content = content,
    )
}

@Composable
internal fun CouixCategoryRow(
    icon: ImageVector?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = COUIX_ROW_HPADDING, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = onSurface,
                modifier = Modifier.size(COUIX_CATEGORY_ICON),
            )
        }
        CouixSettingText(
            text = title,
            style = MiuixTheme.textStyles.body1.copy(color = onSurface),
            modifier = Modifier
                .weight(1f)
                .padding(start = if (icon != null) COUIX_CATEGORY_ICON_GAP else 0.dp),
        )
        if (subtitle != null) {
            CouixSettingText(
                text = subtitle,
                style = MiuixTheme.textStyles.body2.copy(
                    color = summary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                ),
                modifier = Modifier.padding(start = COUIX_CATEGORY_SUBTITLE_GAP),
            )
        }
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = summary.copy(alpha = summary.alpha * COUIX_CATEGORY_CHEVRON_ALPHA),
            modifier = Modifier
                // 仅当右侧有副标题时才需要间隔，其余行的箭头紧贴标题区右缘。
                .padding(start = if (subtitle != null) COUIX_CATEGORY_SUBTITLE_GAP else 0.dp, end = 0.dp)
                .size(COUIX_CATEGORY_CHEVRON),
        )
    }
}

@Composable
internal fun CouixDeviceHeader(
    backgroundStyle: Int?,
    model: String,
    system: String?,
    contentReady: Boolean = true,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(COUIX_CARD_CORNER),
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
            .height(COUIX_HEADER_HEIGHT)
            .onGloballyPositioned { coordinates ->
                if (!ready) placement = coordinates.boundsInWindow()
            }
            .drawWithContent { if (ready) drawContent() }
            .clip(shape)
            .then(longPressModifier),
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // 设备名在横幅正中, 再上移 COUIX_HEADER_MODEL_LIFT。
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = -COUIX_HEADER_MODEL_LIFT),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = model,
                style = MiuixTheme.textStyles.title1.copy(
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
                    .padding(bottom = COUIX_HEADER_SYSTEM_BOTTOM),
                contentAlignment = Alignment.BottomCenter,
            ) {
                CouixSettingText(
                    text = system,
                    style = MiuixTheme.textStyles.body1.copy(
                        color = textColor.copy(alpha = COUIX_HEADER_SYSTEM_ALPHA),
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun CouixActionPairRow(
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
        CouixActionCell(
            title = leftTitle,
            onClick = onLeftClick,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = COUIX_ROW_VPADDING)
                .width(dividerWidth)
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)),
        )
        CouixActionCell(
            title = rightTitle,
            onClick = onRightClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CouixActionCell(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable { onClick() }
            .padding(horizontal = COUIX_ROW_HPADDING, vertical = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CouixSettingText(
                text = title,
                style = MiuixTheme.textStyles.body1.copy(
                    color = MiuixTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.weight(1f),
            )
            // 与分类入口行同一枚前进箭头(同尺寸、同压低后的不透明度)。
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = summary.copy(alpha = summary.alpha * COUIX_CATEGORY_CHEVRON_ALPHA),
                modifier = Modifier.size(COUIX_CATEGORY_CHEVRON),
            )
        }
    }
}

@Composable
private fun couixSwitchTick(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}

@Composable
fun CouixSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    val trackOff = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
    val thumbTarget: Float by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        label = "couix_switch_thumb",
    )
    Canvas(modifier = modifier.requiredSize(COUIX_SWITCH_W, COUIX_SWITCH_H)) {
        val w = size.width
        val h = size.height
        val r = COUIX_SWITCH_RADIUS.toPx()
        drawRoundRect(
            color = if (checked) primary else trackOff,
            cornerRadius = CornerRadius(r, r),
        )
        val thumbR = COUIX_SWITCH_THUMB_R.toPx()
        val padY = (h - 2 * thumbR) / 2f
        val padX = padY
        val onX = w - padX - thumbR
        val offX = padX + thumbR
        val cx = offX + (onX - offX) * thumbTarget
        drawCircle(
            color = Color.White,
            radius = thumbR,
            center = Offset(cx, h / 2f),
        )
    }
}

@Composable
fun CouixSwitchPreference(
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
    val density = LocalDensity.current
    val tick = couixSwitchTick()
    // 按整行实际高度动态计算分割线高度，使分割线随列表项(含副标题/数值)高度自适应。
    var rowHeightPx by remember { mutableStateOf(0) }
    val dividerHeight: Dp = with(density) {
        (rowHeightPx - 2 * COUIX_ROW_VPADDING.toPx()).coerceAtLeast(0f).toDp()
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowHeightPx = it.size.height }
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clickable(enabled = enabled) {
                tick()
                onCheckedChange(!checked)
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (onTitleClick != null) {
                            Modifier.clickable { onTitleClick() }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .padding(
                            start = COUIX_ROW_HPADDING,
                            top = COUIX_ROW_VPADDING,
                            bottom = COUIX_ROW_VPADDING,
                            end = 12.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        CouixSettingText(
                            text = title,
                            style = MiuixTheme.textStyles.body1.copy(
                                color = MiuixTheme.colorScheme.onSurface,
                            ),
                        )
                        if (subtitle != null) {
                            CouixSettingText(
                                text = subtitle,
                                style = MiuixTheme.textStyles.body2.copy(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                ),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    leftTrailingContent()
                }
            }
            if (showDivider) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .width((1f / density.density).dp)
                        .height(dividerHeight)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)),
                )
            }
            Row(
                modifier = Modifier.padding(
                    start = if (showDivider) 12.dp else 0.dp,
                    top = COUIX_ROW_VPADDING,
                    bottom = COUIX_ROW_VPADDING,
                    end = COUIX_ROW_HPADDING,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CouixSwitch(
                    checked = checked,
                )
            }
        }
    }
}

@Composable
fun CouixItemDivider(modifier: Modifier = Modifier, startInset: Dp = COUIX_DIVIDER_INSET) {
    val thickness: Dp = (1f / LocalDensity.current.density).dp
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startInset, end = COUIX_DIVIDER_INSET)
            .height(thickness)
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.16f)),
    )
}

@Composable
internal fun CouixGroup(
    items: List<SettingsItem>,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int = 0,
    overrideValue: Boolean? = null,
    onItemChanged: () -> Unit = {},
    onOpenNavigationApps: () -> Unit = {},
    modifier: Modifier = Modifier,
    footerContent: (@Composable () -> Unit)? = null,
) {
    CouixCard(modifier = modifier) {
        items.forEachIndexed { index, item ->
            if (index > 0) CouixItemDivider()
            androidx.compose.runtime.key(item.keyForCompose) {
                when (item) {
                    is SwitchItem -> {
                        CouixSwitchRow(item = item, prefs = prefs, ctx = ctx, version = version,
                            overrideValue = overrideValue.takeUnless { it == true && !item.masterEnableAllowed },
                            onItemChanged = onItemChanged)
                    }
                    is FolderBlockItem -> CouixFolderBlockRow(item = item, prefs = prefs, ctx = ctx, version = version, overrideValue = overrideValue, onItemChanged = onItemChanged)
                    is SelectItem -> {
                        CouixSelectRow(item = item, prefs = prefs, ctx = ctx, version = version,
                            onItemChanged = onItemChanged)
                        if (item.key == XposedInit.KEY_NOTIFICATION_LAYOUT_STYLE) {
                            CouixItemDivider()
                            NotificationLayoutTestRow(ctx)
                        }
                    }
                    is GroupTitleItem -> Unit
                    is NavigationAppsItem -> CouixCategoryRow(
                        icon = null,
                        title = ctx.getString(item.title),
                        onClick = onOpenNavigationApps,
                    )
                }
            }
        }
        if (footerContent != null) {
            if (items.isNotEmpty()) CouixItemDivider()
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
    }

@Composable
private fun CouixFolderBlockRow(
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
    CouixSwitchPreference(
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
internal fun CouixSelectGroup(
    items: List<SelectItem>,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int = 0,
    modifier: Modifier = Modifier,
) {
    CouixCard(modifier = modifier) {
        items.forEachIndexed { index, item ->
            if (index > 0) CouixItemDivider()
            CouixSelectRow(item = item, prefs = prefs, ctx = ctx, version = version)
        }
    }
}

@Composable
private fun CouixDropdownItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CouixSettingText(
            text = text,
            style = MiuixTheme.textStyles.body1.copy(
                color = if (selected) primary else onSurface,
            ),
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            CouixCheckMark(color = primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CouixCheckMark(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = minOf(w, h) * 0.11f
        val p1 = Offset(w * 0.20f, h * 0.52f)
        val p2 = Offset(w * 0.42f, h * 0.73f)
        val p3 = Offset(w * 0.80f, h * 0.27f)
        drawLine(color, p1, p2, strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, p2, p3, strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun CouixDropdownDivider() {
    val density = LocalDensity.current
    val dividerH = (1f / density.density).dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .height(dividerH)
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)),
    )
}

@Composable
private fun CouixDropdownArrow(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = maxOf(w, h) * 0.09f
        val p1 = Offset(w * 0.18f, h * 0.36f)
        val p2 = Offset(w * 0.5f, h * 0.68f)
        val p3 = Offset(w * 0.82f, h * 0.36f)
        val path = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
        }
        drawPath(path, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
    CouixActionCell(
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
private fun CouixSelectRow(
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
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { SystemFontStore.import(ctx, item.key, uri) }
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
    var expanded by remember { mutableStateOf(false) }
    var popupVisible by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded) {
            delay(220)
            popupVisible = false
        }
    }
    val options = item.options.map { androidx.compose.ui.res.stringResource(it) }
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary

    var anchorHeightPx by remember { mutableStateOf(0) }
    val dropdownShape = miuixShape(COUIX_CARD_CORNER)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchorHeightPx = it.size.height },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (expanded) {
                        expanded = false
                    } else {
                        popupVisible = true
                        expanded = true
                    }
                }
                .padding(horizontal = COUIX_ROW_HPADDING, vertical = COUIX_ROW_VPADDING)
                .heightIn(min = COUIX_SWITCH_H),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                CouixSettingText(
                    text = androidx.compose.ui.res.stringResource(item.label),
                    style = MiuixTheme.textStyles.body1.copy(color = onSurface),
                )
                item.subtitle?.let { subtitle ->
                    CouixSettingText(
                        text = androidx.compose.ui.res.stringResource(subtitle),
                        style = MiuixTheme.textStyles.body2.copy(color = summary),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            CouixSettingText(
                text = options.getOrElse(displayedSelection) { "" },
                style = MiuixTheme.textStyles.body2.copy(color = summary),
            )
            Spacer(modifier = Modifier.width(6.dp))
            CouixDropdownArrow(color = summary, modifier = Modifier.size(18.dp))
        }

        if (popupVisible) Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, anchorHeightPx),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
        ) {
            Box(modifier = Modifier.width(COUIX_DROPDOWN_WIDTH)) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = scaleIn(
                    initialScale = 0.8f,
                    transformOrigin = COUIX_DROPDOWN_TRANSFORM_ORIGIN,
                ) + fadeIn(),
                exit = scaleOut(
                    targetScale = 0.8f,
                    transformOrigin = COUIX_DROPDOWN_TRANSFORM_ORIGIN,
                ) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .width(COUIX_DROPDOWN_WIDTH)
                        .background(MiuixTheme.colorScheme.surfaceContainer, dropdownShape)
                        .clip(dropdownShape),
                ) {
                    options.forEachIndexed { index, opt ->
                        if (index > 0) CouixDropdownDivider()
                        CouixDropdownItem(
                            text = opt,
                            selected = index == displayedSelection,
                        ) {
                            expanded = false
                            if (SystemFontStore.isFont(item.key)) {
                                val value = item.optionValues?.get(index) ?: index
                                if (!importing && value == SystemFontStore.customIndex(item.key)) {
                                    fontPicker.launch(arrayOf("*/*"))
                                } else if (!importing && index != selected) {
                                    importing = true
                                    pendingSelection = index
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { SystemFontStore.select(ctx, item.key, value) }
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
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
fun CouixMasterToggle(
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
    val tick = couixSwitchTick()
    CouixCard(modifier = modifier) {
        if (aboveContent != null) {
            aboveContent()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    tick()
                    onCheckedChange(!checked)
                }
                .padding(horizontal = COUIX_ROW_HPADDING, vertical = COUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                CouixSettingText(
                    text = title,
                    style = MiuixTheme.textStyles.body1.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                )
                if (subtitle != null) {
                    CouixSettingText(
                        text = subtitle,
                        style = MiuixTheme.textStyles.body2.copy(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            CouixSwitch(
                checked = checked,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (belowContent != null) {
            CouixItemDivider()
            belowContent()
        }
    }
}

@Composable
fun CouixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val trackOff = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)
    val v = value.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(COUIX_SLIDER_TRACK_H)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val range = size.width.coerceAtLeast(1)
                onValueChange((offset.x / range).coerceIn(0f, 1f))
                onValueChangeFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { },
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { },
                ) { change, _ ->
                    change.consume()
                    val range = size.width.coerceAtLeast(1)
                    onValueChange((change.position.x / range).coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val gap = COUIX_SLIDER_GAP.toPx()
            val thumbR = COUIX_SLIDER_THUMB_R.toPx()
            val cy = size.height / 2f
            // 轨道恒为全高(已去除 idle 收缩态); barR 为半高兼作圆角与延伸量
            val barH = COUIX_SLIDER_TRACK_H.toPx()
            val barR = barH / 2f
            // thumb 中心按比例走全宽, 不为半径留白
            val cx = size.width * v
            // 轨道(胶囊背景): 向两侧延伸 barR 包裹 thumb(两端时 thumb 半径超出可见区)
            drawRoundRect(
                color = trackOff,
                topLeft = Offset(-barR, cy - barR),
                size = Size(size.width + 2 * barR, barH),
                cornerRadius = CornerRadius(barR, barR),
            )
            // 激活部分: 左端与轨道对齐(-barR), 右端到 thumb 右缘(cx + barR), 包裹 thumb
            drawRoundRect(
                color = primary,
                topLeft = Offset(-barR, cy - barR),
                size = Size(cx + 2 * barR, barH),
                cornerRadius = CornerRadius(barR, barR),
            )
            drawCircle(color = onSurface, radius = thumbR - gap, center = Offset(cx, cy))
        }
    }
}

// 右上角动作按钮: 点击弹出下拉菜单，选择菜单项后收起菜单并直接执行。
data class ActionMenuItem(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
internal fun CouixRowActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorHeight: Int,
    items: List<ActionMenuItem>,
) {
    var popupVisible by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            popupVisible = true
            delay(16)
            revealed = true
        } else {
            revealed = false
            delay(220)
            popupVisible = false
        }
    }
    val shape = miuixShape(COUIX_CARD_CORNER)
    if (popupVisible) Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, anchorHeight),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(Modifier.width(COUIX_DROPDOWN_WIDTH)) {
            AnimatedVisibility(
                visible = revealed,
                enter = scaleIn(initialScale = 0.8f, transformOrigin = COUIX_DROPDOWN_TRANSFORM_ORIGIN) + fadeIn(),
                exit = scaleOut(targetScale = 0.8f, transformOrigin = COUIX_DROPDOWN_TRANSFORM_ORIGIN) + fadeOut(),
            ) {
                Column(Modifier.width(COUIX_DROPDOWN_WIDTH)
                    .shadow(COUIX_DROPDOWN_ELEVATION, shape)
                    .background(MiuixTheme.colorScheme.surfaceContainer, shape).clip(shape)) {
                    items.forEachIndexed { index, item ->
                        if (index > 0) CouixDropdownDivider()
                        CouixDropdownItem(item.label, selected = false) {
                            if (expanded) {
                                onDismiss()
                                item.onClick()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CouixActionMenu(
    icon: @Composable () -> Unit,
    items: List<ActionMenuItem>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var popupVisible by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded) {
            delay(220)
            popupVisible = false
        }
    }
    // Popup 挂载后(expanded 初始为 false)再置 true, 让 AnimatedVisibility 播放进场动画
    LaunchedEffect(popupVisible) {
        if (popupVisible) {
            delay(16)
            expanded = true
        }
    }

    val dropdownShape = miuixShape(COUIX_CARD_CORNER)

    Box(
        modifier = modifier,
    ) {
        IconButton(
            onClick = {
                if (expanded) {
                    expanded = false
                } else {
                    popupVisible = true
                }
            },
        ) { icon() }

        if (popupVisible) Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(0, 0),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
        ) {
            // 全屏根容器: 透明遮罩捕获菜单外点击以收起菜单, 菜单本身靠右上角对齐
            Box(modifier = Modifier.fillMaxSize()) {
                // 透明全屏遮罩, 点击外部收起菜单
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = false },
                )
                // 菜单容器: 右上角对齐并留出间距, 点击菜单项不触发遮罩收起
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 16.dp, top = 16.dp),
                ) {
                    Box(modifier = Modifier.width(COUIX_DROPDOWN_WIDTH)) {
                        AnimatedVisibility(
                            visible = expanded,
                            enter = scaleIn(
                            initialScale = 0.8f,
                            transformOrigin = COUIX_DROPDOWN_TRANSFORM_ORIGIN,
                            animationSpec = tween(durationMillis = 120),
                        ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                        exit = scaleOut(
                            targetScale = 0.8f,
                            transformOrigin = COUIX_DROPDOWN_TRANSFORM_ORIGIN,
                            animationSpec = tween(durationMillis = 120),
                        ) + fadeOut(animationSpec = tween(durationMillis = 120)),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(COUIX_DROPDOWN_WIDTH)
                                // 阴影画在自身边界之外, 故 clip = false; 内容裁剪仍由下面的 .clip() 负责。
                                .shadow(
                                    elevation = COUIX_DROPDOWN_ELEVATION,
                                    shape = dropdownShape,
                                    clip = false,
                                    ambientColor = Color.Black,
                                    spotColor = Color.Black,
                                )
                                .background(MiuixTheme.colorScheme.surfaceContainer, dropdownShape)
                                .clip(dropdownShape),
                        ) {
                            items.forEachIndexed { index, item ->
                                if (index > 0) CouixDropdownDivider()
                                CouixDropdownItem(
                                    text = item.label,
                                    selected = false,
                                ) {
                                    expanded = false
                                    item.onClick()
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun CouixSwitchRow(
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
            CouixSwitchPreference(
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
                Column(Modifier.fillMaxWidth().padding(horizontal = COUIX_ROW_HPADDING, vertical = COUIX_ROW_VPADDING)) {
                    ColorPalette(color = selected, onColorChanged = {
                        selected = it
                        hex = it.toArgb().toUInt().toString(16).padStart(8, '0').uppercase()
                    })
                    top.yukonga.miuix.kmp.basic.TextField(
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
        CouixSwitchPreference(
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
        CouixSwitchPreference(
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
            leftTrailingContent = {
                if (checked) {
                    CouixSettingText(
                        text = if (item.sliderDisplayScale == 1f) {
                            "${intVal}${if (item.sliderUnitRes != 0) ctx.getString(item.sliderUnitRes) else item.sliderUnit}"
                        } else {
                            String.format(java.util.Locale.US, if (item.sliderDisplayScale >= 0.1f) "%.1f%s" else "%.2f%s", intVal * item.sliderDisplayScale, if (item.sliderUnitRes != 0) ctx.getString(item.sliderUnitRes) else item.sliderUnit)
                        },
                        style = MiuixTheme.textStyles.body2.copy(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontWeight = if (intVal != item.sliderDefault) FontWeight.Medium else null,
                        ),
                        modifier = Modifier
                            .padding(start = 12.dp),
                    )
                }
            },
            showDivider = checked,
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
                        start = COUIX_ROW_HPADDING,
                        end = COUIX_ROW_HPADDING,
                        bottom = COUIX_ROW_VPADDING,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CouixSlider(
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
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
