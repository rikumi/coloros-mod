package com.rikumi.colorosmod

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import io.github.suqi8.coui.kmp.theme.COUITheme
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private data class SettingsPageMotion(val from: String?, val to: String?, val back: Boolean)

/** An opaque foreground and a parallax background share the same drag/settle pixel offset. */
@Composable
internal fun SettingsPageHost(
    targetPage: String?,
    onBackCompleted: (String?) -> Unit,
    content: @Composable (String?) -> Unit,
) {
    var displayedPage by remember { mutableStateOf(targetPage) }
    var motion by remember { mutableStateOf<SettingsPageMotion?>(null) }
    var width by remember { mutableIntStateOf(1) }
    val offset = remember { Animatable(0f) }
    val easing = remember { CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f) }

    LaunchedEffect(targetPage) {
        if (displayedPage != targetPage) {
            val from = displayedPage
            offset.snapTo(0f)
            motion = SettingsPageMotion(from, targetPage, targetPage == parentSettingsPage(from))
            offset.animateTo(width.toFloat(), tween(300, easing = easing))
            displayedPage = targetPage
            motion = null
        }
    }

    SettingsPredictiveBackHandler(enabled = targetPage != null) { events ->
        val from = targetPage
        val to = parentSettingsPage(from)
        var startX: Float? = null
        var touchDirection = 1f
        try {
            // Also cancels an unfinished push animation before installing the back layers.
            offset.snapTo(0f)
            displayedPage = from
            motion = SettingsPageMotion(from, to, back = true)
            events.collect { event ->
                if (startX == null) {
                    startX = event.touchX
                    touchDirection = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                }
                val distance = ((event.touchX - startX) * touchDirection).coerceAtLeast(0f)
                // Either edge reveals the lower page on the left.
                offset.snapTo(distance)
            }
            // animateTo starts at the last touch offset, without resetting or seeking a timeline.
            val destination = maxOf(width.toFloat(), offset.value)
            val remaining = abs(destination - offset.value) / width.coerceAtLeast(1)
            offset.animateTo(destination, tween((300 * remaining).roundToInt().coerceIn(1, 300), easing = easing))
            displayedPage = to
            motion = null
            onBackCompleted(to)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                offset.animateTo(0f, tween(200, easing = easing))
                displayedPage = from
                motion = null
            }
            throw cancelled
        }
    }

    val activeMotion = motion
    // The lower page is drawn first. Stable keys keep the foreground composition when dragging starts.
    val pages = if (activeMotion == null) listOf(displayedPage)
        else if (activeMotion.back) listOf(activeMotion.to, activeMotion.from)
        else listOf(activeMotion.from, activeMotion.to)
    val background = COUITheme.colorScheme.surface.copy(alpha = 1f)
    Box(Modifier.fillMaxSize().background(background).clipToBounds().onSizeChanged { width = it.width.coerceAtLeast(1) }) {
        pages.forEach { page ->
            key(page) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val lowerBackPage = activeMotion?.back == true && page == activeMotion.to
                        val distance = abs(offset.value).coerceAtMost(width.toFloat())
                        // Lower page: -width/4 -> 0, moving 1px for every 4px of foreground travel.
                        alpha = if (lowerBackPage) distance / width else 1f
                        translationX = when {
                            activeMotion == null -> 0f
                            lowerBackPage -> (distance - width) / 4f
                            activeMotion.back && page == activeMotion.from -> offset.value
                            !activeMotion.back && page == activeMotion.to -> width - offset.value
                            else -> 0f
                        }
                    }.background(background),
                ) {
                    content(page)
                }
            }
        }
    }
}

/** Include onBackStarted: Activity Compose's progress-only flow omits the initial touch position. */
@Composable
private fun SettingsPredictiveBackHandler(
    enabled: Boolean,
    onBack: suspend (Flow<BackEventCompat>) -> Unit,
) {
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            var channel: Channel<BackEventCompat>? = null
            var job: Job? = null

            fun begin(initial: BackEventCompat? = null): Channel<BackEventCompat> {
                val previousJob = job
                previousJob?.cancel()
                channel?.cancel()
                val next = Channel<BackEventCompat>(Channel.UNLIMITED)
                channel = next
                initial?.let { next.trySend(it) }
                job = scope.launch {
                    previousJob?.join()
                    currentOnBack(next.consumeAsFlow())
                }
                return next
            }

            override fun handleOnBackStarted(backEvent: BackEventCompat) { begin(backEvent) }
            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                channel?.trySend(backEvent)
            }
            override fun handleOnBackPressed() {
                (channel ?: begin()).close()
                channel = null
            }
            override fun handleOnBackCancelled() {
                channel?.cancel()
                channel = null
                job?.cancel()
            }
        }
    }
    SideEffect { callback.isEnabled = enabled }
    DisposableEffect(dispatcher, lifecycle) {
        dispatcher.addCallback(lifecycle, callback)
        onDispose {
            callback.remove()
            callback.handleOnBackCancelled()
        }
    }
}
