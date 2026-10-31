package com.rikumi.colorosmod

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class PersistedHiddenNotification(val key: String, val packageName: String, val id: String)

/** 监听接口不返回重启后尚未重发的通知；系统仍保留它们的休眠键。 */
internal object HiddenNotificationSnoozes {
    fun parse(output: String, user: Int, now: Long): List<PersistedHiddenNotification> =
        output.lineSequence().mapNotNull { line ->
            val record = Regex("^\\s*key: (.+) until: ([0-9]+)\\s*$").matchEntire(line) ?: return@mapNotNull null
            if ((record.groupValues[2].toLongOrNull() ?: 0L) <= now) return@mapNotNull null
            val key = record.groupValues[1]
            val fields = key.split('|', limit = 4)
            if (fields.size < 4 || fields[0].toIntOrNull() !in setOf(user, -1)
                || !fields[1].matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*"))) return@mapNotNull null
            PersistedHiddenNotification(key, fields[1], fields[2])
        }.distinctBy { it.key }.toList()

    fun read(user: Int): List<PersistedHiddenNotification> {
        val output = executeHiddenNotificationCommand(
            "dumpsys notification --noredact | sed -n '/Pending snoozed notifications/,/^$/p'")
        check(output.contains("Pending snoozed notifications")) { "Notification service did not return its hidden rules" }
        return parse(output, user, System.currentTimeMillis())
    }

    fun restore(key: String, user: Int): List<PersistedHiddenNotification> {
        val quoted = "'" + key.replace("'", "'\\''") + "'"
        executeHiddenNotificationCommand("cmd notification unsnooze $quoted")
        repeat(5) {
            val remaining = read(user)
            if (remaining.none { it.key == key }) return remaining
            Thread.sleep(150)
        }
        error("System has not removed the hidden notification rule")
    }
}

internal fun executeHiddenNotificationCommand(command: String): String {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val result = AtomicReference<Result<String>?>()
    val reader = Thread({
        result.set(runCatching { process.inputStream.bufferedReader().use { it.readText() } })
    }, "hidden-notification-rules").also { it.isDaemon = true; it.start() }
    try {
        process.outputStream.close()
        check(process.waitFor(8, TimeUnit.SECONDS)) { "Notification rule command timed out" }
        reader.join(1000)
        val output = result.get()?.getOrThrow() ?: error("Cannot read notification rules")
        check(process.exitValue() == 0) { output.trim().ifEmpty { "Cannot access notification rules" } }
        return output
    } finally {
        process.destroyForcibly()
        runCatching { process.inputStream.close() }
    }
}
