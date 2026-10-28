package com.rikumi.colorosmod

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Build
import com.rikumi.colorosmod.hooks.SystemFontHooks
import io.github.libxposed.service.XposedService
import java.io.File
import java.security.MessageDigest

internal object NativeFontBridge {
    fun prepare(context: Context, service: XposedService, overrideKey: String? = null,
                overrideValue: Int? = null, overrideFile: File? = null): String {
        val de = context.createDeviceProtectedStorageContext()
        val directory = File(de.cacheDir, "native_font_metadata").also { check(it.isDirectory || it.mkdirs()) }
        val prefs = context.settingsPrefs()
        val available = service.listRemoteFiles().toMutableSet()
        val paths = linkedMapOf<String, String>()
        val selected = linkedMapOf<String, List<FontOverlayXml.Source>>()
        fun publish(prefix: String, suffix: String, bytes: ByteArray): String {
            val hash = digest(bytes)
            val name = "$prefix$hash$suffix"
            if (name !in available) {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use { output ->
                        output.channel.truncate(0); output.channel.position(0)
                        output.write(bytes); output.fd.sync()
                    }
                    available += name
                } catch (error: Exception) { runCatching { service.deleteRemoteFile(name) }; throw error }
            }
            return name
        }
        val roles = listOf("zh" to SystemFontHooks.ZH, "latin" to SystemFontHooks.LATIN, "mono" to SystemFontHooks.MONO, "emoji" to SystemFontHooks.EMOJI)
        for ((role, key) in roles) {
            val choice = if (key == overrideKey) requireNotNull(overrideValue) else prefs.getInt(key, if (role == "mono") -1 else 0)
            if ((role == "mono" && choice < 0) || (role == "emoji" && choice == 0)) continue
            // Default must explicitly undo any previous overlay, even when OEM replacement is disabled.
            if ((role == "zh" || role == "latin") && choice == 0) {
                selected[role] = SystemOppoFonts.find(role == "zh").map { file ->
                    FontOverlayXml.Source("../..${file.absolutePath}", FontFileInfo.read(file))
                }
                continue
            }
            if (role == "zh" && choice == 1) {
                selected[role] = SystemCjkFonts.find().map { file ->
                    FontOverlayXml.Source("../..${file.absolutePath}", FontFileInfo.read(file))
                }
                continue
            }
            val bytes: List<ByteArray> = if (role == "emoji") {
                listOf(EmojiFontStore.obtain(context, choice).readBytes())
            } else if (choice == SystemFontStore.customIndex(key)) {
                listOf((if (key == overrideKey && overrideFile != null) overrideFile
                    else File(de.filesDir, "system_fonts/$key.font")).readBytes())
            } else {
                val assets = when (role) {
                    "latin" -> listOf("clock_fonts/${if (choice == 1) "Inter" else "Manrope"}.ttf")
                    else -> {
                        val name = listOf("MapleMono", "JetBrainsMono", "FiraCode", "CascadiaCode")[choice.coerceIn(0, 3)]
                        if (choice == 2) listOf("system_fonts/$name.ttf")
                        else listOf("system_fonts/$name.ttf", "system_fonts/$name-Italic.ttf")
                    }
                }
                assets.map { context.assets.open(it).use { stream -> stream.readBytes() } }
            }
            val fonts = mutableListOf<FontOverlayXml.Source>()
            for (raw in bytes) {
                val data = if (role == "emoji") raw else EmojiFontPriority.apply(raw)
                val hash = digest(data)
                val path = "/system/fonts/colorosmod/$hash.ttf"
                paths[path] = publish("nativefont_", ".font", data)
                val file = File.createTempFile("metadata-", ".font", directory)
                try { file.writeBytes(data); fonts += FontOverlayXml.Source("colorosmod/$hash.ttf", FontFileInfo.read(file)) }
                finally { file.delete() }
            }
            selected[role] = fonts
        }
        for (path in listOf("/system/etc/fonts.xml", "/system/etc/font_fallback.xml", "/system_ext/etc/fonts_base.xml",
                            "/product/etc/fonts_customization.xml")) {
            val source = File(path)
            if (!source.isFile) continue
            val config = if (path.startsWith("/product/")) selected.mapValues { (_, fonts) ->
                fonts.map { FontOverlayXml.Source(if (it.path.startsWith("../../")) it.path else "../../system/fonts/${it.path}", it.faces) }
            } else selected
            val remote = publish("nativecfg_", ".xml", FontOverlayXml.rewrite(original(context, path), config))
            paths[path] = remote
            if (path.startsWith("/system_ext/") || path.startsWith("/product/")) paths["/system$path"] = remote
        }
        check("/system/etc/fonts.xml" in paths) { "System font configuration unavailable" }
        val manifest = paths.entries.joinToString("\n", postfix = "\n") { (path, remote) -> "$path\t$remote" }.toByteArray()
        return publish("nativemap_", ".txt", manifest)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    // Once mounted, File(path) is our previous output. Always rewrite the stock XML so resetting
    // one font choice also restores its original family. A firmware update gets a fresh baseline.
    @Synchronized
    private fun original(context: Context, path: String): ByteArray {
        val de = context.createDeviceProtectedStorageContext()
        val directory = File(de.filesDir, "font_mount_originals/${digest(Build.FINGERPRINT.toByteArray())}")
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, digest(path.toByteArray()) + ".xml")
        if (!file.isFile) {
            val bytes = File(path).readBytes()
            val xml = String(bytes, Charsets.UTF_8)
            check(!xml.contains("colorosmod/") && !xml.contains("coloros-mod-webview/")
                && !xml.contains("coloros-mod-fonts:")) {
                "Stock font configuration unavailable; restore font mounts before rebuilding metadata"
            }
            val temporary = File.createTempFile("original-", ".xml", directory)
            try {
                temporary.outputStream().use { it.write(bytes); it.fd.sync() }
                check(temporary.renameTo(file))
            } finally { temporary.delete() }
        }
        return file.readBytes()
    }
}
