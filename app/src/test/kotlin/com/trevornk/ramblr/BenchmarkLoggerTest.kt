package com.trevornk.ramblr

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkLoggerTest {

    @Test fun `buildLine includes both stages and lengths when everything is present`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1_700_000_000_000L,
            correlationId = "tok-42",
            transcription = BenchmarkStage("OPENAI", "gpt-4o-transcribe", 812L, success = true),
            cleanup = BenchmarkStage("LOCAL_LLM", "lfm2.5-350m", 340L, success = true),
            rawTextLength = 128,
            cleanedTextLength = 120,
        )

        val json = JSONObject(line)
        assertEquals(1_700_000_000_000L, json.getLong("timestamp"))
        assertEquals("tok-42", json.getString("correlationId"))
        assertEquals(128, json.getInt("rawTextLength"))
        assertEquals(120, json.getInt("cleanedTextLength"))

        val transcription = json.getJSONObject("transcription")
        assertEquals("OPENAI", transcription.getString("provider"))
        assertEquals("gpt-4o-transcribe", transcription.getString("model"))
        assertEquals(812L, transcription.getLong("latencyMs"))
        assertTrue(transcription.getBoolean("success"))

        val cleanup = json.getJSONObject("cleanup")
        assertEquals("LOCAL_LLM", cleanup.getString("provider"))
        assertEquals("lfm2.5-350m", cleanup.getString("model"))
        assertEquals(340L, cleanup.getLong("latencyMs"))
        assertTrue(cleanup.getBoolean("success"))
    }

    @Test fun `buildLine represents a null transcription or cleanup stage as JSON null, not a missing key`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "tok-1",
            transcription = null,
            cleanup = BenchmarkStage("OMNIROUTE", "claude/claude-sonnet-4-6", 500L, success = false),
            rawTextLength = null,
            cleanedTextLength = null,
        )

        val json = JSONObject(line)
        assertTrue(json.isNull("transcription"))
        assertFalse(json.isNull("cleanup"))
        assertTrue(json.isNull("rawTextLength"))
        assertTrue(json.isNull("cleanedTextLength"))
        assertFalse(json.getJSONObject("cleanup").getBoolean("success"))
    }

    @Test fun `buildLine omits pipeline as JSON null when the caller doesn't pass one`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "tok-1",
            transcription = null,
            cleanup = null,
            rawTextLength = null,
            cleanedTextLength = null,
        )

        val json = JSONObject(line)
        assertTrue(json.has("pipeline"))
        assertTrue(json.isNull("pipeline"))
    }

    @Test fun `buildLine records the end-to-end pipeline stage with all fields present`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1_700_000_000_000L,
            correlationId = "tok-42",
            transcription = null,
            cleanup = null,
            rawTextLength = null,
            cleanedTextLength = null,
            pipeline = PipelineStage(
                stopToDrainMs = 40L,
                injectionAttemptMs = 950L,
                injectMethod = "DIRECT",
                totalMs = 1010L,
            ),
        )

        val pipeline = JSONObject(line).getJSONObject("pipeline")
        assertEquals(40L, pipeline.getLong("stopToDrainMs"))
        assertEquals(950L, pipeline.getLong("injectionAttemptMs"))
        assertEquals("DIRECT", pipeline.getString("injectMethod"))
        assertEquals(1010L, pipeline.getLong("totalMs"))
    }

    @Test fun `buildLine records a partial pipeline stage's missing fields as JSON null, not omitted`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "tok-3",
            transcription = null,
            cleanup = null,
            rawTextLength = null,
            cleanedTextLength = null,
            // e.g. "no speech detected" bails out before the reader-drain marker was ever set.
            pipeline = PipelineStage(totalMs = 200L),
        )

        val pipeline = JSONObject(line).getJSONObject("pipeline")
        assertTrue(pipeline.isNull("stopToDrainMs"))
        assertTrue(pipeline.isNull("injectionAttemptMs"))
        assertTrue(pipeline.isNull("injectMethod"))
        assertEquals(200L, pipeline.getLong("totalMs"))
    }

    @Test fun `buildLine records a failed stage's success flag as false, not omitted`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L,
            correlationId = "tok-2",
            transcription = BenchmarkStage("LOCAL", "whisper-small.en", 5_000L, success = false),
            cleanup = null,
            rawTextLength = null,
            cleanedTextLength = null,
        )

        val transcription = JSONObject(line).getJSONObject("transcription")
        assertFalse(transcription.getBoolean("success"))
    }

    @Test fun `each buildLine call produces a single self-contained JSON object, not an array`() {
        val line = BenchmarkLogger.buildLine(
            timestamp = 1L, correlationId = "tok-3",
            transcription = null, cleanup = null,
            rawTextLength = null, cleanedTextLength = null,
        )
        // Parses as an object; would throw if it were e.g. a bare array or malformed fragment.
        JSONObject(line)
        assertFalse(line.trim().startsWith("["))
    }

    @Test fun `nextCorrelationId returns distinct values on successive calls`() {
        val first = BenchmarkLogger.nextCorrelationId()
        val second = BenchmarkLogger.nextCorrelationId()
        assertTrue(first != second)
    }

    @Test fun `rotateIfNeeded leaves a file under the threshold untouched`(): Unit {
        val file = File.createTempFile("benchmark_log", ".jsonl")
        try {
            file.writeText("line1\nline2\n")
            val before = file.readText()
            BenchmarkLogger.rotateIfNeeded(file)
            assertEquals(before, file.readText())
        } finally {
            file.delete()
        }
    }

    @Test fun `rotateIfNeeded truncates an oversized file down to its newest complete lines`() {
        val file = File.createTempFile("benchmark_log", ".jsonl")
        try {
            // Build many small, uniquely-numbered lines so we can identify exactly which ones
            // survive rotation without depending on JSON parsing here. Grow the file by actual
            // byte length (not a hardcoded line count) until it clears the rotation threshold --
            // line width grows with digit count as `i` increases, so a fixed repeat count doesn't
            // reliably produce a file over ROTATE_AT_BYTES.
            val builder = StringBuilder()
            var lastIndex = -1
            var i = 0
            while (builder.length < BenchmarkLogger.ROTATE_AT_BYTES) {
                builder.append("{\"n\":$i}\n")
                lastIndex = i
                i++
            }
            file.writeText(builder.toString())
            assertTrue(file.length() >= BenchmarkLogger.ROTATE_AT_BYTES)

            BenchmarkLogger.rotateIfNeeded(file)

            val remaining = file.readText()
            assertTrue("rotated file should shrink well below its pre-rotation size", file.length() <= BenchmarkLogger.KEEP_BYTES_AFTER_ROTATION)
            // The last line written must still be present -- rotation keeps the tail.
            assertTrue(remaining.contains("\"n\":$lastIndex"))
            // The very first line ("n":0) must be gone -- it's nowhere near the kept tail.
            assertFalse(remaining.contains("\"n\":0}"))
            // Every remaining line must itself be valid, complete JSON -- no partial first line
            // left over from the byte-offset cut.
            remaining.trim().lineSequence().filter { it.isNotBlank() }.forEach { JSONObject(it) }
        } finally {
            file.delete()
        }
    }

    @Test fun `rotateIfNeeded on a nonexistent file is a no-op, not a crash`() {
        val file = File.createTempFile("benchmark_log_missing", ".jsonl")
        file.delete()
        assertFalse(file.exists())
        BenchmarkLogger.rotateIfNeeded(file) // must not throw
        assertFalse(file.exists())
    }
}
