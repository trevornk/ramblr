package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingHandoffTest {

    @Test fun `a current token continues transcription — the reader thread merely raced ahead of the stop tap`() {
        assertEquals(
            LateRecordingResolution.CONTINUE_TRANSCRIPTION,
            resolveLateRecording(activeToken = 7, isTokenCurrent = true, state = RecordingStateMachine.State.TRANSCRIBING)
        )
    }

    @Test fun `a zero token never continues even if the guard would call 0 current on a fresh service`() {
        // TranscriptionGuard's counter starts at 0, so isCurrent(0) is true before any start();
        // continuing with token 0 would let every guard check in the pipeline pass spuriously.
        assertEquals(
            LateRecordingResolution.DISCARD_AND_RESET,
            resolveLateRecording(activeToken = 0, isTokenCurrent = true, state = RecordingStateMachine.State.TRANSCRIBING)
        )
    }

    @Test fun `mic error self-claim (no token, state stuck TRANSCRIBING) discards and resets to idle`() {
        assertEquals(
            LateRecordingResolution.DISCARD_AND_RESET,
            resolveLateRecording(activeToken = 0, isTokenCurrent = false, state = RecordingStateMachine.State.TRANSCRIBING)
        )
    }

    @Test fun `after a cancel or watchdog reset (idle, no token) the audio is just discarded`() {
        assertEquals(
            LateRecordingResolution.DISCARD,
            resolveLateRecording(activeToken = 0, isTokenCurrent = false, state = RecordingStateMachine.State.IDLE)
        )
    }

    @Test fun `a stale token with a newer recording already underway discards without touching state`() {
        assertEquals(
            LateRecordingResolution.DISCARD,
            resolveLateRecording(activeToken = 0, isTokenCurrent = false, state = RecordingStateMachine.State.RECORDING)
        )
    }

    @Test fun `a superseded (non-current) token discards`() {
        assertEquals(
            LateRecordingResolution.DISCARD,
            resolveLateRecording(activeToken = 5, isTokenCurrent = false, state = RecordingStateMachine.State.IDLE)
        )
    }
}
