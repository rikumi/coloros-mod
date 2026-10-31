package com.rikumi.colorosmod

/** key 为应用包名、用户及通道 ID；只读取通道设置，不读取通知正文。 */
internal object HiddenNotificationChannels {
    fun parse(output: String): Map<Triple<String, Int, String>, String> {
        val result = mutableMapOf<Triple<String, Int, String>, String>()
        var app: Pair<String, Int>? = null
        output.lineSequence().forEach { line ->
            val setting = APP.matchEntire(line)
            if (setting != null) {
                val uid = setting.groupValues[2].toIntOrNull()
                app = uid?.takeIf { it >= 0 }?.let { setting.groupValues[1] to it / 100000 }
            } else if (app != null && !line.contains("mDeleted=true")) {
                val channel = CHANNEL.find(line) ?: return@forEach
                val id = channel.groupValues[1]
                val name = channel.groupValues[2]
                if (id.isNotBlank() && name.isNotBlank() && name != "null")
                    result[Triple(app!!.first, app!!.second, id)] = name
            }
        }
        return result
    }

    fun read(): Map<Triple<String, Int, String>, String> {
        val output = executeHiddenNotificationCommand(
            // NotificationChannel.dump(redacted=true) 会将通道名称截为 3 个字符。
            // 仍只返回通道设置段，不把通知正文带入应用。
            "dumpsys notification --noredact | sed -n '/^  Ranking Config:/,/^  Notification listeners:/p'")
        check(output.contains("Ranking Config:")) { "Notification service did not return its channels" }
        return parse(output)
    }

    private val APP = Regex("^\\s*AppSettings: ([A-Za-z0-9_.]+) \\((-?[0-9]+)\\).*${'$'}")
    private val CHANNEL = Regex("NotificationChannel\\{mId='(.*?)', mName=(.*?), mDescription=")
}
