package com.trevornk.ramblr

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug-only IME field harness (#233 gates 6 and 7).
 *
 * A WebView cannot express every EditorInfo contract we need to verify: HTML has no way to set
 * IME_FLAG_NO_PERSONALIZED_LEARNING, so gate 7 is unverifiable in a browser page. This activity
 * declares the exact editor flags natively.
 *
 * It exists only in debug builds (app/src/debug) and is never present in a release APK.
 *
 * Launch:
 *   adb -s <serial> shell am start -n com.trevornk.ramblr/.ImeTestHarnessActivity
 */
class ImeTestHarnessActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        root.addView(heading("IME field harness (debug)"))

        root.addView(label("A. normal — dictation allowed, retained"))
        root.addView(field("harness_normal", InputType.TYPE_CLASS_TEXT))

        root.addView(label("B. password — mic must be disabled"))
        root.addView(
            field(
                "harness_password",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            )
        )

        root.addView(label("C. no personalized learning — dictation OK, NO history"))
        root.addView(
            field(
                "harness_nolearn",
                InputType.TYPE_CLASS_TEXT,
                imeFlags = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            )
        )

        root.addView(label("D. second normal field — editor-switch target"))
        root.addView(field("harness_second", InputType.TYPE_CLASS_TEXT))

        setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 24)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 24, 0, 4)
    }

    private fun field(tag: String, inputType: Int, imeFlags: Int = 0) = EditText(this).apply {
        this.tag = tag
        contentDescription = tag
        this.inputType = inputType
        // imeOptions carries the privacy flag under test; keep the action explicit so the flag is
        // the only thing distinguishing this field from the ordinary one.
        this.imeOptions = EditorInfo.IME_ACTION_DONE or imeFlags
        textSize = 16f
    }
}
