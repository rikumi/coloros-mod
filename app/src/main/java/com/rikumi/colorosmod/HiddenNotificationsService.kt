package com.rikumi.colorosmod

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class HiddenNotificationEntry(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val hidden: Boolean,
    val ongoing: Boolean,
    val postTime: Long,
    val posted: Boolean = true,
    val channelId: String = "",
    val channelName: String = "",
) {
    val notificationId: String
        get() = key.split('|', limit = 4).getOrNull(2)?.takeIf { it.toIntOrNull() != null } ?: "—"
}

/** 当前通知优先，历史记录只补充系统中已不存在的隐藏项。 */
internal fun mergeHiddenNotificationEntries(
    current: List<HiddenNotificationEntry>,
    history: List<HiddenNotificationEntry>,
    forgetKey: String? = null,
): List<HiddenNotificationEntry> {
    val entries = current.associateByTo(linkedMapOf()) { it.key }
    history.filter { it.hidden && it.key != forgetKey }.forEach {
        entries.putIfAbsent(it.key, it.copy(posted = false))
    }
    return entries.values.sortedWith(compareByDescending<HiddenNotificationEntry> { it.postTime }.thenBy { it.key })
}

internal data class HiddenNotificationsState(
    val connected: Boolean = false,
    val loaded: Boolean = false,
    val entries: List<HiddenNotificationEntry> = emptyList(),
    val busyKey: String? = null,
    val error: Int? = null,
)

class HiddenNotificationsService : NotificationListenerService() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private var refreshJob: Job? = null
    private val hiddenSystemKeys = mutableSetOf<String>()
    private var history: List<HiddenNotificationEntry>? = null
    private var systemHistory: List<PersistedHiddenNotification>? = null
    private var systemChannels: Map<Triple<String, Int, String>, String>? = null
    private val contentStore = lazy { HiddenNotificationContents(applicationContext) }
    private val contents by contentStore

    companion object {
        internal const val HIDE_DURATION_MS = 31_536_000_000L
        internal const val RESTORE_DURATION_MS = 100L
        private var instance: HiddenNotificationsService? = null
        private val mutableState = MutableStateFlow(HiddenNotificationsState())
        internal val state: StateFlow<HiddenNotificationsState> = mutableState.asStateFlow()

        internal fun component(context: Context) = ComponentName(context, HiddenNotificationsService::class.java)

        internal fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 27) {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component(context))
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component(context) }
        }

        internal fun refresh(context: Context) {
            mutableState.value = mutableState.value.copy(error = null)
            val service = instance
            if (service != null) service.scheduleRefresh(refreshSystemHistory = true) else if (hasAccess(context)) {
                try {
                    requestRebind(component(context))
                } catch (_: RuntimeException) {
                    mutableState.value = mutableState.value.copy(error = R.string.hidden_notifications_connect_failed)
                }
            }
        }

        internal fun toggle(entry: HiddenNotificationEntry) {
            val service = instance ?: return
            if (mutableState.value.busyKey != null) return
            mutableState.value = mutableState.value.copy(busyKey = entry.key, error = null)
            service.work.launch {
                service.operations.withLock {
                    try {
                        service.changeHidden(entry.key, !entry.hidden)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableState.value = mutableState.value.copy(error = R.string.hidden_notifications_action_failed)
                    } finally {
                        mutableState.value = mutableState.value.copy(busyKey = null)
                    }
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        mutableState.value = HiddenNotificationsState(connected = true)
        scheduleRefresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        disconnect()
    }

    override fun onDestroy() {
        disconnect()
        work.cancel()
        if (contentStore.isInitialized()) contents.close()
        super.onDestroy()
    }

    private fun disconnect() {
        work.coroutineContext.cancelChildren()
        hiddenSystemKeys.clear()
        if (instance === this) {
            instance = null
            mutableState.value = HiddenNotificationsState()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        contents.record(sbn.key, HiddenNotificationContents.extract(sbn.notification, sbn.postTime))
        if (sbn.packageName == "android" && sbn.isOngoing && sbn.key in hiddenSystemKeys) {
            work.launch {
                operations.withLock {
                    if (instance === this@HiddenNotificationsService && sbn.key in hiddenSystemKeys) {
                        try {
                            withContext(Dispatchers.IO) { snoozeNotification(sbn.key, HIDE_DURATION_MS) }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            mutableState.value = mutableState.value.copy(error = R.string.hidden_notifications_action_failed)
                        }
                    }
                }
            }
        }
        scheduleRefresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 同时捕获短时间内发送后撤回的通知，不依赖延迟刷新时仍能查询到它。
        contents.record(sbn.key, HiddenNotificationContents.extract(sbn.notification, sbn.postTime))
        scheduleRefresh()
    }

    private fun scheduleRefresh(refreshSystemHistory: Boolean = false) {
        if (refreshSystemHistory) {
            systemHistory = null
            systemChannels = null
        }
        refreshJob?.cancel()
        refreshJob = work.launch {
            delay(120)
            operations.withLock {
                try {
                    readEntries(refreshSystemHistory = refreshSystemHistory)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.value = mutableState.value.copy(error = R.string.hidden_notifications_read_failed)
                }
            }
        }
    }

    private suspend fun readEntries(
        forgetKey: String? = null,
        refreshSystemHistory: Boolean = false,
    ): List<HiddenNotificationEntry> {
        check(instance === this)
        val entries = withContext(Dispatchers.IO) {
            val active = getActiveNotifications() ?: error("Notification listener unavailable")
            val hidden = getSnoozedNotifications() ?: error("Notification listener unavailable")
            val apps = mutableMapOf<Pair<String, Int>, Pair<String, Bitmap?>>()
            fun application(packageName: String, uid: Int): Pair<String, Bitmap?> =
                apps.getOrPut(packageName to uid) {
                    val pm = packageManager
                    val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                    val label = runCatching { info?.loadLabel(pm)?.toString() }.getOrNull() ?: packageName
                    val bitmap = runCatching {
                        val drawable = info?.loadIcon(pm) ?: pm.defaultActivityIcon
                        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
                            drawable.setBounds(0, 0, 64, 64)
                            drawable.draw(Canvas(it))
                        }
                    }.getOrNull()
                    label to bitmap
                }
            val rankings = currentRanking
            fun entry(sbn: StatusBarNotification, isHidden: Boolean): HiddenNotificationEntry {
                val (name, icon) = application(sbn.packageName, sbn.uid)
                val content = HiddenNotificationContents.extract(sbn.notification, sbn.postTime)
                contents.remember(sbn.key, content)
                val ranking = Ranking()
                val channel = if (rankings.getRanking(sbn.key, ranking)) ranking.channel else null
                return HiddenNotificationEntry(
                    sbn.key, sbn.packageName, name, content.title, content.text,
                    icon, isHidden, sbn.isOngoing, sbn.postTime,
                    channelId = channel?.id ?: sbn.notification.channelId.orEmpty(),
                    channelName = channel?.name?.toString().orEmpty(),
                )
            }
            val all = LinkedHashMap<String, HiddenNotificationEntry>()
            active.forEach { all[it.key] = entry(it, false) }
            hidden.forEach { all[it.key] = entry(it, true) }
            val prefs = getSharedPreferences("hidden_notification_history", Context.MODE_PRIVATE)
            val previous = history ?: runCatching {
                val saved = JSONArray(prefs.getString("entries", "[]"))
                (0 until saved.length()).mapNotNull { index ->
                    val item = saved.getJSONObject(index)
                    val key = item.optString("key")
                    val packageName = item.optString("package")
                    if (key.isBlank() || packageName.isBlank()) return@mapNotNull null
                    val (name, icon) = application(packageName, -1)
                    HiddenNotificationEntry(key, packageName, name,
                        item.optString("title").takeUnless { item.optLong("time") == 0L }.orEmpty(), "", icon,
                        hidden = true, ongoing = item.optBoolean("ongoing"), postTime = item.optLong("time"), posted = false,
                        channelId = item.optString("channelId"), channelName = item.optString("channelName"))
                }
            }.getOrElse {
                AppLog.log("hidden notification history read failed: $it")
                emptyList()
            }
            if (refreshSystemHistory || systemHistory == null) {
                runCatching { HiddenNotificationSnoozes.read(android.os.Process.myUid() / 100000) }
                    .onSuccess { systemHistory = it }
                    .onFailure { AppLog.log("system hidden notification rules read failed: $it") }
            }
            val remembered = previous.associateBy { it.key }
            val missing = systemHistory.orEmpty().map { rule ->
                remembered[rule.key] ?: run {
                    val (name, icon) = application(rule.packageName, -1)
                    HiddenNotificationEntry(rule.key, rule.packageName, name,
                        "", "", icon,
                        hidden = true, ongoing = false, postTime = 0, posted = false)
                }
            }
            // 普通监听器不能枚举其它应用的通道；历史通知从系统通道设置只读快照补充名称。
            if (systemChannels == null) {
                systemChannels = runCatching { HiddenNotificationChannels.read() }
                    .onFailure { AppLog.log("notification channel read failed: $it") }.getOrDefault(emptyMap())
            }
            val merged = mergeHiddenNotificationEntries(all.values.toList(), previous + missing, forgetKey).map { item ->
                val last = contents.lookup(item.key)
                val content = if (last == null) item else item.copy(
                    title = last.title.ifBlank { item.title }, text = last.text.ifBlank { item.text })
                val user = item.key.substringBefore('|').toIntOrNull()?.takeIf { it >= 0 }
                    ?: (android.os.Process.myUid() / 100000)
                // 有真实通道 ID 时不跨通道猜测；只为旧记录按已核实的系统发送路径补充 ID。
                val ids = if (item.channelId.isNotBlank()) listOf(item.channelId)
                    else hiddenNotificationChannelIds(item)
                val channel = ids.firstNotNullOfOrNull { id ->
                    systemChannels?.get(Triple(item.packageName, user, id))?.let { id to it }
                }
                content.copy(channelId = channel?.first ?: item.channelId.ifBlank { ids.firstOrNull().orEmpty() },
                    channelName = if (item.posted) item.channelName.ifBlank { channel?.second.orEmpty() }
                        else channel?.second ?: item.channelName)
            }
            val retained = merged.filter { it.hidden && it.key != forgetKey }
                .map { it.copy(text = "", posted = false) }
            val saved = JSONArray()
            retained.forEach { item ->
                saved.put(JSONObject().put("key", item.key).put("package", item.packageName)
                    .put("title", item.title).put("ongoing", item.ongoing).put("time", item.postTime)
                    .put("channelId", item.channelId).put("channelName", item.channelName))
            }
            val serialized = saved.toString()
            if (serialized != prefs.getString("entries", "[]")) {
                check(prefs.edit().putString("entries", serialized).commit()) { "Cannot save hidden notification history" }
            }
            history = retained
            merged
        }
        check(instance === this)
        entries.filter { it.hidden && it.ongoing && it.packageName == "android" }
            .forEach { hiddenSystemKeys.add(it.key) }
        mutableState.value = mutableState.value.copy(loaded = true, entries = entries)
        return entries
    }

    private suspend fun changeHidden(key: String, hidden: Boolean) {
        val current = readEntries().firstOrNull { it.key == key } ?: error("Notification no longer available")
        if (current.hidden == hidden) return
        if (!hidden) hiddenSystemKeys.remove(key)
        if (!current.posted) {
            check(!hidden)
            withContext(Dispatchers.IO) {
                if (systemHistory.orEmpty().any { it.key == key }) {
                    systemHistory = HiddenNotificationSnoozes.restore(key, android.os.Process.myUid() / 100000)
                }
            }
            readEntries(forgetKey = key)
            return
        }
        if (!hidden) systemHistory = systemHistory?.filterNot { it.key == key }
        withContext(Dispatchers.IO) {
            snoozeNotification(key, if (hidden) HIDE_DURATION_MS else RESTORE_DURATION_MS)
        }
        // Binder queues the operation; the method returning does not confirm a state change.
        repeat(20) {
            delay(150)
            val entries = readEntries(forgetKey = if (hidden) null else key)
            if (!hidden) hiddenSystemKeys.remove(key)
            val updated = entries.firstOrNull { it.key == key }
            if (updated?.hidden == hidden || (!hidden && updated == null)) return
        }
        if (current.ongoing && current.packageName == "android" && current.hidden) hiddenSystemKeys.add(key)
        error("Notification state did not change")
    }
}
