package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [SelfUpdateCheckDecision], the pure per-tick decision logic split out of the Part 5
 *  periodic [SelfUpdateCheckWorker] (github distribution flavor only). Lives in src/testGithub/
 *  (not src/test/) because the production code under test lives in src/github/ -- mirrors
 *  [SelfUpdateResolverTest]/[SelfUpdateInstallGateTest]. */
class SelfUpdateCheckDecisionTest {

    private val update = UpdateCheckResult.UpdateAvailable(
        versionName = "1.0.11",
        versionCode = 14,
        downloadUrl = "https://example.com/app.apk",
        sha256 = "abc123",
        releaseUrl = "https://example.com/releases/v1.0.11",
        sizeBytes = 12345L,
    )

    // -- UpdateAvailable + every notify/autoInstall combination --

    @Test fun `UpdateAvailable with notify and autoInstall both on notifies and auto-installs`() {
        assertTrue(SelfUpdateCheckDecision.shouldNotify(update, notifyEnabled = true))
        assertTrue(SelfUpdateCheckDecision.shouldAutoInstall(update, autoInstallEnabled = true))
    }

    @Test fun `UpdateAvailable with notify on and autoInstall off notifies but does not auto-install`() {
        assertTrue(SelfUpdateCheckDecision.shouldNotify(update, notifyEnabled = true))
        assertFalse(SelfUpdateCheckDecision.shouldAutoInstall(update, autoInstallEnabled = false))
    }

    @Test fun `UpdateAvailable with notify off and autoInstall on still auto-installs (independent toggles)`() {
        // Design decision (documented in SelfUpdateCheckDecision's kdoc): auto-install is its own
        // explicit opt-in and must not be silently disabled just because notifications are off.
        assertFalse(SelfUpdateCheckDecision.shouldNotify(update, notifyEnabled = false))
        assertTrue(SelfUpdateCheckDecision.shouldAutoInstall(update, autoInstallEnabled = true))
    }

    @Test fun `UpdateAvailable with both toggles off does neither`() {
        assertFalse(SelfUpdateCheckDecision.shouldNotify(update, notifyEnabled = false))
        assertFalse(SelfUpdateCheckDecision.shouldAutoInstall(update, autoInstallEnabled = false))
    }

    // -- UpToDate: never notify/install regardless of toggles --

    @Test fun `UpToDate never notifies or auto-installs even with both toggles on`() {
        assertFalse(SelfUpdateCheckDecision.shouldNotify(UpdateCheckResult.UpToDate, notifyEnabled = true))
        assertFalse(SelfUpdateCheckDecision.shouldAutoInstall(UpdateCheckResult.UpToDate, autoInstallEnabled = true))
    }

    // -- CheckFailed: never notify/install regardless of toggles --

    @Test fun `CheckFailed never notifies or auto-installs even with both toggles on`() {
        val failed = UpdateCheckResult.CheckFailed("network error")
        assertFalse(SelfUpdateCheckDecision.shouldNotify(failed, notifyEnabled = true))
        assertFalse(SelfUpdateCheckDecision.shouldAutoInstall(failed, autoInstallEnabled = true))
    }
}
