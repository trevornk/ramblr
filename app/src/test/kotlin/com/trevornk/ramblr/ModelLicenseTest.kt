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
        // Permits redistribution and commercial use, but is not DFSG/FSF/OSI-approved (use
        // restrictions such as litigation termination) -- see NVIDIA_OPEN_MODEL_LICENSE's kdoc.
        assertFalse(NVIDIA_OPEN_MODEL_LICENSE.isFree)
    }

    @Test fun `only non-free models require license consent`() {
        assertTrue(LOCAL_CLEANUP_MODEL.requiresLicenseConsent)
        assertFalse(MUMBLE_CLEANUP_Q4_0_MODEL.requiresLicenseConsent)
        for (m in MODEL_CATALOG + STREAMING_MODEL_CATALOG + VAD_MODEL_CATALOG) {
            assertEquals(
                "${m.name}: consent must be required exactly when the license is non-free",
                !m.license.isFree,
                m.requiresLicenseConsent,
            )
        }
    }

    /**
     * The core dictation feature must never *depend* on a non-free model. Since Parakeet
     * Unified 0.6B (#197, NVIDIA Open Model License) that invariant is deliberately weaker than
     * "every transcription model is free": a non-free transcription entry may exist as an
     * optional, consent-gated quality upgrade, but it must never be `recommended` (what
     * onboarding auto-downloads) and free alternatives must remain in the catalog, so a user who
     * declines the license still has the full dictation feature. Streaming preview and VAD stay
     * strictly free: they have single-entry catalogs with no alternative to fall back to.
     */
    @Test fun `core dictation never depends on a non-free model`() {
        for (m in MODEL_CATALOG) {
            if (m.recommended) assertTrue("the default ASR model must be free", m.license.isFree)
        }
        assertTrue(
            "at least one free offline ASR alternative must remain",
            MODEL_CATALOG.any { it.license.isFree },
        )
        for (m in STREAMING_MODEL_CATALOG + VAD_MODEL_CATALOG) {
            assertTrue("${m.name} must be freely licensed", m.license.isFree)
        }
    }

    /**
     * Pins the exact set of non-free models so adding another one is a deliberate, reviewed act
     * rather than something that slips in with a new catalog entry. Every non-free entry must be
     * consent-gated and optional -- either an optional cleanup model or a non-default ASR
     * upgrade (Parakeet Unified 0.6B, #197) -- never a core transcription path the user cannot
     * decline.
     *
     * LFM2.5-350M is the one non-free entry that is still `recommended`. That is not ideal --
     * the F-Droid-correct end state is a *free* default -- but it is no longer blocked on hosting:
     * [MUMBLE_CLEANUP_Q4_0_MODEL] (Apache-2.0, measurably faster on-device at ~2.9s, and ahead on
     * the offline cleanup-quality A/B) is now downloadable from a pinned Hugging Face URL like
     * every other entry. Flipping which model is `recommended` is a user-visible default change
     * that needs on-device confirmation of the winner first, so it is deliberately NOT bundled
     * with the hosting work and remains tracked in #134. Until that flip, the non-free default is
     * disclosed in the store description, declared as a NonFreeAssets anti-feature, and gated
     * behind explicit license consent at [ModelDownloadWorker.enqueue].
     */
    @Test fun `the non-free models are exactly the known consent-gated exceptions`() {
        val all = MODEL_CATALOG + STREAMING_MODEL_CATALOG + LOCAL_CLEANUP_MODEL_CATALOG + VAD_MODEL_CATALOG
        val nonFree = all.filter { !it.license.isFree }
        assertEquals(
            "Adding a non-free model is a deliberate F-Droid-visible decision. If this fails, " +
                "either the new entry needs review or a non-free model was made free by mistake.",
            listOf(
                "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming",
                LOCAL_CLEANUP_MODEL.archive,
            ),
            nonFree.map { it.archive },
        )
        // Whatever the non-free set is, every member must be consent-gated, and any non-free
        // ASR entry must additionally be a non-default upgrade (see this test's kdoc).
        for (m in nonFree) {
            assertTrue("${m.name} must require license consent", m.requiresLicenseConsent)
            if (!m.isLocalCleanup) {
                assertFalse("${m.name}: a non-free ASR entry must never be the default", m.recommended)
            }
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

    @Test fun `consent message for the non-free ASR entry states its own terms, not the cleanup model's`() {
        // The copy was hardcoded for LFM back when that was the only non-free model; shown in
        // front of the NVIDIA-OML ASR download it would have claimed a revenue cap that isn't in
        // that license and called a transcription model a cleanup model (#197).
        val unified = MODEL_CATALOG.first { !it.license.isFree }
        val msg = ModelLicenseConsent.consentMessage(unified)
        assertTrue(msg.contains(unified.name))
        assertTrue(msg.contains(NVIDIA_OPEN_MODEL_LICENSE.name))
        assertTrue(msg.contains(NVIDIA_OPEN_MODEL_LICENSE.url))
        assertTrue("must say plainly that it isn't FOSS", msg.contains("not free/open-source"))
        assertTrue("must say the app works without it", msg.contains("without it"))
        assertFalse("must not misstate LFM's revenue-cap restriction", msg.contains("larger companies"))
        assertFalse("must not call a transcription model a cleanup model", msg.contains("cleanup"))
    }
}
