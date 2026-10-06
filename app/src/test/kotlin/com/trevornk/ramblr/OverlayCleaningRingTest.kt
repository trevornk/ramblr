package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #294: the floating icon's busy ring turns emerald when cleanup starts, so transcription and
 * cleanup are distinguishable. Drives the REAL accessibility runtime listener against the real
 * overlay views.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayCleaningRingTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().apply()
    }

    @After fun tearDown() { prefs().edit().clear().apply() }

    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private fun build(): WhisperAccessibilityService {
        val service = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay").apply { isAccessible = true }.invoke(service)
        idle()
        return service
    }

    private fun listenerOf(service: WhisperAccessibilityService): RuntimeListener =
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }.get(service) as RuntimeListener

    private fun ringOf(service: WhisperAccessibilityService): ProgressBar =
        WhisperAccessibilityService::class.java.getDeclaredField("spinner")
            .apply { isAccessible = true }.get(service) as ProgressBar

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ringColor(ring: ProgressBar): Int = ring.indeterminateTintList!!.defaultColor

    private val neutral = 0xFFE8EAED.toInt()
    private val cleaning = 0xFF34D399.toInt()

    @Test fun `transcribing shows the neutral ring, cleanup turns it emerald`() {
        val service = build()
        val listener = listenerOf(service)
        val ring = ringOf(service)

        listener.onEnterTranscribingUi(); idle()
        assertEquals(View.VISIBLE, ring.visibility)
        assertEquals(neutral, ringColor(ring))

        listener.onCleaningStarted(); idle()
        assertEquals(View.VISIBLE, ring.visibility)
        assertEquals(cleaning, ringColor(ring))
    }

    @Test fun `returning to idle hides the ring and resets the cleanup tint for the next dictation`() {
        val service = build()
        val listener = listenerOf(service)
        val ring = ringOf(service)

        listener.onEnterTranscribingUi(); idle()
        listener.onCleaningStarted(); idle()
        listener.onIdleUi(); idle()
        assertEquals(View.GONE, ring.visibility)

        listener.onRecordingStarted(); idle()
        listener.onEnterTranscribingUi(); idle()
        assertEquals(View.VISIBLE, ring.visibility)
        assertEquals(neutral, ringColor(ring))
    }
}
