package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 (#192): the cheap pre-pipeline gates for dictations that can't produce useful text. */
class DictationGatesTest {

    // --- isBelowMinimumDuration (M3a): 16kHz mono 16-bit PCM = 32 bytes per ms ---

    private val sampleRate = RecordingEngine.SAMPLE_RATE

    @Test fun `zero bytes is below the floor`() {
        assertTrue(isBelowMinimumDuration(0L, sampleRate))
    }

    @Test fun `a 200ms recording is below the 300ms floor`() {
        val bytes200ms = 200L * 32
        assertTrue(isBelowMinimumDuration(bytes200ms, sampleRate))
    }

    @Test fun `one byte under the floor is still below it`() {
        val floorBytes = MIN_RECORDING_DURATION_MS * 32
        assertTrue(isBelowMinimumDuration(floorBytes - 1, sampleRate))
    }

    @Test fun `exactly the floor is not below it`() {
        val floorBytes = MIN_RECORDING_DURATION_MS * 32
        assertFalse(isBelowMinimumDuration(floorBytes, sampleRate))
    }

    @Test fun `a normal 3s dictation clears the floor easily`() {
        assertFalse(isBelowMinimumDuration(3_000L * 32, sampleRate))
    }

    @Test fun `floor scales with the sample rate, not a hardcoded byte count`() {
        // At 8kHz (16 bytes/ms) the same byte count holds twice the audio.
        val bytes200msAt16k = 200L * 32
        assertFalse("200ms of 16kHz bytes is 400ms at 8kHz", isBelowMinimumDuration(bytes200msAt16k, 8_000))
    }

    // --- isJunkTranscript (M3b): content-free transcripts skip the cleanup waterfall ---

    @Test fun `punctuation-only hallucinations are junk`() {
        assertTrue(isJunkTranscript("."))
        assertTrue(isJunkTranscript("…"))
        assertTrue(isJunkTranscript("?!"))
        assertTrue(isJunkTranscript(" . "))
        assertTrue(isJunkTranscript("- --"))
    }

    @Test fun `single characters are junk even when alphanumeric`() {
        assertTrue(isJunkTranscript("a"))
        assertTrue(isJunkTranscript("5"))
        assertTrue(isJunkTranscript(" a "))
    }

    @Test fun `real short words are not junk`() {
        assertFalse(isJunkTranscript("ok"))
        assertFalse(isJunkTranscript("no"))
        assertFalse(isJunkTranscript("you"))
    }

    @Test fun `two chars with a digit are not junk`() {
        assertFalse(isJunkTranscript("a 5"))
        assertFalse(isJunkTranscript("42"))
    }

    @Test fun `normal sentences are not junk`() {
        assertFalse(isJunkTranscript("send the report by five"))
    }

    @Test fun `whitespace padding does not rescue junk`() {
        assertTrue(isJunkTranscript("   .   "))
    }

    @Test fun `non-latin letters count as content`() {
        assertFalse(isJunkTranscript("日本"))
        assertFalse(isJunkTranscript("да"))
    }
}
