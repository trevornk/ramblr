package com.trevornk.ramblr

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import org.json.JSONObject

/**
 * Timeouts for one waterfall step's network client. See ADR-0001 / docs/adr/0001-cleanup-
 * waterfall.md: the connect timeout is deliberately short so an unreachable host (e.g. OmniRoute
 * while away from home) fails fast instead of hanging to the full read timeout, and the read
 * timeout absorbs a real but slow completion without letting one bad step wreck the whole chain.
 */
data class CleanupStepTimeouts(
    val connectMs: Long = 1_500L,
    val readMs: Long = 7_000L,
    /**
     * Whole-call bound (connect + write + read + redirects), clamped to what's actually left of
     * [CLEANUP_WATERFALL_HARD_CAP_MS] -- see [cloudStepTimeouts] (#137). Null means "leave the
     * shared client's own callTimeout alone", which is what test fakes and any caller that
     * doesn't know the deadline get.
     *
     * This exists because [connectMs]/[readMs] are per-*phase* budgets: OkHttp will happily
     * spend connectMs connecting and then a further readMs reading, so bounding the phases
     * individually does not bound the call. Only callTimeout does.
     */
    val callMs: Long? = null,
) {
    companion object {
        val DEFAULT = CleanupStepTimeouts()
    }
}

/**
 * Floor for a step's whole-call budget (#137). Never 0: OkHttp treats a 0 timeout as *infinite*,
 * so clamping a spent budget straight down to zero would turn the hard cap into no cap at all --
 * the exact opposite of the intent. The between-steps gate in [CleanupWaterfallExecutor.execute]
 * already guarantees a step only starts with budget remaining, so this floor is a safety net for
 * the sub-millisecond edge, not a routine path.
 */
const val MIN_STEP_CALL_BUDGET_MS = 250L

/** Retry-only bound: unlike a fresh step, a parameter retry must never borrow the 250ms floor. */
internal fun temperatureRetryTimeouts(deadlineAtMs: Long, nowMs: Long): CleanupStepTimeouts? {
    val remainingMs = deadlineAtMs - nowMs
    if (remainingMs <= 0L) return null
    return CleanupStepTimeouts(
        connectMs = minOf(CleanupStepTimeouts.DEFAULT.connectMs, remainingMs),
        readMs = minOf(CleanupStepTimeouts.DEFAULT.readMs, remainingMs),
        callMs = remainingMs,
    )
}

/**
 * Per-step network timeouts for a cloud step, clamped to the waterfall's remaining wall clock
 * (#137).
 *
 * Before this existed, every cloud step was handed [CleanupStepTimeouts.DEFAULT] verbatim and
 * [CLEANUP_WATERFALL_HARD_CAP_MS] was only ever checked *between* steps -- so a step dispatched
 * at 14,999ms of a 15,000ms budget still got its full connect+read allowance and the real worst
 * case was 14,999 + 1,500 + 7,000 = 23,499ms. Trevor's own benchmark log has a 22,852ms
 * dictation, which is that arithmetic happening in the field.
 *
 * Clamping [CleanupStepTimeouts.callMs] to the remaining budget makes the documented cap
 * actually hold, because callTimeout bounds the entire call rather than one phase of it.
 */
fun cloudStepTimeouts(
    overallDeadlineAtMs: Long,
    nowMs: Long,
    base: CleanupStepTimeouts = CleanupStepTimeouts.DEFAULT,
): CleanupStepTimeouts {
    val remainingMs = (overallDeadlineAtMs - nowMs).coerceAtLeast(MIN_STEP_CALL_BUDGET_MS)
    return base.copy(
        connectMs = minOf(base.connectMs, remainingMs),
        readMs = minOf(base.readMs, remainingMs),
        callMs = remainingMs,
    )
}

/**
 * Hard cap on the whole waterfall, regardless of how many steps remain, before falling back to
 * raw injection.
 *
 * Tuned to 8s from the original 15s against 34 days of real dictation on Trevor's Fold (318
 * successful cleanups, benchmark_log.jsonl): median 901ms, p95 2879ms, p99 3828ms, slowest
 * success 7859ms. An 8s cap kills 0 of those 318 -- 6s would kill the 7859ms one and 5s would
 * kill two -- so this is the tightest cap with no observed regression, with [readMs][
 * CleanupStepTimeouts.readMs] left at 7s precisely because that is what let the 7859ms call
 * through.
 *
 * The 15s value was never load-bearing; it predated #137, when this cap was only checked
 * *between* steps and so didn't bound anything in practice. The same log shows two offline
 * waterfalls running 18.2s and 19.7s under it. Now that #137 makes the deadline real, the cost
 * of a generous cap is paid entirely by the offline case, where every provider burns its full
 * timeout in sequence before the chain gives up and injects raw text -- the user is already
 * getting no cleanup there, so making them wait 15s for that answer is the worst trade in the
 * chain. [LOCAL_LLM_STEP_BUDGET_MS] below remains the mechanism for keeping one slow local
 * attempt from eating this budget.
 */
const val CLEANUP_WATERFALL_HARD_CAP_MS = 8_000L

/**
 * Wall-clock budget for the LOCAL_LLM step specifically (#98, Claude Fable 5 consult), separate
 * from and always <= [CLEANUP_WATERFALL_HARD_CAP_MS]. Before this existed, the LOCAL_LLM step
 * used the *entire* remaining waterfall deadline -- so on a device under real memory pressure, a
 * slow/aborted local attempt could consume the whole budget and leave zero time for the cloud
 * step that should have followed it, defeating the point of having a waterfall at all. With
 * LFM2.5-350M (see [LOCAL_CLEANUP_MODEL]'s kdoc) a healthy device should complete well under
 * 1-2s; 4s gives headroom for a loaded device's slower decode while guaranteeing that a genuinely
 * stuck/slow local attempt aborts (see commit 2212db2's native ggml abort callback, #92) with
 * enough of [CLEANUP_WATERFALL_HARD_CAP_MS] still left for a subsequent cloud step's own
 * [CleanupStepTimeouts] to actually run.
 */
const val LOCAL_LLM_STEP_BUDGET_MS = 4_000L

/**
 * Wall-clock budget for a LOCAL_LLM step that is the LAST step in the waterfall, measured from
 * the waterfall's start (#155). Replaces [CLEANUP_WATERFALL_HARD_CAP_MS] for that one case only.
 *
 * Exists because the 8s hard cap was doing two unrelated jobs with one number. Its own kdoc says
 * what it was tuned for: a user with *no* local model, whose unreachable cloud hosts burn their
 * timeouts in sequence while the chain grinds toward raw injection. That is a hanging-socket
 * problem, and 8s is the right answer to it -- the #137 tuning against 318 real cleanups (p99
 * 3828ms, slowest success 7859ms) is load-bearing and stays exactly as it is for every cloud
 * step.
 *
 * A terminal on-device step has the opposite risk profile. It is a CPU-bound, self-terminating
 * computation that already carries its own native abort callback (#92), so it cannot hang the way
 * a dead socket can; and being last, there is no subsequent step whose time it could steal. The
 * only thing the cloud-shaped cap achieves there is converting a slow-but-healthy completion into
 * an outright failure. Trevor hit exactly that on a Pixel 10a: a local cleanup finished its work
 * in 8003ms and was killed at 8000ms, missing by 3ms. For a user with no cloud provider
 * configured, local cleanup is the ONLY cleanup path, so that 3ms is the difference between
 * cleaned text and no cleanup at all.
 *
 * 12s is chosen as a bound on that observed failure, not a device measurement: it clears the
 * 8003ms case by ~50% while still guaranteeing the user gets *some* answer in a bounded time.
 * It is deliberately a fixed ceiling -- no adaptive or calibration machinery -- because it is a
 * safety net, not a latency fix.
 *
 * This budget deliberately does NOT rest on any claim about vocabulary length. An earlier
 * revision justified it with a "2145 t/s short prompt vs 391 t/s 22-term prompt" figure; that
 * measurement was wrong twice over and has been retracted. Re-measured against the real
 * lfm2.5-350m-q4_0 with the real production prompts: the "364 -> 702" figures were *character*
 * counts, not tokens (true tokenized lengths are 100 -> 212), and the throughput gap was a
 * warmup artifact of an uncontrolled first run -- alternating A/B reps put the 22-term prompt at
 * 2431-2797 t/s versus 1582-1781 t/s for the short one, i.e. the longer prompt evaluates
 * *faster* per token because the larger batch uses the CPU better. The personal vocabulary costs
 * ~15ms of prompt eval, which cannot explain an 8000ms budget overrun.
 *
 * The real cost is structural: LLMInference::startCompletion clears the KV cache every
 * completion, so the system prompt is re-evaluated from scratch on every dictation instead of
 * reusing its cached prefix. See .hermes/plans/2026-08-18-local-cleanup-latency-measured.md.
 *
 * Measured from the waterfall's start rather than from "now", so a chain that spent time on
 * earlier steps cannot extend the total run past this ceiling.
 */
const val TERMINAL_LOCAL_LLM_STEP_BUDGET_MS = 12_000L

/**
 * The actual wall-clock deadline a LOCAL_LLM step gets for one completion attempt (#37 follow-up,
 * Trevor hit this directly with a single-step Local-only chain): [LOCAL_LLM_STEP_BUDGET_MS]
 * exists to guarantee a *subsequent* step gets real time to run after a slow/stuck local attempt
 * aborts -- but when the LOCAL_LLM step is the LAST step in the whole waterfall (no fallback
 * follows it, e.g. a user's simple "Local" cleanup choice with nothing else configured), that
 * same tight 4s budget serves no purpose: there's no later step it's protecting, so it just
 * turns a genuinely healthy but slightly slower completion into an outright failure with no
 * safety net.
 *
 * When [isLastStep] is true this returns [TERMINAL_LOCAL_LLM_STEP_BUDGET_MS] from
 * [waterfallStartedAtMs] (#155) rather than the cloud-shaped [overallDeadlineAtMs] -- see that
 * constant's kdoc for why the 8s hard cap is the wrong instrument for a terminal on-device step.
 * [maxOf] rather than a plain replacement so this can only ever widen the terminal step's
 * deadline, never shrink it below what it already got.
 */
fun localLlmStepDeadline(
    overallDeadlineAtMs: Long,
    nowMs: Long,
    isLastStep: Boolean,
    waterfallStartedAtMs: Long = overallDeadlineAtMs - CLEANUP_WATERFALL_HARD_CAP_MS,
): Long =
    if (isLastStep) maxOf(overallDeadlineAtMs, waterfallStartedAtMs + TERMINAL_LOCAL_LLM_STEP_BUDGET_MS)
    else minOf(overallDeadlineAtMs, nowMs + LOCAL_LLM_STEP_BUDGET_MS)

/**
 * Ceiling on a whole chunked LOCAL_LLM step (#325), unless the caller passes its own (see
 * [CleanupWaterfallExecutor.execute]'s `localStepBudgetMs`). It exists for live dictation, whose
 * 400 s watchdog fires and resets the UI mid-cleanup: a chunked run must finish -- partially
 * cleaned if need be -- before that, with room left for the transcription that precedes it. The
 * per-chunk allowance is NOT a constant: [LocalCleanupChunkBudget] derives it from how fast this
 * device has actually been decoding, from [LocalCleanupChunkBudget.MIN_BUDGET_MS] (the flat 20 s
 * #327 shipped, which a fast phone still gets) up to [LocalCleanupChunkBudget.MAX_BUDGET_MS].
 *
 * When the measured pace says the next chunk cannot finish inside what is left of this ceiling,
 * the chunk is not started and the rest is delivered raw with the honest partial label, instead
 * of burning the remainder on a chunk killed at the deadline. At 4 minutes this covers ~24
 * chunks at the Pixel 10a's 10 s (LFM2.5) or ~17 at its 14 s (Mumble) -- ~5,000-7,000
 * characters of dictation; the audio-file path, which has no watchdog and cleans a long
 * recording piece by piece, passes [AUDIO_LOCAL_STEP_BUDGET_MS] per piece instead.
 */
const val LOCAL_CHUNKED_TOTAL_BUDGET_MS = 240_000L

/**
 * Per-piece ceiling for the audio-file path (see [AudioJobRunner]): a background job with a
 * visible Cancel and no live-dictation watchdog, whose pieces are at most
 * [TranscriptChunker.LOCAL_TERMINAL_MAX_CHARS] (~6 chunks). Ten minutes holds six chunks at the
 * 100 s a chunk may take on a phone several times slower than a Pixel 10a, so a slow phone gets
 * a fully cleaned file (slowly) instead of raw text, while the admission check still stops a
 * piece whose measured pace says the rest cannot fit.
 */
const val AUDIO_LOCAL_STEP_BUDGET_MS = 600_000L

/**
 * After this many chunks in a row time out, the device is plainly too slow for the model and the
 * remaining chunks are returned raw instead of burning another budget on each. Each timeout also
 * raises the next chunk's allowance (see [LocalCleanupChunkBudget]), so two in a row means the
 * allowance has already grown and still was not enough. Validator rejections do not count: those
 * are quick and independent per chunk.
 */
const val LOCAL_CHUNK_MAX_CONSECUTIVE_TIMEOUTS = 2

/**
 * How much of a chunked local cleanup actually got cleaned, attached to a successful result when
 * some chunks fell back to their raw text (#325). Counts only -- never transcript text.
 */
data class PartialCleanup(val cleanedChunks: Int, val totalChunks: Int)

/**
 * Pure grouping/ordering logic for [CleanupWaterfall.steps], split out from the network-calling
 * executor so it's independently unit-testable without any I/O. Groups consecutive steps that
 * share a [CleanupStepGroup] into one fail-fast unit: if the first step in a group fails due to
 * a connection-level failure (host unreachable/timeout — see [CleanupStepOutcome.ConnectionFailed]),
 * every remaining step in that same group is skipped without being attempted, since they'd fail
 * identically against the same dead host. A non-connection failure (e.g. a real HTTP error from
 * a reachable host) only fails that one step; the next step in the same group is still attempted.
 */
object CleanupWaterfallPlanner {
    /**
     * Splits [steps] into runs of consecutive equal-[CleanupStep.group] entries, preserving
     * overall order. Each inner list is one fail-fast unit for [CleanupWaterfallExecutor].
     */
    fun groupConsecutive(steps: List<CleanupStep>): List<List<CleanupStep>> {
        if (steps.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<CleanupStep>>()
        for (step in steps) {
            val last = result.lastOrNull()
            if (last != null && last.last().group == step.group) {
                last.add(step)
            } else {
                result.add(mutableListOf(step))
            }
        }
        return result
    }
}

/**
 * Stable identity signature of a waterfall's step list (each step's group + model + base-URL
 * override, in order), used to detect when the provider chain has been reshaped in Settings. The
 * [CleanupWaterfallCursor] stores a raw index into the step list, so an add/remove/reorder/toggle
 * that changes the list under it would otherwise let the next cleanup resume at a step that now sits
 * at a different position -- skipping newly-added free/local steps and mis-attributing success to
 * the wrong step (M3). The service compares this signature across dictations and resets the cursor
 * when it changes, since it can't observe Settings edits directly across component boundaries.
 */
fun cleanupWaterfallSignature(waterfall: CleanupWaterfall): String =
    waterfall.steps.joinToString("|") { "${it.group.name}~${it.model}~${it.baseUrlOverride ?: ""}" }

/** Why a waterfall step did not produce a result. Distinguishes connection-level failures
 *  (which disqualify the rest of that step's group) from step-level failures (which don't). */
sealed class CleanupStepOutcome {
    /** [sections] is set only by the chunked on-device path: how many chunks were cleaned out of how
     *  many, whether or not any fell back (so a fully cleaned run still reports its real size).
     *  [partial] is [sections] again, but only when some chunk fell back raw. */
    data class Success(val text: String, val partial: PartialCleanup? = null, val sections: PartialCleanup? = null) : CleanupStepOutcome()
    /** Host unreachable, connect timeout, or read timeout — the whole group is dead for this call. */
    data class ConnectionFailed(val message: String?) : CleanupStepOutcome()
    /** Reachable host, but this step failed on its own (bad model name, HTTP error, malformed response). */
    data class StepFailed(val message: String?) : CleanupStepOutcome()
    /** The user (or watchdog) cancelled the in-flight call — the whole waterfall must stop, not
     *  fall through to the next group; anything it produced would be discarded by the caller's
     *  guard token anyway, and a paid direct-provider step must never be billed for it (#63). */
    object Cancelled : CleanupStepOutcome()
    /** The step answered, but [CloudCleanupFidelityGuard] rejected what it said. Terminal: the next
     *  provider is slower and the raw transcript is already a good answer, so the waterfall stops
     *  and the caller delivers raw text with its usual cleanup-failed notice. [reason] is a fixed
     *  enum name, never transcript or model text. */
    data class Rejected(val reason: FidelityViolation) : CleanupStepOutcome()
}

/**
 * Tracks the index of the last waterfall step that succeeded, so the next cleanup call can
 * start there instead of re-probing from step 0 every time (see ADR-0001). Reset to 0 on
 * Android network-change (SSID/VPN change) by the caller (see WhisperAccessibilityService's
 * ConnectivityManager.NetworkCallback registration) and on idle expiry, both handled by the
 * owner of this cursor, not by this class itself — this class only tracks the index and idle
 * timing so both reset triggers can be tested without Android framework dependencies.
 */
class CleanupWaterfallCursor(private val idleExpiryMs: Long = 5 * 60 * 1000L) {
    @Volatile private var index: Int = 0
    @Volatile private var lastSuccessAt: Long = 0L

    /** The step index to start the next call at, given the current time. Expires to 0 if idle too long. */
    fun startIndex(nowMs: Long): Int {
        if (index != 0 && nowMs - lastSuccessAt > idleExpiryMs) {
            index = 0
        }
        return index
    }

    fun recordSuccess(stepIndex: Int, nowMs: Long) {
        index = stepIndex
        lastSuccessAt = nowMs
    }

    /** Called on Android connectivity/network change (SSID/VPN transition). */
    fun reset() {
        index = 0
    }
}

/** Raw result of one HTTP attempt, before it's interpreted as a [CleanupStepOutcome] (which
 *  requires knowing whether the body parses for that step's wire format). */
sealed class CleanupHttpOutcome {
    data class Ok(val body: String) : CleanupHttpOutcome()
    data class HttpError(val code: Int, val body: String) : CleanupHttpOutcome()
    data class ConnectionFailure(val message: String?) : CleanupHttpOutcome()
    /** The call was aborted via [InFlightCall.cancel] (user long-press or watchdog), not by the
     *  network. Kept distinct from [ConnectionFailure] because OkHttp reports a cancel through
     *  the same onFailure(IOException) path a dead host uses — treating it as one made a cancel
     *  *advance* the waterfall to the next (possibly paid) group instead of stopping it (#63). */
    object Cancelled : CleanupHttpOutcome()
}

/**
 * Seam over the network layer: [CleanupWaterfallExecutor] depends on this interface rather than
 * OkHttp directly so tests can fake HTTP responses (including connection failures) without any
 * real I/O. See [RealCleanupHttpTransport] for the production implementation.
 */
fun interface CleanupHttpTransport {
    fun send(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeouts: CleanupStepTimeouts,
        cancelHolder: InFlightCall,
        callback: (CleanupHttpOutcome) -> Unit,
    )
}

/**
 * Outcome of one on-device llama.cpp completion, before it's interpreted as a
 * [CleanupStepOutcome]. Kept distinct from [CleanupHttpOutcome] because local inference has no
 * HTTP status code or connection-level failure -- it either produces text or it doesn't (#37).
 */
sealed class LocalInferenceResult {
    data class Success(val text: String) : LocalInferenceResult()
    data class Failure(val message: String) : LocalInferenceResult()
    /** The user (or watchdog) cancelled while inference was running (#83) -- like
     *  [CleanupHttpOutcome.Cancelled], this must stop the whole waterfall, not fall through. */
    object Cancelled : LocalInferenceResult()
    /** [LOCAL_LLM_STEP_BUDGET_MS] (or the overall deadline) expired before the native decode
     *  produced text -- e.g. `llama_decode() aborted: inference budget exceeded` on a device
     *  too slow for the current model. Deliberately distinct from [Cancelled]: a real user/
     *  watchdog cancel must stop the entire waterfall (a paid step must never run after a
     *  cancel, #63), but a plain timeout is just this one step failing to finish in time --
     *  it should fall through to the next step exactly like any other [Failure], not silently
     *  abort the whole cleanup and leave every configured cloud provider untried (Trevor hit
     *  this live: switching to a cloud-only chain still injected raw, un-cleaned-up text
     *  because a slow local model's timeout was being treated as if he'd hit cancel). */
    data class TimedOut(val message: String) : LocalInferenceResult()
}

/**
 * Seam over on-device llama.cpp inference (#37): [CleanupWaterfallExecutor] depends on this
 * interface rather than [LlamaCppInference] directly so tests can fake local-model completions
 * without any native code, mirroring how [CleanupHttpTransport] fakes the network layer for the
 * cloud provider groups. [systemPrompt]/[userText] are passed through unchanged from
 * [CleanupWaterfallExecutor.execute]'s `prompt`/`text` -- see [LocalCleanupProvider] for why no
 * translation is needed. [modelPath] is the absolute path to the installed GGUF file.
 * [deadlineAtMs]/[isCancelled] bound the otherwise-uninterruptible synchronous inference (#83):
 * generation must stop once the waterfall's wall-clock budget is spent or the user cancelled.
 */
fun interface LocalInferenceEngine {
    fun complete(
        systemPrompt: String,
        userText: String,
        modelPath: String,
        deadlineAtMs: Long,
        isCancelled: () -> Boolean,
    ): LocalInferenceResult

    /**
     * Gets [modelPath] loaded so the first [complete] does not pay the load. A chunked cleanup
     * calls this once before its first chunk so the multi-second GGUF load (4.2-4.6 s for
     * mumble-cleanup-2stage on a Pixel 10a, more on a slower phone) is not charged to chunk 1's
     * time allowance, and so it is not mistaken for decode speed when [LocalCleanupChunkBudget]
     * measures the device. Best effort: a failure here is not reported -- the first [complete]
     * hits the same failure and reports it properly. Default: nothing to prepare.
     */
    fun prepare(modelPath: String) {}
}

/**
 * Runs a bounded blocking call on a background thread and gives up waiting once [deadlineAtMs]
 * passes, without attempting to interrupt or kill the underlying work (#92). Exists because a
 * single native `completionLoop()` decode inside [LlamaCppInference.complete] is one
 * synchronous, uninterruptible JNI call -- [LlamaCompletionAccumulator]'s own deadline/cancel
 * checks only run *between* pieces, so a single slow-to-return decode (thermal throttling,
 * memory pressure causing mmap page faults, or just a slow device) defeats the wall-clock budget
 * entirely: the calling thread blocks inside that one call with no interruption point until it
 * returns on its own. [runWithDeadline] moves the actual call onto [executor] and calls
 * [Future.get] with a real timeout, so the *caller* always gets an answer within budget -- the
 * abandoned background task is left running to completion (Thread.interrupt() on code crossing
 * a JNI boundary mid-native-call is unsafe and can crash or corrupt process state) and its result
 * is simply discarded when it eventually finishes.
 *
 * Note this does not fix "why is a single decode slow" -- only that the app must never hang past
 * its stated budget regardless. A dictation immediately following a timed-out one may still see
 * its own local step wait behind the still-running abandoned task on [executor]'s single thread,
 * but that wait is itself bounded by that dictation's own deadline, so it fails fast rather than
 * hanging too.
 */
object BoundedBlockingCall {
    /** Single dedicated thread: local inference calls are already effectively serialized by
     *  [LocalCleanupModelHolder]'s monitor, so a pool would buy nothing -- this just gives the
     *  abandoned-task-keeps-running semantics a home off the calling (accessibility service)
     *  thread. Daemon so it never blocks process shutdown. */
    private val executor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "local-cleanup-inference").apply { isDaemon = true }
        }
    }

    /** Runs [block] on [executor] and waits up to `deadlineAtMs - now` for it. Returns null on
     *  timeout (block keeps running, abandoned); rethrows [block]'s exception if it completes
     *  within the deadline and threw. A [deadlineAtMs] already in the past still submits and
     *  waits a minimum floor rather than an exact 0ms: `Future.get(0, ...)` treats 0 as "don't
     *  block at all" and can throw [java.util.concurrent.TimeoutException] even for a task that
     *  would complete near-instantly, which would make an already-tight budget spuriously fail a
     *  call that never even got a real chance to run.
     *
     *  Wrapped explicitly in [java.util.concurrent.Callable] rather than passed as a bare
     *  function value: `ExecutorService.submit` is overloaded for `Callable<R>` (keeps the
     *  result) and `Runnable` (discards it, returning a `Future<*>` that always resolves to
     *  null); passing a stored `() -> R` directly resolves to the `Runnable` overload, silently
     *  dropping every real result. */
    fun <R> runWithDeadline(deadlineAtMs: Long, block: () -> R): R? {
        val future = executor.submit(java.util.concurrent.Callable { block() })
        val timeoutMs = (deadlineAtMs - System.currentTimeMillis()).coerceAtLeast(MIN_WAIT_MS)
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Floor for [runWithDeadline]'s wait, so an already-past deadline still gives a fast call a
     *  real chance to complete rather than racing `Future.get(0, ...)`'s "don't block" semantics. */
    private const val MIN_WAIT_MS = 50L
}

/** Production [LocalInferenceEngine]: drives the vendored/adapted llama.cpp JNI wrapper through
 *  [LocalCleanupModelHolder], which keeps the model loaded across dictations instead of paying a
 *  full GGUF load per call (#74). See [LlamaCppInference] for the native binding itself.
 *  [BoundedBlockingCall] (#92) ensures a single slow/stuck native decode can never hang this call
 *  past [deadlineAtMs] -- see its kdoc for why the accumulator's own per-piece deadline check
 *  isn't enough on its own. */
object RealLocalInferenceEngine : LocalInferenceEngine {
    /** Longest [prepare] waits for a load. Generous: a cold load of a ~350 MB GGUF on a slow phone. */
    private const val PREPARE_TIMEOUT_MS = 60_000L

    override fun prepare(modelPath: String) {
        runCatching {
            BoundedBlockingCall.runWithDeadline(System.currentTimeMillis() + PREPARE_TIMEOUT_MS) {
                LocalCleanupModelHolder.withInference(modelPath) { /* load only */ }
            }
        }
    }

    override fun complete(
        systemPrompt: String,
        userText: String,
        modelPath: String,
        deadlineAtMs: Long,
        isCancelled: () -> Boolean,
    ): LocalInferenceResult =
        try {
            if (isCancelled()) {
                LocalInferenceResult.Cancelled
            } else {
                val text = BoundedBlockingCall.runWithDeadline(deadlineAtMs) {
                    LocalCleanupModelHolder.withInference(modelPath) { inference ->
                        inference.complete(systemPrompt, userText, deadlineAtMs, isCancelled)
                    }
                }
                when {
                    text == null -> LocalInferenceResult.TimedOut("Local cleanup timed out")
                    text.isNotBlank() -> validated(systemPrompt, userText, text)
                    else -> LocalInferenceResult.Failure("Local model produced an empty response")
                }
            }
        } catch (e: Throwable) {
            // Broad catch is deliberate: this boundary must absorb everything up to and
            // including UnsatisfiedLinkError so a native problem fails the LOCAL_LLM step
            // cleanly instead of crashing the accessibility service. Note that since the
            // native build wiring landed (#37), UnsatisfiedLinkError here indicates a real
            // packaging regression, not an expected gap (#87 item 5).
            //
            // Actually logged now (#96): a prior version of this comment *claimed* the failure
            // was logged but never called Log.e, which meant every real local-cleanup failure
            // (a fast-throwing load error, an OOM, a native crash) was silently swallowed into a
            // generic toast with no way to diagnose it from logcat -- exactly the gap that made
            // this failure mode invisible until adb-level thread/timing correlation exposed it.
            android.util.Log.e("RealLocalInferenceEngine", "Local cleanup inference failed for $modelPath", e)
            if (isCancelled()) LocalInferenceResult.Cancelled
            else LocalInferenceResult.Failure(e.message ?: "Local inference failed")
        }

    /**
     * Gates non-blank model output through [LocalCleanupOutputValidator] (#155) instead of
     * accepting any non-blank string, which was this engine's entire prior contract.
     *
     * A rejection becomes a [LocalInferenceResult.Failure], never [LocalInferenceResult.Cancelled]:
     * bad local output is this one step failing, so it must fall through to the next waterfall
     * step (or raw-text injection) exactly like a timeout does -- see [LocalInferenceResult.TimedOut]'s
     * kdoc for the live regression caused by conflating the two.
     */
    private fun validated(systemPrompt: String, userText: String, text: String): LocalInferenceResult =
        when (val verdict = LocalCleanupOutputValidator.validate(userText, systemPrompt, text)) {
            is LocalCleanupValidation.Valid -> LocalInferenceResult.Success(text)
            is LocalCleanupValidation.Rejected -> {
                // Log the reason, not the text: the prompt-echo case exists precisely because
                // the output can contain the user's private vocabulary terms.
                android.util.Log.w(
                    "RealLocalInferenceEngine",
                    "Rejected local cleanup output: ${verdict.reason} -- ${verdict.detail}",
                )
                LocalInferenceResult.Failure("Local cleanup output rejected (${verdict.reason})")
            }
        }
}

/** Production [CleanupHttpTransport]: a plain POST with per-step connect/read timeouts (ADR-0001). */
object RealCleanupHttpTransport : CleanupHttpTransport {
    override fun send(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeouts: CleanupStepTimeouts,
        cancelHolder: InFlightCall,
        callback: (CleanupHttpOutcome) -> Unit,
    ) {
        val client = NetworkClients.shared.newBuilder()
            .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
            .apply {
                // #137: bound the WHOLE call, not just its phases. Without this the step inherits
                // the shared client's own (much larger) callTimeout, which is how a step starting
                // just under CLEANUP_WATERFALL_HARD_CAP_MS could still overrun it by ~8.5s.
                timeouts.callMs?.let { callTimeout(it, TimeUnit.MILLISECONDS) }
            }
            .build()
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        // A malformed URL throws IllegalArgumentException from Request.Builder().url() rather
        // than failing the call; treated as a connection failure so it advances the waterfall
        // instead of crashing (mirrors PostProcessor.process's handling of a bad custom base URL).
        val request = try {
            requestBuilder.build()
        } catch (e: IllegalArgumentException) {
            // Query-redact: the exception message echoes the full URL, which must never carry a
            // credential into logcat/toast even if a future endpoint puts one in a query (M-3).
            callback(CleanupHttpOutcome.ConnectionFailure(UrlRedaction.redact(e.message)))
            return
        }

        val call = client.newCall(request)
        cancelHolder.set(call)
        // Close the set-after-cancel race: if cancel() already ran before this call was registered,
        // abort it immediately so it doesn't run to completion (and get billed) unnoticed (#83).
        if (cancelHolder.isCancelled) call.cancel()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancelHolder.clear(call)
                if (call.isCanceled()) {
                    callback(CleanupHttpOutcome.Cancelled)
                } else {
                    callback(CleanupHttpOutcome.ConnectionFailure(e.message))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                cancelHolder.clear(call)
                // A body read that dies mid-stream (stalled socket after headers arrived) must
                // report as a connection failure, not throw out of onResponse — OkHttp swallows
                // exceptions thrown here without ever calling onFailure, which would leave the
                // waterfall permanently stuck on this step (#62).
                HttpBodyReader.read(response).fold(
                    onSuccess = { responseBody ->
                        if (response.isSuccessful) {
                            callback(CleanupHttpOutcome.Ok(responseBody))
                        } else {
                            callback(CleanupHttpOutcome.HttpError(response.code, responseBody))
                        }
                    },
                    onFailure = { e -> callback(CleanupHttpOutcome.ConnectionFailure(e.message)) },
                )
            }
        })
    }
}

/**
 * The network-calling half of the cleanup waterfall (see ADR-0001 / docs/adr/0001-cleanup-
 * waterfall.md and [CleanupWaterfallPlanner] for the pure grouping logic this walks). Starts at
 * [cursor]'s last-known-good index, attempts steps in order, and on any [CleanupStepOutcome]
 * falls through to the next step — or, for a [CleanupStepOutcome.ConnectionFailed], skips every
 * remaining step in that same host group, since they'd fail identically against the same dead
 * host. Only an explicit temperature-parameter rejection gets one retry without that parameter;
 * other failures are not retried because this call blocks a user waiting on their transcript.
 *
 * Deliberately takes no [android.content.Context] — [credentialLookup] is the caller's seam onto
 * the credential store (via [ProviderChainRuntime.providerKindForCleanupSlot] +
 * [ProviderCredentialStore], see [PostProcessor.processProviderChain]), and [transport] is the seam
 * onto the network. Both let this object's step-sequencing logic run in a plain JVM unit test
 * with fakes, with no real I/O and no Android framework dependency.
 */
object CleanupWaterfallExecutor {
    fun execute(
        text: String,
        prompt: String,
        waterfall: CleanupWaterfall,
        cursor: CleanupWaterfallCursor,
        cancelHolder: InFlightCall,
        credentialLookup: (CleanupCredentialSlot) -> String,
        // #274: entry-based credential lookup, keyed by CleanupStep.entryId -- the actual fix for
        // #273 on the cleanup path. Preferred over [credentialLookup] whenever a step carries a
        // non-blank entryId AND this lookup has something configured for it; falls back to
        // [credentialLookup]'s slot-based value otherwise, so a step with no entryId (every
        // pre-#274 test fixture, and any defensive fallback path) behaves exactly as before this
        // parameter existed. Defaulted to "always miss" so no existing call site/test needs to
        // change to keep its current behavior.
        entryCredentialLookup: (String) -> String = { "" },
        transport: CleanupHttpTransport = RealCleanupHttpTransport,
        localInference: LocalInferenceEngine = RealLocalInferenceEngine,
        localModelPath: () -> String? = { null },
        // The LOCAL_LLM step always uses this prompt instead of [prompt], regardless of which
        // cloud persona the user has selected (#54 follow-up): today's on-device models are
        // small, general-purpose instruct models, not fine-tuned for this specific task the way
        // e.g. FluidVoice's Fluid-1 is -- they don't reliably follow DEV_PROMPT's long, few-shot,
        // XML-tagged instructions and can drift into answering/chatting instead of restyling.
        // PostProcessor.SIMPLE_PROMPT is a single short plain-language instruction with no
        // few-shot examples, which small models follow far more reliably. Deliberately a separate
        // parameter (not baked into the LOCAL_LLM branch below) so swapping it out later -- e.g.
        // once a properly fine-tuned local cleanup model replaces the current generic one -- is a
        // one-line change here, not a hunt through the executor.
        localPrompt: String = PostProcessor.SIMPLE_PROMPT,
        // Personal-vocabulary terms applied to LOCAL_LLM output as a deterministic post-pass
        // (#182 option 2, [VocabularyPostCorrector]). Cloud steps get vocabulary via prompt
        // interpolation on [prompt] instead, which works there -- the two paths are mutually
        // exclusive by construction, so terms are never applied twice to one result. Defaulted
        // empty so existing tests/call sites keep their exact behavior unless they opt in.
        localVocabulary: List<String> = emptyList(),
        // Injectable wall clock (defaults to the real one) so the hard-cap path is unit-testable
        // without sleeping -- previously it read System.currentTimeMillis() directly, so the
        // "exceeded time budget" branch had no deterministic test (audit test-gap #6). Its siblings
        // (localLlmStepDeadline, BoundedBlockingCall) already take an injected now.
        nowMs: () -> Long = { System.currentTimeMillis() },
        // Optional benchmark/quality-log correlation (GH #100, #102): when both are non-null,
        // [logStepOutcome] additionally emits a [BenchmarkLogger] entry AND a [QualityLogger]
        // entry for each step outcome, tagged with the same correlationId as this dictation's
        // transcription entries in both logs. Defaulted to null so every existing call
        // site/unit test (none of which is Android-context-aware) is unaffected.
        benchmarkContext: android.content.Context? = null,
        temperatureCacheContext: android.content.Context? = null,
        benchmarkCorrelationId: String? = null,
        // Reject cloud output that fails [CloudCleanupFidelityGuard] and deliver the raw transcript
        // instead. Defaults to on for the wording-preserving built-in prompts (Natural/Formal/
        // Casual) and off for rewriting ones (Notes, Email, Concise, custom). LOCAL_LLM output has
        // its own validator and is never checked here.
        fidelityGuard: Boolean = PostProcessor.preservesWording(prompt),
        // Ceiling on a chunked terminal LOCAL_LLM step (see [LOCAL_CHUNKED_TOTAL_BUDGET_MS]).
        // Live dictation keeps the default; the audio-file path passes [AUDIO_LOCAL_STEP_BUDGET_MS].
        localStepBudgetMs: Long = LOCAL_CHUNKED_TOTAL_BUDGET_MS,
        // Measured on-device pace, shared across chunked cleanups (and so across the pieces of one
        // audio job); see [LocalCleanupPaceStore]. Tests pass their own to stay independent.
        localPace: LocalCleanupPaceStore = LocalCleanupPaceStore.shared,
        callback: (PostProcessor.Result) -> Unit,
    ) {
        val steps = waterfall.steps
        if (steps.isEmpty()) {
            callback(PostProcessor.Result(null, "No cleanup steps configured"))
            return
        }

        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        val groupRanges = groupBoundaries(groups)
        val startedAtMs = nowMs()
        val startIndex = cursor.startIndex(startedAtMs).takeIf { it in steps.indices } ?: 0
        // One waterfall run = one unit of cancellable work (#83): clear any cancel left over
        // from a previous dictation, and give the synchronous LOCAL_LLM step the same wall-clock
        // budget the between-step check below enforces.
        cancelHolder.beginWork()
        val deadlineAtMs = startedAtMs + CLEANUP_WATERFALL_HARD_CAP_MS

        fun nextGroupStart(index: Int): Int {
            val range = groupRanges.first { index in it }
            return range.last + 1
        }

        fun attempt(index: Int, lastFailure: String?) {
            if (index >= steps.size) {
                // Carry the last step's failure detail (which now includes the provider's own error
                // message, see toStepOutcome) into the final result, so an all-steps-failed outcome
                // is diagnosable instead of an opaque "All cleanup steps failed" (L2).
                val message = lastFailure?.let { "All cleanup steps failed: $it" } ?: "All cleanup steps failed"
                callback(PostProcessor.Result(null, message))
                return
            }
            // A cancel that landed in the gap between steps (after clear(call), before the next
            // set(call)) has no Call to abort, so catch it here before starting a fresh — possibly
            // paid — step, the #63 fall-through this flag exists to prevent (#83).
            if (cancelHolder.isCancelled) {
                callback(PostProcessor.Result(null, "Cleanup cancelled"))
                return
            }
            if (nowMs() - startedAtMs >= CLEANUP_WATERFALL_HARD_CAP_MS) {
                callback(PostProcessor.Result(null, "Cleanup waterfall exceeded time budget"))
                return
            }

            performStep(steps[index], text, prompt, localPrompt, localVocabulary, credentialLookup, entryCredentialLookup, transport, localInference, localModelPath, cancelHolder, deadlineAtMs, nowMs, temperatureCacheContext, isLastStep = index == steps.lastIndex, localStepBudgetMs = localStepBudgetMs, localPace = localPace) { stepOutcome ->
                val outcome = if (fidelityGuard) guardCloudOutput(steps[index], text, stepOutcome) else stepOutcome
                logStepOutcome(steps[index], startedAtMs, outcome, benchmarkContext, benchmarkCorrelationId)
                when (outcome) {
                    is CleanupStepOutcome.Success -> {
                        cursor.recordSuccess(index, nowMs())
                        callback(PostProcessor.Result(outcome.text, null, outcome.partial, outcome.sections))
                    }
                    is CleanupStepOutcome.Rejected -> {
                        // The provider worked, so remember it; only its content was refused.
                        cursor.recordSuccess(index, nowMs())
                        callback(PostProcessor.Result(null, "Cloud cleanup output rejected (${outcome.reason.name})"))
                    }
                    is CleanupStepOutcome.StepFailed -> attempt(index + 1, outcome.message)
                    is CleanupStepOutcome.ConnectionFailed -> attempt(nextGroupStart(index), outcome.message)
                    is CleanupStepOutcome.Cancelled -> callback(PostProcessor.Result(null, "Cleanup cancelled"))
                }
            }
        }

        attempt(startIndex, null)
    }

    /** Applies [CloudCleanupFidelityGuard] to a cloud step's successful output (never LOCAL_LLM). */
    internal fun guardCloudOutput(step: CleanupStep, rawText: String, outcome: CleanupStepOutcome): CleanupStepOutcome {
        if (outcome !is CleanupStepOutcome.Success || step.group == CleanupStepGroup.LOCAL_LLM) return outcome
        return when (val verdict = CloudCleanupFidelityGuard.check(rawText, outcome.text)) {
            is FidelityVerdict.Ok -> outcome
            is FidelityVerdict.Rejected -> CleanupStepOutcome.Rejected(verdict.reason)
        }
    }

    /**
     * Logs which cleanup step actually served the request and how long the waterfall had run by
     * that point (#100 perceived-latency follow-up), plus the model id that served it and the
     * outcome (GH #100 benchmark logger) via [BenchmarkLogger]. [android.util.Log] isn't
     * available in the plain JVM unit tests this executor is designed to run in without Android,
     * so failures here are swallowed -- this is diagnostics only, never allowed to affect the
     * real outcome. [BenchmarkLogger.log] itself is a no-op-safe object call when [context] is
     * null (i.e. from a plain JVM unit test with no Android [android.content.Context] available)
     * so this path stays exercisable outside Android too.
     */
    private fun logStepOutcome(step: CleanupStep, startedAtMs: Long, outcome: CleanupStepOutcome, context: android.content.Context?, correlationId: String?) {
        runCatching {
            val elapsedMs = System.currentTimeMillis() - startedAtMs
            when (outcome) {
                is CleanupStepOutcome.Success -> android.util.Log.i("CleanupWaterfallExecutor", "Cleanup step ${step.group} succeeded at +${elapsedMs}ms")
                is CleanupStepOutcome.StepFailed -> android.util.Log.i("CleanupWaterfallExecutor", "Cleanup step ${step.group} failed at +${elapsedMs}ms: ${outcome.message}")
                is CleanupStepOutcome.ConnectionFailed -> android.util.Log.i("CleanupWaterfallExecutor", "Cleanup step ${step.group} connection failed at +${elapsedMs}ms: ${outcome.message}")
                is CleanupStepOutcome.Cancelled -> android.util.Log.i("CleanupWaterfallExecutor", "Cleanup step ${step.group} cancelled at +${elapsedMs}ms")
                // Reason only: the enum name carries no transcript or model text.
                is CleanupStepOutcome.Rejected -> android.util.Log.i("CleanupWaterfallExecutor", "Cleanup step ${step.group} output rejected at +${elapsedMs}ms: ${outcome.reason.name}")
            }
        }
        if (context != null && correlationId != null) {
            runCatching {
                BenchmarkLogger.log(
                    context = context,
                    correlationId = correlationId,
                    cleanup = BenchmarkStage(
                        provider = step.group.name,
                        model = step.model,
                        latencyMs = System.currentTimeMillis() - startedAtMs,
                        success = outcome is CleanupStepOutcome.Success,
                        // #138: the reason already existed here and was only going to logcat,
                        // which rotates within hours. Sanitized because a provider error body can
                        // echo request content and this log is deliberately length-only.
                        error = sanitizeError(
                            when (outcome) {
                                is CleanupStepOutcome.StepFailed -> outcome.message
                                is CleanupStepOutcome.ConnectionFailed -> outcome.message
                                is CleanupStepOutcome.Cancelled -> "Cancelled"
                                is CleanupStepOutcome.Rejected -> "Output rejected (${outcome.reason.name})"
                                is CleanupStepOutcome.Success -> null
                            }
                        ),
                    ),
                    cleanedTextLength = (outcome as? CleanupStepOutcome.Success)?.text?.length,
                )
            }
            runCatching {
                QualityLogger.log(
                    context = context,
                    correlationId = correlationId,
                    cleanup = QualityStage(
                        provider = step.group.name,
                        model = step.model,
                    ),
                    cleanedText = (outcome as? CleanupStepOutcome.Success)?.text,
                )
            }
        }
    }

    private fun groupBoundaries(groups: List<List<CleanupStep>>): List<IntRange> {
        var offset = 0
        return groups.map { group ->
            (offset until offset + group.size).also { offset += group.size }
        }
    }

    /**
     * One on-device cleanup pass over [text]: normalize spoken numbers, run the model (the engine
     * validates its output, see [RealLocalInferenceEngine]), then re-check numbers and apply the
     * personal-vocabulary post-pass. This is the entire pre-#325 LOCAL_LLM step, unchanged -- a
     * short transcript calls it once with the step's own deadline, and the chunked path calls it
     * once per chunk, so a chunk is held to exactly the standard a whole short dictation is.
     */
    private fun cleanLocalPass(
        text: String,
        localPrompt: String,
        localVocabulary: List<String>,
        localInference: LocalInferenceEngine,
        modelPath: String,
        deadlineAtMs: Long,
        cancelHolder: InFlightCall,
    ): CleanupStepOutcome {
        val normalization = SpokenNumberNormalizer.normalize(text)
        return when (
            val result = localInference.complete(
                localPrompt,
                normalization.text,
                modelPath,
                deadlineAtMs,
                { cancelHolder.isCancelled },
            )
        ) {
            is LocalInferenceResult.Success -> {
                // Trim to match what both cloud parsers already do to their model text.
                val trimmed = result.text.trim()
                when {
                    trimmed.isEmpty() -> CleanupStepOutcome.StepFailed("Local model produced an empty response")
                    NumericPreservationVerifier.verify(normalization, trimmed) is NumericPreservation.Rejected ->
                        CleanupStepOutcome.StepFailed("Local cleanup output rejected (NUMERIC_DIVERGENCE)")
                    // #182 option 2: personal vocabulary reaches local cleanup as a
                    // deterministic post-pass over ACCEPTED output, never as prompt
                    // text (which made LFM2.5 echo the term list). Runs after the
                    // validator and the numeric check so corrections are applied to
                    // output that has already earned acceptance -- and never to text
                    // that is about to be discarded for a fallback step.
                    else -> CleanupStepOutcome.Success(VocabularyPostCorrector.correct(trimmed, localVocabulary))
                }
            }
            is LocalInferenceResult.Failure -> CleanupStepOutcome.StepFailed(result.message)
            is LocalInferenceResult.TimedOut -> CleanupStepOutcome.StepFailed(result.message)
            is LocalInferenceResult.Cancelled -> CleanupStepOutcome.Cancelled
        }
    }

    /**
     * Terminal LOCAL_LLM step for a transcript too long for one pass (#325): split it at sentence
     * boundaries ([LocalCleanupChunker]), clean the chunks in order through [cleanLocalPass] on the
     * already-loaded model, and re-join them.
     *
     * **Failure policy.** Each chunk stands alone. A chunk whose pass fails (validator rejection,
     * 512-piece cap, loop detection, its own allowance expiring, native error) is
     * kept as its raw text and the run continues, so one bad sentence cannot cost the user the
     * other 34 chunks of cleanup. The result is a [CleanupStepOutcome.Success] as long as at least
     * one chunk was cleaned, carrying [PartialCleanup] when any chunk fell back; with no chunk
     * cleaned it is a [CleanupStepOutcome.StepFailed] with the last chunk's reason, i.e. exactly
     * what a failed single pass reports, so the existing notices and fall-through still apply.
     * Output of a chunk that fails is never mixed in half-way: it is raw or cleaned, nothing else.
     *
     * **Bounds.** Cancellation is checked before every chunk (and inside each pass by the engine),
     * so a cancel stops the run within one chunk. Three things bound time:
     *
     *  - Each chunk's allowance comes from [LocalCleanupChunkBudget]: the device's measured
     *    ms-per-character times a safety factor, never below the flat 20 s of #327, with a roomy
     *    allowance for the first chunk (nothing measured yet) and a ceiling for a stuck decode.
     *    A fixed 20 s was tuned on one phone and timed out 3 of 40 chunks even on a Pixel 10a with
     *    mumble-cleanup-2stage, whose per-chunk time is 10-18 s; on a slower phone it would time
     *    out every chunk.
     *  - [stepBudgetMs] bounds the step. Before each chunk the measured pace is compared with what
     *    is left: a chunk that cannot finish in the remainder is not started, and everything from
     *    there is delivered raw (the honest partial result), instead of running a chunk into the
     *    deadline and wasting its time.
     *  - [LOCAL_CHUNK_MAX_CONSECUTIVE_TIMEOUTS] stops early on a device too slow for the model.
     *
     * Only the terminal step chunks: a non-terminal local step has a 4s budget that
     * exists to protect the cloud step behind it, and that step is the better place for a long
     * transcript anyway.
     *
     * The measured pace comes from [pace] and outlives this call: the pieces of one audio job share
     * it, so only the very first chunk of the job runs on the roomy first-chunk allowance.
     *
     * The model is loaded before the first chunk's clock starts ([LocalInferenceEngine.prepare])
     * and stays loaded across chunks ([LocalCleanupModelHolder] reuses it, including after a
     * timeout, and each chunk refreshes its idle clock, so the 5-minute idle unload cannot fire
     * mid-run).
     */
    private fun cleanLocalInChunks(
        text: String,
        localPrompt: String,
        localVocabulary: List<String>,
        localInference: LocalInferenceEngine,
        modelPath: String,
        cancelHolder: InFlightCall,
        nowMs: () -> Long,
        stepBudgetMs: Long,
        pace: LocalCleanupPaceStore,
    ): CleanupStepOutcome {
        val chunks = LocalCleanupChunker.split(text, LocalCleanupChunker.maxChunkChars(localPrompt))
        val startedAtMs = nowMs()
        val stepDeadlineAtMs = startedAtMs + stepBudgetMs
        if (cancelHolder.isCancelled) return CleanupStepOutcome.Cancelled
        runCatching { localInference.prepare(modelPath) }
        // Shared with the other pieces of this job: not restarted at the first-chunk allowance.
        val budget = pace.budgetFor(modelPath, nowMs())
        val texts = ArrayList<String>(chunks.size)
        var cleaned = 0
        var consecutiveTimeouts = 0
        var lastFailure: String? = null
        var stoppedEarly: String? = null
        for ((index, chunk) in chunks.withIndex()) {
            if (cancelHolder.isCancelled) return CleanupStepOutcome.Cancelled
            if (stoppedEarly == null) {
                val remainingMs = stepDeadlineAtMs - nowMs()
                val expectedMs = budget.expectedMs(chunk.text.length)
                if (remainingMs <= 0 || (expectedMs != null && expectedMs > remainingMs) ||
                    (expectedMs == null && remainingMs < LocalCleanupChunkBudget.MIN_BUDGET_MS)
                ) {
                    stoppedEarly = "step budget of ${stepBudgetMs}ms cannot fit another chunk " +
                        "(${remainingMs}ms left, ~${expectedMs ?: "?"}ms needed)"
                } else if (consecutiveTimeouts >= LOCAL_CHUNK_MAX_CONSECUTIVE_TIMEOUTS) {
                    stoppedEarly = "$consecutiveTimeouts consecutive chunks timed out"
                }
            }
            if (stoppedEarly != null) {
                texts += chunk.text
                continue
            }
            val chunkStartedAtMs = nowMs()
            val allowedMs = minOf(budget.budgetMs(chunk.text.length), stepDeadlineAtMs - chunkStartedAtMs)
            val outcome = cleanLocalPass(
                chunk.text, localPrompt, localVocabulary, localInference, modelPath,
                chunkStartedAtMs + allowedMs, cancelHolder,
            )
            val tookMs = nowMs() - chunkStartedAtMs
            pace.touch(modelPath, nowMs())
            when (outcome) {
                is CleanupStepOutcome.Cancelled -> return outcome
                is CleanupStepOutcome.Success -> {
                    texts += outcome.text
                    cleaned++
                    consecutiveTimeouts = 0
                    budget.recordCompleted(chunk.text.length, tookMs)
                    logChunk(index, chunks.size, chunk.text.length, outcome.text.length, tookMs, "cleaned", allowedMs)
                }
                else -> {
                    val message = when (outcome) {
                        is CleanupStepOutcome.StepFailed -> outcome.message
                        is CleanupStepOutcome.ConnectionFailed -> outcome.message
                        else -> null
                    }
                    lastFailure = message ?: lastFailure
                    val timedOut = isTimeoutMessage(message)
                    consecutiveTimeouts = if (timedOut) consecutiveTimeouts + 1 else 0
                    // A timeout is evidence about the device (it ran at least this long); a validator
                    // rejection is not about speed and is left out of the estimate.
                    if (timedOut) budget.recordTimedOut(chunk.text.length, allowedMs)
                    texts += chunk.text
                    logChunk(index, chunks.size, chunk.text.length, chunk.text.length, tookMs, "raw: ${message ?: "failed"}", allowedMs)
                }
            }
        }
        runCatching {
            android.util.Log.i(
                "CleanupWaterfallExecutor",
                "Chunked local cleanup: $cleaned/${chunks.size} chunks cleaned in ${nowMs() - startedAtMs}ms" +
                    (stoppedEarly?.let { " (stopped early: $it)" } ?: ""),
            )
        }
        if (cleaned == 0) {
            return CleanupStepOutcome.StepFailed(lastFailure ?: stoppedEarly ?: "Local cleanup produced no usable chunk")
        }
        val partial = if (cleaned < chunks.size) PartialCleanup(cleaned, chunks.size) else null
        return CleanupStepOutcome.Success(LocalCleanupChunker.join(chunks, texts), partial, PartialCleanup(cleaned, chunks.size))
    }

    /** Failure messages that mean "ran out of time", as opposed to "model said something unusable":
     *  the engine's [LocalInferenceResult.TimedOut], the accumulator's between-piece deadline, and
     *  the native ggml abort. */
    private fun isTimeoutMessage(message: String?): Boolean =
        message != null && (
            message.contains("timed out") ||
                message.contains("wall-clock deadline") ||
                message.contains("inference budget exceeded")
            )

    /** Lengths and timing only -- never transcript or model text. */
    private fun logChunk(index: Int, total: Int, inChars: Int, outChars: Int, tookMs: Long, verdict: String, allowedMs: Long) {
        runCatching {
            android.util.Log.i(
                "CleanupWaterfallExecutor",
                "Local chunk ${index + 1}/$total: ${inChars}ch -> ${outChars}ch in ${tookMs}ms of ${allowedMs}ms ($verdict)",
            )
        }
    }

    /** Resolves one step's credential, builds its wire-format-specific request, and interprets the result. */
    private fun performStep(
        step: CleanupStep,
        text: String,
        prompt: String,
        localPrompt: String,
        localVocabulary: List<String>,
        credentialLookup: (CleanupCredentialSlot) -> String,
        entryCredentialLookup: (String) -> String,
        transport: CleanupHttpTransport,
        localInference: LocalInferenceEngine,
        localModelPath: () -> String?,
        cancelHolder: InFlightCall,
        deadlineAtMs: Long,
        nowMs: () -> Long,
        temperatureCacheContext: android.content.Context?,
        isLastStep: Boolean,
        localStepBudgetMs: Long,
        localPace: LocalCleanupPaceStore,
        callback: (CleanupStepOutcome) -> Unit,
    ) {
        // LOCAL_LLM branches before the credential check below: it's the one group with no
        // credential slot and no network host, so "no credential configured" would be a
        // misleading failure reason for it (#37).
        if (step.group == CleanupStepGroup.LOCAL_LLM) {
            val modelPath = localModelPath()
            if (modelPath.isNullOrBlank()) {
                callback(CleanupStepOutcome.StepFailed("Local cleanup model not downloaded"))
                return
            }
            // #325: a transcript too long for one pass (see [LocalCleanupChunker]) is cleaned in
            // chunks, each with its own budget. Terminal step only -- a short transcript, or any
            // non-terminal local step, falls through to the single-pass code below, unchanged.
            if (isLastStep && LocalCleanupChunker.needsChunking(text, localPrompt)) {
                callback(
                    cleanLocalInChunks(
                        text, localPrompt, localVocabulary, localInference, modelPath, cancelHolder, nowMs, localStepBudgetMs, localPace,
                    )
                )
                return
            }

            // Bounded to LOCAL_LLM_STEP_BUDGET_MS (#98) so a slow/aborted local attempt still
            // leaves real time for a subsequent cloud step's own CleanupStepTimeouts to run --
            // except when this LOCAL_LLM step is the LAST step in the whole waterfall (#37
            // follow-up: Trevor's simple "Local" cleanup choice has nothing after it to protect),
            // in which case it gets the real remaining deadline like every other step group.
            // waterfallStartedAtMs is left to its default, which recovers the waterfall's start
            // as `deadlineAtMs - CLEANUP_WATERFALL_HARD_CAP_MS` -- exact here, since execute()
            // builds deadlineAtMs as startedAtMs + CLEANUP_WATERFALL_HARD_CAP_MS.
            val localDeadlineAtMs = localLlmStepDeadline(deadlineAtMs, System.currentTimeMillis(), isLastStep)
            callback(
                cleanLocalPass(text, localPrompt, localVocabulary, localInference, modelPath, localDeadlineAtMs, cancelHolder)
            )
            return
        }

        val apiKey = if (step.entryId.isNotBlank()) {
            entryCredentialLookup(step.entryId).ifBlank { step.credentialSlot()?.let(credentialLookup) ?: "" }
        } else {
            step.credentialSlot()?.let(credentialLookup) ?: ""
        }
        if (apiKey.isBlank()) {
            // No live host was ever contacted, so this is a step-level (not connection-level)
            // failure: it only skips this one step, not the rest of its group.
            callback(CleanupStepOutcome.StepFailed("No credential configured for ${step.group}"))
            return
        }

        if (step.group == CleanupStepGroup.ANTHROPIC_DIRECT) {
            transport.send(
                AnthropicCleanupProvider.ENDPOINT_URL,
                AnthropicCleanupProvider.headers(apiKey),
                AnthropicCleanupProvider.buildRequestBody(text, prompt, step.model).toString(),
                cloudStepTimeouts(deadlineAtMs, nowMs()),
                cancelHolder,
            ) { httpOutcome -> callback(toStepOutcome(httpOutcome, AnthropicCleanupProvider::parseResponse)) }
            return
        }

        if (step.group == CleanupStepGroup.GEMINI_DIRECT) {
            val cache = temperatureCacheContext?.let(TemperatureCapabilityStore::forContext)
            val identity = cache?.let { TemperatureCapabilityStore.identity(ProviderKind.GEMINI,
                GeminiCleanupProvider.endpointUrl(step.model), GeminiCleanupProvider.headers(apiKey), step.model,
                "", "", 0.0, step.entryId) }
            val body = GeminiCleanupProvider.buildRequestBody(text, prompt)
            val omit = cache != null && identity != null && cache.state(identity) == TemperatureCapabilityStore.State.UNSUPPORTED
            if (omit) body.optJSONObject("generationConfig")?.remove("temperature")
            executeTemperatureAwareRequest(
                url = GeminiCleanupProvider.endpointUrl(step.model),
                headers = GeminiCleanupProvider.headers(apiKey),
                body = body,
                transport = transport,
                cancelHolder = cancelHolder,
                deadlineAtMs = deadlineAtMs,
                nowMs = nowMs,
                cache = cache,
                identity = identity,
                parse = GeminiCleanupProvider::parseResponse,
                hasTemperature = { it.optJSONObject("generationConfig")?.has("temperature") == true },
                removeTemperature = { it.optJSONObject("generationConfig")?.remove("temperature") },
                timeoutMs = { cloudStepTimeouts(deadlineAtMs, nowMs()) },
                callback = callback,
            )
            return
        }

        val baseUrl = when (step.group) {
            CleanupStepGroup.OMNIROUTE -> OmniRoute.BASE_URL
            CleanupStepGroup.OPENAI_DIRECT -> step.baseUrlOverride ?: PostProcessor.DEFAULT_BASE_URL
            CleanupStepGroup.ANTHROPIC_DIRECT -> "" // unreachable, handled above
            CleanupStepGroup.GEMINI_DIRECT -> "" // unreachable, handled above
            CleanupStepGroup.LOCAL_LLM -> "" // unreachable, handled above
        }
        val temperatureStore = temperatureCacheContext?.let(TemperatureCapabilityStore::forContext)
        val temperatureIdentity = temperatureStore?.let {
            TemperatureCapabilityStore.identity(
                if (step.group == CleanupStepGroup.OMNIROUTE) ProviderKind.OMNIROUTE else ProviderKind.OPENAI,
                PostProcessor.endpointUrl(baseUrl), mapOf("Authorization" to "Bearer $apiKey"), step.model,
                "", "stream=false", 0.0, step.entryId,
            )
        }
        val initialBody = PostProcessor.buildRequestBody(text, prompt, step.model)
        val omitKnownUnsupported = temperatureStore != null && temperatureIdentity != null &&
            temperatureStore.state(temperatureIdentity) == TemperatureCapabilityStore.State.UNSUPPORTED
        if (omitKnownUnsupported) initialBody.remove("temperature")
        executeTemperatureAwareRequest(
            url = PostProcessor.endpointUrl(baseUrl),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = initialBody,
            transport = transport,
            cancelHolder = cancelHolder,
            deadlineAtMs = deadlineAtMs,
            nowMs = nowMs,
            cache = temperatureStore,
            identity = temperatureIdentity,
            parse = PostProcessor::parseResponse,
            hasTemperature = { it.has("temperature") },
            removeTemperature = { it.remove("temperature") },
            timeoutMs = { cloudStepTimeouts(deadlineAtMs, nowMs()) },
            callback = callback,
        )
    }

    private fun executeTemperatureAwareRequest(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        transport: CleanupHttpTransport,
        cancelHolder: InFlightCall,
        deadlineAtMs: Long,
        nowMs: () -> Long,
        cache: TemperatureCapabilityStore?,
        identity: TemperatureCapabilityStore.Identity?,
        parse: (String) -> PostProcessor.Result,
        hasTemperature: (JSONObject) -> Boolean,
        removeTemperature: (JSONObject) -> Any?,
        timeoutMs: () -> CleanupStepTimeouts,
        callback: (CleanupStepOutcome) -> Unit,
    ) {
        val sentWithoutTemperature = !hasTemperature(body)
        val observationSequence = if (!sentWithoutTemperature && cache != null && identity != null)
            cache.beginObservation(identity) else null
        transport.send(url, headers, body.toString(), timeoutMs(), cancelHolder) { outcome ->
            val rejectsTemperature = !sentWithoutTemperature && outcome is CleanupHttpOutcome.HttpError &&
                TemperatureRejectionClassifier.isTemperatureRejection(outcome.body, outcome.code)
            if (rejectsTemperature) {
                if (cancelHolder.isCancelled) {
                    if (cache != null && identity != null && observationSequence != null)
                        cache.finishObservation(identity, observationSequence)
                    callback(CleanupStepOutcome.Cancelled)
                } else {
                    if (cache != null && identity != null && observationSequence != null)
                        cache.record(identity, TemperatureCapabilityStore.State.UNSUPPORTED, observationSequence)
                    val retryTimeouts = temperatureRetryTimeouts(deadlineAtMs, nowMs())
                    if (retryTimeouts == null) callback(toStepOutcome(outcome, parse))
                    else {
                        val retryBody = JSONObject(body.toString()).apply { removeTemperature(this) }.toString()
                        transport.send(url, headers, retryBody, retryTimeouts, cancelHolder) { retry ->
                            callback(toStepOutcome(retry, parse))
                        }
                    }
                }
            } else {
                if (cache != null && identity != null && observationSequence != null) {
                    val supported = outcome is CleanupHttpOutcome.Ok && !parse(outcome.body).text.isNullOrBlank()
                    if (supported) cache.record(identity, TemperatureCapabilityStore.State.SUPPORTED, observationSequence)
                    else cache.finishObservation(identity, observationSequence)
                }
                callback(toStepOutcome(outcome, parse))
            }
        }
    }

    private fun toStepOutcome(httpOutcome: CleanupHttpOutcome, parse: (String) -> PostProcessor.Result): CleanupStepOutcome =
        when (httpOutcome) {
            is CleanupHttpOutcome.Cancelled -> CleanupStepOutcome.Cancelled
            is CleanupHttpOutcome.ConnectionFailure -> CleanupStepOutcome.ConnectionFailed(httpOutcome.message)
            is CleanupHttpOutcome.HttpError ->
                // Include the provider's own error message (e.g. "invalid x-api-key"), not just the
                // bare status code, so a failed waterfall step is diagnosable instead of an opaque
                // "HTTP 401" (L2). Every provider returns a {"error":{"message":...}} envelope; a
                // body that isn't one falls back to a truncated raw snippet.
                CleanupStepOutcome.StepFailed("HTTP ${httpOutcome.code}: ${errorDetail(httpOutcome.body)}")
            is CleanupHttpOutcome.Ok -> {
                val result = parse(httpOutcome.body)
                if (!result.text.isNullOrBlank()) CleanupStepOutcome.Success(result.text)
                else CleanupStepOutcome.StepFailed(result.error)
            }
        }

    /** Best-effort human-readable detail from a non-2xx response body: the provider error
     *  envelope's message if present, otherwise a whitespace-collapsed, truncated raw snippet. */
    private fun errorDetail(body: String): String {
        val fromEnvelope = runCatching {
            org.json.JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (fromEnvelope != null) return fromEnvelope
        val snippet = body.trim().replace(Regex("\\s+"), " ")
        return if (snippet.isEmpty()) "no response body" else snippet.take(200)
    }
}
