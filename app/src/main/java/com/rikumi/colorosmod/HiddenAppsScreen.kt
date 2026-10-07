package com.rikumi.colorosmod

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class HiddenAppEntry(val component: String, val name: String, val icon: Bitmap?, val safeHidden: Boolean = false)

private fun appEntry(context: Context, component: String): HiddenAppEntry {
    val pm = context.packageManager
    return runCatching {
        val name = ComponentName.unflattenFromString(component) ?: error("Invalid component")
        @Suppress("DEPRECATION") val info = pm.getActivityInfo(name, 0)
        val drawable = info.loadIcon(pm)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(Canvas(bitmap))
        HiddenAppEntry(component, info.loadLabel(pm).toString(), bitmap)
    }.getOrElse { HiddenAppEntry(component, component, null) }
}

private fun readLauncherApps(context: Context): List<HiddenAppEntry> {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    val user = android.os.Process.myUid() / 100000
    val output = runRoot("CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
        "com.rikumi.colorosmod.HiddenAppsQueryCommand $user") ?: error("无法读取应用列表")
    val json = output.lineSequence().firstOrNull { it.startsWith("HIDDEN_APPS_QUERY:") }
        ?.removePrefix("HIDDEN_APPS_QUERY:") ?: error("无法读取安全中心隐藏状态")
    val array = org.json.JSONArray(json)
    val apps = (0 until array.length()).map { index ->
        val entry = array.getJSONObject(index)
        val icon = entry.optString("icon").takeIf { it.isNotEmpty() }?.let {
            val bytes = Base64.decode(it, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } ?: appEntry(context, entry.getString("component")).icon
        HiddenAppEntry(entry.getString("component"), entry.getString("name"), icon, entry.getBoolean("safeHidden"))
    }.distinctBy { it.component }
    val collator = Collator.getInstance(context.resources.configuration.locales[0])
    return apps.sortedWith { a, b ->
        val result = collator.compare(a.name, b.name)
        if (result == 0) a.component.compareTo(b.component) else result
    }
}

@Composable
internal fun HiddenAppsScreen(context: Context, onBack: () -> Unit) {
    val preferences = remember(context) { context.settingsPrefs() }
    var hidden by remember { mutableStateOf(preferences.getStringSet(HiddenLauncherApps.COMPONENTS, emptySet()).orEmpty().toSet()) }
    var picking by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<HiddenAppEntry>?>(null) }
    var entries by remember { mutableStateOf<List<HiddenAppEntry>>(emptyList()) }
    val listState = rememberLazyListState()
    val overscroll = remember { mutableFloatStateOf(0f) }
    fun save(components: Set<String>) {
        if (preferences.edit().putStringSet(HiddenLauncherApps.COMPONENTS, components)
                .putBoolean(HiddenLauncherApps.ENABLED, true).commit()) {
            hidden = components
            SettingsProvider.notifySettingsChanged(context)
        } else Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
    }
    BackHandler(enabled = picking) { picking = false }
    LaunchedEffect(hidden) {
        entries = withContext(Dispatchers.IO) { hidden.sorted().map { appEntry(context, it) } }
    }
    LaunchedEffect(picking) {
        listState.scrollToItem(0)
        if (picking) {
            choices = null
            val result = withContext(Dispatchers.IO) { runCatching { readLauncherApps(context) } }
            choices = result.getOrDefault(emptyList())
            if (result.isFailure) Toast.makeText(context, "读取应用列表失败，请检查 root 授权", Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(topBar = {
        CouixTopAppBar(
            title = if (picking) "选择应用" else "彻底隐藏应用",
            dividerProgress = couixTopBarDividerProgress(listState, overscroll),
            navigationIcon = {
                IconButton(onClick = { if (picking) picking = false else onBack() }) {
                    Icon(MiuixIcons.Back, "返回", tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(COUIX_BACK_ICON))
                }
            },
            actions = {
                if (!picking) BasicText("添加", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.primary),
                    modifier = Modifier.clickable { picking = true }.padding(horizontal = 12.dp, vertical = 10.dp))
                RestartMenu(context)
            },
        )
    }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).couixOverscroll(listState, overscroll)) {
            item { Spacer(Modifier.height(12.dp)) }
            val current = if (picking) choices?.filter { it.component !in hidden } else entries
            when {
                current == null -> item { CouixSmallTitle("加载中…") }
                current.isEmpty() -> item { CouixSmallTitle("暂无应用") }
                else -> {
                    val groups = if (picking) listOf(
                        null to current.filter { !it.safeHidden },
                        "已被安全中心隐藏" to current.filter { it.safeHidden },
                    ) else listOf(null to current)
                    groups.forEach { (title, apps) ->
                        if (apps.isNotEmpty()) {
                            if (title != null) item { CouixSmallTitle(title) }
                            itemsIndexed(apps, key = { _, app -> app.component }) { index, app ->
                                CouixCardRow(first = index == 0, last = index == apps.lastIndex) {
                                    if (index > 0) CouixItemDivider()
                                    HiddenAppRow(app, picking,
                                        onAdd = { save(hidden + app.component); picking = false },
                                        onRemove = { save(hidden - app.component) },
                                        onOpen = {
                                            val launch = ComponentName.unflattenFromString(app.component)?.let {
                                                android.content.Intent(android.content.Intent.ACTION_MAIN)
                                                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                                                    .setComponent(it).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            if (launch == null || runCatching { context.startActivity(launch); true }.getOrDefault(false).not())
                                                Toast.makeText(context, "无法打开应用", Toast.LENGTH_SHORT).show()
                                        })
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun HiddenAppRow(app: HiddenAppEntry, picking: Boolean, onAdd: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var anchorHeight by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    Box(Modifier.onSizeChanged { anchorHeight = it.height }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable { if (picking) onAdd() else expanded = true }
            .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))) }
                ?: Box(Modifier.size(32.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary, RoundedCornerShape(8.dp)))
            BasicText(app.name,
                style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.padding(start = 12.dp).weight(1f))
        }
        CouixRowActionMenu(expanded = expanded, onDismiss = { expanded = false }, anchorHeight = anchorHeight,
            items = listOf(
                ActionMenuItem("打开", onOpen),
                ActionMenuItem("应用信息") {
                    val packageName = ComponentName.unflattenFromString(app.component)?.packageName
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { context.startActivity(intent) }.isFailure)
                        Toast.makeText(context, "无法打开应用信息", Toast.LENGTH_SHORT).show()
                },
                ActionMenuItem("取消隐藏", onRemove),
            ))
    }
}
