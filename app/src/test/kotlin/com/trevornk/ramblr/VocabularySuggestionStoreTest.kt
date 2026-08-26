package com.trevornk.ramblr

import android.content.SharedPreferences
import com.trevornk.ramblr.VocabularySuggestionExtractor.CandidateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularySuggestionStoreTest {

    private val prefs = FakeStringPrefs()

    private val day = 86_400_000L

    private fun event(term: String, heard: String? = null, cloudOnly: Boolean = false) =
        CandidateEvent(term, heard, cloudOnly)

    private fun record(vararg events: CandidateEvent, at: Long, vocab: List<String> = emptyList()) =
        VocabularySuggestionStore.recordEvents(prefs, events.toList(), vocab, at)

    private fun pending(vocab: List<String> = emptyList()) =
        VocabularySuggestionStore.pendingSuggestions(prefs, vocab)

    // --- threshold gating -----------------------------------------------------------------------

    @Test fun `no suggestion below three distinct dictations`() {
        record(event("Hetzner", "hetzler"), at = 0)
        record(event("Hetzner", "hetzler"), at = day)
        assertTrue(pending().isEmpty())
    }

    @Test fun `no suggestion when all dictations fall on one day`() {
        record(event("Hetzner"), at = 1)
        record(event("Hetzner"), at = 2)
        record(event("Hetzner"), at = 3)
        assertTrue(pending().isEmpty())
    }

    @Test fun `three dictations across two days surfaces the suggestion`() {
        record(event("Hetzner", "hetzler"), at = 0)
        record(event("Hetzner", "hetzler"), at = 1)
        record(event("Hetzner", "hetzler"), at = day)
        val suggestions = pending()
        assertEquals(1, suggestions.size)
        assertEquals("Hetzner", suggestions[0].term)
        assertEquals(3, suggestions[0].count)
        assertEquals("hetzler", suggestions[0].heardForm)
    }

    @Test fun `evidence line renders heard form and dictation count`() {
        record(event("Hetzner", "hetzler"), at = 0)
        record(event("Hetzner", "hetzler"), at = 1)
        record(event("Hetzner", "hetzler"), at = day)
        assertEquals(
            "Heard as \u201Chetzler\u201D \u2014 seen in 3 dictations",
            pending()[0].evidenceLine(),
        )
    }

    @Test fun `signal-2-only candidates render a count-only evidence line`() {
        record(event("Tailscale"), at = 0)
        record(event("Tailscale"), at = 1)
        record(event("Tailscale"), at = day)
        assertEquals("Seen in 3 dictations", pending()[0].evidenceLine())
    }

    // --- suggestion list shaping ----------------------------------------------------------------

    @Test fun `at most five suggestions surface, highest count first`() {
        for (i in 1..7) {
            val term = "Term$i"
            // Term i seen (i + 2) times across two days: all clear the threshold.
            for (n in 0 until i + 2) record(event(term), at = if (n == 0) 0 else day + n)
        }
        val suggestions = pending()
        assertEquals(5, suggestions.size)
        assertEquals(listOf("Term7", "Term6", "Term5", "Term4", "Term3"), suggestions.map { it.term })
    }

    @Test fun `terms in the vocabulary are filtered out of pending and their counters dropped`() {
        record(event("Hetzner"), at = 0)
        record(event("Hetzner"), at = 1)
        record(event("Hetzner"), at = day)
        assertEquals(1, pending().size)
        assertTrue(pending(vocab = listOf("hetzner")).isEmpty())
        // The next record drops the stale counter entirely.
        record(event("Other"), at = day, vocab = listOf("Hetzner"))
        assertNull(VocabularySuggestionStore.loadCandidates(prefs)["hetzner"])
    }

    @Test fun `recording a term already in the vocabulary is a no-op`() {
        record(event("Hetzner"), at = 0, vocab = listOf("Hetzner"))
        assertTrue(VocabularySuggestionStore.loadCandidates(prefs).isEmpty())
    }

    // --- counters -------------------------------------------------------------------------------

    @Test fun `heard form updates to the latest pair evidence and survives signal-2 events`() {
        record(event("Hetzner", "hetzler"), at = 0)
        record(event("Hetzner"), at = day) // signal-2 observation: keeps the heard form
        record(event("Hetzner", "hetsner"), at = day + 1)
        assertEquals("hetsner", pending()[0].heardForm)
    }

    @Test fun `cloud-only clears once any observation is within local corrector bounds`() {
        record(event("Hetzner", "xyz", cloudOnly = true), at = 0)
        record(event("Hetzner", "hetzler", cloudOnly = false), at = 1)
        record(event("Hetzner", "xyz", cloudOnly = true), at = day)
        assertFalse(pending()[0].cloudOnly)
    }

    @Test fun `display casing follows the most recent observation`() {
        record(event("tailscale"), at = 0)
        record(event("Tailscale"), at = 1)
        record(event("Tailscale"), at = day)
        assertEquals("Tailscale", pending()[0].term)
    }

    @Test fun `distinct day tracking is bounded`() {
        for (d in 0 until VocabularySuggestionStore.MAX_DAYS_TRACKED + 5) {
            record(event("Hetzner"), at = d * day)
        }
        val candidate = VocabularySuggestionStore.loadCandidates(prefs).getValue("hetzner")
        assertEquals(VocabularySuggestionStore.MAX_DAYS_TRACKED, candidate.days.size)
        assertEquals(VocabularySuggestionStore.MAX_DAYS_TRACKED + 5, candidate.count)
    }

    @Test fun `eviction at the cap removes lowest-count candidates first`() {
        // A recurring candidate that must survive...
        record(event("Keeper"), at = 0)
        record(event("Keeper"), at = day)
        record(event("Keeper"), at = day + 1)
        // ...then a flood of one-off candidates beyond the cap.
        for (i in 0 until VocabularySuggestionStore.MAX_CANDIDATES + 10) {
            record(event("Noise$i"), at = day + 2 + i)
        }
        val candidates = VocabularySuggestionStore.loadCandidates(prefs)
        assertEquals(VocabularySuggestionStore.MAX_CANDIDATES, candidates.size)
        assertEquals(3, candidates.getValue("keeper").count)
    }

    @Test fun `counters survive a save-load round trip`() {
        record(event("Hetzner", "hetzler", cloudOnly = true), at = 5)
        val candidate = VocabularySuggestionStore.loadCandidates(prefs).getValue("hetzner")
        assertEquals("Hetzner", candidate.term)
        assertEquals(1, candidate.count)
        assertEquals(listOf(0L), candidate.days)
        assertEquals(5L, candidate.firstSeenMs)
        assertEquals(5L, candidate.lastSeenMs)
        assertEquals("hetzler", candidate.heardForm)
        assertTrue(candidate.cloudOnly)
    }

    @Test fun `corrupt candidate blob resets instead of crashing`() {
        prefs.edit().putString(VocabularySuggestionStore.CANDIDATES_KEY, "not json{").apply()
        assertTrue(VocabularySuggestionStore.loadCandidates(prefs).isEmpty())
        record(event("Hetzner"), at = 0) // and recording works again
        assertEquals(1, VocabularySuggestionStore.loadCandidates(prefs).size)
    }

    // --- dismiss / restore ----------------------------------------------------------------------

    @Test fun `dismiss removes the suggestion and its counters`() {
        record(event("Hetzner"), at = 0)
        record(event("Hetzner"), at = 1)
        record(event("Hetzner"), at = day)
        VocabularySuggestionStore.dismiss(prefs, "Hetzner")
        assertTrue(pending().isEmpty())
        assertTrue(VocabularySuggestionStore.loadCandidates(prefs).isEmpty())
        assertEquals(listOf("Hetzner"), VocabularySuggestionStore.dismissedTerms(prefs))
    }

    @Test fun `dismissed terms never re-accumulate counters`() {
        VocabularySuggestionStore.dismiss(prefs, "Hetzner")
        record(event("Hetzner"), at = 0)
        record(event("hetzner"), at = day)
        assertTrue(VocabularySuggestionStore.loadCandidates(prefs).isEmpty())
    }

    @Test fun `dismissing twice keeps a single entry`() {
        VocabularySuggestionStore.dismiss(prefs, "Hetzner")
        VocabularySuggestionStore.dismiss(prefs, "hetzner")
        assertEquals(1, VocabularySuggestionStore.dismissedTerms(prefs).size)
    }

    @Test fun `restore makes the term eligible again with fresh counters`() {
        record(event("Hetzner"), at = 0)
        record(event("Hetzner"), at = 1)
        record(event("Hetzner"), at = day)
        VocabularySuggestionStore.dismiss(prefs, "Hetzner")
        VocabularySuggestionStore.restore(prefs, "Hetzner")
        assertTrue(VocabularySuggestionStore.dismissedTerms(prefs).isEmpty())
        // Counters restart from zero: one new sighting is not enough to suggest.
        record(event("Hetzner"), at = 2 * day)
        assertTrue(pending().isEmpty())
        assertEquals(1, VocabularySuggestionStore.loadCandidates(prefs).getValue("hetzner").count)
    }

    @Test fun `restore is case-insensitive and leaves other dismissed terms alone`() {
        VocabularySuggestionStore.dismiss(prefs, "Hetzner")
        VocabularySuggestionStore.dismiss(prefs, "Solveit")
        VocabularySuggestionStore.restore(prefs, "hetzner")
        assertEquals(listOf("Solveit"), VocabularySuggestionStore.dismissedTerms(prefs))
    }
}

/**
 * In-memory [SharedPreferences] fake covering the string+boolean surface the suggestion store
 * and toggle need — same pattern as the per-test fakes in [DebugVisibilityToggleTest] etc.,
 * shared here by the two #216 test classes in this file's package via internal visibility.
 */
internal class FakeStringPrefs(
    private val values: MutableMap<String, Any?> = mutableMapOf()
) : SharedPreferences {

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        throw UnsupportedOperationException()
    override fun getInt(key: String?, defValue: Int): Int = throw UnsupportedOperationException()
    override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException()
    override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException()
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableListOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            throw UnsupportedOperationException()
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(pending)
            pending.clear()
            removals.clear()
            clearAll = false
        }
    }
}
