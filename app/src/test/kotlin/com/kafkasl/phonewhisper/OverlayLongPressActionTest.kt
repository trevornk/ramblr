package com.kafkasl.phonewhisper

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
}
