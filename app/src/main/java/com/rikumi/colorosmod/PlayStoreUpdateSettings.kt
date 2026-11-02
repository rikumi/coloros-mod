package com.rikumi.colorosmod

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object PlayStoreUpdateSettings {
    private fun execute(command: String): String {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            process.outputStream.close()
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Play Store setting command timed out" }
            val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.exitValue() == 0) { result }
            return result
        } finally { process.destroy() }
    }

    fun enabled(): Boolean {
        val result = execute("getprop ${PlayStoreUpdatePolicy.PROPERTY}")
        check(result in setOf("", "0", "1")) { "Unexpected Play Store setting: $result" }
        return result == "1"
    }

    fun apply(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        check(execute("setprop ${PlayStoreUpdatePolicy.PROPERTY} $value && getprop ${PlayStoreUpdatePolicy.PROPERTY}") == value) {
            "Play Store setting was not saved"
        }
        return enabled
    }
}

@Composable
internal fun PlayStoreUpdateRow(context: Context, onChanged: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            checked = withContext(Dispatchers.IO) { PlayStoreUpdateSettings.enabled() }
            loaded = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            AppLog.log("Play Store setting read failed: $error")
            Toast.makeText(context, R.string.ui_play_store_update_failed, Toast.LENGTH_LONG).show()
        }
    }
    SettingsSwitchPreference(
        title = context.getString(R.string.ui_preserve_play_store_update),
        checked = checked,
        enabled = loaded && !busy,
        onCheckedChange = { target ->
            busy = true
            scope.launch {
                try {
                    checked = withContext(Dispatchers.IO) { PlayStoreUpdateSettings.apply(target) }
                    onChanged()
                    Toast.makeText(context, R.string.ui_play_store_update_restart, Toast.LENGTH_LONG).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    AppLog.log("Play Store setting write failed: $error")
                    Toast.makeText(context, R.string.ui_play_store_update_failed, Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        },
    )
}
