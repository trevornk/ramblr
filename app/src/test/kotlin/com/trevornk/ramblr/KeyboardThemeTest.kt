package com.trevornk.ramblr

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The keyboard's own Light/Dark choice wins over the system's night mode. */
@RunWith(RobolectricTestRunner::class)
class KeyboardThemeTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    private val editor = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "harness.example"
        fieldId = 9
    }

    private fun panel(): Pair<RamblrImeService, View> {
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        val view = service.onCreateInputView()
        service.onStartInput(editor, false)
        service.onStartInputView(editor, false)
        return service to view
    }

    private fun surfaceLuminance(view: View): Double =
        ColorUtils.calculateLuminance((view.background as GradientDrawable).color!!.defaultColor)

    @Test
    @Config(sdk = [34])
    fun `defaults to following the system`() {
        assertEquals(KeyboardTheme.SYSTEM, KeyboardTheme.current(app))
        assertTrue(surfaceLuminance(panel().second) > 0.7)
    }

    @Test
    @Config(sdk = [34])
    fun `dark keyboard on a light system`() {
        KeyboardTheme.set(app, KeyboardTheme.DARK)
        assertTrue(surfaceLuminance(panel().second) < 0.2)
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `light keyboard on a dark system`() {
        KeyboardTheme.set(app, KeyboardTheme.LIGHT)
        assertTrue(surfaceLuminance(panel().second) > 0.7)
    }

    @Test
    @Config(sdk = [34])
    fun `forcing a theme keeps the user's font scale`() {
        val override = KeyboardTheme.DARK.nightOverride()!!
        assertEquals("fontScale must stay undefined so the system value survives", 0f, override.fontScale)
        assertEquals(null, KeyboardTheme.SYSTEM.nightOverride())
    }

    @Test
    @Config(sdk = [34])
    fun `a theme changed in Settings applies the next time the keyboard opens`() {
        val (service, first) = panel()
        assertTrue(surfaceLuminance(first) > 0.7)

        KeyboardTheme.set(app, KeyboardTheme.DARK)
        service.onStartInputView(editor, false)

        val rebuilt = service.panelRootForTest()!!
        assertTrue("the panel is rebuilt, not repainted in place", rebuilt !== first)
        assertTrue(surfaceLuminance(rebuilt) < 0.2)
    }

    @Test
    @Config(sdk = [34])
    fun `an unknown stored value falls back to the system theme`() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putString(KeyboardTheme.KEY, "sepia").apply()
        assertEquals(KeyboardTheme.SYSTEM, KeyboardTheme.current(app))
    }
}
