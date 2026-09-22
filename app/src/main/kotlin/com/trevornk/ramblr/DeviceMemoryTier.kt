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
 * otherwise a device is constrained when total physical RAM is below 6 GiB. That safely includes
 * the reviewer's 3.6 GB device, while 8-12 GiB flagships remain capable.
 *
 * Deliberately NOT considered: [android.app.ActivityManager.getMemoryClass]. It returns
 * `dalvik.vm.heapgrowthlimit`, which AOSP leaves at 256 MiB on current flagships -- a Pixel 10 Pro
 * Fold with 15.2 GiB of RAM and a Pixel 10a with 7.4 GiB both report exactly 256. Treating that as
 * a constrained signal classified every modern device as constrained and silently disabled the
 * capable-device pre-warm everywhere. It is also the wrong metric in principle: the allocations
 * this gate protects (the mmap'd cleanup GGUF and the sherpa-onnx recognizers) are native, so the
 * Java heap ceiling does not bound them.
 *
 * Note [totalMemoryBytes] is `MemoryInfo.totalMem`, which reports a few percent below nominal
 * capacity because the kernel reserves memory before userspace sees it. A nominally 6 GB device
 * therefore lands just under the boundary and is treated as constrained -- intended, since 6 GB is
 * genuinely tight for a ~1 GB cleanup model resident alongside batch ASR.
 */
object DeviceMemoryTierDecision {
    const val BYTES_PER_GIB = 1024L * 1024L * 1024L
    const val CONSTRAINED_TOTAL_MEMORY_BYTES = 6L * BYTES_PER_GIB

    fun tier(
        isLowRamDevice: Boolean,
        totalMemoryBytes: Long,
    ): DeviceMemoryTier =
        if (isLowRamDevice || totalMemoryBytes < CONSTRAINED_TOTAL_MEMORY_BYTES) {
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
