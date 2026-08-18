package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericPreservationVerifierTest {
    private fun assertValid(input: String, output: String) {
        val normalized = SpokenNumberNormalizer.normalize(input)
        assertTrue(
            "expected numeric preservation for ${normalized.semanticValues}",
            NumericPreservationVerifier.verify(normalized, output) is NumericPreservation.Valid,
        )
    }

    private fun assertRejected(input: String, output: String) {
        val normalized = SpokenNumberNormalizer.normalize(input)
        val verdict = NumericPreservationVerifier.verify(normalized, output)
        assertTrue("expected rejection for ${normalized.semanticValues}", verdict is NumericPreservation.Rejected)
        assertTrue((verdict as NumericPreservation.Rejected).detail.isNotBlank())
    }

    @Test fun `phone punctuation changes pass when the digit sequence is identical`() {
        assertValid("call 2125553476", "Call 212-555-3476.")
        assertValid("call 2125553476", "Call 212.555.3476.")
        assertValid("call two one two five five five three four seven six", "Call (212) 555-3476.")
        assertValid("call 5553476", "Call 555-3476.")
        assertValid("call 12125553476", "Call +1 (212) 555-3476.")
    }

    @Test fun `changed dropped duplicated and reordered values are rejected`() {
        assertRejected("send 450 dollars to account 9372", "Send 150 dollars to account 9372.")
        assertRejected("send 450 dollars to account 9372", "Send 450 dollars.")
        assertRejected("send 450 dollars", "Send 450, actually 450 dollars.")
        assertRejected("codes are 12 then 34", "Codes are 34 then 12.")
    }

    @Test fun `arithmetic evaluation and collapsed arithmetic are rejected`() {
        assertRejected("divide it by 3 and you get 33", "Divide it by 3 and you get 11.")
        assertRejected("100 minus 25 is 75", "75")
    }

    @Test fun `expanded decimal multiplier must survive as its exact value`() {
        val normalized = SpokenNumberNormalizer.normalize("one point two million dollars")
        assertEquals("1200000 dollars", normalized.text)
        assertEquals(listOf("1200000"), normalized.semanticValues)
        assertTrue(NumericPreservationVerifier.verify(normalized, "$1,200,000") is NumericPreservation.Valid)
        assertTrue(NumericPreservationVerifier.verify(normalized, "$1,200") is NumericPreservation.Rejected)
    }

    @Test fun `long decimals keep decimal meaning despite phone-shaped digit counts`() {
        val normalized = SpokenNumberNormalizer.normalize("pi is 3.1415926")
        assertEquals(listOf("3.1415926"), normalized.semanticValues)
        assertTrue(NumericPreservationVerifier.verify(normalized, "Pi is 3.1415926.") is NumericPreservation.Valid)
        assertTrue(NumericPreservationVerifier.verify(normalized, "Pi is 3.1415927.") is NumericPreservation.Rejected)
    }

    @Test fun `number free text has no numeric preservation burden`() {
        val normalized = SpokenNumberNormalizer.normalize("hello there")
        assertTrue(NumericPreservationVerifier.verify(normalized, "Hello there.") is NumericPreservation.Valid)
    }
}
