package com.trevornk.ramblr

import com.trevornk.ramblr.VocabularySuggestionExtractor.CandidateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularySuggestionExtractorTest {

    /** A small stand-in for the bundled suggestion-filter dictionary: common words only,
     *  no names — mirroring the real list's proper-noun exclusion. */
    private val commonWords = setOf(
        "the", "quick", "brown", "fox", "jumped", "over", "lazy", "dog", "server", "deploy",
        "deployed", "moved", "everything", "yesterday", "with", "this", "that", "house",
        "color", "colour", "colors", "colours", "practice", "practise", "honorable",
        "honourable", "present", "prevent", "sheriff", "meeting", "about", "talked", "today",
        "centre", "center", "organise", "organize", "travelling", "traveling", "catalogue",
        "catalog", "analyse", "analyze",
    )

    private fun extract(
        raw: String,
        final: String,
        vocab: List<String> = emptyList(),
    ): List<CandidateEvent> =
        VocabularySuggestionExtractor.extract(raw, final, vocab) { it in commonWords }

    // --- Signal 1: pair extraction + alignment --------------------------------------------------

    @Test fun `equal texts produce no events`() {
        assertTrue(extract("the quick brown fox", "the quick brown fox").isEmpty())
    }

    @Test fun `substitution pair surfaces the intended word with its heard form`() {
        val events = extract(
            "we deployed it on hetzler yesterday",
            "We deployed it on Hetzner yesterday.",
        )
        assertEquals(1, events.size)
        assertEquals("Hetzner", events[0].term)
        assertEquals("hetzler", events[0].heardForm)
        assertFalse(events[0].cloudOnly)
    }

    @Test fun `alignment survives cleanup inserting and deleting surrounding words`() {
        // Cleanup dropped a filler, added punctuation, and fixed the name.
        val events = extract(
            "um so we talked about elzinore in the meeting",
            "We talked about Elsinore in the meeting.",
        )
        assertEquals(listOf("Elsinore"), events.map { it.term })
        assertEquals("elzinore", events[0].heardForm)
    }

    @Test fun `casing-only differences are not substitution pairs`() {
        assertTrue(extract("talked to hetzner today", "Talked to hetzner today").isEmpty())
    }

    // --- Signal 1 filters -----------------------------------------------------------------------

    @Test fun `intended word in the filter dictionary is never suggested`() {
        // "present -> prevent": both ordinary words; the dictionary kills it.
        assertTrue(extract("they present it", "they prevent it").isEmpty())
    }

    @Test fun `short words are never suggested`() {
        assertTrue(extract("the cat sat", "the car sat").isEmpty())
    }

    @Test fun `variant spelling pairs are killed even when one side escapes the dictionary`() {
        val cases = listOf(
            "colour" to "color",
            "colours" to "colors",
            "practise" to "practice",
            "honourable" to "honorable",
            "centre" to "center",
            "organise" to "organize",
            "travelling" to "traveling",
            "catalogue" to "catalog",
            "analyse" to "analyze",
        )
        for ((heard, intended) in cases) {
            val events = VocabularySuggestionExtractor.extract(
                "the $heard thing", "the $intended thing", emptyList()
            ) { false } // dictionary disabled: proves the kill rule alone removes these
            assertTrue("$heard->$intended should be killed as a variant pair", events.isEmpty())
        }
    }

    @Test fun `variantNormalize equates known dialect shapes`() {
        assertEquals(
            VocabularySuggestionExtractor.variantNormalize("colour"),
            VocabularySuggestionExtractor.variantNormalize("color"),
        )
        assertEquals(
            VocabularySuggestionExtractor.variantNormalize("colours"),
            VocabularySuggestionExtractor.variantNormalize("color"),
        )
        assertEquals(
            VocabularySuggestionExtractor.variantNormalize("organisation"),
            VocabularySuggestionExtractor.variantNormalize("organization"),
        )
    }

    @Test fun `variantNormalize keeps genuinely different words apart`() {
        assertFalse(
            VocabularySuggestionExtractor.variantNormalize("hetzler") ==
                VocabularySuggestionExtractor.variantNormalize("hetzner")
        )
    }

    @Test fun `pair within corrector bounds is locally correctable`() {
        // hetzler -> hetzner: len 7, DL distance 1 <= budget 2, same first char.
        assertTrue(VocabularySuggestionExtractor.withinCorrectorBounds("hetzler", "hetzner"))
    }

    @Test fun `pair beyond the edit budget is tagged cloud-only, not dropped`() {
        // "sonarr" heard as "sooner are"-style mangles: distance too large.
        val events = extract(
            "we upgraded sundeck last night",
            "We upgraded Solveit last night.",
        )
        assertEquals(1, events.size)
        assertEquals("Solveit", events[0].term)
        assertTrue(events[0].cloudOnly)
    }

    @Test fun `pair with mismatched first letter is cloud-only`() {
        assertFalse(VocabularySuggestionExtractor.withinCorrectorBounds("betzner", "hetzner"))
        val events = extract("deploy on betzner", "Deploy on Hetzner")
        assertEquals(1, events.size)
        assertTrue(events[0].cloudOnly)
    }

    @Test fun `intended words with digits or at-signs are never suggested`() {
        assertTrue(extract("email trevor at nash", "email trevor@nash.com").isEmpty())
        assertTrue(
            VocabularySuggestionExtractor.extract(
                "we use llama tree", "we use llama3", emptyList()
            ) { false }.isEmpty()
        )
    }

    // --- Signal 2: novel mid-cap words ----------------------------------------------------------

    @Test fun `mid-sentence capitalized novel word is suggested`() {
        val events = extract("", "We migrated everything to Tailscale today.")
        assertEquals(listOf("Tailscale"), events.map { it.term })
        assertNull(events[0].heardForm)
        assertFalse(events[0].cloudOnly)
    }

    @Test fun `sentence-initial capitals do not count as name-shaped`() {
        assertTrue(extract("", "Tailscale is working now.").isEmpty())
        assertTrue(extract("", "Everything works. Tailscale too.").isEmpty())
    }

    @Test fun `lowercase novel words are not suggested`() {
        assertTrue(extract("", "we migrated everything to tailscale today").isEmpty())
    }

    @Test fun `common words are not suggested even when capitalized mid-sentence`() {
        assertTrue(extract("", "the Sheriff arrived").isEmpty())
    }

    @Test fun `short capitalized words are not suggested`() {
        assertTrue(extract("", "we saw Oz there").isEmpty()) // < 4 chars
    }

    @Test fun `signal 2 respects quotes and dashes before a true sentence start`() {
        // After ". \"" the word is still sentence-initial.
        assertTrue(extract("", "It broke. \u201CTailscale is fine.\u201D").isEmpty())
    }

    @Test fun `possessive evidence counts for the bare term`() {
        val events = extract("", "I checked Tailscale's dashboard.")
        assertEquals(listOf("Tailscale"), events.map { it.term })
    }

    // --- vocab suppression + event uniqueness ---------------------------------------------------

    @Test fun `terms already in the vocabulary are never suggested`() {
        assertTrue(extract("deploy on hetzler", "Deploy on Hetzner", vocab = listOf("Hetzner")).isEmpty())
        // Case-insensitive.
        assertTrue(extract("deploy on hetzler", "Deploy on Hetzner", vocab = listOf("hetzner")).isEmpty())
        // Constituent words of multi-word terms are suppressed too.
        assertTrue(extract("", "I asked Claude about it", vocab = listOf("Claude Code")).isEmpty())
    }

    @Test fun `one dictation yields at most one event per term`() {
        val events = extract(
            "",
            "We use Tailscale everywhere; the Tailscale dashboard shows it. I like Tailscale.",
        )
        assertEquals(1, events.size)
        assertEquals("Tailscale", events[0].term)
    }

    @Test fun `pair evidence wins over signal 2 for the same term`() {
        val events = extract(
            "we moved to hetzler and hetzner stayed up",
            "We moved to Hetzner and Hetzner stayed up.",
        )
        assertEquals(1, events.size)
        assertEquals("hetzler", events[0].heardForm)
    }

    // --- bounds ---------------------------------------------------------------------------------

    @Test fun `texts over the token cap are skipped entirely`() {
        val longText = (1..VocabularySuggestionExtractor.MAX_TOKENS + 1).joinToString(" ") { "word$it" }
        assertTrue(extract(longText, "$longText Tailscale").isEmpty())
        assertTrue(extract("short raw", "$longText Tailscale extra").isEmpty())
    }

    @Test fun `blank final text produces nothing`() {
        assertTrue(extract("something was said", "").isEmpty())
        assertTrue(extract("", "").isEmpty())
    }
}
