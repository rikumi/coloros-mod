package com.rikumi.colorosmod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SystemFontBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            runCatching { HiddenNotificationsService.refresh(context.applicationContext) }
                .onFailure { AppLog.log("notification listener startup failed: $it") }
        }
        val result = goAsync()
        Thread {
            try {
                WifiBssidMigration.migrate(context.applicationContext)
                SystemFontMounts.resume(context.applicationContext)
            }
            finally { result.finish() }
        }.start()
    }
}
