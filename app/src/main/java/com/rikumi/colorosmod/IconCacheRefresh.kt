package com.rikumi.colorosmod

import android.content.Context
import android.os.Process
import android.widget.Toast
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val iconRefreshPending = AtomicBoolean(false)

@Composable
internal fun RefreshIconCacheRow(context: Context) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingsCategoryRow(
        icon = null,
        title = context.getString(if (busy) R.string.ui_refresh_icon_cache_running else R.string.ui_refresh_icon_cache),
        onClick = {
            if (iconRefreshPending.compareAndSet(false, true)) {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { refreshIconCaches() }
                        Toast.makeText(context, R.string.ui_refresh_icon_cache_done, Toast.LENGTH_LONG).show()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AppLog.log("Icon cache refresh failed: $error")
                        Toast.makeText(context, R.string.ui_refresh_icon_cache_failed, Toast.LENGTH_LONG).show()
                    } finally {
                        busy = false
                        iconRefreshPending.set(false)
                    }
                }
            }
        },
    )
}

private fun refreshIconCaches() {
    val process = ProcessBuilder("su", "-c", IconCacheRefreshCommands.command(Process.myUid() / 100000))
        .redirectErrorStream(true).start()
    try {
        process.outputStream.close()
        check(process.waitFor(45, TimeUnit.SECONDS)) { "Icon cache refresh timed out" }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0 && output.lineSequence().any { it.trim() == IconCacheRefreshCommands.DONE }) {
            output.trim().takeLast(1000).ifBlank { "Icon cache refresh was not acknowledged by Launcher" }
        }
    } finally { process.destroy() }
}
