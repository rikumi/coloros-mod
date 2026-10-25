package com.rikumi.colorosmod

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val themeRestoreStarted = AtomicBoolean(false)

internal fun restoreRemovedThemeColor(context: Context) {
    val ctx = context.applicationContext
    val backup = java.io.File(ctx.createDeviceProtectedStorageContext().filesDir, "theme-color-backup")
    if (backup.listFiles()?.none { java.io.File(it, "original.properties").exists() } != false) return
    if (!themeRestoreStarted.compareAndSet(false, true)) return
    CoroutineScope(Dispatchers.IO).launch {
        val error = runCatching {
            fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
            val command = "CLASSPATH=${quote(ctx.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                "com.rikumi.colorosmod.RestoreThemeColorCommand ${quote(backup.path)}"
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0 && output.lineSequence().any { it.trim() == "THEME_RESTORE_OK" }) {
                output.lineSequence().firstOrNull { it.startsWith("THEME_RESTORE_ERROR: ") }
                    ?.removePrefix("THEME_RESTORE_ERROR: ") ?: output.trim().takeLast(500).ifBlank { ctx.getString(R.string.ui_restore_command_failed) }
            }
        }.exceptionOrNull()
        if (error != null) withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_restore_theme_failed, error.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
