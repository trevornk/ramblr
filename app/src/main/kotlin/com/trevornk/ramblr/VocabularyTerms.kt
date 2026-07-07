package com.trevornk.ramblr

/**
 * Personal vocabulary: project names and jargon the cleanup model tends to mishear, editable in
 * Settings instead of hardcoded into the prompt (see #26). Terms are stored in prefs as a
 * newline-delimited string — they aren't secrets, so plain prefs is fine.
 */
object VocabularyTerms {
    /** Seeded into prefs on first run so existing behavior doesn't regress (see #26). */
    val DEFAULTS = listOf(
        "Solveit", "fast.ai", "Answer.AI", "nbdev", "fastcore", "FastHTML", "Pi", "Codex", "Claude Code", "Hetzner"
    )

    val DEFAULT_SERIALIZED = serialize(DEFAULTS)

    /**
     * Parses the newline-delimited prefs value into a clean term list: blank lines dropped,
     * entries trimmed, and duplicates (case-insensitive) removed while keeping the first-seen
     * spelling and order.
     */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        return raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { seen.add(it.lowercase()) }
    }

    /** Serializes a term list back to the newline-delimited prefs format. */
    fun serialize(terms: List<String>): String = terms.joinToString("\n")
}
