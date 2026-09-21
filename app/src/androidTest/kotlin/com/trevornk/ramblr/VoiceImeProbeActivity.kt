package com.trevornk.ramblr

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** A disposable, no-content test editor used only to show the isolated voice IME. */
class VoiceImeProbeActivity : Activity() {
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = EditText(this).apply {
            hint = "Issue 270 test editor"
            requestFocus()
        }
        setContentView(editor)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            editor.post {
                (getSystemService(InputMethodManager::class.java))?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}