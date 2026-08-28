package com.trevornk.ramblr

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeModelReadyReloadTest {
    @Test fun `transcription download completion reloads the already active IME runtime`() {
        val queue = ImeNativeRuntimeTaskQueue()
        val registry = ActiveImeModelReadyReloadRegistry()
        var localLoads = 0
        var streamingLoads = 0
        try {
            val initialization = queue.enqueueInitialization(
                local = { localLoads++ },
                streaming = { streamingLoads++ },
            )
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))
            val active = ImeModelReadyReload(
                queue = queue,
                initialization = initialization,
                reloadLocal = { localLoads++ },
                reloadStreaming = { streamingLoads++ },
            )
            registry.register(active)

            registry.notifyModelReady(ModelDownloadWorker.ModelReloadKind.TRANSCRIPTION)
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))

            assertEquals(2, localLoads)
            assertEquals(1, streamingLoads)
        } finally {
            queue.close()
        }
    }

    @Test fun `streaming classification reloads only streaming while on-demand kinds and absent IME no-op`() {
        val queue = ImeNativeRuntimeTaskQueue()
        val registry = ActiveImeModelReadyReloadRegistry()
        var localReloads = 0
        var streamingReloads = 0
        try {
            registry.notifyModelReady(ModelDownloadWorker.ModelReloadKind.TRANSCRIPTION)
            val initialization = queue.enqueueInitialization({}, {})
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))
            val active = ImeModelReadyReload(
                queue = queue,
                initialization = initialization,
                reloadLocal = { localReloads++ },
                reloadStreaming = { streamingReloads++ },
            )
            registry.register(active)

            registry.notifyModelReady(ModelDownloadWorker.ModelReloadKind.STREAMING)
            registry.notifyModelReady(ModelDownloadWorker.ModelReloadKind.LOCAL_CLEANUP)
            registry.notifyModelReady(ModelDownloadWorker.ModelReloadKind.VAD)
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))

            assertEquals(0, localReloads)
            assertEquals(1, streamingReloads)
        } finally {
            queue.close()
        }
    }

    @Test fun `stale lifecycle unregister cannot remove a newer active runtime`() {
        val queue = ImeNativeRuntimeTaskQueue()
        val registry = ActiveImeModelReadyReloadRegistry()
        var staleReloads = 0
        var activeReloads = 0
        try {
            val staleInitialization = queue.enqueueInitialization({}, {})
            val stale = ImeModelReadyReload(
                queue, staleInitialization, { staleReloads++ }, {},
            )
            registry.register(stale)

            val activeInitialization = queue.enqueueInitialization({}, {})
            val active = ImeModelReadyReload(
                queue, activeInitialization, { activeReloads++ }, {},
            )
            registry.register(active)
            registry.unregister(stale)
            registry.notifyModelReady(ModelDownloadWorker.ModelReloadKind.TRANSCRIPTION)
            assertTrue(queue.awaitIdle(2, TimeUnit.SECONDS))

            assertEquals(0, staleReloads)
            assertEquals(1, activeReloads)
        } finally {
            queue.close()
        }
    }
}
