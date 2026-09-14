package com.trevornk.ramblr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the [PackageInstaller.Session.commit] status callback for the self-update install
 * (Part 4, github distribution flavor only). Registered dynamically-by-manifest (not
 * context-registered) so it can receive the callback even if the process that started the
 * install session has since died -- [PackageInstaller]'s own contract requires the receiver
 * Intent to be deliverable via a [android.app.PendingIntent] that survives process death, which
 * only a manifest-declared receiver guarantees.
 *
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] specifically means: the silent
 * (`USER_ACTION_NOT_REQUIRED`) path we attempted wasn't actually honored by the framework for
 * this install (see [SelfUpdateInstaller]'s kdoc on why that can happen even when every
 * documented precondition is met), and the framework wants to show the user its own confirmation
 * UI. The correct response is to launch the [Intent] the callback provides (under
 * [Intent.EXTRA_INTENT]) so that UI actually appears -- NOT to treat this as a failure. This is
 * the one case [SelfUpdateInstaller.install]'s own try/catch fallback can't catch synchronously,
 * since `commit()` returns immediately and the real status arrives here, asynchronously, later.
 *
 * #253: every other non-success status is a TERMINAL failure that used to reach only logcat. The
 * worker cannot report these -- `commit()` is asynchronous, so it returns (and the worker returns
 * `Result.success()`, correctly, having handed off successfully) long before the real outcome
 * lands here. That is precisely why the device-observed `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
 * surfaced nowhere: detected correctly, logged correctly, and invisible to the user, who was left
 * with a stale "Update available" notification that did nothing when tapped. This receiver now
 * posts the failure and clears that stale prompt.
 */
class SelfUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Self-update installed successfully")
                // The "update available" prompt for the version just installed is now actively
                // wrong -- cancel it rather than leaving the user to dismiss a notification
                // offering an update they already have.
                val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
                if (versionCode != 0) {
                    SelfUpdateNotifications.cancelUpdateAvailable(context, versionCode)
                }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The framework declined the silent path at commit time and wants to show its own
                // confirmation UI -- launch it. This Intent must be started with FLAG_ACTIVITY_NEW_TASK
                // since we're not launching from an existing Activity context here.
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to launch install-confirmation UI", e)
                        // Launching the confirmation UI is the ONLY way this status resolves. If
                        // it throws, the install is over and nothing else will report it, so this
                        // is a terminal failure like any other -- not something to swallow.
                        notifyFailure(context, intent, e.message ?: "Could not show the install confirmation")
                    }
                } else {
                    // PENDING_USER_ACTION with no Intent to launch: nothing can resolve it.
                    Log.w(TAG, "Install needs user action but no confirmation Intent was provided")
                    notifyFailure(context, intent, "The system did not provide an install confirmation screen")
                }
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Self-update install failed: status=$status message=$message")
                notifyFailure(context, intent, message)
            }
        }
    }

    /** Surfaces a terminal failure, using the release identity [SelfUpdateInstaller] attached to
     *  the callback Intent. Falls back to a generic version label rather than staying silent when
     *  the extras are absent (an install committed by an older build, mid-update): a nameless
     *  failure notification is still far better than the logcat-only status quo. */
    private fun notifyFailure(context: Context, intent: Intent, message: String?) {
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME)
        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
        val releaseUrl = intent.getStringExtra(EXTRA_RELEASE_URL)
        SelfUpdateNotifications.postInstallFailedAsync(
            ctx = context,
            versionName = versionName ?: "update",
            versionCode = versionCode,
            releaseUrl = releaseUrl,
            reason = SelfUpdateStatusFormatter.installFailureReason(message),
        )
    }

    companion object {
        private const val TAG = "SelfUpdateInstall"

        /** Release identity carried on the callback Intent by [SelfUpdateInstaller]; see its
         *  comment at the PendingIntent construction for why the receiver cannot obtain these
         *  any other way. */
        const val EXTRA_VERSION_NAME = "com.trevornk.ramblr.extra.UPDATE_VERSION_NAME"
        const val EXTRA_VERSION_CODE = "com.trevornk.ramblr.extra.UPDATE_VERSION_CODE"
        const val EXTRA_RELEASE_URL = "com.trevornk.ramblr.extra.UPDATE_RELEASE_URL"
    }
}
