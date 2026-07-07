package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `stationary long-press still opens the style menu`() {
        assertTrue(shouldFireLongPress(OverlayLongPressAction.SHOW_STYLE_MENU, movedPastThreshold = false))
    }

    @Test fun `a long-press that turned into a drag does not open the style menu`() {
        assertFalse(shouldFireLongPress(OverlayLongPressAction.SHOW_STYLE_MENU, movedPastThreshold = true))
    }

    @Test fun `movement never gates cancel-transcription`() {
        assertTrue(shouldFireLongPress(OverlayLongPressAction.CANCEL_TRANSCRIPTION, movedPastThreshold = true))
    }

    @Test fun `movement never gates undo-injection`() {
        assertTrue(shouldFireLongPress(OverlayLongPressAction.UNDO_INJECTION, movedPastThreshold = true))
    }

    @Test fun `movement never gates none`() {
        assertTrue(shouldFireLongPress(OverlayLongPressAction.NONE, movedPastThreshold = true))
    }
}
