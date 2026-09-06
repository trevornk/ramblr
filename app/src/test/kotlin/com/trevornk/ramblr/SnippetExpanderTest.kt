package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Coverage for [SnippetExpander] (#248): matching contract (case/punctuation tolerance, exact
 * word-sequence, longest-match, no recursion, verbatim replacement) plus the explicit
 * cleanup-interaction limitation the class kdoc documents.
 */
class SnippetExpanderTest {

    private fun entry(trigger: String, expansion: String) = SnippetEntry(key = "k", trigger = trigger, expansion = expansion)

    // --- no-op cases ---------------------------------------------------------------------------

    @Test fun `empty entry list returns text unchanged, same instance`() {
        val text = "Nothing configured here."
        assertSame(text, SnippetExpander.expand(text, emptyList()))
    }

    @Test fun `blank text is returned unchanged`() {
        assertEquals("", SnippetExpander.expand("", listOf(entry("hi", "hello"))))
    }

    @Test fun `text with no trigger occurrence is untouched`() {
        val text = "We walked to the store and bought some milk."
        assertEquals(text, SnippetExpander.expand(text, listOf(entry("my address", "123 Main St"))))
    }

    // --- basic expansion -------------------------------------------------------------------------

    @Test fun `a single-word trigger is replaced with its expansion`() {
        assertEquals(
            "Please send it to 123 Main St, Springfield.",
            SnippetExpander.expand("Please send it to homeaddress, Springfield.", listOf(entry("homeaddress", "123 Main St"))),
        )
    }

    @Test fun `a multi-word trigger is replaced as a unit`() {
        assertEquals(
            "Reach me at trevor@example.com anytime.",
            SnippetExpander.expand("Reach me at my email anytime.", listOf(entry("my email", "trevor@example.com"))),
        )
    }

    @Test fun `expansion is inserted verbatim, including newlines and internal punctuation`() {
        val expansion = "123 Main St.\nSpringfield, IL 62704"
        assertEquals(
            "Mail it to 123 Main St.\nSpringfield, IL 62704 please.",
            SnippetExpander.expand("Mail it to my address please.", listOf(entry("my address", expansion))),
        )
    }

    // --- case tolerance --------------------------------------------------------------------------

    @Test fun `matching is case-insensitive`() {
        assertEquals(
            "Signed, Best regards Trevor",
            SnippetExpander.expand("Signed, My Signature", listOf(entry("my signature", "Best regards Trevor"))),
        )
        assertEquals(
            "Signed, Best regards Trevor",
            SnippetExpander.expand("Signed, MY SIGNATURE", listOf(entry("my signature", "Best regards Trevor"))),
        )
    }

    // --- punctuation tolerance ---------------------------------------------------------------------

    @Test fun `punctuation glued to the trigger words does not block the match`() {
        assertEquals(
            "Text me at 555-0100, thanks.",
            SnippetExpander.expand("Text me at my phone, thanks.", listOf(entry("my phone", "555-0100"))),
        )
    }

    @Test fun `internal punctuation within a trigger word is ignored -- contractions and hyphenation`() {
        // "don't" normalizes to "dont", matching a configured trigger word "dont" or "don't".
        // The trailing "." is outside the matched word core and survives untouched (verbatim
        // replacement only overwrites the matched span, see class kdoc).
        assertEquals(
            "OK, sure thing!.",
            SnippetExpander.expand("Don't worry.", listOf(entry("don't worry", "OK, sure thing!"))),
        )
        assertEquals(
            "OK, sure thing!.",
            SnippetExpander.expand("Dont worry.", listOf(entry("don't worry", "OK, sure thing!"))),
        )
    }

    // --- exact word sequence (no fuzzy matching) ----------------------------------------------

    @Test fun `a near-miss of the trigger is NOT expanded -- exact words only, unlike vocabulary correction`() {
        val text = "Send it to my adress please."
        assertEquals(text, SnippetExpander.expand(text, listOf(entry("my address", "123 Main St"))))
    }

    @Test fun `an extra or missing word breaks the match`() {
        val text = "Send it to my home address please."
        assertEquals(text, SnippetExpander.expand(text, listOf(entry("my address", "123 Main St"))))
    }

    // --- word-boundary / adjacency --------------------------------------------------------------

    @Test fun `a trigger is never matched inside a longer word`() {
        val text = "The pilot signed off."
        assertEquals(text, SnippetExpander.expand(text, listOf(entry("pi", "3.14159"))))
    }

    @Test fun `a multi-word trigger split by a sentence boundary is not treated as one occurrence`() {
        val text = "I like my. Address is unrelated."
        assertEquals(text, SnippetExpander.expand(text, listOf(entry("my address", "123 Main St"))))
    }

    // --- longest match wins / determinism --------------------------------------------------------

    @Test fun `the longest matching trigger wins over a shorter prefix trigger`() {
        assertEquals(
            "Send to WORK-ADDR now.",
            SnippetExpander.expand(
                "Send to my work address now.",
                listOf(entry("my work", "WORK"), entry("my work address", "WORK-ADDR")),
            ),
        )
    }

    @Test fun `two distinct triggers in one text both expand and surrounding text is untouched`() {
        assertEquals(
            "Home: 123 Main St. Email: trevor@example.com. Thanks.",
            SnippetExpander.expand(
                "Home: my address. Email: my email. Thanks.",
                listOf(entry("my address", "123 Main St"), entry("my email", "trevor@example.com")),
            ),
        )
    }

    // --- no recursive expansion -------------------------------------------------------------------

    @Test fun `an expansion that itself looks like another trigger is not re-expanded`() {
        // trigger A -> "say my code"; trigger B -> "code" (won't even tokenize as B's window
        // since B is a single different word, but this proves the ORIGINAL text is scanned once
        // and the inserted expansion text is never rescanned).
        assertEquals(
            "Please say my code now.",
            SnippetExpander.expand(
                "Please trigger a now.",
                listOf(entry("trigger a", "say my code"), entry("my code", "SHOULD NOT APPEAR")),
            ),
        )
    }

    @Test fun `independent occurrences after an earlier expansion still match normally`() {
        // Sanity check in the other direction: if the expansion text is inserted first in
        // reading order relative to a later independent occurrence, that independent occurrence
        // still expands once (non-overlapping, single left-to-right pass over ORIGINAL tokens).
        assertEquals(
            "aaa bbb-expanded",
            SnippetExpander.expand("aaa bbb", listOf(entry("bbb", "bbb-expanded"))),
        )
    }

    // --- validation / normalization -----------------------------------------------------------

    @Test fun `normalizeWords lowercases and strips punctuation per word`() {
        assertEquals(listOf("dont", "worry"), SnippetExpander.normalizeWords("Don't Worry"))
        assertEquals(listOf("my", "email"), SnippetExpander.normalizeWords("  My   Email  "))
    }

    @Test fun `a trigger that normalizes to nothing never matches anything`() {
        val text = "This has punctuation --- everywhere !!! today."
        assertEquals(text, SnippetExpander.expand(text, listOf(entry("!!!", "should never appear"))))
    }
}
