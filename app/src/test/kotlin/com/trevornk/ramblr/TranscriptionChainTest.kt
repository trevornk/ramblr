package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionChainTest {

    // --- precheck ---

    @Test fun `a cloud provider with a credential is called`() {
        assertEquals(
            TranscriptionChain.Precheck.CALL,
            TranscriptionChain.precheck(ProviderKind.OPENAI, hasCredential = true, localModelLoaded = false)
        )
        assertEquals(
            TranscriptionChain.Precheck.CALL,
            TranscriptionChain.precheck(ProviderKind.GEMINI, hasCredential = true, localModelLoaded = false)
        )
    }

    @Test fun `a cloud provider with no credential is skipped`() {
        assertEquals(
            TranscriptionChain.Precheck.SKIP,
            TranscriptionChain.precheck(ProviderKind.OPENAI, hasCredential = false, localModelLoaded = false)
        )
        assertEquals(
            TranscriptionChain.Precheck.SKIP,
            TranscriptionChain.precheck(ProviderKind.GEMINI, hasCredential = false, localModelLoaded = false)
        )
    }

    @Test fun `a LOCAL candidate is called only once its model has loaded`() {
        assertEquals(
            TranscriptionChain.Precheck.CALL,
            TranscriptionChain.precheck(ProviderKind.LOCAL, hasCredential = false, localModelLoaded = true)
        )
        assertEquals(
            TranscriptionChain.Precheck.SKIP,
            TranscriptionChain.precheck(ProviderKind.LOCAL, hasCredential = false, localModelLoaded = false)
        )
    }

    @Test fun `providers not implemented for transcription are skipped`() {
        assertEquals(
            TranscriptionChain.Precheck.SKIP,
            TranscriptionChain.precheck(ProviderKind.ANTHROPIC, hasCredential = true, localModelLoaded = true)
        )
        assertEquals(
            TranscriptionChain.Precheck.SKIP,
            TranscriptionChain.precheck(ProviderKind.OMNIROUTE, hasCredential = true, localModelLoaded = true)
        )
    }

    // --- hasNextCandidate: the #H1 fall-through contract ---

    @Test fun `failure on candidate N tries candidate N+1 when one exists`() {
        // Two candidates: a failure on candidate 0 must advance to candidate 1.
        assertTrue(TranscriptionChain.hasNextCandidate(index = 0, candidateCount = 2))
    }

    @Test fun `failure on the last candidate has nowhere to fall through`() {
        assertFalse(TranscriptionChain.hasNextCandidate(index = 1, candidateCount = 2))
    }

    @Test fun `a mid-chain failure keeps advancing through every remaining candidate`() {
        // candidate 0 -> 1 -> 2 all still have a successor; only 2 (the last) is terminal.
        assertTrue(TranscriptionChain.hasNextCandidate(0, 3))
        assertTrue(TranscriptionChain.hasNextCandidate(1, 3))
        assertFalse(TranscriptionChain.hasNextCandidate(2, 3))
    }

    @Test fun `a single-candidate chain never falls through`() {
        assertFalse(TranscriptionChain.hasNextCandidate(0, 1))
    }

    // --- combined walk: an OpenAI failure falls through to the on-device LOCAL floor ---

    @Test fun `a failing cloud candidate falls through to the on-device LOCAL floor`() {
        // Mirrors the service walk: OpenAI (has key) called and fails, then LOCAL (loaded) runs.
        val candidates = listOf(ProviderKind.OPENAI, ProviderKind.LOCAL)
        val attempted = mutableListOf<Int>()

        var index = 0
        while (index < candidates.size) {
            attempted.add(index)
            val kind = candidates[index]
            val step = TranscriptionChain.precheck(
                kind,
                hasCredential = kind == ProviderKind.OPENAI, // only OpenAI has a key here
                localModelLoaded = true,
            )
            if (step == TranscriptionChain.Precheck.SKIP) { index++; continue }
            // Simulate: OpenAI's call fails, LOCAL succeeds.
            val succeeded = kind == ProviderKind.LOCAL
            if (succeeded) break
            if (!TranscriptionChain.hasNextCandidate(index, candidates.size)) break
            index++
        }

        assertEquals(listOf(0, 1), attempted) // reached the LOCAL floor after OpenAI failed
    }
}
