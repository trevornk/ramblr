package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Explicit coverage for the cleanup-interaction limitation [SnippetExpander]'s kdoc documents
 * (#248): expansion runs on cleanup's OUTPUT, so a cleanup step that rewrites a trigger phrase's
 * wording (not just case/punctuation) destroys the surface this pass matches against and the
 * snippet does not fire. This is asserted directly rather than left as an unverified claim --
 * see the parent audit's requirement that this trade-off be tested, not assumed reliable.
 */
class SnippetExpanderCleanupInteractionTest {

    private val entries = listOf(SnippetEntry("k", "my home address", "123 Main St, Springfield"))

    @Test fun `trigger survives cleanup that only fixes case and punctuation`() {
        // Realistic light-cleanup output: capitalization and a period added, wording untouched.
        val cleaned = "Please send it to my home address."
        assertEquals(
            "Please send it to 123 Main St, Springfield.",
            SnippetExpander.expand(cleaned, entries),
        )
    }

    @Test fun `trigger does NOT survive cleanup that paraphrases the trigger wording`() {
        // A cleanup model rewording "my home address" into "my house's address" -- a plausible
        // grammar-cleanup rewrite -- destroys the exact word sequence this pass requires. This
        // is the documented, tested trade-off of running expansion after cleanup rather than
        // before it (see SnippetExpander's kdoc): the alternative (expand before cleanup) risks
        // the cleanup model paraphrasing the EXPANSION text instead, which is strictly worse for
        // the verbatim-address/signature use case #248 targets.
        val paraphrased = "Please send it to my house's address."
        val result = SnippetExpander.expand(paraphrased, entries)
        assertEquals("Please send it to my house's address.", result)
        assertNotEquals("Please send it to 123 Main St, Springfield.", result)
    }

    @Test fun `trigger does NOT survive cleanup that drops or reorders trigger words`() {
        val reordered = "Please send it to my address at home."
        assertEquals(reordered, SnippetExpander.expand(reordered, entries))
    }

    @Test fun `trigger survives cleanup that only adds trailing punctuation or whitespace`() {
        assertEquals(
            "123 Main St, Springfield ",
            SnippetExpander.expand("my home address ", entries),
        )
    }
}
