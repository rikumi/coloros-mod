package com.rikumi.colorosmod

import android.content.Context

/** 仅为没有通道记录的旧通知推断 ID，不替换通知自身携带的通道 ID。 */
internal fun hiddenNotificationChannelIds(entry: HiddenNotificationEntry): List<String> {
    val tag = entry.key.split('|', limit = 5).getOrNull(3).orEmpty()
    return when (entry.packageName) {
        "android" -> when (entry.notificationId) {
            "0" -> if (tag.startsWith(ALERT_WINDOW_NOTIFICATION_PREFIX)) listOf(tag) else emptyList()
            "43690" -> listOf("VPN")
            "2000" -> listOf(if (tag == "null") "lsposed_status" else "lsposed_module_updated")
            else -> emptyList()
        }
        "com.android.systemui" -> when (entry.notificationId) {
            "10001" -> listOf("channel_dnd_notice")
            "10002" -> listOf("INS")
            // 听力保护提醒也可能使用 ALR；无实际通道记录时按用户指定的充电通知处理。
            "10006" -> listOf("BAT_NEW", "BAT")
            "10011" -> listOf("FLA")
            else -> emptyList()
        }
        else -> emptyList()
    }
}

/** 仅供界面兜底，不写入通知正文；系统通道名称优先，其次读取安装包的通道名称资源。 */
internal fun hiddenNotificationFallbackTitle(context: Context, entry: HiddenNotificationEntry): String {
    val known = when (entry.packageName) {
        "android" -> entry.notificationId in setOf("0", "43690", "2000")
        "com.android.systemui" -> entry.notificationId in setOf("10001", "10002", "10006", "10011")
        else -> false
    }
    if (!known) return ""
    if (entry.channelName.isNotBlank()) return entry.channelName
    val channelId = entry.channelId.ifBlank { hiddenNotificationChannelIds(entry).firstOrNull().orEmpty() }
    val resourceName = when (entry.packageName) {
        "android" -> when {
            channelId == "VPN" -> "notification_channel_vpn"
            channelId.startsWith(ALERT_WINDOW_NOTIFICATION_PREFIX) -> "alert_windows_notification_channel_name"
            else -> ""
        }
        "com.android.systemui" -> when (channelId) {
            "channel_dnd_notice" -> "dnd_notice_channel_name"
            "INS" -> "notification_channel_instant"
            "BAT_NEW", "BAT" -> "notification_channel_battery"
            "ALR" -> "notification_channel_alerts"
            "FLA" -> "notification_flashlight"
            else -> ""
        }
        else -> ""
    }
    if (resourceName.isNotBlank()) {
        val resolved = runCatching {
            val resources = context.packageManager.getResourcesForApplication(entry.packageName)
            val id = resources.getIdentifier(resourceName, "string", entry.packageName)
            if (id == 0) "" else if (channelId.startsWith(ALERT_WINDOW_NOTIFICATION_PREFIX)) {
                val packageName = channelId.removePrefix(ALERT_WINDOW_NOTIFICATION_PREFIX)
                val name = runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                }.getOrDefault(packageName)
                resources.getString(id, name)
            } else resources.getString(id)
        }.getOrDefault("")
        if (resolved.isNotBlank()) return resolved
    }
    return context.getString(when (entry.packageName) {
        "android" -> when (entry.notificationId) {
            "0" -> R.string.hidden_notifications_fallback_overlay_generic
            "43690" -> R.string.hidden_notifications_fallback_vpn
            "2000" -> if (channelId == "lsposed_status") R.string.hidden_notifications_fallback_xposed_running
                else R.string.hidden_notifications_fallback_xposed_updated
            else -> return ""
        }
        "com.android.systemui" -> when (entry.notificationId) {
            "10001" -> R.string.hidden_notifications_fallback_dnd
            "10002" -> R.string.hidden_notifications_fallback_developer
            "10006" -> R.string.hidden_notifications_fallback_charge_status
            "10011" -> R.string.hidden_notifications_fallback_flashlight
            else -> return ""
        }
        else -> return ""
    })
}

private const val ALERT_WINDOW_NOTIFICATION_PREFIX = "com.android.server.wm.AlertWindowNotification - "
