package com.trevornk.ramblr

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Pure decision/state logic for [LocalCleanupModelHolder] (#74), split out so the reload/idle/
 * discard rules are unit-testable without touching [LlamaCppInference] (whose companion `init`
 * loads a native library unavailable in plain JVM tests) -- the same pattern as
 * [CleanupWaterfallCursor]'s index/idle logic vs. its Android-owned reset triggers, and
 * [LlamaCompletionAccumulator] vs. [LlamaCppInference.complete].
 *
 * Tracks which model path (if any) is currently loaded and when it was last used; the holder
 * consults this to decide whether to reuse, reload, or idle-unload, and does the actual native
 * load/close work itself.
 */
class LocalCleanupModelSlot(private val idleUnloadMs: Long = IDLE_UNLOAD_MS) {
    private var loadedPath: String? = null
    private var lastUsedAtMs: Long = 0L

    val isLoaded: Boolean get() = loadedPath != null

    /** True when serving [requestedPath] requires a (re)load: nothing is held, or a different
     *  model is held (the user switched models in Settings since the last dictation). */
    fun needsReload(requestedPath: String): Boolean = loadedPath != requestedPath

    fun recordLoaded(path: String, nowMs: Long) {
        loadedPath = path
        lastUsedAtMs = nowMs
    }

    fun recordUsed(nowMs: Long) {
        lastUsedAtMs = nowMs
    }

    fun recordReleased() {
        loadedPath = null
        lastUsedAtMs = 0L
    }

    /** True when a held model has sat unused for the full idle window and should be unloaded.
     *  Never true for an empty slot. */
    fun idleExpired(nowMs: Long): Boolean =
        loadedPath != null && nowMs - lastUsedAtMs >= idleUnloadMs

    companion object {
        /** Matches [CleanupWaterfallCursor]'s 5-minute idle expiry: after this long with no
         *  dictation, the ~1 GB mapped model is not worth keeping resident. */
        const val IDLE_UNLOAD_MS = 5 * 60 * 1000L
    }
}

/**
 * Caches one loaded [LlamaCppInference] across dictations (#74). Loading a GGUF model is a
 * multi-second, ~1 GB disk-and-memory operation; before this holder existed,
 * [RealLocalInferenceEngine] paid it on every single cleanup call. The transcription side solved
 * the same problem with [TranscriberSlot]; this is the cleanup-side equivalent, shaped around
 * llama.cpp's constraint that one native context can't serve concurrent completions -- so instead
 * of a read/write lock, all access is serialized behind this object's monitor.
 *
 * Reuse across calls is only safe because LLMInference.cpp's `storeChats=false` path now clears
 * both its message history and its KV cache per completion (see the #74 divergence notes there);
 * before that fix, a second `complete()` on one instance embedded the first call's system prompt
 * and transcript in its own prompt, masked only by the old load-per-call pattern.
 *
 * The held instance is dropped on: model-path change (reload with the new model), any exception
 * out of the inference block (the native state may be dirty mid-completion -- reload fresh next
 * time rather than trust it), 5 idle minutes ([LocalCleanupModelSlot.IDLE_UNLOAD_MS]), service
 * destroy, and memory pressure (see WhisperAccessibilityService.onTrimMemory).
 */
object LocalCleanupModelHolder {
    private val slot = LocalCleanupModelSlot()
    @Volatile private var held: LlamaCppInference? = null
    private var idleUnload: ScheduledFuture<*>? = null
    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "local-cleanup-model-holder").apply { isDaemon = true }
        }
    }

    /**
     * Runs [block] with a loaded [LlamaCppInference] for [modelPath], loading it first if nothing
     * (or a different model) is held. Holding the monitor for the whole block is deliberate:
     * completions on one native context must be serialized, and this is the same coarse
     * serialization the waterfall executor already implies for the LOCAL_LLM step.
     *
     * If loading fails the exception propagates with nothing held; if [block] throws, the held
     * instance is closed and discarded before rethrowing, so the next call reloads fresh instead
     * of reusing possibly-corrupted native state (a deadline/cancel abort from
     * [LlamaCompletionAccumulator] lands here too -- rare enough that re-paying one load beats
     * trusting a context that stopped mid-generation).
     */
    @Synchronized
    fun <R> withInference(modelPath: String, block: (LlamaCppInference) -> R): R {
        if (slot.needsReload(modelPath)) {
            releaseHeld()
            val fresh = LlamaCppInference()
            val loadStartedAtMs = System.currentTimeMillis()
            try {
                fresh.load(modelPath)
            } catch (t: Throwable) {
                android.util.Log.e("LocalCleanupModelHolder", "Model load threw after ${System.currentTimeMillis() - loadStartedAtMs}ms for $modelPath", t)
                fresh.close()
                throw t
            }
            // Real, measured load duration (#97) -- replaces guesswork about whether a cold GGUF
            // load or the generation step is what's actually consuming the waterfall's time
            // budget. See CleanupWaterfallExecutor.CLEANUP_WATERFALL_HARD_CAP_MS for the budget
            // this is being measured against.
            android.util.Log.i("LocalCleanupModelHolder", "Model load took ${System.currentTimeMillis() - loadStartedAtMs}ms for $modelPath")
            held = fresh
            slot.recordLoaded(modelPath, System.currentTimeMillis())
        }
        val inference = checkNotNull(held)
        val genStartedAtMs = System.currentTimeMillis()
        val result = try {
            block(inference)
        } catch (t: Throwable) {
            android.util.Log.e("LocalCleanupModelHolder", "Generation threw after ${System.currentTimeMillis() - genStartedAtMs}ms", t)
            releaseHeld()
            throw t
        }
        android.util.Log.i("LocalCleanupModelHolder", "Generation took ${System.currentTimeMillis() - genStartedAtMs}ms")
        slot.recordUsed(System.currentTimeMillis())
        scheduleIdleUnload()
        return result
    }

    /** Closes and discards the held instance, if any. Blocks until any in-flight
     *  [withInference] finishes -- the native handle can't be freed mid-decode. */
    @Synchronized
    fun release() {
        releaseHeld()
    }

    /**
     * Pre-warms [modelPath] on [scheduler]'s background thread (#95), so a later real
     * [withInference] call during cleanup finds the model already resident instead of paying a
     * cold GGUF load (mmap + first-touch page faults on a several-hundred-MB file) out of the
     * cleanup waterfall's own [CLEANUP_WATERFALL_HARD_CAP_MS] time budget -- on a phone, that
     * cold load alone was observed to consume the *entire* budget, leaving no time for actual
     * generation and making every first-dictation-after-launch (or first dictation after the
     * 5-minute idle unload) fail even though [BoundedBlockingCall] (#83) correctly bounded it.
     *
     * Call this speculatively as soon as recording starts (see
     * [WhisperAccessibilityService.warmUpLocalCleanupModelIfNeeded]) so the load overlaps with
     * the user still talking + transcription, instead of starting only once cleanup itself runs.
     * Safe to call even when a matching model is already loaded -- [withInference] no-ops via
     * [LocalCleanupModelSlot.needsReload] under its own lock, so this never double-loads or blocks
     * a concurrent real completion for longer than the load itself takes. Any load failure here
     * is silently swallowed: the real attempt (and its real, user-visible error) still happens
     * inside [withInference] when cleanup actually runs.
     */
    fun warmUpAsync(modelPath: String) {
        scheduler.execute {
            runCatching { withInference(modelPath) { /* no-op body: load only, don't generate */ } }
                .onFailure { android.util.Log.e("LocalCleanupModelHolder", "warmUpAsync load failed for $modelPath", it) }
        }
    }

    /**
     * [release], but off-thread. For main-thread callers (service destroy, onTrimMemory):
     * closing must wait for any in-flight completion, which can take seconds, so the wait
     * happens on the holder's own daemon thread instead of stalling the main Looper. The
     * unsynchronized `held` fast-path read can race a concurrent load; missing that release is
     * benign -- the idle timer still unloads it.
     */
    fun releaseAsync() {
        if (held == null) return
        scheduler.execute { release() }
    }

    private fun releaseHeld() {
        idleUnload?.cancel(false)
        idleUnload = null
        // Failure to close is not actionable here beyond dropping the reference; the next
        // dictation loads a fresh instance either way.
        held?.let { runCatching { it.close() } }
        held = null
        slot.recordReleased()
    }

    private fun scheduleIdleUnload() {
        idleUnload?.cancel(false)
        idleUnload = scheduler.schedule(
            { unloadIfIdle() },
            LocalCleanupModelSlot.IDLE_UNLOAD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** The scheduled check re-verifies idleness under the lock: a dictation that started after
     *  the timer fired but before this ran has renewed the clock, and must not be unloaded. */
    @Synchronized
    private fun unloadIfIdle() {
        if (slot.idleExpired(System.currentTimeMillis())) {
            releaseHeld()
        }
    }
}
