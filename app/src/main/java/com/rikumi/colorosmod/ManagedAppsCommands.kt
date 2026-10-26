package com.rikumi.colorosmod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.text.Collator

internal data class ManagedAppEntry(
    val pkg: String,
    val label: String?,
    val uninstalled: Boolean,
    val icon: Bitmap?,
    val disabled: Boolean = false,
)

private fun runManagedAppCommand(ctx: Context, command: String): String {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    try {
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        check(code == 0) {
            output.trim().ifEmpty { ctx.getString(R.string.app_management_exit_error, code) }
        }
        return output
    } finally { process.destroy() }
}

private fun parseManagedPackages(ctx: Context, output: String): List<ManagedPackage> = try {
    AppManagementPolicy.parsePackages(output)
} catch (error: IllegalArgumentException) {
    throw IllegalStateException(ctx.getString(R.string.app_management_invalid_list, error.message.orEmpty()), error)
}

internal fun loadDisabledManagedApps(ctx: Context): List<ManagedAppEntry> {
    val user = AppManagementPolicy.USER_ID
    val marker = "---COLOROSMOD-APPS---"
    val output = runManagedAppCommand(ctx,
        "pm list packages -f -d --user $user && echo $marker && " +
            "pm list packages -f -u --user $user && echo $marker && pm list packages -f --user $user")
    val sections = output.lineSequence().fold(mutableListOf(StringBuilder())) { parts, line ->
        if (line.trim() == marker) parts.add(StringBuilder()) else parts.last().append(line).append('\n')
        parts
    }
    check(sections.size == 3) { ctx.getString(R.string.app_management_invalid_list, output.trim()) }
    val (disabled, all, installed) = sections.map { parseManagedPackages(ctx, it.toString()) }
    val lists = AppManagementPolicy.partition(disabled, all, installed)
    return sortManagedApps(ctx,
        lists.disabled.map { managedAppEntry(ctx, it, false) } +
            lists.uninstalled.map { managedAppEntry(ctx, it, true) })
}

internal fun loadSystemManagedApps(ctx: Context): List<ManagedAppEntry> {
    val user = AppManagementPolicy.USER_ID
    val marker = "---COLOROSMOD-DISABLED---"
    val output = runManagedAppCommand(ctx,
        "pm list packages -f -s --user $user && echo $marker && pm list packages -f -d -s --user $user")
    val sections = output.split(marker, limit = 2)
    check(sections.size == 2) { ctx.getString(R.string.app_management_invalid_list, output.trim()) }
    val disabled = parseManagedPackages(ctx, sections[1]).map { it.packageName }.toSet()
    return sortManagedApps(ctx, parseManagedPackages(ctx, sections[0]).map {
        managedAppEntry(ctx, it, false).copy(disabled = it.packageName in disabled)
    })
}

internal fun executeManagedAppOperation(ctx: Context, app: ManagedAppEntry, operation: ManagedAppOperation) {
    val command = operation.command(app.pkg)
    val output = runManagedAppCommand(ctx, command)
    check(operation.succeeded(app.pkg, output)) {
        output.trim().ifEmpty { ctx.getString(R.string.app_management_no_success) }
    }
}

private fun sortManagedApps(ctx: Context, apps: List<ManagedAppEntry>): List<ManagedAppEntry> {
    val collator = Collator.getInstance(ctx.resources.configuration.locales[0])
    return apps.sortedWith { a, b ->
        val result = collator.compare(a.label ?: a.pkg, b.label ?: b.pkg)
        if (result == 0) a.pkg.compareTo(b.pkg) else result
    }
}

private fun managedAppEntry(ctx: Context, pkg: ManagedPackage, uninstalled: Boolean): ManagedAppEntry {
    val pm = ctx.packageManager
    return runCatching {
        @Suppress("DEPRECATION") val info = pm.getPackageArchiveInfo(pkg.apkPath, 0)
        val ai = info?.applicationInfo
        if (ai != null) { ai.sourceDir = pkg.apkPath; ai.publicSourceDir = pkg.apkPath }
        val label = ai?.let { pm.getApplicationLabel(it).toString().takeIf(String::isNotBlank) }
        val drawable = runCatching { if (ai == null) pm.defaultActivityIcon else pm.getApplicationIcon(ai) }
            .getOrElse { pm.defaultActivityIcon }
        ManagedAppEntry(pkg.packageName, label, uninstalled, managedIcon(drawable))
    }.getOrElse {
        ManagedAppEntry(pkg.packageName, null, uninstalled,
            runCatching { managedIcon(pm.defaultActivityIcon) }.getOrNull())
    }
}

private fun managedIcon(drawable: Drawable): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
    drawable.setBounds(0, 0, 64, 64)
    drawable.draw(Canvas(it))
}
