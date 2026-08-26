package com.rikumi.colorosmod

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
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
private val COUIX_SLIDER_TRACK_H = 24.dp
private val COUIX_SLIDER_TRACK_H_IDLE = 8.dp
private val COUIX_SLIDER_GAP = 4.dp
private val COUIX_SLIDER_THUMB_R = 12.dp

// 分组卡片圆角（miuix 默认 16dp -> 12dp）。
private val COUIX_CARD_CORNER = 12.dp

// 分组左右外边距（相对屏幕边缘）。
private val COUIX_GROUP_HMARGIN = 16.dp

// 分割线两端距离 group 边界的内缩。
private val COUIX_DIVIDER_INSET = 16.dp

// 列表项内部水平/垂直内边距。
private val COUIX_ROW_HPADDING = 16.dp
private val COUIX_ROW_VPADDING = 13.dp

/** 列表项标题采用粗体文字。 */
fun couixTextStyles(): TextStyles {
    val base = defaultTextStyles()
    return base.copy(
        body1 = base.body1.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        body2 = base.body2.copy(fontSize = 13.sp),
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
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = COUIX_ROW_HPADDING, vertical = COUIX_ROW_VPADDING),
    ) {
        Column(
            modifier = Modifier
                .padding(end = 64.dp)
                .align(Alignment.CenterStart),
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
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        CouixSwitch(
            checked = checked,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
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
    var dragging by remember { mutableStateOf(false) }
    // 高度动画: 1 = 拖动中全高, 0 = 空闲收缩
    val sizeAnim by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        label = "couix_slider_size",
    )
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
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
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
            val idleH = COUIX_SLIDER_TRACK_H_IDLE.toPx()
            val cy = size.height / 2f
            // 轨道与激活部分同高, 随拖动动画从 idleH 到全高; barR 为半高兼作圆角与延伸量
            val barH = idleH + (COUIX_SLIDER_TRACK_H.toPx() - idleH) * sizeAnim
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
    // 带滑条的设置项: 开关行 + 开关打开时下方显示滑条(整数步进, 右侧显示当前值)。
    // AnimatedVisibility 让 item 高度随开关动画; 滑条以 item 底部为锚点(expandFrom/shrinkTowards
    // = Bottom), 打开时自底部向上滑入展开, 关闭时向底部收起滑出。
    Column(modifier = Modifier.fillMaxWidth()) {
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
        AnimatedVisibility(
            visible = checked,
            enter = expandVertically(expandFrom = Alignment.Bottom),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            var intVal by remember(item.sliderKey, item.sliderMax) {
                mutableStateOf(prefs.getInt(item.sliderKey, item.sliderDefault).coerceIn(0, item.sliderMax))
            }
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
                    value = intVal.toFloat() / item.sliderMax,
                    onValueChange = { f ->
                        val nv = (f * item.sliderMax).roundToInt().coerceIn(0, item.sliderMax)
                        if (nv != intVal) {
                            intVal = nv
                            setInt(ctx, item.sliderKey, nv)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                )
                // 数值文字占固定宽度槽位(按最大值宽度预留), 避免其宽度变化挤压滑条
                BasicText(
                    text = "${intVal}${item.sliderUnit}",
                    style = MiuixTheme.textStyles.body2.copy(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    ),
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .width(40.dp),
                )
            }
        }
    }
}
