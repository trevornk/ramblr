package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OmniRoute.BASE_URL is sourced from BuildConfig.OMNIROUTE_BASE_URL (populated from
 * local.properties, gitignored -- see app/build.gradle.kts). This repo is public and CI never
 * sets OMNIROUTE_BASE_URL, so BuildConfig.OMNIROUTE_BASE_URL is always "" in this test run --
 * these tests pin the blank-by-default contract itself (isConfigured tracks BASE_URL, not a
 * hardcoded host) rather than depending on any particular non-blank value.
 */
class OmniRouteTest {

    @Test fun `isConfigured is false when BASE_URL is blank, as it is for any build without a local OMNIROUTE_BASE_URL override`() {
        assertEquals(OmniRoute.BASE_URL.isBlank(), !OmniRoute.isConfigured)
    }

    @Test fun `isConfigured tracks BASE_URL blankness rather than a hardcoded assumption`() {
        // Whatever BASE_URL resolves to for this build (blank on CI/public clones, or a real
        // gateway URL for a dev's own local.properties), isConfigured must agree with it.
        assertEquals(OmniRoute.BASE_URL.isNotBlank(), OmniRoute.isConfigured)
    }
}
