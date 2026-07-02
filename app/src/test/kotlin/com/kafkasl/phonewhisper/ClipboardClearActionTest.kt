package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardClearActionTest {

    @Test fun `direct injection clears the clipboard immediately`() {
        val action = clipboardClearActionFor(InjectMethod.DIRECT, delayMs = 30_000L)

        assertEquals(ClipboardClearAction.Immediate, action)
    }

    @Test fun `clipboard-read injection schedules a delayed clear`() {
        val action = clipboardClearActionFor(InjectMethod.FROM_CLIPBOARD, delayMs = 30_000L)

        assertEquals(ClipboardClearAction.Delayed(30_000L), action)
    }

    @Test fun `failed injection leaves the clipboard fallback untouched`() {
        val action = clipboardClearActionFor(InjectMethod.NONE, delayMs = 30_000L)

        assertEquals(ClipboardClearAction.None, action)
    }
}
