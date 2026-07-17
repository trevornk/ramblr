package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the pure decision logic split out of the github-flavor self-update install pipeline
 *  (Part 4): [SelfUpdateInstallGate]'s quiet-hours window, checksum-match check, and the
 *  install-now composition, plus [SelfUpdateInstallWorker]'s WorkManager-facing pure helpers.
 *  Lives in src/testGithub/ (not src/test/) because the production code under test lives in
 *  src/github/ -- mirrors [SelfUpdateResolverTest]. */
class SelfUpdateInstallGateTest {

    // -- isWithinQuietHours boundary cases (default window: [1, 5)) --

    @Test fun `exactly the start hour is inside the window (inclusive start)`() {
        assertTrue(SelfUpdateInstallGate.isWithinQuietHours(1))
    }

    @Test fun `the hour just before end is inside the window`() {
        assertTrue(SelfUpdateInstallGate.isWithinQuietHours(4))
    }

    @Test fun `exactly the end hour is outside the window (exclusive end)`() {
        assertFalse(SelfUpdateInstallGate.isWithinQuietHours(5))
    }

    @Test fun `midnight is outside the default window`() {
        assertFalse(SelfUpdateInstallGate.isWithinQuietHours(0))
    }

    @Test fun `noon is outside the default window`() {
        assertFalse(SelfUpdateInstallGate.isWithinQuietHours(12))
    }

    @Test fun `the hour just before start is outside the window`() {
        assertFalse(SelfUpdateInstallGate.isWithinQuietHours(0))
        // 24-hour clock has no hour before 0 to check separately; covered by the midnight case.
    }

    @Test fun `every hour of the day classifies consistently with the default 1-5 window`() {
        val expectedInside = setOf(1, 2, 3, 4)
        for (hour in 0..23) {
            assertEquals(
                "hour $hour",
                hour in expectedInside,
                SelfUpdateInstallGate.isWithinQuietHours(hour),
            )
        }
    }

    // -- isWithinQuietHours with a custom, non-default window --

    @Test fun `a custom non-wrapping window respects its own bounds, not the defaults`() {
        assertTrue(SelfUpdateInstallGate.isWithinQuietHours(10, startHour = 9, endHour = 17))
        assertFalse(SelfUpdateInstallGate.isWithinQuietHours(8, startHour = 9, endHour = 17))
        assertFalse(SelfUpdateInstallGate.isWithinQuietHours(17, startHour = 9, endHour = 17))
    }

    // -- isWithinQuietHours wraparound support (startHour > endHour) --

    @Test fun `a wrapping window (e_g_ 22 to 5) is inside at or after start`() {
        assertTrue(SelfUpdateInstallGate.isWithinQuietHours(23, startHour = 22, endHour = 5))
        assertTrue(SelfUpdateInstallGate.isWithinQuietHours(22, startHour = 22, endHour = 5))
    }

    @Test fun `a wrapping window is inside before end, on the other side of midnight`() {
        assertTrue(SelfUpdateInstallGate.isWithinQuietHours(0, startHour = 22, endHour = 5))
        assertTrue(SelfUpdateInstallGate.isWithinQuietHours(4, startHour = 22, endHour = 5))
    }

    @Test fun `a wrapping window is outside during the daytime gap`() {
        assertFalse(SelfUpdateInstallGate.isWithinQuietHours(5, startHour = 22, endHour = 5))
        assertFalse(SelfUpdateInstallGate.isWithinQuietHours(12, startHour = 22, endHour = 5))
        assertFalse(SelfUpdateInstallGate.isWithinQuietHours(21, startHour = 22, endHour = 5))
    }

    // -- checksumMatches --

    @Test fun `matching hashes compare equal case-insensitively`() {
        assertTrue(SelfUpdateInstallGate.checksumMatches("ABCDEF", "abcdef"))
        assertTrue(SelfUpdateInstallGate.checksumMatches("abcdef", "abcdef"))
    }

    @Test fun `mismatched hashes fail`() {
        assertFalse(SelfUpdateInstallGate.checksumMatches("abcdef", "123456"))
    }

    @Test fun `a null expected hash always fails closed, never treated as a pass`() {
        assertFalse(SelfUpdateInstallGate.checksumMatches(null, "abcdef"))
    }

    // -- shouldAttemptInstallNow composition --

    @Test fun `install is attempted when in quiet hours and the service reads idle`() {
        assertTrue(SelfUpdateInstallGate.shouldAttemptInstallNow(true, RecordingStateMachine.State.IDLE))
    }

    @Test fun `install is attempted when in quiet hours and the service isn't running at all (null)`() {
        // Documented reasoning: a disconnected service cannot possibly be mid-dictation, so null
        // is treated the same as IDLE, not as "unknown, therefore unsafe".
        assertTrue(SelfUpdateInstallGate.shouldAttemptInstallNow(true, null))
    }

    @Test fun `install is deferred when outside quiet hours even if idle`() {
        assertFalse(SelfUpdateInstallGate.shouldAttemptInstallNow(false, RecordingStateMachine.State.IDLE))
        assertFalse(SelfUpdateInstallGate.shouldAttemptInstallNow(false, null))
    }

    @Test fun `install is deferred while recording, even during quiet hours`() {
        assertFalse(SelfUpdateInstallGate.shouldAttemptInstallNow(true, RecordingStateMachine.State.RECORDING))
    }

    @Test fun `install is deferred while transcribing, even during quiet hours`() {
        assertFalse(SelfUpdateInstallGate.shouldAttemptInstallNow(true, RecordingStateMachine.State.TRANSCRIBING))
    }

    @Test fun `both conditions failing still defers, not just either`() {
        assertFalse(SelfUpdateInstallGate.shouldAttemptInstallNow(false, RecordingStateMachine.State.RECORDING))
    }

    // -- shouldAttemptManualInstallNow: manual "Install now" gate skips quiet hours --

    @Test fun `manual install proceeds when idle, regardless of time of day`() {
        assertTrue(SelfUpdateInstallGate.shouldAttemptManualInstallNow(RecordingStateMachine.State.IDLE))
    }

    @Test fun `manual install proceeds when the service isn't running at all (null)`() {
        assertTrue(SelfUpdateInstallGate.shouldAttemptManualInstallNow(null))
    }

    @Test fun `manual install is still deferred while recording`() {
        assertFalse(SelfUpdateInstallGate.shouldAttemptManualInstallNow(RecordingStateMachine.State.RECORDING))
    }

    @Test fun `manual install is still deferred while transcribing`() {
        assertFalse(SelfUpdateInstallGate.shouldAttemptManualInstallNow(RecordingStateMachine.State.TRANSCRIBING))
    }

    // -- SelfUpdateInstallWorker pure helpers --

    @Test fun `apkFile path is keyed by versionCode so different versions never collide`() {
        val filesDir = java.io.File(System.getProperty("java.io.tmpdir"), "self-update-test-files")
        val a = SelfUpdateInstallWorker.apkFilePath(filesDir, 13)
        val b = SelfUpdateInstallWorker.apkFilePath(filesDir, 14)
        assertFalse(a.path == b.path)
        assertTrue(a.path.contains("13"))
        assertTrue(b.path.contains("14"))
    }

    @Test fun `apkFile path lives under filesDir, not a public or external directory`() {
        val filesDir = java.io.File(System.getProperty("java.io.tmpdir"), "self-update-test-files")
        val file = SelfUpdateInstallWorker.apkFilePath(filesDir, 13)
        assertTrue(file.path.startsWith(filesDir.path))
    }

    @Test fun `workName is a single stable constant, matching the single-flight intent`() {
        assertEquals(SelfUpdateInstallWorker.workName(), SelfUpdateInstallWorker.workName())
    }

    private fun assertEquals(message: String, expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(message, expected, actual)
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
