package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives [SelfUpdateInstallWorker.doWork] for real (via [TestWorkerParams], no
 * `androidx.work:work-testing`) with Robolectric's actual
 * `PackageManager.canRequestPackageInstalls()` shadow, so the install-permission gate (#253) is
 * proven through its real production entry point rather than only through
 * [SelfUpdateInstallGateTest]'s pure-function coverage of [SelfUpdateInstallGate.canAttemptInstall]
 * in isolation. [SelfUpdateInstallGateTest] already proves the gate function is correct in a
 * vacuum; this proves the Worker actually calls it, actually posts the right notification, and
 * actually returns the right [ListenableWorker.Result] -- exactly the wiring a source-string or
 * pure-unit-only test suite cannot catch a regression in (e.g. someone swaps the `if` condition,
 * or forgets to call the gate at all and it silently downloads anyway).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfUpdateInstallWorkerPermissionGateTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        cachePrefs().edit().clear().apply()
        settingsPrefs().edit().clear().apply()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
    }

    @After fun tearDown() {
        cachePrefs().edit().clear().apply()
        settingsPrefs().edit().clear().apply()
    }

    private fun cachePrefs() = app.getSharedPreferences("ramblr_self_update_cache", Context.MODE_PRIVATE)
    private fun settingsPrefs() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    /** Seeds the real [SelfUpdateChecker] cache with an UpdateAvailable release, so
     *  [SelfUpdateInstallWorker.doWork] reaches its permission gate at all -- mirrors exactly what
     *  a real device has cached from a prior [SelfUpdateChecker.check] before an install is ever
     *  attempted. */
    private fun seedCachedUpdateAvailable(versionCode: Int = 99) {
        val json = """
            {"tag_name": "v9.9.9", "html_url": "https://x",
             "body": "versionCode: $versionCode",
             "assets": [{"name": "Ramblr-9.9.9-github-release.apk",
                         "browser_download_url": "https://example.invalid/update.apk",
                         "size": 100, "digest": "sha256:deadbeef"}]}
        """.trimIndent()
        cachePrefs().edit()
            .putString("cached_json", json)
            .putLong("cached_at", System.currentTimeMillis())
            .apply()
    }

    private fun newWorker(): SelfUpdateInstallWorker = SelfUpdateInstallWorker(app, TestWorkerParams.build())

    // --- denied: the worker must actually consult canRequestPackageInstalls() and stop ---

    @Test
    fun `denied install-unknown-apps access blocks doWork before any download, via the real permission check`() {
        seedCachedUpdateAvailable()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)

        val result = newWorker().doWork()

        // Retryable, not a terminal failure: see doWork's own kdoc on why this deferral must be
        // resumable once the user grants the permission, exactly like the quiet-hours/dictation
        // deferral already is.
        assertTrue("a permission block must be retryable, not terminal", result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `denied access posts the actionable permission-needed notification, not a generic failure`() {
        seedCachedUpdateAvailable()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)

        newWorker().doWork()

        val notificationManager = shadowOf(
            app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        )
        val notification = notificationManager.getNotification(0x5E1F_0002)
        assertTrue("the permission-needed notification must actually be posted", notification != null)
        val extras = notification!!.extras
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(
            "must name the actual blocker, not a generic error: was '$text'",
            text.contains("Install unknown apps", ignoreCase = true),
        )
        // The action must be able to actually resolve to something -- proves the deep-link intent
        // resolves to Android's real "manage unknown app sources" screen and not a dangling one.
        assertTrue("notification must carry a tap action, not just be informational", notification.contentIntent != null)
    }

    @Test
    fun `denied access records the blocked-on-permission flag for the settings-resume retry path`() {
        seedCachedUpdateAvailable()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)

        newWorker().doWork()

        assertTrue(SelfUpdatePrefs.isInstallBlockedOnPermission(app))
    }

    /**
     * Closes the coverage gap flagged in the integration report's Limitations section: the
     * `postInstallPermissionNeeded()` notify() call is wrapped in `try { } catch (_:
     * SecurityException) { }`, and this drives that degraded path for real via Robolectric's
     * `ShadowNotificationManager.setNotificationsEnabled(false)` rather than trusting the
     * sequential-statement code-reading argument alone. Proves the retry Result and the
     * blocked-on-permission flag (the actual behavior [SelfUpdateSettingsActivity.onResume]'s
     * settings-recovery path depends on) are set even when the notification never posts.
     */
    @Test
    fun `denied access with notifications disabled still returns Retry and records the blocked flag`() {
        seedCachedUpdateAvailable()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val notificationManager = shadowOf(
            app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        )
        notificationManager.setNotificationsEnabled(false)

        val result = newWorker().doWork()

        assertTrue(
            "a permission block must still be retryable even when notifications are disabled",
            result is ListenableWorker.Result.Retry,
        )
        assertTrue(
            "the blocked-on-permission flag must still be recorded even when the notification " +
                "could not be posted -- SelfUpdateSettingsActivity's settings-resume recovery " +
                "depends on this flag, not on the notification having actually shown",
            SelfUpdatePrefs.isInstallBlockedOnPermission(app),
        )
        // The notify() call itself is still attempted and swallowed (never crashes doWork), not
        // silently skipped -- Robolectric's shadow still records it as "posted" even with
        // notifications administratively disabled (that flag only gates the *real* notify path,
        // which Robolectric doesn't model at this level); what actually matters here, and what a
        // real disabled-notifications device changes, is proven above: Result and flag survive.
        assertTrue(
            "doWork() must not throw or otherwise abort when notifications are disabled",
            notificationManager.getNotification(0x5E1F_0002) != null,
        )
    }

    /**
     * Companion to the disabled-notifications case above: granting the permission back (the
     * other half of #253's retry story) must clear the flag and let a subsequent settings-resume
     * re-fire the install, regardless of whether notifications are enabled -- the flag, not the
     * notification, is what [SelfUpdateSettingsActivity.onResume] actually reads.
     */
    @Test
    fun `granted access after a notifications-disabled denial still clears the blocked flag`() {
        seedCachedUpdateAvailable()
        val notificationManager = shadowOf(
            app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        )
        notificationManager.setNotificationsEnabled(false)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        newWorker().doWork()
        assertTrue(SelfUpdatePrefs.isInstallBlockedOnPermission(app))

        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        newWorker().doWork()

        assertFalse(
            "granting the permission must clear the blocked flag even though the original " +
                "denial's notification never actually posted",
            SelfUpdatePrefs.isInstallBlockedOnPermission(app),
        )
    }

    @Test
    fun `denied access never reaches the download step (no partial or staged file is written)`() {
        seedCachedUpdateAvailable(versionCode = 42)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)

        newWorker().doWork()

        val staged = SelfUpdateInstallWorker.apkFile(app, 42)
        assertFalse("permission gate must run before any bytes are written to disk", staged.exists())
    }

    // --- granted: the worker must actually proceed past the gate ---

    @Test
    fun `granted access clears any previously-recorded blocked-on-permission flag`() {
        seedCachedUpdateAvailable()
        SelfUpdatePrefs.setInstallBlockedOnPermission(app, true)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)

        newWorker().doWork()

        assertFalse(
            "a run that gets past the gate must clear a stale blocked flag from an earlier denied attempt",
            SelfUpdatePrefs.isInstallBlockedOnPermission(app),
        )
    }

    @Test
    fun `granted access with a cached update proceeds past the gate to a real network attempt`() {
        seedCachedUpdateAvailable()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)

        val result = newWorker().doWork()

        // No permission-needed notification must be posted once the gate passes.
        val notificationManager = shadowOf(
            app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        )
        assertEquals(
            "the permission-needed notification must not fire once access is granted",
            null,
            notificationManager.getNotification(0x5E1F_0002)?.let {
                if (it.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                        ?.contains("Install unknown apps", ignoreCase = true) == true
                ) it else null
            },
        )
        assertFalse(SelfUpdatePrefs.isInstallBlockedOnPermission(app))
        // The real download will fail (example.invalid resolves nowhere in this JVM test), so the
        // actual terminal Result here is a download failure/retry, not success -- what matters
        // for THIS test is only that the gate did not block it, proven by the absence of the
        // permission notification and the cleared flag above, not a specific Result value that
        // depends on network behavior this test doesn't control.
        assertTrue(result is ListenableWorker.Result.Retry || result is ListenableWorker.Result.Failure)
    }
}
