package com.rikumi.colorosmod

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import io.github.libxposed.service.XposedService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Publishes the same font snapshot to filesystem consumers which never enter Xposed. */
internal object SystemFontMounts {
    private const val ROOT = "/data/adb/coloros-mod-fonts"
    private const val CONFIGURED = "font_mount_configured"
    private const val REQUESTED = "font_mount_requested"
    const val ENABLED = "system_font_mount_enabled"
    private val configs = listOf("/system/etc/fonts.xml", "/system/etc/font_fallback.xml",
        "/system_ext/etc/fonts_base.xml", "/product/etc/fonts_customization.xml")

    private val requests = AtomicLong()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "font-mount-sync").also { it.isDaemon = true }
    }

    // Saving a font selection finishes independently of the optional root transaction.
    fun schedule(context: Context, service: XposedService, manifest: String) {
        val app = context.applicationContext
        val request = requests.incrementAndGet()
        worker.execute {
            if (request == requests.get()) sync(app, service, manifest)
        }
    }

    @Synchronized
    private fun sync(context: Context, service: XposedService, manifest: String) {
        val app = context.applicationContext
        val prefs = app.settingsPrefs()
        if (!prefs.getBoolean(ENABLED, false)) { resume(app); return }
        runCatching {
            val de = app.createDeviceProtectedStorageContext()
            val directory = File(de.filesDir, "font_mounts").also { check(it.isDirectory || it.mkdirs()) }
            val script = app.assets.open("system-font-mounts.sh").use { it.readBytes() }
            val metadata = remote(service, manifest)
            val version = digest(metadata + script)
            val input = File(directory, version)
            // Immutable directories are never reused while a root process might still read them.
            if (!File(input, "complete").isFile) {
                check(!input.exists() || input.deleteRecursively())
                check(input.mkdirs())
                val entries = linkedMapOf<String, String>()
                for (line in metadata.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }) {
                    val fields = line.split('\t')
                    require(fields.size == 2)
                    require(fields[1].matches(Regex("native(font|cfg)_[0-9a-f]{64}\\.(font|xml)")))
                    require(entries.put(fields[0], fields[1]) == null)
                }
                require(entries.size <= 128 && configs[0] in entries)
                val targets = StringBuilder()
                for ((path, name) in entries) {
                    when {
                        path in configs -> {
                            val file = "cfg-${configs.indexOf(path)}.xml"
                            write(File(input, file), FontOverlayXml.mountSnapshot(remote(service, name), version))
                            targets.append(path).append('\t').append(file).append('\n')
                        }
                        path.matches(Regex("/system/fonts/colorosmod/[0-9a-f]{64}\\.ttf")) -> {
                            val bytes = remote(service, name)
                            require(digest(bytes) == path.substringAfterLast('/').removeSuffix(".ttf"))
                            write(File(input, "font-${path.substringAfterLast('/')}"), bytes)
                        }
                        // Native hooks also publish aliases for symlinked partitions; mount each XML once.
                        path == "/system/system_ext/etc/fonts_base.xml"
                            || path == "/system/product/etc/fonts_customization.xml" -> Unit
                        else -> error("Unexpected native font path: $path")
                    }
                }
                write(File(input, "configs.list"), targets.toString().toByteArray())
                write(File(input, "complete"), byteArrayOf())
            }
            val installer = backendInstaller(app, script)
            check(prefs.edit().putBoolean(REQUESTED, true).commit())
            val command = installBackend(installer) +
                "(" + loggedWorker("/system/bin/sh $ROOT/backend.sh apply ${quote(input.absolutePath)} $version", "apply") + ")"
            execute(command, 90)
            check(prefs.edit().putBoolean(CONFIGURED, true).commit())
        }.onFailure {
            AppLog.log("WebView font mount failed: $it")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, R.string.ui_font_mount_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Boot recovery replays the root-owned snapshot and does not need a live Xposed service. */
    @Synchronized
    fun resume(context: Context) {
        runCatching {
            val prefs = context.settingsPrefs()
            if (!prefs.getBoolean(CONFIGURED, false) && !prefs.getBoolean(REQUESTED, false)) return
            if (!prefs.getBoolean(ENABLED, false)) {
                restore(context)
                return
            }
            // The detached, root-owned worker can finish even after the broadcast receiver exits.
            execute(installBackend(backendInstaller(context)) +
                "( (" + loggedWorker("/system/bin/sh $ROOT/backend.sh resume", "boot") + ") >/dev/null 2>&1 </dev/null &)", 7)
        }.onFailure { AppLog.log("WebView font mount recovery failed: $it") }
    }

    /** Used when retiring the filesystem backend; only mounts owned by this backend are detached. */
    @Synchronized
    fun restore(context: Context) {
        // Upgrade the worker even when disabling, so old namespace bugs cannot block restore.
        execute(installBackend(backendInstaller(context)) + "/system/bin/sh $ROOT/backend.sh restore", 90)
        check(context.settingsPrefs().edit().putBoolean(CONFIGURED, false).putBoolean(REQUESTED, false).commit())
    }

    private fun backendInstaller(context: Context, bytes: ByteArray? = null): File {
        val directory = File(context.createDeviceProtectedStorageContext().filesDir, "font_mounts")
            .also { check(it.isDirectory || it.mkdirs()) }
        val script = bytes ?: context.assets.open("system-font-mounts.sh").use { it.readBytes() }
        val installer = File(directory, "backend-${digest(script)}.sh")
        if (!installer.isFile) write(installer, script)
        return installer
    }

    private fun installBackend(installer: File) =
        "umask 077; mkdir -p $ROOT && chmod 0700 $ROOT && " +
        "cp ${quote(installer.absolutePath)} $ROOT/backend.new.${'$'}${'$'} && " +
        "chmod 0700 $ROOT/backend.new.${'$'}${'$'} && mv $ROOT/backend.new.${'$'}${'$'} $ROOT/backend.sh && "

    // Per-process output avoids concurrent boot/apply shells truncating each other's logs
    // before the backend acquires its lock. Preserve the last failure across later successes.
    private fun loggedWorker(command: String, kind: String) =
        "log=$ROOT/$kind.${'$'}${'$'}.log; ($command) >\"${'$'}log\" 2>&1; result=${'$'}?; " +
        "cp \"${'$'}log\" $ROOT/last-$kind.log; " +
        "if [ \"${'$'}result\" != 0 ]; then cp \"${'$'}log\" $ROOT/last-failure.log; fi; " +
        "cat \"${'$'}log\"; rm -f \"${'$'}log\"; exit ${'$'}result"

    private fun remote(service: XposedService, name: String): ByteArray {
        require(name.matches(Regex("native(map|font|cfg)_[0-9a-f]{64}\\.(txt|font|xml)")))
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(name)).use { it.readBytes() }
        require(bytes.size <= FontFileInfo.MAX_BYTES)
        require(digest(bytes) == name.substringAfter('_').substringBefore('.'))
        return bytes
    }
    private fun write(file: File, bytes: ByteArray) {
        file.outputStream().use { it.write(bytes); it.fd.sync() }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun execute(command: String, timeoutSeconds: Long) {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { synchronized(output) { if (output.length < 16384) output.append(it).append('\n') } }
            }
        }.also { it.isDaemon = true; it.start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            // Do not kill a transaction half way through; a granted root worker may still complete it.
            error("Font mount worker timed out; inspect $ROOT/last-boot.log and retry")
        }
        reader.join(1000)
        check(process.exitValue() == 0) { synchronized(output) { output.toString().takeLast(4096) } }
    }
}
