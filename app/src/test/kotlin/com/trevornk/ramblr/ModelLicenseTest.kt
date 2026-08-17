package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

/**
 * F-Droid inclusion review (fdroiddata!42401): the catalog's licensing invariants.
 *
 * These are pure catalog/policy assertions with no Android dependency -- the consent *storage*
 * needs a Context and is exercised on-device, but the rules that actually decide whether a
 * download is allowed live in [Model.requiresLicenseConsent] and the catalog data itself, and
 * those are the parts a future edit is most likely to silently break.
 */
class ModelLicenseTest {

    @Test fun `free licenses are marked free and non-free ones are not`() {
        assertTrue(CC_BY_4_0.isFree)
        assertTrue(CC_BY_SA_4_0.isFree)
        assertTrue(APACHE_2_0.isFree)
        // Free of charge for most users, but restricts commercial use above a revenue
        // threshold -- that is a field-of-endeavor restriction, so it is not FLOSS.
        assertFalse(LFM_OPEN_LICENSE_1_0.isFree)
    }

    @Test fun `only non-free models require license consent`() {
        assertTrue(LOCAL_CLEANUP_MODEL.requiresLicenseConsent)
        assertFalse(MUMBLE_CLEANUP_Q4_0_MODEL.requiresLicenseConsent)
        for (m in MODEL_CATALOG + STREAMING_MODEL_CATALOG + VAD_MODEL_CATALOG) {
            assertFalse(
                "${m.name} is a transcription/VAD model and must stay freely licensed",
                m.requiresLicenseConsent,
            )
        }
    }

    /**
     * The core dictation feature must never depend on a non-free model. Cleanup is optional and
     * may offer one (with consent); transcription, streaming preview, and VAD may not, because a
     * user cannot opt out of them and still use the app.
     */
    @Test fun `every transcription streaming and vad model is freely licensed`() {
        for (m in MODEL_CATALOG + STREAMING_MODEL_CATALOG + VAD_MODEL_CATALOG) {
            assertTrue("${m.name} must be freely licensed", m.license.isFree)
        }
    }

    /**
     * Pins the exact set of non-free models so adding another one is a deliberate, reviewed act
     * rather than something that slips in with a new catalog entry. Every non-free entry must be
     * optional-cleanup-only (never a core transcription path) and consent-gated.
     *
     * LFM2.5-350M is the one known exception and is still `recommended`. That is not ideal --
     * the F-Droid-correct end state is a *free* default -- but it is no longer blocked on hosting:
     * [MUMBLE_CLEANUP_Q4_0_MODEL] (Apache-2.0, measurably faster on-device at ~2.9s, and ahead on
     * the offline cleanup-quality A/B) is now downloadable from a pinned Hugging Face URL like
     * every other entry. Flipping which model is `recommended` is a user-visible default change
     * that needs on-device confirmation of the winner first, so it is deliberately NOT bundled
     * with the hosting work and remains tracked in #134. Until that flip, the non-free default is
     * disclosed in the store description, declared as a NonFreeAssets anti-feature, and gated
     * behind explicit license consent at [ModelDownloadWorker.enqueue].
     */
    @Test fun `the only non-free model is the known optional cleanup exception`() {
        val all = MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG + VAD_MODEL_CATALOG
        val nonFree = all.filter { !it.license.isFree }
        assertEquals(
            "Adding a non-free model is a deliberate F-Droid-visible decision. If this fails, " +
                "either the new entry needs review or a non-free model was made free by mistake.",
            listOf(LOCAL_CLEANUP_MODEL.archive),
            nonFree.map { it.archive },
        )
        // Whatever the non-free set is, every member must be optional cleanup and consent-gated.
        for (m in nonFree) {
            assertTrue("${m.name} must be an optional cleanup model", m.isLocalCleanup)
            assertTrue("${m.name} must require license consent", m.requiresLicenseConsent)
        }
    }

    @Test fun `every catalog entry names a license with a terms url`() {
        val all = MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG + VAD_MODEL_CATALOG
        for (m in all) {
            assertTrue("${m.name} has no license name", m.license.name.isNotBlank())
            assertTrue(
                "${m.name}'s license url must be a real link the user can open",
                m.license.url.startsWith("https://"),
            )
        }
    }

    /**
     * The consent copy has to state what the restriction actually is. "Non-free" alone doesn't
     * let a user decide, and an empty/placeholder message would defeat the whole gate.
     */
    @Test fun `consent message names the license and links its terms`() {
        val msg = ModelLicenseConsent.consentMessage(LOCAL_CLEANUP_MODEL)
        assertTrue(msg.contains(LOCAL_CLEANUP_MODEL.name))
        assertTrue(msg.contains(LFM_OPEN_LICENSE_1_0.name))
        assertTrue(msg.contains(LFM_OPEN_LICENSE_1_0.url))
        assertTrue("must say plainly that it isn't FOSS", msg.contains("not free/open-source"))
        assertTrue("must say the app works without it", msg.contains("without it"))
    }
}
