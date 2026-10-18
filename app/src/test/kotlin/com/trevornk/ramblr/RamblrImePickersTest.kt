package com.trevornk.ramblr

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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

/** The keyboard's style and translation dropdowns, driven the way a user taps them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RamblrImePickersTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private val editor = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "harness.chat"
        fieldId = 7
    }

    private fun panel(): Pair<RamblrImeService, View> {
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        val view = service.onCreateInputView()
        service.onStartInput(editor, false)
        service.onStartInputView(editor, false)
        return service to view
    }

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    private fun View.picker(tag: String): TextView = all().filterIsInstance<TextView>().first { it.tag == tag }
    private fun View.style() = picker(RamblrImeService.STYLE_PICKER_TAG)
    private fun View.translate() = picker(RamblrImeService.TRANSLATE_PICKER_TAG)
    private fun View.list(): ViewGroup = all().first { it.tag == RamblrImeService.PICKER_LIST_TAG } as ViewGroup
    private fun View.status(): TextView = all().filterIsInstance<TextView>().first { it.tag == RamblrImeService.STATUS_TAG }

    /** Credentials live in a process-wide cache that outlives Robolectric's per-test prefs. */
    @org.junit.Before fun noKeys() = ProviderCredentialStore.clearLegacyByKind(app, ProviderKind.OPENAI)

    /** A cloud provider with a key, so styles and translation have something to run on. */
    private fun cloudReady() = ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "test-key")

    /** Rows of the open list, by their title (the first text in the row); a setup notice is not a choice. */
    private fun View.rows(): List<View> = (0 until list().childCount).map { list().getChildAt(it) }
        .filter { it.tag != RamblrImeService.SETUP_NOTICE_TAG }
    private fun View.notice(): View? = (0 until list().childCount).map { list().getChildAt(it) }
        .firstOrNull { it.tag == RamblrImeService.SETUP_NOTICE_TAG }
    private fun View.consent(): View? = all().firstOrNull { it.tag == RamblrImeService.CONSENT_TAG }
    private fun View.button(label: String): View = all().filterIsInstance<android.widget.Button>().first { it.text.toString() == label }
    private fun View.rowTitles(): List<String> = rows().map { row -> row.all().filterIsInstance<TextView>().first().text.toString() }
    private fun View.row(title: String): View = rows().first { row -> row.all().filterIsInstance<TextView>().first().text.toString() == title }

    private fun View.listShown() = (list().parent as View).visibility == View.VISIBLE

    @Test fun `style dropdown lists as is and the quick-menu styles, and picking one turns cleanup on`() {
        prefs().edit().putBoolean("use_local", false).apply()
        cloudReady()
        val (_, root) = panel()
        assertEquals(KeyboardStyle.AS_IS_TITLE, root.style().text.toString())
        assertFalse(root.listShown())

        root.style().performClick()
        assertTrue(root.listShown())
        assertEquals("the panel body is covered, not resized", View.INVISIBLE, root.status().let { (it.parent as View).visibility })
        assertEquals(KeyboardStyle.AS_IS_TITLE, root.rowTitles().first())
        assertTrue(root.row(KeyboardStyle.AS_IS_TITLE).isSelected)
        assertEquals("More styles", root.rowTitles().last())

        root.row("Email").performClick()

        assertTrue(PostProcessingToggle.isEnabled(app))
        assertEquals("email", prefs().getString("cleanup_style", null))
        assertFalse("picking closes the list", root.listShown())
        assertEquals("Email", root.style().text.toString())

        root.style().performClick()
        root.row(KeyboardStyle.AS_IS_TITLE).performClick()
        assertFalse("As is turns cleanup off", PostProcessingToggle.isEnabled(app))
        assertEquals(KeyboardStyle.AS_IS_TITLE, root.style().text.toString())
    }

    @Test fun `local recognition with cloud cleanup asks for consent in the keyboard, and not now changes nothing`() {
        prefs().edit().putBoolean("use_local", true).apply()
        CloudFeatureToggle.setCleanupEnabled(app, true)
        cloudReady()
        val (_, root) = panel()

        root.style().performClick()
        root.row("Natural").performClick()

        assertTrue("the question replaces the list", root.consent() != null)
        root.button("Not now").performClick()
        assertFalse(PostProcessingToggle.isEnabled(app))
        assertFalse(prefs().getBoolean(KeyboardAiReadiness.KEY_CONSENT, false))
        assertEquals(KeyboardStyle.AS_IS_TITLE, root.style().text.toString())
        assertFalse(root.listShown())
    }

    @Test fun `allowing turns the style on and is remembered`() {
        prefs().edit().putBoolean("use_local", true).apply()
        CloudFeatureToggle.setCleanupEnabled(app, true)
        cloudReady()
        val (_, root) = panel()

        root.style().performClick()
        root.row("Natural").performClick()
        root.button("Allow").performClick()

        assertTrue(PostProcessingToggle.isEnabled(app))
        assertTrue(prefs().getBoolean(KeyboardAiReadiness.KEY_CONSENT, false))
        assertEquals("Natural", root.style().text.toString())
        assertFalse(root.listShown())

        // Asked once: the next style goes straight through.
        root.style().performClick()
        root.row("Email").performClick()
        assertEquals(null, root.consent())
        assertEquals("Email", root.style().text.toString())
    }

    @Test fun `with no model for styles the list says why and offers setup`() {
        prefs().edit().putBoolean("use_local", false).apply()
        val (service, root) = panel()

        root.style().performClick()
        val notice = root.notice()!!
        assertTrue(notice.all().filterIsInstance<TextView>().first().text.toString() == KeyboardAiReadiness.STYLE_BLOCKED)
        root.row("Email").performClick()
        assertEquals(KeyboardAiReadiness.STYLE_BLOCKED, root.status().text.toString())

        root.style().performClick()
        root.notice()!!.performClick()
        assertEquals(CleanupActivity::class.java.name, shadowOf(service).nextStartedActivity.component?.className)
    }

    @Test fun `without a cloud provider languages are greyed out and say why instead of silently not translating`() {
        KeyboardTranslation.setChosen(app, listOf("en", "ru"))
        val (service, root) = panel()

        root.translate().performClick()
        assertTrue(root.notice() != null)
        assertTrue(root.row("Русский").alpha < 1f)
        root.row("Русский").performClick()

        assertEquals(null, KeyboardTranslation.target(app))
        assertEquals(KeyboardAiReadiness.TRANSLATION_BLOCKED, root.status().text.toString())
        root.translate().performClick()
        root.notice()!!.performClick()
        assertEquals(CloudProviderActivity::class.java.name, shadowOf(service).nextStartedActivity.component?.className)
    }

    @Test fun `translation with on-device recognition asks for consent first`() {
        prefs().edit().putBoolean("use_local", true).apply()
        cloudReady()
        KeyboardTranslation.setChosen(app, listOf("en", "ru"))
        val (service, root) = panel()

        root.translate().performClick()
        root.row("Русский").performClick()
        assertEquals("not set before the answer", null, KeyboardTranslation.target(app))
        root.button("Allow").performClick()

        assertEquals("ru", KeyboardTranslation.target(app)?.code)
        assertEquals("Russian", service.panelControllerForTest()!!.listener.translationTarget())
    }

    @Test fun `translate dropdown follows the chosen languages and sets the dictation target`() {
        prefs().edit().putBoolean("use_local", false).apply()
        cloudReady()
        KeyboardTranslation.setChosen(app, listOf("en", "ru"))
        val (service, root) = panel()
        assertEquals("Original", root.translate().text.toString())

        root.translate().performClick()
        assertEquals(listOf("Original", "English", "Русский", "Add or remove languages"), root.rowTitles())
        root.row("Русский").performClick()

        assertEquals("ru", KeyboardTranslation.target(app)?.code)
        assertEquals("Русский", root.translate().text.toString())
        assertEquals("Russian", service.panelControllerForTest()!!.listener.translationTarget())

        root.translate().performClick()
        assertTrue(root.row("Русский").isSelected)
        root.row("Original").performClick()
        assertEquals(null, service.panelControllerForTest()!!.listener.translationTarget())
    }

    @Test fun `with no languages the list offers to add some and opens that setting`() {
        val (service, root) = panel()
        root.translate().performClick()
        assertEquals(listOf("Original", "Add languages"), root.rowTitles())

        root.row("Add languages").performClick()

        val started = shadowOf(service).nextStartedActivity
        assertEquals(InvocationActivity::class.java.name, started.component?.className)
        assertEquals(InvocationActivity.Section.KEYBOARD.name, started.getStringExtra(InvocationActivity.EXTRA_SECTION))
        assertEquals("Translation languages", started.getStringExtra(BaseSettingsActivity.EXTRA_HIGHLIGHT_TITLE))
        assertFalse(root.listShown())
    }

    @Test fun `more styles opens the style manager`() {
        val (service, root) = panel()
        root.style().performClick()
        root.row("More styles").performClick()

        assertEquals(StyleManagerActivity::class.java.name, shadowOf(service).nextStartedActivity.component?.className)
    }

    @Test fun `an open list scrolls inside the panel instead of making it taller`() {
        val (_, root) = panel()
        fun height(): Int {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST),
            )
            return root.measuredHeight
        }
        val closed = height()

        root.style().performClick()

        assertEquals(closed, height())
    }

    @Test fun `tapping the open dropdown again, or the other one, swaps the list`() {
        val (_, root) = panel()
        root.style().performClick()
        assertTrue(root.style().isSelected)

        root.translate().performClick()
        assertFalse(root.style().isSelected)
        assertTrue(root.translate().isSelected)
        assertEquals("Original", root.rowTitles().first())

        root.translate().performClick()
        assertFalse(root.listShown())
    }

    @Test fun `hiding the keyboard closes an open list`() {
        val (service, root) = panel()
        root.translate().performClick()

        service.onWindowHidden()

        assertFalse(root.listShown())
    }
}
