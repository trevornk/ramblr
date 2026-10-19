package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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
 * #312: the floating ring's long-press style menu must keep working now that its model and writes
 * live in [CleanupStyleMenu] (shared with the keyboard picker). Drives the REAL service's menu
 * content: renders it, taps its rows, and checks the shared prefs -- then reads the result back
 * through [CleanupStyleMenu.load] exactly as the IME does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingStyleMenuSharedHelperTest {

    class TestService : WhisperAccessibilityService() {
        var foregroundPkg: String? = "com.example.chat"
        override fun getRootInActiveWindow(): AccessibilityNodeInfo? =
            foregroundPkg?.let { pkg -> AccessibilityNodeInfo.obtain().apply { packageName = pkg } }
    }

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().putBoolean("local_cleanup_consent_seen", true).apply()
    }

    @After fun tearDown() { prefs().edit().clear().apply() }

    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private fun service(): TestService {
        val s = Robolectric.buildService(TestService::class.java, null).create().get()
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay").apply { isAccessible = true }.invoke(s)
        return s
    }

    private fun menuOf(s: WhisperAccessibilityService): ViewGroup =
        WhisperAccessibilityService::class.java.getDeclaredMethod("buildStyleMenuContent")
            .apply { isAccessible = true }.invoke(s) as ViewGroup

    private fun texts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun tapRowTitled(root: View, titleContains: String) {
        fun find(v: View): TextView? {
            if (v is TextView && v.text.toString().contains(titleContains)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var v: View? = find(root) ?: error("no row containing '$titleContains' in ${texts(root)}")
        while (v != null && !v.isClickable) v = v.parent as? View
        assertNotNull("row for '$titleContains' must be clickable", v)
        v!!.performClick()
    }

    @Test fun `menu lists every style the shared model lists`() {
        val s = service()
        val shown = texts(menuOf(s))

        val model = CleanupStyleMenu.load(app, "com.example.chat")
        for (persona in model.personas) {
            assertTrue("missing ${persona.title} in $shown", shown.any { it.contains(persona.title) })
        }
    }

    @Test fun `tapping a style row selects it through the shared helper and the ime sees it`() {
        val s = service()

        tapRowTitled(menuOf(s), "Casual")

        assertEquals("casual", prefs().getString("cleanup_style", null))
        assertEquals(CleanupPersonas.promptForExplicitSelection(CleanupPersonas.CASUAL), prefs().getString("post_processing_prompt", null))
        assertTrue("selecting a style turns cleanup on", PostProcessingToggle.isEnabled(app))
        // Per-app memory is keyed on the ring's foreground package, which is what the IME will read.
        assertEquals("casual", PerAppPersonaStore.personaKeyFor(app, "com.example.chat"))
        val seenByIme = CleanupStyleMenu.load(app, "com.example.chat")
        assertEquals(CleanupPersonas.CASUAL, seenByIme.current)
        assertTrue(seenByIme.cleanupEnabled)
    }

    @Test fun `the current style is check-marked and re-rendering after a pick moves the mark`() {
        val s = service()
        CleanupStyleMenu.select(app, "com.example.chat", CleanupPersonas.NOTES) // picked from the keyboard

        val shown = texts(menuOf(s))

        assertTrue("keyboard pick must show as selected in the ring: $shown", shown.any { it.startsWith("✓") && it.contains(CleanupPersonas.NOTES.title) })
        assertEquals(1, shown.count { it.startsWith("✓") })
    }

    @Test fun `cleanup row toggles through the shared helper`() {
        val s = service()
        PostProcessingToggle.setEnabled(app, true)
        assertTrue(texts(menuOf(s)).contains("Cleanup: On"))

        tapRowTitled(menuOf(s), "Cleanup: On")

        assertFalse(PostProcessingToggle.isEnabled(app))
        assertTrue(texts(menuOf(s)).contains("Cleanup: Off"))
    }
}
