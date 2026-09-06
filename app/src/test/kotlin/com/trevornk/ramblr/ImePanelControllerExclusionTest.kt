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

    /**
     * The suppression branch's own comment claims "History still records what was said (a local
     * record, not a write into the excluded app -- same rationale as the accessibility-service
     * path)", and [WhisperAccessibilityService.injectText] really does call `recordHistory(...)`
     * before its own exclusion return. The IME must not silently diverge: an excluded dictation
     * is exactly the case where local history is the user's only copy of what they just said,
     * since nothing was committed to the field.
     */
    @Test fun `deliverText still records history for an excluded editor`() {
        val recorded = mutableListOf<DictationHistoryEntry>()
        var committed: String? = null
        val controller = ImePanelController(
            runtime = FakeRuntimeControl(),
            renderState = {},
            editorSnapshot = { Triple(1L, ImeEditorIdentity("com.example.app", 0, 0), Any()) },
            commitText = { _, text -> committed = text; true },
            userMessage = {},
            recordHistory = { entry -> recorded.add(entry); true },
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

        assertEquals("nothing may be committed into the excluded editor", null, committed)
        assertEquals("the dictation must still reach local history", 1, recorded.size)
        assertEquals("hello", recorded.single().rawText)
    }

    /**
     * ...but retention policy still wins over that: an editor that opted out of personalized
     * learning ([EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]) must never be written to history,
     * excluded or not. Guards against "excluded" becoming a backdoor around
     * [ImeEditorPolicy.allowsRetention].
     */
    @Test fun `deliverText does not record history for an excluded no-retention editor`() {
        val recorded = mutableListOf<DictationHistoryEntry>()
        val noRetention = ImeEditorIdentity(
            packageName = "com.example.app",
            fieldId = 0,
            inputType = 0,
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        val controller = ImePanelController(
            runtime = FakeRuntimeControl(),
            renderState = {},
            editorSnapshot = { Triple(1L, noRetention, Any()) },
            commitText = { _, _ -> true },
            userMessage = {},
            recordHistory = { entry -> recorded.add(entry); true },
            isPackageExcluded = { pkg -> pkg == "com.example.app" },
        )
        controller.onEditorChanged(1L, noRetention, Any())
        controller.listener.onRecordingStartRequested()

        controller.listener.deliverText(
            text = "hello",
            rawText = null,
            paidFallbackGroup = null,
            cleanupError = null,
            feedbackDurationMs = 2000,
        )

        assertTrue("a no-retention editor must never reach history", recorded.isEmpty())
    }
}
