package com.rikumi.colorosmod

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.theme.miuixShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/**
 * couix —— 对 miuix 的轻度 ColorOS 化封装。
 * 不直接改动 miuix 库本身，而是用一组包装 Composable 调整尺寸/间距/文字/圆角。
 */

// 开关目标尺寸（miuix 默认 49x28dp -> 缩小到约 40x24dp）。
private val COUIX_SWITCH_W = 40.dp
private val COUIX_SWITCH_H = 24.dp
private val COUIX_SWITCH_RADIUS = 12.dp
private val COUIX_SWITCH_THUMB_R = 8.dp

// ColorOS 风格滑条
private val COUIX_SLIDER_TRACK_H = 20.dp
private val COUIX_SLIDER_GAP = 3.dp
private val COUIX_SLIDER_THUMB_R = 10.dp

// 分组卡片圆角（miuix 默认 16dp -> 12dp）。
private val COUIX_CARD_CORNER = 12.dp

// 分组左右外边距（相对屏幕边缘）。
private val COUIX_GROUP_HMARGIN = 16.dp

// 分割线两端距离 group 边界的内缩。
private val COUIX_DIVIDER_INSET = 16.dp

// 列表项内部水平/垂直内边距。
private val COUIX_ROW_HPADDING = 16.dp
private val COUIX_ROW_VPADDING = 13.dp

// 下拉菜单宽度。
private val COUIX_DROPDOWN_WIDTH = 160.dp
private val COUIX_DROPDOWN_TRANSFORM_ORIGIN = TransformOrigin(0.85f, 0f)

/** 列表项标题采用粗体文字。 */
fun couixTextStyles(): TextStyles {
    val base = defaultTextStyles()
    return base.copy(
        body1 = base.body1.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        body2 = base.body2.copy(fontSize = 14.sp),
    )
}

/**
 * 分组副标题（顶部小标题）：比 miuix SmallTitle 更小、常规字重。
 * miuix SmallTitle 用 subtitle(14sp/Bold) 且不支持外部覆盖字号字重，
 * 这里用 BasicText 直接绘制，颜色跟随 onSurfaceVariantSummary，
 * 左右内缩 28dp、上下 4dp（与分组卡片间距较小）。
 */
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
        modifier = modifier.padding(start = 24.dp, top = 12.dp, bottom = 6.dp),
    )
}

/**
 * 自绘页面大标题：miuix TopAppBar 的大标题用固定 title2 样式（无法外部覆盖字重），
 * 这里用 BasicText 直接绘制，复用 title2 字号/字距、仅加粗，并自行处理状态栏 inset
 * 与右侧 actions（如重启按钮）的布局。
 */
@Composable
fun CouixLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = title,
                style = MiuixTheme.textStyles.title2.copy(
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.weight(1f),
            )
            actions()
        }
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
}

/**
 * 自绘开关：miuix Switch 内部硬编码 49x28 布局（绘制坐标绑定该尺寸），
 * 无法从外部整体缩放且不溢出。这里改用 Canvas 直接按目标尺寸绘制轨道+滑块，
 * 尺寸精确、不溢出，并带滑块位移动画。
 */
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

/**
 * 单行开关偏好：整行可点击切换，右侧为缩小后的 couix 开关。
 * 文字沿用 miuix 主题（已通过 couixTextStyles 全局缩小）。
 */
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
    // 按整行实际高度动态计算分割线高度，使分割线随列表项(含副标题/数值)高度自适应。
    var rowHeightPx by remember { mutableStateOf(0) }
    val dividerHeight: Dp = with(density) {
        (rowHeightPx - 2 * COUIX_ROW_VPADDING.toPx()).coerceAtLeast(0f).toDp()
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowHeightPx = it.size.height }
            .clickable { onCheckedChange(!checked) },
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
fun CouixItemDivider(modifier: Modifier = Modifier) {
    val thickness: Dp = (1f / LocalDensity.current.density).dp
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = COUIX_DIVIDER_INSET, end = COUIX_DIVIDER_INSET)
            .height(thickness)
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.16f)),
    )
}

/**
 * 一个设置分组卡片：圆角更小，item 之间用 1px 分割线（内缩 24dp）隔开。
 * 配合上方 SmallTitle 的较小 insideMargin，分组副标题到卡片的间距也更小。
 * version 用于强制各开关在外部(如"全部开启/关闭")改动后重新读取 prefs; onItemChanged 在
 * 任一项切换时回调, 供顶层刷新主开关状态。
 */
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
    // 取 miuix 默认卡片色，仅略微降低不透明度：既比默认浅层（减弱），
    // 又保留足够不透明度不会比页面背景更暗。
    val baseCard = CardDefaults.defaultColors()
    val weakContainer = baseCard.color.copy(alpha = 0.8f)
    val cardColors: CardColors = baseCard.copy(color = weakContainer)
    Card(
    modifier = modifier.padding(start = COUIX_GROUP_HMARGIN, top = 2.dp, end = COUIX_GROUP_HMARGIN, bottom = 12.dp),
    cornerRadius = COUIX_CARD_CORNER,
    colors = cardColors,
    ) {
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
    val baseCard = CardDefaults.defaultColors()
    val weakContainer = baseCard.color.copy(alpha = 0.8f)
    val cardColors: CardColors = baseCard.copy(color = weakContainer)
    Card(
        modifier = modifier.padding(
            start = COUIX_GROUP_HMARGIN,
            top = 2.dp,
            end = COUIX_GROUP_HMARGIN,
            bottom = 12.dp,
        ),
        cornerRadius = COUIX_CARD_CORNER,
        colors = cardColors,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) CouixItemDivider()
            CouixSelectRow(item = item, prefs = prefs, ctx = ctx, version = version)
        }
    }
}

/**
 * 下拉弹层里的单项：左侧文字（选中态用主题色），右侧细线勾图标；
 * 项与项之间由调用方用 [CouixDropdownDivider] 插入分割线。
 */
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

/**
 * 细线风格勾选图标（对齐 ColorOS 原生下拉）：用两段圆头描边线段绘制，
 * 描边色由调用方传入（选中态主题色），非填充式。
 */
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

/**
 * 顶部"启用模块"主开关卡片：与 CouixGroup 相同样式, 仅一个开关项。
 * checked 与 title 由调用方(基于全部功能是否任一开启)决定; subtitle 非空时紧贴 title 显示,
 * title/subtitle 放在左侧 Column(weight=1f), 右侧为开关, 文字自然水平避让开关不会延伸到其下方。
 */
@Composable
fun CouixMasterToggle(
    checked: Boolean,
    title: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val baseCard = CardDefaults.defaultColors()
    val weakContainer = baseCard.color.copy(alpha = 0.8f)
    val cardColors: CardColors = baseCard.copy(color = weakContainer)
    Card(
        modifier = modifier.padding(
            start = COUIX_GROUP_HMARGIN,
            top = 2.dp,
            end = COUIX_GROUP_HMARGIN,
            bottom = 12.dp,
        ),
        cornerRadius = COUIX_CARD_CORNER,
        colors = cardColors,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
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
    }
}

/**
 * ColorOS 风格自绘滑条: 轨道加高加圆成胶囊容器, 滑块内嵌其中(四周留 2dp 间隙);
 * 激活部分与滑块同宽(高)同圆角, 从轨道内左缘延伸到滑块右缘, 二者连成一个胶囊。
 * 支持点按与水平拖动, value 为 0..1 的比例; 只响应水平手势, 不消费竖直滚动。
 * 拖动时轨道与激活部分为全高(24dp); 未拖动时二者高度动画收缩为 COUIX_SLIDER_TRACK_H_IDLE
 * (滑块尺寸不变, 突出于收缩后的轨道之上)。
 */
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

/**
 * 右上角动作按钮: 点击弹出下拉菜单。
 * 使用 Compose Popup 承载自定义下拉项，保持与设置 group 一致的自然圆角与主题样式。
 * 每个菜单项可附带 confirmTitle/confirmText, 点击后先弹确认框再执行, 用于软重启这类较重操作。
 */
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

/**
 * 极简单确认弹窗(自绘, 用 androidx.compose.ui.window.Dialog 提供遮罩与居中)。
 * 仅用于"软重启"等较重、需二次确认的动作。
 */
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
        mutableStateOf(overrideValue ?: prefs.getBoolean(item.key, item.defaultEnabled))
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
