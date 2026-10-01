package com.trevornk.ramblr

/**
 * A curated third-party OpenAI-compatible provider preset (#275): under the hood it's a plain
 * [ProviderKind.OPENAI] chain entry with [baseUrl] as its [ProviderChainEntry.baseUrlOverride]
 * -- Groq and OpenRouter both really do speak the OpenAI wire format
 * ([TranscriberClient]'s multipart `/audio/transcriptions` and [PostProcessor]'s
 * `/chat/completions`) -- but the preset gives the "Add provider" dialog a prefilled base URL
 * and a curated model catalog slice ([ModelCatalogEntry.presetId]) instead of making the user
 * hand-type a base URL through the hidden advanced field on a plain OpenAI entry (#273's
 * original report).
 *
 * No `forceWav`/compressed-upload override exists here: both shipped presets were verified live
 * against the real `.m4a` (AAC-in-MP4) upload Ramblr actually sends (see the #275 PR body's API
 * verification section) and accept it without issue, so there was nothing to force. A future
 * preset whose provider rejects the compressed format would need that as a new field here --
 * deliberately not speculatively added for a case neither shipped preset hits.
 */
data class ProviderPreset(
    val id: String,
    val displayLabel: String,
    val kind: ProviderKind,
    val baseUrl: String,
)

/** The full set of curated presets offered in "Add provider" (#275), plus lookup helpers. */
object ProviderPresets {
    val GROQ = ProviderPreset(
        id = "groq",
        displayLabel = "Groq",
        kind = ProviderKind.OPENAI,
        baseUrl = "https://api.groq.com/openai/v1",
    )

    val OPENROUTER = ProviderPreset(
        id = "openrouter",
        displayLabel = "OpenRouter",
        kind = ProviderKind.OPENAI,
        baseUrl = "https://openrouter.ai/api/v1",
    )

    val ALL: List<ProviderPreset> = listOf(GROQ, OPENROUTER)

    fun forId(id: String?): ProviderPreset? = id?.let { presetId -> ALL.firstOrNull { it.id == presetId } }

    /** Display label for [entry]: its preset's label if it has one, else the plain kind label
     *  passed in as [kindLabel] (callers already have their own kind->label mapping, e.g.
     *  [CloudProviderActivity.providerLabel] -- this avoids a second competing one here). */
    fun displayLabel(entry: ProviderChainEntry, kindLabel: (ProviderKind) -> String): String =
        forId(entry.presetId)?.displayLabel ?: kindLabel(entry.kind)
}
