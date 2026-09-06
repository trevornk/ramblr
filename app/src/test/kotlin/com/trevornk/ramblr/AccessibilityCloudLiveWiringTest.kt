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

    /** Reads the private constructor-injected `cloudLiveFactory` provider off the real
     *  [DictationRuntime] instance and invokes it -- the exact seam #233/#245 gate on -- rather
     *  than trusting behavior alone, which can pass by accident (e.g. a listener bug masking a
     *  null factory). */
    private fun cloudLiveFactoryOf(runtime: DictationRuntime): CloudLiveTranscriptionSessionFactory? {
        val field: Field = DictationRuntime::class.java.getDeclaredField("cloudLiveFactory")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val provider = field.get(runtime) as () -> CloudLiveTranscriptionSessionFactory?
        return provider()
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

    /**
     * Regression test for the exact production gap: once a host's single, long-lived
     * [DictationRuntime] is constructed, live preference changes (toggling Cloud Live, switching
     * cloud/local transcription, rotating the Gemini key) must still take effect on the NEXT
     * dictation attempt -- not require the accessibility service process to be killed and
     * recreated. This drives the real [WhisperAccessibilityService.runtime] getter (which caches
     * the DictationRuntime instance itself) and asserts that flipping the preference between two
     * reads of the *factory provider* changes the outcome, proving the seam is re-evaluated live
     * rather than captured once at construction time.
     */
    @Test
    fun `a live preference change after construction is reflected on the next dictation, not stale`() {
        // Start disabled: default install, no factory.
        val service = build()
        val runtime = runtimeField(service)
        assertNull("must start with no factory", cloudLiveFactoryOf(runtime))

        // Opt in without rebuilding the service/runtime -- exactly what happens when a user
        // flips the Cloud Live toggle or adds a Gemini key while the accessibility service is
        // already connected and running.
        configureFullyEnabledCloudLive()

        assertNotNull(
            "the SAME runtime instance must see the just-enabled Cloud Live on its next attempt",
            cloudLiveFactoryOf(runtime),
        )
        assertTrue(runtimeField(service) === runtime)
    }

    /** Symmetric case: opting back out (or the credential being cleared) must also be honored
     *  immediately by the same long-lived runtime, not just the one-way enable direction. */
    @Test
    fun `opting back out after construction also takes effect immediately, not just opting in`() {
        configureFullyEnabledCloudLive()
        val service = build()
        val runtime = runtimeField(service)
        assertNotNull("must start enabled", cloudLiveFactoryOf(runtime))

        CloudLiveToggle.setEnabled(app, false)

        assertNull(
            "the same runtime instance must stop offering live once the toggle flips off",
            cloudLiveFactoryOf(runtime),
        )
    }

    /**
     * onDestroy must not construct a DictationRuntime it never needed. If the service is
     * created and torn down without anything ever touching the lazy `runtime` property (e.g. the
     * framework aborts the bind before onServiceConnected runs), a naive `runtime.shutdown()` in
     * onDestroy would build a brand-new instance for the sole purpose of shutting it down --
     * wasted native model-init work on a teardown path, and it would leave the private
     * `runtimeInstance` backing field non-null after destroy for no benefit. This drives the
     * real onDestroy() and reads the real backing field, not a source-string check.
     */
    @Test
    fun `onDestroy on a never-connected service does not construct a runtime`() {
        val service = Robolectric.buildService(TestService::class.java, null).create().get()

        val runtimeInstanceField = WhisperAccessibilityService::class.java.getDeclaredField("runtimeInstance")
            .apply { isAccessible = true }
        assertNull("no runtime should exist before onDestroy on a service that never connected", runtimeInstanceField.get(service))

        service.onDestroy()

        assertNull(
            "onDestroy must not have constructed a runtime merely to tear it down",
            runtimeInstanceField.get(service),
        )
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

    /**
     * The lazy `runtime` getter replaced an eager field initializer, so it must stay safe for the
     * concurrent first-access pattern production actually has: [onServiceConnected] starts two
     * background threads that each touch `runtime` (`initLocalModel()` / `initStreamingModel()`)
     * while the main thread can reach it too. An unsynchronized `?:` check-then-assign lets two
     * threads each construct a [DictationRuntime]; the loser is silently orphaned -- never
     * shut down and never released by [onDestroy], which only ever sees whichever instance won
     * the assignment race -- while both have already begun loading native models.
     */
    @Test
    fun `concurrent first access constructs exactly one runtime`() {
        val service = build()
        val threads = 8
        val barrier = java.util.concurrent.CyclicBarrier(threads)
        val seen = java.util.Collections.synchronizedList(mutableListOf<DictationRuntime>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        val workers = (1..threads).map {
            Thread {
                try {
                    barrier.await()
                    seen.add(service.runtime)
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(10_000) }

        assertTrue("no worker may fail: $failures", failures.isEmpty())
        assertEquals("every thread must observe a runtime", threads, seen.size)
        assertEquals(
            "all threads must observe the same DictationRuntime instance -- a second one would " +
                "be orphaned past onDestroy with native models already loading",
            1,
            seen.distinctBy { System.identityHashCode(it) }.size,
        )
    }
}
