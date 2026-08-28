package com.trevornk.ramblr

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingOrphanCleanerTest {
    @Test fun `null directory listing fails without completing and retries successfully`() {
        val cacheDir = createTempDirectory("ramblr-orphans-listing").toFile()
        try {
            val pcm = File(cacheDir, "rec_retry.pcm").apply { writeText("pcm") }
            var listings = 0
            val cleaner = RecordingOrphanCleaner(InMemoryDictationSessionLeaseRegistry()) { dir, filter ->
                listings++
                if (listings == 1) null else dir.listFiles(filter)
            }

            assertFalse(cleaner.cleanupOnce(cacheDir))
            assertTrue(pcm.exists())
            assertTrue(cleaner.cleanupOnce(cacheDir))
            assertFalse(pcm.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test fun `accessibility startup cannot delete IME recording artifacts while lease is active`() {
        val cacheDir = createTempDirectory("ramblr-orphans-active").toFile()
        try {
            val pcm = File(cacheDir, "rec_active.pcm").apply { writeText("pcm") }
            val m4a = File(cacheDir, "rec_active.m4a").apply { writeText("m4a") }
            val registry = InMemoryDictationSessionLeaseRegistry()
            val imeLease = requireNotNull(registry.tryAcquire())
            val cleaner = RecordingOrphanCleaner(registry)

            assertFalse(cleaner.cleanupOnce(cacheDir))
            assertTrue(pcm.exists())
            assertTrue(m4a.exists())

            assertTrue(registry.release(imeLease))
            assertTrue(cleaner.cleanupOnce(cacheDir))
            assertFalse(pcm.exists())
            assertFalse(m4a.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test fun `IME-only process restart removes PCM and M4A orphans once while preserving other cache files`() {
        val cacheDir = createTempDirectory("ramblr-orphans-restart").toFile()
        try {
            val pcm = File(cacheDir, "rec_dead.pcm").apply { writeText("pcm") }
            val m4a = File(cacheDir, "rec_dead.m4a").apply { writeText("m4a") }
            val unrelated = File(cacheDir, "rec_keep.txt").apply { writeText("keep") }
            val cleanerForRestartedProcess = RecordingOrphanCleaner(InMemoryDictationSessionLeaseRegistry())

            assertTrue(cleanerForRestartedProcess.cleanupOnce(cacheDir))
            assertFalse(pcm.exists())
            assertFalse(m4a.exists())
            assertTrue(unrelated.exists())
            assertTrue(cleanerForRestartedProcess.cleanupOnce(cacheDir))
            assertTrue(unrelated.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
