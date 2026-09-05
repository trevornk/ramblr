package com.trevornk.ramblr

import android.content.Context
import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * #260 regression coverage for overlay window attach/teardown.
 *
 * The defect was ordering, not just the missing catch. `showOverlay()` ran both `wm.addView()`
 * calls and only then assigned `overlayView`/`feedbackView`. A throw on the second call left the
 * first window attached while `overlayView` was still null, and `removeOverlay()` only removes
 * through those null-guarded fields -- so nothing could ever remove it. The window leaked for
 * the life of the process, and because `showOverlay()` runs from `onServiceConnected()`, the
 * uncaught exception killed the service.
 *
 * These drive the real [WhisperAccessibilityService.showOverlay] and
 * [WhisperAccessibilityService.removeOverlay] against a WindowManager that throws where the
 * framework actually throws, rather than re-modelling the logic in the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayAttachGuardTest {

    /**
     * The real service with its WindowManager swapped for one that can fail on a chosen call.
     * [WindowManager.BadTokenException] is what the framework raises when the accessibility-service
     * connection backing a TYPE_ACCESSIBILITY_OVERLAY token is torn down mid-attach -- exactly what
     * an automation app toggling the service off during connect produces.
     */
    open class TestService(
        private val failOnAdd: Int = -1,
        private val throwOnRemove: Boolean = false
    ) : WhisperAccessibilityService() {
        var addCalls = 0
        var removeCalls = 0
        val attached = mutableListOf<View>()
        private var wrapped: WindowManager? = null

        override fun getSystemService(name: String): Any? {
            if (name != Context.WINDOW_SERVICE) return super.getSystemService(name)
            if (wrapped == null) {
                val real = super.getSystemService(name) as WindowManager
                wrapped = object : WindowManager by real {
                    override fun addView(view: View, params: android.view.ViewGroup.LayoutParams) {
                        addCalls++
                        if (addCalls == failOnAdd) {
                            throw WindowManager.BadTokenException("token invalid: connection torn down")
                        }
                        real.addView(view, params)
                        attached += view
                    }

                    override fun removeView(view: View) {
                        removeCalls++
                        if (throwOnRemove) {
                            throw IllegalArgumentException("View=$view not attached to window manager")
                        }
                        real.removeView(view)
                        attached -= view
                    }
                }
            }
            return wrapped
        }
    }

    class FailSecondAdd : TestService(failOnAdd = 2)
    class FailFirstAdd : TestService(failOnAdd = 1)
    class FailRemove : TestService(throwOnRemove = true)

    private fun field(name: String): Field =
        WhisperAccessibilityService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun overlayView(service: WhisperAccessibilityService) = field("overlayView").get(service)
    private fun feedbackView(service: WhisperAccessibilityService) = field("feedbackView").get(service)

    private fun <T : WhisperAccessibilityService> build(cls: Class<T>): T =
        Robolectric.buildService(cls, null).create().get()

    private fun showOverlay(service: WhisperAccessibilityService) {
        WhisperAccessibilityService::class.java
            .getDeclaredMethod("showOverlay").apply { isAccessible = true }
            .invoke(service)
    }

    private fun removeOverlay(service: WhisperAccessibilityService) {
        WhisperAccessibilityService::class.java
            .getDeclaredMethod("removeOverlay").apply { isAccessible = true }
            .invoke(service)
    }

    // --- happy path ---

    @Test fun `both windows attach and are tracked`() {
        val service = build(TestService::class.java)

        showOverlay(service)

        assertEquals(2, service.addCalls)
        assertNotNull(overlayView(service))
        assertNotNull(feedbackView(service))
    }

    // --- the #260 defect ---

    @Test fun `second attach failing does not leak the first window`() {
        val service = build(FailSecondAdd::class.java)

        showOverlay(service) // must not throw

        assertEquals("the attached window must be unwound", 0, service.attached.size)
    }

    @Test fun `second attach failing clears all tracking fields`() {
        val service = build(FailSecondAdd::class.java)

        showOverlay(service)

        assertNull(overlayView(service))
        assertNull(feedbackView(service))
    }

    @Test fun `first attach failing attaches and tracks nothing`() {
        val service = build(FailFirstAdd::class.java)

        showOverlay(service)

        assertEquals(0, service.attached.size)
        assertNull(overlayView(service))
        assertNull(feedbackView(service))
    }

    // --- guards ---

    @Test fun `entry guard makes a redundant showOverlay a no-op`() {
        val service = build(TestService::class.java)
        showOverlay(service)
        val first = overlayView(service)

        showOverlay(service)

        assertEquals("no duplicate windows", 2, service.addCalls)
        assertSame("original overlay still tracked", first, overlayView(service))
    }

    @Test fun `removeOverlay clears tracking fields`() {
        val service = build(TestService::class.java)
        showOverlay(service)

        removeOverlay(service)

        assertNull(overlayView(service))
        assertNull(feedbackView(service))
    }

    @Test fun `removeOverlay survives windows the framework already detached`() {
        // removeOverlay is the unwind path for a failed attach, so it must not throw.
        val service = build(FailRemove::class.java)
        showOverlay(service)

        removeOverlay(service)

        assertNull(overlayView(service))
        assertNull(feedbackView(service))
    }

    @Test fun `showOverlay after removeOverlay re-attaches`() {
        // The entry guard must not wedge the overlay off permanently after a teardown.
        val service = build(TestService::class.java)
        showOverlay(service)
        removeOverlay(service)

        showOverlay(service)

        assertNotNull(overlayView(service))
        assertNotNull(feedbackView(service))
    }
}
