package com.trevornk.ramblr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #344: Samsung One UI on Android 16 guards the `@hide` ACCESSIBILITY_DETAILS_SETTINGS action with
 * a signature permission, so `startActivity` throws SecurityException (not
 * ActivityNotFoundException) and crashed ServiceRecoveryActivity on launch. The shared helper must
 * fall back to the public Accessibility list on either exception.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenServiceDetailsSettingsTest {

    private val component = "com.trevornk.ramblr/com.trevornk.ramblr.WhisperAccessibilityService"

    /** Records every startActivity; throws [onDetails] for the details action only. */
    private class FakeContext(base: Context, private val onDetails: (() -> Throwable)?) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            started += intent
            if (intent.action == InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS) {
                onDetails?.let { throw it() }
            }
        }
    }

    private fun fake(onDetails: (() -> Throwable)? = null) =
        FakeContext(RuntimeEnvironment.getApplication(), onDetails)

    private fun hasNewTask(i: Intent) = i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0

    @Test fun `happy path launches only the details page with the component extra`() {
        val ctx = fake()
        InvocationSecureSettings.openServiceDetailsSettings(ctx, component = component)
        assertEquals(1, ctx.started.size)
        val i = ctx.started.single()
        assertEquals(InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS, i.action)
        assertEquals(component, i.getStringExtra(Intent.EXTRA_COMPONENT_NAME))
        assertTrue(!hasNewTask(i))
    }

    @Test fun `SecurityException on the details page falls back to the accessibility list`() {
        val ctx = fake { SecurityException("requires android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS") }
        InvocationSecureSettings.openServiceDetailsSettings(ctx, component = component)
        assertEquals(2, ctx.started.size)
        val fallback = ctx.started[1]
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, fallback.action)
        assertNull(fallback.getStringExtra(Intent.EXTRA_COMPONENT_NAME))
    }

    @Test fun `ActivityNotFoundException on the details page falls back to the accessibility list`() {
        val ctx = fake { ActivityNotFoundException("no handler") }
        InvocationSecureSettings.openServiceDetailsSettings(ctx, component = component)
        assertEquals(2, ctx.started.size)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, ctx.started[1].action)
    }

    @Test fun `newTask flag is applied to the details intent and to the fallback`() {
        val happy = fake()
        InvocationSecureSettings.openServiceDetailsSettings(happy, newTask = true, component = component)
        assertTrue(hasNewTask(happy.started.single()))

        for (failure in listOf<() -> Throwable>({ SecurityException("x") }, { ActivityNotFoundException("x") })) {
            val ctx = fake(failure)
            InvocationSecureSettings.openServiceDetailsSettings(ctx, newTask = true, component = component)
            assertEquals(2, ctx.started.size)
            assertTrue(ctx.started.all(::hasNewTask))
        }
    }

    @Test fun `default component targets the active service`() {
        val app = RuntimeEnvironment.getApplication()
        val ctx = fake()
        InvocationSecureSettings.openServiceDetailsSettings(ctx)
        assertEquals(
            InvocationSecureSettings.serviceComponent(app),
            ctx.started.single().getStringExtra(Intent.EXTRA_COMPONENT_NAME),
        )
    }
}
