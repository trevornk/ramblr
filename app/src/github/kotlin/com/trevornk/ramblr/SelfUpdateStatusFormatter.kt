package com.trevornk.ramblr

/**
 * Pure, Android-free formatting for the Settings status row (Part 3): given the last-known
 * [UpdateCheckResult] (or null if never checked), when it was checked, and the running app's own
 * version, produces the human-facing status strings. Split out the same way
 * [DownloadNotifications.shouldPostUpdate]/`notificationId` are split from their
 * Android-API-bound siblings, specifically so this is unit-testable without Robolectric (this
 * project doesn't use it -- see DownloadNotificationsTest's kdoc).
 */
object SelfUpdateStatusFormatter {

    /** Short, user-facing summary of the last check's outcome. Deliberately does not surface
     *  [UpdateCheckResult.CheckFailed.reason] -- that's a developer diagnostic, not user copy
     *  (see the field's own kdoc in SelfUpdateResolver.kt). */
    fun statusLine(result: UpdateCheckResult?): String = when (result) {
        null -> "Not checked yet"
        is UpdateCheckResult.UpdateAvailable -> "Update available: v${result.versionName}"
        UpdateCheckResult.UpToDate -> "Up to date"
        is UpdateCheckResult.CheckFailed -> "Couldn't check for updates"
    }

    /** Relative "last checked" phrase. [nowMs] is passed in (rather than read internally) so this
     *  stays pure and deterministic for tests. */
    fun lastCheckedLine(lastCheckedAtMs: Long?, nowMs: Long): String {
        if (lastCheckedAtMs == null) return "Never checked"
        val deltaMs = (nowMs - lastCheckedAtMs).coerceAtLeast(0)
        val minutes = deltaMs / 60_000L
        return when {
            minutes < 1 -> "Last checked just now"
            minutes < 60 -> "Last checked ${minutes}m ago"
            minutes < 60 * 24 -> "Last checked ${minutes / 60}h ago"
            else -> "Last checked ${minutes / (60 * 24)}d ago"
        }
    }

    /** Full status row subtitle: last-checked timestamp, currently running version, and the last
     *  known check result -- exactly the three facts the Part 3 spec's status row calls for. */
    fun subtitle(
        result: UpdateCheckResult?,
        lastCheckedAtMs: Long?,
        nowMs: Long,
        runningVersionName: String,
        runningVersionCode: Int,
    ): String {
        val checked = lastCheckedLine(lastCheckedAtMs, nowMs)
        val running = "Running v$runningVersionName ($runningVersionCode)"
        val status = statusLine(result)
        return "$checked · $running · $status"
    }

    /**
     * Why a staged, checksum-verified update isn't installing yet, in user-facing terms. Drives
     * [SelfUpdateNotifications.postInstallDeferred].
     *
     * The two deferral causes are reported separately because the remedies differ: an in-progress
     * dictation clears itself within seconds, whereas a quiet-hours wait can be hours away and is
     * the case where a user most plausibly wants the "Install now" action instead. Dictation is
     * checked first -- when both apply it's the more immediate and more surprising of the two, and
     * telling someone mid-dictation to wait until 1am would be actively misleading.
     *
     * [quietHoursStartHour]/[quietHoursEndHour] are passed in rather than read from
     * [SelfUpdateInstallGate]'s constants so the copy stays correct if the window ever becomes
     * configurable, and so every phrasing is directly testable.
     */
    fun deferredReason(
        isDictating: Boolean,
        quietHoursStartHour: Int = SelfUpdateInstallGate.QUIET_HOURS_START_HOUR,
        quietHoursEndHour: Int = SelfUpdateInstallGate.QUIET_HOURS_END_HOUR,
    ): String = if (isDictating) {
        "Downloaded. Waiting until dictation finishes."
    } else {
        "Downloaded. Will install overnight, between " +
            "${formatHour(quietHoursStartHour)} and ${formatHour(quietHoursEndHour)}."
    }

    /**
     * Why the pre-flight install-permission check ([SelfUpdateInstallGate.canAttemptInstall])
     * blocked an install before it ever downloaded anything (#253). Deliberately distinct copy
     * from [deferredReason]: this is not "try again later" (nothing here resolves on its own --
     * the user must act), and not a generic failure either (the app hasn't attempted or failed
     * anything yet, it just knows in advance that it would). Drives
     * [SelfUpdateNotifications.postInstallPermissionNeeded].
     */
    fun permissionNeededReason(): String =
        "Install unknown apps access is required to finish installing this update."

    /**
     * User-facing reason for a terminal PackageInstaller failure delivered asynchronously to
     * [SelfUpdateInstallReceiver] (#253).
     *
     * Exists because the framework's own [android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE]
     * is developer-facing diagnostic text -- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means nothing to
     * a user -- but it is also the only thing that distinguishes one failure from another, so it
     * is appended rather than discarded. Keeping the mapping here (pure, no Android types) is what
     * makes it directly testable, matching how [permissionNeededReason] and [deferredReason] are
     * already structured.
     *
     * [message] is nullable because the extra is genuinely absent on some failure paths.
     */
    fun installFailureReason(message: String?): String {
        val detail = message?.trim().orEmpty()
        val base = when {
            detail.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
                "This update is signed with a different key than the installed copy. " +
                    "Reinstalling from the release page is the only way forward."
            detail.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
                "Not enough free storage to install this update."
            detail.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
                "The installed version is already newer than this update."
            detail.contains("ABORTED") ->
                "The install was cancelled before it finished."
            else -> "The update downloaded but could not be installed."
        }
        // Keep the raw framework text when there is any, so a bug report carries the real cause
        // instead of only this app's paraphrase of it.
        return if (detail.isEmpty()) base else "$base ($detail)"
    }

    /** 24h hour-of-day to a 12h clock phrase ("1am", "12pm"), matching how the rest of the app's
     *  user-facing copy reads. Only whole hours occur here: the quiet-hours window is defined in
     *  whole hour-of-day units ([SelfUpdateInstallGate.isWithinQuietHours]). */
    fun formatHour(hour: Int): String {
        val normalized = ((hour % 24) + 24) % 24
        val suffix = if (normalized < 12) "am" else "pm"
        val twelve = when (normalized % 12) {
            0 -> 12
            else -> normalized % 12
        }
        return "$twelve$suffix"
    }
}
