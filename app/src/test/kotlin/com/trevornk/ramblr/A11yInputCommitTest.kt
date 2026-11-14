package com.trevornk.ramblr

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yInputCommitTest {

    @Test fun `only Termux prefers the input connection`() {
        assertTrue(A11yInputCommit.prefersInputConnection("com.termux"))
        assertFalse(A11yInputCommit.prefersInputConnection("com.termux.api"))
        assertFalse(A11yInputCommit.prefersInputConnection("com.google.android.gm"))
        assertFalse(A11yInputCommit.prefersInputConnection(null))
    }

    @Test fun `every password variation is refused`() {
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        ).forEach { assertTrue("inputType=$it", A11yInputCommit.isPasswordInputType(it)) }
    }

    @Test fun `ordinary editors are not treated as passwords`() {
        listOf(
            InputType.TYPE_NULL, // Termux's terminal view
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_NUMBER,
        ).forEach { assertFalse("inputType=$it", A11yInputCommit.isPasswordInputType(it)) }
    }
}
