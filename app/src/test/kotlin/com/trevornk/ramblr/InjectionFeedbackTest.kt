package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

class InjectionFeedbackTest {

    @Test fun `default clipboard message becomes Inserted for a direct injection`() {
        val result = injectionFeedbackFor(InjectMethod.DIRECT, DEFAULT_INJECT_FEEDBACK)

        assertEquals("Inserted", result)
    }

    @Test fun `default clipboard message is unchanged for a clipboard-read injection`() {
        val result = injectionFeedbackFor(InjectMethod.FROM_CLIPBOARD, DEFAULT_INJECT_FEEDBACK)

        assertEquals(DEFAULT_INJECT_FEEDBACK, result)
    }

    @Test fun `default clipboard message is unchanged when nothing was injected`() {
        val result = injectionFeedbackFor(InjectMethod.NONE, DEFAULT_INJECT_FEEDBACK)

        assertEquals(DEFAULT_INJECT_FEEDBACK, result)
    }

    @Test fun `explicit non-default feedback survives untouched even for direct injection`() {
        val result = injectionFeedbackFor(InjectMethod.DIRECT, "Cleanup failed (timeout) — raw copied to clipboard")

        assertEquals("Cleanup failed (timeout) — raw copied to clipboard", result)
    }

    @Test fun `null feedback stays null regardless of method`() {
        assertEquals(null, injectionFeedbackFor(InjectMethod.DIRECT, null))
    }
}
