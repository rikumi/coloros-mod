package com.rikumi.colorosmod

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Compose Fixed line height still adds paragraph-edge padding for tall fonts. Keep
 * BasicText for shaping, accessibility and drawing, but measure the outer line box
 * ourselves. Intrinsic height must follow the same rule: switch rows query it before
 * measuring their children, otherwise the old font-dependent height survives there.
 */
@Composable
internal fun CouixSettingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val linePixels = with(density) { ceil(style.lineHeight.toPx()).toInt().coerceAtLeast(1) }
    val policy = remember(text, style, measurer, linePixels) {
        couixLineMeasurePolicy(text, style, measurer, linePixels)
    }
    Layout(modifier = modifier, content = { BasicText(text = text, style = style) }, measurePolicy = policy)
}

internal fun couixLineMeasurePolicy(text: String, style: TextStyle, measurer: TextMeasurer, linePixels: Int): MeasurePolicy =
        object : MeasurePolicy {
            private fun textLayout(constraints: Constraints) =
                measurer.measure(text, style, constraints = constraints)

            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints) =
                // Let BasicText expose its real edge padding instead of constraining/cropping its layout.
                constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity).let { textConstraints ->
                    val result = textLayout(textConstraints)
                    val child = measurables.single().measure(textConstraints)
                    val height = constraints.constrainHeight(result.lineCount * linePixels)
                    val offset = ((height - child.height) / 2f).roundToInt()
                    layout(child.width, height, mapOf(
                        FirstBaseline to (result.firstBaseline.roundToInt() + offset),
                        LastBaseline to (result.lastBaseline.roundToInt() + offset),
                    )) { child.placeRelative(0, offset) }
                }

            override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
                measurables.single().minIntrinsicWidth(height)
            override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
                measurables.single().maxIntrinsicWidth(height)
            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
                textLayout(Constraints(maxWidth = width.coerceAtLeast(0))).lineCount * linePixels
            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
                minIntrinsicHeight(measurables, width)
        }
