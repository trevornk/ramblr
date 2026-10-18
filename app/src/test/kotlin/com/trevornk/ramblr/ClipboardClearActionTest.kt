package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardClearActionTest {

    @Test fun `direct injection that never staged the clipboard leaves it untouched`() {
        val action = clipboardClearActionFor(InjectMethod.DIRECT, clipboardWritten = false, delayMs = 30_000L)

        assertEquals(ClipboardClearAction.None, action)
    }

    @Test fun `direct injection after an earlier paste attempt staged the text restores immediately`() {
        val action = clipboardClearActionFor(InjectMethod.DIRECT, clipboardWritten = true, delayMs = 30_000L)

        assertEquals(ClipboardClearAction.Immediate, action)
    }

    @Test fun `clipboard-read injection schedules a delayed clear`() {
        val action = clipboardClearActionFor(InjectMethod.FROM_CLIPBOARD, clipboardWritten = true, delayMs = 30_000L)

        assertEquals(ClipboardClearAction.Delayed(30_000L), action)
    }

    @Test fun `failed injection leaves the clipboard fallback untouched`() {
        val action = clipboardClearActionFor(InjectMethod.NONE, clipboardWritten = true, delayMs = 30_000L)

        assertEquals(ClipboardClearAction.None, action)
    }
}
