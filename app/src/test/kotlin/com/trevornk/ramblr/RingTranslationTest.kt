package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.time.Duration
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
import org.robolectric.shadows.ShadowToast

/**
 * The "Translate to" choice now applies to dictations started from the floating icon as well as the
 * keyboard. Drives the REAL accessibility host ([WhisperAccessibilityService]) and the real
 * [DictationRuntime]: the host passes the shared target, the cloud-only routing is reused, skipped
 * translations explain themselves in the floating bubble, the icon shows a language badge, and the
 * long-press menu carries the same Translate section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingTranslationTest {

    class TestService : WhisperAccessibilityService() {
        // No accessibility tree under Robolectric: nothing to inject into, so delivery takes the
        // clipboard-fallback path that always shows a bubble.
        override fun getRootInActiveWindow(): AccessibilityNodeInfo? = null
    }

    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private val bodies = mutableListOf<String>()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    bodies += request.body!!.utf8()
                    return MockResponse.Builder().body("""{"choices":[{"message":{"content":"Hola a todos, la entrega llega tarde."}}]}""").build()
                }
            }
            start()
        }
        prefs().edit().clear()
            .putBoolean("use_local", false)
            .putBoolean(KeyboardTranslationReadiness.KEY_CONSENT, true).apply()
        ProviderKind.values().forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        PostProcessingToggle.setEnabled(app, false)
        PreviewBeforeInjectToggle.setEnabled(app, false)
        KeyboardTranslation.setChosen(app, listOf("es", "de"))
        KeyboardTranslation.setTarget(app, "es")
    }

    @After fun tearDown() {
        server.close()
        ProviderKind.values().forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        prefs().edit().clear().apply()
    }

    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun base() = server.url("/v1").toString().trimEnd('/')
    private fun saveChain(vararg entries: ProviderChainEntry) = ProviderChainStore.save(app, ProviderChain(entries.toList()))

    private fun service(): TestService {
        val s = Robolectric.buildService(TestService::class.java, null).create().get()
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay").apply { isAccessible = true }.invoke(s)
        idle()
        return s
    }

    private fun listenerOf(s: WhisperAccessibilityService): RuntimeListener =
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }.get(s) as RuntimeListener

    private fun <T> field(s: WhisperAccessibilityService, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return WhisperAccessibilityService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(s) as T
    }

    private fun feedbackText(s: WhisperAccessibilityService): String = field<TextView>(s, "feedbackView").text.toString()

    // --- host passes the target ------------------------------------------------------------

    @Test fun `ring host passes the shared translate target to the runtime`() {
        val listener = listenerOf(service())
        assertEquals("Spanish", listener.translationTarget())

        KeyboardTranslation.setTarget(app, "de") // picked on the keyboard
        assertEquals("German", listener.translationTarget())

        KeyboardTranslation.setTarget(app, null)
        assertNull(listener.translationTarget())
    }

    @Test fun `a target that is no longer an offered language is ignored`() {
        prefs().edit().putString(KeyboardTranslation.KEY_TARGET, "fr").apply()
        assertNull(listenerOf(service()).translationTarget())
    }

    // --- ring dictation through the real runtime -------------------------------------------

    /** Real [DictationRuntime] whose translation hooks and delivery go through the ring host. */
    private class Capturing(private val host: RuntimeListener) : RuntimeListener by host {
        val delivered = mutableListOf<Pair<String, String?>>()
        override fun deliverText(text: String, rawText: String?, paidFallbackGroup: CleanupStepGroup?, cleanupError: String?, feedbackDurationMs: Long) {
            delivered += text to cleanupError
        }
    }

    private class Engine(cacheDir: File, private val sm: RecordingStateMachine) : RecordingEngine(cacheDir, sm) {
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean = sm.tryStartRecording()
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false
    }

    private fun ringDictation(host: WhisperAccessibilityService, text: String): Capturing {
        val capture = Capturing(listenerOf(host))
        val runtime = DictationRuntime(app, capture, InMemoryDictationSessionLeaseRegistry()) { dir, sm -> Engine(dir, sm) }
        runtime.onTap(); runtime.onTap()
        runtime.handleTranscriptionResult(text, token = 1)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && capture.delivered.isEmpty()) { idle(); Thread.sleep(10) }
        return capture
    }

    @Test fun `ring dictation with a local step ahead of a cloud step translates on the cloud step`() {
        saveChain(
            ProviderChainEntry(ProviderKind.LOCAL, "on-device"),
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = base()),
        )
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "k")
        val host = service()

        val capture = ringDictation(host, "hello everyone the delivery is late")

        assertEquals("Hola a todos, la entrega llega tarde.", capture.delivered.single().first)
        assertNull(capture.delivered.single().second)
        assertEquals(1, bodies.size)
        assertTrue("the Spanish clause reached the cloud step", bodies.single().contains("Spanish"))
        assertNull("nothing to explain", host.pendingTranslationNotice)
    }

    @Test fun `ring dictation without a cloud key inserts untranslated and queues the reason for the bubble`() {
        saveChain(
            ProviderChainEntry(ProviderKind.LOCAL, "on-device"),
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = base()),
        )
        val host = service()

        val capture = ringDictation(host, "hello everyone the delivery is late")

        assertEquals("hello everyone the delivery is late", capture.delivered.single().first)
        assertEquals(0, bodies.size)
        assertEquals(app.getString(R.string.ring_translate_unavailable_cloud), host.pendingTranslationNotice)
        assertNull("the ring shows the reason in its bubble, not a toast", ShadowToast.getLatestToast())
    }

    @Test fun `ring dictation without off-device consent does not translate silently and points to the keyboard or settings`() {
        prefs().edit().putBoolean("use_local", true).putBoolean(KeyboardTranslationReadiness.KEY_CONSENT, false).apply()
        saveChain(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = base()))
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "k")
        val host = service()

        val capture = ringDictation(host, "hello everyone the delivery is late")

        assertEquals("hello everyone the delivery is late", capture.delivered.single().first)
        assertEquals("nothing may be sent without consent", 0, bodies.size)
        val notice = host.pendingTranslationNotice!!
        assertEquals(app.getString(R.string.ring_translate_unavailable_consent), notice)
        assertTrue(notice.contains("keyboard") && notice.contains("Settings"))
    }

    @Test fun `ring dictation with Original sends nothing and says nothing`() {
        KeyboardTranslation.setTarget(app, null)
        saveChain(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", baseUrlOverride = base()))
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "k")
        val host = service()

        val capture = ringDictation(host, "hello everyone the delivery is late")

        assertEquals("hello everyone the delivery is late", capture.delivered.single().first)
        assertEquals(0, bodies.size)
        assertNull(host.pendingTranslationNotice)
    }

    // --- the bubble ------------------------------------------------------------------------

    private fun deliverAndSettle(host: WhisperAccessibilityService, cleanupError: String? = null) {
        listenerOf(host).deliverText("hello there", null, null, cleanupError, 2000)
        // injectText retries the empty accessibility scan after a short delay before giving up.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    @Test fun `a skipped translation shows its reason in the floating bubble`() {
        val host = service()
        val listener = listenerOf(host)
        assertEquals("Spanish", listener.translationTarget())
        assertTrue(listener.onTranslationUnavailable(consentMissing = false))

        deliverAndSettle(host)

        val bubble = feedbackText(host)
        assertTrue("bubble: $bubble", bubble.startsWith(app.getString(R.string.ring_translate_unavailable_cloud)))
        assertFalse(bubble.contains("Cleanup failed"))
        assertNull("shown once", host.pendingTranslationNotice)
    }

    @Test fun `a failed translation says translation failed, not cleanup failed`() {
        val host = service()
        val listener = listenerOf(host)
        listener.translationTarget() // a target was requested and the route was cloud
        deliverAndSettle(host, cleanupError = "HTTP 401: bad key")

        val bubble = feedbackText(host)
        assertTrue("bubble: $bubble", bubble.startsWith("Translation failed"))
        assertFalse(bubble.contains("Cleanup failed"))
    }

    @Test fun `a failed cleanup without a translate target keeps the cleanup wording`() {
        KeyboardTranslation.setTarget(app, null)
        val host = service()
        listenerOf(host).translationTarget()
        deliverAndSettle(host, cleanupError = "HTTP 401: bad key")

        assertTrue(feedbackText(host).startsWith("Cleanup failed"))
    }

    @Test fun `the skip notice does not leak into the next dictation`() {
        val host = service()
        val listener = listenerOf(host)
        listener.onTranslationUnavailable(consentMissing = true)
        listener.onRecordingStartRequested()
        assertNull(host.pendingTranslationNotice)
    }

    // --- hosts that must not translate -----------------------------------------------------

    @Test fun `process-text, audio-file and share paths never read the translate target`() {
        val dir = File("src/main/kotlin/com/trevornk/ramblr")
        assertTrue("source dir ${dir.absolutePath}", dir.isDirectory)
        val readers = dir.listFiles { f -> f.extension == "kt" }!!
            .filter { f -> f.readText().let { "KeyboardTranslation.target(" in it || "translationTarget()" in it } }
            .map { it.name }.toSet()
        // Dictation hosts that insert into a text field + the plumbing that defines/consumes the hook.
        assertEquals(
            setOf(
                "CleanupStyleMenu.kt", "DictationRuntime.kt",
                "RamblrImeService.kt", "ImePanelController.kt", "WhisperAccessibilityService.kt",
            ),
            readers,
        )
        for (untouched in listOf("ProcessTextActivity.kt", "AudioJobRunner.kt", "AudioShareActivity.kt", "ProcessTextRequest.kt")) {
            assertFalse("$untouched must not translate", untouched in readers)
        }
        // Both background paths still ask the model to KEEP the spoken language.
        assertTrue(File(dir, "ProcessTextActivity.kt").readText().contains("withKeepLanguage"))
        assertTrue(File(dir, "AudioJobRunner.kt").readText().contains("withKeepLanguage"))
    }

    // --- badge -----------------------------------------------------------------------------

    private fun badge(s: WhisperAccessibilityService): TextView = s.translationBadge!!

    @Test fun `badge shows the language code only while a target is set`() {
        val s = service()
        assertEquals(View.VISIBLE, badge(s).visibility)
        assertEquals("ES", badge(s).text.toString())
        assertTrue(badge(s).contentDescription.toString().contains("Spanish"))

        KeyboardTranslation.setTarget(app, null)
        s.refreshTranslationBadge()
        assertEquals("Original: no badge, ring looks as before", View.GONE, badge(s).visibility)
    }

    @Test fun `badge follows a change made on the keyboard without reopening anything`() {
        val s = service()
        s.startObservingRingMode()

        KeyboardTranslation.setTarget(app, "de")
        idle()
        assertEquals("DE", badge(s).text.toString())

        KeyboardTranslation.setTarget(app, null)
        idle()
        assertEquals(View.GONE, badge(s).visibility)
    }

    @Test fun `badge never changes the ring window or swallows its touches`() {
        KeyboardTranslation.setTarget(app, null)
        val s = service()
        val params = field<android.view.WindowManager.LayoutParams>(s, "layoutParams")
        val before = params.width to params.height

        KeyboardTranslation.setTarget(app, "es")
        s.refreshTranslationBadge()

        assertEquals(before, params.width to params.height)
        assertFalse("touches must reach the ring's own listener", badge(s).isClickable)
        assertTrue("badge lives inside the ring window", badge(s).parent === field<FrameLayout>(s, "overlayView"))
        assertTrue("readable at the default ring size", badge(s).textSize / app.resources.displayMetrics.scaledDensity >= 9f)
    }

    @Test fun `badge is hidden while the ring is peeked and returns with it`() {
        val s = service()
        val peeked = WhisperAccessibilityService::class.java.getDeclaredField("isPeeked").apply { isAccessible = true }
        peeked.setBoolean(s, true)
        s.refreshTranslationBadge()
        assertEquals(View.GONE, badge(s).visibility)

        peeked.setBoolean(s, false)
        s.refreshTranslationBadge()
        assertEquals(View.VISIBLE, badge(s).visibility)
    }

    // --- long-press menu -------------------------------------------------------------------

    private fun menuOf(s: WhisperAccessibilityService): ViewGroup =
        WhisperAccessibilityService::class.java.getDeclaredMethod("buildStyleMenuContent")
            .apply { isAccessible = true }.invoke(s) as ViewGroup

    private fun textViews(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun chip(root: View, label: String): TextView =
        textViews(root).first { it.text.toString() == label }

    @Test fun `menu has a Translate to section with Original and the chosen languages`() {
        val menu = menuOf(service())
        val shown = textViews(menu).map { it.text.toString() }

        assertTrue(shown.contains("Translate to"))
        assertTrue(shown.contains("Original"))
        assertTrue(shown.contains("Español"))
        assertTrue(shown.contains("Deutsch"))
        assertFalse(shown.contains("Français"))
    }

    @Test fun `tapping a language chip sets the shared target and the keyboard sees it`() {
        val s = service()
        KeyboardTranslation.setTarget(app, null)

        val es = chip(menuOf(s), "Español")
        assertTrue(es.isClickable)
        es.performClick(); idle()

        assertEquals("es", KeyboardTranslation.target(app)?.code)
        assertEquals("ES", badge(s).text.toString())
        assertEquals("Translating to Spanish", feedbackText(s))

        chip(menuOf(s), "Original").performClick(); idle()
        assertNull(KeyboardTranslation.target(app))
        assertEquals(View.GONE, badge(s).visibility)
    }

    @Test fun `the current target is selected in the menu`() {
        val menu = menuOf(service())
        assertTrue(chip(menu, "Español").contentDescription.toString().contains("selected"))
        assertFalse(chip(menu, "Deutsch").contentDescription.toString().contains("selected"))
    }

    @Test fun `menu chips are disabled while a dictation is in flight`() {
        val s = service()
        val runtime = s.runtime
        val stateMachine = DictationRuntime::class.java.getDeclaredField("stateMachine")
            .apply { isAccessible = true }.get(runtime) as RecordingStateMachine
        assertTrue(stateMachine.tryStartRecording())

        val menu = menuOf(s)
        for (label in listOf("Original", "Español", "Deutsch")) {
            val c = chip(menu, label)
            assertFalse("$label must be disabled while recording", c.isClickable || c.isEnabled)
            c.performClick()
        }
        assertEquals("a disabled chip changes nothing", "es", KeyboardTranslation.target(app)?.code)
        assertTrue(textViews(menu).any { it.text.toString() == app.getString(R.string.ring_translate_menu_busy) })
    }

    @Test fun `a menu opened while idle ignores a chip tapped after a dictation started`() {
        val s = service()
        val menu = menuOf(s)
        val stateMachine = DictationRuntime::class.java.getDeclaredField("stateMachine")
            .apply { isAccessible = true }.get(s.runtime) as RecordingStateMachine
        assertTrue(stateMachine.tryStartRecording())

        chip(menu, "Deutsch").performClick(); idle()

        assertEquals("es", KeyboardTranslation.target(app)?.code)
    }

    @Test fun `with no languages chosen the menu points to settings instead of offering chips`() {
        KeyboardTranslation.setChosen(app, emptyList())
        val shown = textViews(menuOf(service())).map { it.text.toString() }

        assertTrue(shown.contains("Translate to"))
        assertTrue(shown.contains(app.getString(R.string.ring_translate_menu_none)))
        assertFalse(shown.contains("Original"))
    }

    @Test fun `failure notice names translation when one was requested`() {
        val reason = CleanupFailureNotice.summarize("HTTP 401: x")
        assertEquals(
            "Translation failed ($reason) — inserted untranslated text",
            CleanupFailureNotice.messageFor(InjectMethod.DIRECT, "HTTP 401: x", translating = true),
        )
        assertEquals("Translation failed ($reason)", CleanupFailureNotice.messageFor(InjectMethod.NONE, "HTTP 401: x", translating = true))
        assertTrue(CleanupFailureNotice.messageFor(InjectMethod.DIRECT, "HTTP 401: x").startsWith("Cleanup failed"))
    }

    @Test fun `badge text is the upper-case language code`() {
        assertEquals("ES", KeyboardTranslation.badgeText(KeyboardTranslation.byCode("es")!!))
        assertEquals("ZH", KeyboardTranslation.badgeText(KeyboardTranslation.byCode("zh")!!))
    }
}
