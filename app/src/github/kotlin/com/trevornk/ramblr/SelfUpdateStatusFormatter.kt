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
}
