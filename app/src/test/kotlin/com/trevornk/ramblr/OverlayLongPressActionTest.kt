package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayLongPressActionTest {

    @Test fun `transcribing always cancels, regardless of pending injection`() {
        assertEquals(
            OverlayLongPressAction.CANCEL_TRANSCRIPTION,
            overlayLongPressActionFor(RecordingStateMachine.State.TRANSCRIBING, hasPendingInjection = false)
        )
        assertEquals(
            OverlayLongPressAction.CANCEL_TRANSCRIPTION,
            overlayLongPressActionFor(RecordingStateMachine.State.TRANSCRIBING, hasPendingInjection = true)
        )
    }

    @Test fun `idle with a pending injection undoes it`() {
        assertEquals(
            OverlayLongPressAction.UNDO_INJECTION,
            overlayLongPressActionFor(RecordingStateMachine.State.IDLE, hasPendingInjection = true)
        )
    }

    @Test fun `idle with nothing pending opens the style menu`() {
        assertEquals(
            OverlayLongPressAction.SHOW_STYLE_MENU,
            overlayLongPressActionFor(RecordingStateMachine.State.IDLE, hasPendingInjection = false)
        )
    }

    @Test fun `recording never gets a long-press action`() {
        assertEquals(
            OverlayLongPressAction.NONE,
            overlayLongPressActionFor(RecordingStateMachine.State.RECORDING, hasPendingInjection = false)
        )
        assertEquals(
            OverlayLongPressAction.NONE,
            overlayLongPressActionFor(RecordingStateMachine.State.RECORDING, hasPendingInjection = true)
        )
    }

    // #361: the full state x pending-injection x setting matrix. With the setting on (the default)
    // nothing changes from the pre-#361 map; with it off both destructive actions disappear.
    @Test fun `full state by setting matrix`() {
        data class Case(
            val state: RecordingStateMachine.State,
            val pending: Boolean,
            val enabled: Boolean,
            val expected: OverlayLongPressAction,
        )
        val transcribing = RecordingStateMachine.State.TRANSCRIBING
        val idle = RecordingStateMachine.State.IDLE
        val recording = RecordingStateMachine.State.RECORDING
        val cases = listOf(
            Case(transcribing, pending = false, enabled = true, expected = OverlayLongPressAction.CANCEL_TRANSCRIPTION),
            Case(transcribing, pending = true, enabled = true, expected = OverlayLongPressAction.CANCEL_TRANSCRIPTION),
            Case(transcribing, pending = false, enabled = false, expected = OverlayLongPressAction.NONE),
            Case(transcribing, pending = true, enabled = false, expected = OverlayLongPressAction.NONE),
            Case(idle, pending = true, enabled = true, expected = OverlayLongPressAction.UNDO_INJECTION),
            Case(idle, pending = false, enabled = true, expected = OverlayLongPressAction.SHOW_STYLE_MENU),
            Case(idle, pending = true, enabled = false, expected = OverlayLongPressAction.SHOW_STYLE_MENU),
            Case(idle, pending = false, enabled = false, expected = OverlayLongPressAction.SHOW_STYLE_MENU),
            Case(recording, pending = false, enabled = true, expected = OverlayLongPressAction.NONE),
            Case(recording, pending = true, enabled = true, expected = OverlayLongPressAction.NONE),
            Case(recording, pending = false, enabled = false, expected = OverlayLongPressAction.NONE),
            Case(recording, pending = true, enabled = false, expected = OverlayLongPressAction.NONE),
        )
        for (c in cases) {
            assertEquals(
                "state=${c.state} pending=${c.pending} enabled=${c.enabled}",
                c.expected,
                overlayLongPressActionFor(c.state, hasPendingInjection = c.pending, cancelAndUndoEnabled = c.enabled)
            )
        }
    }

    @Test fun `setting defaults to enabled when omitted`() {
        assertEquals(
            OverlayLongPressAction.CANCEL_TRANSCRIPTION,
            overlayLongPressActionFor(RecordingStateMachine.State.TRANSCRIBING, hasPendingInjection = false)
        )
    }
}
