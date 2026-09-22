package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure low-RAM decisions separately from [DeviceMemoryTierDetector], whose Android
 * [android.app.ActivityManager] reads cannot run in plain JVM tests. The reviewer who measured
 * 1.58 GB VmRSS on a 3.6 GB Redmi Note 8T makes the below-threshold boundary a real product
 * contract, not a speculative device heuristic.
 */
class DeviceMemoryTierDecisionTest {

    @Test fun `platform low-RAM flag constrains even a device reporting ample capacity`() {
        assertEquals(
            DeviceMemoryTier.CONSTRAINED,
            DeviceMemoryTierDecision.tier(
                isLowRamDevice = true,
                totalMemoryBytes = 12L * DeviceMemoryTierDecision.BYTES_PER_GIB,
            ),
        )
    }

    @Test fun `memory below the total-RAM boundary is constrained`() {
        assertEquals(
            DeviceMemoryTier.CONSTRAINED,
            DeviceMemoryTierDecision.tier(
                isLowRamDevice = false,
                totalMemoryBytes = DeviceMemoryTierDecision.CONSTRAINED_TOTAL_MEMORY_BYTES - 1,
            ),
        )
    }

    @Test fun `memory exactly at the total-RAM boundary is capable`() {
        assertEquals(
            DeviceMemoryTier.CAPABLE,
            DeviceMemoryTierDecision.tier(
                isLowRamDevice = false,
                totalMemoryBytes = DeviceMemoryTierDecision.CONSTRAINED_TOTAL_MEMORY_BYTES,
            ),
        )
    }

    /**
     * Regression guard for the real hardware this gate ships to. `MemoryInfo.totalMem` values are
     * the measured `/proc/meminfo` MemTotal of the maintainer's devices, both of which report a
     * `getMemoryClass()` of exactly 256 MiB. An earlier revision treated that heap ceiling as a
     * constrained signal and classified both flagships as CONSTRAINED, disabling the capable-device
     * pre-warm on essentially every modern phone. These must stay CAPABLE.
     */
    @Test fun `modern flagships with a 256 MiB heap class remain capable`() {
        val pixel10ProFoldTotalMem = 15_948_932L * 1024L
        val pixel10aTotalMem = 7_752_904L * 1024L
        listOf(pixel10ProFoldTotalMem, pixel10aTotalMem).forEach { totalMem ->
            assertEquals(
                DeviceMemoryTier.CAPABLE,
                DeviceMemoryTierDecision.tier(isLowRamDevice = false, totalMemoryBytes = totalMem),
            )
        }
    }

    /** The device from the review: 3.6 GB must classify as constrained. */
    @Test fun `the reviewer's 3_6 GB device is constrained`() {
        assertEquals(
            DeviceMemoryTier.CONSTRAINED,
            DeviceMemoryTierDecision.tier(
                isLowRamDevice = false,
                totalMemoryBytes = (3.6 * DeviceMemoryTierDecision.BYTES_PER_GIB).toLong(),
            ),
        )
    }

    @Test fun `modern eight to twelve GiB devices are capable`() {
        listOf(8L, 12L).forEach { gib ->
            assertEquals(
                DeviceMemoryTier.CAPABLE,
                DeviceMemoryTierDecision.tier(
                    isLowRamDevice = false,
                    totalMemoryBytes = gib * DeviceMemoryTierDecision.BYTES_PER_GIB,
                ),
            )
        }
    }

    @Test fun `constrained devices do not speculative-prewarm local cleanup`() {
        assertFalse(LocalCleanupPrewarmDecision.shouldWarmUp(DeviceMemoryTier.CONSTRAINED))
    }

    @Test fun `capable devices preserve speculative local cleanup prewarm`() {
        assertTrue(LocalCleanupPrewarmDecision.shouldWarmUp(DeviceMemoryTier.CAPABLE))
    }

    @Test fun `only constrained local-cleanup paths release batch ASR before cleanup`() {
        assertTrue(
            LocalCleanupPrewarmDecision.shouldReleaseBatchTranscriberBeforeCleanup(
                tier = DeviceMemoryTier.CONSTRAINED,
                cleanupUsesLocalLlm = true,
            ),
        )
        assertFalse(
            LocalCleanupPrewarmDecision.shouldReleaseBatchTranscriberBeforeCleanup(
                tier = DeviceMemoryTier.CAPABLE,
                cleanupUsesLocalLlm = true,
            ),
        )
        assertFalse(
            LocalCleanupPrewarmDecision.shouldReleaseBatchTranscriberBeforeCleanup(
                tier = DeviceMemoryTier.CONSTRAINED,
                cleanupUsesLocalLlm = false,
            ),
        )
    }
}
