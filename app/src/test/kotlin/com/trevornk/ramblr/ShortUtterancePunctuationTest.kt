package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortUtterancePunctuationTest {

    private fun strip(s: String) = ShortUtterancePunctuation.stripTrailingPeriod(s)

    @Test fun `one two and three word utterances lose the final period`() {
        assertEquals("Okay", strip("Okay."))
        assertEquals("Sounds good", strip("Sounds good."))
        assertEquals("On my way", strip("On my way."))
    }

    @Test fun `four or more words keep the period`() {
        assertEquals("I am on my way.", strip("I am on my way."))
        assertEquals("See you in five.", strip("See you in five."))
    }

    @Test fun `question and exclamation marks are kept`() {
        assertEquals("Really?", strip("Really?"))
        assertEquals("Sounds good!", strip("Sounds good!"))
        assertEquals("On my way?!", strip("On my way?!"))
    }

    @Test fun `ellipses are kept`() {
        assertEquals("Well...", strip("Well..."))
        assertEquals("Well..", strip("Well.."))
        assertEquals("Well…", strip("Well…"))
        assertEquals("Hmm. . .", strip("Hmm. . ."))
    }

    @Test fun `text without a trailing period is untouched`() {
        assertEquals("Okay", strip("Okay"))
        assertEquals("Okay,", strip("Okay,"))
        assertEquals("", strip(""))
        assertEquals("   ", strip("   "))
    }

    @Test fun `capitalization and interior text are preserved`() {
        assertEquals("iPhone Pro", strip("iPhone Pro."))
        assertEquals("OK Google", strip("OK Google."))
        assertEquals("Hi, John", strip("Hi, John."))
    }

    @Test fun `trailing whitespace is preserved and does not hide the period`() {
        assertEquals("Okay ", strip("Okay. "))
        assertEquals("Okay\n", strip("Okay.\n"))
    }

    @Test fun `leading whitespace is preserved`() {
        assertEquals(" Okay", strip(" Okay."))
    }

    @Test fun `titles and common abbreviations keep their period`() {
        for (w in listOf("Dr.", "Mr.", "Mrs.", "Ms.", "St.", "Jr.", "Sr.", "Prof.", "etc.", "vs.", "Inc.", "Jan.", "Sept.")) {
            assertEquals("ask $w", "ask $w", strip("ask $w"))
            assertEquals(w, w, strip(w))
        }
        assertEquals("Hello Dr.", strip("Hello Dr."))
        assertEquals("DR.", strip("DR."))
    }

    @Test fun `dotted abbreviations keep their period`() {
        assertEquals("From the U.S.", strip("From the U.S."))
        assertEquals("At 5 p.m.", strip("At 5 p.m."))
        assertEquals("Tomorrow at 9 a.m.", strip("Tomorrow at 9 a.m.")) // 4 words, kept regardless
        assertEquals("Nine a.m.", strip("Nine a.m."))
        assertEquals("Like e.g.", strip("Like e.g."))
        assertEquals("Go to example.com.", strip("Go to example.com."))
    }

    @Test fun `a single initial keeps its period`() {
        assertEquals("John J.", strip("John J."))
    }

    @Test fun `decimals and versions are not abbreviations`() {
        assertEquals("Version 2.0", strip("Version 2.0."))
        assertEquals("About 3.5", strip("About 3.5."))
        assertEquals("Call 555", strip("Call 555."))
    }

    @Test fun `a multi-sentence utterance is left alone`() {
        assertEquals("Yes. No.", strip("Yes. No."))
        assertEquals("Okay. Sure.", strip("Okay. Sure."))
        assertEquals("Hi! Okay.", strip("Hi! Okay."))
        assertEquals("What? Okay.", strip("What? Okay."))
    }

    @Test fun `an abbreviation earlier in the text does not block stripping`() {
        assertEquals("Dr. Smith", strip("Dr. Smith."))
        assertEquals("Mr. Jones here", strip("Mr. Jones here."))
    }

    @Test fun `punctuation-only text is never emptied`() {
        assertEquals(".", strip("."))
        assertEquals(" . ", strip(" . "))
    }

    @Test fun `quoted or bracketed endings still strip`() {
        assertEquals("Say \"hi\"", strip("Say \"hi\"."))
        assertEquals("OK (maybe)", strip("OK (maybe)."))
    }

    @Test fun `abbreviation detector`() {
        assertTrue(ShortUtterancePunctuation.isAbbreviation("Dr."))
        assertTrue(ShortUtterancePunctuation.isAbbreviation("U.S."))
        assertTrue(ShortUtterancePunctuation.isAbbreviation("p.m."))
        assertFalse(ShortUtterancePunctuation.isAbbreviation("okay."))
        assertFalse(ShortUtterancePunctuation.isAbbreviation("2.0."))
        assertFalse(ShortUtterancePunctuation.isAbbreviation("."))
    }

    @Test fun `idempotent`() {
        for (s in listOf("Okay.", "Sounds good.", "Dr.", "Hello world and more.", "Yes. No.")) {
            assertEquals(strip(s), strip(strip(s)))
        }
    }
}
