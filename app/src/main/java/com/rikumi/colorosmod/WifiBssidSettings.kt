package com.rikumi.colorosmod

import android.content.Context
import android.util.Base64
import android.widget.Toast
import android.widget.ImageView
import android.content.res.ColorStateList
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.WindowDropdownPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object WifiBssidSettings {
    var data by mutableStateOf(JSONObject("{\"rules\":[]}"))
        private set
    var busy by mutableStateOf(false)
        private set
    var scans by mutableStateOf<Map<String, List<JSONObject>>>(emptyMap())
        private set
    var scanning by mutableStateOf(false)
        private set
    private val scanMutex = Mutex()
    private val mutex = Mutex()

    suspend fun perform(context: Context, operation: String, request: JSONObject = JSONObject()): Boolean = (if (operation == "scan") scanMutex else mutex).withLock {
        if (operation == "scan") {
            scanning = true
            scans = scans - request.optString("id")
        } else busy = true
        try {
            val response = withContext(Dispatchers.IO) {
                fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
                val payload = Base64.encodeToString(request.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
                val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                    "com.rikumi.colorosmod.WifiBssidCommand ${quote(operation)} ${quote(payload)}"
                val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                val reader = Executors.newSingleThreadExecutor()
                try {
                    process.outputStream.close()
                    val output = reader.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
                    check(process.waitFor(45, TimeUnit.SECONDS)) { "WiFi operation timed out" }
                    val text = output.get(2, TimeUnit.SECONDS).trim()
                    check(process.exitValue() == 0) { text.takeLast(1000) }
                    val json = text.lineSequence().lastOrNull { it.startsWith("{") } ?: error("Missing WiFi response")
                    JSONObject(json)
                } finally { process.destroyForcibly(); reader.shutdownNow() }
            }
            if (operation == "scan" || operation == "connect-once") {
                // A scan may finish after a policy edit: retain the latest rules.
                data = JSONObject(data.toString()).put("current", response.opt("current") ?: JSONObject.NULL)
                if (operation == "scan") {
                    val entries = response.getJSONArray("scans")
                    scans = scans + (response.getString("scanId") to (0 until entries.length()).map { entries.getJSONObject(it) })
                }
            } else {
                data = response
                if (operation == "remove") scans = scans - request.optString("id")
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            AppLog.log("WiFi BSSID $operation failed: $error")
            val detail = error.message.orEmpty()
            val message = when {
                detail.contains("WIFI_BSSID_CONNECT_TIMEOUT") -> context.getString(R.string.wifi_bssid_connect_timeout)
                detail.contains("WIFI_BSSID_SCAN_REJECTED") -> context.getString(R.string.wifi_bssid_scan_rejected)
                detail.contains("WIFI_BSSID_SCAN_TIMEOUT") -> context.getString(R.string.wifi_bssid_scan_timeout)
                detail.contains("WIFI_BSSID_AP_UNAVAILABLE") -> context.getString(R.string.wifi_bssid_ap_unavailable)
                detail.contains("WIFI_BSSID_HOOK_INACTIVE") -> context.getString(R.string.wifi_bssid_hook_inactive, detail.substringAfter("WIFI_BSSID_HOOK_INACTIVE: ").take(500))
                detail.contains("Connect to a saved WiFi first") -> context.getString(R.string.wifi_bssid_connect_first)
                detail.contains("Current AP belongs to a different WiFi") -> context.getString(R.string.wifi_bssid_wrong_wifi)
                detail.contains("Saved WiFi no longer exists") -> context.getString(R.string.wifi_bssid_saved_missing)
                else -> context.getString(R.string.wifi_bssid_error, error.message ?: error.javaClass.simpleName)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            false
        } finally { if (operation == "scan") scanning = false else busy = false }
    }
    fun entries(): List<JSONObject> {
        val array = data.optJSONArray("rules") ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
}

@Composable
internal fun WifiBssidGroup(context: Context, onOpenWifi: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { WifiBssidSettings.perform(context, "list") }
    ArrowPreference(title = context.getString(R.string.wifi_bssid_add_wifi), enabled = !WifiBssidSettings.busy,
        onClick = { scope.launch {
            if (WifiBssidSettings.perform(context, "add-wifi")) {
                WifiBssidSettings.data.optJSONObject("current")?.optString("id")
                    ?.takeIf { it.isNotBlank() }?.let(onOpenWifi)
            }
        } })
    WifiBssidSettings.entries().forEach { rule ->
        SettingsItemDivider()
        ArrowPreference(title = rule.getString("ssid"), onClick = { onOpenWifi(rule.getString("id")) })
    }
}

@Composable
private fun WifiFilterMode(
    context: Context, mode: Int, enabled: Boolean, onMode: (Int) -> Unit,
    developerConfirmedSubtitle: String? = null,
) {
    WindowDropdownPreference(
        title = context.getString(R.string.wifi_bssid_mode), summary = developerConfirmedSubtitle,
        items = listOf(context.getString(R.string.wifi_bssid_blacklist), context.getString(R.string.wifi_bssid_whitelist)),
        selectedIndex = mode, enabled = enabled, onSelectedIndexChange = onMode,
    )
}

@Composable
internal fun WifiBssidDetailScreen(context: Context, id: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberSettingsListScroll(rememberLazyListState())
    val bssidFont by produceState<FontFamily?>(null, context, id) {
        value = withContext(Dispatchers.IO) {
            runCatching { FontFamily(WifiBssidFont.load(context)) }
                .onFailure { AppLog.log("BSSID monospace font load failed: $it") }.getOrNull()
        }
    }
    val rule = WifiBssidSettings.entries().firstOrNull { it.optString("id") == id }
    var confirmRemoval by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (WifiBssidSettings.perform(context, "list") && WifiBssidSettings.entries().any { it.optString("id") == id })
            WifiBssidSettings.perform(context, "scan", JSONObject().put("id", id))
    }
    val request = { JSONObject().put("id", id) }
    Scaffold(topBar = {
        SettingsTopAppBar(title = rule?.optString("ssid") ?: context.getString(R.string.wifi_bssid_group),
            scrollBehavior = scroll.appBar,
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(COUIIcons.Back, contentDescription = context.getString(R.string.ui_back), modifier = Modifier.size(SETTINGS_BACK_ICON))
            } })
    }) { padding ->
        SettingsLazyColumn(scroll = scroll, modifier = Modifier.fillMaxSize().padding(padding)) {
            if (rule != null) {
                item {
                    SettingsCard {
                        WifiFilterMode(context, rule.optInt("mode", 0), !WifiBssidSettings.busy,
                            onMode = { mode -> scope.launch { WifiBssidSettings.perform(context, "mode", request().put("mode", mode)) } },
                            developerConfirmedSubtitle = context.getString(R.string.wifi_bssid_mode_subtitle))
                    }
                }
                item {
                    SettingsCard {
                        val entries = rule.getJSONArray("bssids")
                        for (index in 0 until entries.length()) {
                            if (index > 0) SettingsItemDivider()
                            val mac = entries.getString(index)
                            var expanded by remember(id, mac) { mutableStateOf(false) }
                            Box {
                                val ap = WifiBssidSettings.scans[id].orEmpty().firstOrNull { it.optString("bssid") == mac }
                                    ?: rule.optJSONObject("apDetails")?.optJSONObject(mac)
                                BasicComponent(enabled = !WifiBssidSettings.busy,
                                    content = { WifiApLabel(context, mac, ap, !WifiBssidSettings.busy, bssidFont) },
                                    onClick = { expanded = true }, endActions = {
                                        Text(context.getString(if (rule.optInt("mode", 0) == 0)
                                            R.string.wifi_bssid_no_auto_connect else R.string.wifi_bssid_auto_connect),
                                            style = COUITheme.textStyles.body2, color = settingsSummaryTextColor())
                                    })
                                SettingsRowActionMenu(expanded, onDismiss = { expanded = false }, items = listOf(
                                    ActionMenuItem(context.getString(R.string.wifi_bssid_delete)) {
                                        scope.launch { WifiBssidSettings.perform(context, "delete-ap", request().put("bssid", mac)) }
                                    }))
                            }
                        }
                        if (entries.length() > 0) SettingsItemDivider()
                        ArrowPreference(title = context.getString(R.string.wifi_bssid_add_ap), enabled = !WifiBssidSettings.busy,
                            onClick = { scope.launch { WifiBssidSettings.perform(context, "add-ap", request()) } })
                    }
                }
                item {
                    Layout(modifier = Modifier.fillMaxWidth(), content = {
                        SettingsSmallTitle(context.getString(R.string.wifi_bssid_scanned_aps))
                        if (!WifiBssidSettings.scanning) Text(context.getString(R.string.wifi_bssid_refresh),
                            style = COUITheme.textStyles.subtitle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = COUITheme.colorScheme.primary,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                scope.launch { WifiBssidSettings.perform(context, "scan", request()) }
                            }.padding(horizontal = SETTINGS_GROUP_TITLE_START, vertical = 10.dp))
                    }) { children, constraints ->
                        val refresh = children.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val titleWidth = (constraints.maxWidth - (refresh?.width ?: 0)).coerceAtLeast(0)
                        val title = children[0].measure(constraints.copy(minWidth = titleWidth, maxWidth = titleWidth))
                        // Keep the caption's native height. The action retains its full
                        // padded hit target and is centered independently of that height.
                        layout(constraints.maxWidth, title.height) {
                            title.placeRelative(0, 0)
                            refresh?.placeRelative(constraints.maxWidth - refresh.width, (title.height - refresh.height) / 2)
                        }
                    }
                }
                if (WifiBssidSettings.scanning) item {
                    SettingsCard {
                        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                            RotatingProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (WifiBssidSettings.scans[id].orEmpty().isNotEmpty()) item {
                    SettingsCard {
                        WifiBssidSettings.scans[id].orEmpty().forEachIndexed { index, ap ->
                            if (index > 0) SettingsItemDivider()
                            val mac = ap.getString("bssid")
                            val enabled = !WifiBssidSettings.busy
                            var expanded by remember(id, mac) { mutableStateOf(false) }
                            Box {
                            BasicComponent(enabled = enabled,
                                content = { WifiApLabel(context, mac, ap, enabled, bssidFont) },
                                onClick = { expanded = true }, endActions = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text("${ap.getInt("rssi")}dB", style = COUITheme.textStyles.body2.copy(
                                            fontFeatureSettings = "tnum"), color = settingsSummaryTextColor())
                                        WifiSystemSignalIcon(context, ap.getInt("level"),
                                            WifiBssidSettings.data.optJSONObject("current")?.optString("bssid") == mac)
                                    }
                                })
                                SettingsRowActionMenu(expanded, onDismiss = { expanded = false }, items = listOf(
                                    ActionMenuItem(context.getString(R.string.wifi_bssid_connect_once)) {
                                        scope.launch { WifiBssidSettings.perform(context, "connect-once", request().put("bssid", mac)) }
                                    },
                                    ActionMenuItem(context.getString(if (rule.optInt("mode", 0) == 0)
                                        R.string.wifi_bssid_join_blacklist else R.string.wifi_bssid_join_whitelist)) {
                                        scope.launch { WifiBssidSettings.perform(context, "add-scanned-ap", request().put("bssid", mac)) }
                                    },
                                ))
                            }
                        }
                    }
                }
                item {
                    SettingsCard {
                        BasicComponent(
                            title = context.getString(R.string.wifi_bssid_remove),
                            titleColor = BasicComponentDefaults.titleColor(color = Color(0xFFD84040)),
                            enabled = !WifiBssidSettings.busy,
                            onClick = { confirmRemoval = true },
                        )
                    }
                }
            }
        }
    }
    if (confirmRemoval) SettingsConfirmDialog(
        title = context.getString(R.string.wifi_bssid_remove),
        text = AnnotatedString(context.getString(R.string.wifi_bssid_remove_confirm)),
        onDismiss = { confirmRemoval = false },
        onConfirm = {
            confirmRemoval = false
            scope.launch { if (WifiBssidSettings.perform(context, "remove", request())) onBack() }
        },
    )
}

@Composable
private fun WifiApLabel(context: Context, mac: String, ap: JSONObject?, enabled: Boolean, font: FontFamily?) {
    // Avoid briefly resolving the generic monospace alias while the concrete font loads.
    if (font == null) return
    val frequency = ap?.optInt("frequency") ?: 0
    val band = when (frequency) {
        in 2400..2500 -> "2.4"
        in 4900..5895 -> "5"
        in 5925..7125 -> "6"
        in 58320..70200 -> "60"
        else -> null
    }
    val channel = ap?.optInt("channel", -1) ?: -1
    val suffix = if (band != null && channel > 0)
        context.getString(R.string.wifi_bssid_ap_details, band, channel) else ""
    val detailsStyle = COUITheme.textStyles.body2.toSpanStyle().copy(color = settingsSummaryTextColor())
    val label = buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = font)) { append(mac) }
        withStyle(detailsStyle) { append(suffix) }
    }
    BasicText(label, style = COUITheme.textStyles.body1.copy(
        fontFeatureSettings = "tnum",
        color = COUITheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
    ))
}

/** Resolve the installed SystemUI resource so ROM overlays and current artwork are retained. */
@Composable
private fun WifiSystemSignalIcon(context: Context, level: Int, connected: Boolean) {
    val configuration = LocalConfiguration.current
    val drawable = remember(context, level, configuration) {
        runCatching {
            val systemUi = context.createPackageContext("com.android.systemui", 0)
            val resource = systemUi.resources.getIdentifier("stat_signal_wifi_signal_${level.coerceIn(0, 4)}", "drawable", "com.android.systemui")
            if (resource == 0) null else systemUi.getDrawable(resource)?.mutate()
        }.getOrNull() ?: context.getDrawable(intArrayOf(
            R.drawable.wifi_bssid_signal_0, R.drawable.wifi_bssid_signal_1, R.drawable.wifi_bssid_signal_2,
            R.drawable.wifi_bssid_signal_3, R.drawable.wifi_bssid_signal_4)[level.coerceIn(0, 4)])?.mutate()
    }
    val tint = COUITheme.colorScheme.onSurface.toArgb()
    val badgeColor = COUITheme.colorScheme.primary
    val badgeBackground = CardDefaults.defaultColors().color
    Box(Modifier.width(28.dp).height(22.dp)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ImageView(it) }, update = {
            it.setImageDrawable(drawable)
            it.imageTintList = ColorStateList.valueOf(tint)
            it.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        if (connected) Canvas(Modifier.size(11.dp).align(Alignment.BottomEnd).background(badgeBackground, CircleShape)) {
            val stroke = 1.5.dp.toPx()
            drawLine(badgeColor, Offset(size.width * 0.18f, size.height * 0.52f),
                Offset(size.width * 0.42f, size.height * 0.75f), stroke, StrokeCap.Round)
            drawLine(badgeColor, Offset(size.width * 0.42f, size.height * 0.75f),
                Offset(size.width * 0.84f, size.height * 0.24f), stroke, StrokeCap.Round)
        }
    }
}
