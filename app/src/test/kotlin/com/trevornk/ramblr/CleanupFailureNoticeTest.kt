package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #175: what the floating bubble says when cleanup fails and Ramblr inserts the raw transcript.
 *
 * The issue was filed as "no user-visible signal". That turned out to be wrong -- the bubble has
 * fired since #98 -- so these tests pin the two things that were actually broken: a false claim
 * about the clipboard, and raw executor diagnostics being rendered in an overlay.
 *
 * The `RAW_` constants below are verbatim strings produced by [CleanupWaterfallExecutor], captured
 * by running its real `execute()` against fake engines. They are fixtures rather than paraphrases
 * because the mapping is substring-based: if the executor ever reworks its prefixes, these tests
 * must fail rather than silently degrade every bubble to "unknown error".
 */
class CleanupFailureNoticeTest {

    // --- verbatim executor output ---------------------------------------------------------------

    // Measured: local-only chain, terminal step rejected by LocalCleanupOutputValidator. Note the
    // nested prefix -- this is why summarize() matches on `contains`, not equality.
    private val rawPromptEcho =
        "All cleanup steps failed: Local cleanup output rejected (PROMPT_ECHO)"
    private val rawLengthCollapse =
        "All cleanup steps failed: Local cleanup output rejected (LENGTH_COLLAPSE)"
    private val rawLengthExpansion =
        "All cleanup steps failed: Local cleanup output rejected (LENGTH_EXPANSION)"
    private val rawNumericDivergence =
        "All cleanup steps failed: Local cleanup output rejected (NUMERIC_DIVERGENCE)"
    private val rawTimeout = "All cleanup steps failed: Local cleanup timed out"
    private val rawEmpty = "All cleanup steps failed: Local model produced an empty response"
    private val rawBudget = "Cleanup waterfall exceeded time budget"

    // A cloud step failing. errorDetail() splices in up to 200 chars of the provider's own body.
    private val rawHttp401 =
        "All cleanup steps failed: HTTP 401: Incorrect API key provided: sk-proj-********. " +
            "You can find your API key at https://platform.openai.com/account/api-keys."

    // --- the clipboard claim ------------------------------------------------------------------

    @Test fun `a DIRECT injection never claims the raw text is on the clipboard`() {
        // The actual #175 defect. clipboardClearActionFor(DIRECT) is Immediate, so the old
        // hardcoded "raw copied to clipboard" pointed the user at a clipboard Ramblr had just
        // wiped. Same bug #118 fixed for the success message.
        val message = CleanupFailureNotice.messageFor(InjectMethod.DIRECT, rawPromptEcho)
        assertFalse("must not promise a clipboard copy: \"$message\"", message.contains("clipboard"))
        assertTrue("must say what actually happened: \"$message\"", message.contains("inserted raw text"))
    }

    @Test fun `a FROM_CLIPBOARD injection does not send the user to the clipboard either`() {
        // Delayed clear, not None: the copy is transient, so pointing at it is still wrong.
        val message = CleanupFailureNotice.messageFor(InjectMethod.FROM_CLIPBOARD, rawPromptEcho)
        assertFalse("must not promise a clipboard copy: \"$message\"", message.contains("clipboard"))
    }

    @Test fun `a NONE injection does not claim text was inserted`() {
        // Nothing was injected -- the clipboard IS the delivery path here. Claiming an insert
        // would be the mirror-image lie. The injection seam appends its own "tap to copy again".
        val message = CleanupFailureNotice.messageFor(InjectMethod.NONE, rawPromptEcho)
        assertFalse("nothing was inserted: \"$message\"", message.contains("inserted"))
        assertEquals("Cleanup failed (model repeated its instructions)", message)
    }

    // --- keeping diagnostics out of the overlay -------------------------------------------------

    @Test fun `the notice never leaks executor prefixes into the bubble`() {
        val message = CleanupFailureNotice.messageFor(InjectMethod.DIRECT, rawPromptEcho)
        assertFalse("internal prefix leaked: \"$message\"", message.contains("All cleanup steps failed"))
        assertFalse("validator constant leaked: \"$message\"", message.contains("PROMPT_ECHO"))
    }

    @Test fun `a verbose provider error body never reaches the bubble`() {
        // errorDetail() allows 200 chars of provider prose. Rendering that in a WRAP_CONTENT pill
        // beside the floating icon is the failure mode this guards. The status code survives
        // because it's actionable -- 401 means the key is wrong.
        val message = CleanupFailureNotice.messageFor(InjectMethod.DIRECT, rawHttp401)
        assertEquals("Cleanup failed (server error 401) — inserted raw text", message)
        assertFalse("provider body leaked: \"$message\"", message.contains("platform.openai.com"))
        assertFalse("key fragment leaked: \"$message\"", message.contains("sk-proj"))
    }

    @Test fun `every notice stays short enough for a floating bubble`() {
        // Measured, the old local PROMPT_ECHO bubble was 112 chars; the HTTP 401 case above would
        // have been 190+. The bubble has no maxLines, so length is the only bound there is.
        val all = listOf(
            rawPromptEcho, rawLengthCollapse, rawLengthExpansion, rawNumericDivergence,
            rawTimeout, rawEmpty, rawBudget, rawHttp401, null, "",
        )
        for (raw in all) {
            for (method in InjectMethod.values()) {
                val message = CleanupFailureNotice.messageFor(method, raw)
                assertTrue(
                    "bubble text too long (${message.length}): \"$message\"",
                    message.length <= 70,
                )
            }
        }
    }

    // --- the reason mapping ---------------------------------------------------------------------

    @Test fun `each validator rejection gets its own plain-language reason`() {
        // Distinct reasons matter: "model repeated its instructions" and "model changed a number"
        // are different problems with different user responses. Collapsing them to a generic
        // "cleanup failed" is what makes the current experience undiagnosable.
        assertEquals("model repeated its instructions", CleanupFailureNotice.summarize(rawPromptEcho))
        assertEquals("model dropped most of the text", CleanupFailureNotice.summarize(rawLengthCollapse))
        assertEquals("model added text you didn't say", CleanupFailureNotice.summarize(rawLengthExpansion))
        assertEquals("model changed a number", CleanupFailureNotice.summarize(rawNumericDivergence))
    }

    @Test fun `the four validator reasons are all distinct`() {
        val reasons = listOf(rawPromptEcho, rawLengthCollapse, rawLengthExpansion, rawNumericDivergence)
            .map { CleanupFailureNotice.summarize(it) }
        assertEquals("each rejection must be distinguishable", reasons.size, reasons.toSet().size)
    }

    @Test fun `local engine failures are reported by cause`() {
        assertEquals("model timed out", CleanupFailureNotice.summarize(rawTimeout))
        assertEquals("model returned nothing", CleanupFailureNotice.summarize(rawEmpty))
        assertEquals("cleanup took too long", CleanupFailureNotice.summarize(rawBudget))
    }

    @Test fun `an absent or blank error degrades to a generic reason`() {
        assertEquals(CleanupFailureNotice.UNKNOWN_REASON, CleanupFailureNotice.summarize(null))
        assertEquals(CleanupFailureNotice.UNKNOWN_REASON, CleanupFailureNotice.summarize(""))
        assertEquals(CleanupFailureNotice.UNKNOWN_REASON, CleanupFailureNotice.summarize("   "))
    }

    @Test fun `an unrecognised error does not echo itself into the bubble`() {
        // The safety property behind the default branch: anything unmapped must fall back to a
        // fixed literal, never pass the raw string through. A future executor error string that
        // happened to contain transcript content would otherwise land straight in the overlay.
        val message = CleanupFailureNotice.messageFor(InjectMethod.DIRECT, "totally novel failure mode")
        assertFalse("raw error echoed: \"$message\"", message.contains("totally novel"))
        assertTrue(message.contains(CleanupFailureNotice.UNKNOWN_REASON))
    }
}
