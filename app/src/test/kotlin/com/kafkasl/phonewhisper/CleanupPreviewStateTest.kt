package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupPreviewStateTest {

    @Test fun `starts unresolved`() {
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned")
        assertFalse(state.isResolved)
        assertEquals(null, state.resolution)
    }

    @Test fun `commit injects the candidate text and is marked committed`() {
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned")
        val resolution = state.commit()
        assertEquals("cleaned", resolution.textToInject)
        assertTrue(resolution.committed)
        assertTrue(state.isResolved)
        assertEquals(resolution, state.resolution)
    }

    @Test fun `discard falls back to raw text and is not committed`() {
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned")
        val resolution = state.discard()
        assertEquals("raw", resolution.textToInject)
        assertFalse(resolution.committed)
    }

    @Test fun `timeout falls back to raw text and is not committed`() {
        // Per #40's "err toward safe timeout falls back to raw injection" guidance -- a distracted
        // user who never taps must still get their dictation injected, just uncleaned.
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned")
        val resolution = state.timeout()
        assertEquals("raw", resolution.textToInject)
        assertFalse(resolution.committed)
    }

    @Test fun `resolution is one-shot -- a timeout after commit is a no-op`() {
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned")
        val committed = state.commit()
        val afterTimeout = state.timeout()
        assertEquals(committed, afterTimeout)
        assertTrue(afterTimeout.committed)
        assertEquals("cleaned", afterTimeout.textToInject)
    }

    @Test fun `resolution is one-shot -- a commit after timeout is a no-op`() {
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned")
        val timedOut = state.timeout()
        val afterCommit = state.commit()
        assertEquals(timedOut, afterCommit)
        assertFalse(afterCommit.committed)
        assertEquals("raw", afterCommit.textToInject)
    }

    @Test fun `resolution is one-shot -- a second discard after discard returns the same outcome`() {
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned")
        val first = state.discard()
        val second = state.discard()
        assertEquals(first, second)
    }

    @Test fun `empty candidate is still a valid commit target`() {
        // Cleanup returning blank text is handled upstream (falls back before a preview ever
        // starts) -- this just checks the state machine itself doesn't special-case emptiness.
        val state = CleanupPreviewState(rawText = "raw", candidateText = "")
        assertEquals("", state.commit().textToInject)
    }

    @Test fun `paidFallbackGroup defaults to null`() {
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned")
        assertEquals(null, state.paidFallbackGroup)
    }

    @Test fun `paidFallbackGroup is carried through unchanged for the caller to read after commit`() {
        // #33: which group served the candidate is decided by the caller before the preview even
        // starts (see WhisperAccessibilityService.beginPreview) -- this state machine just carries
        // it, it doesn't attach it to PreviewResolution since it's only meaningful on commit.
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned", paidFallbackGroup = CleanupStepGroup.OPENAI_DIRECT)
        state.commit()
        assertEquals(CleanupStepGroup.OPENAI_DIRECT, state.paidFallbackGroup)
    }

    @Test fun `historyTimestamp defaults to zero when not supplied`() {
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned")
        assertEquals(0L, state.historyTimestamp)
    }

    @Test fun `historyTimestamp is carried through unchanged for the caller to reuse on resolution`() {
        // #73: WhisperAccessibilityService.beginPreview records history immediately and stamps
        // the resulting timestamp here so resolvePreview can update that same row instead of
        // adding a duplicate -- this state machine just carries the value through untouched.
        val state = CleanupPreviewState(rawText = "raw", candidateText = "cleaned", historyTimestamp = 42L)
        state.commit()
        assertEquals(42L, state.historyTimestamp)
    }
}
