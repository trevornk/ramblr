package com.trevornk.ramblr

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Small, Android-free policy layer around the IME host so destination safety is deterministic. */
internal enum class ImeUiState { IDLE, RECORDING, TRANSCRIBING, CLEANING, ERROR, SECURE_FIELD }

internal enum class ImeLifecycleLoss { HIDDEN, INPUT_FINISHED, DESTROYED }

internal data class ImeEditorIdentity(
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int,
    val imeOptions: Int = 0,
    val privateImeOptions: String? = null,
)

internal data class ImeEditorPolicy(
    val allowsDictation: Boolean,
    val allowsRetention: Boolean,
)

/** Password/PIN fields are never recorded; no-learning fields still dictate but retain no text. */
internal fun imeEditorPolicy(inputType: Int, imeOptions: Int): ImeEditorPolicy {
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    val password = when (inputClass) {
        InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
    val noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
    return ImeEditorPolicy(allowsDictation = !password, allowsRetention = !password && !noLearning)
}

internal interface ImeRuntimeControl {
    fun onTap()
    /** Synchronous token/session invalidation; must never wait for native/audio teardown. */
    fun invalidate()
    /** Potentially blocking reader/native teardown, always launched off the IME main thread. */
    fun teardownAsync()
}

internal enum class ImeCommitResult { SUCCESS, STALE, REJECTED }

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

    /** One fail-closed delivery attempt. False/throw are terminal and never redirected or retried. */
    fun commitIfCurrent(
        generation: Long,
        identity: ImeEditorIdentity,
        connection: Any?,
        text: String,
        commit: (String) -> Boolean,
    ): ImeCommitResult {
        val bound = origin ?: return ImeCommitResult.STALE
        val now = current ?: return ImeCommitResult.STALE
        if (consumed || connection == null ||
            bound.generation != generation || bound.identity != identity || bound.connection !== connection ||
            now.generation != generation || now.identity != identity || now.connection !== connection
        ) return ImeCommitResult.STALE
        consumed = true
        return try {
            if (commit(text)) ImeCommitResult.SUCCESS else ImeCommitResult.REJECTED
        } catch (_: Exception) {
            ImeCommitResult.REJECTED
        }
    }
}

/** Runtime listener + lifecycle adapter used by RamblrImeService. */
internal class ImePanelController(
    private val runtime: ImeRuntimeControl,
    private val renderState: (ImeUiState) -> Unit,
    private val destination: ImeDestinationGuard = ImeDestinationGuard(),
    private val editorSnapshot: (() -> Triple<Long, ImeEditorIdentity, Any?>)? = null,
    private val commitText: ((Any, String) -> Boolean)? = null,
    private val renderPartial: (String) -> Unit = {},
    private val userMessage: (String) -> Unit = {},
    private val recordHistory: (DictationHistoryEntry) -> Boolean = { false },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var active = true
    private var deliveryTerminal = false
    @Volatile private var cachedPackageName: String? = null
    @Volatile private var editorPolicy = ImeEditorPolicy(allowsDictation = true, allowsRetention = true)
    @Volatile private var sessionAllowsRetention = true

    val listener: RuntimeListener = object : RuntimeListener {
        override fun onRecordingStartRequested() {
            if (!active || !editorPolicy.allowsDictation) return
            editorSnapshot?.invoke()?.let { (generation, identity, connection) ->
                onEditorChanged(generation, identity, connection)
            }
            destination.bindDictation()
            deliveryTerminal = false
            sessionAllowsRetention = editorPolicy.allowsRetention
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
            if (!active || deliveryTerminal) return
            deliveryTerminal = true

            // Persist the accepted final output before attempting the one-shot InputConnection
            // delivery, matching the accessibility host's raw/cleaned history semantics. A stale,
            // false, or throwing commit therefore remains recoverable when retention is allowed.
            val historySaved = sessionAllowsRetention && runCatching {
                recordHistory(
                    DictationHistoryEntry(
                        timestamp = nowMs(),
                        rawText = rawText ?: text,
                        cleanedText = text.takeIf { rawText != null },
                        paidFallbackGroup = paidFallbackGroup,
                    )
                )
            }.getOrDefault(false)

            val snapshot = runCatching { editorSnapshot?.invoke() }.getOrNull()
            val result = if (snapshot != null && commitText != null) {
                destination.commitIfCurrent(
                    snapshot.first,
                    snapshot.second,
                    snapshot.third,
                    text,
                ) { value -> commitText(snapshot.third!!, value) }
            } else {
                ImeCommitResult.STALE
            }
            if (result != ImeCommitResult.SUCCESS) {
                renderState(ImeUiState.ERROR)
                userMessage(
                    if (historySaved) "Text could not be inserted — dictated text was saved in Ramblr history"
                    else "Text could not be inserted"
                )
            } else if (cleanupError != null) {
                userMessage("Cleanup failed — inserted raw transcript")
            }
        }

        override fun foregroundPackageName(): String? = cachedPackageName
        override fun allowsTranscriptRetention(): Boolean = sessionAllowsRetention
    }

    fun onEditorChanged(generation: Long, identity: ImeEditorIdentity, connection: Any?) {
        cachedPackageName = identity.packageName
        editorPolicy = imeEditorPolicy(identity.inputType, identity.imeOptions)
        if (editorPolicy.allowsDictation) destination.editorChanged(generation, identity, connection)
        else destination.invalidate()
    }

    fun currentEditorPolicy(): ImeEditorPolicy = editorPolicy

    fun onMicTap() {
        if (!active) return
        if (!editorPolicy.allowsDictation) {
            renderState(ImeUiState.SECURE_FIELD)
            userMessage("Voice input unavailable in secure fields")
            return
        }
        runtime.onTap()
    }

    fun onLifecycleLost(reason: ImeLifecycleLoss) {
        @Suppress("UNUSED_VARIABLE") val lifecycleReason = reason
        if (!active) return
        active = false
        sessionAllowsRetention = false
        destination.invalidate()
        runtime.invalidate()
        runtime.teardownAsync()
    }
}

internal enum class ImeSwitchResult { SWITCHED, PICKER_SHOWN }

internal fun switchIme(tryPrevious: () -> Boolean, showPicker: () -> Unit): ImeSwitchResult {
    if (tryPrevious()) return ImeSwitchResult.SWITCHED
    showPicker()
    return ImeSwitchResult.PICKER_SHOWN
}
