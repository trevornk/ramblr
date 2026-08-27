package com.trevornk.ramblr

import java.util.concurrent.Executors

/** Process-serial history writer so read-modify-write ordering is preserved without IME main I/O. */
internal object ImeHistoryWriteExecutor {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ramblr-ime-history").apply { isDaemon = true }
    }

    fun execute(action: () -> Unit) {
        executor.execute(action)
    }
}
