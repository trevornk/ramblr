package com.trevornk.ramblr

/**
 * Pure, Android-free decision logic for *when* it's safe to attempt a silent self-install
 * (github distribution flavor only -- see [SelfUpdateChecker]'s AGENTS note). Split out of
 * [SelfUpdateInstallWorker] the same way [SelfUpdateResolver] is split from [SelfUpdateChecker]:
 * everything here takes plain values in and returns a plain value out, so every boundary case is
 * directly unit-testable without a real [android.content.Context], [WhisperAccessibilityService],
 * or wall clock.
 *
 * Two independent conditions gate an install attempt, both required:
 *  1. [isWithinQuietHours] -- current device-local hour is inside a configurable overnight
 *     window (default 1am-5am), so a silent APK swap never lands while Trevor is actively using
 *     the phone.
 *  2. Dictation is idle -- see [SelfUpdateInstallWorker]'s idle-debounce (checks
 *     [WhisperAccessibilityService.currentRecordingState] twice, ~30s apart) for why that half
 *     isn't a pure function here: it inherently involves a real-time wait between two live reads
 *     of mutable service state, which doesn't fit this file's "no I/O, no waiting" contract.
 *     [shouldAttemptInstallNow] below composes the *result* of that (a single already-resolved
 *     recording-state snapshot) with the quiet-hours check, but the wait itself lives in the
 *     Worker.
 */
object SelfUpdateInstallGate {
    /** Overnight window start, inclusive, in device-local 24h hour-of-day (0-23). */
    const val QUIET_HOURS_START_HOUR = 1

    /** Overnight window end, EXCLUSIVE, in device-local 24h hour-of-day (0-23). 5 means the
     *  window covers hour-of-day values 1, 2, 3, 4 -- i.e. up to but not including 5:00am. */
    const val QUIET_HOURS_END_HOUR = 5

    /**
     * True if [hour] (device-local hour-of-day, 0-23, as from `Calendar.get(Calendar.HOUR_OF_DAY)`
     * or `LocalTime.getHour()`) falls inside `[startHour, endHour)`. Takes the hour as a plain Int
     * parameter (rather than reading a live clock internally) specifically so tests can drive
     * every boundary case directly.
     *
     * Supports a window that wraps past midnight (e.g. `startHour=22, endHour=5`) by treating
     * `startHour > endHour` as "wraps": inside the window means `hour >= startHour` OR
     * `hour < endHour`. The default 1-5am window doesn't wrap (1 < 5), so this branch isn't
     * exercised by the default config today, but is implemented and tested regardless since a
     * differently-configured window could legitimately need it.
     *
     * Boundary semantics: start is inclusive, end is exclusive -- `hour == startHour` (exactly
     * 1:00) is inside the window, `hour == endHour` (exactly 5:00) is outside it.
     */
    fun isWithinQuietHours(
        hour: Int,
        startHour: Int = QUIET_HOURS_START_HOUR,
        endHour: Int = QUIET_HOURS_END_HOUR,
    ): Boolean = if (startHour <= endHour) {
        hour in startHour until endHour
    } else {
        // Wraps past midnight, e.g. 22..5: inside if at/after start, or before end.
        hour >= startHour || hour < endHour
    }

    /**
     * True if [computedSha256] (freshly hashed from the just-downloaded file) matches
     * [expectedSha256] (from [UpdateCheckResult.UpdateAvailable.sha256]), case-insensitively --
     * mirrors [ModelDownloader.verifyChecksum]'s own comparison exactly. A null [expectedSha256]
     * always fails closed (returns false): GitHub's release `digest` field is expected to always
     * be present (see [SelfUpdateJson.parseLatestRelease]), but a self-update install is
     * high-enough stakes that "we don't know what to check against" must never be silently
     * treated as "checks out".
     */
    fun checksumMatches(expectedSha256: String?, computedSha256: String): Boolean =
        expectedSha256 != null && expectedSha256.equals(computedSha256, ignoreCase = true)

    /**
     * The single pure decision of whether a *snapshot* of current conditions permits an install
     * attempt right now: quiet hours must hold, AND the recording state must read as idle (either
     * literally [RecordingStateMachine.State.IDLE], or null -- meaning
     * [WhisperAccessibilityService] isn't connected/running at all, which is equally safe to treat
     * as "definitely not recording" since a disconnected service cannot be mid-dictation).
     *
     * This function does not itself implement the double-check-30s-apart debounce
     * ([SelfUpdateInstallWorker.doWork] calls this twice, once before and once after a real delay)
     * -- it only judges one snapshot. Composing two snapshots taken 30s apart is what actually
     * reduces (not eliminates -- see the Worker's own kdoc) the race against a dictation starting
     * in between the second check and the moment the install session actually commits.
     */
    fun shouldAttemptInstallNow(
        withinQuietHours: Boolean,
        recordingState: RecordingStateMachine.State?,
    ): Boolean = withinQuietHours && (recordingState == null || recordingState == RecordingStateMachine.State.IDLE)
}
