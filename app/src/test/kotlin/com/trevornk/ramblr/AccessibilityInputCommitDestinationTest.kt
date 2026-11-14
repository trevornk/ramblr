package com.trevornk.ramblr

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #378: [AccessibilityTextDestination.commitViaInputConnection] gating. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityInputCommitDestinationTest {

    private fun node(focused: Boolean, password: Boolean = false) = AccessibilityNodeInfo().apply {
        isFocused = focused
        isPassword = password
    }

    @Test fun `commits through the input connection for a focused node`() {
        val sent = mutableListOf<String>()
        val destination = AccessibilityTextDestination(node(focused = true), insertAtCaret = { sent += it; true })

        assertTrue(destination.commitViaInputConnection("hello"))
        assertEquals(listOf("hello"), sent)
    }

    @Test fun `refuses a password field without calling the input connection`() {
        val sent = mutableListOf<String>()
        val destination = AccessibilityTextDestination(node(focused = true, password = true), insertAtCaret = { sent += it; true })

        assertFalse(destination.commitViaInputConnection("secret"))
        assertTrue(sent.isEmpty())
    }

    @Test fun `refuses an unfocused node because the connection belongs to the focused editor`() {
        val sent = mutableListOf<String>()
        val destination = AccessibilityTextDestination(node(focused = false), insertAtCaret = { sent += it; true })

        assertFalse(destination.commitViaInputConnection("hello"))
        assertTrue(sent.isEmpty())
    }

    @Test fun `does nothing without an input commit hook`() {
        assertFalse(AccessibilityTextDestination(node(focused = true)).commitViaInputConnection("hello"))
    }

    @Test fun `reports the hook's refusal so the writer falls back to paste`() {
        val destination = AccessibilityTextDestination(node(focused = true), insertAtCaret = { false })

        assertFalse(destination.commitViaInputConnection("hello"))
    }
}
