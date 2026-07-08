package com.trevornk.ramblr

/**
 * Derives the user-facing "where does cloud cleanup actually go" facts from the live provider chain,
 * so the off-device consent dialog and the Settings subtitles name the *real* destination instead of
 * the legacy `cleanup_base_url`/`cleanup_model` prefs that nothing writes (they always read
 * "api.openai.com" / "gpt-4o-mini" even when cleanup routes to Gemini/Anthropic/OmniRoute) (M9).
 * Pure -- takes the chain, no Context.
 */
object CleanupDestination {
    /** The entry that would actually serve cloud cleanup: the first non-LOCAL entry in [chain]
     *  (every non-LOCAL kind supports cleanup), or null when the chain is local-only / empty. */
    fun firstCloudEntry(chain: ProviderChain): ProviderChainEntry? =
        chain.entries.firstOrNull { it.kind != ProviderKind.LOCAL }

    /** Network host cloud cleanup would contact for [entry], e.g. "api.openai.com". */
    fun hostFor(entry: ProviderChainEntry): String = when (entry.kind) {
        ProviderKind.OPENAI -> PostProcessor.destinationHost(entry.baseUrlOverride ?: PostProcessor.DEFAULT_BASE_URL)
        ProviderKind.GEMINI -> PostProcessor.destinationHost(entry.baseUrlOverride ?: GeminiCleanupProvider.BASE_URL)
        ProviderKind.ANTHROPIC -> PostProcessor.destinationHost(entry.baseUrlOverride ?: AnthropicCleanupProvider.BASE_URL)
        ProviderKind.OMNIROUTE -> PostProcessor.destinationHost(entry.baseUrlOverride ?: OmniRoute.BASE_URL)
        ProviderKind.LOCAL -> "your device"
    }

    /** Display label for [kind], e.g. "OpenAI". */
    fun label(kind: ProviderKind): String = when (kind) {
        ProviderKind.OPENAI -> "OpenAI"
        ProviderKind.GEMINI -> "Gemini"
        ProviderKind.ANTHROPIC -> "Anthropic"
        ProviderKind.OMNIROUTE -> "OmniRoute"
        ProviderKind.LOCAL -> "on-device"
    }

    /** Host to name in the "cleanup sends text off-device" consent for [chain]; falls back to the
     *  OpenAI default the Cloud switch would seed when no cloud entry exists yet. */
    fun consentHost(chain: ProviderChain): String =
        firstCloudEntry(chain)?.let { hostFor(it) } ?: PostProcessor.destinationHost(PostProcessor.DEFAULT_BASE_URL)

    /** The "<provider> · <model>" detail for the Cleanup row's "Cloud · …" subtitle. */
    fun cloudSubtitleDetail(chain: ProviderChain): String {
        val entry = firstCloudEntry(chain)
            ?: return "${label(ProviderKind.OPENAI)} · ${PostProcessor.DEFAULT_MODEL}"
        val model = entry.model.ifBlank { defaultModelFor(entry.kind) }
        return "${label(entry.kind)} · $model"
    }

    private fun defaultModelFor(kind: ProviderKind): String = when (kind) {
        ProviderKind.GEMINI -> GeminiCleanupProvider.DEFAULT_MODEL
        else -> PostProcessor.DEFAULT_MODEL
    }
}
