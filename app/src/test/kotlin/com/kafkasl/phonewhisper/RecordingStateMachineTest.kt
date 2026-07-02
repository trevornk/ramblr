package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class RecordingStateMachineTest {

    @Test fun `starts idle`() {
        assertEquals(RecordingStateMachine.State.IDLE, RecordingStateMachine().current())
    }

    @Test fun `tryStartRecording moves idle to recording`() {
        val sm = RecordingStateMachine()
        assertTrue(sm.tryStartRecording())
        assertEquals(RecordingStateMachine.State.RECORDING, sm.current())
        assertTrue(sm.isRecording())
    }

    @Test fun `tryStartRecording fails when not idle`() {
        val sm = RecordingStateMachine()
        sm.tryStartRecording()
        assertFalse(sm.tryStartRecording())
        assertEquals(RecordingStateMachine.State.RECORDING, sm.current())
    }

    @Test fun `tryStartTranscribing moves recording to transcribing`() {
        val sm = RecordingStateMachine()
        sm.tryStartRecording()
        assertTrue(sm.tryStartTranscribing())
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, sm.current())
        assertFalse(sm.isRecording())
    }

    @Test fun `tryStartTranscribing fails from idle`() {
        val sm = RecordingStateMachine()
        assertFalse(sm.tryStartTranscribing())
        assertEquals(RecordingStateMachine.State.IDLE, sm.current())
    }

    @Test fun `tryStartTranscribing fails when already transcribing`() {
        val sm = RecordingStateMachine()
        sm.tryStartRecording()
        sm.tryStartTranscribing()
        assertFalse(sm.tryStartTranscribing())
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, sm.current())
    }

    @Test fun `reset forces idle from any state`() {
        val sm = RecordingStateMachine()
        sm.tryStartRecording()
        sm.reset()
        assertEquals(RecordingStateMachine.State.IDLE, sm.current())

        sm.tryStartRecording()
        sm.tryStartTranscribing()
        sm.reset()
        assertEquals(RecordingStateMachine.State.IDLE, sm.current())
    }

    @Test fun `only one thread wins a concurrent tryStartRecording race`() {
        val sm = RecordingStateMachine()
        val winners = (1..50).toList().parallelStream()
            .filter { sm.tryStartRecording() }
            .count()
        assertEquals(1L, winners)
    }
}
