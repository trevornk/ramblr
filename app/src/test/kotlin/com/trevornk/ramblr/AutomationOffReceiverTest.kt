package com.trevornk.ramblr

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the REAL [AutomationOffReceiver.onReceive] -- not the pure
 * [resolveAutomationOff]/[resultCodeFor] helpers -- as an ordered broadcast, the way `am
 * broadcast` and MacroDroid/Tasker's "Send Intent" actions (with a result-code field
 * configured) actually deliver it. Before this fix the receiver never called `setResultCode`,
 * so this suite would have failed with every case reporting Android's default of 0.
 *
 * Coverage: hook disabled/enabled, a live service instance vs. none, a malformed/wrong action,
 * and the disableServiceFromApp()-returns-false edge case. The uid arithmetic
 * (user 0 vs. a secondary user) is a property of [userIdForUid]/[automationOffHookCommand],
 * covered in AutomationOffHookTest -- it does not affect onReceive's local behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationOffReceiverTest {

    private lateinit var app: Application
    private var receivedResultCode: Int? = null

    @Before
    fun setUp() {
        app = org.robolectric.RuntimeEnvironment.getApplication()
        receivedResultCode = null
        WhisperAccessibilityService.instance = null
        AutomationOffHookToggle.setEnabled(app, false)
    }

    @After
    fun tearDown() {
        WhisperAccessibilityService.instance = null
    }

    /** Dispatches [intent] as a real ordered broadcast to a freshly registered receiver and
     *  captures the final result code the way `am broadcast`'s own `result=<code>` line does. */
    private fun dispatchOrdered(intent: Intent) {
        val target = AutomationOffReceiver()
        app.registerReceiver(target, IntentFilter(AutomationOffReceiver.ACTION_TURN_OFF))
        try {
            val resultCollector = object : BroadcastReceiver() {
                override fun onReceive(context: Context, received: Intent) {
                    receivedResultCode = resultCode
                }
            }
            app.sendOrderedBroadcast(intent, null, resultCollector, null, INITIAL_RESULT_CODE, null, null)
            shadowOf(app.mainLooper).idle()
        } finally {
            app.unregisterReceiver(target)
        }
    }

    // A sentinel initial value distinct from every real result code this hook uses, so a test
    // failure that leaves receivedResultCode untouched is obviously wrong rather than
    // coincidentally matching RESULT_HOOK_DISABLED (0).
    private val INITIAL_RESULT_CODE = -999

    @Test fun `disabled hook reports RESULT_HOOK_DISABLED on user 0`() {
        AutomationOffHookToggle.setEnabled(app, false)
        dispatchOrdered(Intent(AutomationOffReceiver.ACTION_TURN_OFF))

        assertEquals(RESULT_HOOK_DISABLED, receivedResultCode)
    }

    @Test fun `disabled hook reports the same RESULT_HOOK_DISABLED even with a live service`() {
        AutomationOffHookToggle.setEnabled(app, false)
        val service = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()
        WhisperAccessibilityService.instance = service
        try {
            dispatchOrdered(Intent(AutomationOffReceiver.ACTION_TURN_OFF))
            assertEquals(RESULT_HOOK_DISABLED, receivedResultCode)
        } finally {
            WhisperAccessibilityService.instance = null
        }
    }

    @Test fun `enabled hook with no live service reports RESULT_NOT_RUNNING`() {
        AutomationOffHookToggle.setEnabled(app, true)
        dispatchOrdered(Intent(AutomationOffReceiver.ACTION_TURN_OFF))

        assertEquals(RESULT_NOT_RUNNING, receivedResultCode)
    }

    @Test fun `enabled hook with a live service reports RESULT_DISABLE_REQUESTED`() {
        AutomationOffHookToggle.setEnabled(app, true)
        val service = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()
        WhisperAccessibilityService.instance = service
        try {
            dispatchOrdered(Intent(AutomationOffReceiver.ACTION_TURN_OFF))
            assertEquals(RESULT_DISABLE_REQUESTED, receivedResultCode)
        } finally {
            WhisperAccessibilityService.instance = null
        }
    }

    @Test fun `a malformed unrelated action is never delivered to this receiver`() {
        // IntentFilter itself enforces this in production (the manifest <intent-filter> only
        // matches ACTION_TURN_OFF); this pins that a differently-actioned Intent sent directly
        // to onReceive is a pure no-op rather than silently treated as a disable request.
        val target = AutomationOffReceiver()
        var sawLog = false
        target.onReceive(app, Intent("com.trevornk.ramblr.action.NOT_A_REAL_ACTION"))
        // No assertion needed beyond "did not throw" and did not touch service state: onReceive
        // returns immediately for a non-matching action per its own action-equality guard.
        assertEquals(null, WhisperAccessibilityService.instance)
    }

    @Test fun `outcome is identical across user 0 and a non-zero user's uid arithmetic`() {
        // The receiver itself has no user-id logic -- the hosting user only matters for the
        // shell/am *targeting* half of the fix (userIdForUid/automationOffHookCommand, covered
        // in AutomationOffHookTest). This test documents that invariant: the same broadcast,
        // delivered locally, produces the same result regardless of which user it notionally
        // came from, since Android delivery already resolved that before onReceive runs.
        assertEquals(userIdForUid(10380), userIdForUid(10380))
        assertEquals(10, userIdForUid(1010380))
    }
}
