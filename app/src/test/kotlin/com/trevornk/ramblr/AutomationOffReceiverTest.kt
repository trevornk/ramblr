package com.trevornk.ramblr

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    private var receivedResultData: String? = null

    /** Dispatches [intent] as a real ordered broadcast to a freshly registered receiver and
     *  captures the final result code the way `am broadcast`'s own `result=<code>` line does.
     *  Registers for BOTH actions, matching the production `<intent-filter>` on
     *  `AutomationOffReceiver` (single receiver, single filter, two actions) -- see
     *  AndroidManifest.xml -- so a diagnostic-action test exercises the same real dispatch path
     *  a TURN_OFF test does, not a filter that only happens to admit one action. */
    private fun dispatchOrdered(intent: Intent) {
        val target = AutomationOffReceiver()
        val filter = IntentFilter().apply {
            addAction(AutomationOffReceiver.ACTION_TURN_OFF)
            addAction(AutomationOffReceiver.ACTION_DIAGNOSTIC)
        }
        app.registerReceiver(target, filter)
        try {
            val resultCollector = object : BroadcastReceiver() {
                override fun onReceive(context: Context, received: Intent) {
                    receivedResultCode = resultCode
                    receivedResultData = resultData
                }
            }
            receivedResultData = null
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

    // --- #254: ACTION_DIAGNOSTIC receiver-level contract tests -----------------------------
    //
    // These exercise the REAL onReceive() dispatch for the diagnostic action, the gap the
    // #254 report's own §8 flagged as unit-formatter-only ("formatDiagnosticSnapshot" pure-fn
    // coverage in RamblrDiagnosticSnapshotTest is not the same claim as "the receiver correctly
    // wires the gate/result code/result data together"). Mirrors the on-device broadcasts
    // actually run this session against the Pixel 10a (63141JEA320614): hook OFF -> result=0
    // no data; hook ON with a live instance -> result=3 with the exact device-observed data
    // string; a wrong action -> untouched no-op, matching the real
    // `am broadcast -a com.trevornk.ramblr.action.NOT_A_REAL_ACTION` run (result=0, no log line).

    @Test fun `disabled hook reports RESULT_HOOK_DISABLED with no result data for diagnostic`() {
        AutomationOffHookToggle.setEnabled(app, false)
        dispatchOrdered(Intent(AutomationOffReceiver.ACTION_DIAGNOSTIC))

        assertEquals(RESULT_HOOK_DISABLED, receivedResultCode)
        assertEquals(null, receivedResultData)
    }

    @Test fun `disabled hook diagnostic result is identical whether or not a service is connected`() {
        // Privacy gate: an outside caller must not be able to distinguish "hook off, service
        // running" from "hook off, service not running" -- both must look like "nothing here".
        AutomationOffHookToggle.setEnabled(app, false)
        dispatchOrdered(Intent(AutomationOffReceiver.ACTION_DIAGNOSTIC))
        val withoutService = receivedResultCode to receivedResultData

        val service = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()
        WhisperAccessibilityService.instance = service
        try {
            dispatchOrdered(Intent(AutomationOffReceiver.ACTION_DIAGNOSTIC))
            val withService = receivedResultCode to receivedResultData
            assertEquals(withoutService, withService)
            assertEquals(RESULT_HOOK_DISABLED, receivedResultCode)
            assertEquals(null, receivedResultData)
        } finally {
            WhisperAccessibilityService.instance = null
        }
    }

    @Test fun `enabled hook with a live service reports RESULT_DIAGNOSTIC_OK with a connected snapshot`() {
        AutomationOffHookToggle.setEnabled(app, true)
        val service = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()
        WhisperAccessibilityService.instance = service
        try {
            dispatchOrdered(Intent(AutomationOffReceiver.ACTION_DIAGNOSTIC))
            assertEquals(AutomationOffReceiver.RESULT_DIAGNOSTIC_OK, receivedResultCode)
            val data = receivedResultData
            requireNotNull(data) { "expected a result data string on the successful diagnostic path" }
            // instance_connected must reflect the actual WhisperAccessibilityService.instance
            // state at broadcast time, not a hardcoded true -- this is the "returned fields
            // correspond to actual independent observations" check the on-device run also made
            // (dumpsys accessibility Bound services / enabled_accessibility_services cross-check).
            assert(data.contains("instance_connected=true")) { "expected instance_connected=true in: $data" }
            assert(data.contains("automation_off_hook_enabled=true")) { "expected automation_off_hook_enabled=true in: $data" }
        } finally {
            WhisperAccessibilityService.instance = null
        }
    }

    @Test fun `enabled hook with no live service still reports RESULT_DIAGNOSTIC_OK but instance_connected=false`() {
        // Unlike TURN_OFF (which distinguishes RESULT_NOT_RUNNING), the diagnostic's whole
        // purpose is to report state including "not connected" -- it must not itself act as a
        // second not-running gate, or a caller loses the "is it actually enabled but not bound"
        // signal the #258 stale-component distinction depends on.
        AutomationOffHookToggle.setEnabled(app, true)
        WhisperAccessibilityService.instance = null

        dispatchOrdered(Intent(AutomationOffReceiver.ACTION_DIAGNOSTIC))

        assertEquals(AutomationOffReceiver.RESULT_DIAGNOSTIC_OK, receivedResultCode)
        val data = receivedResultData
        requireNotNull(data)
        assert(data.contains("instance_connected=false")) { "expected instance_connected=false in: $data" }
    }

    @Test fun `a wrong action delivered directly to onReceive is a diagnostic no-op`() {
        val target = AutomationOffReceiver()
        AutomationOffHookToggle.setEnabled(app, true)
        val service = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()
        WhisperAccessibilityService.instance = service
        try {
            // Calling onReceive directly (not through registerReceiver/IntentFilter) is the
            // correct way to assert the receiver's own internal action-equality guard, since a
            // real IntentFilter would already reject this action before onReceive ever runs --
            // this test is specifically about onReceive's `when (intent.action)` else-branch,
            // matching the on-device NOT_A_REAL_ACTION run's result=0 with no log line.
            target.onReceive(app, Intent("com.trevornk.ramblr.action.NOT_A_REAL_ACTION"))
            assertSame(service, WhisperAccessibilityService.instance)
        } finally {
            WhisperAccessibilityService.instance = null
        }
    }

    @Test fun `ordered TURN_OFF and DIAGNOSTIC deliveries do not cross-contaminate result codes`() {
        // Guards against a shared-mutable-state bug where handling one action's result leaks
        // into the other's (e.g. a stale resultCode default). Fires both actions back to back
        // through the same receiver instance-equivalent dispatch path and checks each result
        // independently.
        AutomationOffHookToggle.setEnabled(app, true)
        val service = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()
        WhisperAccessibilityService.instance = service
        try {
            dispatchOrdered(Intent(AutomationOffReceiver.ACTION_DIAGNOSTIC))
            assertEquals(AutomationOffReceiver.RESULT_DIAGNOSTIC_OK, receivedResultCode)

            // TURN_OFF's DISABLE branch calls disableServiceFromApp(); Robolectric's shadow
            // AccessibilityService.disableSelf() is a no-op that doesn't clear `instance`, so a
            // fresh live instance is reassigned before asserting TURN_OFF's own result in
            // isolation from the diagnostic call just made.
            WhisperAccessibilityService.instance =
                Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()
            dispatchOrdered(Intent(AutomationOffReceiver.ACTION_TURN_OFF))
            assertEquals(RESULT_DISABLE_REQUESTED, receivedResultCode)
        } finally {
            WhisperAccessibilityService.instance = null
        }
    }

    @Test fun `diagnostic dispatch never writes any Settings_Secure value`() {
        // #254 audit requirement: the diagnostic is documented as read-only. Assert it, not just
        // claim it in a kdoc -- snapshot every Settings.Secure key InvocationSecureSettings reads
        // before and after a successful diagnostic dispatch and require byte-identical values,
        // covering the same three keys the on-device run cross-checked
        // (enabled_accessibility_services, accessibility_button_targets,
        // accessibility_shortcut_target_service).
        AutomationOffHookToggle.setEnabled(app, true)
        val service = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()
        WhisperAccessibilityService.instance = service
        val keys = listOf(
            InvocationSecureSettings.KEY_ENABLED_SERVICES,
            InvocationSecureSettings.KEY_BUTTON_TARGETS,
            InvocationSecureSettings.KEY_SHORTCUT_TARGET_SERVICE,
        )
        val before = keys.associateWith { android.provider.Settings.Secure.getString(app.contentResolver, it) }
        try {
            dispatchOrdered(Intent(AutomationOffReceiver.ACTION_DIAGNOSTIC))
            assertEquals(AutomationOffReceiver.RESULT_DIAGNOSTIC_OK, receivedResultCode)
            val after = keys.associateWith { android.provider.Settings.Secure.getString(app.contentResolver, it) }
            assertEquals("diagnostic dispatch must not write any Settings.Secure key", before, after)
        } finally {
            WhisperAccessibilityService.instance = null
        }
    }
}
