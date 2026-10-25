package com.rikumi.colorosmod

import android.content.Context

/** Publish through the system shell, without requesting the module's notification permission. */
internal object NotificationLayoutPreview {
    private const val TAG = "notification_layout_preview"

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun shell(command: String): String? = runCatching {
        // NotificationShellCmd attributes uid 2000 to com.android.shell. Executing as
        // uid 0 instead attributes it to the non-package name "root" on this ROM.
        // ProcessBuilder preserves localized UTF-8 text (runRoot writes ASCII bytes).
        val process = ProcessBuilder("su", "2000", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (process.waitFor() == 0) output else null
    }.getOrNull()

    @Synchronized
    fun sendIfAbsent(context: Context): Boolean {
        val active = shell("cmd notification list") ?: return false
        // StatusBarNotification keys are user|package|id|tag|uid. Also recognize
        // previews from the previous module-owned implementation, if still present.
        if (active.lineSequence().any { it.split('|').getOrNull(3) == TAG }) return true
        val title = quote(context.getString(R.string.ui_notification_layout_test_title))
        val text = quote(context.getString(R.string.ui_notification_layout_test_text))
        val expanded = quote(context.getString(R.string.ui_notification_layout_test_expanded))
        return shell("cmd notification post -S bigtext --bigtext $expanded -i @android:drawable/stat_notify_more -t $title ${quote(TAG)} $text") != null
    }
}
