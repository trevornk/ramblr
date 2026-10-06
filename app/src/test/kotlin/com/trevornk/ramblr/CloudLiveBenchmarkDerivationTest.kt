package com.trevornk.ramblr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of [CloudLiveBenchmarkTest] (#233 Phase 1 item 10): derivation of the cloud-live
 * benchmark record, outcome-name mapping and JSONL line construction. None of it touches an
 * Android API, so it runs as plain JUnit.
 *
 * These cases used to live in the Robolectric class, where the class-level `@Before` ran the full
 * Robolectric/MockWebServer setup before every one of them. That made a pure-function test
 * fail intermittently with an NPE inside Robolectric's `ShadowImpl.extract` (#289) whenever the
 * shadow/application setup hiccuped -- a failure unrelated to the code under test.
 */
class CloudLiveBenchmarkDerivationTest {

    @Test
    fun `a failure message that echoes the transcript is bounded by sanitizeError`() {
        // A provider error envelope can quote the request that caused it. The stage must route
        // through sanitizeError like every other error field: collapsed (JSONL is line-oriented,
        // so an embedded newline would split one record into two unparseable fragments) and
        // capped at MAX_ERROR_DETAIL_CHARS.
        val stage = cloudLiveBenchmarkStage(
            outcome = CloudLiveOutcome.BATCH_SERVED_LIVE_FAILED,
            terminal = CloudLiveTerminal.Failure(
                CloudLiveFailureReason.PROTOCOL_ERROR,
                "rejected\n" + "x".repeat(MAX_ERROR_DETAIL_CHARS * 3),
                CloudLiveTiming(connectStartedAtMs = 1),
            ),
            timing = null,
        )
        val error = requireNotNull(stage.error)
        assertFalse("a newline would split the JSONL record", error.contains("\n"))
        assertEquals(MAX_ERROR_DETAIL_CHARS + 3, error.length)
        assertTrue(error.endsWith("..."))
    }


    @Test
    fun `a cloud-live line keeps every pre-existing key and an ordinary line keeps a null cloudLive`() {
        val json = JSONObject(
            BenchmarkLogger.buildLine(
                timestamp = 1_700_000_000_000L,
                correlationId = "tok-7",
                transcription = BenchmarkStage("OPENAI", "gpt-4o-transcribe", 812L, success = true),
                cleanup = null,
                rawTextLength = 12,
                cleanedTextLength = null,
            ),
        )
        // Every consumer (BackupManager's ENTRY_BENCHMARK_LOG copy, DataLogsActivity's share,
        // and the existing tests) reads these keys; an additive block must not disturb them.
        assertEquals(1_700_000_000_000L, json.getLong("timestamp"))
        assertEquals("tok-7", json.getString("correlationId"))
        assertEquals(12, json.getInt("rawTextLength"))
        assertTrue(json.isNull("cleanedTextLength"))
        assertTrue(json.isNull("cleanup"))
        assertTrue(json.isNull("pipeline"))
        assertEquals("OPENAI", json.getJSONObject("transcription").getString("provider"))
        assertTrue("a line with no live attempt still carries the key, as JSON null", json.isNull("cloudLive"))

        val withLive = JSONObject(
            BenchmarkLogger.buildLine(
                timestamp = 2L,
                correlationId = "tok-8",
                transcription = null,
                cleanup = null,
                rawTextLength = null,
                cleanedTextLength = null,
                cloudLive = CloudLiveStage(
                    outcome = CloudLiveOutcome.LIVE_DELIVERED.name,
                    fellBackToBatch = false,
                    setupMs = 180L,
                    firstInterimMs = 400L,
                    endOfAudioToFinalMs = 320L,
                ),
            ),
        )
        // Still a complete, self-contained record: the additive block never replaces the old keys.
        assertEquals(2L, withLive.getLong("timestamp"))
        assertTrue(withLive.isNull("transcription"))
        assertTrue(withLive.isNull("cleanup"))
        assertTrue(withLive.isNull("rawTextLength"))
        assertTrue(withLive.isNull("cleanedTextLength"))
        assertTrue(withLive.isNull("pipeline"))
        assertEquals("LIVE_DELIVERED", withLive.getJSONObject("cloudLive").getString("outcome"))
    }


    @Test
    fun `derivation reports a mark that never happened as null rather than a fabricated zero`() {
        val stage = cloudLiveBenchmarkStage(
            outcome = CloudLiveOutcome.BATCH_SERVED_LIVE_FAILED,
            terminal = CloudLiveTerminal.Failure(
                CloudLiveFailureReason.SETUP_TIMEOUT,
                "setup timed out",
                CloudLiveTiming(connectStartedAtMs = 500),
            ),
            timing = null,
        )
        assertNull("setup never completed; 0ms would be a lie", stage.setupMs)
        assertNull(stage.firstInterimMs)
        assertNull(stage.endOfAudioToFinalMs)
        assertEquals("SETUP_TIMEOUT", stage.failureReason)
        assertTrue(stage.fellBackToBatch)
    }


    @Test
    fun `derivation falls back to the last seen interim timing when no terminal ever arrived`() {
        val stage = cloudLiveBenchmarkStage(
            outcome = CloudLiveOutcome.BATCH_SERVED_NO_TERMINAL,
            terminal = null,
            timing = CloudLiveTiming(connectStartedAtMs = 100, setupCompletedAtMs = 260, firstInterimAtMs = 700),
        )
        assertEquals(160L, stage.setupMs)
        assertEquals(600L, stage.firstInterimMs)
        assertNull(stage.endOfAudioToFinalMs)
        assertNull(stage.failureReason)
        assertNull(stage.error)
    }


    @Test
    fun `derivation degrades to outcome only when there are no marks at all`() {
        val stage = cloudLiveBenchmarkStage(CloudLiveOutcome.BATCH_SERVED_NO_TERMINAL, terminal = null, timing = null)
        assertEquals("BATCH_SERVED_NO_TERMINAL", stage.outcome)
        assertTrue(stage.fellBackToBatch)
        assertNull(stage.setupMs)
        assertNull(stage.firstInterimMs)
        assertNull(stage.endOfAudioToFinalMs)
    }


    @Test
    fun `pre-rename outcome names still resolve so historical trial logs stay readable`() {
        // Device trials that ran before the FALLBACK_* -> BATCH_SERVED_* rename wrote the old
        // spellings into benchmark_log.jsonl. Those logs are the only record of what the
        // hardware actually did, so the mapping is load-bearing, not cosmetic.
        assertEquals(
            CloudLiveOutcome.BATCH_SERVED_LIVE_FAILED,
            CloudLiveOutcome.fromLogName("FALLBACK_FAILED"),
        )
        assertEquals(
            CloudLiveOutcome.BATCH_SERVED_NO_TERMINAL,
            CloudLiveOutcome.fromLogName("FALLBACK_NO_TERMINAL"),
        )
        assertEquals(
            CloudLiveOutcome.BATCH_SERVED_UNUSABLE_FINAL,
            CloudLiveOutcome.fromLogName("FALLBACK_UNUSABLE_FINAL"),
        )
        // Current spellings resolve to themselves, and LIVE_DELIVERED never had an alias.
        for (outcome in CloudLiveOutcome.entries) {
            assertEquals(outcome, CloudLiveOutcome.fromLogName(outcome.name))
        }
        assertNull(CloudLiveOutcome.LIVE_DELIVERED.legacyName)
        assertNull(CloudLiveOutcome.fromLogName("NOT_AN_OUTCOME"))
    }


    @Test
    fun `every batch-served outcome reports falling back and live-delivered does not`() {
        // The invariant the name change is meant to make obvious: BATCH_SERVED_* means the user
        // still got their text, via batch. Only LIVE_DELIVERED skipped the fallback entirely.
        for (outcome in CloudLiveOutcome.entries) {
            val stage = cloudLiveBenchmarkStage(outcome, terminal = null, timing = null)
            val expected = outcome != CloudLiveOutcome.LIVE_DELIVERED
            assertEquals(
                "$outcome should report fellBackToBatch=$expected",
                expected,
                stage.fellBackToBatch,
            )
            assertEquals(expected, outcome.name.startsWith("BATCH_SERVED_"))
        }
    }
}
