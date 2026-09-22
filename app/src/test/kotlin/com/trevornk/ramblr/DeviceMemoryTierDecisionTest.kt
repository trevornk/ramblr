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
                memoryClassMb = 512,
                totalMemoryBytes = 12L * DeviceMemoryTierDecision.BYTES_PER_GIB,
            ),
        )
    }

    @Test fun `memory below the total-RAM boundary is constrained`() {
        assertEquals(
            DeviceMemoryTier.CONSTRAINED,
            DeviceMemoryTierDecision.tier(
                isLowRamDevice = false,
                memoryClassMb = 512,
                totalMemoryBytes = DeviceMemoryTierDecision.CONSTRAINED_TOTAL_MEMORY_BYTES - 1,
            ),
        )
    }

    @Test fun `memory exactly at the total-RAM boundary is capable`() {
        assertEquals(
            DeviceMemoryTier.CAPABLE,
            DeviceMemoryTierDecision.tier(
                isLowRamDevice = false,
                memoryClassMb = 512,
                totalMemoryBytes = DeviceMemoryTierDecision.CONSTRAINED_TOTAL_MEMORY_BYTES,
            ),
        )
    }

    @Test fun `small per-app memory class constrains despite total RAM`() {
        assertEquals(
            DeviceMemoryTier.CONSTRAINED,
            DeviceMemoryTierDecision.tier(
                isLowRamDevice = false,
                memoryClassMb = DeviceMemoryTierDecision.CONSTRAINED_MEMORY_CLASS_MB,
                totalMemoryBytes = 8L * DeviceMemoryTierDecision.BYTES_PER_GIB,
            ),
        )
    }

    @Test fun `modern eight to twelve GiB devices are capable`() {
        listOf(8L, 12L).forEach { gib ->
            assertEquals(
                DeviceMemoryTier.CAPABLE,
                DeviceMemoryTierDecision.tier(
                    isLowRamDevice = false,
                    memoryClassMb = 512,
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
