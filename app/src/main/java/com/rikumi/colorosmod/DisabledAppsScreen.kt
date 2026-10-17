package com.rikumi.colorosmod

import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme

import com.rikumi.colorosmod.XposedInit.KEY_DISABLE_APPS_NOVERIFY_ENABLED
import com.rikumi.colorosmod.XposedInit.KEY_HIDE_DISABLED_APPS_ENABLED

// 保留原警告和普通入口的只读用途；主动操作只由五次点击后的警告菜单进入。


// 说明段落之间的额外间距: 段内靠行距, 段间再拉开一点以区分段落。
private val HINT_PARAGRAPH_GAP = 2.dp

// 生成导出脚本: 每行一条 adb 命令, 按模式分段并以注释标出(停用 / 用户级卸载)。
private fun buildExportScript(ctx: Context, apps: List<ManagedAppEntry>): String {
    val sb = StringBuilder("#!/bin/sh\n")
    val disabled = apps.filter { !it.uninstalled }
    val uninstalled = apps.filter { it.uninstalled }
    if (disabled.isNotEmpty()) {
        sb.append("\n# ").append(ctx.getString(R.string.ui_disabled_state)).append("\n")
        disabled.forEach { sb.append("adb shell pm disable-user --user 0 ").append(it.pkg).append('\n') }
    }
    if (uninstalled.isNotEmpty()) {
        sb.append("\n# ").append(ctx.getString(R.string.ui_user_uninstalled)).append("\n")
        uninstalled.forEach { sb.append("adb shell pm uninstall -k --user 0 ").append(it.pkg).append('\n') }
    }
    return sb.toString()
}

/** 普通入口保留列表及导出；五次点击警告说明后，显式确认才进入系统应用操作列表。 */
@Composable
internal fun DisabledAppsScreen(ctx: Context, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<ManagedAppEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loadGeneration by remember { mutableStateOf(0) }
    val pickerState = rememberLazyListState()
    val currentListState = if (picking) pickerState else listState
    BackHandler(enabled = picking || busy) { if (!busy) picking = false }
    var apps by remember { mutableStateOf<List<ManagedAppEntry>?>(null) }
    LaunchedEffect(Unit) {
        val generation = loadGeneration
        try {
            val loaded = withContext(Dispatchers.IO) { loadDisabledManagedApps(ctx) }
            if (generation == loadGeneration) apps = loaded
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation == loadGeneration) {
                apps = emptyList()
                showManagedAppsError(ctx, R.string.app_management_read_disabled_failed, error)
            }
        }
    }
    LaunchedEffect(picking) {
        overscrollOffset.floatValue = 0f
        if (picking) {
            choices = null
            pickerState.scrollToItem(0)
            try {
                choices = withContext(Dispatchers.IO) { loadSystemManagedApps(ctx) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                choices = emptyList()
                showManagedAppsError(ctx, R.string.app_management_read_system_failed, error)
            }
        }
    }
    fun perform(app: ManagedAppEntry, operation: ManagedAppOperation) {
        if (busy) return
        busy = true
        loadGeneration++
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.app_management_progress,
            ctx.getString(operation.titleRes)), android.widget.Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { executeManagedAppOperation(ctx, app, operation) }
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.app_management_success),
                    android.widget.Toast.LENGTH_SHORT).show()
                try {
                    apps = withContext(Dispatchers.IO) { loadDisabledManagedApps(ctx) }
                    if (picking) choices = withContext(Dispatchers.IO) { loadSystemManagedApps(ctx) }
                } catch (error: Exception) {
                    showManagedAppsError(ctx, R.string.app_management_refresh_failed, error)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.app_management_action_failed,
                    ctx.getString(operation.titleRes), error.message ?: error.javaClass.simpleName),
                    android.widget.Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    // SAF 创建文件后写入脚本, 无需存储权限。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-sh"),
    ) { uri ->
        if (uri != null) {
            val current = apps.orEmpty()
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(buildExportScript(ctx, current).toByteArray())
                    } != null
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx,
                        if (ok) ctx.getString(R.string.ui_exported) else ctx.getString(R.string.ui_export_failed),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    Scaffold(
        topBar = {
            CouixTopAppBar(
                title = ctx.getString(if (picking) R.string.app_management_select_system else R.string.ui_category_disabled),
                dividerProgress = couixTopBarDividerProgress(currentListState, overscrollOffset),
                navigationIcon = {
                    IconButton(onClick = { if (!busy) { if (picking) picking = false else onBack() } }) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = ctx.getString(R.string.ui_back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(COUIX_BACK_ICON),
                        )
                    }
                },
                actions = {
                    if (!picking && !busy) {
                        IconButton(onClick = {
                            if (apps.isNullOrEmpty()) {
                                android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_no_apps_to_export), android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                exportLauncher.launch("disabled_apps.sh")
                            }
                        }) {
                            Icon(
                                imageVector = MiuixIcons.Share,
                                contentDescription = ctx.getString(R.string.ui_export),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        RestartMenu(ctx)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = currentListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .couixOverscroll(currentListState, overscrollOffset),
        ) {
            if (picking) {
                item { CouixSmallTitle(ctx.getString(R.string.app_management_system_apps)) }
                val current = choices
                when {
                    current == null -> item { CouixSmallTitle(ctx.getString(R.string.ui_loading)) }
                    current.isEmpty() -> item { CouixSmallTitle(ctx.getString(R.string.ui_no_apps)) }
                    else -> itemsIndexed(current, key = { _, app -> app.pkg }) { index, app ->
                        CouixCardRow(first = index == 0, last = index == current.lastIndex) {
                            if (index > 0) CouixItemDivider()
                            ManagedAppActionsRow(app, busy, picking = true,
                                onAction = { perform(app, it) })
                        }
                    }
                }
            } else {
                // 第一个 group: 功能说明, 标题 + subtitle 形式(行内边距与设置项一致)。
                item { CouixCard { DisabledAppsHintRow(enabled = !busy) { picking = true } } }
                item { CouixSmallTitle(text = ctx.getString(R.string.ui_disabled_settings)) }
                item {
                    CouixCard {
                        DisableAppsNoVerifyRow(ctx)
                        CouixItemDivider()
                        HideDisabledAppsRow(ctx)
                    }
                }
                item { CouixSmallTitle(text = ctx.getString(R.string.ui_disabled_apps)) }
                val current = apps
                when {
                    current == null -> item { CouixSmallTitle(text = ctx.getString(R.string.ui_loading)) }
                    current.isEmpty() -> item { CouixSmallTitle(text = ctx.getString(R.string.ui_no_disabled_apps)) }
                    // 每行一个独立的惰性 item: 行数上百时不能共用一个容器(见 CouixCardRow 注释)。
                    else -> itemsIndexed(current, key = { _, entry -> entry.pkg }) { index, entry ->
                        CouixCardRow(first = index == 0, last = index == current.lastIndex) {
                            if (index > 0) CouixItemDivider()
                            ManagedAppActionsRow(entry, busy,
                                onInfo = { openAppDetails(ctx, entry.pkg) },
                                onAction = { perform(entry, it) })
                        }
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}

/** 停用应用免密码开关: 开启后设置里停用受保护应用不再要求生物识别/锁屏验证。 */
@Composable
private fun DisableAppsNoVerifyRow(ctx: Context) {
    var checked by remember {
        mutableStateOf(ctx.settingsPrefs().getBoolean(KEY_DISABLE_APPS_NOVERIFY_ENABLED, false))
    }
    CouixSwitchPreference(
        checked = checked,
        onCheckedChange = {
            checked = it
            setBool(ctx, KEY_DISABLE_APPS_NOVERIFY_ENABLED, it)
        },
        title = androidx.compose.ui.res.stringResource(R.string.ui_disable_no_password),
    )
}

/** 隐藏已停用应用开关: 开启后设置的应用管理页不再列出已停用的应用。 */
@Composable
private fun HideDisabledAppsRow(ctx: Context) {
    var checked by remember {
        mutableStateOf(ctx.settingsPrefs().getBoolean(KEY_HIDE_DISABLED_APPS_ENABLED, false))
    }
    CouixSwitchPreference(
        checked = checked,
        onCheckedChange = {
            checked = it
            setBool(ctx, KEY_HIDE_DISABLED_APPS_ENABLED, it)
        },
        title = androidx.compose.ui.res.stringResource(R.string.ui_hide_in_app_management),
    )
}

/** 功能说明行: 标题 + subtitle(与设置项同款文字样式与行内边距)。 */
@Composable
private fun DisabledAppsHintRow(enabled: Boolean, onAdd: () -> Unit) {
    val gate = remember { AppManagementEntryGate() }
    var lastTap by remember { mutableStateOf(0L) }
    var expanded by remember { mutableStateOf(false) }
    var anchorHeight by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(lastTap) {
        val tappedAt = lastTap
        delay(1_000)
        // 重组前的新点击也不能被上一次点击的延迟回调清除。
        if (lastTap == tappedAt) gate.reset()
    }
    Box(Modifier.onSizeChanged { anchorHeight = it.height }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                    val now = SystemClock.elapsedRealtime()
                    lastTap = now
                    if (gate.tap(now)) expanded = true
                }
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            BasicText(
                text = androidx.compose.ui.res.stringResource(R.string.ui_feature_info),
                style = MiuixTheme.textStyles.body1.copy(
                    color = MiuixTheme.colorScheme.onSurface,
                ),
            )
            // 说明按换行符切成多段: 段内走行距, 段间额外加一点间距区分段落。
            androidx.compose.ui.res.stringResource(R.string.ui_disabled_apps_hint).split('\n').forEachIndexed { index, paragraph ->
                BasicText(
                    text = paragraph,
                    style = MiuixTheme.textStyles.body2.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        // 等宽数字: 让 "1." / "2." 这类序号的句点对齐。
                        fontFeatureSettings = "tnum",
                    ),
                    modifier = Modifier.padding(
                        top = 6.dp,
                    ),
                )
            }
        }
        if (expanded && enabled) {
            val shape = RoundedCornerShape(COUIX_CARD_CORNER)
            Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, anchorHeight),
                onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                Column(Modifier.width(260.dp).shadow(12.dp, shape)
                    .background(MiuixTheme.colorScheme.surfaceContainer, shape).clip(shape)) {
                    listOf(R.string.app_management_ignore_warning, R.string.ui_cancel).forEachIndexed { index, title ->
                        BasicText(ctx.getString(title), style = MiuixTheme.textStyles.body1.copy(
                            color = if (index == 0) Color(0xFFE5484D) else MiuixTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                                expanded = false
                                if (index == 0) onAdd()
                            }.padding(horizontal = 20.dp, vertical = 16.dp))
                    }
                }
            }
        }
    }

}

// 跳转到设置的应用详情页。标准 Intent 由 com.android.settings.applications
// .InstalledAppDetails 响应(实测停用与用户级卸载的包都能解析到它)。用户级卸载的包已不在当前
// 用户下, 设置可能打不开, 因此兜住 ActivityNotFoundException 并提示。
private fun openAppDetails(ctx: Context, pkg: String) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        )
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_cannot_open_app_settings), android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

/** 带图标及状态的应用行；动作菜单由父组件按列表用途提供。 */
@Composable
private fun DisabledAppRow(entry: ManagedAppEntry, showState: Boolean = true, onClick: () -> Unit) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entry.icon?.let {
            Image(it.asImageBitmap(), contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
            Box(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (entry.label != null) {
                BasicText(
                    text = entry.label,
                    style = MiuixTheme.textStyles.body1.copy(color = onSurface),
                )
                BasicText(
                    text = entry.pkg,
                    style = MiuixTheme.textStyles.body2.copy(color = summary),
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                BasicText(
                    text = entry.pkg,
                    style = MiuixTheme.textStyles.body1.copy(color = onSurface),
                )
            }
        }
        if (showState) BasicText(
            text = if (entry.uninstalled) androidx.compose.ui.res.stringResource(R.string.ui_user_uninstalled) else androidx.compose.ui.res.stringResource(R.string.ui_disabled_state),
            style = MiuixTheme.textStyles.body2.copy(color = summary),
            modifier = Modifier.padding(start = 12.dp),
        )
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            // 与分类入口行同款: 在 summary 之上再压低透明度, 只作指示不抢视觉。
            tint = summary.copy(alpha = summary.alpha * 0.6f),
            modifier = Modifier
                .padding(start = 12.dp)
                .size(16.dp),
        )
    }
}

private val ManagedAppOperation.titleRes: Int
    get() = when (this) {
        ManagedAppOperation.DISABLE -> R.string.app_management_disable
        ManagedAppOperation.UNINSTALL -> R.string.app_management_uninstall
        ManagedAppOperation.ENABLE, ManagedAppOperation.INSTALL_EXISTING -> R.string.app_management_restore
    }

private fun showManagedAppsError(ctx: Context, title: Int, error: Exception) {
    if (error is CancellationException) throw error
    android.widget.Toast.makeText(ctx, ctx.getString(R.string.app_management_error,
        ctx.getString(title), error.message ?: error.javaClass.simpleName),
        android.widget.Toast.LENGTH_LONG).show()
}

@Composable
private fun ManagedAppActionsRow(app: ManagedAppEntry, busy: Boolean, picking: Boolean = false,
    onInfo: () -> Unit = {}, onAction: (ManagedAppOperation) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var anchorHeight by remember { mutableStateOf(0) }
    Box(Modifier.onSizeChanged { anchorHeight = it.height }) {
        DisabledAppRow(app, showState = !picking) { if (!busy) expanded = true }
        val actions = if (picking) listOf(
            ActionMenuItem(ctx.getString(R.string.app_management_disable)) { onAction(ManagedAppOperation.DISABLE) },
            ActionMenuItem(ctx.getString(R.string.app_management_uninstall)) { onAction(ManagedAppOperation.UNINSTALL) },
        ) else listOf(
            ActionMenuItem(ctx.getString(R.string.ui_app_info), onInfo),
            ActionMenuItem(ctx.getString(R.string.app_management_restore)) {
                onAction(if (app.uninstalled) ManagedAppOperation.INSTALL_EXISTING else ManagedAppOperation.ENABLE)
            },
        )
        CouixRowActionMenu(expanded = expanded && !busy, onDismiss = { expanded = false },
            anchorHeight = anchorHeight, items = actions)
    }
}
