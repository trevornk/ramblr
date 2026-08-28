package com.trevornk.ramblr

import com.trevornk.ramblr.tools.BenchmarkArgs
import com.trevornk.ramblr.tools.BenchmarkReport
import com.trevornk.ramblr.tools.BenchmarkTarget
import com.trevornk.ramblr.tools.CallOutcome
import com.trevornk.ramblr.tools.categorizeFailure
import com.trevornk.ramblr.tools.inlineAudioRejection
import com.trevornk.ramblr.tools.renderJson
import com.trevornk.ramblr.tools.renderMarkdown
import com.trevornk.ramblr.tools.resolveConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the #129 benchmark's **production fidelity** — the ways its request can silently
 * diverge from what a real dictation sends, and the report fields that make that divergence
 * auditable after the fact.
 *
 * The danger these guard against is specific: a benchmark that calls the real client but with a
 * different *prompt* or without production's pre-flight gates produces numbers that look like
 * production numbers and are not. Each assertion below pins one such divergence shut.
 */
class TranscriptionBenchmarkFidelityTest {

    @get:Rule val temp = TemporaryFolder()

    private fun writeCorpus(): File {
        File(temp.root, "f1.wav").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(temp.root, "manifest.json").writeText(
            """
            {"version":1,"fixtures":[{"id":"f1","audioPath":"f1.wav","referenceText":"hello world",
             "durationMs":1000,"language":"en-US","scenarios":["short_command"],
             "source":"synthetic-tts","consent":"CC0-1.0"}]}
            """.trimIndent()
        )
        return temp.root
    }

    // ---------------------------------------------------------------- vocabulary in the prompt

    // Production interpolates the user's personal vocabulary into the transcription prompt itself
    // (GeminiTranscriberClient.transcribePrompt, #114 part 2), and prefs are seeded with
    // VocabularyTerms.DEFAULTS on first run. Benchmarking with an empty vocabulary would measure a
    // prompt no shipped build ever sends.
    @Test fun `an unset vocabulary override uses the same defaults prefs are seeded with`() {
        val config = resolveConfig(BenchmarkArgs(true, null, null, writeCorpus().path, null))
        assertEquals(VocabularyTerms.DEFAULTS, config.vocabularyTerms)
        assertTrue(config.vocabularyTerms.isNotEmpty())
    }

    @Test fun `the resolved vocabulary actually changes the production prompt`() {
        val config = resolveConfig(BenchmarkArgs(true, null, null, writeCorpus().path, null))
        val prompt = GeminiTranscriberClient.transcribePrompt(config.vocabularyTerms)
        assertFalse(
            "default vocabulary must alter the shipped prompt, or the benchmark is measuring a bare one",
            prompt == GeminiTranscriberClient.TRANSCRIBE_PROMPT
        )
        VocabularyTerms.DEFAULTS.forEach { term ->
            assertTrue("prompt must carry '$term'", prompt.contains(term))
        }
    }

    // An explicitly empty override is a legitimate experiment ("what is vocabulary biasing worth?"),
    // distinct from an unset one. It must disable terms, not fall back to the defaults.
    @Test fun `an explicitly empty vocabulary override disables terms rather than falling back`() {
        val config = resolveConfig(BenchmarkArgs(true, null, null, writeCorpus().path, ""))
        assertEquals(emptyList<String>(), config.vocabularyTerms)
        assertEquals(
            GeminiTranscriberClient.TRANSCRIBE_PROMPT,
            GeminiTranscriberClient.transcribePrompt(config.vocabularyTerms)
        )
    }

    // Parsing is delegated to the production VocabularyTerms parser rather than reimplemented, so
    // trimming, blank-dropping and case-insensitive dedupe match the app exactly.
    @Test fun `a vocabulary override is parsed with production trimming and dedupe rules`() {
        val config = resolveConfig(
            BenchmarkArgs(true, null, null, writeCorpus().path, " Ramblr , , nbdev ,RAMBLR, Hetzner ")
        )
        assertEquals(listOf("Ramblr", "nbdev", "Hetzner"), config.vocabularyTerms)
    }

    // ---------------------------------------------------------------- production inline-audio gate

    // On a real device the GEMINI branch checks canInlineAudio(pcm.length()) BEFORE calling Gemini
    // and falls through to the next candidate when it fails. A benchmark without that gate would
    // report accuracy for a call production refuses to make.
    @Test fun `clips within the inline budget are not rejected`() {
        assertNull(inlineAudioRejection(0))
        assertNull(inlineAudioRejection(1_024))
        assertNull(inlineAudioRejection(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES))
    }

    @Test fun `a clip over the inline budget is rejected exactly where production rejects it`() {
        val overBy1 = GeminiTranscriberClient.MAX_INLINE_PCM_BYTES + 1
        // The gate boundary is inherited from production, never restated as a literal here: a
        // hardcoded 10MB would keep passing after production changed its threshold.
        assertFalse(GeminiTranscriberClient.canInlineAudio(overBy1))
        val rejection = inlineAudioRejection(overBy1)
        assertNotNull("a clip production would skip must be rejected by the benchmark too", rejection)
        assertTrue(rejection!!, rejection.contains(overBy1.toString()))
        assertTrue(rejection, rejection.contains(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES.toString()))
    }

    // The gate failure is produced locally, not by the API, so it must not pollute the transport
    // failure buckets that a reader uses to judge Gemini's reliability.
    @Test fun `a size-gate skip is categorized apart from transport failures`() {
        val rejection = inlineAudioRejection(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES + 1)!!
        val category = categorizeFailure(rejection)
        assertEquals("skipped: exceeds inline audio limit", category)
        assertFalse(category == "other")
        assertFalse(category == "network")
        assertFalse(category == "timeout")
    }

    // ---------------------------------------------------------------- report auditability

    private fun sampleReport(vocabularyRaw: String?): BenchmarkReport {
        val config = resolveConfig(BenchmarkArgs(true, "gemini-3.5-flash", null, writeCorpus().path, vocabularyRaw))
        return BenchmarkReport(
            commitSha = "abc123",
            timestampUtc = "2026-01-01T00:00:00Z",
            config = config,
            outcomes = listOf(
                CallOutcome("f1", "gemini-3.5-flash", 1, "hello world", null, 120),
            ),
        )
    }

    // A report that doesn't record the prompt vocabulary can't be compared against a later run:
    // the same corpus and model with different terms is a different experiment.
    @Test fun `the markdown report records the exact vocabulary sent in the prompt`() {
        val md = renderMarkdown(sampleReport(null))
        assertTrue(md, md.contains("Vocabulary terms in prompt: ${VocabularyTerms.DEFAULTS.size}"))
        VocabularyTerms.DEFAULTS.forEach { assertTrue(md, md.contains(it)) }
        assertTrue(md, md.contains(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES.toString()))
    }

    @Test fun `the markdown report states plainly when vocabulary was disabled`() {
        val md = renderMarkdown(sampleReport(""))
        assertTrue(md, md.contains("Vocabulary terms in prompt: 0 (explicitly disabled)"))
    }

    @Test fun `the json report carries the vocabulary and the inline limit as structured fields`() {
        val root = JSONObject(renderJson(sampleReport(null)))
        val terms = root.getJSONArray("vocabularyTerms")
        assertEquals(VocabularyTerms.DEFAULTS.size, terms.length())
        assertEquals(VocabularyTerms.DEFAULTS, (0 until terms.length()).map { terms.getString(it) })
        assertEquals(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES, root.getLong("inlineAudioLimitBytes"))
    }

    // Guards the scoring wiring end to end: a perfect transcript must land as WER 0 with a
    // recorded latency, asserted on the parsed JSON structure rather than a substring of prose.
    @Test fun `the json report scores a perfect transcript as zero error`() {
        val root = JSONObject(renderJson(sampleReport(null)))
        val perModel = root.getJSONArray("perModel")
        assertEquals(1, perModel.length())
        val model = perModel.getJSONObject(0)
        assertEquals("gemini-3.5-flash", model.getString("model"))
        assertEquals(1, model.getInt("calls"))
        assertEquals(1, model.getInt("successes"))
        assertEquals(1.0, model.getDouble("successRate"), 1e-9)
        assertEquals(0.0, model.getDouble("microWer"), 1e-9)
        assertEquals(0.0, model.getDouble("microCer"), 1e-9)
        assertEquals(1.0, model.getDouble("strictExactMatchRate"), 1e-9)
        assertEquals(120L, model.getLong("latencyP50Ms"))

        val call = root.getJSONArray("calls").getJSONObject(0)
        assertEquals("f1", call.getString("fixtureId"))
        assertEquals("hello world", call.getString("reference"))
        assertEquals("hello world", call.getString("transcript"))
        assertEquals(0.0, call.getDouble("wer"), 1e-9)
        assertTrue(call.getBoolean("strictExactMatch"))
        assertEquals("none", call.getString("failureCategory"))
    }

    // Failed calls must not be silently scored as successes: a report where every call errored
    // must show a 0% success rate, not a flattering 100%.
    @Test fun `a failed call is reported as a failure and never scored`() {
        val config = resolveConfig(BenchmarkArgs(true, "gemini-3.5-flash", null, writeCorpus().path, null))
        val report = BenchmarkReport(
            commitSha = "abc123",
            timestampUtc = "2026-01-01T00:00:00Z",
            config = config,
            outcomes = listOf(
                CallOutcome("f1", "gemini-3.5-flash", 1, null, "Unable to resolve host generativelanguage.googleapis.com", 30),
            ),
        )
        val root = JSONObject(renderJson(report))
        val model = root.getJSONArray("perModel").getJSONObject(0)
        assertEquals(0, model.getInt("successes"))
        assertEquals(0.0, model.getDouble("successRate"), 1e-9)
        assertEquals(JSONObject.NULL, model.get("latencyP50Ms"))

        val call = root.getJSONArray("calls").getJSONObject(0)
        assertEquals("network", call.getString("failureCategory"))
        assertEquals(JSONObject.NULL, call.get("transcript"))
        assertFalse("a failed call must carry no accuracy score", call.has("wer"))
        assertEquals(1, root.getJSONObject("failureCategories").getInt("network"))
    }

    @Test fun `one config can compare generateContent with dedicated verbatim interactions`() {
        val config = resolveConfig(
            BenchmarkArgs(
                apiKeyPresent = true,
                modelsRaw = "gemini-3.1-flash-lite",
                repetitionsRaw = "2",
                evalDirRaw = writeCorpus().path,
                enginesRaw = "generateContent,interactions",
                transcribeModelRaw = "gemini-3.5-transcribe",
                transcribeModesRaw = "verbatim",
            ),
        )

        assertEquals(
            listOf(
                BenchmarkTarget("Gemini", "generateContent", "gemini-3.1-flash-lite", "prompted-verbatim"),
                BenchmarkTarget("Gemini", "interactions/files", "gemini-3.5-transcribe", "verbatim"),
            ),
            config.targets,
        )
        assertEquals(4, config.totalCalls)
    }

    @Test fun `smart is an explicit secondary target and is never mixed with verbatim`() {
        val config = resolveConfig(
            BenchmarkArgs(
                true, "gemini-3.1-flash-lite", null, writeCorpus().path,
                enginesRaw = "interactions",
                transcribeModesRaw = "verbatim,smart",
            ),
        )
        assertEquals(listOf("verbatim", "smart"), config.targets.map { it.mode })
        assertTrue(config.targets.all { it.path == "interactions/files" })
    }

    @Test fun `reports keep path and mode axes separate even when model ids match`() {
        val base = resolveConfig(BenchmarkArgs(true, "gemini-3.5-transcribe", null, writeCorpus().path))
        val targets = listOf(
            BenchmarkTarget("Gemini", "generateContent", "gemini-3.5-transcribe", "prompted-verbatim"),
            BenchmarkTarget("Gemini", "interactions/files", "gemini-3.5-transcribe", "verbatim"),
        )
        val config = base.copy(targets = targets)
        val report = BenchmarkReport(
            "abc123", "2026-01-01T00:00:00Z", config,
            listOf(
                CallOutcome("f1", "gemini-3.5-transcribe", 1, "hello world", null, 100,
                    engine = "Gemini", path = "generateContent", mode = "prompted-verbatim"),
                CallOutcome("f1", "gemini-3.5-transcribe", 1, "wrong words", null, 200,
                    engine = "Gemini", path = "interactions/files", mode = "verbatim"),
            ),
        )

        val root = JSONObject(renderJson(report))
        val summaries = root.getJSONArray("perTarget")
        assertEquals(2, summaries.length())
        assertEquals("generateContent", summaries.getJSONObject(0).getString("path"))
        assertEquals(0.0, summaries.getJSONObject(0).getDouble("microWer"), 1e-9)
        assertEquals("interactions/files", summaries.getJSONObject(1).getString("path"))
        assertTrue(summaries.getJSONObject(1).getDouble("microWer") > 0.0)
        val markdown = renderMarkdown(report)
        assertTrue(markdown.contains("| Engine | Path | Model | Mode |"))
        assertTrue(markdown.contains("`interactions/files`"))
    }

    @Test fun `config resolves and reports explicit benchmark-only inter-call pacing`() {
        val config = resolveConfig(
            BenchmarkArgs(true, "gemini-3.1-flash-lite", null, writeCorpus().path, delayMsRaw = "7500"),
        )
        assertEquals(7500L, config.interCallDelayMs)
        val report = BenchmarkReport("abc", "2026-01-01T00:00:00Z", config, emptyList())
        assertTrue(renderMarkdown(report).contains("Inter-call delay: 7500 ms"))
        assertEquals(7500L, JSONObject(renderJson(report)).getLong("interCallDelayMs"))
    }
}
