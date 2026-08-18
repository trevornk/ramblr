package com.trevornk.ramblr

/**
 * Context-free parsing and decision logic behind [ProcessTextActivity] (#157 Option B: clean up
 * selected text from Android's text-selection menu via `ACTION_PROCESS_TEXT`).
 *
 * Everything here is a pure function over plain values, deliberately kept out of the Activity:
 * this module has no Robolectric and `android.util.Log` is unmocked in plain-JVM tests, so
 * anything that touches a framework class cannot be unit-tested at all. The same split the rest
 * of the codebase already uses ([LocalCleanupProvider.systemPromptFor] vs. its Context wrapper,
 * [CleanupWaterfallCursor] vs. its Android-owned reset triggers) applies here.
 */

/** A validated, non-blank selection handed to the Activity by [ProcessTextIntent.parse]. */
data class ProcessTextRequest(
    val text: String,
    /** Mirrors `Intent.EXTRA_PROCESS_TEXT_READONLY`: the host is telling us it will ignore any
     *  replacement we return, so the only honest delivery is the clipboard (see
     *  [ProcessTextDelivery]). */
    val readOnly: Boolean,
)

/** Outcome of reading the incoming `ACTION_PROCESS_TEXT` extras. */
sealed class ProcessTextParse {
    data class Accepted(val request: ProcessTextRequest) : ProcessTextParse()

    /** No selection worth cleaning: the extra was absent, empty, or whitespace only. */
    object EmptySelection : ProcessTextParse()

    /** Selection past [ProcessTextIntent.MAX_SELECTION_CHARS]. */
    data class TooLong(val length: Int, val limit: Int) : ProcessTextParse()
}

object ProcessTextIntent {
    /**
     * Upper bound on a selection this entry point will accept.
     *
     * Two independent reasons, either of which alone justifies a cap: (1) the cleaned string
     * travels back to the host inside an Intent extra, and an oversized parcel is a
     * `TransactionTooLargeException` in the *host's* process, not a catchable failure in ours;
     * (2) [CLEANUP_WATERFALL_HARD_CAP_MS] is an 8s budget sized for a dictation-length
     * transcript, so a book-length selection cannot complete anyway and would just burn paid
     * cloud tokens before timing out. 20k characters is far above any realistic hand-selection
     * and far below the binder limit.
     */
    const val MAX_SELECTION_CHARS = 20_000

    /**
     * Validates the incoming extras. [rawText] is `Intent.EXTRA_PROCESS_TEXT` (a `CharSequence`,
     * possibly styled — the style is dropped deliberately, since the cleanup providers take and
     * return plain text and re-applying spans to rewritten text is not well defined).
     *
     * The accepted text is trimmed: hosts routinely include the trailing space or newline the
     * selection handle snapped to, and sending it to a model adds nothing.
     */
    fun parse(rawText: CharSequence?, readOnly: Boolean): ProcessTextParse {
        val text = rawText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return ProcessTextParse.EmptySelection
        if (text.length > MAX_SELECTION_CHARS) {
            return ProcessTextParse.TooLong(text.length, MAX_SELECTION_CHARS)
        }
        return ProcessTextParse.Accepted(ProcessTextRequest(text, readOnly))
    }
}

/** How a cleaned result can be handed back to the user. */
enum class ProcessTextDelivery {
    /** Return it as `Intent.EXTRA_PROCESS_TEXT` with `RESULT_OK`; the host performs the replacement. */
    REPLACE_IN_HOST,

    /**
     * Copy it to the clipboard and say so. Used when the host declared the field read-only, where
     * returning a result is silently discarded.
     *
     * Note this is also the *fallback* for hosts that accept a non-read-only selection but then
     * ignore `setResult` anyway: the Activity always copies before returning, so those hosts
     * degrade to "it's on your clipboard" instead of losing the work. SwiftSlate solves that case
     * with a TTL handoff to its accessibility service; #157 explicitly rejects that design,
     * because watching for the host's text-changed event re-introduces the whole Option A privacy
     * cost that this option exists to avoid.
     */
    CLIPBOARD_ONLY,
}

/** The read-only decision, isolated so it is testable and cannot drift into a silent no-op. */
fun deliveryFor(readOnly: Boolean): ProcessTextDelivery =
    if (readOnly) ProcessTextDelivery.CLIPBOARD_ONLY else ProcessTextDelivery.REPLACE_IN_HOST

/** Why a selection cannot be cleaned up with the user's current provider configuration. */
enum class ProcessTextUnavailableReason {
    /** The chain has cleanup-capable cloud entries, but "Use cloud for Cleanup" is off and the
     *  chain has no LOCAL entry to fall back to. Actionable: the user can flip the toggle. */
    CLOUD_CLEANUP_DISABLED,

    /** The chain has no executable cleanup step at all, toggle or no toggle. */
    NO_CLEANUP_CONFIGURED,

    /** A single-OpenAI chain with no key: there is no second step to fall through to, so this is
     *  worth failing fast on before making a network call that can only fail. Mirrors
     *  [WhisperAccessibilityService]'s identical pre-flight on the dictation path. */
    MISSING_OPENAI_KEY,
}

sealed class ProcessTextCleanupPlan {
    data class Ready(val chain: ProviderChain, val waterfall: CleanupWaterfall) : ProcessTextCleanupPlan()
    data class Unavailable(val reason: ProcessTextUnavailableReason) : ProcessTextCleanupPlan()
}

/**
 * Resolves which providers a selection-menu cleanup may use, applying exactly the same gates the
 * dictation path applies at `WhisperAccessibilityService.handleTranscriptionResult`.
 *
 * The [CloudFeatureToggle] gate matters more here than it does for dictation (#157): the
 * selection menu is a far cheaper gesture than speaking, so it can reach paid providers many more
 * times per day. Honouring the existing toggle rather than inventing a second one means a user
 * who has already said "no cloud for cleanup" is not silently opted back in by a new entry point.
 */
object ProcessTextCleanupPlanner {
    fun plan(
        chain: ProviderChain,
        cloudCleanupEnabled: Boolean,
        allowLocalFallback: Boolean,
        isCredentialConfigured: (ProviderKind) -> Boolean,
    ): ProcessTextCleanupPlan {
        val effective = ProviderChainRuntime.effectiveChainForCleanup(chain, cloudCleanupEnabled, allowLocalFallback)
        val waterfall = ProviderChainRuntime.cleanupWaterfallFor(effective)
        if (waterfall.steps.isEmpty()) {
            val cloudGateIsTheCause = !cloudCleanupEnabled &&
                ProviderChainRuntime.cleanupWaterfallFor(chain).steps.isNotEmpty()
            return ProcessTextCleanupPlan.Unavailable(
                if (cloudGateIsTheCause) {
                    ProcessTextUnavailableReason.CLOUD_CLEANUP_DISABLED
                } else {
                    ProcessTextUnavailableReason.NO_CLEANUP_CONFIGURED
                }
            )
        }
        if (!ProviderChainRuntime.shouldUseCleanupExecutor(effective) && !isCredentialConfigured(ProviderKind.OPENAI)) {
            return ProcessTextCleanupPlan.Unavailable(ProcessTextUnavailableReason.MISSING_OPENAI_KEY)
        }
        return ProcessTextCleanupPlan.Ready(effective, waterfall)
    }
}

/** What to do with a finished [PostProcessor.Result]. */
sealed class ProcessTextOutcome {
    data class Cleaned(val text: String) : ProcessTextOutcome()

    /**
     * Cleanup produced nothing usable. Unlike dictation — where injecting the raw transcript is
     * strictly better than injecting nothing, because the user's speech would otherwise be lost —
     * the selected text is still sitting in the host field, so writing it back unchanged would be
     * an invisible no-op that reads as "Ramblr did nothing". [reason] carries the provider's own
     * error so the failure is diagnosable instead of a generic "cleanup failed" (#98).
     */
    data class Failed(val reason: String) : ProcessTextOutcome()
}

/** Maps a [PostProcessor.Result]'s two nullable fields onto one exhaustive outcome. */
fun processTextOutcome(cleanedText: String?, error: String?): ProcessTextOutcome =
    if (cleanedText != null && cleanedText.isNotBlank()) {
        ProcessTextOutcome.Cleaned(cleanedText)
    } else {
        ProcessTextOutcome.Failed(error?.takeIf { it.isNotBlank() } ?: "unknown error")
    }
