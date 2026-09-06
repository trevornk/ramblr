package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #257 automation off-hook policy. The gate is the whole security story for an exported
 * receiver, so it is pinned here rather than left to a device test.
 */
class AutomationOffHookTest {

    @Test
    fun `hook disabled ignores the broadcast even when the service is running`() {
        assertEquals(
            AutomationOffOutcome.IGNORED_DISABLED,
            resolveAutomationOff(hookEnabled = false, serviceConnected = true),
        )
    }

    @Test
    fun `hook disabled ignores the broadcast when the service is not running`() {
        assertEquals(
            AutomationOffOutcome.IGNORED_DISABLED,
            resolveAutomationOff(hookEnabled = false, serviceConnected = false),
        )
    }

    @Test
    fun `hook enabled with a connected service disables it`() {
        assertEquals(
            AutomationOffOutcome.DISABLE,
            resolveAutomationOff(hookEnabled = true, serviceConnected = true),
        )
    }

    @Test
    fun `hook enabled with no connected service is a no-op`() {
        assertEquals(
            AutomationOffOutcome.IGNORED_NOT_RUNNING,
            resolveAutomationOff(hookEnabled = true, serviceConnected = false),
        )
    }

    /**
     * The disabled check must come first, so an outside caller cannot use the outcome to learn
     * whether Ramblr's service is running while the hook is off.
     */
    @Test
    fun `disabled hook reports the same outcome regardless of service state`() {
        assertEquals(
            resolveAutomationOff(hookEnabled = false, serviceConnected = true),
            resolveAutomationOff(hookEnabled = false, serviceConnected = false),
        )
    }

    // --- ordered-broadcast result-code mapping ------------------------------------------------
    //
    // `am broadcast` always sends an ordered broadcast and prints `result=<code>`, but the
    // receiver never called setResultCode(), so every outcome -- gate disabled, no live service,
    // or a real disable -- printed the same default result=0. That made a genuinely accepted
    // disable indistinguishable from a silent no-op, which is exactly backwards: the *disabled*
    // gate is the one outcome that must stay silent (no service-state disclosure to an arbitrary
    // caller), while an accepted disable is useful signal for the automation tool and should NOT
    // collide with the "nothing happened" code.

    @Test
    fun `disabled-gate outcome reports the same result code regardless of service state`() {
        // This is the privacy invariant: a caller that doesn't know the hook is off must not be
        // able to distinguish "hook off, service running" from "hook off, service not running"
        // by result code alone.
        assertEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED),
            resultCodeFor(resolveAutomationOff(hookEnabled = false, serviceConnected = true)),
        )
        assertEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED),
            resultCodeFor(resolveAutomationOff(hookEnabled = false, serviceConnected = false)),
        )
    }

    @Test
    fun `disabled-gate result code matches am broadcast's own default no-match code`() {
        // RESULT_HOOK_DISABLED must equal Android's default ordered-broadcast result (0) --
        // the same code a caller already sees for a mistyped action or missing component, so a
        // disabled hook is indistinguishable from "nothing there at all", not a new tell.
        assertEquals(0, resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED))
    }

    @Test
    fun `an accepted disable does not collide with the disabled-gate result code`() {
        assertNotEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED),
            resultCodeFor(AutomationOffOutcome.DISABLE),
        )
    }

    @Test
    fun `hook-enabled-but-not-running does not collide with the disabled-gate result code`() {
        // The hook is on, so the caller already knows Ramblr's off-hook is opted in (they got
        // the command from Ramblr's own settings screen) -- telling them "nothing was running"
        // here is not a new disclosure the way it would be while the gate itself is off.
        assertNotEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED),
            resultCodeFor(AutomationOffOutcome.IGNORED_NOT_RUNNING),
        )
    }

    @Test
    fun `every outcome maps to a distinct result code except the disabled gate`() {
        assertNotEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_NOT_RUNNING),
            resultCodeFor(AutomationOffOutcome.DISABLE),
        )
    }

    // --- numeric hosting user derivation ------------------------------------------------------
    //
    // The copied `am broadcast` command hardcoded no --user flag (which `am` resolves to user 0
    // from a shell context), so it only worked when the caller's own user happened to be 0.
    // userIdForUid must derive the ACTUAL hosting user from a uid, the same partitioning
    // UserHandle uses internally (uid = userId * 100000 + appId), not assume 0 and not require
    // any permission to compute. This is preventive hardening for multi-user/work-profile hosts,
    // not a reproduction of reporter #257's setup, which was a normal per-app uid on the primary
    // user (user 0).

    @Test
    fun `uid on the primary user maps to user 0`() {
        // A typical app uid on user 0, e.g. u0_a380 -- matches #257 reporter's actual uid shape.
        assertEquals(0, userIdForUid(10380))
    }

    @Test
    fun `uid on a secondary user maps to that user's numeric id`() {
        // Same appId, hosted on a non-zero user, e.g. u10_a380.
        assertEquals(10, userIdForUid(1010380))
    }

    @Test
    fun `uid on user 2 maps to 2`() {
        assertEquals(2, userIdForUid(200380))
    }

    // --- am broadcast command copy ----------------------------------------------------------
    //
    // The command shown to the user must explicitly target the numeric user hosting Ramblr, not
    // rely on am's no-flag default (user 0) or --user current (needs INTERACT_ACROSS_USERS a
    // same-user shell/automation caller does not have).

    @Test
    fun `command targets the given numeric user explicitly`() {
        val command = automationOffHookCommand("com.trevornk.ramblr", userId = 10)
        assertEquals(
            "am broadcast -a com.trevornk.ramblr.action.TURN_OFF " +
                "-n com.trevornk.ramblr/.AutomationOffReceiver --user 10",
            command,
        )
    }

    @Test
    fun `command for user 0 still targets it explicitly rather than omitting --user`() {
        val command = automationOffHookCommand("com.trevornk.ramblr", userId = 0)
        assertTrue("must not omit --user even for the primary user", command.contains("--user 0"))
    }

    @Test
    fun `command never uses --user current`() {
        // --user current resolves via USER_CURRENT (-2) and requires INTERACT_ACROSS_USERS,
        // a permission a same-user shell/automation caller does not have.
        val command = automationOffHookCommand("com.trevornk.ramblr", userId = 7)
        assertTrue(!command.contains("current"))
    }

    // --- disableServiceFromApp() return value gating --------------------------------------

    @Test
    fun `an accepted disable attempt reports RESULT_DISABLE_REQUESTED`() {
        assertEquals(RESULT_DISABLE_REQUESTED, resultCodeForDisableAttempt(disabled = true))
    }

    @Test
    fun `a disable attempt that found nothing live reports RESULT_NOT_RUNNING, not an accepted disable`() {
        // disableServiceFromApp() can return false if the live instance disappeared between the
        // receiver's serviceConnected check and the call. The result code must reflect what the
        // invocation actually returned, not what resolveAutomationOff optimistically decided.
        assertEquals(RESULT_NOT_RUNNING, resultCodeForDisableAttempt(disabled = false))
    }
}
