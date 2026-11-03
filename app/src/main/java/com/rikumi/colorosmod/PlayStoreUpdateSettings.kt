package com.rikumi.colorosmod

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object PlayStoreUpdateSettings {
    private fun execute(command: List<String>, timeout: Long = 55): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            process.outputStream.close()
            check(process.waitFor(timeout, TimeUnit.SECONDS)) { "Play Store setting command timed out" }
            val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.exitValue() == 0) { result.takeLast(1000) }
            return result
        } finally { process.destroy() }
    }

    // Reading the receipt needs no su, so the switch remains readable after temporary root is lost.
    fun enabled(): Boolean = execute(listOf("/system/bin/getprop", PlayStoreRecoveryCommand.DATA_PROPERTY), 5) == "1"

    private fun root(context: Context, operation: String) {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
            "com.rikumi.colorosmod.PlayStoreRecoveryCommand $operation"
        val result = execute(listOf("su", "-c", command))
        check(result.lineSequence().any { it == PlayStoreRecoveryCommand.OK }) { result.takeLast(1000) }
    }

    private fun reloadSystemServer(): Boolean {
        for (service in MainActivity.xposedServices.toList()) {
            if (service.apiVersion < io.github.libxposed.service.XposedService.API_102) continue
            val target = service.runningTargets.firstOrNull {
                it.uid == 1000 && it.processName in setOf("system_server", "android")
            } ?: continue
            val done = java.util.concurrent.CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicReference<io.github.libxposed.service.HotReloadResult>()
            service.hotReloadModule(target, null) { _, value -> result.set(value); done.countDown() }
            check(done.await(45, TimeUnit.SECONDS)) { "System framework reload timed out" }
            check(result.get()?.status == io.github.libxposed.service.HotReloadResult.Status.SUCCEEDED) {
                "System framework reload failed"
            }
            return true
        }
        return false
    }

    fun apply(context: Context, enabled: Boolean): Boolean {
        if (!enabled) {
            root(context, "disable")
            return false
        }
        val wasEnabled = enabled()
        root(context, "enable")
        try {
            // Repair historical shadows while privileges are still available. Clean devices
            // can configure the vendor data file even without an active Xposed framework.
            val alreadyPersisted = runCatching { root(context, "check-current") }.isSuccess
            if (!alreadyPersisted) {
                check(reloadSystemServer()) { "System framework is required to repair the existing package record" }
                root(context, "verify-current")
            }
            return true
        } catch (error: Exception) {
            if (!wasEnabled) runCatching { root(context, "disable") }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}

@Composable
internal fun PlayStoreUpdateRow(
    context: Context,
    onChanged: () -> Unit,
    developerConfirmedSubtitle: String? = null,
) {
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
        developerConfirmedSubtitle = developerConfirmedSubtitle,
        checked = checked,
        enabled = loaded && !busy,
        onCheckedChange = { target ->
            busy = true
            scope.launch {
                try {
                    checked = withContext(Dispatchers.IO) { PlayStoreUpdateSettings.apply(context, target) }
                    onChanged()
                    Toast.makeText(context, R.string.ui_play_store_update_restart, Toast.LENGTH_LONG).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    AppLog.log("Play Store setting write failed: $error")
                    Toast.makeText(context, R.string.ui_play_store_update_failed, Toast.LENGTH_LONG).show()
                    runCatching { withContext(Dispatchers.IO) { PlayStoreUpdateSettings.enabled() } }
                        .onSuccess { checked = it }
                } finally { busy = false }
            }
        },
    )
}
