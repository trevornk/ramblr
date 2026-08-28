package com.trevornk.ramblr

import com.trevornk.ramblr.tools.TranscriptionEvalManifest
import com.trevornk.ramblr.tools.TranscriptionEvalManifest.Fixture
import com.trevornk.ramblr.tools.BenchmarkArgs
import com.trevornk.ramblr.tools.categorizeFailure
import com.trevornk.ramblr.tools.resolveConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Tests for the fixture manifest data model, parser, and validator. The validator must fail loudly
 * on every malformed-corpus shape; in particular an empty model list must be rejected rather than
 * silently falling back to [GeminiTranscriberClient.DEFAULT_MODEL], because a benchmark that
 * quietly benchmarks a different model than you asked for is worse than one that refuses to run.
 */
class TranscriptionEvalManifestTest {

    @get:Rule val temp = TemporaryFolder()

    private fun writeAudio(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File {
        val f = File(temp.root, name)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fixture(
        id: String = "f1",
        audioPath: String = "f1.wav",
        referenceText: String = "hello world",
        durationMs: Long = 1500,
        language: String = "en-US",
        scenarios: List<String> = listOf("short_command"),
        source: String = "synthetic-tts",
        consent: String = "CC0-1.0",
        sha256: String? = null,
    ) = Fixture(id, audioPath, referenceText, durationMs, language, scenarios, source, consent, sha256)

    // ---------------------------------------------------------------- parsing

    @Test fun `parses a full manifest into real typed fields`() {
        val json = """
            {
              "version": 1,
              "fixtures": [
                {
                  "id": "clip-a",
                  "audioPath": "clip-a.wav",
                  "referenceText": "turn on the kitchen light",
                  "durationMs": 2400,
                  "language": "en-US",
                  "scenarios": ["short_command", "numbers_and_punctuation"],
                  "source": "synthetic TTS, Piper en_US-lessac",
                  "consent": "CC0-1.0 synthetic voice, no human speaker",
                  "sha256": "abc123"
                }
              ]
            }
        """.trimIndent()

        val manifest = TranscriptionEvalManifest.parse(json)
        assertEquals(1, manifest.version)
        assertEquals(1, manifest.fixtures.size)
        val f = manifest.fixtures.single()
        assertEquals("clip-a", f.id)
        assertEquals("clip-a.wav", f.audioPath)
        assertEquals("turn on the kitchen light", f.referenceText)
        assertEquals(2400L, f.durationMs)
        assertEquals("en-US", f.language)
        assertEquals(listOf("short_command", "numbers_and_punctuation"), f.scenarios)
        assertEquals("synthetic TTS, Piper en_US-lessac", f.source)
        assertEquals("CC0-1.0 synthetic voice, no human speaker", f.consent)
        assertEquals("abc123", f.sha256)
    }

    @Test fun `parses a placeholder manifest with an empty fixture list`() {
        val manifest = TranscriptionEvalManifest.parse("""{"version":1,"fixtures":[]}""")
        assertEquals(0, manifest.fixtures.size)
    }

    @Test fun `rejects malformed json with a structured error`() {
        val error = try {
            TranscriptionEvalManifest.parse("{ not json")
            null
        } catch (e: TranscriptionEvalManifest.ManifestException) {
            e.message
        }
        assertTrue("expected a parse error, got $error", error != null && error.contains("parse", ignoreCase = true))
    }

    @Test fun `rejects a manifest missing the fixtures array`() {
        val error = try {
            TranscriptionEvalManifest.parse("""{"version":1}""")
            null
        } catch (e: TranscriptionEvalManifest.ManifestException) {
            e.message
        }
        assertTrue("expected a fixtures error, got $error", error != null && error!!.contains("fixtures"))
    }

    @Test fun `rejects a fixture missing a required field`() {
        val error = try {
            TranscriptionEvalManifest.parse("""{"version":1,"fixtures":[{"id":"a"}]}""")
            null
        } catch (e: TranscriptionEvalManifest.ManifestException) {
            e.message
        }
        assertTrue("expected a missing-field error, got $error", error != null && error!!.contains("audioPath"))
    }

    // ---------------------------------------------------------------- fixture validation

    @Test fun `a well-formed manifest with existing audio validates clean`() {
        writeAudio("f1.wav")
        val errors = TranscriptionEvalManifest.validateFixtures(listOf(fixture()), temp.root)
        assertEquals(emptyList<String>(), errors)
    }

    @Test fun `an empty fixture list is allowed as a placeholder corpus`() {
        assertEquals(emptyList<String>(), TranscriptionEvalManifest.validateFixtures(emptyList(), temp.root))
    }

    @Test fun `rejects duplicate fixture ids`() {
        writeAudio("f1.wav")
        writeAudio("f2.wav")
        val errors = TranscriptionEvalManifest.validateFixtures(
            listOf(fixture(id = "dup", audioPath = "f1.wav"), fixture(id = "dup", audioPath = "f2.wav")),
            temp.root,
        )
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("duplicate", ignoreCase = true))
        assertTrue(errors.single(), errors.single().contains("dup"))
    }

    @Test fun `rejects a blank fixture id`() {
        writeAudio("f1.wav")
        val errors = TranscriptionEvalManifest.validateFixtures(listOf(fixture(id = "   ")), temp.root)
        assertTrue(errors.toString(), errors.any { it.contains("id", ignoreCase = true) && it.contains("blank") })
    }

    @Test fun `rejects a blank reference transcript`() {
        writeAudio("f1.wav")
        val errors = TranscriptionEvalManifest.validateFixtures(listOf(fixture(referenceText = "   \n ")), temp.root)
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("referenceText"))
        assertTrue(errors.single(), errors.single().contains("blank"))
    }

    @Test fun `rejects a fixture whose audio file is missing`() {
        // No file written on disk at all.
        val errors = TranscriptionEvalManifest.validateFixtures(listOf(fixture(audioPath = "nope.wav")), temp.root)
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("nope.wav"))
        assertTrue(errors.single(), errors.single().contains("missing", ignoreCase = true))
    }

    @Test fun `rejects an unknown scenario category`() {
        writeAudio("f1.wav")
        val errors = TranscriptionEvalManifest.validateFixtures(
            listOf(fixture(scenarios = listOf("short_command", "yodelling"))),
            temp.root,
        )
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("yodelling"))
        assertTrue(errors.single(), errors.single().contains("scenario", ignoreCase = true))
    }

    @Test fun `every advertised known scenario actually validates`() {
        writeAudio("f1.wav")
        assertTrue(TranscriptionEvalManifest.KNOWN_SCENARIOS.isNotEmpty())
        val errors = TranscriptionEvalManifest.validateFixtures(
            listOf(fixture(scenarios = TranscriptionEvalManifest.KNOWN_SCENARIOS.toList())),
            temp.root,
        )
        assertEquals(emptyList<String>(), errors)
    }

    @Test fun `rejects an empty scenario list`() {
        writeAudio("f1.wav")
        val errors = TranscriptionEvalManifest.validateFixtures(listOf(fixture(scenarios = emptyList())), temp.root)
        assertTrue(errors.toString(), errors.any { it.contains("scenario", ignoreCase = true) })
    }

    @Test fun `rejects a non-positive duration`() {
        writeAudio("f1.wav")
        val errors = TranscriptionEvalManifest.validateFixtures(listOf(fixture(durationMs = 0)), temp.root)
        assertTrue(errors.toString(), errors.any { it.contains("durationMs") })
    }

    @Test fun `rejects blank provenance and consent metadata`() {
        writeAudio("f1.wav")
        val errors = TranscriptionEvalManifest.validateFixtures(
            listOf(fixture(source = " ", consent = "")),
            temp.root,
        )
        assertEquals(2, errors.size)
        assertTrue(errors.toString(), errors.any { it.contains("source") })
        assertTrue(errors.toString(), errors.any { it.contains("consent") })
    }

    @Test fun `rejects a blank language tag`() {
        writeAudio("f1.wav")
        val errors = TranscriptionEvalManifest.validateFixtures(listOf(fixture(language = "")), temp.root)
        assertTrue(errors.toString(), errors.any { it.contains("language") })
    }

    @Test fun `verifies the declared sha256 against the real file bytes`() {
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        writeAudio("f1.wav", bytes)
        val good = TranscriptionEvalManifest.validateFixtures(
            listOf(fixture(sha256 = sha256Of(bytes))),
            temp.root,
        )
        assertEquals(emptyList<String>(), good)

        val bad = TranscriptionEvalManifest.validateFixtures(
            listOf(fixture(sha256 = "0".repeat(64))),
            temp.root,
        )
        assertEquals(1, bad.size)
        assertTrue(bad.single(), bad.single().contains("sha256"))
    }

    @Test fun `collects every problem rather than stopping at the first`() {
        val errors = TranscriptionEvalManifest.validateFixtures(
            listOf(fixture(id = "a", audioPath = "gone.wav", referenceText = "", scenarios = listOf("bogus"))),
            temp.root,
        )
        assertTrue("expected several errors, got $errors", errors.size >= 3)
    }

    // ---------------------------------------------------------------- model-list validation

    @Test fun `a normal model list validates clean`() {
        assertEquals(
            emptyList<String>(),
            TranscriptionEvalManifest.validateModelIds(listOf("gemini-3.1-flash-lite", "gemini-3.5-flash")),
        )
    }

    /**
     * Mutation target (c). Removing the empty-list rejection must break this test. An empty list
     * must NOT be silently replaced with [GeminiTranscriberClient.DEFAULT_MODEL].
     */
    @Test fun `rejects an empty model list instead of falling back to the production default`() {
        val errors = TranscriptionEvalManifest.validateModelIds(emptyList())
        assertEquals("empty model list must produce exactly one error, got $errors", 1, errors.size)
        assertTrue(errors.single(), errors.single().contains("empty", ignoreCase = true))
        assertTrue(errors.single(), errors.single().contains("model", ignoreCase = true))
        // And the error must not be a disguised fallback announcement.
        assertFalse(errors.single(), errors.single().contains(GeminiTranscriberClient.DEFAULT_MODEL))
    }

    @Test fun `rejects a blank model id`() {
        val errors = TranscriptionEvalManifest.validateModelIds(listOf("gemini-3.5-flash", "   "))
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("blank"))
    }

    @Test fun `rejects duplicate model ids`() {
        val errors = TranscriptionEvalManifest.validateModelIds(listOf("gemini-3.5-flash", "gemini-3.5-flash"))
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("duplicate", ignoreCase = true))
        assertTrue(errors.single(), errors.single().contains("gemini-3.5-flash"))
    }

    // ---------------------------------------------------------------- checksum + throwing wrapper

    @Test fun `manifest checksum is a stable sha256 of the raw document`() {
        val doc = """{"version":1,"fixtures":[]}"""
        val checksum = TranscriptionEvalManifest.checksum(doc)
        assertEquals(64, checksum.length)
        assertEquals(sha256Of(doc.toByteArray(Charsets.UTF_8)), checksum)
        assertEquals(checksum, TranscriptionEvalManifest.checksum(doc))
        assertFalse(checksum == TranscriptionEvalManifest.checksum("""{"version":2,"fixtures":[]}"""))
    }

    @Test fun `validateOrThrow surfaces every collected error in one exception`() {
        val message = try {
            TranscriptionEvalManifest.validateOrThrow(listOf(fixture(audioPath = "gone.wav")), temp.root, emptyList())
            null
        } catch (e: TranscriptionEvalManifest.ManifestException) {
            e.message
        }
        assertTrue("expected a combined error, got $message", message != null && message!!.contains("gone.wav"))
        assertTrue(message!!, message.contains("model", ignoreCase = true))
    }

    @Test fun `validateOrThrow passes for a clean manifest and model list`() {
        writeAudio("f1.wav")
        TranscriptionEvalManifest.validateOrThrow(listOf(fixture()), temp.root, listOf("gemini-3.5-flash"))
    }

    // ---------------------------------------------------------------- shipped placeholder manifest

    @Test fun `the checked-in placeholder manifest parses and validates`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("transcription_eval/manifest.json")
        requireNotNull(stream) { "transcription_eval/manifest.json must be on the test resources path" }
        val doc = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val manifest = TranscriptionEvalManifest.parse(doc)
        // Deliberately a placeholder: no invented fixture entries without matching audio.
        assertEquals(emptyList<Fixture>(), manifest.fixtures)
    }

    // ---------------------------------------------------------------- benchmark config resolver
    // resolveConfig is the benchmark runner's entire "should this run start" decision, extracted
    // as a pure function precisely so it can be driven here with no network and no env vars.

    private fun writeManifest(body: String): File {
        val f = File(temp.root, "manifest.json")
        f.writeText(body)
        return f
    }

    private fun resolveError(args: BenchmarkArgs): String {
        val message = try {
            resolveConfig(args)
            null
        } catch (e: TranscriptionEvalManifest.ManifestException) {
            e.message
        }
        assertTrue("expected resolveConfig to reject $args", message != null)
        return message!!
    }

    @Test fun `resolveConfig rejects a missing api key`() {
        writeManifest("""{"version":1,"fixtures":[]}""")
        val message = resolveError(BenchmarkArgs(false, null, null, temp.root.path))
        assertTrue(message, message.contains("GEMINI_API_KEY"))
    }

    @Test fun `resolveConfig rejects an explicitly empty model override without falling back`() {
        writeManifest("""{"version":1,"fixtures":[]}""")
        val message = resolveError(BenchmarkArgs(true, "  ,  ", null, temp.root.path))
        assertTrue(message, message.contains("empty", ignoreCase = true))
        assertFalse(message, message.contains(GeminiTranscriberClient.DEFAULT_MODEL))
    }

    @Test fun `resolveConfig rejects a placeholder manifest with no fixtures`() {
        writeManifest("""{"version":1,"fixtures":[]}""")
        val message = resolveError(BenchmarkArgs(true, null, null, temp.root.path))
        assertTrue(message, message.contains("no fixtures"))
    }

    @Test fun `resolveConfig rejects a non-numeric or non-positive repetition count`() {
        writeManifest("""{"version":1,"fixtures":[]}""")
        assertTrue(resolveError(BenchmarkArgs(true, null, "abc", temp.root.path)).contains("integer"))
        assertTrue(resolveError(BenchmarkArgs(true, null, "0", temp.root.path)).contains("at least 1"))
    }

    @Test fun `resolveConfig rejects invalid benchmark pacing delay`() {
        writeManifest("""{"version":1,"fixtures":[]}""")
        assertTrue(resolveError(BenchmarkArgs(true, null, null, temp.root.path, delayMsRaw = "nope")).contains("DELAY_MS"))
        assertTrue(resolveError(BenchmarkArgs(true, null, null, temp.root.path, delayMsRaw = "-1")).contains("non-negative"))
    }

    @Test fun `resolveConfig rejects a missing manifest file`() {
        val message = resolveError(BenchmarkArgs(true, null, null, File(temp.root, "nowhere").path))
        assertTrue(message, message.contains("Manifest not found"))
    }

    @Test fun `resolveConfig returns a usable config for a valid corpus`() {
        writeAudio("f1.wav", byteArrayOf(1, 2, 3, 4))
        writeManifest(
            """
            {"version":1,"fixtures":[{"id":"f1","audioPath":"f1.wav","referenceText":"hello world",
             "durationMs":1000,"language":"en-US","scenarios":["short_command"],
             "source":"synthetic-tts","consent":"CC0-1.0"}]}
            """.trimIndent()
        )
        val config = resolveConfig(BenchmarkArgs(true, "gemini-3.5-flash,gemini-3.1-flash-lite", "3", temp.root.path))
        assertEquals(listOf("gemini-3.5-flash", "gemini-3.1-flash-lite"), config.modelIds)
        assertEquals(3, config.repetitions)
        assertEquals(1, config.fixtures.size)
        assertEquals(6, config.totalCalls) // 1 fixture x 2 models x 3 repetitions
        assertEquals(64, config.manifestChecksum.length)
        assertTrue(config.callTimeoutSeconds > 0)
    }

    @Test fun `resolveConfig uses the live catalog models when no override is given`() {
        writeAudio("f1.wav")
        writeManifest(
            """
            {"version":1,"fixtures":[{"id":"f1","audioPath":"f1.wav","referenceText":"hi",
             "durationMs":500,"language":"en-US","scenarios":["short_command"],
             "source":"synthetic-tts","consent":"CC0-1.0"}]}
            """.trimIndent()
        )
        val config = resolveConfig(BenchmarkArgs(true, null, null, temp.root.path))
        assertEquals(listOf("gemini-3.1-flash-lite", "gemini-3.5-flash"), config.modelIds)
        assertEquals(1, config.repetitions)
    }

    @Test fun `resolveConfig reports fixture problems and model problems together`() {
        writeManifest(
            """
            {"version":1,"fixtures":[{"id":"f1","audioPath":"gone.wav","referenceText":"hi",
             "durationMs":500,"language":"en-US","scenarios":["short_command"],
             "source":"synthetic-tts","consent":"CC0-1.0"}]}
            """.trimIndent()
        )
        val message = resolveError(BenchmarkArgs(true, "", null, temp.root.path))
        assertTrue(message, message.contains("gone.wav"))
        assertTrue(message, message.contains("empty", ignoreCase = true))
    }

    // ---------------------------------------------------------------- failure categorization

    @Test fun `failure categories map real client error strings to buckets`() {
        assertEquals("none", categorizeFailure(null))
        assertEquals("timeout", categorizeFailure("timeout"))
        assertEquals("quota/rate-limit", categorizeFailure("RESOURCE_EXHAUSTED: quota exceeded"))
        assertEquals("auth", categorizeFailure("API key not valid"))
        assertEquals("empty/blocked response", categorizeFailure("No text content in response"))
        assertEquals("bad model/endpoint", categorizeFailure("Invalid Gemini endpoint: ..."))
        assertEquals("other", categorizeFailure("something unexpected"))
    }
}
