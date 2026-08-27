package com.trevornk.ramblr

import android.text.InputType
import android.view.inputmethod.EditorInfo
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
    fun `IME contains no conventional key rows runtime cleanup or auto enable path`() {
        val source = File(
            repoRoot(),
            "app/src/main/kotlin/com/trevornk/ramblr/RamblrImeService.kt",
        ).readText()
        assertFalse(source.contains("KEYCODE_"))
        assertFalse(source.contains("Settings.Secure"))
        assertFalse(source.contains("setInputMethod"))
        assertFalse(source.contains("cleanupOrphanedRecordings"))

        val main = File(
            repoRoot(),
            "app/src/main/kotlin/com/trevornk/ramblr/MainActivity.kt",
        ).readText()
        assertTrue(main.contains("Enable voice keyboard"))
        assertTrue(main.contains("Settings.ACTION_INPUT_METHOD_SETTINGS"))
        assertFalse(main.contains("Settings.Secure.putString"))
        assertFalse(main.contains("setInputMethod("))
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

        assertEquals(ImeCommitResult.SUCCESS, destination.commitIfCurrent(7, identity, origin, "hello") { commits++; true })
        assertEquals(ImeCommitResult.STALE, destination.commitIfCurrent(7, identity, origin, "hello again") { commits++; true })
        assertEquals(1, commits)
    }

    @Test
    fun `runtime listener commits accepted output through its originating snapshot`() {
        val connection = Any()
        val identity = ImeEditorIdentity("example.app", 42, 1)
        val commits = mutableListOf<String>()
        val controller = ImePanelController(
            FakeRuntimeControl(),
            {},
            editorSnapshot = { Triple(9L, identity, connection) },
            commitText = { _, text -> commits += text; true },
        )
        controller.onEditorChanged(9L, identity, connection)
        controller.listener.onRecordingStartRequested()

        controller.listener.deliverText("hello", null, null, null, 2_000)
        controller.listener.deliverText("duplicate", null, null, null, 2_000)

        assertEquals(listOf("hello"), commits)
    }

    @Test
    fun `editor generation change rejects late output instead of replacement field`() {
        val destination = ImeDestinationGuard()
        val origin = Any()
        val replacement = Any()
        val first = ImeEditorIdentity("one.app", 1, 1)
        val second = ImeEditorIdentity("two.app", 2, 1)
        var committed: String? = null
        destination.editorChanged(1, first, origin)
        destination.bindDictation()
        destination.editorChanged(2, second, replacement)

        assertEquals(ImeCommitResult.STALE, destination.commitIfCurrent(2, second, replacement, "stale") { committed = it; true })
        assertNull(committed)
    }

    @Test
    fun `runtime listener preserves output after editor generation changes`() {
        val origin = Any()
        var connection: Any? = origin
        var generation = 1L
        var identity = ImeEditorIdentity("one.app", 1, 1)
        val commits = mutableListOf<String>()
        val histories = mutableListOf<DictationHistoryEntry>()
        val messages = mutableListOf<String>()
        val controller = ImePanelController(
            FakeRuntimeControl(),
            {},
            editorSnapshot = { Triple(generation, identity, connection) },
            commitText = { _, text -> commits += text; true },
            recordHistory = { histories += it; true },
            userMessage = messages::add,
        )
        controller.onEditorChanged(generation, identity, connection)
        controller.listener.onRecordingStartRequested()
        generation = 2
        identity = ImeEditorIdentity("two.app", 2, 1)
        connection = Any()
        controller.onEditorChanged(generation, identity, connection)

        controller.listener.deliverText("stale", null, null, null, 2_000)

        assertTrue(commits.isEmpty())
        assertEquals(listOf("stale"), histories.map { it.rawText })
        assertEquals(listOf("Text could not be inserted — dictated text was saved in Ramblr history"), messages)
    }

    @Test
    fun `hidden finished and destroyed lifecycle cancel runtime and invalidate callbacks`() {
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
                commitText = { _, text -> commits += text; true },
            )

            controller.onLifecycleLost(loss)
            controller.listener.deliverText("late", null, null, null, 2_000)

            assertEquals(1, runtime.invalidations)
            assertEquals(1, runtime.asyncTeardowns)
            assertEquals(ImeCommitResult.STALE, destination.commitIfCurrent(1, identity, connection, "late") { true })
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

    @Test
    fun `successful commit records history and attempts insertion exactly once`() {
        val connection = Any()
        val identity = ImeEditorIdentity("example.app", 42, InputType.TYPE_CLASS_TEXT)
        val history = mutableListOf<DictationHistoryEntry>()
        var commits = 0
        val controller = ImePanelController(
            FakeRuntimeControl(),
            {},
            editorSnapshot = { Triple(1L, identity, connection) },
            commitText = { _, _ -> commits++; true },
            recordHistory = { history += it; true },
        )
        controller.onEditorChanged(1L, identity, connection)
        controller.listener.onRecordingStartRequested()

        controller.listener.deliverText("Cleaned", "raw", null, null, 2_000)
        controller.listener.deliverText("duplicate", null, null, null, 2_000)

        assertEquals(1, commits)
        assertEquals(1, history.size)
        assertEquals("raw", history.single().rawText)
        assertEquals("Cleaned", history.single().cleanedText)
    }

    @Test
    fun `false commit is terminal and leaves one recoverable history record`() {
        verifyFailedCommit { false }
    }

    @Test
    fun `throwing commit is caught and leaves one recoverable history record`() {
        verifyFailedCommit { throw IllegalStateException("dead connection") }
    }

    @Test
    fun `password variations are blocked while no personalized learning remains eligible`() {
        val passwordTypes = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        passwordTypes.forEach { assertFalse(imeEditorPolicy(it, 0).allowsDictation) }

        val noLearning = imeEditorPolicy(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        assertTrue(noLearning.allowsDictation)
        assertFalse(noLearning.allowsRetention)
    }

    @Test
    fun `secure editor mic is disabled before runtime can start`() {
        val runtime = FakeRuntimeControl()
        val states = mutableListOf<ImeUiState>()
        val messages = mutableListOf<String>()
        val controller = ImePanelController(runtime, states::add, userMessage = messages::add)
        val secure = ImeEditorIdentity(
            "secure.example",
            7,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        controller.onEditorChanged(1L, secure, Any())

        controller.onMicTap()

        assertEquals(0, runtime.taps)
        assertEquals(listOf(ImeUiState.SECURE_FIELD), states)
        assertEquals(listOf("Voice input unavailable in secure fields"), messages)
    }

    @Test
    fun `no personalized learning commits without history retention`() {
        val connection = Any()
        val identity = ImeEditorIdentity(
            "bank.example",
            1,
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        var commits = 0
        var histories = 0
        val controller = ImePanelController(
            FakeRuntimeControl(),
            {},
            editorSnapshot = { Triple(1L, identity, connection) },
            commitText = { _, _ -> commits++; true },
            recordHistory = { histories++; true },
        )
        controller.onEditorChanged(1L, identity, connection)
        controller.listener.onRecordingStartRequested()
        controller.listener.deliverText("private", null, null, null, 2_000)

        assertEquals(1, commits)
        assertEquals(0, histories)
        assertFalse(controller.listener.allowsTranscriptRetention())
    }

    @Test
    fun `background package callback reads cached identity without editor snapshot`() {
        val identity = ImeEditorIdentity("cached.example", 1, InputType.TYPE_CLASS_TEXT)
        var snapshotReads = 0
        val controller = ImePanelController(
            FakeRuntimeControl(),
            {},
            editorSnapshot = { snapshotReads++; Triple(1L, identity, Any()) },
        )
        controller.onEditorChanged(1L, identity, Any())

        assertEquals("cached.example", controller.listener.foregroundPackageName())
        assertEquals(0, snapshotReads)
    }

    @Test
    fun `lifecycle invalidates synchronously before asynchronous teardown`() {
        val runtime = FakeRuntimeControl()
        val controller = ImePanelController(runtime, {})

        controller.onLifecycleLost(ImeLifecycleLoss.HIDDEN)

        assertEquals(1, runtime.invalidations)
        assertEquals(1, runtime.asyncTeardowns)
        controller.onMicTap()
        assertEquals(0, runtime.taps)
    }

    private fun verifyFailedCommit(commit: () -> Boolean) {
        val connection = Any()
        val identity = ImeEditorIdentity("example.app", 42, InputType.TYPE_CLASS_TEXT)
        val history = mutableListOf<DictationHistoryEntry>()
        val messages = mutableListOf<String>()
        var attempts = 0
        val controller = ImePanelController(
            FakeRuntimeControl(),
            {},
            editorSnapshot = { Triple(1L, identity, connection) },
            commitText = { _, _ -> attempts++; commit() },
            recordHistory = { history += it; true },
            userMessage = messages::add,
        )
        controller.onEditorChanged(1L, identity, connection)
        controller.listener.onRecordingStartRequested()

        controller.listener.deliverText("recover me", null, null, null, 2_000)
        controller.listener.deliverText("do not retry", null, null, null, 2_000)

        assertEquals(1, attempts)
        assertEquals(listOf("recover me"), history.map { it.rawText })
        assertEquals(
            listOf("Text could not be inserted — dictated text was saved in Ramblr history"),
            messages,
        )
    }

    private class FakeRuntimeControl : ImeRuntimeControl {
        var taps = 0
        var invalidations = 0
        var asyncTeardowns = 0
        override fun onTap() { taps++ }
        override fun invalidate() { invalidations++ }
        override fun teardownAsync() { asyncTeardowns++ }
    }
}
