package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini Live's `interimInputTranscription` is a cumulative hypothesis (each message is the whole
 * utterance so far), but it intermittently drops the space at a seam where an earlier part of the
 * hypothesis was revised -- measured against the real endpoint (gemini-3.5-transcribe-live) with
 * synthesized speech:
 *   "...send a report to Dyson and Adalyn and"      -> "...send areport to Dyson ... and then a"
 *   "...the meeting got moved to 3:30, so if"       -> "...the meeting gotmoved to 3:30, so if you"
 *   "...update the database schema. Run the"        -> "...update the database schema.Run the migration"
 *   "...the results, and then roll it out to"       -> "...the results, and thenroll it out to"
 * The seam usually heals on a later message and the final `inputTranscription` is always correctly
 * spaced, so only the live preview showed the run-together words. These tests pin the display-only
 * repair applied to interims (the final text path is never touched).
 */
class CloudInterimTextTest {

    // ---- punctuation seams (no history needed) ----

    @Test fun `sentence end glued to the next sentence gets its space`() {
        assertEquals("We need to update the database schema. Run the migration", repairInterimSpacing("We need to update the database schema.Run the migration"))
        assertEquals("Done! Next", repairInterimSpacing("Done!Next"))
        assertEquals("Really? Yes", repairInterimSpacing("Really?Yes"))
    }

    @Test fun `comma and semicolon glued to the next word get a space`() {
        assertEquals("so if you can make it, that would be great", repairInterimSpacing("so if you can make it,that would be great"))
        assertEquals("one; two", repairInterimSpacing("one;two"))
    }

    @Test fun `things that legitimately have no space after punctuation are left alone`() {
        for (s in listOf(
            "We leave at 7:30 a.m. on Friday",
            "It costs 1,000 dollars or 3.5 euros",
            "Visit www.example.com or https://example.com/a.B today",
            "Mail me at john.Smith@example.com please",
            "The U.S.A. is big",
            "Use e.g. a list, i.e. this",
            "Open Node.js and run it",
            "He said 3:45 pm, then left",
        )) {
            assertEquals(s, repairInterimSpacing(s))
        }
    }

    @Test fun `non-latin text without spaces is untouched`() {
        for (s in listOf("你好世界,今天天气很好", "こんにちは。今日は", "สวัสดีครับ")) assertEquals(s, repairInterimSpacing(s))
    }

    @Test fun `blank and already clean text pass through`() {
        assertEquals("", repairInterimSpacing(""))
        assertEquals("Hello, this is a test of the live transcription. I want", repairInterimSpacing("Hello, this is a test of the live transcription. I want"))
    }

    // ---- lowercase run-together seams (healed from the previous hypothesis) ----

    @Test fun `a seam that lost its space is restored from the previous message`() {
        val n = CloudInterimNormalizer()
        assertEquals("Okay, so basically what I want to do is send a report to Dyson and Adalyn and", n.normalize("Okay, so basically what I want to do is send a report to Dyson and Adalyn and"))
        assertEquals(
            "Okay, so basically what I want to do is send a report to Dyson and Adalyn and then a",
            n.normalize("Okay, so basically what I want to do is send areport to Dyson and Adalyn and then a"),
        )
    }

    @Test fun `the repair survives later messages that keep the glued seam`() {
        val n = CloudInterimNormalizer()
        n.normalize("I just wanted to let you know that the meeting got moved to 3:30, so if")
        assertEquals(
            "I just wanted to let you know that the meeting got moved to 3:30, so if you can make it that would",
            n.normalize("I just wanted to let you know that the meeting gotmoved to 3:30, so if you can make it that would"),
        )
        assertEquals(
            "I just wanted to let you know that the meeting got moved to 3:30, so if you can make it, that would be great.",
            n.normalize("I just wanted to let you know that the meeting gotmoved to 3:30, so if you can make it, that would be great."),
        )
    }

    @Test fun `a revision that changes the tail is shown as the model sent it`() {
        val n = CloudInterimNormalizer()
        n.normalize("Hello")
        assertEquals("Hello,", n.normalize("Hello,"))
        assertEquals("Hello, this is a", n.normalize("Hello, this is a"))
        // the model rewrote everything: nothing in common with the previous message
        assertEquals("Goodbye for now", n.normalize("Goodbye for now"))
    }

    @Test fun `a space the model later adds is kept`() {
        val n = CloudInterimNormalizer()
        assertEquals("We need to update the database", n.normalize("We need to update the database"))
        assertEquals("We need to update the database schema. Run the", n.normalize("We need to update the database schema. Run the"))
    }

    // ---- replay of a real captured interim sequence ----

    @Test fun `replaying a real glued sequence never shows a run-together word`() {
        val captured = listOf(
            "Okay.",
            "Okay, so",
            "Okay, so basically when I",
            "Okay, so basically what I want to do",
            "Okay, so basically what I want to do is send a",
            "Okay, so basically what I want to do is send a report to",
            "Okay, so basically what I want to do is send a report to Dyson",
            "Okay, so basically what I want to do is send a report to Dyson and Adalyn and",
            "Okay, so basically what I want to do is send areport to Dyson and Adalyn and then a",
            "Okay, so basically what I want to do is send areport to Dyson and Adalyn and then a follow-up on",
            "Okay, so basically what I want to do is send areport to Dyson and Adalyn and then follow up on Monday",
            "Okay, so basically what I want to do is send areport to Dyson and Adalyn and then follow up on Monday with the numbers from Q3 which were up 12% compared to",
        )
        val n = CloudInterimNormalizer()
        val shown = captured.map(n::normalize)
        shown.forEach { assertFalse("run-together seam left in: $it", it.contains("areport")) }
        assertEquals(captured.first(), shown.first())
        assertEquals("Okay, so basically what I want to do is send a report to Dyson and Adalyn and then follow up on Monday with the numbers from Q3 which were up 12% compared to", shown.last())
    }

    @Test fun `replaying a captured sequence with seams that heal later`() {
        val captured = listOf(
            "We need to update the database schema. Run the migration on",
            "We need to update the database schema.Run the migration on staging.",
            "We need to update the database schema.Run the migration on staging.Verify the result.",
            "We need to update the database schema. Run the migration on staging. Verify the results.",
            "We need to update the database schema.Run the migration on staging.Verify the results, and then roll it out to production.",
            "We need to update the database schema.Run the migration on staging.Verify the results, and thenroll it out to production on",
        )
        val n = CloudInterimNormalizer()
        val shown = captured.map(n::normalize)
        assertEquals("We need to update the database schema. Run the migration on staging.", shown[1])
        assertEquals("We need to update the database schema. Run the migration on staging. Verify the result.", shown[2])
        assertEquals("We need to update the database schema. Run the migration on staging. Verify the results.", shown[3])
        assertEquals("We need to update the database schema. Run the migration on staging. Verify the results, and then roll it out to production.", shown[4])
        assertEquals("We need to update the database schema. Run the migration on staging. Verify the results, and then roll it out to production on", shown[5])
    }

    @Test fun `each attempt has its own history`() {
        val first = CloudInterimNormalizer()
        first.normalize("the meeting got moved to 3:30, so if")
        val second = CloudInterimNormalizer()
        assertEquals("the meeting gotmoved", second.normalize("the meeting gotmoved"))
    }

    @Test fun `normalizing never changes the non-whitespace characters`() {
        val n = CloudInterimNormalizer()
        for (s in listOf("send areport to Dyson", "gotmoved to 3:30", "schema.Run it", "a  b\tc", "  padded  ")) {
            assertEquals(s.filterNot(Char::isWhitespace), n.normalize(s).filterNot(Char::isWhitespace))
        }
        assertTrue(n.normalize("x").isNotEmpty())
    }
}
