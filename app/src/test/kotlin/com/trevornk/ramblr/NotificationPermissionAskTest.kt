package com.trevornk.ramblr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The notification prompt used to come back on every launch until granted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPermissionAskTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Test fun `asked automatically only once, and never before Android 13 or once granted`() {
        assertTrue(NotificationPermissionAsk.shouldAutoAsk(33, granted = false, alreadyAsked = false))
        assertFalse(NotificationPermissionAsk.shouldAutoAsk(33, granted = false, alreadyAsked = true))
        assertFalse(NotificationPermissionAsk.shouldAutoAsk(33, granted = true, alreadyAsked = false))
        assertFalse(NotificationPermissionAsk.shouldAutoAsk(32, granted = false, alreadyAsked = false))
    }

    @Test fun `a second launch of a returning user does not ask again`() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("onboarding_complete", true).apply()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)

        fun launchAsks(): Boolean {
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val request = shadowOf(activity).lastRequestedPermission
            return request?.requestedPermissions?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
        }

        assertTrue("first launch", launchAsks())
        assertTrue(NotificationPermissionAsk.alreadyAsked(app))
        assertFalse("second launch", launchAsks())
    }

    @Test fun `setup has a notifications row that opens the app's notification settings once asked`() {
        NotificationPermissionAsk.markAsked(app)
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        fun View.all(): Sequence<View> = sequence {
            yield(this@all)
            if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
        }
        val title = activity.window.decorView.all().filterIsInstance<TextView>().first { it.text.toString() == "Notifications" }
        val row = generateSequence(title as View) { it.parent as? View }.first { it.isClickable }
        val subtitle = (row as ViewGroup).all().filterIsInstance<TextView>().first { it.tag == "subtitle" }
        assertTrue(subtitle.text.startsWith("Off"))

        row.performClick()

        val started: Intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
