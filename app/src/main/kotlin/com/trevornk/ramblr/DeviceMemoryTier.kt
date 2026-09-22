package com.trevornk.ramblr

import android.app.ActivityManager
import android.content.Context

/**
 * Device capacity band used only for dictation allocations that can otherwise overlap. The
 * constrained band reflects the F-Droid Redmi Note 8T review: its 3.6 GB device reached 1.58 GB
 * VmRSS for a 31-second local dictation when the ~1 GB cleanup GGUF and batch ASR recognizer were
 * resident together. This is a quality/latency concern even though Android did not OOM-kill it.
 */
enum class DeviceMemoryTier { CONSTRAINED, CAPABLE }

/**
 * Pure device-memory classification split from [DeviceMemoryTierDetector] so the thresholds stay
 * JVM-testable without an Android [ActivityManager]. Android's low-RAM flag is authoritative;
 * otherwise a device is constrained when either the process memory class is at most 256 MiB or
 * total physical RAM is below 6 GiB. The latter safely includes the reviewer's 3.6 GB device,
 * while 8-12 GiB flagships remain capable. The per-process limit catches OEM profiles whose total
 * RAM would otherwise hide that this app cannot safely retain both large native models.
 */
object DeviceMemoryTierDecision {
    const val BYTES_PER_GIB = 1024L * 1024L * 1024L
    const val CONSTRAINED_TOTAL_MEMORY_BYTES = 6L * BYTES_PER_GIB
    const val CONSTRAINED_MEMORY_CLASS_MB = 256

    fun tier(
        isLowRamDevice: Boolean,
        memoryClassMb: Int,
        totalMemoryBytes: Long,
    ): DeviceMemoryTier =
        if (isLowRamDevice ||
            memoryClassMb <= CONSTRAINED_MEMORY_CLASS_MB ||
            totalMemoryBytes < CONSTRAINED_TOTAL_MEMORY_BYTES
        ) {
            DeviceMemoryTier.CONSTRAINED
        } else {
            DeviceMemoryTier.CAPABLE
        }
}

/** Thin Android adapter around [DeviceMemoryTierDecision]. A missing system service fails open to
 * [DeviceMemoryTier.CAPABLE], preserving the existing behavior rather than disabling the capable
 * path because a platform read unexpectedly failed. */
object DeviceMemoryTierDetector {
    fun tier(context: Context): DeviceMemoryTier {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return DeviceMemoryTier.CAPABLE
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return DeviceMemoryTierDecision.tier(
            isLowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
            totalMemoryBytes = memoryInfo.totalMem,
        )
    }
}

/**
 * Pure sequencing policy for the two native allocations that produced the F-Droid reviewer's
 * peak RSS. Capable devices retain the established recording-start pre-warm because a cold GGUF
 * load can consume the cleanup waterfall's whole deadline; constrained devices defer that load
 * and relinquish batch ASR before a possible local cleanup instead.
 */
object LocalCleanupPrewarmDecision {
    fun shouldWarmUp(tier: DeviceMemoryTier): Boolean = tier == DeviceMemoryTier.CAPABLE

    fun shouldReleaseBatchTranscriberBeforeCleanup(
        tier: DeviceMemoryTier,
        cleanupUsesLocalLlm: Boolean,
    ): Boolean = tier == DeviceMemoryTier.CONSTRAINED && cleanupUsesLocalLlm
}
