package com.trevornk.ramblr

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-serial native-runtime queue. Local and streaming siblings initialize concurrently, but a
 * retired runtime's initialization and teardown fully finish before the next runtime can load.
 */
internal class ImeNativeRuntimeTaskQueue {
    private val serial: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ramblr-ime-native-serial").apply { isDaemon = true }
    }
    private val loaders: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ramblr-ime-native-loader").apply { isDaemon = true }
    }

    fun enqueueInitialization(local: () -> Unit, streaming: () -> Unit) {
        serial.execute {
            val localFuture = loaders.submit { runCatching(local) }
            val streamingFuture = loaders.submit { runCatching(streaming) }
            runCatching { localFuture.get() }
            runCatching { streamingFuture.get() }
        }
    }

    fun enqueueTeardown(action: () -> Unit) {
        serial.execute { runCatching(action) }
    }

    internal fun awaitIdle(timeout: Long, unit: TimeUnit): Boolean {
        val marker = java.util.concurrent.CountDownLatch(1)
        serial.execute { marker.countDown() }
        return marker.await(timeout, unit)
    }

    internal fun close() {
        serial.shutdownNow()
        loaders.shutdownNow()
    }
}

/** Shared across IME service recreation so old/new native ownership cannot overlap. */
internal object ProcessImeNativeRuntimeTasks {
    private val queue = ImeNativeRuntimeTaskQueue()

    fun enqueueInitialization(local: () -> Unit, streaming: () -> Unit) =
        queue.enqueueInitialization(local, streaming)

    fun enqueueTeardown(action: () -> Unit) = queue.enqueueTeardown(action)
}
