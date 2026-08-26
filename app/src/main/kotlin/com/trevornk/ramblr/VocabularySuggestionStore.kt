package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence + thresholding for smart vocabulary suggestions (#216). Sits beside
 * [VocabularyTerms] in the same "ramblr" prefs file, same plain-prefs reasoning: candidate
 * counters aren't secrets and they must survive process death.
 *
 * Two independent prefs keys, deliberately separate:
 *
 *  - [CANDIDATES_KEY]: a JSON map of candidate term → counters. This is accumulated telemetry,
 *    so the master toggle ([VocabularySuggestionsToggle]) clears it on disable — toggle off
 *    means nothing is retained.
 *  - [DISMISSED_KEY]: the user's dismissed terms, newline-delimited like [VocabularyTerms]'s
 *    storage. This is explicit user intent, not telemetry, so it SURVIVES the toggle and is
 *    only ever edited through [dismiss]/[restore].
 *
 * Per-candidate counters (see [Candidate]): distinct-dictation count, a bounded set of distinct
 * epoch-days seen (last [MAX_DAYS_TRACKED]), first/last-seen timestamps, the latest heard form
 * (Signal-1 evidence for the UI line, a single token — never transcript text), and whether every
 * observation fell outside [VocabularyPostCorrector]'s local matching bounds (cloud-only).
 *
 * A candidate becomes a visible suggestion at ≥ [SUGGEST_MIN_DICTATIONS] distinct dictations
 * spanning ≥ [SUGGEST_MIN_DAYS] distinct calendar days (the spike's strongest junk lever:
 * one-off confusions never recur, real jargon recurs across days). At most
 * [MAX_PENDING_SUGGESTIONS] suggestions surface at once, highest count first. The map is capped
 * at [MAX_CANDIDATES] entries with lowest-count-then-oldest eviction — the spike's storage math
 * shows a 500-entry counter loses nothing that could ever clear the threshold.
 *
 * All methods take [SharedPreferences] directly (with Context conveniences) so tests run
 * host-side against a fake, matching the toggle-object pattern used across this codebase.
 */
object VocabularySuggestionStore {

    const val CANDIDATES_KEY = "vocab_suggestion_candidates"
    const val DISMISSED_KEY = "vocab_suggestion_dismissed"

    const val SUGGEST_MIN_DICTATIONS = 3
    const val SUGGEST_MIN_DAYS = 2
    const val MAX_PENDING_SUGGESTIONS = 5
    const val MAX_CANDIDATES = 500
    /** Distinct epoch-days kept per candidate; 2 clears the threshold, 8 gives headroom. */
    const val MAX_DAYS_TRACKED = 8

    private const val PREFS_NAME = "ramblr"
    private const val MS_PER_DAY = 86_400_000L

    /** One tracked candidate. [term] preserves the display casing last seen in accepted text. */
    data class Candidate(
        val term: String,
        val count: Int,
        val days: List<Long>,
        val firstSeenMs: Long,
        val lastSeenMs: Long,
        val heardForm: String?,
        val cloudOnly: Boolean,
    )

    /** One surfaced suggestion, ready for the UI. */
    data class Suggestion(
        val term: String,
        val count: Int,
        val heardForm: String?,
        val cloudOnly: Boolean,
    ) {
        /** The evidence line shown under the term, e.g. `Heard as "hetzler" — seen in 4
         *  dictations`. */
        fun evidenceLine(): String {
            val dictations = "seen in $count dictation" + if (count == 1) "" else "s"
            return if (heardForm != null) "Heard as \u201C$heardForm\u201D \u2014 $dictations" else dictations.replaceFirstChar { it.uppercase() }
        }
    }

    // --- recording ------------------------------------------------------------------------------

    /**
     * Records one accepted dictation's extracted [events] (at most one per term, as
     * [VocabularySuggestionExtractor.extract] guarantees), so each call bumps each term's
     * distinct-dictation count by exactly one. Terms currently dismissed or already in
     * [vocabularyTerms] are dropped (and any stale counters for them removed).
     */
    fun recordEvents(
        prefs: SharedPreferences,
        events: List<VocabularySuggestionExtractor.CandidateEvent>,
        vocabularyTerms: List<String>,
        nowMs: Long,
    ) {
        if (events.isEmpty()) return
        val dismissed = dismissedTerms(prefs).map { it.lowercase() }.toHashSet()
        val vocab = vocabularyTerms.map { it.trim().lowercase() }.toHashSet()
        val candidates = loadCandidates(prefs)
        candidates.keys.removeAll { it in dismissed || it in vocab }
        val day = nowMs / MS_PER_DAY
        for (event in events) {
            val key = event.term.lowercase()
            if (key in dismissed || key in vocab) continue
            val existing = candidates[key]
            candidates[key] = if (existing == null) {
                Candidate(
                    term = event.term,
                    count = 1,
                    days = listOf(day),
                    firstSeenMs = nowMs,
                    lastSeenMs = nowMs,
                    heardForm = event.heardForm,
                    cloudOnly = event.cloudOnly,
                )
            } else {
                existing.copy(
                    term = event.term,
                    count = existing.count + 1,
                    days = (existing.days + day).distinct().sorted().takeLast(MAX_DAYS_TRACKED),
                    lastSeenMs = nowMs,
                    heardForm = event.heardForm ?: existing.heardForm,
                    // A single in-bounds observation proves local correctability.
                    cloudOnly = existing.cloudOnly && event.cloudOnly,
                )
            }
        }
        evictToCap(candidates)
        saveCandidates(prefs, candidates)
    }

    /** Lowest count evicted first, oldest last-seen breaking ties — recurring candidates are
     *  the whole point, so they are the last to go. */
    private fun evictToCap(candidates: LinkedHashMap<String, Candidate>) {
        if (candidates.size <= MAX_CANDIDATES) return
        val evict = candidates.entries
            .sortedWith(compareBy({ it.value.count }, { it.value.lastSeenMs }))
            .take(candidates.size - MAX_CANDIDATES)
            .map { it.key }
        candidates.keys.removeAll(evict.toSet())
    }

    // --- surfacing ------------------------------------------------------------------------------

    /**
     * The pending suggestions to show: candidates over both thresholds, minus anything now in
     * [vocabularyTerms] or dismissed, highest count first, capped at [MAX_PENDING_SUGGESTIONS].
     */
    fun pendingSuggestions(prefs: SharedPreferences, vocabularyTerms: List<String>): List<Suggestion> {
        val dismissed = dismissedTerms(prefs).map { it.lowercase() }.toHashSet()
        val vocab = vocabularyTerms.map { it.trim().lowercase() }.toHashSet()
        return loadCandidates(prefs).values.asSequence()
            .filter { it.term.lowercase() !in dismissed && it.term.lowercase() !in vocab }
            .filter { it.count >= SUGGEST_MIN_DICTATIONS && it.days.size >= SUGGEST_MIN_DAYS }
            .sortedWith(compareByDescending<Candidate> { it.count }.thenBy { it.term.lowercase() })
            .take(MAX_PENDING_SUGGESTIONS)
            .map { Suggestion(it.term, it.count, it.heardForm, it.cloudOnly) }
            .toList()
    }

    /** Drops [term]'s counters (used after the user Adds it to the vocabulary — the vocab entry
     *  itself is written by the caller through the normal [VocabularyTerms] path). */
    fun removeCandidate(prefs: SharedPreferences, term: String) {
        val candidates = loadCandidates(prefs)
        if (candidates.remove(term.lowercase()) != null) saveCandidates(prefs, candidates)
    }

    /** Clears every accumulated counter. Called when the master toggle turns off; the dismissed
     *  list is intentionally untouched (user intent, not telemetry). */
    fun clearCandidates(prefs: SharedPreferences) {
        prefs.edit().remove(CANDIDATES_KEY).apply()
    }

    // --- dismissed list -------------------------------------------------------------------------

    /** Moves [term] to the dismissed list and drops its counters. Dismissed terms are never
     *  suggested again until restored. */
    fun dismiss(prefs: SharedPreferences, term: String) {
        val dismissed = dismissedTerms(prefs)
        if (dismissed.none { it.equals(term, ignoreCase = true) }) {
            prefs.edit()
                .putString(DISMISSED_KEY, VocabularyTerms.serialize(dismissed + term))
                .apply()
        }
        removeCandidate(prefs, term)
    }

    /** The dismissed terms, in dismissal order, display casing preserved. */
    fun dismissedTerms(prefs: SharedPreferences): List<String> =
        VocabularyTerms.parse(prefs.getString(DISMISSED_KEY, null))

    /** Un-dismisses [term]: it becomes an eligible candidate again, counters restarting from
     *  zero (its old counters were dropped at dismissal). */
    fun restore(prefs: SharedPreferences, term: String) {
        val remaining = dismissedTerms(prefs).filterNot { it.equals(term, ignoreCase = true) }
        prefs.edit().putString(DISMISSED_KEY, VocabularyTerms.serialize(remaining)).apply()
    }

    // --- Context conveniences -------------------------------------------------------------------

    fun pendingSuggestions(context: Context, vocabularyTerms: List<String>): List<Suggestion> =
        pendingSuggestions(prefs(context), vocabularyTerms)

    fun dismissedTerms(context: Context): List<String> = dismissedTerms(prefs(context))

    fun dismiss(context: Context, term: String) = dismiss(prefs(context), term)

    fun restore(context: Context, term: String) = restore(prefs(context), term)

    fun removeCandidate(context: Context, term: String) = removeCandidate(prefs(context), term)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- JSON (de)serialization -----------------------------------------------------------------

    internal fun loadCandidates(prefs: SharedPreferences): LinkedHashMap<String, Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        val raw = prefs.getString(CANDIDATES_KEY, null) ?: return out
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return out // corrupt blob: start over rather than crash the dictation path
        }
        for (key in json.keys()) {
            val o = json.optJSONObject(key) ?: continue
            val daysArr = o.optJSONArray("d") ?: JSONArray()
            val days = ArrayList<Long>(daysArr.length())
            for (i in 0 until daysArr.length()) days += daysArr.optLong(i)
            out[key] = Candidate(
                term = o.optString("t", key),
                count = o.optInt("c", 0),
                days = days,
                firstSeenMs = o.optLong("f", 0L),
                lastSeenMs = o.optLong("l", 0L),
                heardForm = if (o.has("h")) o.optString("h") else null,
                cloudOnly = o.optBoolean("x", false),
            )
        }
        return out
    }

    private fun saveCandidates(prefs: SharedPreferences, candidates: Map<String, Candidate>) {
        val json = JSONObject()
        for ((key, c) in candidates) {
            json.put(key, JSONObject().apply {
                put("t", c.term)
                put("c", c.count)
                put("d", JSONArray(c.days))
                put("f", c.firstSeenMs)
                put("l", c.lastSeenMs)
                if (c.heardForm != null) put("h", c.heardForm)
                put("x", c.cloudOnly)
            })
        }
        prefs.edit().putString(CANDIDATES_KEY, json.toString()).apply()
    }
}
