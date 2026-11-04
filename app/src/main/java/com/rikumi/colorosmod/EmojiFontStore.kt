package com.rikumi.colorosmod

import android.content.Context
import android.graphics.fonts.Font
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.security.MessageDigest

/** Downloads color fonts on demand; ZIP entries are resolved from the current remote directory. */
internal object EmojiFontStore {
    @Synchronized
    fun obtain(context: Context, choice: Int, onDownload: ((Long, Long) -> Unit)? = null): File {
        val catalog = JSONArray(context.assets.open("emoji_fonts/catalog.json").bufferedReader().use { it.readText() })
        val item = (0 until catalog.length()).map { catalog.getJSONObject(it) }
            .firstOrNull { it.getInt("id") == choice } ?: error("Unknown emoji font choice: $choice")
        if (item.has("zipEntry")) return obtainArchive(context, item, onDownload)
        val hash = item.getString("sha256")
        val directory = File(context.createDeviceProtectedStorageContext().cacheDir, "emoji_fonts")
            .also { check(it.isDirectory || it.mkdirs()) }
        val target = File(directory, "$hash.font")
        FontOperation.report(FontOperation.Step.CHECK_CACHE, item.getString("name"))
        if (target.isFile && target.length() == item.getLong("size") && digest(target) == hash) return target
        onDownload?.invoke(0, item.getLong("size"))
        val temporary = File.createTempFile("download-", ".font", directory)
        val connection = URL(item.getString("url")).openConnection() as HttpURLConnection
        try {
            FontOperation.track(connection)
            if (onDownload == null) Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, R.string.ui_font_emoji_downloading, Toast.LENGTH_LONG).show()
            }
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "ColorOS-Mod")
            FontOperation.report(FontOperation.Step.REQUEST, item.getString("url"))
            val status = connection.responseCode
            check(status == 200) { "Emoji font download failed: $status" }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    var lastProgress = 0L
                    while (true) {
                        FontOperation.check()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= item.getLong("size") && total <= FontFileInfo.MAX_BYTES)
                        output.write(buffer, 0, count)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 100 || total == item.getLong("size")) {
                            onDownload?.invoke(total, item.getLong("size"))
                            lastProgress = now
                        }
                    }
                    output.fd.sync()
                }
            }
            FontOperation.report(FontOperation.Step.VERIFY_FONT, "SHA-256")
            check(temporary.length() == item.getLong("size") && digest(temporary) == hash) { "Emoji font integrity check failed" }
            FontOperation.report(FontOperation.Step.PARSE_FONT, item.getString("name"))
            val faces = FontFileInfo.read(temporary)
            check(faces.any { it.color }) { "Emoji font has no Android-compatible color tables" }
            faces.forEach { Font.Builder(temporary).setTtcIndex(it.index).build() }
            FontOperation.check()
            FontOperation.report(FontOperation.Step.SAVE_FONT, item.getString("name"))
            check(temporary.renameTo(target)) { "Cannot save Emoji font" }
            return target
        } finally { connection.disconnect(); temporary.delete() }
    }
    private fun obtainArchive(context: Context, item: JSONObject, onDownload: ((Long, Long) -> Unit)?): File {
        val url = item.getString("url")
        val entryName = item.getString("zipEntry")
        val directory = File(context.createDeviceProtectedStorageContext().cacheDir, "emoji_fonts")
            .also { check(it.isDirectory || it.mkdirs()) }
        val cacheKey = MessageDigest.getInstance("SHA-256").digest("$url\n$entryName".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val metadata = File(directory, "$cacheKey.json")
        val cached = runCatching { JSONObject(metadata.readText()) }.getOrNull()
        FontOperation.report(FontOperation.Step.CHECK_CACHE, entryName)
        val cachedFile = runCatching { cached?.optString("sha256")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?.let { File(directory, "$it.font") }?.takeIf {
                it.isFile && it.length() == cached.getLong("size") && digest(it) == cached.getString("sha256")
            } }.getOrNull()
        // Automatic service sync reuses the chosen snapshot; an explicit selection checks upstream.
        if (onDownload == null && cachedFile != null) return cachedFile
        var entry: RemoteZipFont.Entry? = null
        try {
            entry = RemoteZipFont.locate(url, entryName)
            if (cachedFile != null && cached?.optLong("size", -1) == entry.size && cached.optLong("crc", -1) == entry.crc)
                return cachedFile
        } catch (_: RemoteZipFont.RangeUnsupported) { /* Fall back to streaming the current ZIP. */ }
        val temporary = File.createTempFile("download-", ".font", directory)
        try {
            val progress = RemoteZipFont.Progress { done, total -> onDownload?.invoke(done, total) }
            val descriptor = if (entry != null) {
                try {
                    RemoteZipFont.download(url, entry, temporary, progress)
                    longArrayOf(entry.size, entry.crc)
                } catch (_: RemoteZipFont.RangeUnsupported) {
                    RemoteZipFont.downloadWhole(url, entryName, temporary, progress)
                }
            } else RemoteZipFont.downloadWhole(url, entryName, temporary, progress)
            FontOperation.report(FontOperation.Step.PARSE_FONT, entryName)
            val faces = FontFileInfo.read(temporary)
            check(faces.any { it.color }) { "Emoji font has no Android-compatible color tables" }
            faces.forEach { Font.Builder(temporary).setTtcIndex(it.index).build() }
            FontOperation.report(FontOperation.Step.VERIFY_FONT, "SHA-256")
            val hash = digest(temporary)
            val target = File(directory, "$hash.font")
            FontOperation.check()
            FontOperation.report(FontOperation.Step.SAVE_FONT, entryName)
            check(temporary.renameTo(target)) { "Cannot save Emoji font" }
            val record = JSONObject().put("sha256", hash).put("size", descriptor[0]).put("crc", descriptor[1])
            val newMetadata = File.createTempFile("metadata-", ".json", directory)
            try {
                newMetadata.outputStream().use { it.write(record.toString().toByteArray()); it.fd.sync() }
                check(newMetadata.renameTo(metadata)) { "Cannot save emoji cache metadata" }
            } finally { newMetadata.delete() }
            return target
        } finally { temporary.delete() }
    }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                FontOperation.check()
                val count = input.read(bytes); if (count < 0) break
                hash.update(bytes, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
