package com.rikumi.colorosmod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SystemFontBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val result = goAsync()
        Thread {
            try { SystemFontMounts.resume(context.applicationContext) }
            finally { result.finish() }
        }.start()
    }
}
