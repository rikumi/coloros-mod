package com.rikumi.colorosmod

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
)

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
            if (service != null) service.scheduleRefresh() else if (hasAccess(context)) {
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
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = work.launch {
            delay(120)
            operations.withLock {
                try {
                    readEntries()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.value = mutableState.value.copy(error = R.string.hidden_notifications_read_failed)
                }
            }
        }
    }

    private suspend fun readEntries(): List<HiddenNotificationEntry> {
        check(instance === this)
        val entries = withContext(Dispatchers.IO) {
            val active = getActiveNotifications() ?: error("Notification listener unavailable")
            val hidden = getSnoozedNotifications() ?: error("Notification listener unavailable")
            val apps = mutableMapOf<Pair<String, Int>, Pair<String, Bitmap?>>()
            fun entry(sbn: StatusBarNotification, isHidden: Boolean): HiddenNotificationEntry {
                val (name, icon) = apps.getOrPut(sbn.packageName to sbn.uid) {
                    val pm = packageManager
                    val info = runCatching { pm.getApplicationInfo(sbn.packageName, 0) }.getOrNull()
                    val label = runCatching { info?.loadLabel(pm)?.toString() }.getOrNull() ?: sbn.packageName
                    val bitmap = runCatching {
                        val drawable = info?.loadIcon(pm) ?: sbn.notification.smallIcon?.loadDrawable(this@HiddenNotificationsService)
                            ?: pm.defaultActivityIcon
                        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
                            drawable.setBounds(0, 0, 64, 64)
                            drawable.draw(Canvas(it))
                        }
                    }.getOrNull()
                    label to bitmap
                }
                val extras = sbn.notification.extras
                fun text(key: String) = runCatching { extras.getCharSequence(key)?.toString().orEmpty() }
                    .getOrDefault("").take(2000)
                return HiddenNotificationEntry(
                    sbn.key, sbn.packageName, name, text(Notification.EXTRA_TITLE),
                    text(Notification.EXTRA_TEXT).ifBlank { text(Notification.EXTRA_BIG_TEXT) },
                    icon, isHidden, sbn.isOngoing, sbn.postTime,
                )
            }
            val all = LinkedHashMap<String, HiddenNotificationEntry>()
            active.forEach { all[it.key] = entry(it, false) }
            hidden.forEach { all[it.key] = entry(it, true) }
            all.values.sortedWith(compareByDescending<HiddenNotificationEntry> { it.postTime }.thenBy { it.key })
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
        withContext(Dispatchers.IO) {
            snoozeNotification(key, if (hidden) HIDE_DURATION_MS else RESTORE_DURATION_MS)
        }
        // Binder queues the operation; the method returning does not confirm a state change.
        repeat(20) {
            delay(150)
            val entries = readEntries()
            if (!hidden) hiddenSystemKeys.remove(key)
            val updated = entries.firstOrNull { it.key == key }
            if (updated?.hidden == hidden || (!hidden && updated == null)) return
        }
        if (current.ongoing && current.packageName == "android" && current.hidden) hiddenSystemKeys.add(key)
        error("Notification state did not change")
    }
}
