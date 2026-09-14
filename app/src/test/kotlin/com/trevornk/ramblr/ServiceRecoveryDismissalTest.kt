package com.trevornk.ramblr

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #254: the dismissal contract for the out-of-app recovery notification.
 *
 * The pure post/suppress decision lives in [shouldPostServiceRecoveryNotification]
 * (InvocationMethodsTest); what is covered here is the STATEFUL half those inputs come from --
 * that a swipe is actually persisted, and that it is scoped to the current loss rather than
 * silencing every future one. That scoping is the whole reason the flag is cleared inside
 * [InvocationGuardRail.recordServiceConnected] rather than being a standalone preference, and a
 * regression there would be invisible in the pure tests: every case would still pass while the
 * user silently stopped being told about real failures forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceRecoveryDismissalTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `a fresh install has no dismissal recorded`() {
        assertFalse(InvocationGuardRail.recoveryNotificationDismissed(app))
    }

    @Test
    fun `the dismiss receiver persists the swipe`() {
        ServiceRecoveryDismissReceiver().onReceive(
            app, Intent(ServiceRecoveryDismissReceiver.ACTION_DISMISSED),
        )
        assertTrue(InvocationGuardRail.recoveryNotificationDismissed(app))
    }

    @Test
    fun `the dismiss receiver ignores a broadcast with a different action`() {
        ServiceRecoveryDismissReceiver().onReceive(app, Intent("com.trevornk.ramblr.action.NOT_REAL"))
        assertFalse(InvocationGuardRail.recoveryNotificationDismissed(app))
    }

    @Test
    fun `the service reconnecting re-arms a dismissed notification for the next failure`() {
        // The core non-nagging contract: a swipe silences THIS loss. Once the service is
        // genuinely working again, a LATER loss is a new event the user must hear about --
        // otherwise one dismissal would permanently disable the only out-of-app signal that
        // Ramblr has stopped working.
        InvocationGuardRail.recordRecoveryNotificationDismissed(app)
        assertTrue(InvocationGuardRail.recoveryNotificationDismissed(app))

        InvocationGuardRail.recordServiceConnected(app)

        assertFalse(InvocationGuardRail.recoveryNotificationDismissed(app))
    }

    @Test
    fun `reconnecting clears the automation-off flag alongside the notification dismissal`() {
        // Both flags are re-armed by the same connect, and for the same reason. Asserted together
        // because dropping either key from recordServiceConnected's edit() is an easy mistake
        // that leaves the other silently stuck.
        InvocationGuardRail.recordUserTurnedOff(app)
        InvocationGuardRail.recordRecoveryNotificationDismissed(app)

        InvocationGuardRail.recordServiceConnected(app)

        assertFalse(InvocationGuardRail.recoveryNotificationDismissed(app))
        assertFalse(
            app.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE)
                .getBoolean(InvocationGuardRail.KEY_USER_TURNED_OFF, false)
        )
    }
}
