package com.trevornk.ramblr

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #254 investigation: unit-level coverage of a DEFENSIVE guard, not a proven fix.
 *
 * [WhisperAccessibilityService.instance] is the SINGLE signal the rest of the app (MainActivity's
 * "acc" status, the guard rail, the Quick Settings tile, [WhisperAccessibilityService.requestToggleRecording])
 * uses to decide whether Ramblr's accessibility service is actually running -- see e.g.
 * MainActivity.refresh(): `val acc = WhisperAccessibilityService.instance != null`.
 *
 * This test simulates, by directly setting the companion field (NOT by driving real Android
 * lifecycle callbacks or a real bind/rebind), a hypothetical ordering where a stale instance A's
 * onDestroy() runs AFTER a newer instance B's connection already claimed `instance`. It proves
 * only that IF that ordering ever occurs, the identity check in onDestroy() prevents A from
 * clobbering B's reference. It does NOT prove that ordering is reachable on real Android: a
 * bounded on-device disable/enable harness (Pixel 10a, six cycles, see
 * scripts/254_lifecycle_harness.sh and the #254 investigation notes) did not reproduce two
 * overlapping live instances, one dumpsys ServiceRecord was present after every re-enable, and
 * AOSP's ActiveServices.bringDownServiceLocked()/realStartServiceLocked() ordering (read during
 * this investigation) appears to serialize the old instance's scheduleStopService() strictly
 * before a new bringUpServiceLocked() for the same ServiceRecord/instanceName. Treat the
 * production change under test as a defensive no-op pending stronger reproduction evidence, not
 * as a confirmed fix for any #254 report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhisperAccessibilityServiceLifecycleTest {

    @Before
    fun setUp() {
        WhisperAccessibilityService.instance = null
    }

    @After
    fun tearDown() {
        WhisperAccessibilityService.instance = null
    }

    @Test
    fun `a superseded instance's onDestroy must not clear a newer live instance`() {
        val controllerA = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create()
        val serviceA = controllerA.get()
        val controllerB = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create()
        val serviceB = controllerB.get()

        // Simulate a HYPOTHETICAL lifecycle order, NOT one confirmed to occur on real Android
        // (see the class kdoc): A connects, then B connects, and only THEN does A's onDestroy()
        // run. Setting the companion field directly (rather than invoking the real
        // onServiceConnected(), which drives window/overlay attachment and background
        // model-load threads well outside this test's scope -- see AutomationOffReceiverTest for
        // the same established pattern) isolates exactly the piece of lifecycle logic under
        // test: what onDestroy() does to `instance` if this ordering were ever to occur.
        WhisperAccessibilityService.instance = serviceA
        WhisperAccessibilityService.instance = serviceB
        assertSame("serviceB should be the live instance after it connects", serviceB, WhisperAccessibilityService.instance)

        controllerA.destroy()

        assertSame(
            "stale onDestroy() from the superseded instance A must not clobber the live instance B",
            serviceB,
            WhisperAccessibilityService.instance,
        )
    }

    @Test
    fun `the current instance's own onDestroy still clears the reference`() {
        val controllerA = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create()
        val serviceA = controllerA.get()
        WhisperAccessibilityService.instance = serviceA
        assertSame(serviceA, WhisperAccessibilityService.instance)

        controllerA.destroy()

        assertSame(null, WhisperAccessibilityService.instance)
    }
}
