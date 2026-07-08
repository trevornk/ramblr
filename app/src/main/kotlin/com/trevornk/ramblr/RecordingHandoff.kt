package com.trevornk.ramblr

/**
 * What to do with a finished recording that reached [WhisperAccessibilityService]'s
 * onRecordingFinished without a valid transcription token on the reader thread's first look
 * (#66/#90). Decided on the main thread from authoritative state, never from the reader
 * thread's possibly-stale snapshot.
 */
enum class LateRecordingResolution {
    /** A stop tap's token exists after all (the reader thread just raced ahead of
     *  stopAndTranscribe's assignment) — transcribe normally. */
    CONTINUE_TRANSCRIPTION,
    /** No transcription is expected (cancelled, watchdog-reset, or superseded by a new
     *  recording) — discard the audio; the UI is already in a consistent state. */
    DISCARD,
    /** The reader thread self-claimed RECORDING -> TRANSCRIBING (mic error mid-recording, #90)
     *  but no transcription will ever run and no watchdog was armed — discard the audio AND
     *  walk the state machine back to IDLE, or the overlay is left stuck in TRANSCRIBING with
     *  taps as no-ops forever. */
    DISCARD_AND_RESET,
}

/**
 * Pure resolution logic, evaluated on the main thread so [activeToken]/[state] are consistent
 * with each other (#66): the stop tap mints its token on the main thread *after* the
 * RECORDING -> TRANSCRIBING transition the reader thread reacts to, so by the time a posted
 * runnable reads them, the token either exists (continue) or genuinely never will.
 * [isTokenCurrent] must come from [TranscriptionGuard.isCurrent]; a zero [activeToken] is
 * never continuable regardless (the guard's counter also starts at 0, so 0 could otherwise
 * read as "current" on a fresh service).
 */
fun resolveLateRecording(
    activeToken: Int,
    isTokenCurrent: Boolean,
    state: RecordingStateMachine.State,
): LateRecordingResolution = when {
    activeToken != 0 && isTokenCurrent -> LateRecordingResolution.CONTINUE_TRANSCRIPTION
    activeToken == 0 && state == RecordingStateMachine.State.TRANSCRIBING -> LateRecordingResolution.DISCARD_AND_RESET
    else -> LateRecordingResolution.DISCARD
}

/**
 * Pure post-loop handoff decision for [RecordingEngine]'s reader thread (#88/H2). The service
 * creates a fresh [RecordingEngine] per recording but they share a process-wide generation
 * counter, so a reader thread stalled across stop -> new-tap is "superseded": a newer recording
 * has bumped the counter past this reader's own generation.
 *
 * A superseded reader must NOT claim the shared state machine's RECORDING -> TRANSCRIBING
 * transition ([shouldClaimTranscribing] false) -- doing so would steal the *new* session's
 * recording out from under it, silently killing a dictation the user is still speaking -- and its
 * own audio is always [discarded]. A still-current reader claims the transition (a no-op if the
 * stop tap already made it) and is discarded only if the machine isn't in TRANSCRIBING afterwards.
 */
object RecorderHandoff {
    /** Whether the reader may claim RECORDING -> TRANSCRIBING. Only a still-current reader may. */
    fun shouldClaimTranscribing(superseded: Boolean): Boolean = !superseded

    /** Whether this reader's audio must be discarded (never handed to transcription). */
    fun discarded(superseded: Boolean, stateAfterClaim: RecordingStateMachine.State): Boolean =
        superseded || stateAfterClaim != RecordingStateMachine.State.TRANSCRIBING
}
