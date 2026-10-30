package com.rikumi.colorosmod

import android.content.Context
import android.graphics.fonts.Font
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.rikumi.colorosmod.hooks.SystemFontHooks
import io.github.libxposed.service.XposedService
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

internal object SystemFontStore {
    private val changes = Executors.newSingleThreadExecutor { task ->
        Thread(task, "font-settings-sync").also { it.isDaemon = true }
    }
    private var changeFailure: Throwable? = null

    // Enqueue from the UI callback, before launching a cancellable UI coroutine.
    fun enqueue(change: () -> Unit): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        changes.execute {
            try {
                change()
                changeFailure = null
                result.complete(Unit)
            } catch (error: Throwable) {
                changeFailure = error
                FontFailureLog.record("font settings operation failed", error)
                result.completeExceptionally(error)
            }
        }
        return result
    }

    fun whenReady(context: Context, action: () -> Unit, onFailure: (Throwable) -> Unit) {
        val app = context.applicationContext
        changes.execute {
            try {
                changeFailure?.let { throw IllegalStateException("Previous font change failed", it) }
                SystemFontMounts.awaitPending(app)
            } catch (error: Throwable) {
                onFailure(error)
                return@execute
            }
            action()
        }
    }

    class NotMonospaceFontException : IllegalArgumentException()
    private val slots = listOf(SystemFontHooks.ZH, SystemFontHooks.LATIN, SystemFontHooks.MONO, SystemFontHooks.EMOJI)
    fun isFont(key: String) = key in slots
    fun customIndex(key: String) = when (key) {
        SystemFontHooks.ZH -> 2
        SystemFontHooks.LATIN -> 3
        SystemFontHooks.EMOJI -> -2
        else -> 4
    }
    private fun directory(context: Context) = File(context.createDeviceProtectedStorageContext().filesDir, "system_fonts")
        .also { check(it.isDirectory || it.mkdirs()) }
    private fun cache(context: Context, key: String) = File(directory(context), "$key.font")
    private fun service() = MainActivity.xposedServices.firstOrNull()
        ?: error("Xposed service unavailable")

    @Synchronized
    fun sync(context: Context, service: XposedService) {
        val local = context.settingsPrefs()
        val remote = service.getRemotePreferences(SystemFontHooks.PREFS)
        val editor = remote.edit()
        for (key in slots) {
            val choice = local.getInt(key, if (key == SystemFontHooks.MONO) -1 else 0)
            val file = if (key == SystemFontHooks.EMOJI && choice > 0) EmojiFontStore.obtain(context, choice) else cache(context, key)
            if (file.isFile) editor.putString(key + "_file", publish(service, key, file))
            editor.putInt(key, choice)
        }
        editor.putBoolean(SystemFontHooks.DISABLE_REPLACEMENT, local.getBoolean(SystemFontHooks.DISABLE_REPLACEMENT, false))
        val manifest = NativeFontBridge.prepare(context, service)
        editor.putString("native_manifest", manifest)
        check(editor.commit()) { "Cannot save font settings" }
        SystemFontMounts.schedule(context, service, manifest)
    }

    @Synchronized
    fun syncReplacementSettings(context: Context) {
        val local = context.settingsPrefs()
        for (service in MainActivity.xposedServices) {
            runCatching {
                check(service.getRemotePreferences(SystemFontHooks.PREFS).edit()
                    .putBoolean(SystemFontHooks.DISABLE_REPLACEMENT, local.getBoolean(SystemFontHooks.DISABLE_REPLACEMENT, false)).commit())
            }.onFailure { AppLog.log("font replacement settings sync failed: $it") }.getOrThrow()
        }
    }

    @Synchronized
    fun select(context: Context, key: String, value: Int) {
        val service = service()
        val emoji = if (key == SystemFontHooks.EMOJI && value > 0) EmojiFontStore.obtain(context, value) else null
        val emojiRemote = emoji?.let { publish(service, key, it) }
        val manifest = NativeFontBridge.prepare(context, service, key, value)
        val editor = service.getRemotePreferences(SystemFontHooks.PREFS).edit().putInt(key, value)
            .putString("native_manifest", manifest)
        if (emojiRemote != null) editor.putString(key + "_file", emojiRemote)
        check(editor.commit()) {
            "Cannot save font settings"
        }
        setInt(context, key, value)
        SystemFontMounts.schedule(context, service, manifest)
        if (key == SystemFontHooks.EMOJI && value > 0) notifyReady(context)
    }

    @Synchronized
    fun import(context: Context, key: String, uri: Uri) {
        require(isFont(key) && key != SystemFontHooks.EMOJI)
        val app = context.applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            android.widget.Toast.makeText(app, R.string.ui_font_importing, android.widget.Toast.LENGTH_LONG).show()
        }
        val service = service()
        val temporary = File.createTempFile("import-", ".font", directory(context))
        var backup: File? = null
        var replaced = false
        var committed = false
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= FontFileInfo.MAX_BYTES) { "Font exceeds 100 MB" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            // Validate every TTC member with Android's parser before replacing the previous import.
            val faces = FontFileInfo.read(temporary)
            if (key == SystemFontHooks.MONO && faces.none { it.monospace }) throw NotMonospaceFontException()
            for (face in faces) Font.Builder(temporary).setTtcIndex(face.index).build()
            val remote = service.getRemotePreferences(SystemFontHooks.PREFS)
            val oldFile = remote.getString(key + "_file", null)
            val name = publish(service, key, temporary)
            val manifest = NativeFontBridge.prepare(context, service, key, customIndex(key), temporary)
            val current = cache(context, key)
            if (current.exists()) {
                backup = File.createTempFile("previous-", ".font", directory(context))
                current.copyTo(backup, overwrite = true)
            }
            check(temporary.renameTo(current)) { "Cannot save font" }
            replaced = true
            check(remote.edit().putString(key + "_file", name).putInt(key, customIndex(key))
                .putString("native_manifest", manifest).commit()) {
                "Cannot save font settings"
            }
            committed = true
            setInt(context, key, customIndex(key))
            SystemFontMounts.schedule(context, service, manifest)
            if (oldFile != null && oldFile != name) runCatching { service.deleteRemoteFile(oldFile) }
        } finally {
            if (replaced && !committed) {
                if (backup != null) check(backup.renameTo(cache(context, key))) { "Cannot restore previous font" }
                else cache(context, key).delete()
            }
            backup?.delete()
            temporary.delete()
        }
        notifyReady(context)
    }

    private fun notifyReady(context: Context) {
        SystemFontMounts.awaitPending(context)
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, R.string.ui_font_emoji_ready, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun publish(service: XposedService, key: String, file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val name = "${key}_$hash.font"
        if (name !in service.listRemoteFiles()) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use { output ->
                    output.channel.truncate(0)
                    output.channel.position(0)
                    file.inputStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
            } catch (error: Exception) {
                runCatching { service.deleteRemoteFile(name) }
                throw error
            }
        }
        return name
    }
}
