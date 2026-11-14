package com.trevornk.ramblr

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log

/**
 * Commit through the accessibility input method (Android 13+).
 *
 * `AccessibilityService.onCreateInputMethod()` gives an accessibility service a real
 * `InputMethod.AccessibilityInputConnection` bound to whatever editor currently has input -- the
 * same channel a keyboard uses, without being the selected IME. This is what reaches apps whose
 * text surface exposes no editable accessibility node and no ACTION_PASTE, Termux most notably:
 * its terminal is a plain View, but it *is* a text editor (`onCheckIsTextEditor()` returns true),
 * so it publishes an input connection.
 *
 * Requires `flagInputMethodEditor` in the service's accessibilityFlags, otherwise
 * `AccessibilityService.getInputMethod()` returns null.
 *
 * The connection only exists between onStartInput and onFinishInput. When Termux holds focus at
 * the end of a dictation the session is already live, so a single check is enough.
 */
object A11yInputCommit {
    private const val TAG = "PhoneWhisper"
    const val TERMUX_PACKAGE = "com.termux"

    /** Apps whose text surface accepts input only through an input connection. */
    fun prefersInputConnection(packageName: CharSequence?): Boolean =
        packageName?.toString() == TERMUX_PACKAGE

    /**
     * Commits [text] to the editor that currently holds input. When [expectedPackage] is given,
     * refuses unless that editor belongs to it, so a stale session can never type into a
     * different app than the one being dictated into. Password editors are always refused.
     */
    fun commit(service: AccessibilityService, text: String, expectedPackage: String? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "IME commit: skipped, SDK ${Build.VERSION.SDK_INT} < 33")
            return false
        }
        val inputMethod = try {
            service.inputMethod
        } catch (e: RuntimeException) {
            Log.w(TAG, "IME commit: getInputMethod() threw", e)
            return false
        }
        if (inputMethod == null) {
            Log.i(TAG, "IME commit: getInputMethod() == null (flagInputMethodEditor not applied?)")
            return false
        }

        // No waiting here: this runs on the main looper, which is also the thread that delivers
        // onStartInput, so sleeping would only block the session it is waiting for.
        val info = inputMethod.currentInputEditorInfo
        val connection = safeConnection(inputMethod)
        if (connection == null) {
            Log.i(
                TAG,
                "IME commit: no input connection (started=${inputMethod.currentInputStarted}, " +
                    "editorPkg=${info?.packageName}, inputType=${info?.inputType})",
            )
            return false
        }
        if (expectedPackage != null && info?.packageName != expectedPackage) {
            Log.i(TAG, "IME commit: refused, editor is ${info?.packageName}, expected $expectedPackage")
            return false
        }
        if (info != null && isPasswordInputType(info.inputType)) {
            Log.i(TAG, "IME commit: refused, editor is a password field")
            return false
        }
        return try {
            connection.commitText(text, 1, null)
            Log.i(TAG, "IME commit: commitText(${text.length} chars) sent to ${info?.packageName}")
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "IME commit: commitText threw", e)
            false
        }
    }

    /** True for every text and number password variation [android.text.InputType] defines. */
    internal fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        return when (cls) {
            android.text.InputType.TYPE_CLASS_TEXT -> variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            android.text.InputType.TYPE_CLASS_NUMBER -> variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun safeConnection(
        inputMethod: android.accessibilityservice.InputMethod,
    ): android.accessibilityservice.InputMethod.AccessibilityInputConnection? = try {
        inputMethod.currentInputConnection
    } catch (e: RuntimeException) {
        Log.w(TAG, "IME commit: currentInputConnection threw", e)
        null
    }
}
