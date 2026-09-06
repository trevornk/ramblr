package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #256: IME-side exclusion gating on [ImePanelController] -- the IME is a distinct OS registration
 * from the accessibility service, so it carries its own `isPackageExcluded`/`isRecording` seams
 * (see the constructor kdoc) rather than reusing WhisperAccessibilityService's.
 */
class ImePanelControllerExclusionTest {

    private class FakeRuntimeControl : ImeRuntimeControl {
        var taps = 0
        override fun onTap() { taps++ }
        override fun invalidate() {}
        override fun teardownAsync() {}
    }

    private fun controllerFor(
        excludedPackages: Set<String>,
        recording: Boolean = false,
    ): Triple<ImePanelController, FakeRuntimeControl, MutableList<String>> {
        val runtimeControl = FakeRuntimeControl()
        val messages = mutableListOf<String>()
        val states = mutableListOf<ImeUiState>()
        val controller = ImePanelController(
            runtime = runtimeControl,
            renderState = { states.add(it) },
            editorSnapshot = { Triple(1L, ImeEditorIdentity("com.example.app", 0, 0), Any()) },
            commitText = { _, _ -> true },
            userMessage = { messages.add(it) },
            isPackageExcluded = { pkg -> pkg in excludedPackages },
            isRecording = { recording },
        )
        controller.onEditorChanged(1L, ImeEditorIdentity("com.example.app", 0, 0), Any())
        return Triple(controller, runtimeControl, messages)
    }

    @Test fun `mic tap starts recording normally when app is not excluded`() {
        val (controller, runtimeControl, _) = controllerFor(excludedPackages = emptySet())

        controller.onMicTap()

        assertEquals(1, runtimeControl.taps)
    }

    @Test fun `mic tap is blocked and never reaches runtime when the editor's app is excluded`() {
        val (controller, runtimeControl, messages) = controllerFor(excludedPackages = setOf("com.example.app"))

        controller.onMicTap()

        assertEquals(0, runtimeControl.taps)
        assertTrue(messages.any { it.contains("excluded") })
    }

    @Test fun `mic tap still reaches runtime while already recording, even if excluded -- stop must work`() {
        val (controller, runtimeControl, _) = controllerFor(
            excludedPackages = setOf("com.example.app"),
            recording = true,
        )

        controller.onMicTap()

        assertEquals(1, runtimeControl.taps)
    }

    @Test fun `mic tap is unaffected by an excluded package other than the current editor`() {
        val (controller, runtimeControl, _) = controllerFor(excludedPackages = setOf("com.other.app"))

        controller.onMicTap()

        assertEquals(1, runtimeControl.taps)
    }

    @Test fun `deliverText suppresses commit for an excluded editor`() {
        var committed: String? = null
        val runtimeControl = FakeRuntimeControl()
        val messages = mutableListOf<String>()
        val controller = ImePanelController(
            runtime = runtimeControl,
            renderState = {},
            editorSnapshot = { Triple(1L, ImeEditorIdentity("com.example.app", 0, 0), Any()) },
            commitText = { _, text -> committed = text; true },
            userMessage = { messages.add(it) },
            isPackageExcluded = { pkg -> pkg == "com.example.app" },
        )
        controller.onEditorChanged(1L, ImeEditorIdentity("com.example.app", 0, 0), Any())
        controller.listener.onRecordingStartRequested()

        controller.listener.deliverText(
            text = "hello",
            rawText = null,
            paidFallbackGroup = null,
            cleanupError = null,
            feedbackDurationMs = 2000,
        )

        assertEquals(null, committed)
        assertTrue(messages.any { it.contains("excluded") })
    }

    @Test fun `deliverText commits normally for a non-excluded editor`() {
        var committed: String? = null
        val runtimeControl = FakeRuntimeControl()
        val controller = ImePanelController(
            runtime = runtimeControl,
            renderState = {},
            editorSnapshot = { Triple(1L, ImeEditorIdentity("com.example.app", 0, 0), Any()) },
            commitText = { _, text -> committed = text; true },
            isPackageExcluded = { false },
        )
        controller.onEditorChanged(1L, ImeEditorIdentity("com.example.app", 0, 0), Any())
        controller.listener.onRecordingStartRequested()

        controller.listener.deliverText(
            text = "hello",
            rawText = null,
            paidFallbackGroup = null,
            cleanupError = null,
            feedbackDurationMs = 2000,
        )

        assertEquals("hello", committed)
    }
}
