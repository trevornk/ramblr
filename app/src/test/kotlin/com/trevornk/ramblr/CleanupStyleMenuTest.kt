package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #312: the cleanup-style menu model shared by the floating-ring long-press menu and the keyboard's
 * style picker. Both hosts must read and write the SAME prefs through [CleanupStyleMenu], so the
 * two surfaces can never disagree about the selected style or whether cleanup is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupStyleMenuTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // Consent already given by default; the gate tests below withdraw it explicitly.
        prefs().edit().clear().putBoolean("local_cleanup_consent_seen", true).apply()
    }

    @After fun tearDown() { prefs().edit().clear().apply() }

    private fun prefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    @Test fun `fresh install lists the default quick-menu personas with cleanup off`() {
        val model = CleanupStyleMenu.load(app, packageName = null)

        assertFalse(model.cleanupEnabled)
        assertEquals(QuickMenuPersonaStore.defaultSelection(), model.personas.map { it.key })
    }

    @Test fun `custom personas chosen in the style manager appear in the list`() {
        val custom = CustomPersonaStore.add(app, "Standup notes", "Bullets", "Rewrite as bullets.")
        QuickMenuPersonaStore.setSelection(app, listOf("formal", "casual", "notes", custom.key))

        val model = CleanupStyleMenu.load(app, packageName = null)

        assertEquals(listOf("formal", "casual", "notes", custom.key), model.personas.map { it.key })
        assertEquals("Standup notes", model.personas.last().title)
    }

    @Test fun `current is the saved global style`() {
        prefs().edit().putString("cleanup_style", "casual").apply()

        assertEquals(CleanupPersonas.CASUAL, CleanupStyleMenu.load(app, "com.example.app").current)
    }

    @Test fun `current follows the per-app memory for the editor package when per-app is on`() {
        prefs().edit().putString("cleanup_style", "casual").apply()
        PerAppPersonaStore.record(app, "com.example.app", CleanupPersonas.FORMAL)

        assertEquals(CleanupPersonas.FORMAL, CleanupStyleMenu.load(app, "com.example.app").current)
        // A different app, and an unknown package, fall back to the global style.
        assertEquals(CleanupPersonas.CASUAL, CleanupStyleMenu.load(app, "com.other").current)
        assertEquals(CleanupPersonas.CASUAL, CleanupStyleMenu.load(app, null).current)
    }

    @Test fun `per-app memory is ignored when the per-app toggle is off`() {
        prefs().edit().putString("cleanup_style", "casual").apply()
        PerAppPersonaStore.record(app, "com.example.app", CleanupPersonas.FORMAL)
        PerAppPersonaToggle.setEnabled(app, false)

        assertEquals(CleanupPersonas.CASUAL, CleanupStyleMenu.load(app, "com.example.app").current)
    }

    @Test fun `select writes the exact prefs Settings and the dictation runtime read`() {
        val result = CleanupStyleMenu.select(app, "com.example.app", CleanupPersonas.FORMAL)

        assertEquals("formal", prefs().getString("cleanup_style", null))
        assertEquals(
            CleanupPersonas.promptForExplicitSelection(CleanupPersonas.FORMAL),
            prefs().getString("post_processing_prompt", null),
        )
        assertEquals("com.example.app".let { PerAppPersonaStore.personaKeyFor(app, it) }, "formal")
        assertEquals(CleanupPersonas.FORMAL, result.persona)
    }

    @Test fun `select also turns cleanup on`() {
        assertFalse(PostProcessingToggle.isEnabled(app))

        val result = CleanupStyleMenu.select(app, "com.example.app", CleanupPersonas.CASUAL)

        assertTrue(result.cleanupEnabled)
        assertTrue(PostProcessingToggle.isEnabled(app))
    }

    @Test fun `select still saves the style but leaves cleanup off when the consent gate blocks it`() {
        // use_local defaults true; with cloud cleanup enabled and no consent seen, turning cleanup
        // on must go through Settings (#23) rather than silently sending local transcripts out.
        prefs().edit().putBoolean("use_local", true).putBoolean("local_cleanup_consent_seen", false).apply()
        CloudFeatureToggle.setCleanupEnabled(app, true)

        val result = CleanupStyleMenu.select(app, "com.example.app", CleanupPersonas.CASUAL)

        assertFalse(result.cleanupEnabled)
        assertFalse(PostProcessingToggle.isEnabled(app))
        assertEquals("casual", prefs().getString("cleanup_style", null))
    }

    @Test fun `select skips per-app memory when the per-app toggle is off`() {
        PerAppPersonaToggle.setEnabled(app, false)

        CleanupStyleMenu.select(app, "com.example.app", CleanupPersonas.FORMAL)

        assertEquals(null, PerAppPersonaStore.personaKeyFor(app, "com.example.app"))
    }

    @Test fun `toggle turns cleanup off instantly and on again`() {
        PostProcessingToggle.setEnabled(app, true)

        assertEquals(CleanupStyleMenu.ToggleResult.TURNED_OFF, CleanupStyleMenu.toggleCleanup(app))
        assertFalse(PostProcessingToggle.isEnabled(app))
        assertEquals(CleanupStyleMenu.ToggleResult.TURNED_ON, CleanupStyleMenu.toggleCleanup(app))
        assertTrue(PostProcessingToggle.isEnabled(app))
    }

    @Test fun `toggle on is refused with NEEDS_SETTINGS while the consent gate blocks it`() {
        prefs().edit().putBoolean("use_local", true).putBoolean("local_cleanup_consent_seen", false).apply()
        CloudFeatureToggle.setCleanupEnabled(app, true)

        assertEquals(CleanupStyleMenu.ToggleResult.NEEDS_SETTINGS, CleanupStyleMenu.toggleCleanup(app))
        assertFalse(PostProcessingToggle.isEnabled(app))
    }

    @Test fun `a selection made through one host is what the other host reads back`() {
        // The ring menu and the IME both call load()/select() with their own package source.
        CleanupStyleMenu.select(app, "com.example.app", CleanupPersonas.NOTES) // "ring"
        val seenByIme = CleanupStyleMenu.load(app, "com.example.app")           // "ime"

        assertEquals(CleanupPersonas.NOTES, seenByIme.current)
        assertTrue(seenByIme.cleanupEnabled)
    }
}
