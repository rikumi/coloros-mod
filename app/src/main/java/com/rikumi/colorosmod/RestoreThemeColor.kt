package com.rikumi.colorosmod

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val themeRestoreStarted = AtomicBoolean(false)

/** 仅清理已移除功能留下的系统配色；没有备份时不调用 root。 */
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
                    ?.removePrefix("THEME_RESTORE_ERROR: ") ?: output.trim().takeLast(500).ifBlank { "无法执行恢复命令，请检查 root 权限" }
            }
        }.exceptionOrNull()
        if (error != null) withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(ctx, "原始主题恢复失败：${error.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
