package com.rikumi.colorosmod

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object InstallerSettings {
    const val RESTORE = "installer_restore_legacy"
    private const val PACKAGE = "com.oplus.appdetail"

    private fun execute(ctx: Context, command: String): String {
        val output = runRoot("$command\ninstaller_result=\$?\nprintf '\\n__installer_status__%s\\n' \"\$installer_result\"")
            ?: error(ctx.getString(R.string.installer_action_failed))
        val marker = output.lastIndexOf("\n__installer_status__")
        check(marker >= 0 && output.substring(marker).trim() == "__installer_status__0") {
            ctx.getString(R.string.installer_action_failed)
        }
        return output.substring(0, marker)
    }

    private fun packages(ctx: Context, options: String): Set<String> {
        val output = execute(ctx, "pm list packages $options --user 0 $PACKAGE")
        return output.lineSequence().map(String::trim).filter(String::isNotEmpty).map {
            require(it.startsWith("package:")) { ctx.getString(R.string.installer_action_failed) }
            it.removePrefix("package:")
        }.toSet()
    }

    fun restored(ctx: Context): Boolean = PACKAGE !in packages(ctx, "") || PACKAGE in packages(ctx, "-d")

    private fun operation(ctx: Context, operation: ManagedAppOperation) {
        val output = execute(ctx, operation.command(PACKAGE))
        check(operation.succeeded(PACKAGE, output)) { ctx.getString(R.string.installer_action_failed) }
    }

    fun apply(ctx: Context, enabled: Boolean): Boolean {
        val installed = PACKAGE in packages(ctx, "")
        if (enabled) {
            if (installed) operation(ctx, ManagedAppOperation.DISABLE)
        } else {
            if (!installed) operation(ctx, ManagedAppOperation.INSTALL_EXISTING)
            operation(ctx, ManagedAppOperation.ENABLE)
        }
        return restored(ctx).also { check(it == enabled) { ctx.getString(R.string.installer_action_failed) } }
    }
}

@Composable
internal fun InstallerRestoreRow(ctx: Context, onChanged: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            checked = withContext(Dispatchers.IO) { InstallerSettings.restored(ctx) }
            loaded = true
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Toast.makeText(ctx, ctx.getString(R.string.installer_action_failed), Toast.LENGTH_LONG).show()
        }
    }
    CouixSwitchPreference(
        title = ctx.getString(R.string.installer_restore_legacy),
        checked = checked,
        enabled = loaded && !busy,
        onCheckedChange = { target ->
            busy = true
            scope.launch {
                try {
                    checked = withContext(Dispatchers.IO) { InstallerSettings.apply(ctx, target) }
                    onChanged()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Toast.makeText(ctx, ctx.getString(R.string.installer_action_failed), Toast.LENGTH_LONG).show()
                    runCatching { withContext(Dispatchers.IO) { InstallerSettings.restored(ctx) } }
                        .onSuccess { checked = it }
                } finally { busy = false }
            }
        },
    )
}
