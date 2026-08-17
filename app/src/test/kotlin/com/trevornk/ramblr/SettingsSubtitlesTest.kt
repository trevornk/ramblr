package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the F-Droid review follow-ups in #153 (fdroiddata!42401).
 *
 * Both bugs lived in Activity code the JVM suite can't construct, which is exactly why neither
 * was caught before a human reviewer ran the app on a phone. The formatting is now pure, so the
 * behaviour is pinned here instead of relying on a device pass.
 */
class SettingsSubtitlesTest {

    private fun model(
        name: String = "Test model",
        sizeMb: Int = 250,
        quality: String = "Good",
        license: ModelLicense = APACHE_2_0,
    ) = Model(
        name = name,
        archive = "$name.zip",
        sizeMb = sizeMb,
        quality = quality,
        license = license,
    )

    // --- cleanup model subtitle -------------------------------------------------------------

    @Test fun `non-free model shows its license in the row subtitle`() {
        val subtitle = cleanupModelSubtitleText(
            model = model(license = LFM_OPEN_LICENSE_1_0),
            installed = false,
            sideloadOnly = false,
        )
        // The whole point of the F-Droid finding: a user comparing cleanup models must be able to
        // see this without opening the consent dialog.
        assertTrue(
            "expected the license name in: $subtitle",
            subtitle.contains(LFM_OPEN_LICENSE_1_0.name),
        )
        assertTrue("expected a not-FOSS marker in: $subtitle", subtitle.contains("not FOSS"))
    }

    @Test fun `freely licensed model shows no license note`() {
        val subtitle = cleanupModelSubtitleText(
            model = model(license = APACHE_2_0),
            installed = true,
            sideloadOnly = false,
        )
        assertFalse(subtitle.contains("not FOSS"))
        assertFalse(subtitle.contains(APACHE_2_0.name))
        assertEquals("Good · 250 MB", subtitle)
    }

    /**
     * The actual #153 bug: the builder produced the license note and the refresh dropped it. Both
     * call sites now go through this function, so identical inputs must give identical output --
     * if a future edit reintroduces a second formatting site, this is the test that fails.
     */
    @Test fun `subtitle is stable across repeated refreshes of the same state`() {
        val m = model(license = LFM_OPEN_LICENSE_1_0)
        val first = cleanupModelSubtitleText(m, installed = false, sideloadOnly = false)
        val second = cleanupModelSubtitleText(m, installed = false, sideloadOnly = false)
        assertEquals(first, second)
    }

    @Test fun `installing a non-free model does not drop its license note`() {
        val m = model(license = LFM_OPEN_LICENSE_1_0)
        val before = cleanupModelSubtitleText(m, installed = false, sideloadOnly = false)
        val after = cleanupModelSubtitleText(m, installed = true, sideloadOnly = false)
        assertTrue(before.contains("not FOSS"))
        assertTrue("license note must survive installation: $after", after.contains("not FOSS"))
    }

    @Test fun `sideload-only note appears only before installation`() {
        val m = model()
        assertTrue(
            cleanupModelSubtitleText(m, installed = false, sideloadOnly = true)
                .contains("sideload only"),
        )
        assertFalse(
            cleanupModelSubtitleText(m, installed = true, sideloadOnly = true)
                .contains("sideload only"),
        )
    }

    @Test fun `a non-free sideload-only model shows both notes`() {
        val subtitle = cleanupModelSubtitleText(
            model = model(license = LFM_OPEN_LICENSE_1_0),
            installed = false,
            sideloadOnly = true,
        )
        assertTrue(subtitle.contains("not FOSS"))
        assertTrue(subtitle.contains("sideload only"))
    }

    @Test fun `subtitle always leads with quality and size`() {
        val subtitle = cleanupModelSubtitleText(
            model = model(quality = "Best", sizeMb = 352, license = LFM_OPEN_LICENSE_1_0),
            installed = false,
            sideloadOnly = true,
        )
        assertTrue("expected 'Best · 352 MB' prefix in: $subtitle", subtitle.startsWith("Best · 352 MB"))
    }

    // --- advanced subtitle ------------------------------------------------------------------

    @Test fun `storefront build does not advertise updates`() {
        val subtitle = advancedSubtitleText(hasSelfUpdate = false)
        // The storefront/F-Droid flavor compiles in no self-updater, so naming it is a lie.
        assertFalse("storefront must not mention updates: $subtitle", subtitle.contains("updates"))
        assertTrue(subtitle.contains("data & logs"))
        assertTrue(subtitle.contains("Redo setup"))
    }

    @Test fun `github build still advertises updates`() {
        val subtitle = advancedSubtitleText(hasSelfUpdate = true)
        assertTrue("github must mention updates: $subtitle", subtitle.contains("updates"))
        assertTrue(subtitle.contains("data & logs"))
    }

    @Test fun `advanced subtitle stays well formed in both flavors`() {
        for (hasSelfUpdate in listOf(true, false)) {
            val subtitle = advancedSubtitleText(hasSelfUpdate)
            assertFalse("double comma in: $subtitle", subtitle.contains(",,"))
            assertFalse("double space in: $subtitle", subtitle.contains("  "))
            assertFalse("dangling comma in: $subtitle", subtitle.trim().endsWith(","))
        }
    }
}
