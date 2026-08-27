package com.trevornk.ramblr

/** Small, Android-free policy layer around the IME host so destination safety is deterministic. */
internal enum class ImeUiState { IDLE, RECORDING, TRANSCRIBING, CLEANING, ERROR }

internal enum class ImeLifecycleLoss { HIDDEN, INPUT_FINISHED, DESTROYED }

internal data class ImeEditorIdentity(
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int,
    val imeOptions: Int = 0,
    val privateImeOptions: String? = null,
)

internal interface ImeRuntimeControl {
    fun onTap()
    fun shutdown()
}

/**
 * Binds one dictation to the exact editor generation, editor metadata, and InputConnection object
 * that existed when recording began. It is service-instance state, never process-global state.
 */
internal class ImeDestinationGuard {
    private data class Editor(val generation: Long, val identity: ImeEditorIdentity, val connection: Any)

    private var current: Editor? = null
    private var origin: Editor? = null
    private var consumed = false

    fun editorChanged(generation: Long, identity: ImeEditorIdentity, connection: Any?) {
        current = connection?.let { Editor(generation, identity, it) }
    }

    fun bindDictation() {
        origin = current
        consumed = false
    }

    fun invalidate() {
        current = null
        origin = null
        consumed = true
    }

    fun commitIfCurrent(
        generation: Long,
        identity: ImeEditorIdentity,
        connection: Any?,
        text: String,
        commit: (String) -> Unit,
    ): Boolean {
        val bound = origin ?: return false
        val now = current ?: return false
        if (consumed || connection == null ||
            bound.generation != generation || bound.identity != identity || bound.connection !== connection ||
            now.generation != generation || now.identity != identity || now.connection !== connection
        ) return false
        consumed = true
        commit(text)
        return true
    }
}

/** Runtime listener + lifecycle adapter used by RamblrImeService. */
internal class ImePanelController(
    private val runtime: ImeRuntimeControl,
    private val renderState: (ImeUiState) -> Unit,
    private val destination: ImeDestinationGuard = ImeDestinationGuard(),
    private val editorSnapshot: (() -> Triple<Long, ImeEditorIdentity, Any?>)? = null,
    private val commitText: ((Any, String) -> Unit)? = null,
    private val renderPartial: (String) -> Unit = {},
    private val userMessage: (String) -> Unit = {},
) {
    private var active = true

    val listener: RuntimeListener = object : RuntimeListener {
        override fun onRecordingStartRequested() {
            if (!active) return
            editorSnapshot?.invoke()?.let { (generation, identity, connection) ->
                destination.editorChanged(generation, identity, connection)
            }
            destination.bindDictation()
            renderPartial("")
        }

        override fun onRecordingStartFailed() = Unit
        override fun onRecordingStarted() { if (active) renderState(ImeUiState.RECORDING) }
        override fun onEnterTranscribingUi() { if (active) renderState(ImeUiState.TRANSCRIBING) }
        override fun onCleaningStarted() { if (active) renderState(ImeUiState.CLEANING) }
        override fun onIdleUi() { if (active) renderState(ImeUiState.IDLE) }
        override fun onStreamingTeardown() = Unit
        override fun onStreamingPartial(text: String) { if (active) renderPartial(text) }
        override fun onUserMessage(message: String) {
            if (!active) return
            renderState(ImeUiState.ERROR)
            userMessage(message)
        }

        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {
            if (!active) return
            val snapshot = editorSnapshot?.invoke()
            val committed = snapshot != null && commitText != null && destination.commitIfCurrent(
                snapshot.first,
                snapshot.second,
                snapshot.third,
                text,
                { value -> commitText(snapshot.third!!, value) },
            )
            if (!committed) {
                renderState(ImeUiState.ERROR)
                userMessage("Editor changed — dictated text was discarded")
            } else if (cleanupError != null) {
                userMessage("Cleanup failed — inserted raw transcript")
            }
        }

        override fun foregroundPackageName(): String? = editorSnapshot?.invoke()?.second?.packageName
    }

    fun onMicTap() {
        if (active) runtime.onTap()
    }

    fun onLifecycleLost(reason: ImeLifecycleLoss) {
        @Suppress("UNUSED_VARIABLE") val lifecycleReason = reason
        if (!active) return
        active = false
        destination.invalidate()
        runtime.shutdown()
    }
}

internal enum class ImeSwitchResult { SWITCHED, PICKER_SHOWN }

internal fun switchIme(tryPrevious: () -> Boolean, showPicker: () -> Unit): ImeSwitchResult {
    if (tryPrevious()) return ImeSwitchResult.SWITCHED
    showPicker()
    return ImeSwitchResult.PICKER_SHOWN
}
