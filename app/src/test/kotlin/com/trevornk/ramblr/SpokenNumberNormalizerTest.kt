package com.trevornk.ramblr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenNumberNormalizerTest {
    @Test fun `cardinal compounds become digits without changing surrounding prose`() {
        assertEquals(
            "send 450 dollars to the joint account",
            SpokenNumberNormalizer.normalize("send four hundred and fifty dollars to the joint account").text,
        )
        assertEquals(
            "the total is 12500 tomorrow",
            SpokenNumberNormalizer.normalize("the total is twelve thousand five hundred tomorrow").text,
        )
    }

    @Test fun `repeated spoken digits retain every digit in one run`() {
        assertEquals(
            "call me at 5551234567",
            SpokenNumberNormalizer.normalize("call me at five five five one two three four five six seven").text,
        )
        assertEquals("room 042", SpokenNumberNormalizer.normalize("room oh four two").text)
    }

    @Test fun `decimals and multipliers render their exact expanded value`() {
        assertEquals(
            "the round was 1200000 dollars in total",
            SpokenNumberNormalizer.normalize("the round was one point two million dollars in total").text,
        )
        assertEquals("pi is 3.14", SpokenNumberNormalizer.normalize("pi is three point one four").text)
    }

    @Test fun `currency and percentages keep their units with normalized values`() {
        assertEquals("send 450 dollars", SpokenNumberNormalizer.normalize("send four hundred fifty dollars").text)
        assertEquals("charge 1 dollar", SpokenNumberNormalizer.normalize("charge one dollar").text)
        assertEquals("a 1 percent decrease", SpokenNumberNormalizer.normalize("a one percent decrease").text)
        assertEquals("a 23 percent increase", SpokenNumberNormalizer.normalize("a twenty three percent increase").text)
    }

    @Test fun `times dates ordinals and literal digits cover the measured categories`() {
        assertEquals("meet at 4:30 pm", SpokenNumberNormalizer.normalize("meet at four thirty pm").text)
        assertEquals("due August 18th", SpokenNumberNormalizer.normalize("due August eighteenth").text)
        assertEquals(
            "due August 18th 2026",
            SpokenNumberNormalizer.normalize("due August eighteenth twenty twenty six").text,
        )
        assertEquals("the 21st attempt", SpokenNumberNormalizer.normalize("the twenty first attempt").text)
        val literal = "meet at 3:30 on 08/18/2026 with $12,500"
        assertSame(literal, SpokenNumberNormalizer.normalize(literal).text)
    }

    @Test fun `eval samples normalize their measured numeric phrases`() {
        val dir = File("src/test/resources/eval_samples").takeIf { it.isDirectory }
            ?: File("app/src/test/resources/eval_samples")
        fun sample(name: String) = File(dir, name).readText()
        assertEquals(
            "pick up the dry cleaning on the way home it should be ready by 5\n",
            SpokenNumberNormalizer.normalize(sample("quick_note_02.txt")).text,
        )
        assertEquals(
            "the meeting is at 3 o'clock no sorry i'm looking at the wrong calendar it's actually at 4:30 in the conference room on the 2nd floor not the one downstairs that we usually use\n",
            SpokenNumberNormalizer.normalize(sample("self_correction_02.txt")).text,
        )
        val pricing = SpokenNumberNormalizer.normalize(sample("self_correction_04.txt")).text
        assertTrue(pricing.contains("charge 20 dollars"))
        assertTrue(pricing.contains("say 25"))
        val model = SpokenNumberNormalizer.normalize(sample("rambling_brainstorm_03.txt")).text
        assertTrue(model.contains("the 600 meg parakeet model"))
        assertTrue(model.contains("take 10 seconds or 10 minutes"))
    }

    @Test fun `ambiguous article and quantifiers are left alone`() {
        val text = "one of the things is half a dozen eggs and a quarter cup"
        val result = SpokenNumberNormalizer.normalize(text)
        assertSame(text, result.text)
        assertEquals(emptyList<String>(), result.semanticValues)
    }

    @Test fun `separate numbers are never merged across punctuation or conjunctions`() {
        assertEquals("one, 2, 3 options", SpokenNumberNormalizer.normalize("one, two, three options").text)
        assertEquals("one. 2 ideas", SpokenNumberNormalizer.normalize("one. two ideas").text)
        assertEquals("one and 2", SpokenNumberNormalizer.normalize("one and two").text)
        assertEquals("3 and 4", SpokenNumberNormalizer.normalize("three and four").text)
        // The connector still belongs to a genuine scale compound.
        assertEquals("450 dollars", SpokenNumberNormalizer.normalize("four hundred and fifty dollars").text)
    }

    @Test fun `three word spoken times keep their real clock value`() {
        assertEquals("meet at 4:35 pm", SpokenNumberNormalizer.normalize("meet at four thirty five pm").text)
        assertEquals("meet at 12:05", SpokenNumberNormalizer.normalize("meet at twelve oh five").text)
        assertEquals("meet at 9:15 am", SpokenNumberNormalizer.normalize("meet at nine fifteen am").text)
    }

    @Test fun `spoken years need date context and stay untouched when ambiguous`() {
        assertEquals("shipped in 2026", SpokenNumberNormalizer.normalize("shipped in twenty twenty six").text)
        assertEquals("back in 1984", SpokenNumberNormalizer.normalize("back in nineteen eighty four").text)
        val ambiguous = "twenty twenty dollar bills"
        assertSame(ambiguous, SpokenNumberNormalizer.normalize(ambiguous).text)
    }

    @Test fun `scale ordinals multiply instead of adding`() {
        assertEquals("the 100th customer", SpokenNumberNormalizer.normalize("the one hundredth customer").text)
        assertEquals("the 200th customer", SpokenNumberNormalizer.normalize("the two hundredth customer").text)
        assertEquals("the 1000th customer", SpokenNumberNormalizer.normalize("the one thousandth customer").text)
    }
}
