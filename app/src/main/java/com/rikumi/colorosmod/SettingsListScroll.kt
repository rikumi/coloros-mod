package com.rikumi.colorosmod

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import kotlin.math.min

internal class SettingsListScroll(val list: LazyListState, val appBar: ScrollBehavior) {
    var overscroll by mutableFloatStateOf(0f)
    var viewport: LayoutCoordinates? = null
    var content: LayoutCoordinates? = null

    fun updatePosition() {
        val outer = viewport?.takeIf { it.isAttached } ?: return
        val inner = content?.takeIf { it.isAttached } ?: return
        // Observe COUI's actual translated child, including spring frames after release.
        overscroll = outer.localPositionOf(inner, Offset.Zero).y
    }
}

@Composable
internal fun rememberSettingsListScroll(list: LazyListState): SettingsListScroll {
    val behavior = COUIScrollBehavior()
    val scroll = remember(list, behavior) { SettingsListScroll(list, behavior) }
    LaunchedEffect(scroll) {
        snapshotFlow {
            // Once the first item is gone, the divider is already fully extended.
            val distance = if (list.firstVisibleItemIndex > 0) Float.MAX_VALUE
                else list.firstVisibleItemScrollOffset.toFloat()
            distance - min(scroll.overscroll, 0f)
        }.collect { behavior.state.contentOffset = -it }
    }
    return scroll
}

/** Apply COUI's nested-scroll elasticity even when LazyColumn has no scrollable range. */
@Composable
internal fun SettingsLazyColumn(
    scroll: SettingsListScroll,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned {
            scroll.viewport = it
            scroll.updatePosition()
        }) {
            Box(Modifier.fillMaxSize().overScrollVertical()) {
                LazyColumn(
                    state = scroll.list,
                    // The explicit COUI modifier owns overscroll; avoid applying it twice.
                    overscrollEffect = null,
                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                        scroll.content = it
                        scroll.updatePosition()
                    },
                    content = content,
                )
            }
        }
    }
}
