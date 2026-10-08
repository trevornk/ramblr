package com.trevornk.ramblr

import org.json.JSONObject
import java.io.File

/**
 * #285: one audio-file transcription job, from "the user handed us a file" (or "recorded a note to
 * transcribe later") to "text is in History". The job list is persisted so a queued/finished job
 * survives the app being closed, and so a job interrupted by process death is shown as failed and
 * retryable instead of silently vanishing.
 *
 * No transcript text lives here: the result goes to [DictationHistoryStore] (subject to the user's
 * history toggle) and [historyTimestamp] is the pointer to it.
 */
enum class JobKind { IMPORTED, RECORDED }

enum class JobStatus {
    /** A recorded note waiting for the user to ask for transcription. Audio is on disk. */
    SAVED,

    /** Waiting for the worker. */
    QUEUED,

    /** Copying the shared/picked file into app storage (the share grant is short-lived). */
    IMPORTING,
    DECODING,
    TRANSCRIBING,
    CLEANING,
    DONE,
    FAILED,
    CANCELLED;

    /** Work is actively running (or about to): the user can cancel it. */
    val isActive: Boolean get() = this == QUEUED || this == IMPORTING || this == DECODING || this == TRANSCRIBING || this == CLEANING

    /** Nothing further will happen without a user action. */
    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/** Coarse failure class: shown to the user, never the raw error (provider bodies can echo input). */
enum class JobFailure {
    UNREADABLE,
    NO_SPEECH,
    NO_PROVIDER,
    LOCAL_UNAVAILABLE,
    TRANSCRIPTION_FAILED,
    STORAGE,
    INTERRUPTED,
    TIMED_OUT,
}

data class AudioJob(
    val id: String,
    val displayName: String,
    val kind: JobKind,
    val status: JobStatus,
    val createdAt: Long,
    /** Content URI to import from; only meaningful while [status] is QUEUED and nothing is imported yet. */
    val sourceUri: String? = null,
    /** Whether the audio now lives in app storage (see [AudioJobFiles.sourceFile]). */
    val hasLocalAudio: Boolean = false,
    val durationMs: Long = 0L,
    val chunkIndex: Int = 0,
    val chunkTotal: Int = 0,
    /** 0..100 while decoding. */
    val decodePercent: Int = 0,
    val failure: JobFailure? = null,
    val cleanupFailed: Boolean = false,
    val historyTimestamp: Long? = null,
    /** Result was put on the clipboard because History is switched off. */
    val copiedInsteadOfSaved: Boolean = false,
)

/** Events that move a job through its life. The only code allowed to change [AudioJob.status]. */
sealed class JobEvent {
    object Enqueue : JobEvent()
    object ImportStarted : JobEvent()
    object DecodeStarted : JobEvent()
    data class DecodeProgress(val percent: Int) : JobEvent()
    data class TranscribeStarted(val chunkTotal: Int, val durationMs: Long) : JobEvent()
    data class ChunkDone(val index: Int) : JobEvent()
    object CleaningStarted : JobEvent()
    data class Completed(val historyTimestamp: Long?, val cleanupFailed: Boolean, val copied: Boolean) : JobEvent()
    data class Failed(val failure: JobFailure) : JobEvent()

    /** User cancelled. */
    object Cancel : JobEvent()

    /** The process died (or the service was stopped) under a running job. */
    object Interrupted : JobEvent()
    object Retry : JobEvent()
}

object AudioJobMachine {
    /**
     * Pure transition function. Returns the updated job, or the same job unchanged when [event] is
     * not legal from the current status (a stale event from a cancelled run must not resurrect it).
     */
    fun apply(job: AudioJob, event: JobEvent): AudioJob {
        val s = job.status
        return when (event) {
            JobEvent.Enqueue ->
                if (s == JobStatus.SAVED) job.copy(status = JobStatus.QUEUED, failure = null) else job
            JobEvent.ImportStarted ->
                if (s == JobStatus.QUEUED) job.copy(status = JobStatus.IMPORTING) else job
            JobEvent.DecodeStarted ->
                if (s == JobStatus.QUEUED || s == JobStatus.IMPORTING) job.copy(status = JobStatus.DECODING, decodePercent = 0) else job
            is JobEvent.DecodeProgress ->
                if (s == JobStatus.DECODING) job.copy(decodePercent = event.percent.coerceIn(0, 100)) else job
            is JobEvent.TranscribeStarted ->
                if (s == JobStatus.DECODING) job.copy(
                    status = JobStatus.TRANSCRIBING,
                    chunkIndex = 0,
                    chunkTotal = event.chunkTotal,
                    durationMs = event.durationMs,
                ) else job
            is JobEvent.ChunkDone ->
                if (s == JobStatus.TRANSCRIBING) job.copy(chunkIndex = (event.index + 1).coerceAtMost(job.chunkTotal)) else job
            JobEvent.CleaningStarted ->
                if (s == JobStatus.TRANSCRIBING) job.copy(status = JobStatus.CLEANING) else job
            is JobEvent.Completed ->
                if (s == JobStatus.TRANSCRIBING || s == JobStatus.CLEANING) job.copy(
                    status = JobStatus.DONE,
                    historyTimestamp = event.historyTimestamp,
                    cleanupFailed = event.cleanupFailed,
                    copiedInsteadOfSaved = event.copied,
                    hasLocalAudio = false,
                    failure = null,
                ) else job
            is JobEvent.Failed ->
                if (s.isActive) job.copy(status = JobStatus.FAILED, failure = event.failure) else job
            JobEvent.Cancel ->
                when {
                    !s.isActive -> job
                    // A recording is the user's only copy: cancelling its transcription returns it to
                    // the list, it does not destroy it.
                    job.kind == JobKind.RECORDED && job.hasLocalAudio -> job.copy(status = JobStatus.SAVED, chunkIndex = 0, chunkTotal = 0)
                    else -> job.copy(status = JobStatus.CANCELLED, hasLocalAudio = false)
                }
            JobEvent.Interrupted ->
                // A QUEUED job that already has its audio on disk just waits for the next run; one
                // whose only copy is a (now dead) share grant can never run.
                if (s.isActive && (s != JobStatus.QUEUED || !job.hasLocalAudio)) job.copy(status = JobStatus.FAILED, failure = JobFailure.INTERRUPTED) else job
            JobEvent.Retry ->
                // Only possible while the audio still exists: an import that never got copied has
                // lost its (short-lived) source grant, and a finished job has deleted its audio.
                if (s == JobStatus.FAILED && job.hasLocalAudio) job.copy(
                    status = JobStatus.QUEUED, failure = null, chunkIndex = 0, chunkTotal = 0, decodePercent = 0,
                ) else job
        }
    }

    /** Whether the user can retry this job. */
    fun canRetry(job: AudioJob): Boolean = job.status == JobStatus.FAILED && job.hasLocalAudio

    /** Whether [job] should keep its audio file on disk. */
    fun keepsAudio(job: AudioJob): Boolean = job.hasLocalAudio && job.status != JobStatus.DONE && job.status != JobStatus.CANCELLED
}

/** On-disk layout for job audio. */
object AudioJobFiles {
    fun dir(filesDir: File): File = File(filesDir, "audio_jobs").apply { mkdirs() }
    fun sourceFile(filesDir: File, id: String): File = File(dir(filesDir), "$id.src")
    fun workDir(cacheDir: File): File = File(cacheDir, "audio_jobs").apply { mkdirs() }
}

/**
 * Persisted job list (JSON lines, atomic rewrite, one shared instance per file like
 * [DictationHistoryStore]). Plain [File] I/O so it is unit-testable.
 */
class AudioJobStore(private val file: File) {

    companion object {
        private const val FILE_NAME = "audio_jobs.jsonl"
        const val MAX_FINISHED = 30
        private val instances = mutableMapOf<String, AudioJobStore>()

        fun forFile(file: File): AudioJobStore =
            synchronized(instances) { instances.getOrPut(file.absolutePath) { AudioJobStore(file) } }

        fun forFilesDir(filesDir: File): AudioJobStore = forFile(File(filesDir, FILE_NAME))
    }

    /** Oldest first. */
    @Synchronized
    fun all(): List<AudioJob> = read()

    @Synchronized
    fun get(id: String): AudioJob? = read().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(job: AudioJob) {
        val jobs = read()
        val next = if (jobs.any { it.id == job.id }) jobs.map { if (it.id == job.id) job else it } else jobs + job
        write(prune(next))
    }

    /**
     * Applies [event] to the stored job atomically and returns the result (null if absent). The
     * write is skipped when the event was illegal for the current status.
     */
    @Synchronized
    fun update(id: String, event: JobEvent): AudioJob? {
        val jobs = read()
        val current = jobs.firstOrNull { it.id == id } ?: return null
        val next = AudioJobMachine.apply(current, event)
        if (next != current) write(jobs.map { if (it.id == id) next else it })
        return next
    }

    /** Sets [AudioJob.hasLocalAudio] / [AudioJob.sourceUri] housekeeping that is not a status change. */
    @Synchronized
    fun mutate(id: String, f: (AudioJob) -> AudioJob): AudioJob? {
        val jobs = read()
        val current = jobs.firstOrNull { it.id == id } ?: return null
        val next = f(current)
        if (next != current) write(jobs.map { if (it.id == id) next else it })
        return next
    }

    @Synchronized
    fun delete(id: String) = write(read().filterNot { it.id == id })

    /** First job waiting for the worker, oldest first. */
    @Synchronized
    fun nextQueued(): AudioJob? = read().firstOrNull { it.status == JobStatus.QUEUED }

    /**
     * Process-start recovery: anything that claimed to be running when the previous process died
     * is failed as interrupted (retryable if its audio is still there). Returns the ids changed.
     */
    @Synchronized
    fun recoverInterrupted(): List<String> {
        val jobs = read()
        val changed = mutableListOf<String>()
        val next = jobs.map { j ->
            val n = AudioJobMachine.apply(j, JobEvent.Interrupted)
            if (n != j) changed += j.id
            n
        }
        if (changed.isNotEmpty()) write(next)
        return changed
    }

    private fun prune(jobs: List<AudioJob>): List<AudioJob> {
        val finished = jobs.filter { it.status == JobStatus.DONE || it.status == JobStatus.CANCELLED }
        if (finished.size <= MAX_FINISHED) return jobs
        val drop = finished.sortedBy { it.createdAt }.take(finished.size - MAX_FINISHED).map { it.id }.toSet()
        return jobs.filterNot { it.id in drop }
    }

    private fun read(): List<AudioJob> {
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }
            .mapNotNull { runCatching { parse(it) }.getOrNull() }
    }

    private fun write(jobs: List<AudioJob>) {
        file.parentFile?.mkdirs()
        val body = jobs.joinToString("") { serialize(it) + "\n" }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            file.writeText(body)
            tmp.delete()
        }
    }

    private fun serialize(j: AudioJob): String = JSONObject().apply {
        put("id", j.id)
        put("displayName", j.displayName)
        put("kind", j.kind.name)
        put("status", j.status.name)
        put("createdAt", j.createdAt)
        put("sourceUri", j.sourceUri ?: JSONObject.NULL)
        put("hasLocalAudio", j.hasLocalAudio)
        put("durationMs", j.durationMs)
        put("chunkIndex", j.chunkIndex)
        put("chunkTotal", j.chunkTotal)
        put("decodePercent", j.decodePercent)
        put("failure", j.failure?.name ?: JSONObject.NULL)
        put("cleanupFailed", j.cleanupFailed)
        put("historyTimestamp", j.historyTimestamp ?: JSONObject.NULL)
        put("copiedInsteadOfSaved", j.copiedInsteadOfSaved)
    }.toString()

    private fun parse(line: String): AudioJob {
        val o = JSONObject(line)
        return AudioJob(
            id = o.getString("id"),
            displayName = o.getString("displayName"),
            kind = JobKind.valueOf(o.getString("kind")),
            status = JobStatus.valueOf(o.getString("status")),
            createdAt = o.getLong("createdAt"),
            sourceUri = if (o.isNull("sourceUri")) null else o.getString("sourceUri"),
            hasLocalAudio = o.optBoolean("hasLocalAudio", false),
            durationMs = o.optLong("durationMs", 0L),
            chunkIndex = o.optInt("chunkIndex", 0),
            chunkTotal = o.optInt("chunkTotal", 0),
            decodePercent = o.optInt("decodePercent", 0),
            failure = if (o.isNull("failure")) null else runCatching { JobFailure.valueOf(o.getString("failure")) }.getOrNull(),
            cleanupFailed = o.optBoolean("cleanupFailed", false),
            historyTimestamp = if (o.isNull("historyTimestamp")) null else o.getLong("historyTimestamp"),
            copiedInsteadOfSaved = o.optBoolean("copiedInsteadOfSaved", false),
        )
    }
}
