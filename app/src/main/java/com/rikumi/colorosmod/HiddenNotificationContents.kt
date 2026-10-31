package com.rikumi.colorosmod

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import java.util.concurrent.Executors

internal data class LastNotificationContent(val title: String, val text: String, val postTime: Long)

internal fun notificationContentIdentity(key: String): String {
    val fields = key.split('|', limit = 4)
    return if (fields.size == 4 && fields[0].toIntOrNull() != null)
        fields.take(3).joinToString("|") else key
}

internal fun latestNotificationContent(
    previous: LastNotificationContent?,
    incoming: LastNotificationContent,
): LastNotificationContent {
    if (previous != null && incoming.postTime < previous.postTime) return previous
    return incoming.copy(title = incoming.title.ifBlank { previous?.title.orEmpty() },
        text = incoming.text.ifBlank { previous?.text.orEmpty() })
}

/** 每个应用、用户及通知编号仅保留最近一份内容，不保存逐次发送历史。 */
internal class HiddenNotificationContents(context: Context) : AutoCloseable {
    private val database = object : SQLiteOpenHelper(context.applicationContext, "notification_contents.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE contents (identity TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, post_time INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val writes = Executors.newSingleThreadExecutor { task ->
        Thread(task, "notification-content-save").also { it.isDaemon = true }
    }

    fun record(key: String, content: LastNotificationContent) {
        writes.execute {
            runCatching { remember(key, content) }
                .onFailure { AppLog.log("notification content save failed: $it") }
        }
    }

    @Synchronized
    fun lookup(key: String): LastNotificationContent? = database.readableDatabase.query("contents",
        arrayOf("title", "body", "post_time"), "identity = ?", arrayOf(notificationContentIdentity(key)),
        null, null, null).use { cursor ->
        if (cursor.moveToFirst()) LastNotificationContent(cursor.getString(0), cursor.getString(1), cursor.getLong(2)) else null
    }

    @Synchronized
    fun remember(key: String, content: LastNotificationContent) {
        if (content.title.isBlank() && content.text.isBlank()) return
        val previous = lookup(key)
        val latest = latestNotificationContent(previous, content)
        if (latest == previous) return
        val values = ContentValues().apply {
            put("identity", notificationContentIdentity(key))
            put("title", latest.title)
            put("body", latest.text)
            put("post_time", latest.postTime)
        }
        check(database.writableDatabase.insertWithOnConflict("contents", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    override fun close() {
        writes.execute { database.close() }
        writes.shutdown()
    }

    companion object {
        fun extract(notification: Notification, postTime: Long): LastNotificationContent {
            val extras = notification.extras
            fun text(key: String) = runCatching { extras.getCharSequence(key)?.toString().orEmpty().trim() }.getOrDefault("")
            val messages = runCatching {
                @Suppress("DEPRECATION")
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull {
                    (it as? Bundle)?.getCharSequence("text")?.toString()?.takeIf(String::isNotBlank)
                }?.lastOrNull().orEmpty()
            }.getOrDefault("")
            val lines = runCatching { extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty() }.getOrDefault("")
            val body = text(Notification.EXTRA_BIG_TEXT).ifBlank { text(Notification.EXTRA_TEXT) }
                .ifBlank { messages }.ifBlank { lines }.ifBlank { text(Notification.EXTRA_SUB_TEXT) }
            return LastNotificationContent(text(Notification.EXTRA_TITLE).ifBlank { text(Notification.EXTRA_TITLE_BIG) }.take(2000),
                body.take(2000), postTime)
        }
    }
}
