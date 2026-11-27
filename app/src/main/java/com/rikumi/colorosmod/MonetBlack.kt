package com.rikumi.colorosmod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object MonetBlack {
    const val KEY = "system_monet_oled_black"
    private fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("monet_black", Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean(KEY, false)
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    @Synchronized
    fun apply(context: Context, enabled: Boolean) {
        check(Build.VERSION.SDK_INT >= 34) { "Android 14 or newer required" }
        val apk = context.applicationInfo.sourceDir
        val operation = if (enabled) "enable" else "disable"
        val command = "CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin " +
            "com.rikumi.colorosmod.MonetBlackCommand $operation ${quote(apk)}"
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            check(process.waitFor(90, TimeUnit.SECONDS)) { "Root command timed out" }
            val output = process.inputStream.readNBytes(8192).toString(Charsets.UTF_8)
            check(process.exitValue() == 0 && output.lineSequence().any { it.trim() == "MONET_BLACK_OK" }) {
                output.lineSequence().firstOrNull { it.startsWith("MONET_BLACK_ERROR:") }
                    ?: output.trim().takeLast(400).ifBlank { "Root command failed" }
            }
            check(preferences(context).edit().putBoolean(KEY, enabled).commit()) { "Cannot save Monet setting" }
        } finally { process.destroy() }
    }
    fun resume(context: Context) {
        if (enabled(context)) apply(context, true)
    }
}

class MonetBlackBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!MonetBlack.enabled(context)) return
        val result = goAsync()
        Thread {
            try { MonetBlack.resume(context.applicationContext) }
            catch (error: Exception) { android.util.Log.e("MonetBlack", "Cannot restore system Monet palette", error) }
            finally { result.finish() }
        }.start()
    }
}

@Composable
internal fun MonetBlackPreference(ctx: Context, onChanged: () -> Unit) {
    val context = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf(MonetBlack.enabled(context)) }
    var busy by remember { mutableStateOf(false) }
    SettingsSwitchPreference(
        title = context.getString(R.string.ui_system_monet_oled_black),
        enabled = !busy,
        checked = checked,
        onCheckedChange = { target ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { MonetBlack.apply(context, target) }
                        checked = target
                        onChanged()
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Toast.makeText(context, context.getString(R.string.ui_system_monet_oled_black_failed, error.message), Toast.LENGTH_LONG).show()
                    } finally { busy = false }
                }
            }
        },
    )
}
