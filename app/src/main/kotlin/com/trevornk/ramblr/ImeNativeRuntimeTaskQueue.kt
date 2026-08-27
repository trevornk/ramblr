package com.trevornk.ramblr

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-serial native-runtime queue. Local and streaming siblings initialize concurrently, but a
 * retired runtime's initialization and teardown fully finish before the next runtime can load.
 * Pending initialization is generation-gated so a runtime retired before its task starts does no
 * native work at all.
 */
internal class ImeNativeRuntimeTaskQueue(
    private val reportFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    internal class Initialization internal constructor(internal val generation: Long) {
        internal var retired = false
    }

    private val serial: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ramblr-ime-native-serial").apply { isDaemon = true }
    }
    private val loaders: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ramblr-ime-native-loader").apply { isDaemon = true }
    }
    private val schedulingLock = Any()
    private var generation = 0L
    private var latest: Initialization? = null

    fun enqueueInitialization(local: () -> Unit, streaming: () -> Unit): Initialization =
        synchronized(schedulingLock) {
            val initialization = Initialization(++generation)
            latest = initialization
            serial.execute {
                val mayStart = synchronized(schedulingLock) {
                    !initialization.retired && latest === initialization
                }
                if (!mayStart) return@execute

                val localFuture = loaders.submit(local)
                val streamingFuture = loaders.submit(streaming)
                val interrupted = await("local initialization", localFuture) or
                    await("streaming initialization", streamingFuture)
                if (interrupted) Thread.currentThread().interrupt()
            }
            initialization
        }

    fun enqueueTeardown(initialization: Initialization, action: () -> Unit) {
        synchronized(schedulingLock) {
            initialization.retired = true
            if (latest === initialization) latest = null
            serial.execute {
                try {
                    action()
                } catch (failure: Throwable) {
                    reportFailure("runtime teardown", failure)
                }
            }
        }
    }

    /** Waits through interruption so a still-running native constructor can never overlap teardown. */
    private fun await(task: String, future: java.util.concurrent.Future<*>): Boolean {
        var interrupted = false
        while (true) {
            try {
                future.get()
                return interrupted
            } catch (failure: InterruptedException) {
                interrupted = true
                reportFailure(task, failure)
                // Future.get clears the interrupt status when it throws. Keep waiting and restore
                // it only after both sibling constructors have fully stopped.
            } catch (failure: ExecutionException) {
                reportFailure(task, failure.cause ?: failure)
                return interrupted
            } catch (failure: Throwable) {
                reportFailure(task, failure)
                return interrupted
            }
        }
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
    private const val TAG = "ImeNativeRuntimeTasks"
    private val queue = ImeNativeRuntimeTaskQueue { task, failure ->
        android.util.Log.e(TAG, "$task failed", failure)
    }

    fun enqueueInitialization(local: () -> Unit, streaming: () -> Unit) =
        queue.enqueueInitialization(local, streaming)

    fun enqueueTeardown(
        initialization: ImeNativeRuntimeTaskQueue.Initialization,
        action: () -> Unit,
    ) = queue.enqueueTeardown(initialization, action)
}
