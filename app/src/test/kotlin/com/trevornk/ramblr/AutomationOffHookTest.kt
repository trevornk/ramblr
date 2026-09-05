package com.trevornk.ramblr

import org.junit.Assert.assertEquals
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
}
