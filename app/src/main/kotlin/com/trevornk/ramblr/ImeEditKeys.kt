package com.trevornk.ramblr

import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.text.InputType

/**
 * Quick edit keys for the voice keyboard (#312): Backspace, Space, Enter. Deliberately NOT a
 * typing surface -- three correction keys next to the mic, nothing else.
 *
 * Everything decision-shaped is a pure function so it is unit-tested without a service; the
 * InputConnection work is in [ImeEditActions] and always runs on the IME main thread.
 */
internal enum class ImeEditKey { BACKSPACE, SPACE, ENTER }

/** What the Enter key should do in the current editor. */
internal sealed class EnterKeyAction {
    /** Multi-line field: insert "\n". */
    object InsertNewline : EnterKeyAction()

    /** Single-line field advertising an action (Go/Search/Send/Done/Next/Previous). */
    data class PerformEditorAction(val actionId: Int) : EnterKeyAction()

    /** Single-line field with no action: press the Enter key like a hardware keyboard would. */
    object SendEnterKey : EnterKeyAction()
}

private val PERFORMABLE_ACTIONS = setOf(
    EditorInfo.IME_ACTION_GO,
    EditorInfo.IME_ACTION_SEARCH,
    EditorInfo.IME_ACTION_SEND,
    EditorInfo.IME_ACTION_DONE,
    EditorInfo.IME_ACTION_NEXT,
    EditorInfo.IME_ACTION_PREVIOUS,
)

/**
 * Multi-line wins over the advertised action on purpose: a chat box is typically multi-line AND
 * actionSend, and an Enter key on a *voice* keyboard that silently submits the half-written
 * message is the worst failure available. Only genuinely single-line fields perform the action.
 */
internal fun resolveEnterKey(inputType: Int, imeOptions: Int): EnterKeyAction {
    val multiLine = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
        (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
    if (multiLine) return EnterKeyAction.InsertNewline
    if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterKeyAction.SendEnterKey
    val action = imeOptions and EditorInfo.IME_MASK_ACTION
    return if (action in PERFORMABLE_ACTIONS) EnterKeyAction.PerformEditorAction(action) else EnterKeyAction.SendEnterKey
}

private fun isDictationInFlight(state: ImeUiState): Boolean =
    state == ImeUiState.RECORDING || state == ImeUiState.TRANSCRIBING || state == ImeUiState.CLEANING

/**
 * Whether [key] may act right now.
 *
 * Backspace / Space / newline are safe at ANY time a field is editable, including mid-dictation:
 * they run on the main thread, serialised with the dictation's own `commitText`, they never touch
 * the destination ticket (editor generation + identity + InputConnection identity), and the
 * dictation inserts at the caret when it lands (no composing region or cached offsets exist for
 * them to invalidate). Enter is the exception when it would *leave* the field (perform Send/Go/
 * Search/Done/Next or press Enter): submitting before the dictated text lands would send the form
 * without it and then drop the text into a field the user has left. [commitPending] covers the
 * short window where the UI already says IDLE but the guarded commit has not run yet.
 */
internal fun imeEditKeyEnabled(
    key: ImeEditKey,
    state: ImeUiState,
    commitPending: Boolean,
    enter: EnterKeyAction,
): Boolean {
    if (state == ImeUiState.SECURE_FIELD) return false
    if (key != ImeEditKey.ENTER) return true
    if (enter == EnterKeyAction.InsertNewline) return true
    return !(isDictationInFlight(state) || commitPending)
}

/**
 * The cleanup-style picker only changes what the NEXT dictation uses. DictationRuntime reads the
 * style pref when cleanup starts (after transcription), so changing it mid-flight would silently
 * restyle the dictation already in progress. Rather than snapshotting inside the runtime, the
 * picker is simply unavailable until the dictation (and its commit) has finished.
 */
internal fun imeStylePickerEnabled(state: ImeUiState, commitPending: Boolean): Boolean =
    !(isDictationInFlight(state) || state == ImeUiState.SECURE_FIELD || commitPending)

/** InputConnection operations behind the edit keys. All are no-ops (false) without a connection. */
internal object ImeEditActions {
    /** Deletes the selection if there is one, otherwise exactly one code point before the caret. */
    fun backspace(ic: InputConnection?): Boolean {
        ic ?: return false
        return try {
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                // By code point, so an emoji is removed whole instead of leaving half a surrogate pair.
                ic.deleteSurroundingTextInCodePoints(1, 0) || sendKey(ic, KeyEvent.KEYCODE_DEL)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun space(ic: InputConnection?): Boolean {
        ic ?: return false
        return try { ic.commitText(" ", 1) } catch (_: Exception) { false }
    }

    fun enter(ic: InputConnection?, inputType: Int, imeOptions: Int): Boolean {
        ic ?: return false
        return try {
            when (val action = resolveEnterKey(inputType, imeOptions)) {
                EnterKeyAction.InsertNewline -> ic.commitText("\n", 1)
                is EnterKeyAction.PerformEditorAction -> ic.performEditorAction(action.actionId)
                EnterKeyAction.SendEnterKey -> sendKey(ic, KeyEvent.KEYCODE_ENTER)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sendKey(ic: InputConnection, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        val up = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return down && up
    }
}

/**
 * Hold-to-repeat for a key: [press] acts once immediately (so a tap is exactly one action), then
 * after [firstDelayMs] repeats every [intervalMs] until [release] or until [action] returns false
 * (nothing left to act on). Scheduling is injected so the timing is unit-testable and the Android
 * Handler stays in the service.
 */
internal class KeyRepeater(
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val firstDelayMs: Long = defaultFirstDelayMs(),
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val action: () -> Boolean,
) {
    private var holding = false
    private val tick = object : Runnable {
        override fun run() {
            if (!holding) return
            if (action()) post(this, intervalMs) else holding = false
        }
    }

    fun press() {
        release()
        holding = true
        if (action()) post(tick, firstDelayMs) else holding = false
    }

    fun release() {
        holding = false
        cancel(tick)
    }

    companion object {
        /** Platform key-repeat cadence (Android's own long-press-to-repeat is 500ms then 50ms). */
        const val DEFAULT_INTERVAL_MS = 50L
        fun defaultFirstDelayMs(): Long = ViewConfiguration.getKeyRepeatTimeout().toLong()
    }
}
