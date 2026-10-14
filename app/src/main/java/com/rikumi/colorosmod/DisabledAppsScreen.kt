package com.rikumi.colorosmod

import android.content.Context
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
import androidx.compose.ui.unit.sp
import java.text.Collator
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

// 页面顶部说明: 本页不提供直接停用的入口, 只做只读展示与脚本导出。


// 说明段落之间的额外间距: 段内靠行距, 段间再拉开一点以区分段落。
private val HINT_PARAGRAPH_GAP = 2.dp

// 停用应用条目: label 取不到时为 null(列表退回只显示包名);
// uninstalled=true 表示用户级卸载(pm uninstall -k --user 0), false 表示停用(pm disable-user)。
private data class DisabledAppEntry(
    val pkg: String,
    val label: String?,
    val uninstalled: Boolean,
)

// 读取已停用与用户级卸载的应用列表(只读 pm 命令, IO 线程调用)。无 root 或输出异常时返回 null。
// -d: 已停用; -u: 含已卸载但保留数据的包; 无参: 当前用户已安装。用户级卸载 = -u 与已安装的差集;
// 同一包只归入一个类别, 卸载优先(停用列表剔除已卸载项)。
private fun listDisabledApps(ctx: Context): List<DisabledAppEntry>? {
    val marker = "---COLOROSMOD-SECTION---"
    val out = runRoot(
        "pm list packages -f -d; echo $marker; pm list packages -f -u; echo $marker; pm list packages -f"
    ) ?: return null
    // 按分隔行切成三段; 每段解析 -f 输出: package:/路径/base.apk=包名。
    val sections = mutableListOf<List<Pair<String, String>>>()
    var current = mutableListOf<Pair<String, String>>()
    out.lines().forEach { line ->
        val t = line.trim()
        if (t == marker) {
            sections.add(current)
            current = mutableListOf()
        } else if (t.startsWith("package:")) {
            val eq = t.lastIndexOf('=')
            if (eq > "package:".length) current.add(t.substring("package:".length, eq) to t.substring(eq + 1))
        }
    }
    sections.add(current)
    if (sections.size != 3) return null
    val (disabled, withUninstalled, installed) = sections
    val installedPkgs = installed.map { it.second }.toSet()
    val uninstalled = withUninstalled.filter { it.second !in installedPkgs }
    val uninstalledPkgs = uninstalled.map { it.second }.toSet()
    val entries = mutableListOf<DisabledAppEntry>()
    disabled.forEach { (path, pkg) ->
        if (pkg !in uninstalledPkgs) {
            entries += DisabledAppEntry(pkg, appLabel(ctx, path), uninstalled = false)
        }
    }
    uninstalled.forEach { (path, pkg) ->
        entries += DisabledAppEntry(pkg, appLabel(ctx, path), uninstalled = true)
    }
    // 按当前语言的本地化规则排序(中文按拼音等), 而非按码位; 名称相同时以包名兜底, 保证顺序稳定。
    val collator = Collator.getInstance(ctx.resources.configuration.locales[0])
    return entries.sortedWith { a, b ->
        val c = collator.compare(a.label ?: a.pkg, b.label ?: b.pkg)
        if (c != 0) c else a.pkg.compareTo(b.pkg)
    }
}

// 直接从 APK 文件解析应用名: 停用/用户级卸载的包 APK 仍在, getPackageArchiveInfo 不受包可见性限制。
// 解析失败(如 APK 已物理删除)返回 null, 列表退回显示包名。
private fun appLabel(ctx: Context, apkPath: String): String? {
    return runCatching {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION") val info = pm.getPackageArchiveInfo(apkPath, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = apkPath
        ai.publicSourceDir = apkPath
        pm.getApplicationLabel(ai).toString().takeIf { it.isNotBlank() }
    }.getOrNull()
}

// 生成导出脚本: 每行一条 adb 命令, 按模式分段并以注释标出(停用 / 用户级卸载)。
private fun buildExportScript(ctx: Context, apps: List<DisabledAppEntry>): String {
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

/** 停用应用子页面: 只读列出已停用与用户级卸载的应用, 右上角导出为 adb 脚本。 */
@Composable
internal fun DisabledAppsScreen(ctx: Context, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // null = 加载中; 加载失败(无 root 等)toast 后按空列表展示。
    var apps by remember { mutableStateOf<List<DisabledAppEntry>?>(null) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { listDisabledApps(ctx) }
        if (result == null) {
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_root_denied), android.widget.Toast.LENGTH_SHORT).show()
        }
        apps = result.orEmpty()
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
                title = ctx.getString(R.string.ui_category_disabled),
                dividerProgress = couixTopBarDividerProgress(listState, overscrollOffset),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = ctx.getString(R.string.ui_back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(COUIX_BACK_ICON),
                        )
                    }
                },
                actions = {
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
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .couixOverscroll(listState, overscrollOffset),
        ) {
            // 第一个 group: 功能说明, 标题 + subtitle 形式(行内边距与设置项一致)。
            item { CouixCard { DisabledAppsHintRow() } }
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
                        DisabledAppRow(entry) { openAppDetails(ctx, entry.pkg) }
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
private fun DisabledAppsHintRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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

/** 停用应用列表行: 左侧应用名(取不到时只显示包名)与包名, 右侧灰色状态文字与前进箭头; 点击跳转设置。 */
@Composable
private fun DisabledAppRow(entry: DisabledAppEntry, onClick: () -> Unit) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        BasicText(
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
