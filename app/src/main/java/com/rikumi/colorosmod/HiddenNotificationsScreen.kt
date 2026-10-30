package com.rikumi.colorosmod

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HiddenNotificationsScreen(ctx: Context, onBack: () -> Unit, showBack: Boolean = true) {
    val state by HiddenNotificationsService.state.collectAsState()
    var hasAccess by remember { mutableStateOf(HiddenNotificationsService.hasAccess(ctx)) }
    val listState = rememberLazyListState()
    val overscroll = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, ctx) {
        fun refresh() {
            hasAccess = HiddenNotificationsService.hasAccess(ctx)
            HiddenNotificationsService.refresh(ctx)
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val hidden = remember(state.entries) { state.entries.filter { it.hidden } }
    val visible = remember(state.entries) { state.entries.filterNot { it.hidden } }
    Scaffold(topBar = {
        CouixTopAppBar(
            title = ctx.getString(R.string.ui_category_hidden_notifications),
            dividerProgress = couixTopBarDividerProgress(listState, overscroll),
            navigationIcon = if (showBack) {
                {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, ctx.getString(R.string.ui_back),
                            tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(COUIX_BACK_ICON))
                    }
                }
            } else null,
            actions = {
                IconButton(onClick = {
                    hasAccess = HiddenNotificationsService.hasAccess(ctx)
                    HiddenNotificationsService.refresh(ctx)
                }) {
                    Icon(MiuixIcons.Refresh, ctx.getString(R.string.hidden_notifications_refresh),
                        tint = MiuixTheme.colorScheme.onSurface)
                }
            },
        )
    }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)
            .couixOverscroll(listState, overscroll)) {
            if (!hasAccess || !state.connected) {
                item {
                    CouixSmallTitle(ctx.getString(if (hasAccess) R.string.hidden_notifications_connecting
                        else R.string.hidden_notifications_permission_hint))
                }
                item {
                    CouixCard {
                        CouixCategoryRow(null, ctx.getString(R.string.hidden_notifications_permission),
                            onClick = { openNotificationAccess(ctx) })
                    }
                }
            }
            state.error?.let { message ->
                item { CouixSmallTitle(ctx.getString(message)) }
            }
            if (hasAccess && state.connected) {
                listOf(
                    Triple(R.string.hidden_notifications_hidden, R.string.hidden_notifications_no_hidden, hidden),
                    Triple(R.string.hidden_notifications_visible, R.string.hidden_notifications_no_visible, visible),
                ).forEach { (title, empty, entries) ->
                    item(key = title) { CouixSmallTitle(ctx.getString(title)) }
                    when {
                        !state.loaded -> item { CouixSmallTitle(ctx.getString(R.string.ui_loading)) }
                        entries.isEmpty() -> item { CouixSmallTitle(ctx.getString(empty)) }
                        else -> itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                            CouixCardRow(first = index == 0, last = index == entries.lastIndex) {
                                if (index > 0) CouixItemDivider()
                                HiddenNotificationRow(ctx, entry, enabled = state.busyKey == null,
                                    busy = state.busyKey == entry.key,
                                    onClick = { HiddenNotificationsService.toggle(entry) })
                            }
                        }
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HiddenNotificationRow(
    ctx: Context,
    entry: HiddenNotificationEntry,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val action = ctx.getString(if (entry.hidden) R.string.hidden_notifications_restore else R.string.hidden_notifications_hide)
    val detailStyle = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 16.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Row(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = action, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        entry.icon?.let {
            Image(it.asImageBitmap(), contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
            Box(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(entry.title.ifBlank { entry.appName },
                style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(if (entry.appName == entry.packageName) entry.packageName
                else "${entry.appName} · ${entry.packageName}", style = detailStyle,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (entry.text.isNotBlank()) BasicText(entry.text, style = detailStyle,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        BasicText(ctx.getString(when {
            busy -> R.string.hidden_notifications_processing
            entry.hidden -> R.string.hidden_notifications_state_hidden
            else -> R.string.hidden_notifications_state_visible
        }),
            style = detailStyle, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun openNotificationAccess(ctx: Context) {
    val detail = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                HiddenNotificationsService.component(ctx).flattenToString())
    } else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    try {
        ctx.startActivity(detail)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.hidden_notifications_settings_failed, Toast.LENGTH_LONG).show()
        }
    }
}
