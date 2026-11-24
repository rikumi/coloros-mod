package com.rikumi.colorosmod

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A granted listener can connect later than the activity resumes, especially after a reboot. */
internal class HiddenNotificationConnection(
    private val scope: CoroutineScope,
    private val waitForConnection: suspend (Long) -> Unit = { delay(it) },
) {
    private var job: Job? = null

    fun request(
        hasAccess: () -> Boolean,
        isConnected: () -> Boolean,
        rebind: () -> Unit,
        onFailure: () -> Unit,
    ) {
        if (job?.isActive == true) return
        job = scope.launch {
            for (wait in longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000)) {
                if (!hasAccess() || isConnected()) return@launch
                try { rebind() }
                catch (error: RuntimeException) { AppLog.log("notification listener rebind failed: $error") }
                waitForConnection(wait)
            }
            if (hasAccess() && !isConnected()) onFailure()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
