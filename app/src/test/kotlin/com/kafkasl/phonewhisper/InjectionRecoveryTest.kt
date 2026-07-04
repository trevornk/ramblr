package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
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
}
