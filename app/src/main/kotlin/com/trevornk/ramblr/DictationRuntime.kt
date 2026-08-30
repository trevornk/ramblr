package com.trevornk.ramblr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import kotlin.concurrent.thread

/**
 * Host seam for [DictationRuntime] (#143 Phase 1): the minimal set of callbacks the extracted
 * dictation engine needs from whatever component is hosting it. Today the only implementation
 * lives in [WhisperAccessibilityService] (and, via inheritance, [SystemControlsAccessibilityService]);
 * a future IME host (#143 Phase 3) provides its own.
 *
 * The three UI-state callbacks ([onRecordingStarted], [onEnterTranscribingUi], [onIdleUi]) map
 * 1:1 onto the states the overlay ring already distinguishes (RECORDING red, TRANSCRIBING busy,
 * IDLE). The remaining callbacks are the host-owned pieces of the pipeline the runtime
 * orchestrates around: pre-recording teardown of host delivery state, streaming-preview
 * bookkeeping (the host owns the injected-node references), text delivery (the host owns the
 * injection/preview apparatus), and the foreground-package read (an accessibility API only an
 * a11y host can answer; an IME host may return null).
 *
 * Threading: every callback except [foregroundPackageName] is invoked on the main thread.
 * [foregroundPackageName] is invoked from the runtime's background transcription threads,
 * exactly as [WhisperAccessibilityService.currentForegroundPackageName] was called from
 * `handleTranscriptionResult`'s background thread before the extraction.
 */
interface RuntimeListener {
    /** Host-visible operational message (permission, busy lease, errors, limits). Main thread. */
    fun onUserMessage(message: String) {}

    /** Cleanup has begun after transcription. Main thread. */
    fun onCleaningStarted() {}

    /** Recording is about to start (permission + warm-ups already done): the host must resolve
     *  any still-pending preview and reset its per-recording streaming/delivery state, exactly
     *  as the pre-extraction `startRecording` prologue did. Main thread. */
    fun onRecordingStartRequested()

    /** The recorder failed to start (mic busy). Mirrors the pre-extraction failure path's
     *  `endStreamingSession()` call. Main thread. */
    fun onRecordingStartFailed()

    /** Recording is live: show the RECORDING ring state (red + pulse). Main thread. */
    fun onRecordingStarted()

    /** The pipeline entered TRANSCRIBING: show the busy ring state (grey + spinner). Main
     *  thread (called from `stopAndTranscribe` / the max-duration mint, both main-thread). */
    fun onEnterTranscribingUi()

    /** The pipeline is back at IDLE: show the idle ring state. Called from `resetToIdle`'s
     *  always-runs funnel, immediately before [onStreamingTeardown]. Main thread. */
    fun onIdleUi()

    /** The host part of the pre-extraction `teardownStreamingPreview()`: flush the previous
     *  pending streaming handoff and move the live streaming session (if any) into the pending
     *  handoff slot. The runtime ends the streaming recognizer's session right after this
     *  returns, preserving the original ordering. Main thread. */
    fun onStreamingTeardown()

    /** A new streaming-preview hypothesis is available (#29). The host decides whether/where to
     *  surface it (live field partial or feedback bubble). Main thread (posted). */
    fun onStreamingPartial(text: String)

    /** Provider-neutral cloud-live interim. IME may render it generation-safely; accessibility
     * deliberately inherits this no-op and remains final-only. Main thread. */
    fun onCloudLiveInterim(text: String) {}

    /**
     * A finished dictation result is ready to deliver. The host owns delivery: the a11y host
     * routes this through its candidate-scan injection (or preview-before-inject when [rawText]
     * is non-null and the toggle is on -- the exact pre-extraction branch). Invoked on the main
     * thread with the runtime's guard already checked; `resetToIdle` runs immediately after this
     * returns, preserving the original inject-then-reset ordering.
     *
     * [rawText] is non-null exactly when cleanup ran and succeeded (then [text] is the cleaned
     * candidate); [cleanupError] is non-null exactly when cleanup ran and failed (then [text] is
     * the raw transcript being injected as the fallback).
     */
    fun deliverText(
        text: String,
        rawText: String?,
        paidFallbackGroup: CleanupStepGroup?,
        cleanupError: String?,
        feedbackDurationMs: Long,
    )

    /** Best-effort package name of the foreground app, for per-app persona resolution (#103).
     *  May be called from background threads. An IME host may return null. */
    fun foregroundPackageName(): String?

    /** Whether this host/session permits retention of transcript-bearing diagnostics. */
    fun allowsTranscriptRetention(): Boolean = true
}

/**
 * The service-independent dictation engine (#143 Phase 1): record -> transcribe -> clean ->
 * deliver, extracted verbatim from [WhisperAccessibilityService] so a future IME host can drive
 * the same pipeline without an accessibility service. Pure refactor: every method body below was
 * moved from the service with names, comments, threading, and behavior intact -- the only edits
 * are the mechanical ones the move forces (`this`-as-Context becomes [context], host UI/delivery
 * calls become [listener] callbacks).
 *
 * The runtime owns: the recording state machine, the recording engine + optional per-recording
 * sessions (silence auto-stop VAD #108, AAC encoder #109), the transcription guard/token/
 * watchdog/pipeline-timing lifecycle, local + cloud transcription dispatch, the cleanup
 * waterfall invocation, junk/no-speech gating (#192), the transcriber slots and their trim/warm
 * lifecycle, and the streaming recognizer slot (decode only -- the host owns where partials go).
 *
 * The host keeps: overlay/ring UI, all text injection and preview/undo/clipboard state, history
 * recording (which happens at delivery time, inside the host's injection funnel), and
 * accessibility-only reads.
 *
 * Threading contract (as found, unchanged):
 *  - [onTap], [startRecording], [stopAndTranscribe], [cancelTranscription], [resetToIdle]:
 *    main thread.
 *  - The engine's `onFinished` -> [onRecordingFinished]: RecordingEngine reader thread.
 *  - [continueTranscription]: reader thread, or a fresh `thread {}` from the main-thread token
 *    resolutions.
 *  - [handleTranscriptionResult]: whatever background thread the transcriber's callback used;
 *    every UI/delivery mutation hops to [handler].
 *  - [reset]: any thread (hops).
 *  - [shutdown]: main thread (the host's onDestroy).
 *
 * [context] is whatever Context the host is (the service passes itself); the runtime only uses
 * Context-level APIs (prefs, cacheDir, system services, Toast), never service-only ones.
 */
class DictationRuntime internal constructor(
    private val context: Context,
    private val listener: RuntimeListener,
    private val leaseRegistry: DictationSessionLeaseRegistry = ProcessDictationSessionLeaseRegistry,
    /** Explicit internal seam: null in every shipped host, so this slice changes no default or
     * provider catalog. A later opt-in wires a configured provider factory here. */
    private val cloudLiveFactory: CloudLiveTranscriptionSessionFactory? = null,
    /** Test-only observation seam proving the preserved-PCM batch path is claimed once. */
    private val onCloudLiveBatchFallback: () -> Unit = {},
    /** Test seam: lets host-side unit tests substitute a fake engine at the capture boundary.
     *  The default is exactly the pre-extraction construction. */
    private val engineFactory: (File, RecordingStateMachine) -> RecordingEngine =
        { cacheDir, stateMachine -> RecordingEngine(cacheDir, stateMachine) },
) {

    companion object {
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        const val BUSY_MESSAGE = "Ramblr is already dictating from another input surface"

        /** Backstop if no transcription/cleanup callback ever fires; covers transcription + cleanup callTimeouts. */
        private const val WATCHDOG_TIMEOUT_MS = 400_000L
        private const val CLOUD_LIVE_FINAL_WAIT_MS = 2_500L
    }

    private val stateMachine = RecordingStateMachine()

    @Volatile private var recordingEngine: RecordingEngine? = null
    @Volatile private var sessionLease: DictationSessionLease? = null
    @Volatile private var shuttingDown = false
    @Volatile private var shutdownLeaseAwaitingReader: DictationSessionLease? = null

    private class CloudLiveAttempt(val lease: DictationSessionLease) {
        var session: CloudLiveTranscriptionSession? = null
        var terminal: CloudLiveTerminal? = null
        var recordingResult: RecordingEngine.Result? = null
        var token: Int = 0
        var claimed = false
        var finalWait: Runnable? = null
        /** Newest [CloudLiveTiming] seen from any callback (#233 item 10). Main-thread only, same
         *  as every other field here. Kept so a fallback that happens because NO terminal ever
         *  arrived can still report that setup landed and interims flowed -- otherwise the most
         *  interesting failure on a real device (mid-stream drop) would log nothing but nulls. */
        var lastTiming: CloudLiveTiming? = null
    }

    @Volatile private var cloudLiveAttempt: CloudLiveAttempt? = null

    /** Releases only the exact session captured by the caller; stale callbacks are harmless. */
    private fun releaseSessionLease(lease: DictationSessionLease?) {
        if (lease == null) return
        synchronized(this) {
            if (sessionLease !== lease) return
            sessionLease = null
        }
        leaseRegistry.release(lease)
    }

    /** Non-null only while a recording with silence-based auto-stop (#108, mode 1) active is in
     *  progress -- see [startRecording]/[onRecordingFinished]. Zero-cost when the feature is off
     *  or the VAD model isn't installed: this field simply stays null, no [SilenceAutoStopSession]
     *  (and therefore no native [com.k2fsa.sherpa.onnx.Vad]) is ever created. */
    @Volatile private var silenceAutoStopSession: SilenceAutoStopSession? = null

    /** Non-null only while a recording with compressed-upload encoding (#109) active is in
     *  progress -- see [startRecording]/[onRecordingFinished]. Zero-cost when [CompressedUploadToggle]
     *  is off: this field simply stays null, no [AacEncoderSession] (and therefore no MediaCodec/
     *  MediaMuxer) is ever created. */
    @Volatile private var aacEncoderSession: AacEncoderSession? = null

    private val guard = TranscriptionGuard()

    /**
     * Bounded partial wakelock across the recording+transcription window (M6 audit, 2026-08-26).
     * Screen-off long dictations otherwise risk the CPU being throttled mid local decode (the
     * multi-minute sherpa path), and OEM battery managers are quicker to silence the mic for an
     * app holding no wakelock. Lazily created once (PowerManager.newWakeLock), then reused for
     * every dictation; non-reference-counted so acquire/release are idempotent under the rapid
     * cancel->restart races #193 documents -- a re-acquire simply refreshes the timeout, and a
     * second release is a no-op instead of a RuntimeException.
     *
     * Deliberately minimal scope: a full foregroundServiceType="microphone" FGS migration is the
     * robust fix for OEM mic silencing and is tracked as future work in #204 -- this is the
     * bounded, low-risk slice of it.
     */
    private var transcriptionWakeLock: android.os.PowerManager.WakeLock? = null

    /** Leak backstop for [transcriptionWakeLock]: recording caps at 10 min and a worst-case
     *  local decode adds multi-minute tail, so 20 min comfortably covers one dictation while
     *  guaranteeing the OS drops the lock even if every release path were somehow missed. Each
     *  [acquireTranscriptionWakeLock] refreshes this window for the new dictation. */
    private val TRANSCRIPTION_WAKELOCK_TIMEOUT_MS = 20 * 60 * 1000L

    /** Acquires (or refreshes) the bounded partial wakelock for a dictation that just started
     *  recording. Main thread only (called from [startRecording]). Any failure is swallowed:
     *  a wakelock is an optimization for screen-off reliability, never worth failing a
     *  dictation over. */
    private fun acquireTranscriptionWakeLock() {
        runCatching {
            val lock = transcriptionWakeLock ?: (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ramblr:transcription")
                .apply { setReferenceCounted(false) }
                .also { transcriptionWakeLock = it }
            lock.acquire(TRANSCRIPTION_WAKELOCK_TIMEOUT_MS)
        }.onFailure { Log.w(TAG, "Couldn't acquire transcription wakelock", it) }
    }

    /** Releases the wakelock at a pipeline terminal state. isHeld-guarded (and the lock is
     *  non-reference-counted), so calling this from multiple teardown paths -- [resetToIdle]'s
     *  always-runs funnel and [shutdown]'s belt-and-suspenders -- is safe. */
    private fun releaseTranscriptionWakeLock() {
        runCatching {
            transcriptionWakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Log.w(TAG, "Couldn't release transcription wakelock", it) }
    }

    /** Per-process-launch unique prefix for [correlationIdFor] (real bug fix, 2026-07-17):
     *  [guard]'s underlying token is an in-process [java.util.concurrent.atomic.AtomicInteger]
     *  that always restarts at 1 on a fresh accessibility-service process (app update, OS memory
     *  pressure eviction, device reboot, or the user toggling the service off/on) -- all of which
     *  happen routinely on Android, not edge cases. Benchmark log analysis confirmed this
     *  collides real, unrelated dictations recorded hours or days apart under the identical
     *  "tok-1"/"tok-3"/etc correlationId, silently corrupting any per-dictation grouping done
     *  against [BenchmarkLogger]'s data. This value is captured once when the runtime is
     *  actually created and folded into every correlationId it mints, so IDs are unique across
     *  restarts while [guard]/[TranscriptionGuard] itself is untouched -- its only real job is
     *  same-process staleness detection via [TranscriptionGuard.isCurrent], which needs no
     *  cross-process uniqueness at all. */
    private val processLaunchId = System.currentTimeMillis()
    private fun correlationIdFor(token: Int) = "tok-$processLaunchId-$token"

    private val inFlightCall = InFlightCall()
    private val cleanupCursor = CleanupWaterfallCursor()

    /** Signature of the cleanup waterfall the [cleanupCursor]'s index was last recorded against, so
     *  a chain edit in Settings (which reshapes the step list) resets the position-based cursor
     *  rather than resuming at a now-different step (M3). */
    private var lastCleanupWaterfallSignature: String? = null

    @Volatile private var activeToken: Int = 0

    /** #115: wall-clock markers for the current dictation's user-perceived, stop-tap-to-injection
     *  timeline. Started the instant [activeToken] is minted (stop tap, or the max-duration
     *  auto-stop path's synthetic "stop") and consumed once in the host's `finishInjection` to
     *  write the end-to-end [PipelineStage] benchmark line. Every non-happy-path exit -- no
     *  speech detected, long-press cancel, watchdog timeout, transcription-chain failure --
     *  abandons the timeline instead of leaving it populated (H2, #192), so a later, unrelated
     *  injectText() call (e.g. the feedback bubble's raw-text retry) can never attribute stale
     *  timing to itself. A single slot (not a per-token map) is safe here because exactly one
     *  dictation is ever in flight at a time -- [guard]/[activeToken] already enforce that
     *  invariant everywhere else. Internal so the host's injection funnel can consume it. */
    internal val pipelineTiming = PipelineTimingSlot()

    private val handler = Handler(Looper.getMainLooper())

    /** The currently-armed transcription watchdog (#L14), held so it can be removed on normal
     *  completion/teardown instead of one accumulating in the handler queue per dictation. */
    private var watchdogRunnable: Runnable? = null

    // Local transcription engine (loaded lazily)
    private val transcriberSlot = TranscriberSlot<LocalTranscriber> { it.release() }
    private val transcriberLifecycle = TranscriberLifecycle(transcriberSlot)

    // Streaming live-preview engine (#29) — only loaded when the opt-in setting is on and the
    // streaming model is installed (see initStreamingModel/shouldUseStreamingPreview). Kept loaded
    // across recordings like transcriberSlot; only its per-recording OnlineStream is torn down
    // between dictations (see StreamingTranscriber.beginSession/endSession).
    private val streamingTranscriberSlot = TranscriberSlot<StreamingTranscriber> { it.release() }
    private val streamingTranscriberLifecycle = TranscriberLifecycle(streamingTranscriberSlot)

    // Set by onTrimMemory (#98) when the transcriber slots were released under memory pressure;
    // cleared once warmUpTranscribersIfTrimmed reloads them. Avoids reloading on every single
    // recording start -- only after a real trim actually emptied the slots.
    @Volatile private var transcribersTrimmed = false

    /** Current [RecordingStateMachine.State] of the pipeline. */
    fun currentState(): RecordingStateMachine.State = stateMachine.current()

    fun isRecording(): Boolean = stateMachine.isRecording()

    /** The primary reset trigger for [cleanupCursor], per ADR-0001 (#61) -- see the host's
     *  network-callback registration for when this fires. */
    internal fun onDefaultNetworkChanged() {
        cleanupCursor.reset()
    }

    internal fun initLocalModel() {
        val initialization = transcriberLifecycle.beginInitialization() ?: return
        val modelName = prefs().getString("model_name", "") ?: ""
        val newTranscriber = if (modelName.isBlank()) {
            // Auto-detect first available model
            val models = LocalTranscriber.availableModels(context)
            if (models.isNotEmpty()) {
                Log.i(TAG, "Auto-detected model: ${models.first()}")
                LocalTranscriber.create(context, models.first())
            } else null
        } else {
            LocalTranscriber.create(context, modelName)
        }
        // Swap in the new transcriber, then release the old one — waiting for any transcription
        // still in flight on it — so switching models never holds more than one native recognizer.
        val installed = transcriberLifecycle.install(initialization, newTranscriber)
        if (installed && newTranscriber != null) {
            Log.i(TAG, "Local transcription ready")
        } else if (installed) {
            Log.i(TAG, "No local model found, will use API")
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    /**
     * (Re)loads the streaming live-preview model (#29), gated on both the opt-in setting and the
     * streaming model being installed (see [shouldUseStreamingPreview]). Called from MainActivity
     * when either changes, mirroring [reloadModel]'s pattern for the offline model.
     */
    internal fun initStreamingModel() {
        val initialization = streamingTranscriberLifecycle.beginInitialization() ?: return
        val archive = prefs().getString("streaming_model_name", STREAMING_MODEL.archive) ?: STREAMING_MODEL.archive
        val model = ModelDownloader.resolveActiveModel(STREAMING_MODEL_CATALOG, archive)
        val enabled = shouldUseStreamingPreview(
            settingEnabled = prefs().getBoolean("streaming_preview_enabled", false),
            streamingModelInstalled = StreamingTranscriber.isAvailable(context, model)
        )
        val newTranscriber = if (enabled) StreamingTranscriber.create(context, model) else null
        val installed = streamingTranscriberLifecycle.install(initialization, newTranscriber)
        if (installed) {
            Log.i(TAG, if (newTranscriber != null) "Streaming preview ready" else "Streaming preview unavailable")
        }
    }

    /** Reload the streaming preview model (called from MainActivity when the toggle or the
     *  streaming model's install state changes). */
    fun reloadStreamingModel() { thread { initStreamingModel() } }

    /**
     * Under memory pressure, drop the cached local-cleanup model (#74) -- it's a pure cache, and
     * the next dictation reloads it. RUNNING_LOW is the threshold; every higher-numbered level
     * (RUNNING_CRITICAL, plus the UI_HIDDEN/BACKGROUND/MODERATE/COMPLETE band, which all signal
     * at least as much pressure or less foreground relevance) qualifies too.
     *
     * The transcriber slots ARE released here too, as of #98: see the host's onTrimMemory
     * override, which delegates the raw level here; the threshold check and slot release live
     * here exactly as they did in the service body. [warmUpTranscribersIfTrimmed] reloads them
     * the next time recording starts.
     */
    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            LocalCleanupModelHolder.releaseAsync()
            transcribersTrimmed = true
            // replace(null) takes the slot's write lock, which blocks until any in-flight
            // transcription (a full batch on a background thread, up to a 10-minute recording)
            // finishes. onTrimMemory runs on the main thread, so doing this inline would ANR the
            // accessibility service exactly when memory pressure coincides with active
            // transcription. Hop to a background thread, mirroring reloadModel() (H3).
            thread {
                transcriberSlot.replace(null)
                streamingTranscriberSlot.replace(null)
            }
        }
    }

    // --- State machine ---

    fun onTap() {
        when (stateMachine.current()) {
            RecordingStateMachine.State.IDLE -> startRecording()
            RecordingStateMachine.State.RECORDING -> stopAndTranscribe()
            RecordingStateMachine.State.TRANSCRIBING -> {}
        }
    }

    internal fun startRecording() {
        val lease = leaseRegistry.tryAcquire()
        if (lease == null) {
            toast(BUSY_MESSAGE)
            return
        }
        sessionLease = lease
        var recordingStarted = false
        var liveAttempt: CloudLiveAttempt? = null
        try {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("Grant audio permission in Ramblr app"); return
            }

            warmUpLocalCleanupModelIfNeeded()
            warmUpTranscribersIfTrimmed()
            warmUpCloudConnectionsIfNeeded()

        // Host-owned pre-recording teardown: resolve a still-pending preview (#40), end the
        // previous streaming session, flush the pending handoff, and reset the live-preview
        // bubble throttle -- verbatim the pre-extraction startRecording prologue, now behind the
        // listener seam because every one of those pieces is host delivery state.
            listener.onRecordingStartRequested()
            liveAttempt = beginCloudLiveAttempt(lease)
            val streamingActive = streamingTranscriberSlot.get() != null
            if (streamingActive) streamingTranscriberSlot.use { it.beginSession() }

        // Silence-based auto-stop (#108, mode 1): additive and opt-in -- a session (and the
        // native Vad it owns) is only ever created when the toggle is on AND the model is
        // actually installed. Either condition being false means zero VAD instantiation and
        // byte-for-byte identical recording behavior to before this feature existed.
            val autoStopSession = if (SilenceAutoStopToggle.isEnabled(context)) {
            ModelDownloader.vadModelFile(context, SILERO_VAD_MODEL)?.let { modelFile ->
                try {
                    SilenceAutoStopSession(
                        modelFile = modelFile,
                        thresholdMs = SilenceAutoStopThreshold.millisOrDefault(context),
                        onSilenceThresholdExceeded = { handler.post { stopAndTranscribe() } },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start silence auto-stop VAD session", e)
                    null
                }
            }
        } else null
            silenceAutoStopSession = autoStopSession

        // Compressed-upload AAC encoding (#109): additive and opt-in -- a session (and the
        // MediaCodec/MediaMuxer it owns) is only ever created when CompressedUploadToggle is on.
        // Unlike #108's VAD, no model download or extra precondition is needed: AAC-LC is a
        // built-in platform codec. Toggle off means zero AacEncoderSession instantiation and
        // byte-for-byte identical recording behavior to before this feature existed.
            val aacSession = if (CompressedUploadToggle.isEnabled(context)) {
            try {
                AacEncoderSession(context.cacheDir)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AAC encoder session", e)
                null
            }
        } else null
            aacEncoderSession = aacSession

            val existingOnChunk: (ByteArray, Int) -> Unit = when {
            streamingActive && autoStopSession != null && aacSession != null -> { buf, len ->
                handleStreamingChunk(buf, len)
                autoStopSession.onChunk(buf, len)
                aacSession.onChunk(buf, len)
            }
            streamingActive && autoStopSession != null -> { buf, len ->
                handleStreamingChunk(buf, len)
                autoStopSession.onChunk(buf, len)
            }
            streamingActive && aacSession != null -> { buf, len ->
                handleStreamingChunk(buf, len)
                aacSession.onChunk(buf, len)
            }
            autoStopSession != null && aacSession != null -> { buf, len ->
                autoStopSession.onChunk(buf, len)
                aacSession.onChunk(buf, len)
            }
            streamingActive -> ::handleStreamingChunk
            autoStopSession != null -> autoStopSession::onChunk
            aacSession != null -> aacSession::onChunk
            else -> { _, _ -> }
        }
            val onChunk: (ByteArray, Int) -> Unit = if (liveAttempt == null) existingOnChunk else { buf, len ->
                existingOnChunk(buf, len)
                // Cloud live is an optional tee. Its contract copies before returning; failure is
                // terminal to only that attempt and never interrupts the PCM file write.
                runCatching { liveAttempt.session?.sendPcm(buf, len) }
            }

            val engine = engineFactory(context.cacheDir, stateMachine)
            val started = engine.start(
            onFinished = { result ->
                // H1 (#193): release the per-recording LOCALS captured by this closure, never the
                // fields. A reader stalled in AudioRecord.read() can outlive a rapid
                // cancel->restart; by the time its onFinished runs, the fields already hold the
                // NEW recording's sessions, and releasing those would kill the new recording's
                // silence auto-stop and finalize its AAC encoder mid-recording. The fields are
                // only nulled when they still reference these exact instances -- the same
                // identity-guard pattern onRecordingFinished uses for `recordingEngine === engine`.
                autoStopSession?.release()
                if (silenceAutoStopSession === autoStopSession) silenceAutoStopSession = null
                val compressedFile = aacSession?.finish()
                aacSession?.release()
                if (aacEncoderSession === aacSession) aacEncoderSession = null
                val finalResult = if (compressedFile != null) result.copy(compressedFile = compressedFile) else result
                onRecordingFinished(engine, finalResult, lease)
            },
            onChunk = onChunk
        )
            if (!started) {
            // End the streaming session so a failed recorder start doesn't leak the OnlineStream
            // opened by beginSession() above until the next recording (L11).
            if (streamingActive) listener.onRecordingStartFailed()
            autoStopSession?.release()
            silenceAutoStopSession = null
            aacSession?.release()
            aacEncoderSession = null
            toast("Couldn't start recording — mic busy?")
                return
            }

            recordingStarted = true
            recordingEngine = engine
        // M6: recording is genuinely live -- open the bounded wakelock window that spans
        // recording + transcription; resetToIdle (the terminal-state funnel) closes it.
        // Placed after the `started` check so a failed recorder start never acquires.
            acquireTranscriptionWakeLock()
            listener.onRecordingStarted()
        } finally {
            if (!recordingStarted) {
                cancelCloudLiveAttempt(liveAttempt)
                releaseSessionLease(lease)
            }
        }
    }

    private fun beginCloudLiveAttempt(lease: DictationSessionLease): CloudLiveAttempt? {
        val factory = cloudLiveFactory ?: return null
        val attempt = CloudLiveAttempt(lease)
        return try {
            attempt.session = factory.create(object : CloudLiveTranscriptionListener {
                override fun onInterim(text: String, timing: CloudLiveTiming) {
                    handler.post {
                        if (cloudLiveAttempt !== attempt) return@post
                        // #233 item 10: record the marks even when this interim isn't shown (a
                        // claimed/stopped attempt still produced real setup + first-interim
                        // timing, and that is exactly what the device trial needs to read back).
                        attempt.lastTiming = timing
                        if (sessionLease === lease &&
                            stateMachine.current() == RecordingStateMachine.State.RECORDING && !attempt.claimed) {
                            listener.onCloudLiveInterim(text)
                        }
                    }
                }

                override fun onTerminal(result: CloudLiveTerminal) {
                    handler.post {
                        if (cloudLiveAttempt !== attempt || sessionLease !== lease || attempt.claimed) return@post
                        attempt.terminal = result
                        attempt.lastTiming = result.timing
                        resolveCloudLiveAttempt(attempt)
                    }
                }
            })
            // Overwriting the field is the LAST reference anyone holds to an incumbent, so it must
            // be cancelled first -- otherwise a rapid abandon->restart leaves an authenticated,
            // still-open microphone socket with no owner and no armed timeout (its catch block
            // below already got this right).
            cancelCloudLiveAttempt()
            cloudLiveAttempt = attempt
            val session = requireNotNull(attempt.session)
            session.connect()
            if (!session.startActivity()) throw IllegalStateException("Cloud-live start rejected")
            attempt
        } catch (e: Exception) {
            // Never fails the dictation -- cloud-live is an optional tee -- but a silent catch here
            // meant a misconfigured endpoint, bad model id or blank key (all init `require`s)
            // disabled the feature with zero diagnostics. Control flow is unchanged; this only
            // makes the cause visible.
            Log.w(TAG, "Cloud-live attempt could not start; continuing with the batch pipeline", e)
            if (cloudLiveAttempt === attempt) cloudLiveAttempt = null
            attempt.session?.let { session -> runCatching { session.cancel(); session.close() } }
            null
        }
    }

    /** Flips shared state; the reader thread notices, drains, tears down and hands off via [onRecordingFinished]. */
    internal fun stopAndTranscribe() {
        val lease = sessionLease ?: return
        if (!stateMachine.tryStartTranscribing()) return
        activeToken = guard.start()
        cloudLiveAttempt?.takeIf { it.lease === lease }?.let { attempt ->
            runCatching { attempt.session?.endActivity() }
        }
        pipelineTiming.start(PipelineTiming(stopTapAtMs = System.currentTimeMillis(), correlationId = correlationIdFor(activeToken)))
        armWatchdog(activeToken, lease)
        listener.onEnterTranscribingUi()
    }

    /**
     * Pre-warms the local cleanup model (#95) the instant recording starts, so its cold GGUF
     * load (mmap + first-touch page faults on a several-hundred-MB file) overlaps with the user
     * still talking and the transcription that follows, instead of starting only once cleanup
     * itself runs and eating into [CLEANUP_WATERFALL_HARD_CAP_MS]'s budget -- see
     * [LocalCleanupModelHolder.warmUpAsync]'s kdoc for the failure mode this fixes.
     *
     * Deliberately checked (not unconditional): only bothers when cleanup is actually enabled
     * and the configured waterfall would use LOCAL_LLM for at least one step, since otherwise
     * this would resident-load a multi-hundred-MB model into memory for a dictation that will
     * never touch it (e.g. cleanup off, or an all-cloud waterfall).
     */
    private fun warmUpLocalCleanupModelIfNeeded() {
        if (!PostProcessingToggle.shouldRunCleanup(PostProcessingToggle.isEnabled(context))) return
        val providerChain = ProviderChainStore.load(context)
        if (!providerChain.usesLocalLlm()) return
        val modelPath = ModelDownloader.localCleanupModelFile(context, LocalCleanupProvider.selectedModel(context))?.absolutePath
            ?: return
        LocalCleanupModelHolder.warmUpAsync(modelPath)
    }

    /**
     * Reloads the batch (and, if enabled, streaming) transcriber if [onTrimMemory] released them
     * under memory pressure (#98) -- mirrors [warmUpLocalCleanupModelIfNeeded]'s pre-warm timing:
     * this runs the instant recording starts, so a reload (typically well under a second for
     * these much-smaller-than-the-cleanup-LLM models) overlaps with the user still talking rather
     * than adding perceived latency at transcription time. No-ops on the far more common case
     * where no trim has happened since the last dictation.
     */
    private fun warmUpTranscribersIfTrimmed() {
        if (!transcribersTrimmed) return
        transcribersTrimmed = false
        thread { initLocalModel() }
        thread { initStreamingModel() }
    }

    /**
     * Pre-warms DNS/TCP/TLS for whatever cloud host(s) this dictation would actually call (#100
     * perceived-latency follow-up), the same "pay the cost while the user is still talking"
     * timing [warmUpLocalCleanupModelIfNeeded]/[warmUpTranscribersIfTrimmed] already use. Reads
     * the same [ProviderChainStore]/[CloudFeatureToggle] state the real transcription and cleanup
     * call sites resolve against, so this only opens connections a real call could actually use.
     *
     * The [ProviderCredentialStore] gate (#168) is what makes that last sentence true: without
     * it this warmed a host for a provider that has no key, which both real call paths refuse to
     * contact, so a Local-mode user with cleanup off and no key still opened a TLS connection to
     * api.openai.com on every dictation.
     */
    private fun warmUpCloudConnectionsIfNeeded() {
        val chain = ProviderChainStore.load(context)
        val transcriptionCandidates = ProviderChainRuntime.transcriptionCandidates(chain)
        val cleanupChain = ProviderChainRuntime.effectiveChainForCleanup(chain, CloudFeatureToggle.cleanupEnabled(context))
        val hosts = NetworkWarmup.hostsToWarm(transcriptionCandidates, cleanupChain) { kind ->
            ProviderCredentialStore.isConfigured(context, kind)
        }
        NetworkWarmup.warmUpAsync(hosts)
    }

    /** Long-press while TRANSCRIBING (see the host's overlay touch listener): abort the in-flight call and return to idle. */
    fun cancelTranscription() {
        if (stateMachine.current() != RecordingStateMachine.State.TRANSCRIBING) return
        val lease = sessionLease ?: return
        inFlightCall.cancel()
        // H2 (#192): this dictation will never reach finishInjection, so drop its timeline now --
        // otherwise the next unrelated injection would consume it as its own.
        pipelineTiming.abandon()
        resetToIdle(lease)
        toast("Transcription cancelled")
    }

    /** Backstop for a callback that never arrives (stalled socket, hung local model, etc). See #20. */
    private fun armWatchdog(token: Int, lease: DictationSessionLease) {
        // Remove any previously-armed watchdog so a fresh arm (e.g. the transcribe->cleanup handoff
        // arms a second one) doesn't leave the first runnable sitting in the handler queue for its
        // full 400s (L14). The runnable is held so resetToIdle/shutdown can remove it on normal
        // completion instead of letting one accumulate per dictation.
        cancelWatchdog()
        val runnable = Runnable {
            watchdogRunnable = null
            if (guard.isCurrent(token)) {
                inFlightCall.cancel()
                // H2 (#192): a timed-out dictation never reaches finishInjection; drop its
                // timeline so a later unrelated injection can't consume it.
                pipelineTiming.abandon()
                resetToIdle(lease)
                toast("Transcription timed out")
            }
        }
        watchdogRunnable = runnable
        handler.postDelayed(runnable, WATCHDOG_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    /** Common teardown for every path back to IDLE: normal completion, cancel, or watchdog. */
    internal fun resetToIdle(expectedLease: DictationSessionLease) {
        if (sessionLease !== expectedLease) return
        try {
            cancelCloudLiveAttempt()
            cancelWatchdog()
            guard.cancel()
            activeToken = 0
            // M6: the pipeline is terminal here -- injection finished, cancel, watchdog, or error
            // (reset(msg) funnels here too) -- so the recording+transcription wakelock window ends.
            // isHeld-guarded and non-reference-counted, so the paths where no dictation was running
            // (e.g. preview-before-inject's early resetToIdle) are harmless no-ops or early releases
            // that the next startRecording() re-acquires.
            releaseTranscriptionWakeLock()
            // #115: deliberately NOT abandoned here. resetToIdle runs immediately after beginPreview()
            // too (preview-before-inject, #40) -- well before the real injectText() call that resolves
            // the preview, possibly seconds later on a timeout. Abandoning here would silently drop
            // pipeline timing for every previewed dictation. Instead each non-happy-path exit
            // (cancel, watchdog, no-speech, reset(msg)) calls pipelineTiming.abandon() itself (H2,
            // #192), and the happy path consumes exactly once in the host's finishInjection().
            stateMachine.reset()
            listener.onIdleUi()
            teardownStreamingPreview()
        } finally {
            // Cancellation can reach IDLE while AudioRecord's reader is still draining. Its exact
            // lease stays held until onRecordingFinished confirms the microphone is released.
            if (recordingEngine?.isReaderTeardownPending() != true) {
                releaseSessionLease(expectedLease)
            }
        }
    }

    /** The runtime half of streaming teardown. Host/UI cleanup is always delivered on main; native
     *  stream release stays on the caller so asynchronous shutdown never routes UI callbacks from
     *  its worker thread. */
    private fun teardownStreamingPreview() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener.onStreamingTeardown()
        } else {
            handler.post { listener.onStreamingTeardown() }
        }
        streamingTranscriberSlot.use { it.endSession() }
    }

    /** Called on the reader thread once the AudioRecord has been drained and released. */
    private fun onRecordingFinished(
        engine: RecordingEngine,
        result: RecordingEngine.Result,
        lease: DictationSessionLease,
    ) {
        // Clear the reference only if it still points at THIS engine (H2): a late old-session
        // handoff must not null out the *new* engine's reference, or shutdown would skip its
        // teardown and leave the new reader keeping the mic hot after the service appears off.
        if (recordingEngine === engine) recordingEngine = null
        if (result.discarded) {
            // The attempt is abandoned with it: nothing downstream will ever claim or time it out
            // (setupTimeout is cancelled once setup lands, finalTimeout is only armed by
            // endActivity, and stopAndTranscribe never ran), so without this the authenticated
            // microphone socket stays open with no reference held anywhere.
            cancelCloudLiveAttemptForLeaseOnMain(lease)
            // During shutdown the host deliberately keeps ownership until reader teardown AND
            // cancellation finish below. Every other discarded handoff can release immediately.
            if (!shuttingDown) {
                releaseSessionLease(lease)
            } else if (shutdownLeaseAwaitingReader === lease) {
                shutdownLeaseAwaitingReader = null
                releaseSessionLease(lease)
            }
            return // service destroyed / recording forced off — nothing to transcribe
        }

        // #115: mark stop-tap -> reader-drain/PCM-handoff the instant it actually happens, on
        // this reader thread, rather than waiting for the main-thread token resolution below --
        // resolveLateRecordingOnMain's hop can add a real, variable delay that would otherwise be
        // silently folded into this measurement.
        pipelineTiming.markDrained(System.currentTimeMillis())

        val token = when {
            activeToken != 0 && guard.isCurrent(activeToken) -> activeToken
            result.stopReason == RecordingEngine.StopReason.MAX_DURATION -> {
                startMaxDurationTranscription(result, lease)
                return
            }
            else -> {
                // No valid token from this thread's view — but that view can be stale (#66): the
                // stop tap mints the token on the main thread *after* the RECORDING->TRANSCRIBING
                // CAS this reader thread reacted to, so deciding to discard here could silently
                // drop a real dictation. Resolve on the main thread instead, where token/state
                // are authoritative — mirroring startMaxDurationTranscription's pattern.
                handler.post { resolveLateRecordingOnMain(result, lease) }
                return
            }
        }
        continueWithCloudLiveOrBatch(result, token, lease)
    }

    /** Main-thread resolution for a recording that finished without a token (#66/#90) — see
     *  [resolveLateRecording] for the decision table. */
    private fun resolveLateRecordingOnMain(
        result: RecordingEngine.Result,
        lease: DictationSessionLease,
    ) {
        val token = activeToken
        when (resolveLateRecording(token, guard.isCurrent(token), stateMachine.current())) {
            LateRecordingResolution.CONTINUE_TRANSCRIPTION -> thread { continueWithCloudLiveOrBatch(result, token, lease) }
            LateRecordingResolution.DISCARD -> {
                // Abandoned without reaching resetToIdle: the attempt would otherwise keep an
                // authenticated, un-timed-out microphone socket open with no owner.
                cloudLiveAttempt?.takeIf { it.lease === lease }?.let(::cancelCloudLiveAttempt)
                result.pcmFile?.delete()
                result.compressedFile?.delete()
                releaseSessionLease(lease)
            }
            LateRecordingResolution.DISCARD_AND_RESET -> {
                // Mic error mid-recording (#90): the reader thread self-claimed TRANSCRIBING but
                // no transcription will run and no watchdog was armed — without this reset the
                // overlay stays stuck in TRANSCRIBING with taps as no-ops forever.
                result.pcmFile?.delete()
                result.compressedFile?.delete()
                resetRecordingError(
                    result.errorMessage?.let { "Recording error: $it" } ?: "Recording stopped unexpectedly",
                    lease,
                )
            }
        }
    }

    /**
     * The reader thread can hit the duration cap without a UI tap, so no token exists yet. Mint
     * that token on the main thread with a fresh state check; if the user/service already reset to
     * IDLE, discard the temp file instead of resurrecting a cancelled transcription.
     */
    private fun startMaxDurationTranscription(
        result: RecordingEngine.Result,
        lease: DictationSessionLease,
    ) {
        handler.post {
            if (stateMachine.current() != RecordingStateMachine.State.TRANSCRIBING || activeToken != 0) {
                // Same abandon-without-resetToIdle shape as the other discard paths.
                cloudLiveAttempt?.takeIf { it.lease === lease }?.let(::cancelCloudLiveAttempt)
                result.pcmFile?.delete()
                result.compressedFile?.delete()
                releaseSessionLease(lease)
                return@post
            }
            val token = guard.start()
            activeToken = token
            cloudLiveAttempt?.takeIf { it.lease === lease }?.let { attempt ->
                runCatching { attempt.session?.endActivity() }
            }
            // #115: max-duration auto-stop has no real user "stop tap" -- the cap itself is the
            // trigger -- so anchor the timeline here instead, at the moment this app-side decided
            // the dictation is over. drainAtMs is set to the same instant since the drain already
            // happened (this whole branch runs after onRecordingFinished already returned) before
            // any pipelineTiming existed to record it against.
            val nowMs = System.currentTimeMillis()
            pipelineTiming.start(PipelineTiming(stopTapAtMs = nowMs, correlationId = correlationIdFor(token), drainAtMs = nowMs))
            armWatchdog(token, lease)
            listener.onEnterTranscribingUi()
            toast("Recording limit reached (10 min) — transcribing…")
            thread { continueWithCloudLiveOrBatch(result, token, lease) }
        }
    }

    private fun continueWithCloudLiveOrBatch(
        result: RecordingEngine.Result,
        token: Int,
        lease: DictationSessionLease,
    ) {
        val attempt = cloudLiveAttempt
        if (attempt == null || attempt.lease !== lease) {
            continueTranscription(result, token, lease)
            return
        }
        handler.post {
            if (cloudLiveAttempt !== attempt || sessionLease !== lease || !guard.isCurrent(token)) {
                // This attempt can never be claimed now (recordingResult was never set and no
                // finalWait was armed, so resolveCloudLiveAttempt would bail forever) -- cancel it
                // rather than leaving an inert, still-open authenticated socket behind.
                cancelCloudLiveAttempt(attempt)
                result.pcmFile?.delete()
                result.compressedFile?.delete()
                return@post
            }
            attempt.recordingResult = result
            attempt.token = token
            resolveCloudLiveAttempt(attempt)
            if (!attempt.claimed && attempt.finalWait == null) {
                val timeout = Runnable {
                    attempt.finalWait = null
                    // No terminal ever arrived within the bounded wait -- the mid-stream drop /
                    // wedged-socket case, which reports no CloudLiveFailureReason of its own
                    // (#233 item 10). Distinguished from BATCH_SERVED_LIVE_FAILED so a device
                    // trial can tell "the session told us it broke" from "it went silent".
                    if (cloudLiveAttempt === attempt && !attempt.claimed) {
                        startCloudLiveBatchFallback(
                            attempt,
                            if (attempt.terminal == null) CloudLiveOutcome.BATCH_SERVED_NO_TERMINAL
                            else CloudLiveOutcome.BATCH_SERVED_LIVE_FAILED,
                        )
                    }
                }
                attempt.finalWait = timeout
                handler.postDelayed(timeout, CLOUD_LIVE_FINAL_WAIT_MS)
            }
        }
    }

    /** Main-thread arbitration point: preserved PCM is owned by exactly one of live success or the
     * unchanged batch pipeline. A terminal event alone cannot claim it until reader handoff. */
    private fun resolveCloudLiveAttempt(attempt: CloudLiveAttempt) {
        val result = attempt.recordingResult ?: return
        val terminal = attempt.terminal ?: return
        if (attempt.claimed || cloudLiveAttempt !== attempt || sessionLease !== attempt.lease) return
        when (terminal) {
            is CloudLiveTerminal.Success -> {
                if (terminal.text.isBlank() || result.pcmFile == null ||
                    isBelowMinimumDuration(result.pcmFile.length(), SAMPLE_RATE)) {
                    startCloudLiveBatchFallback(attempt, CloudLiveOutcome.BATCH_SERVED_UNUSABLE_FINAL)
                    return
                }
                attempt.claimed = true
                attempt.finalWait?.let(handler::removeCallbacks)
                attempt.finalWait = null
                cloudLiveAttempt = null
                runCatching { attempt.session?.close() }
                result.pcmFile.delete()
                result.compressedFile?.delete()
                logCloudLiveAttempt(attempt, CloudLiveOutcome.LIVE_DELIVERED)
                thread { handleTranscriptionResult(terminal.text, attempt.token, attempt.lease) }
            }
            is CloudLiveTerminal.Failure -> startCloudLiveBatchFallback(attempt, CloudLiveOutcome.BATCH_SERVED_LIVE_FAILED)
        }
    }

    /**
     * Writes this attempt's timing + outcome to the existing per-dictation [BenchmarkLogger] line
     * (#233 Phase 1 item 10).
     *
     * The reason this exists at all: the live->batch fallback is lossless, so on a real device a
     * live failure and a live success look identical from the outside -- the same text is
     * delivered the same way either way. Without a durable record, nobody can tell whether live
     * ever ran, which makes the device acceptance gate unfalsifiable.
     *
     * Correlated by [correlationIdFor] on the attempt's own token, i.e. the exact id the
     * transcription/cleanup/pipeline lines for this same dictation already use -- no second id
     * scheme. Emitted as its own JSONL line (not folded into another call site's line) because
     * live resolution happens strictly before the batch/cleanup lines are written and a line is
     * self-contained by design; a reader groups on correlationId, which is what that key is for.
     *
     * Length-only, like the rest of this log: durations, enum names and booleans, never the
     * interim or final text. Failure-isolated -- [BenchmarkLogger.log] already wraps its I/O in
     * runCatching on a daemon executor, and the extra runCatching here means even a malformed
     * derivation can't take the delivery path down with it.
     */
    private fun logCloudLiveAttempt(attempt: CloudLiveAttempt, outcome: CloudLiveOutcome) {
        runCatching {
            BenchmarkLogger.log(
                context = context,
                correlationId = correlationIdFor(attempt.token),
                cloudLive = cloudLiveBenchmarkStage(outcome, attempt.terminal, attempt.lastTiming),
            )
        }.onFailure { Log.w(TAG, "Couldn't record cloud-live benchmark timing", it) }
    }

    private fun startCloudLiveBatchFallback(attempt: CloudLiveAttempt, outcome: CloudLiveOutcome) {
        val result = attempt.recordingResult ?: return
        if (attempt.claimed || cloudLiveAttempt !== attempt || sessionLease !== attempt.lease) return
        attempt.claimed = true
        attempt.finalWait?.let(handler::removeCallbacks)
        attempt.finalWait = null
        cloudLiveAttempt = null
        runCatching { attempt.session?.close() }
        logCloudLiveAttempt(attempt, outcome)
        onCloudLiveBatchFallback()
        thread { continueTranscription(result, attempt.token, attempt.lease) }
    }

    private fun cancelCloudLiveAttempt(attempt: CloudLiveAttempt? = cloudLiveAttempt) {
        if (attempt == null) return
        if (cloudLiveAttempt === attempt) cloudLiveAttempt = null
        attempt.claimed = true
        attempt.finalWait?.let(handler::removeCallbacks)
        attempt.finalWait = null
        attempt.session?.let { session -> runCatching { session.cancel(); session.close() } }
    }

    /**
     * Cancels the live attempt belonging to [lease] from a path that abandons its recording
     * without reaching [resetToIdle] -- the reader thread's `discarded` handoff, and the
     * main-thread late/max-duration resolutions that discard.
     *
     * Lease-compared (never the bare field) so a stalled old reader's late handoff can't tear down
     * the attempt of the *new* dictation the user has already started -- the same identity guard
     * `recordingEngine === engine` uses. Hops to main because [cancelCloudLiveAttempt] touches
     * handler callbacks and the field the main-looper arbitration owns; already-on-main callers
     * just see a queued no-op after the field is cleared.
     */
    private fun cancelCloudLiveAttemptForLeaseOnMain(lease: DictationSessionLease) {
        handler.post { cloudLiveAttempt?.takeIf { it.lease === lease }?.let(::cancelCloudLiveAttempt) }
    }

    private fun continueTranscription(
        result: RecordingEngine.Result,
        token: Int,
        lease: DictationSessionLease,
    ) {
        val file = result.pcmFile
        if (file == null) { reset("No audio captured", token, lease); return }
        if (result.errorMessage != null) Log.e(TAG, "Recording ended with error: ${result.errorMessage}")

        // M3a (#192): a recording under the minimum duration floor (~300ms of PCM) cannot contain
        // usable speech -- discard it with the existing "No speech detected" UX *before* paying
        // for a full local decode or cloud upload that would only ever produce a blank transcript.
        if (isBelowMinimumDuration(file.length(), SAMPLE_RATE)) {
            Log.i(TAG, "Recording below ${MIN_RECORDING_DURATION_MS}ms floor (${file.length()} bytes); discarding")
            file.delete()
            result.compressedFile?.delete()
            reset("No speech detected", token, lease)
            return
        }

        val useLocal = prefs().getBoolean("use_local", true)
        val allowCloudFallback = DictationModeToggle.allowCloudFallback(context)
        // #109: the compressed .m4a (when the toggle was on and AacEncoderSession's encode
        // succeeded) only ever matters to a cloud upload -- the local transcriber reads raw PCM
        // samples directly, never a file upload, so it's discarded (never uploaded) on that path.
        val compressedFile = result.compressedFile

        when {
            useLocal && transcriberSlot.get() != null -> {
                compressedFile?.delete()
                transcribeLocal(file, token, lease)
            }
            // #98 UX follow-up: previously this fell straight through to transcribeApi() whenever
            // the local model wasn't loaded yet, regardless of *why* -- including the real-world
            // case of a fresh install where onboarding's model download is still in progress.
            // Since a local-mode user typically never entered a cloud API key, that produced a
            // confusing "Set API key in Ramblr app" error with no connection to the actual cause,
            // making the whole dictation feature look silently broken during exactly the moment
            // (onboarding's "Try it out" step) a new user is forming their first impression.
            // Give an honest, specific message instead of misrouting to a path that was never
            // configured -- unless #100's "fall back to cloud if on-device fails" toggle is on,
            // in which case a not-yet-downloaded local model is exactly the case that toggle
            // exists for.
            useLocal && allowCloudFallback -> transcribeApi(file, token, compressedFile, lease)
            useLocal -> {
                compressedFile?.delete()
                file.delete()
                reset("Local model still downloading — try again once it finishes", token, lease)
            }
            else -> transcribeApi(file, token, compressedFile, lease)
        }
    }

    /**
     * Current WorkManager state of [model]'s download, or null when no work has ever been
     * enqueued for it (#139).
     *
     * Queried synchronously because [transcribeLocal] already runs on its own background thread
     * -- never call this from the main thread. Any failure resolves to null, which makes
     * [VadModelProvisioning.shouldFetch] fall through to enqueueing; `ExistingWorkPolicy.KEEP`
     * then collapses the duplicate, so the safe direction is preserved.
     */
    private fun vadDownloadStateFor(model: Model): WorkInfo.State? = try {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ModelDownloadWorker.workName(model.archive))
            .get()
            .firstOrNull { !it.state.isFinished }
            ?.state
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read VAD download state; treating as not in flight", e)
        null
    }

    /**
     * Decodes the recording one VAD-detected speech segment at a time when the Silero VAD model
     * is available (#132), falling back to the original whole-file decode when it isn't.
     *
     * The segmented path is what keeps peak memory bounded by utterance length: the old path read
     * the take into one FloatArray and handed all of it to a single `OfflineStream`, which drove
     * ~2GB RSS on a 5:52 dictation and got the process OOM-killed before any text or error could
     * reach the user. Contrary to this method's previous doc comment, that FloatArray was never
     * the dominant allocation (~22MB for a 6-minute take) -- the single-shot native decode was.
     *
     * The fallback is deliberate rather than a hard failure: the VAD model is an optional ~1-2MB
     * download today (see [SILERO_VAD_MODEL]), so a user who has never enabled silence auto-stop
     * would otherwise lose local transcription entirely. Short takes are unaffected either way.
     * Since #139 this path also provisions that model itself, so the fallback is a transient
     * state for local-transcription users rather than a permanent one.
     *
     * PCM lifetime (M5 audit, 2026-08-26): [file] is deleted here on success (the transcript
     * exists, nothing downstream needs audio) and on failure of the *direct* pure-local path
     * ([onChainFailure] null -- nothing could retry, so keeping it would only leak cache). When
     * the provider-chain walk calls this as a candidate it passes [onChainFailure], taking the
     * failure-path PCM lifetime for itself: the file is kept alive so the next candidate can
     * still transcribe it, and [transcribeApi]'s `advanceOrGiveUp` deletes it once the chain is
     * exhausted -- the [TranscriptionChain.shouldDeletePcm] contract.
     */
    private fun transcribeLocal(
        file: File,
        token: Int,
        lease: DictationSessionLease,
        onChainFailure: ((String) -> Unit)? = null,
    ) {
        thread {
            // Benchmark-log timing starts before the read/decode too, so a failure there (rare,
            // but possible on a corrupt/truncated PCM file) still gets an honest latency instead
            // of one measured from a t0 that never ran (GH #100 benchmark logger).
            val benchmarkStartMs = System.currentTimeMillis()
            try {
                val vadModelFile = ModelDownloader.vadModelFile(context, SILERO_VAD_MODEL)
                // #139: provision the VAD model for local transcription itself, rather than
                // leaving it to the unrelated silence-auto-stop toggle (#108). Without this the
                // segmented decode below is unreachable for anyone who never enabled that
                // feature, silently pinning them to the unsegmented path #132 was filed to fix.
                // Asynchronous by design: this take still falls back below, and a later one gets
                // segmented decode once the ~644KB download lands.
                if (VadModelProvisioning.shouldFetch(
                        modelInstalled = vadModelFile != null,
                        downloadInFlight = ModelDownloadWorker.isInFlight(
                            vadDownloadStateFor(SILERO_VAD_MODEL)
                        ),
                    )
                ) {
                    Log.i(TAG, "VAD model missing — enqueueing download for segmented decode (#139)")
                    ModelDownloadWorker.enqueue(context, SILERO_VAD_MODEL)
                }
                val vad = vadModelFile?.let { SherpaVadHandle.create(it) }

                val t0 = System.currentTimeMillis()
                val durationSeconds = (file.length() / 2 / SAMPLE_RATE).toInt()
                val text = try {
                    transcriberSlot.use { transcriber ->
                        if (vad != null) {
                            transcriber.transcribeSegmented(file, vad, SAMPLE_RATE)
                        } else {
                            Log.i(TAG, "VAD model unavailable — decoding unsegmented (#132)")
                            transcriber.transcribe(PcmFileBuffer.readAsFloatArray(file), SAMPLE_RATE)
                        }
                    } ?: throw IllegalStateException("Local model was unloaded during transcription")
                } finally {
                    vad?.close()
                }
                // Success: the transcript exists, so no candidate (chain or otherwise) needs the
                // audio anymore -- mirrors the delete-on-success the cloud candidates do (M5).
                file.delete()
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${durationSeconds}s audio")
                BenchmarkLogger.log(
                    context = context,
                    correlationId = correlationIdFor(token),
                    transcription = BenchmarkStage(
                        provider = ProviderKind.LOCAL.name,
                        model = localTranscriptionModelId(),
                        latencyMs = System.currentTimeMillis() - benchmarkStartMs,
                        success = true,
                    ),
                    rawTextLength = text.length,
                )
                if (listener.allowsTranscriptRetention()) QualityLogger.log(
                    context = context,
                    correlationId = correlationIdFor(token),
                    transcription = QualityStage(
                        provider = ProviderKind.LOCAL.name,
                        model = localTranscriptionModelId(),
                    ),
                    rawText = text,
                )

                handleTranscriptionResult(text, token, lease)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                BenchmarkLogger.log(
                    context = context,
                    correlationId = correlationIdFor(token),
                    transcription = BenchmarkStage(
                        provider = ProviderKind.LOCAL.name,
                        model = localTranscriptionModelId(),
                        latencyMs = System.currentTimeMillis() - benchmarkStartMs,
                        success = false,
                        // #138: this catch already had the reason and sent it only to logcat,
                        // which rotates within hours while this file is the durable artifact.
                        error = sanitizeError(e.toString()),
                    ),
                )
                if (listener.allowsTranscriptRetention()) QualityLogger.log(
                    context = context,
                    correlationId = correlationIdFor(token),
                    transcription = QualityStage(
                        provider = ProviderKind.LOCAL.name,
                        model = localTranscriptionModelId(),
                    ),
                )
                if (onChainFailure != null) {
                    // Chain-candidate path (M5): the PCM is deliberately NOT deleted -- the walk
                    // owns its lifetime now (see kdoc above), so the next candidate can still
                    // upload it. advanceOrGiveUp hops to main, re-checks the guard, and either
                    // advances or deletes the audio and surfaces this error at chain exhaustion.
                    onChainFailure("Local error: ${e.message}")
                } else {
                    // Direct pure-local path (continueTranscription): no chain walk owns the PCM
                    // and nothing can retry without one, so delete it here and report honestly.
                    // Cloud fallback for this path is only wired at the "model not loaded yet"
                    // branch in continueTranscription -- a post-load failure this late is rare
                    // and re-recording is cheap.
                    file.delete()
                    handler.post {
                        if (!guard.isCurrent(token)) return@post // cancelled or watchdog already reset the UI
                        toast("Local error: ${e.message}")
                        resetToIdle(lease)
                    }
                }
            }
        }
    }

    /** The on-device transcription model currently selected via [TranscriptionActivity] (a
     *  separate model-picker system from [ProviderChainEntry.transcriptionModel] -- see that
     *  field's kdoc). Read straight from the same "model_name" preference key
     *  [ModelDownloader]/[TranscriptionActivity] use, so benchmark-log entries reflect the real
     *  active local model rather than guessing at a chain entry that doesn't apply to LOCAL. */
    private fun localTranscriptionModelId(): String = prefs().getString("model_name", "") ?: ""

    /** The user's personal vocabulary terms (#26/#114), read from the same "custom_vocabulary_terms"
     *  prefs key [BehaviorActivity] writes -- shared by both the cleanup-stage prompt interpolation
     *  below and the transcription-stage prompt biasing (OpenAI/Gemini) in [transcribeApi]. */
    private fun vocabularyTerms(): List<String> =
        VocabularyTerms.parse(prefs().getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED))

    private fun transcribeApi(
        file: File,
        token: Int,
        compressedFile: File? = null,
        lease: DictationSessionLease,
    ) {
        val chain = ProviderChainStore.load(context)
        val allowLocalFallback = DictationModeToggle.allowLocalFallback(context)
        val candidates = ProviderChainRuntime.transcriptionCandidates(chain, allowLocalFallback)
        if (candidates.isEmpty()) {
            file.delete()
            compressedFile?.delete()
            reset("No transcription provider configured", token, lease)
            return
        }

        // M5: which compressed .m4a copy (if any) the walk still holds. A LOCAL candidate never
        // uploads, so its branch deletes the compressed copy and nulls this -- and now that LOCAL
        // can *fail through* to a later cloud candidate (see its branch below), that candidate
        // must see null (upload raw PCM) rather than a File pointing at deleted bytes. Only ever
        // written before a candidate runs and read after its completion posts back, so the
        // walk's existing thread handoffs (thread{}/handler.post) order every access.
        var remainingCompressedFile: File? = compressedFile

        fun attempt(index: Int) {
            if (index >= candidates.size) {
                file.delete()
                remainingCompressedFile?.delete()
                reset("Set API key in Ramblr app", token, lease)
                return
            }
            val entry = candidates[index]

            // A candidate failed to produce a transcript (unusable, HTTP error, timeout, or empty).
            // Fall through to the next configured candidate -- Gemini, or the on-device LOCAL floor
            // -- instead of losing the dictation. Only once every candidate is exhausted do we
            // delete the audio and reset with the last real error. The PCM file is kept alive
            // across the whole walk precisely so a later candidate can still use it (#H1). Runs on
            // whatever thread the failing candidate's callback used; hop to the main thread and
            // re-check the guard first so a cancel/watchdog reset stops the walk.
            fun advanceOrGiveUp(error: String?) {
                handler.post {
                    if (!guard.isCurrent(token)) {
                        // Cancelled or watchdog already reset the UI: nothing more to try, but the
                        // PCM is still ours to clean up.
                        file.delete()
                        remainingCompressedFile?.delete()
                        return@post
                    }
                    if (TranscriptionChain.hasNextCandidate(index, candidates.size)) {
                        Log.w(TAG, "Transcription candidate #$index (${entry.kind}) failed: ${error ?: "unusable"}; trying next candidate")
                        // M2 (2026-08-25 audit): the guard re-check above belongs on main, but
                        // attempt() itself must not run there -- a fallback candidate's request
                        // prep is real work (a GEMINI candidate readBytes()es the whole PCM, up
                        // to ~10MB, and base64s it to ~13MB inside GeminiTranscriberClient before
                        // OkHttp ever sees it). Mirrors the thread { continueTranscription(...) }
                        // hop resolveLateRecordingOnMain/startMaxDurationTranscription already
                        // use: attempt(0) already runs on that reader thread, so everything
                        // attempt() touches is exercised off-main today -- the thread{}/
                        // handler.post handoffs order every access to the walk's shared state
                        // (remainingCompressedFile, guard, candidates).
                        thread { attempt(index + 1) }
                    } else {
                        file.delete()
                        remainingCompressedFile?.delete()
                        reset("Error: ${error ?: "transcription failed"}", token, lease)
                    }
                }
            }

            val localLoaded = transcriberSlot.get() != null
            val hasCredential = when (entry.kind) {
                ProviderKind.OPENAI -> ProviderCredentialStore.get(context, ProviderKind.OPENAI).isNotBlank()
                ProviderKind.GEMINI -> ProviderCredentialStore.get(context, ProviderKind.GEMINI).isNotBlank()
                else -> false
            }
            if (TranscriptionChain.precheck(entry.kind, hasCredential, localLoaded) == TranscriptionChain.Precheck.SKIP) {
                when (entry.kind) {
                    ProviderKind.LOCAL ->
                        // A LOCAL entry whose model hasn't loaded advances to the next candidate;
                        // only if it's the last one do we surface "still downloading" (#H1).
                        advanceOrGiveUp("Local model still downloading — try again once it finishes")
                    else -> {
                        Log.w(TAG, "Skipping transcription provider ${entry.kind}: not usable (no credential / not implemented)")
                        attempt(index + 1)
                    }
                }
                return
            }

            when (entry.kind) {
                ProviderKind.OPENAI -> {
                    val apiKey = ProviderCredentialStore.get(context, ProviderKind.OPENAI)
                    Log.i(TAG, "Cloud transcription via ProviderChain provider=${entry.kind} (OpenAI audio/transcriptions)")
                    val transcribeStartMs = System.currentTimeMillis()
                    // Honor the entry's base-URL override and TRANSCRIPTION model (#101/#102: a
                    // separate field from entry.model, which is the CLEANUP model -- OpenAI's
                    // chat-completions models like "gpt-5.4-mini" can never serve
                    // /v1/audio/transcriptions, which needs "whisper-1"/"gpt-4o-transcribe").
                    // .ifBlank { null } (#104 audit finding): the current UI never persists a
                    // literal "" here, but without this guard a hand-edited SharedPreferences
                    // value or a future regression could send an empty-string model id straight
                    // to OpenAI instead of falling back to DEFAULT_MODEL -- matches the same
                    // guard already applied on the Gemini call site just below.
                    // #114 parts 1/2: bias transcription itself toward the user's vocabulary, not
                    // just the cleanup stage -- same terms already read below at the cleanup call
                    // site (see vocabularyTerms()).
                    // M5: capture the compressed copy this attempt actually uploads as a local
                    // (the #193 locals-capture pattern) -- remainingCompressedFile can be nulled
                    // by a LOCAL candidate, and this callback must report/delete what IT sent.
                    val uploadCompressedFile = remainingCompressedFile
                    TranscriberClient.transcribe(
                        file, apiKey, inFlightCall,
                        baseUrl = entry.baseUrlOverride ?: PostProcessor.DEFAULT_BASE_URL,
                        model = entry.transcriptionModel?.ifBlank { null } ?: TranscriberClient.DEFAULT_MODEL,
                        vocabularyTerms = vocabularyTerms(),
                        compressedFile = uploadCompressedFile,
                    ) { result ->
                        val roundTripMs = System.currentTimeMillis() - transcribeStartMs
                        Log.i(TAG, "OpenAI transcription HTTP round-trip took ${roundTripMs}ms")
                        val success = result.text != null && result.text.isNotBlank()
                        BenchmarkLogger.log(
                            context = context,
                            correlationId = correlationIdFor(token),
                            transcription = BenchmarkStage(
                                provider = entry.kind.name,
                                model = entry.transcriptionModel?.ifBlank { null } ?: TranscriberClient.DEFAULT_MODEL,
                                latencyMs = roundTripMs,
                                success = success,
                                compressedUpload = uploadCompressedFile != null,
                                // #138: a provider error envelope yields blank text, so this
                                // records success=false; without the reason a bad key, a rate
                                // limit and a timeout are indistinguishable after logcat rotates.
                                error = sanitizeError(result.error),
                            ),
                            rawTextLength = result.text?.length,
                        )
                        if (listener.allowsTranscriptRetention()) QualityLogger.log(
                            context = context,
                            correlationId = correlationIdFor(token),
                            transcription = QualityStage(
                                provider = entry.kind.name,
                                model = entry.transcriptionModel?.ifBlank { null } ?: TranscriberClient.DEFAULT_MODEL,
                            ),
                            rawText = result.text,
                        )
                        if (success) {
                            file.delete()
                            uploadCompressedFile?.delete()
                            handleTranscriptionResult(result.text, token, lease)
                        } else {
                            advanceOrGiveUp(result.error ?: "empty transcript")
                        }
                    }
                }
                ProviderKind.LOCAL -> {
                    Log.i(TAG, "Transcription via ProviderChain provider=${entry.kind}")
                    // The local transcriber reads raw PCM, never an upload (#109) -- but null the
                    // walk-level handle too (M5), so a cloud candidate reached via LOCAL's new
                    // fail-through below uploads the raw PCM instead of a deleted .m4a path.
                    remainingCompressedFile?.delete()
                    remainingCompressedFile = null
                    // M5: as a chain candidate, LOCAL's failure must behave like any cloud
                    // candidate's -- keep the PCM alive and walk on via advanceOrGiveUp (which
                    // deletes it at chain exhaustion) instead of transcribeLocal's direct-path
                    // delete-and-reset. See transcribeLocal's PCM-lifetime kdoc and
                    // TranscriptionChain.shouldDeletePcm.
                    transcribeLocal(file, token, lease, onChainFailure = { error -> advanceOrGiveUp(error) })
                }
                ProviderKind.GEMINI -> {
                    val apiKey = ProviderCredentialStore.get(context, ProviderKind.GEMINI)
                    // Gemini's inline-audio path buffers the recording ~4x in memory, so gate it by
                    // size: above the threshold, fall through to the next candidate rather than risk
                    // an OOM stacked on the resident STT/cleanup models (M6). The compressed .m4a
                    // (#109) is always much smaller than the PCM/WAV it was encoded from, but the
                    // size check still gates on the original PCM: it's the cheap, reliable proxy for
                    // recording length already used everywhere else on this path, and a length-gated
                    // recording that's small enough to compress successfully is smaller still once
                    // encoded, so this can only ever be more permissive, never wrongly reject.
                    if (!GeminiTranscriberClient.canInlineAudio(file.length())) {
                        Log.w(TAG, "Recording too large for Gemini inline audio (${file.length()} bytes); trying next candidate")
                        advanceOrGiveUp("Recording too large for Gemini transcription")
                    } else {
                        Log.i(TAG, "Cloud transcription via ProviderChain provider=${entry.kind} (Gemini generateContent audio)")
                        val geminiModel = entry.transcriptionModel?.ifBlank { null } ?: GeminiTranscriberClient.DEFAULT_MODEL
                        val geminiStartMs = System.currentTimeMillis()
                        // M5: same locals-capture as the OpenAI branch above.
                        val uploadCompressedFile = remainingCompressedFile
                        GeminiTranscriberClient.transcribe(
                            file, apiKey, geminiModel, inFlightCall,
                            vocabularyTerms = vocabularyTerms(),
                            compressedFile = uploadCompressedFile,
                        ) { result ->
                            val success = result.text != null && result.text.isNotBlank()
                            BenchmarkLogger.log(
                                context = context,
                                correlationId = correlationIdFor(token),
                                transcription = BenchmarkStage(
                                    provider = entry.kind.name,
                                    model = geminiModel,
                                    latencyMs = System.currentTimeMillis() - geminiStartMs,
                                    success = success,
                                    compressedUpload = uploadCompressedFile != null,
                                    // #138: same as the OpenAI path -- Gemini reports failure as
                                    // an error envelope with blank text, not an exception.
                                    error = sanitizeError(result.error),
                                ),
                                rawTextLength = result.text?.length,
                            )
                            if (listener.allowsTranscriptRetention()) QualityLogger.log(
                                context = context,
                                correlationId = correlationIdFor(token),
                                transcription = QualityStage(
                                    provider = entry.kind.name,
                                    model = geminiModel,
                                ),
                                rawText = result.text,
                            )
                            if (success) {
                                file.delete()
                                uploadCompressedFile?.delete()
                                handleTranscriptionResult(result.text, token, lease)
                            } else {
                                advanceOrGiveUp(result.error ?: "empty transcript")
                            }
                        }
                    }
                }
                ProviderKind.ANTHROPIC, ProviderKind.OMNIROUTE -> attempt(index + 1) // filtered out by capability; defensive only
            }
        }

        attempt(0)
    }

    /** Test seam retaining the existing token-only API; stale tokens never borrow a newer lease. */
    internal fun handleTranscriptionResult(text: String?, token: Int) {
        val lease = sessionLease ?: return
        if (activeToken != token || !guard.isCurrent(token)) return
        handleTranscriptionResult(text, token, lease)
    }

    private fun handleTranscriptionResult(
        text: String?,
        token: Int,
        lease: DictationSessionLease,
    ) {
        if (text.isNullOrBlank()) {
            // H2 (#192): a no-speech dictation never reaches finishInjection -- abandon its
            // timing so a later unrelated injection (e.g. feedback-bubble raw-text retry) can't
            // consume it and write a garbage benchmark line.
            pipelineTiming.abandon()
            handler.post {
                if (!guard.isCurrent(token)) return@post
                toast("No speech detected")
                resetToIdle(lease)
            }
            return
        }

        // M3b (#192): a non-blank but content-free transcript (ASR hallucinations like "." or
        // "you"-length fragments) has nothing for a cleanup model to improve -- running the full
        // waterfall (network calls, LOCAL_LLM load) on it is pure waste. Inject it raw directly.
        if (isJunkTranscript(text)) {
            Log.i(TAG, "Transcript is content-free (len=${text.length}); skipping cleanup waterfall")
            handler.post {
                if (!guard.isCurrent(token)) return@post
                listener.deliverText(text, rawText = null, paidFallbackGroup = null, cleanupError = null, feedbackDurationMs = 2000)
                resetToIdle(lease)
            }
            return
        }

        val usePostProcessing = PostProcessingToggle.shouldRunCleanup(PostProcessingToggle.isEnabled(context))

        if (usePostProcessing) {
            // Phase 3 (#95): "Use cloud for Cleanup" toggle on the new unified Cloud screen --
            // applied here as a pure filter ahead of cleanup resolution so this call site is the
            // only place that needs to know about it; everything downstream (cleanupWaterfallFor,
            // shouldUseCleanupExecutor, processProviderChain) keeps operating on a plain
            // ProviderChain exactly as Phase 2 verified.
            val providerChain = ProviderChainRuntime.effectiveChainForCleanup(
                ProviderChainStore.load(context), CloudFeatureToggle.cleanupEnabled(context), DictationModeToggle.allowLocalFallback(context)
            )
            val cleanupWaterfall = ProviderChainRuntime.cleanupWaterfallFor(providerChain)

            // Reset the position-based waterfall cursor whenever the chain reshapes since the last
            // cleanup (add/remove/reorder/toggle in Settings), so a cached raw index can't resume at
            // a step that now occupies a different position -- which would skip newly-added free/
            // local steps and mis-attribute success to the wrong step (M3).
            val waterfallSignature = cleanupWaterfallSignature(cleanupWaterfall)
            if (waterfallSignature != lastCleanupWaterfallSignature) {
                cleanupCursor.reset()
                lastCleanupWaterfallSignature = waterfallSignature
            }

            // A zero-step provider chain means the user explicitly removed every executable cleanup
            // step: cleanup is disabled, so inject raw instead of falling back to any legacy store.
            if (cleanupWaterfall.steps.isEmpty()) {
                handler.post {
                    if (!guard.isCurrent(token)) return@post
                    listener.deliverText(text, rawText = null, paidFallbackGroup = null, cleanupError = null, feedbackDurationMs = 2000)
                    resetToIdle(lease)
                }
                return
            }

            // A single-OpenAI chain has exactly one required credential and no other step to fall
            // through to, so a missing key is worth failing fast on here, before the executor makes
            // a network call it can only fail. Real multi-step chains resolve credentials inside
            // CleanupWaterfallExecutor and can fall through past an unconfigured cloud step to
            // another provider (including LOCAL). Note this is only a pre-flight check -- since
            // #105 every chain, this one included, is executed by CleanupWaterfallExecutor.
            if (!ProviderChainRuntime.shouldUseCleanupExecutor(providerChain) &&
                ProviderCredentialStore.get(context, ProviderKind.OPENAI).isBlank()) {
                handler.post {
                    if (!guard.isCurrent(token)) return@post
                    toast("Post-processing needs API key. Using raw text.")
                    listener.deliverText(text, rawText = null, paidFallbackGroup = null, cleanupError = null, feedbackDurationMs = 2000)
                    resetToIdle(lease)
                }
                return
            }

            val savedPrompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            val perAppPersonaKey = if (PerAppPersonaToggle.isEnabled(context)) {
                PerAppPersonaStore.personaKeyFor(context, listener.foregroundPackageName())
            } else {
                null
            }
            val rawPrompt = perAppPersonaKey
                ?.let { CleanupPersonas.promptForExplicitSelection(PersonaRegistry.resolve(context, it)) }
                ?: savedPrompt
            val vocabulary = vocabularyTerms()
            val prompt = PostProcessor.interpolateVocabulary(rawPrompt, vocabulary)

            if (!guard.isCurrent(token)) return
            handler.post { if (guard.isCurrent(token)) listener.onCleaningStarted() }
            Log.i(TAG, "Cleanup via ProviderChain entries=${providerChain.entries.map { it.kind }} executableSteps=${cleanupWaterfall.steps.map { it.group }}")
            PostProcessor.processProviderChain(
                text = text,
                prompt = prompt,
                chain = providerChain,
                cursor = cleanupCursor,
                cancelHolder = inFlightCall,
                credentialLookup = { kind -> ProviderCredentialStore.get(context, kind) },
                localModelPath = { ModelDownloader.localCleanupModelFile(context, LocalCleanupProvider.selectedModel(context))?.absolutePath },
                localPrompt = LocalCleanupProvider.selectedSystemPrompt(context),
                // #182 option 2: local cleanup applies the same terms as a deterministic
                // post-pass over its output instead of in its prompt (which broke LFM2.5).
                localVocabulary = vocabulary,
                benchmarkContext = context.takeIf { listener.allowsTranscriptRetention() },
                benchmarkCorrelationId = correlationIdFor(token),
            ) { result ->
                handler.post {
                    if (!guard.isCurrent(token)) return@post // cancelled or watchdog already reset the UI
                    if (result.text != null && result.text.isNotBlank()) {
                        val servingGroup = recordProviderChainCleanupSuccess(cleanupWaterfall)
                        val paidFallbackGroup = servingGroup?.takeIf { it.isPaidFallback() }
                        listener.deliverText(result.text, rawText = text, paidFallbackGroup = paidFallbackGroup, cleanupError = null, feedbackDurationMs = 2000)
                    } else {
                        // Log + surface the real failure reason (bad/missing key, HTTP status,
                        // network error, etc.) instead of a generic "cleanup failed" that gives
                        // the user and any future debugging nothing to go on (#98, Trevor hit
                        // this directly: OpenAI key rejected/failed with zero visible reason).
                        // result.error already carries this from PostProcessor.Result/
                        // CleanupStepOutcome -- it was just being discarded here.
                        val reason = result.error?.takeIf { it.isNotBlank() } ?: "unknown error"
                        Log.w(TAG, "Cleanup failed, injecting raw text: $reason")
                        // #175: pass the raw error, not a finished message -- the host's
                        // injectText() builds the user-facing notice once the injection method is
                        // known, so it can state the truth about the clipboard and keep executor
                        // diagnostics (nested prefixes, provider error bodies) out of a floating
                        // overlay.
                        listener.deliverText(text, rawText = null, paidFallbackGroup = null, cleanupError = reason, feedbackDurationMs = 4000)
                    }
                    resetToIdle(lease)
                }
            }
        } else {
            handler.post {
                if (!guard.isCurrent(token)) return@post
                listener.deliverText(text, rawText = null, paidFallbackGroup = null, cleanupError = null, feedbackDurationMs = 2000)
                resetToIdle(lease)
            }
        }
    }

    /** Marks whichever provider-chain cleanup step just served this cleanup call as healthy (#32), so the
     *  Settings status dot reflects real usage, not just a Test-button press, and returns that
     *  step's group so callers can attribute the result for dictation history's "paid fallback"
     *  badge (#33). Which step that was isn't threaded through [PostProcessor.processProviderChain]'s
     *  callback, so it's inferred from [cleanupCursor]'s last-known-good index instead -- safe to
     *  read immediately after a success since [CleanupWaterfallCursor.recordSuccess] was just
     *  called with "now", well inside the idle-expiry window [CleanupWaterfallCursor.startIndex]
     *  checks. A total-waterfall failure is deliberately not attributed to any one step here,
     *  since several steps may have failed for different reasons -- the Settings "Test" button is
     *  the deterministic way to pin down which. */
    private fun recordProviderChainCleanupSuccess(executableWaterfall: CleanupWaterfall): CleanupStepGroup? {
        val succeededIndex = cleanupCursor.startIndex(System.currentTimeMillis())
        val step = executableWaterfall.steps.getOrNull(succeededIndex) ?: return null
        CleanupStepStatusStore.record(context, step, CleanupStepHealth.SUCCESS)
        return step.group
    }

    /** Safe to call from any thread; both token and lease bind the reset to its originating session. */
    private fun reset(msg: String, token: Int, expectedLease: DictationSessionLease) {
        if (!guard.isCurrent(token) || sessionLease !== expectedLease) return
        toast(msg)
        // H2 (#192): every reset(msg) call site is a terminal error/give-up exit (no audio, chain
        // exhausted, recording error, ...) after which no finishInjection will run for this
        // dictation -- abandon its timing so it can't be misattributed to a later injection.
        pipelineTiming.abandon()
        handler.post {
            if (!guard.isCurrent(token) || sessionLease !== expectedLease) return@post
            resetToIdle(expectedLease)
        }
    }

    /** Tokenless recorder-error reset, still compare-bound to the exact recording lease. */
    private fun resetRecordingError(msg: String, expectedLease: DictationSessionLease) {
        if (sessionLease !== expectedLease) return
        toast(msg)
        pipelineTiming.abandon()
        handler.post {
            if (sessionLease !== expectedLease) return@post
            resetToIdle(expectedLease)
        }
    }

    // --- Streaming preview (#29) ---

    /**
     * Runs on [RecordingEngine]'s reader thread (see [RecordingEngine.start]'s `onChunk`): converts
     * the raw PCM chunk and feeds it to the streaming recognizer, then hops to the main thread only
     * if there's a new hypothesis to potentially show. Decoding happens on every chunk regardless of
     * whether the result ends up injected — the host's `maybeInjectPartial` is what throttles actual
     * UI/field updates, not this.
     */
    private fun handleStreamingChunk(buf: ByteArray, len: Int) {
        val samples = PcmFileBuffer.bytesToFloatArray(buf, len)
        val text = streamingTranscriberSlot.use { it.acceptChunk(samples, SAMPLE_RATE) } ?: return
        handler.post { listener.onStreamingPartial(text) }
    }

    // --- Host lifecycle hooks ---

    /**
     * Terminal teardown for the host's onDestroy, running the runtime-owned pieces of the
     * pre-extraction `WhisperAccessibilityService.onDestroy` in their exact original order (the
     * host interleaves its own pieces around this call -- see the service's onDestroy).
     */
    /** Synchronous invalidation phase: no waits, and every late callback fails closed immediately. */
    fun beginShutdown() {
        synchronized(this) {
            if (shuttingDown) return
            shuttingDown = true
            transcriberLifecycle.beginShutdown()
            streamingTranscriberLifecycle.beginShutdown()
        }
        val lease = sessionLease
        if (recordingEngine != null) shutdownLeaseAwaitingReader = lease
        cancelCloudLiveAttempt()
        inFlightCall.cancel()
        stateMachine.reset()
        cancelWatchdog()
        guard.cancel()
        activeToken = 0
        pipelineTiming.abandon()
    }

    /** Compatibility path for the accessibility host; IME uses the split async path below. */
    fun shutdown() {
        beginShutdown()
        finishShutdown()
    }

    /** Blocking audio/native teardown phase. Never invoke this directly from an IME callback. */
    private fun finishShutdown() {
        val lease = sessionLease
        val engine = recordingEngine
        if (engine != null) shutdownLeaseAwaitingReader = lease
        // Cancel transcription work before waiting for capture teardown. If the reader times out,
        // its eventual callback can now release ownership knowing both terminal conditions hold.
        inFlightCall.cancel()
        var readerTeardownCompleted = engine == null
        try {
            // If a recording is in progress, force the reader thread off RECORDING/TRANSCRIBING so
            // it tears down the AudioRecord and discards buffered PCM instead of leaking the mic or
            // silently continuing to record after the service appears off. reset() runs
            // unconditionally (H2): even if recordingEngine reads null here, a late old-session
            // handoff could have raced our read of it while a reader is still live, so we must always
            // walk the shared state machine out of RECORDING/TRANSCRIBING.
            stateMachine.reset()
            readerTeardownCompleted = engine?.awaitTeardown() ?: true
            recordingEngine = null
            // Belt-and-suspenders alongside the release already wired into startRecording's onFinished
            // (#108): awaitTeardown above blocks until the reader thread's onFinished has run, so this
            // is normally already null, but a stray VAD session must never survive service teardown.
            silenceAutoStopSession?.release()
            silenceAutoStopSession = null
            // Belt-and-suspenders alongside the release already wired into startRecording's onFinished
            // (#109): awaitTeardown above blocks until the reader thread's onFinished has run, so this
            // is normally already null, but a stray encoder session must never survive service teardown
            // and leave a partial .m4a temp file behind.
            aacEncoderSession?.release()
            aacEncoderSession = null
            cancelWatchdog()
            guard.cancel()
        } finally {
            // A timed-out reader keeps the lease until its onFinished callback confirms AudioRecord
            // teardown. Otherwise release now; active capture never migrates to another destination.
            if (readerTeardownCompleted) {
                if (shutdownLeaseAwaitingReader === lease) shutdownLeaseAwaitingReader = null
                releaseSessionLease(lease)
            }
        }
        // M6 belt-and-suspenders: service teardown is terminal for any in-flight dictation, and
        // resetToIdle may never run for it. isHeld-guarded, so a no-dictation destroy is a no-op.
        releaseTranscriptionWakeLock()
        // The cancel above makes any in-flight local completion abort at its next piece check
        // (#83), so the holder's daemon thread can close the cached ~1 GB cleanup model promptly
        // without this main-thread teardown waiting on it (#74).
        LocalCleanupModelHolder.releaseAsync()
        teardownStreamingPreview()
    }

    /** Blocking IME teardown stage; callers must serialize it off main before any new model load. */
    internal fun finishShutdownAndReleaseTranscribers() {
        finishShutdown()
        transcriberLifecycle.releaseInstalled()
        streamingTranscriberLifecycle.releaseInstalled()
    }

    /** IME teardown compatibility helper for non-serialized hosts. */
    fun finishShutdownAsync() {
        thread { finishShutdownAndReleaseTranscribers() }
    }

    /**
     * Releases the native transcriber recognizers (M7) at host teardown: like onTrimMemory,
     * replace(null) can block on an in-flight transcription, so it runs off the main thread.
     * Without this, a service destroy/recreate in the same process (accessibility toggle off/on)
     * leaves the old instance's recognizers (batch model up to 465MB) resident alongside the new
     * ones until process death. Called by the host's onDestroy after it has flushed its pending
     * streaming handoff -- the same position the `thread { ... replace(null) ... }` block held in
     * the pre-extraction onDestroy.
     */
    fun releaseTranscribersAsync() {
        transcriberLifecycle.beginShutdown()
        streamingTranscriberLifecycle.beginShutdown()
        thread {
            transcriberLifecycle.releaseInstalled()
            streamingTranscriberLifecycle.releaseInstalled()
        }
    }

    private fun prefs() = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private fun toast(msg: String) {
        handler.post {
            listener.onUserMessage(msg)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
