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
 */
class SelfUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Self-update installed successfully")
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
                    }
                }
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Self-update install failed: status=$status message=$message")
            }
        }
    }

    companion object {
        private const val TAG = "SelfUpdateInstall"
    }
}
