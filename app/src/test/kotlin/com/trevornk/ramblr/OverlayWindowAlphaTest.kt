package com.trevornk.ramblr

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.Display
import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #373: on Samsung AOD the app-drawn hide (View.alpha = 0) never reaches the screen because no
 * frame is rendered while the display is off, so SurfaceFlinger keeps compositing the stale ring
 * buffer. The fix also zeroes the *window* alpha (applied by WMS without an app frame) -- but only
 * while the screen is off. These drive the real service and capture what reaches updateViewLayout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayWindowAlphaTest {

    class TestService : WhisperAccessibilityService() {
        /** Window alpha / flags of the most recent updateViewLayout for the ring window. */
        var lastAlpha = -1f
        var lastFlags = 0
        /** Number of updateViewLayout calls seen for the ring window. */
        var updateCount = 0
        private var wrapped: WindowManager? = null

        override fun getSystemService(name: String): Any? {
            if (name != Context.WINDOW_SERVICE) return super.getSystemService(name)
            if (wrapped == null) {
                val real = super.getSystemService(name) as WindowManager
                wrapped = object : WindowManager by real {
                    override fun getCurrentWindowMetrics() = real.currentWindowMetrics
                    override fun updateViewLayout(view: View, params: android.view.ViewGroup.LayoutParams) {
                        val lp = params as WindowManager.LayoutParams
                        if (view === ringView()) { lastAlpha = lp.alpha; lastFlags = lp.flags; updateCount++ }
                        real.updateViewLayout(view, params)
                    }
                }
            }
            return wrapped
        }

        fun ringView(): View? =
            WhisperAccessibilityService::class.java.getDeclaredField("overlayView")
                .apply { isAccessible = true }.get(this) as View?
    }

    private lateinit var service: TestService

    @Before fun setUp() {
        service = Robolectric.buildService(TestService::class.java, null).create().get()
        WhisperAccessibilityService::class.java.getDeclaredMethod("registerScreenStateReceiver")
            .apply { isAccessible = true }.invoke(service)
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }.invoke(service)
        setMainForeground(false)
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        broadcast(Intent.ACTION_SCREEN_ON)
    }

    @After fun tearDown() {
        setMainForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
    }

    // `instance` is only set in onServiceConnected, so set the static flag directly.
    private fun setMainForeground(v: Boolean) {
        WhisperAccessibilityService::class.java.getDeclaredField("mainActivityForeground")
            .apply { isAccessible = true }.setBoolean(null, v)
    }

    private fun broadcast(action: String) {
        service.sendBroadcast(Intent(action))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun setKeyguard(locked: Boolean) =
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)

    private fun viewAlpha() = service.ringView()!!.alpha
    private fun notTouchable() = service.lastFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0

    @Test fun `screen on unlocked - window and view alpha 1`() {
        // No updateViewLayout is needed (params already alpha 1 / touchable), so read live params.
        val params = WhisperAccessibilityService::class.java.getDeclaredField("layoutParams")
            .apply { isAccessible = true }.get(service) as WindowManager.LayoutParams
        assertEquals(1f, params.alpha, 0f)
        assertEquals(1f, viewAlpha(), 0f)
    }

    @Test fun `SCREEN_OFF with keyguard - window alpha minimal, view alpha 0, not touchable`() {
        setKeyguard(true)
        broadcast(Intent.ACTION_SCREEN_OFF)
        assertEquals(WhisperAccessibilityService.SCREEN_OFF_WINDOW_ALPHA, service.lastAlpha, 0f)
        assertEquals(0f, viewAlpha(), 0f)
        assertTrue(notTouchable())
    }

    @Test fun `non-interactive power state alone minimizes window alpha`() {
        shadowOf(service.getSystemService(PowerManager::class.java)).turnScreenOn(false)
        service.applyOverlayVisibility()
        assertEquals(WhisperAccessibilityService.SCREEN_OFF_WINDOW_ALPHA, service.lastAlpha, 0f)
        assertEquals(0f, viewAlpha(), 0f)
    }

    @Test fun `doze display state alone minimizes window alpha`() {
        val dm = service.getSystemService(android.hardware.display.DisplayManager::class.java)
        shadowOf(dm.getDisplay(Display.DEFAULT_DISPLAY)).setState(Display.STATE_DOZE)
        service.applyOverlayVisibility()
        assertEquals(WhisperAccessibilityService.SCREEN_OFF_WINDOW_ALPHA, service.lastAlpha, 0f)
        assertEquals(0f, viewAlpha(), 0f)
    }

    @Test fun `SCREEN_ON while keyguard still locked - window alpha back to 1, view stays 0`() {
        setKeyguard(true)
        broadcast(Intent.ACTION_SCREEN_OFF)
        broadcast(Intent.ACTION_SCREEN_ON)
        assertEquals(1f, service.lastAlpha, 0f)
        assertEquals(0f, viewAlpha(), 0f)
        assertTrue(notTouchable())
    }

    @Test fun `USER_PRESENT after unlock - window and view alpha 1`() {
        setKeyguard(true)
        broadcast(Intent.ACTION_SCREEN_OFF)
        broadcast(Intent.ACTION_SCREEN_ON)
        setKeyguard(false)
        broadcast(Intent.ACTION_USER_PRESENT)
        assertEquals(1f, service.lastAlpha, 0f)
        assertEquals(1f, viewAlpha(), 0f)
        assertTrue(!notTouchable())
    }

    @Test fun `hidden for MainActivity foreground with screen on - window alpha stays 1`() {
        setMainForeground(true)
        service.applyOverlayVisibility()
        assertEquals(1f, service.lastAlpha, 0f)
        assertEquals(0f, viewAlpha(), 0f)
        assertTrue(notTouchable())
    }

    @Test fun `hidden by hide-icon with screen on - window alpha stays 1`() {
        IconHiddenState.setHidden(service, true)
        service.applyOverlayVisibility()
        assertEquals(1f, service.lastAlpha, 0f)
        assertEquals(0f, viewAlpha(), 0f)
    }

    @Test fun `force-visible override while screen off keeps low alpha and not touchable`() {
        WhisperAccessibilityService.setOverlayForceVisibleOverride(true)
        broadcast(Intent.ACTION_SCREEN_OFF)
        service.applyOverlayVisibility()
        assertEquals(WhisperAccessibilityService.SCREEN_OFF_WINDOW_ALPHA, service.lastAlpha, 0f)
        assertEquals(0f, viewAlpha(), 0f)
        assertTrue(notTouchable())
    }

    @Test fun `reposition while screen off does not reset window alpha`() {
        broadcast(Intent.ACTION_SCREEN_OFF)
        val before = service.updateCount
        val params = WhisperAccessibilityService::class.java.getDeclaredField("layoutParams")
            .apply { isAccessible = true }.get(service) as WindowManager.LayoutParams
        val animate = WhisperAccessibilityService::class.java.declaredMethods
            .first { it.name == "animateRingX" }.apply { isAccessible = true }
        animate.invoke(service, params, service.getSystemService(Context.WINDOW_SERVICE), params.x - 40)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("reposition should have issued updateViewLayout", service.updateCount > before)
        assertEquals(WhisperAccessibilityService.SCREEN_OFF_WINDOW_ALPHA, service.lastAlpha, 0f)
        assertTrue(notTouchable())
    }

    @Test fun `repeated applyOverlayVisibility with no state change issues no extra updateViewLayout`() {
        broadcast(Intent.ACTION_SCREEN_OFF)
        service.applyOverlayVisibility()
        val n = service.updateCount
        service.applyOverlayVisibility()
        service.applyOverlayVisibility()
        assertEquals(n, service.updateCount)
        // ...and a real state change still goes through.
        broadcast(Intent.ACTION_SCREEN_ON)
        assertTrue(service.updateCount > n)
        assertEquals(1f, service.lastAlpha, 0f)
    }

    @Test fun `first hidden apply after showOverlay still updates the window`() {
        // showOverlay builds params with alpha 1 / touchable; hiding must push the change.
        setMainForeground(true)
        val n = service.updateCount
        service.applyOverlayVisibility()
        assertTrue(service.updateCount > n)
        assertTrue(notTouchable())
    }
}
