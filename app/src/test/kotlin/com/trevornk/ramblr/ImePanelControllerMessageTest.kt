package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

/** A runtime error used to be painted over by the idle repaint that follows it at once. */
class ImePanelControllerMessageTest {

    private class Ui {
        val states = mutableListOf<ImeUiState>()
        val messages = mutableListOf<String>()
    }

    private fun controller(ui: Ui) = ImePanelController(
        runtime = object : ImeRuntimeControl {
            override fun onTap() {}
            override fun invalidate() {}
            override fun teardownAsync() {}
        },
        renderState = { ui.states.add(it) },
        editorSnapshot = { Triple(1L, ImeEditorIdentity("com.example.app", 0, 0), Any()) },
        commitText = { _, _ -> true },
        userMessage = { ui.messages.add(it) },
    ).also { it.onEditorChanged(1L, ImeEditorIdentity("com.example.app", 0, 0), Any()) }

    @Test fun `an error stays on screen after the idle repaint`() {
        val ui = Ui()
        val listener = controller(ui).listener

        listener.onUserMessage("Error: model overloaded")
        listener.onIdleUi()

        assertEquals(ImeUiState.IDLE, ui.states.last())
        assertEquals("Error: model overloaded", ui.messages.last())
    }

    @Test fun `the next recording clears it`() {
        val ui = Ui()
        val listener = controller(ui).listener
        listener.onUserMessage("No speech detected")
        listener.onIdleUi()
        val shown = ui.messages.size

        listener.onRecordingStartRequested()
        listener.onIdleUi()

        assertEquals(shown, ui.messages.size)
    }

    @Test fun `an idle repaint without a message shows nothing extra`() {
        val ui = Ui()
        controller(ui).listener.onIdleUi()

        assertEquals(listOf(ImeUiState.IDLE), ui.states)
        assertEquals(emptyList<String>(), ui.messages)
    }

    @Test fun `a cleanup failure notice survives the idle repaint too`() {
        val ui = Ui()
        val listener = controller(ui).listener
        listener.onRecordingStartRequested()

        listener.deliverText("raw words", rawText = null, paidFallbackGroup = null, cleanupError = "timeout", feedbackDurationMs = 0)
        listener.onIdleUi()

        assertEquals("Cleanup failed — inserted raw transcript", ui.messages.last())
    }
}
