package com.trevornk.ramblr

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dark-mode coverage for the IME panel.
 *
 * The panel is built by an [android.inputmethodservice.InputMethodService], whose theme is the bare
 * platform default: always light, and missing every Material3 attribute the panel paints itself
 * with. Each `resolveColor` therefore fell through to its hardcoded light fallback, so the keyboard
 * stayed white in dark mode while the rest of the app followed the system. These tests assert the
 * resolved pixels under both ui modes rather than the presence of a wrapper, so the regression
 * cannot come back through a different mechanism.
 */
@RunWith(RobolectricTestRunner::class)
class RamblrImePanelThemeTest {

    private fun normalEditor(): EditorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "harness.example"
        fieldId = 42
    }

    private fun secureEditor(): EditorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        packageName = "harness.example"
        fieldId = 43
    }

    private fun panel(): Pair<RamblrImeService, View> {
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        val view = service.onCreateInputView()
        service.onStartInput(normalEditor(), false)
        service.onStartInputView(normalEditor(), false)
        return service to view
    }

    private fun View.descendants(): List<View> =
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } + this
        else listOf(this)

    private fun backgroundColor(view: View): Int =
        (view.background as GradientDrawable).color!!.defaultColor

    private fun micButton(root: View): ImageButton =
        root.descendants().filterIsInstance<ImageButton>().first { it.isClickable && it.contentDescription != null }

    private fun statusText(root: View): TextView =
        root.descendants().filterIsInstance<TextView>().first()

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `panel surface and text follow dark mode`() {
        val (_, root) = panel()

        val surface = backgroundColor(root)
        assertTrue(
            "panel surface must be dark in night mode, was #${Integer.toHexString(surface)}",
            ColorUtils.calculateLuminance(surface) < 0.2,
        )
        assertTrue(
            "the white fallback proves the Material3 attrs did not resolve",
            surface != Color.WHITE,
        )

        val status = statusText(root).currentTextColor
        assertTrue(
            "status text must be light on a dark surface, was #${Integer.toHexString(status)}",
            ColorUtils.calculateLuminance(status) > 0.5,
        )
        assertTrue(
            "text must stay legible against the panel",
            ColorUtils.calculateContrast(status, surface) >= 4.5,
        )
    }

    @Test
    @Config(sdk = [34])
    fun `panel surface stays light in day mode`() {
        val (_, root) = panel()

        val surface = backgroundColor(root)
        assertTrue(
            "panel surface must stay light in day mode, was #${Integer.toHexString(surface)}",
            ColorUtils.calculateLuminance(surface) > 0.7,
        )
        assertTrue(
            "text must stay legible against the panel",
            ColorUtils.calculateContrast(statusText(root).currentTextColor, surface) >= 4.5,
        )
    }

    /** #240: a blocked mic must still read as blocked, in either ui mode. */
    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `secure-field mic stays visibly disabled against a dark surface`() {
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        val root = service.onCreateInputView()
        service.onStartInput(secureEditor(), false)

        assertEquals(ImeUiState.SECURE_FIELD, service.lastRenderedStateForTest())
        val mic = micButton(root)
        val disabled = backgroundColor(mic)
        val surface = backgroundColor(root)

        assertTrue("a blocked mic must not be tappable", !mic.isEnabled)
        assertTrue(
            "disabled mic must not be brighter than the dark panel around it",
            ColorUtils.calculateLuminance(disabled) < 0.3,
        )
        assertTrue(
            "disabled mic must not reuse the enabled accent",
            disabled != backgroundColor(micButton(panel().second)),
        )
    }

    /** A schedule crossing sunset must repaint a keyboard that is already open. */
    @Test
    @Config(sdk = [34])
    fun `night mode flip while visible repaints the panel`() {
        val (service, dayRoot) = panel()
        val dayStatus = service.lastRenderedStateForTest()
        assertNotNull(dayStatus)
        val daySurface = backgroundColor(dayRoot)

        val night = android.content.res.Configuration(service.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        service.onConfigurationChanged(night)

        assertEquals(
            "a repaint must not reset what the panel was showing",
            dayStatus,
            service.lastRenderedStateForTest(),
        )
        assertTrue(
            "the surface must actually change on a night flip",
            ColorUtils.calculateLuminance(daySurface) > 0.7,
        )
    }
}
