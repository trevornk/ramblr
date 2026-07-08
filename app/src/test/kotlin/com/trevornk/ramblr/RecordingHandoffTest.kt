package com.trevornk.ramblr

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

    // --- RecorderHandoff: the reader thread's post-loop decision (#88/H2) ---

    @Test fun `a still-current reader may claim RECORDING to TRANSCRIBING`() {
        assertEquals(true, RecorderHandoff.shouldClaimTranscribing(superseded = false))
    }

    @Test fun `a superseded reader must NOT claim the transition — it belongs to the new session`() {
        // This is the core H2 fix: an old reader stalled across a new tap must not CAS the NEW
        // session's RECORDING->TRANSCRIBING, which would silently kill the user's live dictation.
        assertEquals(false, RecorderHandoff.shouldClaimTranscribing(superseded = true))
    }

    @Test fun `a current reader that reached TRANSCRIBING hands its audio off`() {
        assertEquals(
            false,
            RecorderHandoff.discarded(superseded = false, stateAfterClaim = RecordingStateMachine.State.TRANSCRIBING)
        )
    }

    @Test fun `a current reader stuck outside TRANSCRIBING discards`() {
        // e.g. a cancel walked the machine back to IDLE before the claim landed.
        assertEquals(
            true,
            RecorderHandoff.discarded(superseded = false, stateAfterClaim = RecordingStateMachine.State.IDLE)
        )
    }

    @Test fun `a superseded reader always discards, even if the shared machine reads TRANSCRIBING`() {
        // The TRANSCRIBING it sees belongs to the NEW session; the old reader's own audio is stale
        // and must never be handed off just because the shared state happens to look right.
        assertEquals(
            true,
            RecorderHandoff.discarded(superseded = true, stateAfterClaim = RecordingStateMachine.State.TRANSCRIBING)
        )
    }

    @Test fun `a superseded reader discards regardless of state`() {
        assertEquals(true, RecorderHandoff.discarded(superseded = true, stateAfterClaim = RecordingStateMachine.State.RECORDING))
        assertEquals(true, RecorderHandoff.discarded(superseded = true, stateAfterClaim = RecordingStateMachine.State.IDLE))
    }
}
