package com.trevornk.ramblr

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Password/PIN detection shared by the voice keyboard ([imeEditorPolicy]) and the floating icon
 * (accessibility service), so both surfaces answer "is this a secret-entry field?" identically.
 * Android-free apart from the [InputType] constants, which are compile-time ints.
 */
internal fun isPasswordInputType(inputType: Int): Boolean {
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputClass) {
        InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

/** A field is a password field if the app flags it as one (what Chrome/WebViews and most apps
 *  expose) or its input type is one of the password variations. */
internal fun isPasswordField(flaggedPassword: Boolean, inputType: Int): Boolean =
    flaggedPassword || isPasswordInputType(inputType)

internal fun AccessibilityNodeInfo.isPasswordField(): Boolean = isPasswordField(isPassword, inputType)

/** Shown whenever the floating icon refuses (or discards) a dictation because of a password field. */
internal const val PASSWORD_FIELD_REFUSAL_MESSAGE = "Ramblr doesn't dictate into password fields"

/** What the floating icon does with a finished dictation. */
internal enum class DictationResultRoute { DELIVER, DISCARD_PASSWORD_FIELD }

/** Pure decisions behind the floating icon's password-field policy. */
internal object PasswordFieldGuard {
    /** A NEW recording is refused when the focused input is a password field. Stop/cancel of a
     *  recording already in flight must stay reachable, so only IDLE is gated. */
    fun shouldRefuseRecordingStart(state: RecordingStateMachine.State?, focusedIsPassword: Boolean): Boolean =
        focusedIsPassword && state == RecordingStateMachine.State.IDLE

    /** Whether a finished dictation may be delivered. Discarded outright (no insertion, no
     *  clipboard, no history, no vocabulary counters) when the field focused now, or the field
     *  the dictation was captured in, is a password field. */
    fun routeResult(focusedIsPassword: Boolean, capturedTargetIsPassword: Boolean): DictationResultRoute =
        if (focusedIsPassword || capturedTargetIsPassword) DictationResultRoute.DISCARD_PASSWORD_FIELD
        else DictationResultRoute.DELIVER

    /** Live-preview partials are written only when the focused input is not a password field. */
    fun allowsPartialWrite(focusedIsPassword: Boolean): Boolean = !focusedIsPassword

    /** A node is never an injection candidate if it is a password field. */
    fun allowsInjectionCandidate(nodeIsPassword: Boolean): Boolean = !nodeIsPassword
}
