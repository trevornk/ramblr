package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM check (no Robolectric needed -- [SnippetManagerActivity.HELP_TEXT] is a `const`) that
 * the Snippets settings screen's help copy truthfully discloses the cleanup-interaction
 * limitation [SnippetExpander]'s kdoc documents and [SnippetExpanderCleanupInteractionTest]
 * exercises directly, rather than staying silent about it or claiming a trigger-protection
 * guarantee the matching contract doesn't actually provide (expansion runs strictly after
 * cleanup, with no mechanism that shields a trigger phrase from a wording-changing rewrite).
 */
class SnippetManagerHelpTextTest {

    @Test fun `help text discloses that a cleanup rewrite can prevent a trigger from matching`() {
        val text = SnippetManagerActivity.HELP_TEXT.lowercase()
        assertTrue(
            "help text must mention cleanup/rewrite interacting with trigger matching: '$text'",
            text.contains("cleanup") && (text.contains("wording") || text.contains("rewrite")),
        )
        assertTrue(
            "help text must state the effect (match can fail), not just describe cleanup running",
            text.contains("prevent") || text.contains("not match") || text.contains("won't match") ||
                text.contains("can't match") || text.contains("stop") || text.contains("fail"),
        )
    }

    @Test fun `help text does not overclaim protection cleanup cannot actually provide`() {
        val text = SnippetManagerActivity.HELP_TEXT.lowercase()
        // No fabricated "protected"/"safe from"/"guaranteed" language -- this feature has no
        // mechanism (fragile placeholder or otherwise) that shields a trigger from a rewrite that
        // changes its wording; the honest disclosure above is the whole story.
        assertFalse(text.contains("protected"))
        assertFalse(text.contains("guaranteed"))
        assertFalse(text.contains("safe from"))
    }

    @Test fun `help text still states expansion is inserted verbatim after cleanup runs`() {
        val text = SnippetManagerActivity.HELP_TEXT.lowercase()
        assertTrue(text.contains("cleanup"))
        assertTrue(text.contains("vocabulary correction"))
    }
}
