package com.trevornk.ramblr

/**
 * Reload endpoint for one live IME runtime. Requests are submitted to the same process-serial queue
 * as native initialization and teardown; the queue's initialization token rejects work after this
 * runtime loses its lifecycle or a newer runtime supersedes it.
 */
internal class ImeModelReadyReload(
    private val queue: ImeNativeRuntimeTaskQueue,
    private val initialization: ImeNativeRuntimeTaskQueue.Initialization,
    private val reloadLocal: () -> Unit,
    private val reloadStreaming: () -> Unit,
) {
    fun notifyModelReady(kind: ModelDownloadWorker.ModelReloadKind) {
        val reload = when (kind) {
            ModelDownloadWorker.ModelReloadKind.TRANSCRIPTION -> reloadLocal
            ModelDownloadWorker.ModelReloadKind.STREAMING -> reloadStreaming
            ModelDownloadWorker.ModelReloadKind.LOCAL_CLEANUP,
            ModelDownloadWorker.ModelReloadKind.VAD -> return
        }
        queue.enqueueReload(initialization, reload)
    }
}

/** Process-local active-IME registry. An absent IME deliberately makes download notification a no-op. */
internal class ActiveImeModelReadyReloadRegistry {
    @Volatile private var active: ImeModelReadyReload? = null

    fun register(reload: ImeModelReadyReload) {
        synchronized(this) { active = reload }
    }

    /** Identity-check prevents delayed teardown of an old service from clearing its replacement. */
    fun unregister(reload: ImeModelReadyReload) {
        synchronized(this) {
            if (active === reload) active = null
        }
    }

    fun notifyModelReady(kind: ModelDownloadWorker.ModelReloadKind) {
        active?.notifyModelReady(kind)
    }
}

/** Shared bridge from WorkManager to whichever Ramblr Voice service runtime is currently alive. */
internal object ProcessActiveImeModelReadyReload {
    private val registry = ActiveImeModelReadyReloadRegistry()

    fun register(reload: ImeModelReadyReload) = registry.register(reload)
    fun unregister(reload: ImeModelReadyReload) = registry.unregister(reload)
    fun notifyModelReady(kind: ModelDownloadWorker.ModelReloadKind) = registry.notifyModelReady(kind)
}
