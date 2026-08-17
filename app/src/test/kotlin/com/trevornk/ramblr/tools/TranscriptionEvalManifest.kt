package com.trevornk.ramblr.tools

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Fixture-corpus manifest for the #129 transcription benchmark: pure data model, parser, and
 * validator with no network and no Android dependencies, so the entire "is this run even
 * well-formed" decision is unit-testable offline.
 *
 * Every rejection here is deliberate and loud. A benchmark that silently drops a fixture with a
 * missing audio file, silently scores against a blank reference, or silently substitutes a
 * default model for an empty model list produces numbers that look plausible and mean nothing —
 * far more dangerous than a run that refuses to start.
 */
object TranscriptionEvalManifest {

    /** Structured failure for a manifest that cannot be parsed or does not validate. */
    class ManifestException(message: String) : Exception(message)

    /**
     * One benchmark clip. [source] and [consent] are mandatory metadata, not decoration: recorded
     * human speech is biometric data, so every fixture must carry its provenance and the licence
     * or consent under which it may be redistributed (see the corpus README).
     */
    data class Fixture(
        val id: String,
        val audioPath: String,
        val referenceText: String,
        val durationMs: Long,
        val language: String,
        val scenarios: List<String>,
        val source: String,
        val consent: String,
        val sha256: String? = null,
    )

    data class Manifest(val version: Int, val fixtures: List<Fixture>)

    /** Scenario categories a fixture may be tagged with. A closed vocabulary so per-scenario
     *  breakdowns stay comparable across runs instead of fragmenting into typo'd one-off tags. */
    val KNOWN_SCENARIOS: Set<String> = setOf(
        "short_command",
        "long_dictation",
        "technical_jargon",
        "numbers_and_punctuation",
        "proper_nouns",
        "self_correction",
        "noisy_environment",
        "accented_speech",
        "fast_speech",
        "quiet_speech",
        "code_switching",
        "silence_or_nonspeech",
    )

    // ------------------------------------------------------------------ parsing

    private fun JSONObject.requiredString(field: String, context: String): String =
        if (has(field) && !isNull(field)) getString(field)
        else throw ManifestException("$context is missing required field '$field'")

    private fun JSONObject.requiredLong(field: String, context: String): Long =
        if (has(field) && !isNull(field)) getLong(field)
        else throw ManifestException("$context is missing required field '$field'")

    /** Parses a manifest document. Structural problems (bad JSON, missing fields) throw here;
     *  semantic problems (missing audio, duplicate ids) are reported by [validateFixtures]. */
    fun parse(json: String): Manifest = try {
        val root = JSONObject(json)
        if (!root.has("fixtures")) throw ManifestException("Manifest is missing the 'fixtures' array")
        val array: JSONArray = root.getJSONArray("fixtures")
        val fixtures = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val context = "fixtures[$i]"
            val scenariosArray = obj.optJSONArray("scenarios") ?: JSONArray()
            Fixture(
                id = obj.requiredString("id", context),
                audioPath = obj.requiredString("audioPath", context),
                referenceText = obj.requiredString("referenceText", context),
                durationMs = obj.requiredLong("durationMs", context),
                language = obj.requiredString("language", context),
                scenarios = (0 until scenariosArray.length()).map { scenariosArray.getString(it) },
                source = obj.requiredString("source", context),
                consent = obj.requiredString("consent", context),
                sha256 = obj.optString("sha256", "").takeIf { it.isNotBlank() },
            )
        }
        Manifest(version = root.optInt("version", 1), fixtures = fixtures)
    } catch (e: JSONException) {
        throw ManifestException("Failed to parse transcription eval manifest: ${e.message}")
    }

    fun parse(file: File): Manifest =
        if (!file.isFile) throw ManifestException("Manifest not found: ${file.path}")
        else parse(file.readText())

    /** SHA-256 of the raw manifest document, recorded in every report so a set of results can be
     *  tied back to the exact corpus definition that produced them. */
    fun checksum(document: String): String = sha256Hex(document.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ validation

    /**
     * Returns every problem found in [fixtures], resolved against [audioRoot]. An empty list means
     * the corpus is usable. All problems are collected rather than throwing on the first, so a
     * broken corpus can be fixed in one pass instead of one error per run.
     */
    fun validateFixtures(fixtures: List<Fixture>, audioRoot: File): List<String> {
        val errors = mutableListOf<String>()

        fixtures.groupBy { it.id.trim() }
            .filter { (id, group) -> id.isNotEmpty() && group.size > 1 }
            .forEach { (id, group) -> errors.add("Duplicate fixture id '$id' used by ${group.size} fixtures") }

        fixtures.forEachIndexed { index, fixture ->
            val label = "fixtures[$index]" + if (fixture.id.isNotBlank()) " (id '${fixture.id}')" else ""

            if (fixture.id.isBlank()) errors.add("$label: id is blank")
            if (fixture.referenceText.isBlank()) errors.add("$label: referenceText is blank — a fixture with no ground truth cannot be scored")
            if (fixture.language.isBlank()) errors.add("$label: language tag is blank")
            if (fixture.source.isBlank()) errors.add("$label: source (provenance) is blank — every fixture must record where its audio came from")
            if (fixture.consent.isBlank()) errors.add("$label: consent/licence is blank — voice recordings are biometric data and need explicit consent or licence terms")
            if (fixture.durationMs <= 0) errors.add("$label: durationMs must be positive, got ${fixture.durationMs}")

            if (fixture.scenarios.isEmpty()) {
                errors.add("$label: no scenario tags — tag at least one of ${KNOWN_SCENARIOS.sorted().joinToString()}")
            }
            fixture.scenarios.filterNot { it in KNOWN_SCENARIOS }.forEach { unknown ->
                errors.add("$label: unknown scenario category '$unknown' (known: ${KNOWN_SCENARIOS.sorted().joinToString()})")
            }

            if (fixture.audioPath.isBlank()) {
                errors.add("$label: audioPath is blank")
            } else {
                val audio = File(audioRoot, fixture.audioPath)
                if (!audio.isFile) {
                    errors.add("$label: audio file is missing at ${audio.path}")
                } else {
                    val declared = fixture.sha256
                    if (declared != null) {
                        val actual = sha256Hex(audio.readBytes())
                        if (!actual.equals(declared, ignoreCase = true)) {
                            errors.add("$label: sha256 mismatch — manifest declares $declared, file is $actual")
                        }
                    }
                }
            }
        }
        return errors
    }

    /**
     * Returns every problem with the requested model list.
     *
     * An empty list is an error, never a cue to fall back to
     * [com.trevornk.ramblr.GeminiTranscriberClient.DEFAULT_MODEL]: the whole purpose of this
     * benchmark is comparing named models, and a run that quietly benchmarks the shipped default
     * while the operator believes they configured something else produces actively misleading
     * results.
     */
    fun validateModelIds(modelIds: List<String>): List<String> {
        val errors = mutableListOf<String>()
        if (modelIds.isEmpty()) {
            errors.add(
                "Model list is empty — specify at least one model id via GEMINI_TRANSCRIPTION_MODELS. " +
                    "The benchmark will not substitute a default on your behalf."
            )
            return errors
        }
        modelIds.forEachIndexed { index, id ->
            if (id.isBlank()) errors.add("Model id at position $index is blank")
        }
        modelIds.filter { it.isNotBlank() }
            .groupBy { it }
            .filter { it.value.size > 1 }
            .forEach { (id, group) -> errors.add("Duplicate model id '$id' listed ${group.size} times") }
        return errors
    }

    /** Convenience wrapper: validates fixtures and models together and throws one combined
     *  [ManifestException] listing every problem, or returns normally when everything is clean. */
    fun validateOrThrow(fixtures: List<Fixture>, audioRoot: File, modelIds: List<String>) {
        val errors = validateFixtures(fixtures, audioRoot) + validateModelIds(modelIds)
        if (errors.isNotEmpty()) {
            throw ManifestException(
                "Transcription eval configuration is invalid (${errors.size} problem(s)):\n" +
                    errors.joinToString("\n") { "  - $it" }
            )
        }
    }
}
