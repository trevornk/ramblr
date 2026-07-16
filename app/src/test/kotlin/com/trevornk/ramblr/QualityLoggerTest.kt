package com.trevornk.ramblr

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityLoggerTest {

    @Test fun `buildLine includes both stages and text when everything is present`() {
        val line = QualityLogger.buildLine(
            timestamp = 1_700_000_000_000L,
            correlationId = "tok-42",
            transcription = QualityStage("OPENAI", "gpt-4o-transcribe"),
            cleanup = QualityStage("LOCAL_LLM", "lfm2.5-350m"),
            rawText = "raw dictated words here",
            cleanedText = "Raw dictated words here.",
        )

        val json = JSONObject(line)
        assertEquals(1_700_000_000_000L, json.getLong("timestamp"))
        assertEquals("tok-42", json.getString("correlationId"))
        assertEquals("raw dictated words here", json.getString("rawText"))
        assertEquals("Raw dictated words here.", json.getString("cleanedText"))

        val transcription = json.getJSONObject("transcription")
        assertEquals("OPENAI", transcription.getString("provider"))
        assertEquals("gpt-4o-transcribe", transcription.getString("model"))

        val cleanup = json.getJSONObject("cleanup")
        assertEquals("LOCAL_LLM", cleanup.getString("provider"))
        assertEquals("lfm2.5-350m", cleanup.getString("model"))
    }

    @Test fun `buildLine represents a null transcription or cleanup stage as JSON null, not a missing key`() {
        val line = QualityLogger.buildLine(
            timestamp = 1L,
            correlationId = "tok-1",
            transcription = null,
            cleanup = QualityStage("OMNIROUTE", "claude/claude-sonnet-4-6"),
            rawText = null,
            cleanedText = null,
        )

        val json = JSONObject(line)
        assertTrue(json.isNull("transcription"))
        assertFalse(json.isNull("cleanup"))
        assertTrue(json.isNull("rawText"))
        assertTrue(json.isNull("cleanedText"))
    }

    @Test fun `buildLine records rawText without cleanedText when cleanup did not run`() {
        val line = QualityLogger.buildLine(
            timestamp = 1L,
            correlationId = "tok-2",
            transcription = QualityStage("LOCAL", "whisper-small.en"),
            cleanup = null,
            rawText = "some raw transcript",
            cleanedText = null,
        )

        val json = JSONObject(line)
        assertEquals("some raw transcript", json.getString("rawText"))
        assertTrue(json.isNull("cleanedText"))
        assertTrue(json.isNull("cleanup"))
    }

    @Test fun `each buildLine call produces a single self-contained JSON object, not an array`() {
        val line = QualityLogger.buildLine(
            timestamp = 1L, correlationId = "tok-3",
            transcription = null, cleanup = null,
            rawText = null, cleanedText = null,
        )
        // Parses as an object; would throw if it were e.g. a bare array or malformed fragment.
        JSONObject(line)
        assertFalse(line.trim().startsWith("["))
    }

    @Test fun `rotateIfNeeded leaves a file under the threshold untouched`(): Unit {
        val file = File.createTempFile("quality_log", ".jsonl")
        try {
            file.writeText("line1\nline2\n")
            val before = file.readText()
            QualityLogger.rotateIfNeeded(file)
            assertEquals(before, file.readText())
        } finally {
            file.delete()
        }
    }

    @Test fun `rotateIfNeeded truncates an oversized file down to its newest complete lines`() {
        val file = File.createTempFile("quality_log", ".jsonl")
        try {
            // Build many small, uniquely-numbered lines so we can identify exactly which ones
            // survive rotation without depending on JSON parsing here. Grow the file by actual
            // byte length (not a hardcoded line count) until it clears the rotation threshold --
            // line width grows with digit count as `i` increases, so a fixed repeat count doesn't
            // reliably produce a file over ROTATE_AT_BYTES. Verify the real byte length exceeds
            // the threshold before rotating, so this test can't silently skip the rotation path
            // the way a prior BenchmarkLoggerTest fixture once did.
            val builder = StringBuilder()
            var lastIndex = -1
            var i = 0
            while (builder.length < QualityLogger.ROTATE_AT_BYTES) {
                builder.append("{\"n\":$i}\n")
                lastIndex = i
                i++
            }
            file.writeText(builder.toString())
            assertTrue(
                "fixture must genuinely exceed ROTATE_AT_BYTES (${QualityLogger.ROTATE_AT_BYTES}) " +
                    "for the rotation path to actually be exercised, was ${file.length()}",
                file.length() >= QualityLogger.ROTATE_AT_BYTES,
            )

            QualityLogger.rotateIfNeeded(file)

            val remaining = file.readText()
            assertTrue("rotated file should shrink well below its pre-rotation size", file.length() <= QualityLogger.KEEP_BYTES_AFTER_ROTATION)
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
        val file = File.createTempFile("quality_log_missing", ".jsonl")
        file.delete()
        assertFalse(file.exists())
        QualityLogger.rotateIfNeeded(file) // must not throw
        assertFalse(file.exists())
    }
}
