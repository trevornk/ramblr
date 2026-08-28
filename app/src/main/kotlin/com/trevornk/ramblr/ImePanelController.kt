package com.trevornk.ramblr

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.util.concurrent.atomic.AtomicBoolean

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

internal enum class ImeCommitResult { SUCCESS, STALE, REJECTED, DUPLICATE }

internal class ImeDestinationTicket internal constructor(
    internal val id: Long,
    internal val generation: Long,
    internal val identity: ImeEditorIdentity,
    internal val connection: Any,
) {
    private val consumed = AtomicBoolean(false)
    internal fun consume(): Boolean = consumed.compareAndSet(false, true)
}

/**
 * Binds one dictation to the exact editor generation, editor metadata, and InputConnection object
 * that existed when recording began. Each bind returns an immutable ticket, so asynchronous
 * persistence completion can only consume its own destination and never a newer dictation's.
 */
internal class ImeDestinationGuard {
    private data class Editor(val generation: Long, val identity: ImeEditorIdentity, val connection: Any)

    private var current: Editor? = null
    private var latestTicket: ImeDestinationTicket? = null
    private var nextTicketId = 0L

    fun editorChanged(generation: Long, identity: ImeEditorIdentity, connection: Any?) {
        current = connection?.let { Editor(generation, identity, it) }
    }

    fun bindDictation(): ImeDestinationTicket? {
        val editor = current ?: return null
        return ImeDestinationTicket(
            id = ++nextTicketId,
            generation = editor.generation,
            identity = editor.identity,
            connection = editor.connection,
        ).also { latestTicket = it }
    }

    fun invalidate() {
        current = null
        latestTicket = null
    }

    /** One fail-closed delivery attempt. False/throw are terminal and never redirected or retried. */
    fun commitIfCurrent(
        ticket: ImeDestinationTicket?,
        text: String,
        commit: (Any, String) -> Boolean,
    ): ImeCommitResult {
        val bound = ticket ?: return ImeCommitResult.STALE
        if (!bound.consume()) return ImeCommitResult.DUPLICATE
        val now = current ?: return ImeCommitResult.STALE
        if (now.generation != bound.generation || now.identity != bound.identity ||
            now.connection !== bound.connection
        ) return ImeCommitResult.STALE
        return try {
            if (commit(bound.connection, text)) ImeCommitResult.SUCCESS else ImeCommitResult.REJECTED
        } catch (_: Exception) {
            ImeCommitResult.REJECTED
        }
    }

    /** Compatibility helper for direct guard tests and non-async callers. */
    fun commitIfCurrent(
        generation: Long,
        identity: ImeEditorIdentity,
        connection: Any?,
        text: String,
        commit: (String) -> Boolean,
    ): ImeCommitResult {
        val ticket = latestTicket
        if (ticket == null || connection == null || ticket.generation != generation ||
            ticket.identity != identity || ticket.connection !== connection
        ) return ImeCommitResult.STALE
        val result = commitIfCurrent(ticket, text) { _, value -> commit(value) }
        return if (result == ImeCommitResult.DUPLICATE) ImeCommitResult.STALE else result
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
    private val runHistoryWrite: (() -> Unit) -> Unit = { it() },
    private val postToMain: (() -> Unit) -> Unit = { it() },
) {
    private var active = true
    private var deliveryTerminal = false
    private var deliveryTicket: ImeDestinationTicket? = null
    private var latestUiTicket: ImeDestinationTicket? = null
    @Volatile private var cachedPackageName: String? = null
    @Volatile private var editorPolicy = ImeEditorPolicy(allowsDictation = true, allowsRetention = true)
    @Volatile private var sessionAllowsRetention = true

    val listener: RuntimeListener = object : RuntimeListener {
        override fun onRecordingStartRequested() {
            if (!active || !editorPolicy.allowsDictation) return
            editorSnapshot?.invoke()?.let { (generation, identity, connection) ->
                onEditorChanged(generation, identity, connection)
            }
            deliveryTicket = destination.bindDictation()
            latestUiTicket = deliveryTicket
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
            val ticket = deliveryTicket
            val entry = DictationHistoryEntry(
                timestamp = nowMs(),
                rawText = rawText ?: text,
                cleanedText = text.takeIf { rawText != null },
                paidFallbackGroup = paidFallbackGroup,
            )

            // File read/parse/rewrite is unbounded and must never run on the IME main thread. The
            // immutable ticket is revalidated only after the durable write returns to main.
            if (sessionAllowsRetention) {
                val accepted = runCatching {
                    runHistoryWrite {
                        val historySaved = runCatching { recordHistory(entry) }.getOrDefault(false)
                        postToMain { completeDelivery(ticket, text, cleanupError, historySaved) }
                    }
                }.isSuccess
                if (!accepted) completeDelivery(ticket, text, cleanupError, historySaved = false)
            } else {
                completeDelivery(ticket, text, cleanupError, historySaved = false)
            }
        }

        override fun foregroundPackageName(): String? = cachedPackageName
        override fun allowsTranscriptRetention(): Boolean = sessionAllowsRetention
    }

    private fun completeDelivery(
        ticket: ImeDestinationTicket?,
        text: String,
        cleanupError: String?,
        historySaved: Boolean,
    ) {
        if (!active) return
        val result = if (commitText != null) {
            destination.commitIfCurrent(ticket, text, commitText)
        } else {
            ImeCommitResult.STALE
        }
        if (result == ImeCommitResult.DUPLICATE) return
        val ownsUi = latestUiTicket === ticket
        if (ownsUi) latestUiTicket = null
        if (!ownsUi) return
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
        deliveryTicket = null
        latestUiTicket = null
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
