package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * #245: Gemini Cloud Live interim transcript was wired into the IME host only
 * (RamblrImeService.kt) -- [WhisperAccessibilityService] constructed its [DictationRuntime]
 * with no `cloudLiveFactory` at all, and its listener let [RuntimeListener.onCloudLiveInterim]
 * fall through to the default no-op, so enabling the Cloud Live toggle while dictating via the
 * ring/floating icon silently did nothing.
 *
 * These drive the REAL [WhisperAccessibilityService] and [DictationRuntime] construction and
 * callback wiring -- not a source-string check -- so a regression that removes the factory
 * argument or reverts `onCloudLiveInterim` to a no-op fails these tests even though the file
 * still compiles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityCloudLiveWiringTest {

    class TestService : WhisperAccessibilityService()

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().apply()
        ProviderKind.values().forEach { ProviderCredentialStore.clear(app, it) }
        PreviewBeforeInjectToggle.setEnabled(app, false)
    }

    @After fun tearDown() {
        prefs().edit().clear().apply()
        ProviderKind.values().forEach { ProviderCredentialStore.clear(app, it) }
    }

    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private fun configureFullyEnabledCloudLive() {
        CloudLiveToggle.setEnabled(app, true)
        prefs().edit().putBoolean("use_local", false).apply()
        ProviderCredentialStore.set(app, ProviderKind.GEMINI, "test-gemini-key")
    }

    private fun build(): TestService = Robolectric.buildService(TestService::class.java, null).create().get()

    private fun runtimeField(service: WhisperAccessibilityService): DictationRuntime = service.runtime

    /** Reads the private constructor-injected `cloudLiveFactory` field straight off the real
     *  [DictationRuntime] instance -- the exact seam #233/#245 gate on -- rather than trusting
     *  behavior alone, which can pass by accident (e.g. a listener bug masking a null factory). */
    private fun cloudLiveFactoryOf(runtime: DictationRuntime): CloudLiveTranscriptionSessionFactory? {
        val field: Field = DictationRuntime::class.java.getDeclaredField("cloudLiveFactory")
        field.isAccessible = true
        return field.get(runtime) as CloudLiveTranscriptionSessionFactory?
    }

    // --- construction: the same three-condition gate CloudLiveWiringTest proves for the IME ---

    @Test
    fun `default install builds a runtime with no cloud-live factory, exactly as before this change`() {
        val service = build()

        val runtime = runtimeField(service)

        assertNull(
            "unconfigured Cloud Live must not reach the accessibility host either",
            cloudLiveFactoryOf(runtime),
        )
    }

    @Test
    fun `toggle on but transcription still on-device builds no factory`() {
        CloudLiveToggle.setEnabled(app, true)
        prefs().edit().putBoolean("use_local", true).apply()
        ProviderCredentialStore.set(app, ProviderKind.GEMINI, "test-gemini-key")
        val service = build()

        assertNull(cloudLiveFactoryOf(runtimeField(service)))
    }

    @Test
    fun `all three conditions met builds a real Gemini cloud-live factory on the accessibility host`() {
        configureFullyEnabledCloudLive()
        val service = build()

        val factory = cloudLiveFactoryOf(runtimeField(service))

        assertNotNull("the accessibility host must now reach the same seam the IME host uses", factory)
        assertTrue(factory is GeminiCloudLiveTranscriptionClient)
    }

    @Test
    fun `the runtime instance is stable across repeated access, not rebuilt per call`() {
        configureFullyEnabledCloudLive()
        val service = build()

        val first = runtimeField(service)
        val second = runtimeField(service)

        assertTrue("re-reading the field must not construct a second DictationRuntime", first === second)
    }

    // --- callback wiring: onCloudLiveInterim must actually route to field/bubble injection ---

    /** Drives the real [WhisperAccessibilityService.onCloudLiveInterim] override through the
     *  preview-before-inject bubble branch of [maybeInjectPartial] -- the one path that needs no
     *  [AccessibilityNodeInfo] tree, so it can run entirely off-device under Robolectric while
     *  still exercising production code, not a re-modelled copy of it. */
    @Test
    fun `onCloudLiveInterim actually surfaces text through the same path local streaming partials use`() {
        PreviewBeforeInjectToggle.setEnabled(app, true)
        val service = build()
        // Attach the overlay windows so feedbackView exists to write into.
        val showOverlay = WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }
        showOverlay.invoke(service)

        val runtime = runtimeField(service)
        // Put the state machine into RECORDING the same way onTap() would, without touching a
        // real AudioRecord: reach the private RuntimeListener the field construction wired up
        // and call its onCloudLiveInterim override directly, exactly as DictationRuntime does
        // from beginCloudLiveAttempt's onInterim callback.
        val stateMachineField = DictationRuntime::class.java.getDeclaredField("stateMachine")
            .apply { isAccessible = true }
        val stateMachine = stateMachineField.get(runtime) as RecordingStateMachine
        assertTrue(stateMachine.tryStartRecording())

        val listenerField = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }
        val listener = listenerField.get(service) as RuntimeListener

        listener.onCloudLiveInterim("hello from cloud live")
        shadowOf(Looper.getMainLooper()).idle()

        val feedbackViewField = WhisperAccessibilityService::class.java.getDeclaredField("feedbackView")
            .apply { isAccessible = true }
        val feedbackView = feedbackViewField.get(service) as android.widget.TextView
        assertEquals(
            "the interim must land in the same feedback surface local streaming partials use",
            "Hello from cloud live",
            feedbackView.text.toString(),
        )
    }

    @Test
    fun `onCloudLiveInterim is a no-op while not recording, matching onStreamingPartial's guard`() {
        PreviewBeforeInjectToggle.setEnabled(app, true)
        val service = build()
        val showOverlay = WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }
        showOverlay.invoke(service)

        val listenerField = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }
        val listener = listenerField.get(service) as RuntimeListener

        // State machine starts IDLE; no onTap() was called.
        listener.onCloudLiveInterim("must be dropped")
        shadowOf(Looper.getMainLooper()).idle()

        val feedbackViewField = WhisperAccessibilityService::class.java.getDeclaredField("feedbackView")
            .apply { isAccessible = true }
        val feedbackView = feedbackViewField.get(service) as android.widget.TextView
        assertFalse(feedbackView.text.toString().contains("must be dropped", ignoreCase = true))
    }
}
