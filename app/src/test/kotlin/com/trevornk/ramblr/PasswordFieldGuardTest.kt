package com.trevornk.ramblr

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The password-field policy behind the floating icon: pure decisions, no Android runtime. */
class PasswordFieldGuardTest {

    private val passwordTypes = listOf(
        "text password" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        "visible password" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
        "web password" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
        "numeric PIN" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
    )

    private val ordinaryTypes = listOf(
        "plain text" to InputType.TYPE_CLASS_TEXT,
        "multi-line text" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
        "email address" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        "plain number" to InputType.TYPE_CLASS_NUMBER,
        "unspecified" to 0,
    )

    @Test fun `every password input type variation is a password field`() {
        passwordTypes.forEach { (name, type) ->
            assertTrue(name, isPasswordInputType(type))
            assertTrue(name, isPasswordField(flaggedPassword = false, inputType = type))
        }
    }

    @Test fun `a node flagged isPassword is a password field whatever its input type`() {
        ordinaryTypes.forEach { (name, type) ->
            assertTrue(name, isPasswordField(flaggedPassword = true, inputType = type))
        }
    }

    @Test fun `ordinary fields are not password fields`() {
        ordinaryTypes.forEach { (name, type) ->
            assertFalse(name, isPasswordInputType(type))
            assertFalse(name, isPasswordField(flaggedPassword = false, inputType = type))
        }
    }

    @Test fun `the keyboard and the floating icon share one predicate`() {
        (passwordTypes + ordinaryTypes).forEach { (name, type) ->
            assertEquals(name, isPasswordInputType(type), !imeEditorPolicy(type, 0).allowsDictation)
        }
    }

    @Test fun `a new recording is refused only when idle and the focused field is a password field`() {
        val idle = RecordingStateMachine.State.IDLE
        assertTrue(PasswordFieldGuard.shouldRefuseRecordingStart(idle, focusedIsPassword = true))
        assertFalse(PasswordFieldGuard.shouldRefuseRecordingStart(idle, focusedIsPassword = false))
        // An unknown state (service not ready) is never refused on this rule's account.
        assertFalse(PasswordFieldGuard.shouldRefuseRecordingStart(null, focusedIsPassword = true))
    }

    @Test fun `stop and cancel stay reachable while a password field is focused`() {
        RecordingStateMachine.State.values()
            .filter { it != RecordingStateMachine.State.IDLE }
            .forEach { state ->
                assertFalse(state.name, PasswordFieldGuard.shouldRefuseRecordingStart(state, focusedIsPassword = true))
            }
    }

    @Test fun `a result is discarded when focus is on a password field`() {
        assertEquals(
            DictationResultRoute.DISCARD_PASSWORD_FIELD,
            PasswordFieldGuard.routeResult(focusedIsPassword = true, capturedTargetIsPassword = false),
        )
    }

    @Test fun `a result is discarded when it was captured in a password field even if focus moved on`() {
        assertEquals(
            DictationResultRoute.DISCARD_PASSWORD_FIELD,
            PasswordFieldGuard.routeResult(focusedIsPassword = false, capturedTargetIsPassword = true),
        )
    }

    @Test fun `a result for an ordinary field is delivered`() {
        assertEquals(
            DictationResultRoute.DELIVER,
            PasswordFieldGuard.routeResult(focusedIsPassword = false, capturedTargetIsPassword = false),
        )
    }

    @Test fun `live partials and injection candidates exclude password fields only`() {
        assertFalse(PasswordFieldGuard.allowsPartialWrite(focusedIsPassword = true))
        assertTrue(PasswordFieldGuard.allowsPartialWrite(focusedIsPassword = false))
        assertFalse(PasswordFieldGuard.allowsInjectionCandidate(nodeIsPassword = true))
        assertTrue(PasswordFieldGuard.allowsInjectionCandidate(nodeIsPassword = false))
    }
}
