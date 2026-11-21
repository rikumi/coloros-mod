package com.rikumi.colorosmod

import android.content.Context

internal object WifiBssidMigration {
    private const val COMPLETE = "wifi_bssid_autojoin_migrated_v1"

    @JvmStatic
    @Synchronized
    fun migrate(context: Context) {
        val prefs = context.settingsPrefs()
        if (prefs.getBoolean(COMPLETE, false)) return
        runCatching {
            if (WifiBssidSettings.execute(context, "migrate-autojoin").optBoolean("autojoinMigrationComplete")) {
                check(prefs.edit().putBoolean(COMPLETE, true).commit())
            }
        }.onFailure { AppLog.log("WiFi BSSID auto-connect migration failed: $it") }
    }
}
