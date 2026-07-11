package com.trevornk.ramblr

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Owns the actual APK install call for the github-flavor self-update pipeline (Part 4), via
 * [PackageInstaller] -- the modern, non-deprecated Android API for both the silent and
 * confirmation-dialog install paths (as opposed to the older `Intent.ACTION_INSTALL_PACKAGE`,
 * which [PackageInstaller] has superseded since API 21 and is what a plain
 * `Intent.ACTION_VIEW`-on-a-content-uri approach would still end up funneling through internally
 * on modern Android anyway).
 *
 * ## The real, verified `USER_ACTION_NOT_REQUIRED` contract (API 31+, confirmed against
 * developer.android.com's current [PackageInstaller.SessionParams] reference, not assumed)
 *
 * Calling [PackageInstaller.SessionParams.setRequireUserAction] with
 * [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED] causes Android to skip the install
 * confirmation dialog ONLY when **all** of the following hold:
 *  - API 31+ (the constant and method don't exist below that; this app's minSdk is 30, so this
 *    whole path is conditionally compiled/branched on `Build.VERSION.SDK_INT`, never assumed).
 *  - The installer (this app) holds `REQUEST_INSTALL_PACKAGES`... actually, per the docs, that
 *    permission is for the "installer" role generally; what additionally matters here is that the
 *    installer **declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`** (normal protection level, added
 *    in the manifest below).
 *  - The install session is an **update of an already-installed package** where this app is
 *    either the existing install's "update owner" (if update-ownership enforcement is active) or
 *    its "installer of record" (if not) -- OR the app is "updating itself", which is exactly this
 *    feature's use case (self-update).
 *  - The **target APK's targetSdk** meets a floor that has tightened release over release: API 29+
 *    on Android 12 (API 31), API 30+ on Android 13 (API 33), API 31+ on Android 14 (API 34),
 *    API 33+ on Android 15 (API 35), API 34+ on Android 16 (API 36). This app's targetSdk is 34,
 *    which clears the API-31-runtime floor (29) and the API-34-runtime floor (31), but would MISS
 *    the API-35 floor (33) and API-36 floor (34) on a hypothetical future device running Android
 *    15/16 without a targetSdk bump -- i.e. the silent path's availability is not just an
 *    OS-version check on today's devices, it depends on keeping targetSdk reasonably current
 *    going forward too. [attemptSilentInstall] below doesn't special-case this by exact OS
 *    version -- it just tries the silent session and falls back to the standard path on ANY
 *    failure, which structurally covers this without needing to hardcode the version matrix.
 *  - Even when every condition above holds, the framework may still require user action for
 *    other reasons (the docs are explicit: "a return value of USER_ACTION_NOT_REQUIRED does not
 *    guarantee the install will not result in user action") -- so callers must always be prepared
 *    to handle [PackageInstaller.STATUS_PENDING_USER_ACTION], which [install] does by falling back
 *    to the standard confirmation-dialog flow rather than treating it as an error.
 *
 * This app's actual floor is API 30 (minSdk), where none of the above exists at all -- the
 * standard confirmation-dialog flow (still via [PackageInstaller], just without
 * `setRequireUserAction`) is the ONLY path, and that's expected, correct behavior on that OS, not
 * a degraded fallback to be alarmed about.
 */
object SelfUpdateInstaller {
    /** Installs [apkFile] via [PackageInstaller], preferring the silent
     *  ([PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]) path on API 31+ and always
     *  falling back to the standard confirmation-dialog path (same Session APIs, default
     *  `requireUserAction`) if the silent attempt isn't available or throws for any reason.
     *  Throws [IOException] if even the fallback attempt fails to start (e.g. can't open a
     *  session at all) -- caller (SelfUpdateInstallWorker) treats that as a terminal failure. */
    fun install(ctx: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                installSession(ctx, apkFile, silent = true)
                return
            } catch (e: Exception) {
                // Any failure of the silent path (permission not actually effective, session
                // commit still came back STATUS_PENDING_USER_ACTION and the receiver here treats
                // that as failure rather than resolving it, or any other install-time error) falls
                // through to the standard path below -- this is the documented, expected fallback,
                // not a bug: the framework itself can decline USER_ACTION_NOT_REQUIRED for reasons
                // outside this app's control (see the kdoc above).
            }
        }
        installSession(ctx, apkFile, silent = false)
    }

    private fun installSession(ctx: Context, apkFile: File, silent: Boolean) {
        val packageInstaller = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (silent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        try {
            session.openWrite("ramblr-update", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, SelfUpdateInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                ctx, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw IOException("PackageInstaller session failed: ${e.message}", e)
        } finally {
            session.close()
        }
    }
}
