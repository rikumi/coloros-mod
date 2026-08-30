package com.rikumi.colorosmod

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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.window.Dialog
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import top.yukonga.miuix.kmp.theme.miuixShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ln
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

// couix —— 对 miuix 的轻度 ColorOS 化封装。不直接改动 miuix 库本身,
// 而是用一组包装 Composable 调整尺寸/间距/文字/圆角。

// 开关目标尺寸（miuix 默认 49x28dp -> 缩小到约 36x22dp）。
private val COUIX_SWITCH_W = 36.dp
private val COUIX_SWITCH_H = 22.dp
private val COUIX_SWITCH_RADIUS = 12.dp
private val COUIX_SWITCH_THUMB_R = 8.dp

// ColorOS 风格滑条
private val COUIX_SLIDER_TRACK_H = 20.dp
private val COUIX_SLIDER_GAP = 3.dp
private val COUIX_SLIDER_THUMB_R = 10.dp

// 首页顶部机型横幅: 作为第一张 group 卡片的内容, 底色与渐变中心色随明暗主题取不同值。
private val COUIX_HEADER_HEIGHT = 200.dp
private val COUIX_HEADER_BASE_DARK = Color(0xFF34383B)
private val COUIX_HEADER_GLOW_DARK = Color(0xFFB17666)
private val COUIX_HEADER_BASE_LIGHT = Color(0xFFDDE2E8)
private val COUIX_HEADER_GLOW_LIGHT = Color(0xFFDD7963)
private const val COUIX_HEADER_GLOW_RADIUS_RATIO = 0.7f
// 渐变圆心在横幅底边之下(>1 表示沉到底边外), 使可见范围内只剩光晕上部, 越往上越淡。
private const val COUIX_HEADER_GLOW_CENTER_Y_RATIO = 1f
// 椭圆的横向/纵向半径: 分别以横幅宽、高为基准。radialGradient 本身是正圆, 绘制时纵向压缩
// canvas 得到椭圆(绘制矩形已按压缩比反向放大, 渐变半径处 alpha 已归零, 故纵向可放心压小)。
private const val COUIX_HEADER_GLOW_RY_RATIO = 0.85f
// 中心再叠一个更扁的椭圆提亮紧邻底边的一带: 横向半径相对大椭圆略收(让亮带向中心收拢),
// 纵向略微抬高, 形成一小片贴底的亮区而非一条细线。
private const val COUIX_HEADER_CORE_RADIUS_RATIO = 8f
private const val COUIX_HEADER_CORE_RY_RATIO = 0.34f
private const val COUIX_HEADER_CORE_ALPHA = 0.5f
// 渐变衰减曲线: 由若干 stop 逼近 log 曲线 alpha(t) = 1 - ln(1 + k·t)/ln(1 + k)(t 为归一化半径)。
// 相比线性衰减, 中心附近掉得快、外圈留一条长尾; k 越大越陡, k→0 退化为线性。
private const val COUIX_HEADER_GLOW_LOG_K = 12f
private const val COUIX_HEADER_GLOW_STOP_COUNT = 32
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

// 右上角下拉菜单的阴影: 抬升值取对话框级别(Material 的菜单通常只有 3~8dp), 扩散范围随抬升
// 单调增大, 菜单本身面积小, 这个值已经是一片明显偏大的投影。环境光/主光源两层都压成纯黑,
// 暗色主题下也能看出菜单轮廓。
private val COUIX_DROPDOWN_ELEVATION = 12.dp

// 标题栏底部分割线: 界面上滑时出现, 初始两端各内缩 16dp, 随滚动量在该距离内逐渐延长至通栏。
private val TOP_BAR_DIVIDER_INSET = 16.dp
private val TOP_BAR_DIVIDER_EXTEND_SCROLL = 48.dp

// 子页面返回按钮图标尺寸: miuix 图标固有尺寸为 24dp, 此处略微放大。
internal val COUIX_BACK_ICON = 26.dp

// 标题栏(首页大标题 / 子页面标题)共用尺寸: 垂直内边距、右侧内缩、标题字号三者一致,
// 保证两种页面的标题栏高度完全相同。两侧都放有 40dp 的 IconButton(重启菜单),
// 因此行高由它决定, 标题再高也不会撑开。
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

/** 列表项标题采用粗体文字。 */
fun couixTextStyles(): TextStyles {
    val base = defaultTextStyles()
    return base.copy(
        body1 = base.body1.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        body2 = base.body2.copy(fontSize = 14.sp, lineHeight = COUIX_SUBTITLE_LINE_HEIGHT),
    )
}

// 副标题(body2)行高: 略高于默认行距, 长副标题折行后行与行之间不至于挤在一起。
private val COUIX_SUBTITLE_LINE_HEIGHT = 20.sp

// 分组副标题（顶部小标题）：比 miuix SmallTitle 更小、常规字重。
// miuix SmallTitle 用 subtitle(14sp/Bold) 且不支持外部覆盖字号字重，故用 BasicText 直接绘制。
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
            fontWeight = FontWeight.Bold,
        ),
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 6.dp),
    )
}

/** 标题栏标题文字样式: 首页与子页面完全一致(title3 加粗)。 */
@Composable
private fun couixTopBarTitleStyle(): TextStyle =
    MiuixTheme.textStyles.title3.copy(
        color = MiuixTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )

// 标题栏外框: 首页大标题与子页面标题共用, 统一状态栏 inset、垂直内边距与底部分割线,
// 从而保证两种页面的标题栏高度完全相同。startPadding 因首页无返回按钮而不同, 但只影响水平位置。
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

// 自绘页面大标题: miuix TopAppBar 的大标题用固定样式(无法外部覆盖字重), 这里用 BasicText 直接
// 绘制, 仅加粗, 并自行处理状态栏 inset 与右侧 actions 的布局。高度与字号与 CouixTopAppBar 一致。
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

// 依据列表滚动状态计算标题栏分割线进度:
// 首项仍在屏幕内时按真实滚动偏移延长, 首项离开后保持通栏。
// overscrollOffset 不为空时, 一并参考回弹位移(上滑为负), 让不可滚动页面在上滑回弹时也显示分割线。
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

/**
 * 状态栏跟随 miuix 主题: 背景取主题 surface(与 miuix Scaffold 的底色一致), 图标按背景明暗
 * 在深/浅之间切换。需在 `MiuixTheme` 内调用。
 *
 * AppCompat 的 DayNight 主题**没有**定义 `android:windowLightStatusBar`(已核对 appcompat
 * 1.6.1 全部 values-* 资源), 该属性默认为 false, 日间模式下状态栏图标依然是白色, 故在此显式跟随。
 */
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

// 惯性撞边时位移的渐近上限: 常规速度下位移远达不到它, 只在极端速度下才起作用,
// 免得一次猛甩把列表拉出去太远 —— 见 couixOverscrollFlingStep。
private val COUIX_OVERSCROLL_FLING_MAX = 50.dp

// 惯性撞边的减速阻尼: 单帧位移 = 该帧的惯性位移 × DRAG_FLING。惯性在衰减, 每帧的惯性位移
// 逐帧变小, 推出的量同步变小 —— 撞边瞬间即开始减速, 位移随惯性一起走完整个减速过程,
// 到速度耗尽时停在当下拉到的位置, 不存在"推到某个固定位置才停"。
private const val COUIX_OVERSCROLL_DRAG_FLING = 0.7f

// 拉动阻尼系数: 手指移动 1px 内容移动这么多, 全程恒定, 与已拉出的距离无关
// (1 = 完全跟手)。越小越"沉", 但无论拉多远手感都不变。
private const val COUIX_OVERSCROLL_DRAG = 0.7f

// 松手回弹: 不过冲(DampingRatioNoBouncy), 刚度明显低于系统 MediumLow(400), 回弹过程更慢更柔和。
private const val COUIX_RELEASE_STIFFNESS = 120f

/**
 * 关闭系统自带的过度滚动, 让 [Modifier.couixOverscroll] 完全接管边界回弹。
 *
 * 系统 EdgeEffect(Android 12+ 的内容拉伸)有两个问题: 阻尼几乎 1:1 且无法调; 惯性滑动撞到
 * 边界后它会一直保持拉伸, 直到惯性速度完全衰减才释放, 表现为"停住很久才弹回"。
 * 将 [LocalOverscrollFactory] 置空后 `rememberOverscrollEffect()` 返回 null, 边界上剩余的
 * 手势增量就会全部流到 [Modifier.couixOverscroll] 的 connection 里。
 */
@Composable
fun CouixOverscrollHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides null) { content() }
}

/**
 * 让列表在上滑/下滑到边界时回弹, 并接管回弹的阻尼与回弹动画。
 *
 * 列表吃不掉的手势增量一律按固定比例(见 COUIX_OVERSCROLL_DRAG)折算成整列位移, 松手后由
 * 本函数自己的弹簧弹回。需配合 [CouixOverscrollHost] 使用: 系统 overscroll 会抢先吃掉边界
 * 增量并自己回弹, 不关掉的话这里拿不到量(内容可滚动时尤甚)。
 */
/**
 * @param overscrollOffset 可选: 跨组件共享的当前回弹位移(px, 上滑为负)。传入后,
 *   顶部标题栏分割线进度可一并参考该位移, 让不可滚动页面在上滑回弹时也显示分割线。
 */
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
            // 已有位移时, 反向拖动先 1:1 原路返回; 归零后剩余增量交还列表(内容不可滚动时无剩余)。
            // delta 与屏幕坐标同向: 上滑为负、下滑为正, 与 offsetPx 同号。
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

            // 列表吃不到的增量转成回弹位移: 只看拖动方向上是否还有内容可滚,
            // 到顶继续下拉 / 到底继续上滑都算到边(内容不足一屏时两个 can* 均为 false, 恒接管)。
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
                    // 惯性撞边: 按剩余速度折算位移 —— 惯性每衰减一点, 这一帧推出的量就跟着
                    // 变小, 于是"撞边立刻开始减速", 而不是等推到某个位置才停。
                    val step = couixOverscrollFlingStep(delta, flingPx, offsetPx)
                    offsetPx += step
                    // 惯性速度耗尽(单帧已推不动)时报告"未消费": DefaultFlingBehavior 只要有
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

            // 惯性滑动撞到边界后残留的增量同样会被 onPostScroll 吃成位移,
            // 滑行结束时这里负责把它弹回(可能与 onPreFling 的回弹重复调用, 由 startRelease 去重)。
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                startRelease()
                return Velocity.Zero
            }

            /** 位移回零: 已在回弹中则忽略, 否则从当前位移起弹。 */
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

/**
 * 手指拖动的回弹位移推进: 线性阻尼。手指的位移按固定比例折算成内容位移, 系数恒为 DRAG,
 *
 *     offset' = offset + delta * DRAG
 *
 * 与已拉出的距离无关 —— 拉到哪儿都是同一份"沉", 不会出现越拉越费力的橡皮筋感。
 * 代价是必须给一个终点: 到 maxPx 即停。
 */
private fun couixOverscrollStep(current: Float, delta: Float, maxPx: Float): Float {
    if (maxPx <= 0f) return 0f
    return (current + delta * COUIX_OVERSCROLL_DRAG).coerceIn(-maxPx, maxPx)
}

/**
 * 惯性撞边的位移推进: 位移完全由惯性当前这一帧的位移 [delta] 驱动 —— 惯性在减速, 每帧推出的
 * 量同步变小, 所以撞边瞬间就开始减速, 到惯性速度耗尽时位移也恰好停在当时推到的位置并回弹,
 * 全程不需要"推到固定位置才停"的判定。
 *
 * 剩余行程系数(与 [capPx] 的差距成正比)只在位移接近上限时才起作用, 用于兜住极端速度;
 * 常规速度下位移远不到 capPx, 该系数恒为 1, 位移只由惯性决定。
 */
private fun couixOverscrollFlingStep(delta: Float, capPx: Float, current: Float = 0f): Float {
    if (capPx <= 0f) return 0f
    val remaining = (capPx - abs(current)).coerceIn(0f, capPx)
    return delta * COUIX_OVERSCROLL_DRAG_FLING * (remaining / capPx)
}

/** 标题栏底部分割线: 随 [progress] 从两端内缩逐渐延长至通栏。 */
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

// 子页面标题栏: 左侧返回按钮 + 小标题, 右侧 actions(如重启菜单),
// 底部同样带随列表滚动延长的分割线。
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val baseCard = CardDefaults.defaultColors()
    val cardColors: CardColors = baseCard.copy(color = baseCard.color.copy(alpha = 0.8f))
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

// 首页分类入口行: 左侧线条图标(与标题同色, 无底色), 中间标题, 右侧前进箭头。
// 配置了 subtitle 时不占第二行, 而是显示在前进箭头左侧、右对齐。
@Composable
internal fun CouixCategoryRow(
    icon: ImageVector,
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = onSurface,
            modifier = Modifier.size(COUIX_CATEGORY_ICON),
        )
        BasicText(
            text = title,
            style = MiuixTheme.textStyles.body1.copy(color = onSurface),
            modifier = Modifier
                .weight(1f)
                .padding(start = COUIX_CATEGORY_ICON_GAP),
        )
        if (subtitle != null) {
            BasicText(
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

/** 按 log 曲线采样出渐变的 stop 表: 第 i 个 stop 位于 t = i/N, alpha 为 log 衰减值 × [alpha]。 */
private fun glowStops(color: Color, alpha: Float): Array<Pair<Float, Color>> {
    val k = COUIX_HEADER_GLOW_LOG_K.toDouble()
    val denom = ln(1.0 + k)
    return Array(COUIX_HEADER_GLOW_STOP_COUNT + 1) { i ->
        val t = i.toFloat() / COUIX_HEADER_GLOW_STOP_COUNT
        val falloff = (1.0 - ln(1.0 + k * t) / denom).toFloat()
        t to color.copy(alpha = alpha * falloff)
    }
}

/** 首页第一张 group 卡片顶部的机型横幅: 底色上叠两层同心椭圆光晕(圆心沉在底边之下),
 *  外层为压扁的椭圆放射, 内层再叠一个更扁的椭圆把紧邻底边的一带提亮。
 *  设备名粗体居中(再上移一点), 系统名贴底, system 未取到时只显示设备名。
 *  shape 由调用方给出: 嵌在卡片顶部时只需裁顶部两角, 单独成卡片时才四角全裁。
 *  亮色主题下底色/渐变色另取一套, 文字色随之切换(见下方 light 常量)。 */
@Composable
internal fun CouixDeviceHeader(
    model: String,
    system: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(COUIX_CARD_CORNER),
) {
    // miuix 未直接暴露当前明暗, 用系统配置判定(主题为 ColorSchemeMode.System, 两者一致)。
    val light = !isSystemInDarkTheme()
    val baseColor = if (light) COUIX_HEADER_BASE_LIGHT else COUIX_HEADER_BASE_DARK
    val glowColor = if (light) COUIX_HEADER_GLOW_LIGHT else COUIX_HEADER_GLOW_DARK
    // 亮底上白字不可读, 故文字色跟着主题走: 暗底用纯白, 亮底用 onSurface。
    val textColor = if (light) MiuixTheme.colorScheme.onSurface else Color.White
    // 两层光晕共用同一条 log 衰减曲线, 只有整体 alpha 不同; 按颜色缓存避免每次重组重算。
    val outerStops = remember(glowColor) { glowStops(glowColor, 1f) }
    val coreStops = remember(glowColor) { glowStops(glowColor, COUIX_HEADER_CORE_ALPHA) }
    // 长按时连续轻微短振动: 按住超过阈值后反复触发一次 VIRTUAL_KEY tick(与系统虚拟按键同款,
    // 跟随系统"触摸反馈"开关), 间隔由 BANNER_TICK_INTERVAL_MS 控制, 松手即停。
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
            .clip(shape)
            .then(longPressModifier)
            .drawBehind {
                drawRect(color = baseColor)
                val cx = size.width / 2f
                val cy = size.height * COUIX_HEADER_GLOW_CENTER_Y_RATIO
                // 画一层以 (cx, cy) 为心、横/纵半径分别为 radiusX/radiusY 的椭圆光晕。
                // radialGradient 只会画正圆, 故按 k = radiusY/radiusX 纵向压缩 canvas 得到椭圆。
                // 注意压缩同时会缩小所绘矩形: 若按 size 绘制, 矩形纵向只覆盖 [cy-k*cy, cy+k*(h-cy)],
                // 渐变在该边界处 alpha 尚未归零, 会露出一条硬边(看起来就是被裁切的圆)。
                // 因此把矩形的纵向范围按 1/k 反向放大, 使其压缩后正好铺满整张横幅。
                fun drawGlowEllipse(
                    radiusX: Float,
                    radiusY: Float,
                    stops: Array<Pair<Float, Color>>,
                ) {
                    val k = radiusY / radiusX
                    scale(scaleX = 1f, scaleY = k, pivot = Offset(cx, cy)) {
                        val top = cy + (0f - cy) / k
                        val bottom = cy + (size.height - cy) / k
                        drawRect(
                            brush = Brush.radialGradient(
                                colorStops = stops,
                                center = Offset(cx, cy),
                                radius = radiusX,
                            ),
                            topLeft = Offset(0f, top),
                            size = Size(size.width, bottom - top),
                        )
                    }
                }
                drawGlowEllipse(
                    radiusX = size.width * COUIX_HEADER_GLOW_RADIUS_RATIO,
                    radiusY = size.height * COUIX_HEADER_GLOW_RY_RATIO,
                    stops = outerStops,
                )
                drawGlowEllipse(
                    radiusX = size.width * COUIX_HEADER_CORE_RADIUS_RATIO,
                    radiusY = size.height * COUIX_HEADER_CORE_RY_RATIO,
                    stops = coreStops,
                )
            },
    ) {
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
                    fontWeight = FontWeight.ExtraBold,
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
                BasicText(
                    text = system,
                    style = MiuixTheme.textStyles.body1.copy(
                        color = textColor.copy(alpha = COUIX_HEADER_SYSTEM_ALPHA),
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

// 两个等宽可点击格横向并排, 中间以 1px 竖线分隔(纵向内缩与列表项一致)。
// 无图标、无开关, 只放标题, 用于并排摆放一次性动作(如启动 KernelSU / LSPosed)。
// 与 CouixCategoryRow 同高, 标题各自在半区内左对齐。
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
            BasicText(
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

/**
 * 开关切换时的短振动反馈: 与系统虚拟按键同款 tick, 跟随系统"触摸反馈"开关, 不额外申请权限。
 */
@Composable
private fun couixSwitchTick(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}

// 自绘开关: miuix Switch 内部硬编码 49x28 布局(绘制坐标绑定该尺寸), 无法从外部整体缩放且不溢出。
// 这里改用 Canvas 直接按目标尺寸绘制轨道+滑块, 尺寸精确、不溢出, 并带滑块位移动画。
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

// 单行开关偏好: 整行可点击切换, 右侧为缩小后的 couix 开关。
// 文字沿用 miuix 主题(已通过 couixTextStyles 全局缩小)。
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
            .clickable {
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
                        BasicText(
                            text = title,
                            style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                        )
                        if (subtitle != null) {
                            BasicText(
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

/** 物理 1px 分割线（按当前 density 折算），两端内缩 COUIX_DIVIDER_INSET。 */
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

// 一个设置分组卡片：圆角更小，item 之间用 1px 分割线（内缩 24dp）隔开。
// version 用于强制各开关在外部(如"全部开启/关闭")改动后重新读取 prefs; onItemChanged 在
// 任一项切换时回调, 供顶层刷新主开关状态。
@Composable
internal fun CouixGroup(
    items: List<SwitchItem>,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int = 0,
    overrideValue: Boolean? = null,
    onItemChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    CouixCard(modifier = modifier) {
        items.forEachIndexed { index, item ->
            if (index > 0) CouixItemDivider()
            CouixSwitchRow(item = item, prefs = prefs, ctx = ctx, version = version, overrideValue = overrideValue, onItemChanged = onItemChanged)
        }
    }
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

// 下拉弹层里的单项: 左侧文字(选中态用主题色), 右侧细线勾图标;
// 项与项之间由调用方用 CouixDropdownDivider 插入分割线。
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
        BasicText(
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

// 细线风格勾选图标(对齐 ColorOS 原生下拉): 用两段圆头描边线段绘制,
// 描边色由调用方传入(选中态主题色), 非填充式。
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

/** 下拉弹层内的 1px 细分割线（ColorOS 原生下拉风格）。 */
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

/**
 * 折叠行右侧的下拉指示箭头（细线风格，与勾图标一致）：用两段圆头描边线段绘制成下指 chevron。
 */
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
        drawLine(color, p1, p2, strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, p2, p3, strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun CouixSelectRow(
    item: SelectItem,
    prefs: SharedPreferences,
    ctx: Context,
    version: Int,
) {
    var selected by remember(item.key, version) {
        mutableStateOf(prefs.getInt(item.key, item.defaultValue).coerceIn(0, item.options.lastIndex))
    }
    var expanded by remember { mutableStateOf(false) }
    var popupVisible by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded) {
            delay(220)
            popupVisible = false
        }
    }
    val options = item.options
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
                .padding(horizontal = COUIX_ROW_HPADDING, vertical = COUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = item.label,
                style = MiuixTheme.textStyles.body1.copy(color = onSurface),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = options.getOrElse(selected) { "" },
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
                            selected = index == selected,
                        ) {
                            expanded = false
                            if (index != selected) {
                                selected = index
                                setInt(ctx, item.key, index)
                            }
                        }
                    }
                }
            }
        }
    }
}
}

// 顶部"启用模块"主开关卡片: 与 CouixGroup 相同样式, 仅一个开关项。checked 与 title 由调用方
// (基于全部功能是否任一开启)决定; subtitle 非空时紧贴 title 显示。title/subtitle 放在左侧
// Column(weight=1f), 右侧为开关, 文字自然水平避让开关而不会延伸到其下方。
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
            CouixItemDivider()
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
                BasicText(
                    text = title,
                    style = MiuixTheme.textStyles.body1.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                )
                if (subtitle != null) {
                    BasicText(
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

// ColorOS 风格自绘滑条: 轨道加高加圆成胶囊容器, 滑块内嵌其中(四周留 2dp 间隙); 激活部分与
// 滑块同宽同圆角, 从轨道内左缘延伸到滑块右缘, 二者连成一个胶囊。只响应水平手势, 不消费竖直滚动。
// 拖动时轨道为全高(24dp), 未拖动时高度动画收缩为 COUIX_SLIDER_TRACK_H_IDLE(滑块尺寸不变)。
@Composable
fun CouixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
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
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { },
                    onDragEnd = { },
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

// 右上角动作按钮: 点击弹出下拉菜单。用 Compose Popup 承载自定义下拉项, 保持与设置 group 一致的样式。
// 每个菜单项可附带 confirmTitle/confirmText, 点击后先弹确认框再执行, 用于软重启这类较重操作。
data class ActionMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val confirmTitle: String? = null,
    val confirmText: String? = null,
)

@Composable
fun CouixActionMenu(
    icon: @Composable () -> Unit,
    items: List<ActionMenuItem>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var popupVisible by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<ActionMenuItem?>(null) }
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

        // 菜单锚定到屏幕左上角(0,0)，宽度铺满全屏并右对齐，使菜单从最右上角开始布局：
        // 向下移动 24dp、向左 12dp 微调，使其恰好落在图标下方而非压住图标，
        // 再次点击右上角图标时菜单顶部就位于图标处，可快速重复触发重启作用域。
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
                                    if (item.confirmTitle != null) pendingConfirm = item else item.onClick()
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    if (pendingConfirm != null) {
        CouixConfirmDialog(
            title = pendingConfirm!!.confirmTitle ?: "",
            text = pendingConfirm!!.confirmText ?: "",
            onConfirm = {
                val action = pendingConfirm!!.onClick
                pendingConfirm = null
                action()
            },
            onDismiss = { pendingConfirm = null },
        )
    }
}

// 极简单确认弹窗(自绘, 用 androidx.compose.ui.window.Dialog 提供遮罩与居中)。
// 仅用于"软重启"等较重、需二次确认的动作。
@Composable
fun CouixConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            cornerRadius = COUIX_CARD_CORNER,
            colors = CardDefaults.defaultColors(),
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .padding(horizontal = 8.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                BasicText(
                    text = title,
                    style = MiuixTheme.textStyles.body1.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                BasicText(
                    text = text,
                    style = MiuixTheme.textStyles.body2.copy(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        BasicText(
                            text = dismissLabel,
                            style = MiuixTheme.textStyles.body2.copy(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            ),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clickable { onConfirm() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        BasicText(
                            text = confirmLabel,
                            style = MiuixTheme.textStyles.body2.copy(
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
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
    // overrideValue 非空时优先显示覆盖值(主开关动画期间), 否则读 prefs;
    // version/overrideValue 变化时重新计算, 其余时刻用本地状态即时切换。
    var checked by remember(item.key, version, overrideValue) {
        mutableStateOf(overrideValue ?: prefs.getBoolean(item.key, false))
    }
    if (item.sliderKey == null) {
        CouixSwitchPreference(
            checked = checked,
            onCheckedChange = {
                checked = it
                setBool(ctx, item.key, it)
                onItemChanged()
            },
            title = item.label,
            subtitle = item.subtitle,
        )
        return
    }
    // 带滑条的设置项: 数值显示在标题行右侧, 滑条默认折叠; 单独开启功能时自动展开。
    var expanded by remember(item.key) { mutableStateOf(false) }
    var intVal by remember(item.sliderKey, item.sliderMin, item.sliderMax, version, overrideValue) {
        mutableStateOf(prefs.getInt(item.sliderKey, item.sliderDefault).coerceIn(item.sliderMin, item.sliderMax))
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        CouixSwitchPreference(
            checked = checked,
            onCheckedChange = {
                checked = it
                expanded = it
                setBool(ctx, item.key, it)
                if (!it) {
                    intVal = item.sliderDefault
                    setInt(ctx, item.sliderKey, item.sliderDefault)
                }
                onItemChanged()
            },
            title = item.label,
            subtitle = item.subtitle,
            onTitleClick = if (checked) {
                { expanded = !expanded }
            } else {
                null
            },
            leftTrailingContent = {
                if (checked) {
                    BasicText(
                        text = "${intVal}${item.sliderUnit}",
                        style = MiuixTheme.textStyles.body2.copy(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontWeight = if (intVal != item.sliderDefault) FontWeight.Bold else null,
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
                        val nv = (item.sliderMin + f * (item.sliderMax - item.sliderMin))
                            .roundToInt().coerceIn(item.sliderMin, item.sliderMax)
                        if (nv != intVal) {
                            intVal = nv
                            setInt(ctx, item.sliderKey, nv)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
