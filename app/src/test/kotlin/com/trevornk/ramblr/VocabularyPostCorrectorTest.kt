package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Coverage for [VocabularyPostCorrector] (#182 option 2): the deterministic vocabulary
 * correction pass over local cleanup output that replaced prompt interpolation (which made
 * LFM2.5-350M echo the term list) and finally delivers vocabulary support for BOTH local models.
 *
 * The tests encode the pass's conservatism contract explicitly: a false positive (rewriting
 * legitimate prose into a vocabulary term) is much worse than a false negative, so the
 * guard tests here are as load-bearing as the correction tests.
 */
class VocabularyPostCorrectorTest {

    private val defaults = VocabularyTerms.DEFAULTS

    // --- no-op cases ---------------------------------------------------------------------------

    @Test fun `empty term list returns the text unchanged, same instance`() {
        val text = "This is a perfectly normal sentence."
        assertSame(text, VocabularyPostCorrector.correct(text, emptyList()))
    }

    @Test fun `blank text is returned unchanged`() {
        assertEquals("", VocabularyPostCorrector.correct("", defaults))
        assertEquals("   ", VocabularyPostCorrector.correct("   ", defaults))
    }

    @Test fun `text already matching a term exactly is untouched`() {
        val text = "I set up Hetzner with FastHTML yesterday."
        assertEquals(text, VocabularyPostCorrector.correct(text, defaults))
    }

    @Test fun `text with no term occurrences at all is untouched`() {
        val text = "We walked to the store and bought some milk."
        assertEquals(text, VocabularyPostCorrector.correct(text, defaults))
    }

    @Test fun `the pass is idempotent`() {
        val once = VocabularyPostCorrector.correct("I deployed it on hetzler's cloud.", listOf("Hetzner"))
        assertEquals("I deployed it on Hetzner's cloud.", once)
        assertEquals(once, VocabularyPostCorrector.correct(once, listOf("Hetzner")))
    }

    @Test fun `a possessive keeps its apostrophe-s through a recase`() {
        assertEquals(
            "Hetzner's dashboard is fine.",
            VocabularyPostCorrector.correct("hetzner's dashboard is fine.", listOf("Hetzner")),
        )
    }

    // --- case preservation ---------------------------------------------------------------------

    @Test fun `a case-insensitive exact match is recased to the canonical spelling`() {
        assertEquals(
            "Deployed on Hetzner last night.",
            VocabularyPostCorrector.correct("Deployed on hetzner last night.", listOf("Hetzner")),
        )
    }

    @Test fun `an all-caps mishearing of a term is recased`() {
        assertEquals(
            "FastHTML is neat.",
            VocabularyPostCorrector.correct("FASTHTML is neat.", listOf("FastHTML")),
        )
    }

    // --- near-miss single word -----------------------------------------------------------------

    @Test fun `a one-edit mishearing of a medium-length term is corrected`() {
        // "hetzler" -> "Hetzner": distance 1 (substitution), length 7, first letter matches,
        // not a common English word.
        assertEquals(
            "The server runs on Hetzner now.",
            VocabularyPostCorrector.correct("The server runs on hetzler now.", listOf("Hetzner")),
        )
    }

    @Test fun `a transposition mishearing is corrected`() {
        // "nbdve" -> "nbdev": adjacent transposition, distance 1.
        assertEquals(
            "Published with nbdev today.",
            VocabularyPostCorrector.correct("Published with nbdve today.", listOf("nbdev")),
        )
    }

    @Test fun `a two-edit mishearing of a long term is corrected`() {
        // "fasthamel" -> "fasthtml": length 8 core allows distance 2.
        assertEquals(
            "I built the site in FastHTML.",
            VocabularyPostCorrector.correct("I built the site in fasthamel.", listOf("FastHTML")),
        )
    }

    @Test fun `punctuation glued to the mishearing survives the replacement`() {
        assertEquals(
            "We moved to Hetzner, then scaled up.",
            VocabularyPostCorrector.correct("We moved to hetzler, then scaled up.", listOf("Hetzner")),
        )
    }

    // --- multi-word terms ----------------------------------------------------------------------

    @Test fun `a two-word term with one misheard word is corrected as a unit`() {
        // "clawed code" -> "Claude Code": "clawed" is a common word but the exact "code" sibling
        // anchors the window (see matchDistance kdoc).
        assertEquals(
            "I asked Claude Code to fix it.",
            VocabularyPostCorrector.correct("I asked clawed code to fix it.", listOf("Claude Code")),
        )
    }

    @Test fun `a two-word term in the wrong case is recased`() {
        assertEquals(
            "Claude Code finished the task.",
            VocabularyPostCorrector.correct("claude code finished the task.", listOf("Claude Code")),
        )
    }

    @Test fun `a multi-word term split by a sentence boundary is not treated as one occurrence`() {
        // "...Claude. Code..." spans a sentence boundary between the cores -- must not be fused.
        val text = "I like Claude. Code reviews are separate."
        assertEquals(text, VocabularyPostCorrector.correct(text, listOf("Claude Code")))
    }

    @Test fun `the longest matching term wins over a shorter one`() {
        // Both "Claude" and "Claude Code" configured: the two-word window must be consumed by
        // the longer term, not corrected word-by-word.
        assertEquals(
            "Ask Claude Code about it.",
            VocabularyPostCorrector.correct("Ask claude code about it.", listOf("Claude", "Claude Code")),
        )
    }

    // --- word-boundary safety ------------------------------------------------------------------

    @Test fun `a term is never corrected inside a longer word`() {
        // "spin" contains "pi"; "fastcorelike" contains "fastcore" but is 4 edits away as a
        // whole token -- word-boundary tokenization plus the edit budget must leave both alone.
        val text = "The spin class had a fastcorelike vibe."
        assertEquals(text, VocabularyPostCorrector.correct(text, listOf("Pi", "fastcore")))
    }

    @Test fun `a short term requires an exact match`() {
        // "Pi" (length 2) has edit budget 0: "pie" and "pin" must survive, exact "pi" is recased.
        assertEquals(
            "Pi is running, the pie is baking, the pin is set.",
            VocabularyPostCorrector.correct("pi is running, the pie is baking, the pin is set.", listOf("Pi")),
        )
    }

    // --- false-positive guards (the conservatism contract) -------------------------------------

    @Test fun `a common English word near a term is never rewritten`() {
        // "code" is distance 1 from "Codex" and "fast" is the core of "fast.ai"'s first token --
        // both are everyday words and must survive. This is the single most important test in
        // the file: without the common-word guard the pass corrupts ordinary prose.
        val text = "I write code fast and answer emails."
        assertEquals(text, VocabularyPostCorrector.correct(text, defaults))
    }

    @Test fun `a fuzzy candidate with a different first letter is never rewritten`() {
        // "rodex" is distance 1 from "codex" but starts differently -- mishearings keep the
        // initial sound, so this is a coincidence, not a mishearing.
        val text = "The rodex was on the desk."
        assertEquals(text, VocabularyPostCorrector.correct(text, listOf("Codex")))
    }

    @Test fun `two terms tying at the same distance abort the correction`() {
        // "hetzfer" is distance 1 from both fictional terms; ambiguity must yield no rewrite.
        val text = "It runs on hetzfer today."
        assertEquals(text, VocabularyPostCorrector.correct(text, listOf("Hetzner", "Hetzler")))
    }

    @Test fun `codex mishearing is corrected while the word code is preserved in the same sentence`() {
        // "codix" -> "Codex" (distance 1, not an English word) while "code" (a common word one
        // deletion from "Codex") survives in the same sentence.
        assertEquals(
            "Codex wrote the code for me.",
            VocabularyPostCorrector.correct("codix wrote the code for me.", listOf("Codex")),
        )
    }

    // --- punctuated and email terms ------------------------------------------------------------

    @Test fun `a dotted term is corrected from a near-miss`() {
        // "fast.ay" -> "fast.ai": the dotted term tokenizes as one core, distance 1.
        assertEquals(
            "The fast.ai course is great.",
            VocabularyPostCorrector.correct("The fast.ay course is great.", listOf("fast.ai")),
        )
    }

    @Test fun `a dotted term is recased on an exact case-insensitive match`() {
        assertEquals(
            "Answer.AI shipped a model.",
            VocabularyPostCorrector.correct("answer.ai shipped a model.", listOf("Answer.AI")),
        )
    }

    @Test fun `an email term only matches exactly -- near misses are left alone`() {
        // '@' makes the term exact-only: guessing at email corrections is high-consequence.
        val text = "Mail trevor@nashkellermedio.com about it."
        assertEquals(text, VocabularyPostCorrector.correct(text, listOf("trevor@nashkellermedia.com")))
    }

    @Test fun `an email term is recased on an exact case-insensitive match`() {
        assertEquals(
            "Mail trevor@nashkellermedia.com today.",
            VocabularyPostCorrector.correct("Mail Trevor@NashKellerMedia.com today.", listOf("trevor@nashkellermedia.com")),
        )
    }

    @Test fun `a term containing digits only matches exactly`() {
        val text = "The lfm3 model is loaded."
        assertEquals(text, VocabularyPostCorrector.correct(text, listOf("LFM2")))
        assertEquals(
            "The LFM2 model is loaded.",
            VocabularyPostCorrector.correct("The lfm2 model is loaded.", listOf("LFM2")),
        )
    }

    @Test fun `a hyphenated name is corrected from a near-miss`() {
        assertEquals(
            "Send it to Nash-Keller today.",
            VocabularyPostCorrector.correct("Send it to nash-kellar today.", listOf("Nash-Keller")),
        )
    }

    // --- structure preservation ----------------------------------------------------------------

    @Test fun `multiple corrections in one text all apply and surrounding text is untouched`() {
        assertEquals(
            "Hetzner hosts it; nbdev builds it, and FastHTML renders it.",
            VocabularyPostCorrector.correct(
                "hetzler hosts it; nbdve builds it, and fasthamel renders it.",
                listOf("Hetzner", "nbdev", "FastHTML"),
            ),
        )
    }

    @Test fun `whitespace and newlines around corrections are preserved`() {
        assertEquals(
            "Line one Hetzner.\nLine two  nbdev.",
            VocabularyPostCorrector.correct("Line one hetzler.\nLine two  nbdve.", listOf("Hetzner", "nbdev")),
        )
    }

    @Test fun `blank and whitespace-only terms are ignored`() {
        val text = "Nothing should change here."
        assertEquals(text, VocabularyPostCorrector.correct(text, listOf("", "   ")))
    }

    // --- edit budget pinning -------------------------------------------------------------------

    @Test fun `edit budget scales with term length`() {
        assertEquals(0, VocabularyPostCorrector.editBudgetFor(2))
        assertEquals(0, VocabularyPostCorrector.editBudgetFor(3))
        assertEquals(1, VocabularyPostCorrector.editBudgetFor(4))
        assertEquals(1, VocabularyPostCorrector.editBudgetFor(6))
        assertEquals(2, VocabularyPostCorrector.editBudgetFor(7))
        assertEquals(2, VocabularyPostCorrector.editBudgetFor(20))
    }

    @Test fun `a candidate past the edit budget is not corrected`() {
        // "hetlanders" is distance 4 from "hetzner" -- way past budget 2.
        val text = "The hetlanders arrived early."
        assertEquals(text, VocabularyPostCorrector.correct(text, listOf("Hetzner")))
    }
}
