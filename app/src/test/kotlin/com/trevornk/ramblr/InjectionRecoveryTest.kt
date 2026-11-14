package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InjectionRecoveryTest {

    // --- planUndo ---

    @Test fun `restores in place when the node is still available`() {
        val plan = planUndo(ageMs = 1000, windowMs = 10_000, nodeAvailable = true, priorNodeText = "old text", priorClipboard = "clip")

        assertEquals(UndoPlan.RestoreInPlace("old text"), plan)
    }

    @Test fun `falls back to clipboard when the node is unavailable but the clipboard changed`() {
        val plan = planUndo(ageMs = 1000, windowMs = 10_000, nodeAvailable = false, priorNodeText = "old text", priorClipboard = "clip")

        assertEquals(UndoPlan.ClipboardOnly("clip"), plan)
    }

    @Test fun `falls back to clipboard when there was no prior node text to restore`() {
        val plan = planUndo(ageMs = 1000, windowMs = 10_000, nodeAvailable = true, priorNodeText = null, priorClipboard = "clip")

        assertEquals(UndoPlan.ClipboardOnly("clip"), plan)
    }

    @Test fun `unavailable when neither the node nor the clipboard can restore anything`() {
        val plan = planUndo(ageMs = 1000, windowMs = 10_000, nodeAvailable = false, priorNodeText = null, priorClipboard = null)

        assertEquals(UndoPlan.Unavailable, plan)
    }

    @Test fun `expired once the window has passed, even with a restorable node`() {
        val plan = planUndo(ageMs = 10_001, windowMs = 10_000, nodeAvailable = true, priorNodeText = "old text", priorClipboard = "clip")

        assertEquals(UndoPlan.Expired, plan)
    }

    @Test fun `right at the window boundary is not yet expired`() {
        val plan = planUndo(ageMs = 10_000, windowMs = 10_000, nodeAvailable = true, priorNodeText = "old text", priorClipboard = "clip")

        assertEquals(UndoPlan.RestoreInPlace("old text"), plan)
    }

    @Test fun `prefers in-place restore over the clipboard when both are available`() {
        val plan = planUndo(ageMs = 1000, windowMs = 10_000, nodeAvailable = true, priorNodeText = "old text", priorClipboard = "clip")

        assertEquals(UndoPlan.RestoreInPlace("old text"), plan)
    }

    // --- canRetryRaw ---

    @Test fun `offers raw retry when cleanup changed the text and the window hasn't passed`() {
        assertEquals(true, canRetryRaw(ageMs = 1000, windowMs = 10_000, rawText = "raw", injectedText = "Cleaned."))
    }

    @Test fun `no raw retry once the window has passed`() {
        assertEquals(false, canRetryRaw(ageMs = 10_001, windowMs = 10_000, rawText = "raw", injectedText = "Cleaned."))
    }

    @Test fun `no raw retry when cleanup left the text unchanged`() {
        assertEquals(false, canRetryRaw(ageMs = 1000, windowMs = 10_000, rawText = "same", injectedText = "same"))
    }

    @Test fun `right at the window boundary raw retry is still offered`() {
        assertEquals(true, canRetryRaw(ageMs = 10_000, windowMs = 10_000, rawText = "raw", injectedText = "Cleaned."))
    }

    // --- shouldRetryEmptyScan ---

    @Test fun `retries when the first scan finds no candidates`() {
        assertEquals(true, shouldRetryEmptyScan(candidateCount = 0))
    }

    @Test fun `does not retry once the scan finds at least one candidate`() {
        assertEquals(false, shouldRetryEmptyScan(candidateCount = 1))
        assertEquals(false, shouldRetryEmptyScan(candidateCount = 5))
    }

    // --- clipboardRestoreOutcomeFor ---

    @Test fun `restores the user's prior clipboard once the injected text is no longer needed`() {
        val outcome = clipboardRestoreOutcomeFor(
            currentClipboard = "dictated text",
            injectedText = "dictated text",
            priorClipboard = "phone number the user had copied"
        )

        assertEquals(ClipboardRestoreOutcome.Restore("phone number the user had copied"), outcome)
    }

    @Test fun `clears the clipboard when the user had nothing copied beforehand`() {
        val outcome = clipboardRestoreOutcomeFor(
            currentClipboard = "dictated text",
            injectedText = "dictated text",
            priorClipboard = null
        )

        assertEquals(ClipboardRestoreOutcome.Clear, outcome)
    }

    @Test fun `leaves the clipboard alone if something else has taken it over since`() {
        val outcome = clipboardRestoreOutcomeFor(
            currentClipboard = "something the user just copied",
            injectedText = "dictated text",
            priorClipboard = "old clipboard contents"
        )

        assertEquals(ClipboardRestoreOutcome.LeaveAlone, outcome)
    }

    @Test fun `leaves the clipboard alone when it was emptied out from under the injection`() {
        val outcome = clipboardRestoreOutcomeFor(
            currentClipboard = null,
            injectedText = "dictated text",
            priorClipboard = "old clipboard contents"
        )

        assertEquals(ClipboardRestoreOutcome.LeaveAlone, outcome)
    }

    // --- swapInjectedForRaw (#343) ---

    private fun swap(prior: String, selStart: Int, selEnd: Int, injected: String, raw: String): RawSwap? {
        val current = composeOneShotInjection(prior, selStart, selEnd, injected)
        return swapInjectedForRaw(prior, current, injected, raw)
    }

    @Test fun `raw replaces cleaned text inserted at the end`() {
        val result = swap("Hello there", 11, 11, "Cleaned text.", "cleaned text uh")

        assertEquals(RawSwap("Hello there cleaned text uh", caret = 27), result)
    }

    @Test fun `raw replaces cleaned text inserted mid-text and keeps what follows`() {
        val result = swap("Hello world", 5, 5, "Clean", "raw um")

        assertEquals(RawSwap("Hello raw um world", caret = 12), result)
    }

    @Test fun `raw replaces cleaned text inserted into an empty field`() {
        val result = swap("", 0, 0, "Hi there.", "hi there um")

        assertEquals(RawSwap("hi there um", caret = 11), result)
    }

    @Test fun `the separator composition added is kept for the raw text`() {
        // "Dear John," + " " + injected: the added space sits inside the diff and must survive.
        val result = swap("Dear John,", 10, 10, "Body.", "body um")

        assertEquals(RawSwap("Dear John, body um", caret = 18), result)
    }

    @Test fun `no separator is added after existing whitespace`() {
        val result = swap("Dear John, ", 11, 11, "Body.", "body")

        assertEquals(RawSwap("Dear John, body", caret = 15), result)
    }

    @Test fun `a selection the injection overwrote stays replaced`() {
        val result = swap("select me", 0, 9, "Clean", "raw")

        assertEquals(RawSwap("raw", caret = 3), result)
    }

    @Test fun `null when the user edited the field after the injection`() {
        val prior = "Hello there"
        val current = composeOneShotInjection(prior, 11, 11, "Cleaned text.") + " and more"

        assertNull(swapInjectedForRaw(prior, current, "Cleaned text.", "raw"))
    }

    @Test fun `null when the user edited the injected text itself`() {
        val prior = "Hello there"
        val current = "Hello there Cleaned test."

        assertNull(swapInjectedForRaw(prior, current, "Cleaned text.", "raw"))
    }

    @Test fun `null when the injected text is not in the field at all`() {
        assertNull(swapInjectedForRaw("Hello there", "Something else entirely", "Cleaned text.", "raw"))
        assertNull(swapInjectedForRaw("Hello there", "Hello there", "Cleaned text.", "raw"))
    }

    @Test fun `null when the injected text could sit in two places`() {
        // "hi hi" + " hi": the new "hi" is either the middle or the last one; guessing would
        // rewrite the wrong word half the time.
        val prior = "hi hi"
        val current = composeOneShotInjection(prior, 5, 5, "hi")

        assertEquals("hi hi hi", current)
        assertNull(swapInjectedForRaw(prior, current, "hi", "raw"))
    }

    @Test fun `unambiguous when the injected text also occurs in the prior text`() {
        val prior = "Cleaned text. is good"
        val current = composeOneShotInjection(prior, 21, 21, "Cleaned text.")

        assertEquals(RawSwap("Cleaned text. is good raw", caret = 25), swapInjectedForRaw(prior, current, "Cleaned text.", "raw"))
    }

    @Test fun `null for an empty injected text`() {
        assertNull(swapInjectedForRaw("abc", "abc", "", "raw"))
    }

    // --- #376: lossy paste injections and the staged payload ---

    @Test fun `a paste injection with no prior node text can only be undone through the clipboard`() {
        val plan = planUndo(ageMs = 1000, windowMs = 10_000, nodeAvailable = false, priorNodeText = null, priorClipboard = "clip")
        assertEquals(UndoPlan.ClipboardOnly("clip"), plan)
    }

    @Test fun `restore compares against the staged payload so a separator-prefixed paste is still restored`() {
        val staging = ClipboardStaging { }
        staging.stage(" dictated")
        assertEquals(
            ClipboardRestoreOutcome.Restore("PRIOR"),
            clipboardRestoreOutcomeFor(" dictated", staging.stagedPayload!!, "PRIOR"),
        )
        // Compared against the plain transcript instead, the restore would never run.
        assertEquals(ClipboardRestoreOutcome.LeaveAlone, clipboardRestoreOutcomeFor(" dictated", "dictated", "PRIOR"))
    }

    @Test fun `staging a different payload replaces the earlier one and the same payload is copied once`() {
        val copied = mutableListOf<String>()
        val staging = ClipboardStaging { copied += it }
        assertEquals(false, staging.isStaged)
        staging.stage(" dictated")
        staging.stage(" dictated")
        staging.stage("dictated")
        assertEquals(listOf(" dictated", "dictated"), copied)
        assertEquals("dictated", staging.stagedPayload)
    }

    @Test fun `a clipboard that refuses the copy leaves nothing marked as staged`() {
        val staging = ClipboardStaging { throw IllegalStateException("denied") }
        try { staging.stage("x") } catch (_: IllegalStateException) {}
        assertEquals(false, staging.isStaged)
        assertNull(staging.stagedPayload)
    }

    @Test fun `the NONE fallback re-stages the plain transcript after a failed lossy paste and restore compares against it`() {
        val copied = mutableListOf<String>()
        val staging = ClipboardStaging { copied += it }
        staging.stage(" dictated")          // beforePaste of the failed lossy paste
        staging.stage("dictated")           // NONE fallback stages the plain transcript
        assertEquals(listOf(" dictated", "dictated"), copied)
        assertEquals(
            ClipboardRestoreOutcome.Restore("PRIOR"),
            clipboardRestoreOutcomeFor("dictated", staging.stagedPayload ?: "dictated", "PRIOR"),
        )
    }

    // --- shouldTryNextCandidate / lossyStreamingSessionAfterPartial ---

    @Test fun `only a failure outside the lossy path moves on to the next candidate`() {
        assertEquals(true, shouldTryNextCandidate(TextCommitResult(InjectMethod.NONE)))
        assertEquals(false, shouldTryNextCandidate(TextCommitResult(InjectMethod.NONE, lossyPathTaken = true)))
        assertEquals(false, shouldTryNextCandidate(TextCommitResult(InjectMethod.DIRECT)))
        assertEquals(false, shouldTryNextCandidate(TextCommitResult(InjectMethod.FROM_CLIPBOARD, lossyPathTaken = true)))
    }

    @Test fun `the lossy streaming flag latches on the first lossy partial and a new recording starts clear`() {
        var flag = false
        flag = lossyStreamingSessionAfterPartial(flag, fieldIsLossy = false)
        assertEquals(false, flag)
        flag = lossyStreamingSessionAfterPartial(flag, fieldIsLossy = true)
        assertEquals(true, flag)
        flag = lossyStreamingSessionAfterPartial(flag, fieldIsLossy = false)
        assertEquals(true, flag)
        // Per-recording reset is the service assigning false; the routing it feeds:
        assertEquals(true, shouldRouteStreamingPartialToBubble(false, livePreviewEnabled = true, lossyFieldSession = flag))
        assertEquals(false, shouldRouteStreamingPartialToBubble(false, livePreviewEnabled = true, lossyFieldSession = false))
    }
}
