package com.rikumi.colorosmod

import android.content.Context
import android.util.Log
import java.io.File

/** Keep font failures available even when the firmware suppresses app logcat output. */
internal object FontFailureLog {
    @Volatile private var directory: File? = null

    @JvmStatic
    fun initialize(context: Context) {
        directory = context.createDeviceProtectedStorageContext().filesDir
    }

    @Synchronized
    fun record(operation: String, error: Throwable) {
        Log.e("ColorOSMod", operation, error)
        runCatching {
            val root = directory ?: return
            val file = File(root, "font-failures.log")
            if (file.length() > 128 * 1024) {
                val previous = File(root, "font-failures.previous.log")
                if (previous.exists()) check(previous.delete())
                check(file.renameTo(previous))
            }
            file.appendText("\n${java.util.Date()} $operation\n${Log.getStackTraceString(error)}\n")
        }
    }
}
