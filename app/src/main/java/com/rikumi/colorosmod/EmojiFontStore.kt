package com.rikumi.colorosmod

import android.content.Context
import android.graphics.fonts.Font
import org.json.JSONArray
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.security.MessageDigest

/** Downloads pinned Android color fonts on demand and reuses verified cache entries. */
internal object EmojiFontStore {
    @Synchronized
    fun obtain(context: Context, choice: Int): File {
        require(choice in 1..4)
        val catalog = JSONArray(context.assets.open("emoji_fonts/catalog.json").bufferedReader().use { it.readText() })
        val item = catalog.getJSONObject(choice - 1)
        val hash = item.getString("sha256")
        val directory = File(context.createDeviceProtectedStorageContext().cacheDir, "emoji_fonts")
            .also { check(it.isDirectory || it.mkdirs()) }
        val target = File(directory, "$hash.font")
        if (target.isFile && target.length() == item.getLong("size") && digest(target) == hash) return target
        val temporary = File.createTempFile("download-", ".font", directory)
        val connection = URL(item.getString("url")).openConnection() as HttpURLConnection
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, R.string.ui_font_emoji_downloading, Toast.LENGTH_LONG).show()
            }
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "ColorOS-Mod")
            check(connection.responseCode == 200) { "Emoji font download failed: ${connection.responseCode}" }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= item.getLong("size") && total <= FontFileInfo.MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(temporary.length() == item.getLong("size") && digest(temporary) == hash) { "Emoji font integrity check failed" }
            val faces = FontFileInfo.read(temporary)
            check(faces.any { it.color }) { "Emoji font has no Android-compatible color tables" }
            faces.forEach { Font.Builder(temporary).setTtcIndex(it.index).build() }
            check(temporary.renameTo(target)) { "Cannot save Emoji font" }
            return target
        } finally { connection.disconnect(); temporary.delete() }
    }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
