package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers [SelfUpdateStatusFormatter], the pure status-string logic backing Part 3's Settings
 *  status row -- lives in src/testGithub/ (not src/test/) since the production code under test
 *  lives in src/github/, mirroring [SelfUpdateResolverTest]. */
class SelfUpdateStatusFormatterTest {

    // --- statusLine ---

    @Test fun `statusLine reports not checked yet when there is no result`() {
        assertEquals("Not checked yet", SelfUpdateStatusFormatter.statusLine(null))
    }

    @Test fun `statusLine reports the version name when an update is available`() {
        val update = UpdateCheckResult.UpdateAvailable(
            versionName = "1.0.11", versionCode = 14, downloadUrl = "https://x", sha256 = null,
            releaseUrl = "https://x", sizeBytes = 100L,
        )
        assertEquals("Update available: v1.0.11", SelfUpdateStatusFormatter.statusLine(update))
    }

    @Test fun `statusLine reports up to date`() {
        assertEquals("Up to date", SelfUpdateStatusFormatter.statusLine(UpdateCheckResult.UpToDate))
    }

    @Test fun `statusLine reports a generic failure message, not the raw diagnostic reason`() {
        val result = UpdateCheckResult.CheckFailed("fetch failed or returned unparseable JSON")
        assertEquals("Couldn't check for updates", SelfUpdateStatusFormatter.statusLine(result))
    }

    // --- lastCheckedLine ---

    @Test fun `lastCheckedLine reports never checked when null`() {
        assertEquals("Never checked", SelfUpdateStatusFormatter.lastCheckedLine(null, nowMs = 1_000L))
    }

    @Test fun `lastCheckedLine reports just now for sub-minute deltas`() {
        assertEquals("Last checked just now", SelfUpdateStatusFormatter.lastCheckedLine(1_000L, nowMs = 1_500L))
    }

    @Test fun `lastCheckedLine reports minutes for sub-hour deltas`() {
        val fiveMinutesMs = 5 * 60_000L
        assertEquals("Last checked 5m ago", SelfUpdateStatusFormatter.lastCheckedLine(0L, nowMs = fiveMinutesMs))
    }

    @Test fun `lastCheckedLine reports hours for sub-day deltas`() {
        val threeHoursMs = 3 * 60 * 60_000L
        assertEquals("Last checked 3h ago", SelfUpdateStatusFormatter.lastCheckedLine(0L, nowMs = threeHoursMs))
    }

    @Test fun `lastCheckedLine reports days for multi-day deltas`() {
        val twoDaysMs = 2 * 24 * 60 * 60_000L
        assertEquals("Last checked 2d ago", SelfUpdateStatusFormatter.lastCheckedLine(0L, nowMs = twoDaysMs))
    }

    @Test fun `lastCheckedLine never goes negative for a clock skew where lastChecked is after now`() {
        assertEquals("Last checked just now", SelfUpdateStatusFormatter.lastCheckedLine(10_000L, nowMs = 1_000L))
    }

    // --- subtitle ---

    @Test fun `subtitle combines all three facts`() {
        val subtitle = SelfUpdateStatusFormatter.subtitle(
            result = UpdateCheckResult.UpToDate,
            lastCheckedAtMs = 0L,
            nowMs = 5 * 60_000L,
            runningVersionName = "1.0.10",
            runningVersionCode = 13,
        )
        assertEquals("Last checked 5m ago · Running v1.0.10 (13) · Up to date", subtitle)
    }
}
