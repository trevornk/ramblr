package com.trevornk.ramblr

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Conservative process-start cleanup shared by accessibility and IME hosts. */
internal class RecordingOrphanCleaner(
    private val leaseRegistry: DictationSessionLeaseRegistry,
    private val listFiles: (File, (File) -> Boolean) -> Array<File>? = { directory, filter ->
        directory.listFiles(filter)
    },
) {
    private val completed = AtomicBoolean(false)

    /**
     * Deletes only Ramblr recording temp artifacts and only while session acquisition is excluded.
     * A skipped or failed pass remains retryable by the next host startup callback.
     */
    fun cleanupOnce(cacheDir: File): Boolean {
        if (completed.get()) return true
        var cleaned = false
        val idle = leaseRegistry.runIfIdle {
            if (completed.get()) {
                cleaned = true
                return@runIfIdle
            }
            val files = listFiles(cacheDir) { file ->
                file.name.startsWith("rec_") &&
                    (file.name.endsWith(".pcm") || file.name.endsWith(".m4a"))
            }
            cleaned = files?.all { !it.exists() || it.delete() } ?: false
            if (cleaned) completed.set(true)
        }
        return idle && cleaned
    }
}

/** One successful hygiene pass per app process, whichever host starts first. */
internal object ProcessRecordingOrphanCleaner {
    private val cleaner = RecordingOrphanCleaner(ProcessDictationSessionLeaseRegistry)

    fun cleanupOnce(cacheDir: File): Boolean = cleaner.cleanupOnce(cacheDir)
}
