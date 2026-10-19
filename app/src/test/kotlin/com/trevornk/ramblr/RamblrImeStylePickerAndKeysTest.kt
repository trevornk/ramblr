package com.trevornk.ramblr

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #312 at the host layer: the real [RamblrImeService] panel exposes the style chip and the three
 * edit keys with accessible labels and 48dp targets, opens the in-window picker, writes the shared
 * prefs on selection, and gates both while a dictation is in flight or the field is secure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RamblrImeStylePickerAndKeysTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    @Before fun setUp() { prefs().edit().clear().putBoolean("local_cleanup_consent_seen", true).apply() }
    @After fun tearDown() { prefs().edit().clear().apply() }

    private fun editor(inputType: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0) = EditorInfo().apply {
        this.inputType = inputType
        this.imeOptions = imeOptions
        packageName = "harness.example"
        fieldId = 42
    }

    private fun panel(info: EditorInfo = editor()): Triple<RamblrImeService, View, EditorInfo> {
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        val root = service.onCreateInputView()
        service.onStartInput(info, false)
        service.onStartInputView(info, false)
        return Triple(service, root, info)
    }

    private fun View.all(): List<View> =
        if (this is ViewGroup) listOf(this) + (0 until childCount).flatMap { getChildAt(it).all() } else listOf(this)

    private fun View.byDescription(text: String): View =
        all().firstOrNull { it.contentDescription?.toString() == text }
            ?: error("no view described '$text' among ${all().mapNotNull { it.contentDescription }}")

    private fun View.chip(): TextView =
        all().filterIsInstance<TextView>().first { it.contentDescription?.toString()?.startsWith("Cleanup") == true }

    private fun renderState(service: RamblrImeService, state: ImeUiState) {
        RamblrImeService::class.java.getDeclaredMethod("renderState", ImeUiState::class.java)
            .apply { isAccessible = true }.invoke(service, state)
    }

    private fun dp(v: Int) = v * app.resources.displayMetrics.density

    @Test fun `keyboard shows backspace space and enter with accessible labels and 48dp targets`() {
        val (_, root, _) = panel()

        for (label in listOf("Backspace", "Space", "Enter")) {
            val key = root.byDescription(label)
            key.measure(
                View.MeasureSpec.makeMeasureSpec(dp(360).toInt(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(400).toInt(), View.MeasureSpec.AT_MOST),
            )
            assertTrue("$label key must be clickable", key.isClickable || key.hasOnClickListeners())
            val lp = key.layoutParams
            assertTrue("$label key height must be at least 48dp, was ${lp.height}", lp.height >= dp(48).toInt())
            if (label != "Space") assertTrue("$label key width must be at least 48dp, was ${lp.width}", lp.width >= dp(48).toInt())
        }
    }

    @Test fun `style chip has a 48dp touch target`() {
        val (_, root, _) = panel()

        assertTrue(root.chip().minHeight >= dp(48).toInt())
    }

    @Test fun `enter key is labelled for what it will do in this field`() {
        assertNotNull(panel(editor(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)).second.byDescription("Search"))
        assertNotNull(panel(editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND)).second.byDescription("New line"))
    }

    @Test fun `chip shows the current style and an accessible hint`() {
        prefs().edit().putBoolean("use_post_processing", true).putString("cleanup_style", "casual").apply()
        val (_, root, _) = panel()

        val chip = root.chip()

        assertEquals("Casual", chip.text.toString())
        assertEquals("Cleanup style: Casual. Tap to change", chip.contentDescription.toString())
    }

    @Test fun `chip reads Cleanup off when cleanup is disabled`() {
        val (_, root, _) = panel()

        assertEquals("Cleanup off", root.chip().text.toString())
    }

    @Test fun `tapping the chip opens the picker in-window and hides the dictation controls`() {
        val (_, root, _) = panel()

        root.chip().performClick()

        val rows = root.all().filterIsInstance<TextView>().map { it.text.toString() }
        for (persona in QuickMenuPersonaStore.defaultSelection().map { CleanupPersonas.fromKey(it).title }) {
            assertTrue("picker must list $persona, saw $rows", persona in rows)
        }
        assertTrue("Cleanup toggle row", rows.any { it.startsWith("Cleanup: ") })
        assertEquals(View.GONE, root.byDescription("Start Ramblr dictation").parentChainVisibility())
    }

    private fun View.parentChainVisibility(): Int {
        var v: View? = this
        while (v != null) { if (v.visibility != View.VISIBLE) return v.visibility; v = v.parent as? View }
        return View.VISIBLE
    }

    @Test fun `picking a style writes the shared prefs, closes the picker and updates the chip`() {
        val (_, root, _) = panel()
        root.chip().performClick()

        val row = root.all().first { it.isClickable && it.contentDescription?.toString() == "Formal" }
        row.performClick()

        assertEquals("formal", prefs().getString("cleanup_style", null))
        assertTrue(PostProcessingToggle.isEnabled(app))
        assertEquals("harness.example".let { PerAppPersonaStore.personaKeyFor(app, it) }, "formal")
        assertEquals(View.VISIBLE, root.byDescription("Start Ramblr dictation").parentChainVisibility())
        assertEquals("Formal", root.chip().text.toString())
    }

    @Test fun `the picker's cleanup chip toggles the shared cleanup pref and reports consent blocks`() {
        PostProcessingToggle.setEnabled(app, true)
        val (_, root, _) = panel()
        root.chip().performClick()

        root.all().filterIsInstance<TextView>().first { it.text.toString() == "Cleanup: On" }.performClick()

        assertFalse("tapping Cleanup: On turns cleanup off, shared with the ring", PostProcessingToggle.isEnabled(app))
        assertEquals("Cleanup off", root.chip().text.toString())

        // Consent withdrawn: turning it back on must be refused with a pointer to Settings.
        prefs().edit().putBoolean("local_cleanup_consent_seen", false).apply()
        root.chip().performClick()
        root.all().filterIsInstance<TextView>().first { it.text.toString() == "Cleanup: Off" }.performClick()
        assertFalse(PostProcessingToggle.isEnabled(app))
        assertTrue(root.all().filterIsInstance<TextView>().any { it.text.toString() == "Enable cleanup once in Ramblr settings first" })
    }

    @Test fun `chip follows a style or cleanup change made elsewhere while the keyboard is open`() {
        val (_, root, _) = panel()
        assertEquals("Cleanup off", root.chip().text.toString())

        // e.g. picked from the floating ring's long-press menu while the keyboard is on screen
        CleanupStyleMenu.select(app, "harness.example", CleanupPersonas.EMAIL)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("Email", root.chip().text.toString())

        PostProcessingToggle.setEnabled(app, false)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Cleanup off", root.chip().text.toString())
    }

    @Test fun `a style picked on the keyboard is what the ring menu model reads`() {
        val (_, root, _) = panel()
        root.chip().performClick()
        root.all().first { it.isClickable && it.contentDescription?.toString() == CleanupPersonas.NOTES.title }.performClick()

        assertEquals(CleanupPersonas.NOTES, CleanupStyleMenu.load(app, "harness.example").current)
    }

    @Test fun `picker and chip are unavailable while recording and an open picker is dismissed`() {
        val (service, root, _) = panel()
        root.chip().performClick()

        renderState(service, ImeUiState.RECORDING)

        assertEquals("open picker must be dismissed so the mic/stop control is reachable",
            View.VISIBLE, root.byDescription("Stop recording and transcribe").parentChainVisibility())
        assertFalse(root.chip().isEnabled)
        assertTrue(root.chip().contentDescription.toString().contains("when dictation finishes"))
        root.chip().performClick() // ignored: must not reopen
        assertEquals("a blocked chip must not reopen the picker",
            View.VISIBLE, root.byDescription("Stop recording and transcribe").parentChainVisibility())
    }

    @Test fun `submit-capable enter is disabled while recording but newline and backspace stay on`() {
        val (service, root, _) = panel(editor(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND))
        assertTrue(root.byDescription("Send").isEnabled)

        renderState(service, ImeUiState.RECORDING)

        assertFalse(root.byDescription("Send").isEnabled)
        assertTrue(root.byDescription("Backspace").isEnabled)
        assertTrue(root.byDescription("Space").isEnabled)
    }

    @Test fun `multi-line enter stays enabled while recording`() {
        val multi = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND)
        val (service, root, _) = panel(multi)

        renderState(service, ImeUiState.RECORDING)

        assertTrue(root.byDescription("New line").isEnabled)
    }

    @Test fun `edit keys are disabled in a secure field`() {
        val secure = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val (_, root, _) = panel(secure)

        for (label in listOf("Backspace", "Space", "Enter")) assertFalse(label, root.byDescription(label).isEnabled)
    }

    @Test fun `panel clears the navigation bar so the full-width key row is not under the system IME buttons`() {
        val (_, root, _) = panel()
        val basePadding = root.paddingBottom

        root.dispatchApplyWindowInsets(
            android.view.WindowInsets.Builder()
                .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 107))
                .build(),
        )

        assertEquals(basePadding + 107, root.paddingBottom)

        // Insets are re-dispatched on every layout; padding must be recomputed, not accumulated.
        root.dispatchApplyWindowInsets(
            android.view.WindowInsets.Builder()
                .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 107))
                .build(),
        )
        assertEquals(basePadding + 107, root.paddingBottom)
    }

    @Test fun `edit keys with no input connection do nothing and do not crash`() {
        val (_, root, _) = panel()

        root.byDescription("Space").performClick()
        root.byDescription("Enter").performClick()
        root.byDescription("Backspace").performClick()
    }
}
