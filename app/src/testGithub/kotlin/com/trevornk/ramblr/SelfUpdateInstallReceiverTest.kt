package com.trevornk.ramblr

import android.content.Intent
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * #253: the async PackageInstaller callback is the ONLY place a terminal install failure can be
 * observed -- `commit()` returns immediately, so the worker has already returned success by the
 * time the real outcome arrives. These tests pin that the receiver turns that callback into
 * something the user can actually see, since the defect being fixed was a failure that was
 * detected and logged correctly and then visible only over adb.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfUpdateInstallReceiverTest {

    private fun notificationManager(): ShadowNotificationManager =
        org.robolectric.Shadows.shadowOf(
            RuntimeEnvironment.getApplication()
                .getSystemService(android.app.NotificationManager::class.java)
        )

    private fun callbackIntent(
        status: Int,
        message: String? = null,
        versionName: String? = "1.0.30",
        versionCode: Int = 30,
        releaseUrl: String? = "https://example.invalid/releases/v1.0.30",
    ): Intent = Intent().apply {
        putExtra(PackageInstaller.EXTRA_STATUS, status)
        message?.let { putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, it) }
        versionName?.let { putExtra(SelfUpdateInstallReceiver.EXTRA_VERSION_NAME, it) }
        if (versionCode != 0) putExtra(SelfUpdateInstallReceiver.EXTRA_VERSION_CODE, versionCode)
        releaseUrl?.let { putExtra(SelfUpdateInstallReceiver.EXTRA_RELEASE_URL, it) }
    }

    private fun deliver(intent: Intent) {
        SelfUpdateInstallReceiver().onReceive(RuntimeEnvironment.getApplication(), intent)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `a terminal failure posts a notification instead of only logging`() {
        val ctx = RuntimeEnvironment.getApplication()
        // Pre-existing "update available" prompt, exactly as SelfUpdateCheckWorker left it.
        SelfUpdateNotifications.postUpdateAvailable(
            ctx,
            UpdateCheckResult.UpdateAvailable(
                versionName = "1.0.30",
                versionCode = 30,
                downloadUrl = "https://example.invalid/a.apk",
                sha256 = null,
                releaseUrl = "https://example.invalid/releases/v1.0.30",
                sizeBytes = 1L,
            ),
        )
        assertEquals(1, notificationManager().size())

        deliver(
            callbackIntent(
                status = PackageInstaller.STATUS_FAILURE_CONFLICT,
                message = "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            )
        )

        val notifications = notificationManager().allNotifications
        assertEquals("stale prompt should be replaced, not added to", 1, notifications.size)
        val text = notifications.first().extras.getString(android.app.Notification.EXTRA_TITLE)
        assertTrue("expected a failure title, got: $text", text!!.contains("failed to install"))
        assertTrue("expected the version named", text.contains("1.0.30"))
    }

    @Test
    fun `the stale update-available notification is cancelled on failure`() {
        val ctx = RuntimeEnvironment.getApplication()
        val availableId = SelfUpdateNotifications.notificationId(30)
        SelfUpdateNotifications.postUpdateAvailable(
            ctx,
            UpdateCheckResult.UpdateAvailable(
                versionName = "1.0.30",
                versionCode = 30,
                downloadUrl = "https://example.invalid/a.apk",
                sha256 = null,
                releaseUrl = "https://example.invalid/releases/v1.0.30",
                sizeBytes = 1L,
            ),
        )
        assertTrue(notificationManager().getNotification(availableId) != null)

        deliver(callbackIntent(status = PackageInstaller.STATUS_FAILURE, message = "boom"))

        // This is the actual user-visible defect in #253: a permanently stale "Update available:
        // v1.0.30 / Tap to view the release" that does nothing when tapped.
        assertTrue(
            "update-available notification should be gone after a failed install",
            notificationManager().getNotification(availableId) == null,
        )
    }

    @Test
    fun `a successful install clears the update-available prompt`() {
        val ctx = RuntimeEnvironment.getApplication()
        val availableId = SelfUpdateNotifications.notificationId(30)
        SelfUpdateNotifications.postUpdateAvailable(
            ctx,
            UpdateCheckResult.UpdateAvailable(
                versionName = "1.0.30",
                versionCode = 30,
                downloadUrl = "https://example.invalid/a.apk",
                sha256 = null,
                releaseUrl = "https://example.invalid/releases/v1.0.30",
                sizeBytes = 1L,
            ),
        )

        deliver(callbackIntent(status = PackageInstaller.STATUS_SUCCESS))

        assertTrue(
            "offering an update the user just installed is wrong",
            notificationManager().getNotification(availableId) == null,
        )
        // And success must not masquerade as a failure.
        assertEquals(0, notificationManager().size())
    }

    @Test
    fun `pending user action with a confirmation intent is not treated as a failure`() {
        val confirm = Intent(Intent.ACTION_VIEW)
        deliver(
            callbackIntent(status = PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, confirm)
        )
        // The framework wants to show its own UI; that is the documented, expected path and the
        // install is still very much alive. Posting "failed to install" here would be a lie.
        assertEquals(0, notificationManager().size())
    }

    @Test
    fun `pending user action with no confirmation intent is terminal and reported`() {
        deliver(callbackIntent(status = PackageInstaller.STATUS_PENDING_USER_ACTION))
        // Nothing can resolve this status without an Intent to launch, so staying silent would
        // reproduce the exact invisibility this issue is about.
        assertEquals(1, notificationManager().size())
    }

    @Test
    fun `a failure from an older build without extras still notifies`() {
        // An install committed before this change carries no release identity: the PendingIntent
        // was built by the previous version. A nameless notification still beats logcat-only.
        deliver(
            callbackIntent(
                status = PackageInstaller.STATUS_FAILURE,
                message = "whatever",
                versionName = null,
                versionCode = 0,
                releaseUrl = null,
            )
        )
        assertEquals(1, notificationManager().size())
    }
}
