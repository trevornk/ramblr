package com.trevornk.ramblr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RamblrImeTest {
    private fun repoRoot(): File = generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }
        .first { File(it, "app/src/main/AndroidManifest.xml").isFile }

    @Test
    fun `manifest declares protected exported IME and metadata`() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".RamblrImeService\""))
        assertTrue(manifest.contains("android:permission=\"android.permission.BIND_INPUT_METHOD\""))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("android.view.InputMethod"))
        assertTrue(manifest.contains("android.view.im"))
        assertTrue(manifest.contains("@xml/method"))

        val method = File(repoRoot(), "app/src/main/res/xml/method.xml").readText()
        assertTrue(method.contains("input-method"))
        assertTrue(method.contains("supportsSwitchingToNextInputMethod"))
        assertFalse("voice-only multilingual IME must not claim a false locale", method.contains("imeSubtypeLocale"))
    }

    @Test
    fun `IME contains no conventional key rows or auto enable path`() {
        val source = File(
            repoRoot(),
            "app/src/main/kotlin/com/trevornk/ramblr/RamblrImeService.kt",
        ).readText()
        assertFalse(source.contains("KEYCODE_"))
        assertFalse(source.contains("Settings.Secure"))
        assertFalse(source.contains("setInputMethod"))
        assertFalse(source.contains("ACTION_INPUT_METHOD_SETTINGS"))
    }

    @Test
    fun `mic taps start and stop through runtime while listener reflects states`() {
        val runtime = FakeRuntimeControl()
        val states = mutableListOf<ImeUiState>()
        val controller = ImePanelController(runtime, states::add)

        controller.onMicTap()
        controller.listener.onRecordingStarted()
        controller.onMicTap()
        controller.listener.onEnterTranscribingUi()
        controller.listener.onCleaningStarted()
        controller.listener.onIdleUi()

        assertEquals(2, runtime.taps)
        assertEquals(
            listOf(ImeUiState.RECORDING, ImeUiState.TRANSCRIBING, ImeUiState.CLEANING, ImeUiState.IDLE),
            states,
        )
    }

    @Test
    fun `accepted output commits exactly once to originating editor`() {
        val destination = ImeDestinationGuard()
        val origin = Any()
        val identity = ImeEditorIdentity("example.app", 42, 1)
        var commits = 0
        destination.editorChanged(7, identity, origin)
        destination.bindDictation()

        assertTrue(destination.commitIfCurrent(7, identity, origin, "hello") { commits++ })
        assertFalse(destination.commitIfCurrent(7, identity, origin, "hello again") { commits++ })
        assertEquals(1, commits)
    }

    @Test
    fun `runtime listener commits accepted output through its originating snapshot`() {
        val connection = Any()
        val identity = ImeEditorIdentity("example.app", 42, 1)
        var generation = 9L
        var currentConnection: Any? = connection
        val commits = mutableListOf<String>()
        val controller = ImePanelController(
            FakeRuntimeControl(),
            {},
            editorSnapshot = { Triple(generation, identity, currentConnection) },
            commitText = { _, text -> commits += text },
        )
        controller.listener.onRecordingStartRequested()

        controller.listener.deliverText("hello", null, null, null, 2_000)
        controller.listener.deliverText("duplicate", null, null, null, 2_000)

        assertEquals(listOf("hello"), commits)
    }

    @Test
    fun `editor generation change discards late output instead of replacement field`() {
        val destination = ImeDestinationGuard()
        val origin = Any()
        val replacement = Any()
        val first = ImeEditorIdentity("one.app", 1, 1)
        val second = ImeEditorIdentity("two.app", 2, 1)
        var committed: String? = null
        destination.editorChanged(1, first, origin)
        destination.bindDictation()
        destination.editorChanged(2, second, replacement)

        assertFalse(destination.commitIfCurrent(2, second, replacement, "stale") { committed = it })
        assertNull(committed)
    }

    @Test
    fun `runtime listener discards output after editor generation changes`() {
        val origin = Any()
        var connection: Any? = origin
        var generation = 1L
        var identity = ImeEditorIdentity("one.app", 1, 1)
        val commits = mutableListOf<String>()
        val messages = mutableListOf<String>()
        val controller = ImePanelController(
            FakeRuntimeControl(),
            {},
            editorSnapshot = { Triple(generation, identity, connection) },
            commitText = { _, text -> commits += text },
            userMessage = messages::add,
        )
        controller.listener.onRecordingStartRequested()
        generation = 2
        identity = ImeEditorIdentity("two.app", 2, 1)
        connection = Any()

        controller.listener.deliverText("stale", null, null, null, 2_000)

        assertTrue(commits.isEmpty())
        assertEquals(listOf("Editor changed — dictated text was discarded"), messages)
    }

    @Test
    fun `hidden finished and destroyed lifecycle cancels runtime and invalidates callbacks`() {
        listOf(ImeLifecycleLoss.HIDDEN, ImeLifecycleLoss.INPUT_FINISHED, ImeLifecycleLoss.DESTROYED).forEach { loss ->
            val runtime = FakeRuntimeControl()
            val destination = ImeDestinationGuard()
            val connection = Any()
            val identity = ImeEditorIdentity("example.app", 1, 1)
            val commits = mutableListOf<String>()
            destination.editorChanged(1, identity, connection)
            destination.bindDictation()
            val controller = ImePanelController(
                runtime,
                {},
                destination,
                editorSnapshot = { Triple(1, identity, connection) },
                commitText = { _, text -> commits += text },
            )

            controller.onLifecycleLost(loss)
            controller.listener.deliverText("late", null, null, null, 2_000)

            assertEquals(1, runtime.shutdowns)
            assertFalse(destination.commitIfCurrent(1, identity, connection, "late") {})
            assertTrue(commits.isEmpty())
        }
    }

    @Test
    fun `lease denial busy message is surfaced in IME state`() {
        val messages = mutableListOf<String>()
        val controller = ImePanelController(FakeRuntimeControl(), {}, userMessage = messages::add)

        controller.listener.onUserMessage(DictationRuntime.BUSY_MESSAGE)

        assertEquals(listOf(DictationRuntime.BUSY_MESSAGE), messages)
    }

    @Test
    fun `keyboard switch falls back to picker when previous IME cannot be selected`() {
        var pickerCalls = 0
        assertEquals(ImeSwitchResult.SWITCHED, switchIme({ true }) { pickerCalls++ })
        assertEquals(0, pickerCalls)

        assertEquals(ImeSwitchResult.PICKER_SHOWN, switchIme({ false }) { pickerCalls++ })
        assertEquals(1, pickerCalls)
    }

    private class FakeRuntimeControl : ImeRuntimeControl {
        var taps = 0
        var shutdowns = 0
        override fun onTap() { taps++ }
        override fun shutdown() { shutdowns++ }
    }
}
