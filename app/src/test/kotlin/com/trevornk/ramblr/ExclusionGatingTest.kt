package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionGatingTest {

    // --- shouldBlockNewRecording ---

    @Test fun `blocks a new recording only when idle and excluded`() {
        assertTrue(
            ExclusionGating.shouldBlockNewRecording(RecordingStateMachine.State.IDLE, excluded = true)
        )
    }

    @Test fun `does not block when not excluded`() {
        assertFalse(
            ExclusionGating.shouldBlockNewRecording(RecordingStateMachine.State.IDLE, excluded = false)
        )
    }

    @Test fun `never blocks stop of an already-running recording, excluded or not`() {
        assertFalse(
            ExclusionGating.shouldBlockNewRecording(RecordingStateMachine.State.RECORDING, excluded = true)
        )
        assertFalse(
            ExclusionGating.shouldBlockNewRecording(RecordingStateMachine.State.RECORDING, excluded = false)
        )
    }

    @Test fun `never blocks while transcribing, excluded or not`() {
        assertFalse(
            ExclusionGating.shouldBlockNewRecording(RecordingStateMachine.State.TRANSCRIBING, excluded = true)
        )
    }

    @Test fun `treats unknown state as not blockable`() {
        assertFalse(ExclusionGating.shouldBlockNewRecording(null, excluded = true))
    }

    // --- shouldSuppressInsertion ---

    @Test fun `suppresses insertion exactly when excluded`() {
        assertTrue(ExclusionGating.shouldSuppressInsertion(excluded = true))
        assertFalse(ExclusionGating.shouldSuppressInsertion(excluded = false))
    }

    // --- ringHiddenForExclusion ---

    @Test fun `hides ring when foreground package is positively known and excluded`() {
        assertTrue(ExclusionGating.ringHiddenForExclusion("com.chase.sig.android", setOf("com.chase.sig.android")))
    }

    @Test fun `keeps ring visible when foreground package is known but not excluded`() {
        assertFalse(ExclusionGating.ringHiddenForExclusion("com.whatsapp", setOf("com.chase.sig.android")))
    }

    @Test fun `never hides ring when foreground identity is unknown -- fails open, not closed`() {
        // This is the honest, deliberate choice: an unreadable foreground identity must not be
        // treated as "excluded," or the ring would occasionally vanish in ordinary apps for no
        // privacy benefit -- worse than the alternative of staying visible one tick longer.
        assertFalse(ExclusionGating.ringHiddenForExclusion(null, setOf("com.chase.sig.android")))
    }

    @Test fun `empty exclusion set never hides the ring`() {
        assertFalse(ExclusionGating.ringHiddenForExclusion("com.chase.sig.android", emptySet()))
    }
}
