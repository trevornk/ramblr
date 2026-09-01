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

    // -- orphanedStagedApks: the staged-APK leak fixed in #249 --
    //
    // Regression context: staged APKs are keyed by versionCode and every delete in the worker
    // only ever touched the current target's own file, so a moved update target abandoned the
    // previous download permanently. Observed on a real device as a 57 MB ramblr-update-26.apk
    // left for 18 days next to the live -29, 113 MB total, in storage the user cannot clear.

    @Test fun `staged apk for a different versionCode is orphaned`() {
        val orphans = SelfUpdateInstallGate.orphanedStagedApks(
            listOf("ramblr-update-26.apk", "ramblr-update-29.apk"),
            currentTargetVersionCode = 29,
        )
        assertEquals(listOf("ramblr-update-26.apk"), orphans)
    }

    @Test fun `the current target's staged apk is never orphaned`() {
        val orphans = SelfUpdateInstallGate.orphanedStagedApks(
            listOf("ramblr-update-29.apk"),
            currentTargetVersionCode = 29,
        )
        assertTrue(orphans.isEmpty())
    }

    @Test fun `every staged apk is orphaned when no update is pending`() {
        val orphans = SelfUpdateInstallGate.orphanedStagedApks(
            listOf("ramblr-update-26.apk", "ramblr-update-29.apk"),
            currentTargetVersionCode = null,
        )
        assertEquals(listOf("ramblr-update-26.apk", "ramblr-update-29.apk"), orphans)
    }

    @Test fun `a newer staged versionCode than the target is also orphaned`() {
        // Target can move backwards (a release pulled/yanked, cache refreshed to an older one).
        // A staged build the pipeline is no longer working toward will never be installed by it.
        val orphans = SelfUpdateInstallGate.orphanedStagedApks(
            listOf("ramblr-update-30.apk"),
            currentTargetVersionCode = 29,
        )
        assertEquals(listOf("ramblr-update-30.apk"), orphans)
    }

    @Test fun `unrecognized file names are never deleted`() {
        // Fails closed: this code does not exclusively own the directory, so anything that isn't
        // unmistakably a staged APK is left alone.
        val orphans = SelfUpdateInstallGate.orphanedStagedApks(
            listOf("notes.txt", "ramblr-update-.apk", "ramblr-update-abc.apk", "update-29.apk", ""),
            currentTargetVersionCode = 29,
        )
        assertTrue("expected no matches, got $orphans", orphans.isEmpty())
    }

    @Test fun `part files are left to the download path's own cleanup`() {
        // A concurrent download writing ramblr-update-29.apk.part must not have it deleted out
        // from under it; the download path already cleans .part up on every failure branch.
        val orphans = SelfUpdateInstallGate.orphanedStagedApks(
            listOf("ramblr-update-26.apk.part", "ramblr-update-29.apk.part"),
            currentTargetVersionCode = 29,
        )
        assertTrue("expected .part files to be skipped, got $orphans", orphans.isEmpty())
    }

    @Test fun `an empty directory yields no orphans`() {
        assertTrue(SelfUpdateInstallGate.orphanedStagedApks(emptyList(), 29).isEmpty())
        assertTrue(SelfUpdateInstallGate.orphanedStagedApks(emptyList(), null).isEmpty())
    }

    @Test fun `orphan detection round-trips with the real apkFilePath naming`() {
        // Guards against the regex and the path builder drifting apart: if apkFilePath's naming
        // ever changes, this fails rather than silently pruning nothing forever.
        val filesDir = java.io.File(System.getProperty("java.io.tmpdir"), "self-update-test-files")
        val staleName = SelfUpdateInstallWorker.apkFilePath(filesDir, 26).name
        val currentName = SelfUpdateInstallWorker.apkFilePath(filesDir, 29).name
        val orphans = SelfUpdateInstallGate.orphanedStagedApks(listOf(staleName, currentName), 29)
        assertEquals(listOf(staleName), orphans)
    }

    @Test fun `staging dir is where apkFilePath actually writes`() {
        val filesDir = java.io.File(System.getProperty("java.io.tmpdir"), "self-update-test-files")
        assertEquals(
            SelfUpdateInstallWorker.stagingDir(filesDir).path,
            SelfUpdateInstallWorker.apkFilePath(filesDir, 29).parentFile?.path,
        )
    }

    // -- pruneStagedApks against a REAL filesystem (not just the pure name filter above) --

    /** Fresh, isolated staging dir per test, seeded with [names] as real non-empty files. */
    private fun seedStagingDir(vararg names: String): java.io.File {
        val filesDir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "ramblr-prune-test-${System.nanoTime()}",
        )
        val staging = SelfUpdateInstallWorker.stagingDir(filesDir)
        staging.mkdirs()
        for (name in names) java.io.File(staging, name).writeText("apk bytes")
        return filesDir
    }

    private fun stagedNames(filesDir: java.io.File): List<String> =
        SelfUpdateInstallWorker.stagingDir(filesDir).list()?.sorted() ?: emptyList()

    @Test fun `prune actually deletes the orphan and keeps the current target on disk`() {
        val filesDir = seedStagingDir("ramblr-update-26.apk", "ramblr-update-29.apk")
        try {
            val deleted = SelfUpdateInstallWorker.pruneStagedApks(filesDir, currentTargetVersionCode = 29)
            assertEquals(listOf("ramblr-update-26.apk"), deleted)
            assertEquals(listOf("ramblr-update-29.apk"), stagedNames(filesDir))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test fun `prune with no pending update clears every staged apk but spares other files`() {
        val filesDir = seedStagingDir("ramblr-update-26.apk", "ramblr-update-29.apk", "notes.txt")
        try {
            val deleted = SelfUpdateInstallWorker.pruneStagedApks(filesDir, currentTargetVersionCode = null)
            assertEquals(listOf("ramblr-update-26.apk", "ramblr-update-29.apk"), deleted.sorted())
            assertEquals(listOf("notes.txt"), stagedNames(filesDir))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test fun `prune leaves a mid-download part file alone`() {
        val filesDir = seedStagingDir("ramblr-update-29.apk.part", "ramblr-update-26.apk")
        try {
            val deleted = SelfUpdateInstallWorker.pruneStagedApks(filesDir, currentTargetVersionCode = 29)
            assertEquals(listOf("ramblr-update-26.apk"), deleted)
            assertEquals(listOf("ramblr-update-29.apk.part"), stagedNames(filesDir))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test fun `prune on a missing staging dir is a harmless no-op`() {
        // First-run case: nothing has ever been downloaded, so the directory doesn't exist yet.
        val filesDir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "ramblr-prune-absent-${System.nanoTime()}",
        )
        assertTrue(SelfUpdateInstallWorker.pruneStagedApks(filesDir, 29).isEmpty())
        assertTrue(SelfUpdateInstallWorker.pruneStagedApks(filesDir, null).isEmpty())
    }

    @Test fun `prune is idempotent`() {
        val filesDir = seedStagingDir("ramblr-update-26.apk", "ramblr-update-29.apk")
        try {
            assertEquals(listOf("ramblr-update-26.apk"), SelfUpdateInstallWorker.pruneStagedApks(filesDir, 29))
            assertTrue(SelfUpdateInstallWorker.pruneStagedApks(filesDir, 29).isEmpty())
            assertEquals(listOf("ramblr-update-29.apk"), stagedNames(filesDir))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test fun `prune reclaims the exact real-world leak from issue 249`() {
        // The observed device state: an 18-day-old ramblr-update-26.apk stranded next to the
        // live -29 because the update target moved and nothing ever deleted the old one.
        val filesDir = seedStagingDir("ramblr-update-26.apk", "ramblr-update-29.apk")
        try {
            SelfUpdateInstallWorker.pruneStagedApks(filesDir, currentTargetVersionCode = 29)
            val stale = java.io.File(SelfUpdateInstallWorker.stagingDir(filesDir), "ramblr-update-26.apk")
            val live = java.io.File(SelfUpdateInstallWorker.stagingDir(filesDir), "ramblr-update-29.apk")
            assertFalse("stale staged APK must be gone", stale.exists())
            assertTrue("in-flight staged APK must survive", live.exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun assertEquals(message: String, expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(message, expected, actual)
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
