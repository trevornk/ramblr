package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioJobMachineTest {
    private fun job(status: JobStatus, kind: JobKind = JobKind.IMPORTED, audio: Boolean = false) = AudioJob(
        id = "j", displayName = "n", kind = kind, status = status, createdAt = 1, hasLocalAudio = audio,
    )

    private fun walk(start: AudioJob, vararg events: JobEvent) = events.fold(start) { j, e -> AudioJobMachine.apply(j, e) }

    @Test fun `happy path imported`() {
        val j = walk(
            job(JobStatus.QUEUED),
            JobEvent.ImportStarted, JobEvent.DecodeStarted, JobEvent.DecodeProgress(40),
            JobEvent.TranscribeStarted(3, 90_000), JobEvent.ChunkDone(0), JobEvent.ChunkDone(1), JobEvent.ChunkDone(2),
            JobEvent.CleaningStarted, JobEvent.Completed(123L, cleanupFailed = false, copied = false),
        )
        assertEquals(JobStatus.DONE, j.status)
        assertEquals(123L, j.historyTimestamp)
        assertEquals(3, j.chunkIndex)
        assertFalse(j.hasLocalAudio)
    }

    @Test fun `cleanup stage is optional`() {
        val j = walk(job(JobStatus.QUEUED, audio = true), JobEvent.DecodeStarted, JobEvent.TranscribeStarted(1, 1), JobEvent.Completed(5, false, false))
        assertEquals(JobStatus.DONE, j.status)
    }

    @Test fun `saved recording must be enqueued before running`() {
        val s = job(JobStatus.SAVED, JobKind.RECORDED, audio = true)
        assertEquals(s, AudioJobMachine.apply(s, JobEvent.DecodeStarted))
        assertEquals(JobStatus.QUEUED, AudioJobMachine.apply(s, JobEvent.Enqueue).status)
    }

    @Test fun `illegal events are ignored not applied`() {
        val done = job(JobStatus.DONE)
        assertEquals(done, AudioJobMachine.apply(done, JobEvent.Failed(JobFailure.UNREADABLE)))
        assertEquals(done, AudioJobMachine.apply(done, JobEvent.Cancel))
        val queued = job(JobStatus.QUEUED)
        assertEquals(queued, AudioJobMachine.apply(queued, JobEvent.TranscribeStarted(2, 1)))
        assertEquals(queued, AudioJobMachine.apply(queued, JobEvent.Completed(1, false, false)))
    }

    @Test fun `a stale event cannot resurrect a cancelled job`() {
        val c = walk(job(JobStatus.DECODING), JobEvent.Cancel)
        assertEquals(JobStatus.CANCELLED, c.status)
        assertEquals(c, AudioJobMachine.apply(c, JobEvent.TranscribeStarted(2, 1)))
        assertEquals(c, AudioJobMachine.apply(c, JobEvent.Completed(1, false, false)))
    }

    @Test fun `cancelling an imported job discards it`() {
        val c = AudioJobMachine.apply(job(JobStatus.TRANSCRIBING, audio = true), JobEvent.Cancel)
        assertEquals(JobStatus.CANCELLED, c.status)
        assertFalse(AudioJobMachine.keepsAudio(c))
    }

    @Test fun `cancelling a recorded note returns it to saved and keeps the audio`() {
        val c = AudioJobMachine.apply(job(JobStatus.TRANSCRIBING, JobKind.RECORDED, audio = true).copy(chunkTotal = 4, chunkIndex = 2), JobEvent.Cancel)
        assertEquals(JobStatus.SAVED, c.status)
        assertTrue(AudioJobMachine.keepsAudio(c))
        assertEquals(0, c.chunkTotal)
    }

    @Test fun `failure keeps audio and allows retry`() {
        val f = AudioJobMachine.apply(job(JobStatus.TRANSCRIBING, audio = true), JobEvent.Failed(JobFailure.TRANSCRIPTION_FAILED))
        assertEquals(JobStatus.FAILED, f.status)
        assertTrue(AudioJobMachine.keepsAudio(f))
        assertTrue(AudioJobMachine.canRetry(f))
        val r = AudioJobMachine.apply(f, JobEvent.Retry)
        assertEquals(JobStatus.QUEUED, r.status)
        assertNull(r.failure)
    }

    @Test fun `a failed import that never copied its audio cannot be retried`() {
        val f = AudioJobMachine.apply(job(JobStatus.IMPORTING, audio = false), JobEvent.Failed(JobFailure.UNREADABLE))
        assertFalse(AudioJobMachine.canRetry(f))
        assertEquals(f, AudioJobMachine.apply(f, JobEvent.Retry))
    }

    @Test fun `interrupted marks running jobs failed but leaves a queued one with audio waiting`() {
        assertEquals(JobFailure.INTERRUPTED, AudioJobMachine.apply(job(JobStatus.DECODING, audio = true), JobEvent.Interrupted).failure)
        val q = job(JobStatus.QUEUED, audio = true)
        assertEquals(q, AudioJobMachine.apply(q, JobEvent.Interrupted))
        // ...but a queued share whose only copy was a dead grant is failed
        assertEquals(JobStatus.FAILED, AudioJobMachine.apply(job(JobStatus.QUEUED, audio = false), JobEvent.Interrupted).status)
        val saved = job(JobStatus.SAVED, JobKind.RECORDED, audio = true)
        assertEquals(saved, AudioJobMachine.apply(saved, JobEvent.Interrupted))
    }

    @Test fun `chunk progress never exceeds the total`() {
        val j = walk(job(JobStatus.DECODING), JobEvent.TranscribeStarted(2, 1), JobEvent.ChunkDone(5))
        assertEquals(2, j.chunkIndex)
    }

    @Test fun `decode percent is clamped`() {
        assertEquals(100, AudioJobMachine.apply(job(JobStatus.DECODING), JobEvent.DecodeProgress(900)).decodePercent)
    }

    @Test fun `notice policy`() {
        val base = job(JobStatus.DONE).copy(historyTimestamp = 1)
        assertEquals(AudioNoticeKind.DONE, audioNoticeFor(base))
        assertEquals(AudioNoticeKind.DONE_CLEANUP_FAILED, audioNoticeFor(base.copy(cleanupFailed = true)))
        assertEquals(AudioNoticeKind.DONE_COPIED, audioNoticeFor(base.copy(copiedInsteadOfSaved = true, cleanupFailed = true)))
        assertEquals(AudioNoticeKind.FAILED, audioNoticeFor(job(JobStatus.FAILED)))
        assertNull(audioNoticeFor(job(JobStatus.CANCELLED)))
        assertNull(audioNoticeFor(job(JobStatus.SAVED)))
        assertNull(audioNoticeFor(job(JobStatus.TRANSCRIBING)))
    }
}

class AudioJobStoreTest {
    private fun tmp() = File.createTempFile("audio_jobs", ".jsonl").apply { deleteOnExit(); delete() }
    private fun job(id: String, status: JobStatus = JobStatus.QUEUED, t: Long = 1, audio: Boolean = true) =
        AudioJob(id, "name $id", JobKind.IMPORTED, status, t, hasLocalAudio = audio, sourceUri = "content://x/$id")

    @Test fun `round trips every field`() {
        val s = AudioJobStore(tmp())
        val j = job("a").copy(durationMs = 5, chunkIndex = 1, chunkTotal = 3, decodePercent = 7, failure = JobFailure.STORAGE,
            cleanupFailed = true, historyTimestamp = 99, copiedInsteadOfSaved = true, status = JobStatus.FAILED)
        s.upsert(j)
        assertEquals(j, s.get("a"))
    }

    @Test fun `update applies the machine and skips illegal events`() {
        val s = AudioJobStore(tmp())
        s.upsert(job("a", JobStatus.DONE))
        assertEquals(JobStatus.DONE, s.update("a", JobEvent.Failed(JobFailure.UNREADABLE))?.status)
        assertNull(s.update("missing", JobEvent.Cancel))
    }

    @Test fun `nextQueued is oldest first`() {
        val s = AudioJobStore(tmp())
        s.upsert(job("a", JobStatus.QUEUED)); s.upsert(job("b", JobStatus.QUEUED)); s.upsert(job("c", JobStatus.DONE))
        assertEquals("a", s.nextQueued()?.id)
        s.update("a", JobEvent.DecodeStarted)
        assertEquals("b", s.nextQueued()?.id)
    }

    @Test fun `recoverInterrupted fails running jobs only`() {
        val s = AudioJobStore(tmp())
        s.upsert(job("run", JobStatus.TRANSCRIBING)); s.upsert(job("q", JobStatus.QUEUED)); s.upsert(job("done", JobStatus.DONE))
        s.upsert(job("noaudio", JobStatus.QUEUED, audio = false))
        val changed = s.recoverInterrupted().toSet()
        assertEquals(setOf("run", "noaudio"), changed)
        assertEquals(JobFailure.INTERRUPTED, s.get("run")?.failure)
        assertEquals(JobStatus.QUEUED, s.get("q")?.status)
        assertTrue(s.recoverInterrupted().isEmpty())
    }

    @Test fun `finished jobs are pruned oldest first and active ones never`() {
        val s = AudioJobStore(tmp())
        s.upsert(job("active", JobStatus.QUEUED, t = 0))
        for (i in 1..(AudioJobStore.MAX_FINISHED + 5)) s.upsert(job("d$i", JobStatus.DONE, t = i.toLong()))
        val ids = s.all().map { it.id }
        assertTrue("active" in ids)
        assertFalse("d1" in ids)
        assertTrue("d${AudioJobStore.MAX_FINISHED + 5}" in ids)
        assertEquals(AudioJobStore.MAX_FINISHED, ids.count { it.startsWith("d") })
    }

    @Test fun `corrupt lines are skipped and survive across instances`() {
        val f = tmp()
        AudioJobStore(f).upsert(job("a"))
        f.appendText("not json\n")
        assertEquals(listOf("a"), AudioJobStore(f).all().map { it.id })
    }
}
