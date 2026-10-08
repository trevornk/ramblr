package com.trevornk.ramblr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #285: runs ONE audio-file job to completion, on the calling (worker) thread:
 * import -> decode to 16 kHz mono PCM -> chunk -> transcribe each chunk (local or cloud, per the
 * user's existing settings) -> optional cleanup -> History. It does not own the process-keep-alive
 * (that is [AudioTranscriptionService]) and never logs transcript text.
 *
 * Settings are the live-dictation ones, read the same way: local-vs-cloud (`use_local`), the
 * provider chain and its fall-back toggles, the Dictation language, the personal vocabulary, and
 * the cleanup toggle + chain.
 */
internal class AudioJobRunner(
    private val context: Context,
    private val store: AudioJobStore,
    private val isCancelled: (String) -> Boolean,
    private val onStatus: (AudioJob) -> Unit,
) {
    private val filesDir: File get() = context.filesDir
    private val inFlight = InFlightCall()

    /** Aborts the network call currently in flight (the cancel flag stops the rest). */
    fun abortInFlight() = inFlight.cancel()

    private class Stop(val failure: JobFailure?) : Exception()

    fun run(initial: AudioJob) {
        val id = initial.id
        val workDir = AudioJobFiles.workDir(context.cacheDir)
        val pcm = File(workDir, "$id.pcm")
        var local: LocalTranscriber? = null
        inFlight.beginWork()
        try {
            if (isCancelled(id)) throw Stop(null)
            if (!initial.hasLocalAudio) importSource(initial)

            val source = AudioJobFiles.sourceFile(filesDir, id)
            if (!source.exists()) throw Stop(JobFailure.UNREADABLE)

            event(id, JobEvent.DecodeStarted)
            var lastPercent = -1
            val decoded = try {
                AudioFileDecoder.decode(source, pcm, { isCancelled(id) }) { f ->
                    val p = (f * 100).toInt()
                    if (p != lastPercent) { lastPercent = p; event(id, JobEvent.DecodeProgress(p)) }
                }
            } catch (e: AudioDecodeException) {
                if (isCancelled(id)) throw Stop(null)
                throw Stop(if (e.cause is IOException && isNoSpace(e.cause)) JobFailure.STORAGE else JobFailure.UNREADABLE)
            }
            if (isBelowMinimumDuration(decoded.pcmBytes, AudioChunkPlanner.SAMPLE_RATE)) throw Stop(JobFailure.NO_SPEECH)

            // ---- routes ------------------------------------------------------------------------
            val prefs = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
            val chain = ProviderChainStore.load(context)
            val localModel = resolveLocalModelName(prefs.getString("model_name", "") ?: "")
            val vadFile = ModelDownloader.vadModelFile(context, SILERO_VAD_MODEL)
            val plan = AudioTranscriptionRoutes.plan(
                useLocal = prefs.getBoolean("use_local", true),
                localReady = localModel != null,
                allowCloudFallback = DictationModeToggle.allowCloudFallback(context),
                candidates = ProviderChainRuntime.transcriptionCandidates(chain, DictationModeToggle.allowLocalFallback(context)),
                hasCredential = { ProviderCredentialStore.getOrLegacy(context, it).isNotBlank() },
            )
            if (plan.routes.isEmpty()) throw Stop(plan.unavailable ?: JobFailure.NO_PROVIDER)
            if (vadFile == null && plan.routes.any { it is AudioRoute.Local }) {
                // Same as live dictation (#139): fetch the ~1 MB VAD model for next time; this job
                // decodes unsegmented in short chunks instead.
                runCatching { ModelDownloadWorker.enqueue(context, SILERO_VAD_MODEL) }
            }

            // ---- chunk + transcribe -------------------------------------------------------------
            val totalSamples = decoded.pcmBytes / 2
            val maxChunk = AudioTranscriptionRoutes.maxChunkSamples(plan, vadAvailable = vadFile != null)
            val energies = if (totalSamples > maxChunk) AudioChunkPlanner.scanEnergies(pcm) else null
            val chunks = AudioChunkPlanner.plan(totalSamples, maxChunk, energies, AudioTranscriptionRoutes.searchBackSamples(maxChunk))
            event(id, JobEvent.TranscribeStarted(chunks.size, decoded.durationMs))

            val language = DictationLanguage.languageOrNull(context)
            val vocabulary = VocabularyTerms.parse(prefs.getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED))
            val texts = ArrayList<String>(chunks.size)
            var routeIndex = 0
            for (chunk in chunks) {
                if (isCancelled(id)) throw Stop(null)
                val chunkFile = if (chunks.size == 1) pcm else File(workDir, "${id}_${chunk.index}.pcm").also {
                    try { AudioChunkPlanner.extract(pcm, chunk, it) } catch (e: IOException) {
                        it.delete(); throw Stop(if (isNoSpace(e)) JobFailure.STORAGE else JobFailure.UNREADABLE)
                    }
                }
                try {
                    val r = ChunkWalk.run(plan.routes.size, routeIndex) { i ->
                        if (isCancelled(id)) return@run ChunkAttempt.Cancelled
                        when (val route = plan.routes[i]) {
                            AudioRoute.Local -> {
                                val t = local ?: LocalTranscriber.create(context, localModel!!).also { local = it }
                                    ?: return@run ChunkAttempt.Error()
                                try {
                                    ChunkAttempt.Text(transcribeLocalChunk(t, chunkFile, vadFile))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Local chunk failed: ${e.javaClass.simpleName}")
                                    ChunkAttempt.Error()
                                }
                            }
                            is AudioRoute.Cloud -> transcribeCloudChunk(id, route.entry, chunkFile, language, vocabulary)
                        }
                    }
                    if (r.cancelled) throw Stop(null)
                    texts += r.text ?: throw Stop(JobFailure.TRANSCRIPTION_FAILED)
                    routeIndex = r.routeIndex
                } finally {
                    if (chunkFile !== pcm) chunkFile.delete()
                }
                event(id, JobEvent.ChunkDone(chunk.index))
            }
            // Free the (large) on-device ASR model before cleanup can load a local LLM.
            local?.release(); local = null
            pcm.delete()

            val raw = SegmentedTranscript.join(texts)
            if (raw.isBlank() || isJunkTranscript(raw)) throw Stop(JobFailure.NO_SPEECH)
            if (isCancelled(id)) throw Stop(null)

            // ---- cleanup ------------------------------------------------------------------------
            val cleanup = cleanUp(id, raw)

            // ---- deliver ------------------------------------------------------------------------
            val finalText = cleanup.cleaned ?: raw
            val timestamp = System.currentTimeMillis()
            val historyOn = prefs.getBoolean("dictation_history_enabled", true)
            if (historyOn) {
                DictationHistoryStore.forContext(context).upsert(DictationHistoryEntry(timestamp, raw, cleanup.cleaned))
            } else {
                // History is the one place a job's text lives; with it off, hand the text over now.
                ClipboardUtil.copy(context, finalText)
            }
            val done = store.update(id, JobEvent.Completed(if (historyOn) timestamp else null, cleanup.failed, copied = !historyOn))
            deleteAudio(id)
            done?.let { onStatus(it) }
        } catch (s: Stop) {
            fail(id, s.failure)
        } catch (e: Throwable) {
            Log.e(TAG, "Audio job crashed: ${e.javaClass.simpleName}")
            fail(id, JobFailure.TRANSCRIPTION_FAILED)
        } finally {
            runCatching { local?.release() }
            pcm.delete()
            File(workDir, "$id.tmp").delete()
        }
    }

    private fun fail(id: String, failure: JobFailure?) {
        val job = if (failure == null || isCancelled(id)) {
            val cancelled = store.update(id, JobEvent.Cancel)
            // A cancelled import/decode of a user's own file leaves nothing behind; a recorded
            // note went back to SAVED (its audio is the only copy) and is kept.
            if (cancelled != null && !AudioJobMachine.keepsAudio(cancelled)) deleteAudio(id)
            cancelled
        } else {
            store.update(id, JobEvent.Failed(failure))
        }
        job?.let { onStatus(it) }
    }

    private fun event(id: String, e: JobEvent) {
        store.update(id, e)?.let { onStatus(it) }
    }

    private fun deleteAudio(id: String) {
        AudioJobFiles.sourceFile(filesDir, id).delete()
    }

    // ---- import ---------------------------------------------------------------------------------

    private fun importSource(job: AudioJob) {
        val id = job.id
        val uri = job.sourceUri?.let { Uri.parse(it) } ?: throw Stop(JobFailure.UNREADABLE)
        event(id, JobEvent.ImportStarted)
        val dest = AudioJobFiles.sourceFile(filesDir, id)
        val tmp = File(dest.parentFile, "${dest.name}.part")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw Stop(JobFailure.UNREADABLE)
            input.use { src ->
                tmp.outputStream().buffered(256 * 1024).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (isCancelled(id)) throw Stop(null)
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            if (tmp.length() == 0L) throw Stop(JobFailure.UNREADABLE)
            if (!tmp.renameTo(dest)) throw Stop(JobFailure.STORAGE)
        } catch (s: Stop) {
            tmp.delete(); throw s
        } catch (e: SecurityException) {
            tmp.delete(); throw Stop(JobFailure.UNREADABLE)
        } catch (e: IOException) {
            tmp.delete(); throw Stop(if (isNoSpace(e)) JobFailure.STORAGE else JobFailure.UNREADABLE)
        }
        store.mutate(id) { it.copy(hasLocalAudio = true, sourceUri = null) }
    }

    private fun isNoSpace(e: Throwable?): Boolean {
        val m = e?.message?.lowercase() ?: return false
        return "enospc" in m || "no space" in m
    }

    // ---- transcription --------------------------------------------------------------------------

    private fun resolveLocalModelName(configured: String): String? {
        if (configured.isNotBlank()) {
            return configured.takeIf { ModelDownloader.isInstalledDir(File(filesDir, "models/$it")) }
        }
        return LocalTranscriber.availableModels(context).firstOrNull()
    }

    private fun transcribeLocalChunk(t: LocalTranscriber, file: File, vadFile: File?): String {
        val vad = vadFile?.let { SherpaVadHandle.create(it) }
        return try {
            if (vad != null) t.transcribeSegmented(file, vad, AudioChunkPlanner.SAMPLE_RATE)
            else t.transcribe(PcmFileBuffer.readAsFloatArray(file), AudioChunkPlanner.SAMPLE_RATE)
        } finally {
            vad?.close()
        }
    }

    private fun transcribeCloudChunk(
        id: String,
        entry: ProviderChainEntry,
        file: File,
        language: String?,
        vocabulary: List<String>,
    ): ChunkAttempt {
        val apiKey = ProviderCredentialStore.getOrLegacy(context, entry)
        // One retry with backoff: a 40-chunk job should not die to one dropped connection.
        for (attempt in 0..1) {
            if (isCancelled(id)) return ChunkAttempt.Cancelled
            val latch = CountDownLatch(1)
            var text: String? = null
            val cb: (String?) -> Unit = { text = it?.takeIf(String::isNotBlank); latch.countDown() }
            when (entry.kind) {
                ProviderKind.OPENAI -> TranscriberClient.transcribe(
                    file, apiKey, inFlight,
                    baseUrl = entry.baseUrlOverride ?: PostProcessor.DEFAULT_BASE_URL,
                    model = entry.transcriptionModel?.ifBlank { null } ?: TranscriberClient.DEFAULT_MODEL,
                    vocabularyTerms = vocabulary,
                    language = language,
                ) { cb(it.text) }
                ProviderKind.GEMINI -> GeminiTranscriberClient.transcribe(
                    file, apiKey,
                    entry.transcriptionModel?.ifBlank { null } ?: GeminiTranscriberClient.DEFAULT_MODEL,
                    inFlight,
                    vocabularyTerms = vocabulary,
                    language = language,
                ) { cb(it.text) }
                else -> return ChunkAttempt.Error()
            }
            if (!latch.await(NetworkClients.CALL_TIMEOUT_SECONDS + 30, TimeUnit.SECONDS)) inFlight.cancel()
            if (isCancelled(id)) return ChunkAttempt.Cancelled
            text?.let { return ChunkAttempt.Text(it) }
            if (attempt == 0) {
                try { Thread.sleep(3_000) } catch (_: InterruptedException) { return ChunkAttempt.Cancelled }
            }
        }
        return ChunkAttempt.Error()
    }

    // ---- cleanup --------------------------------------------------------------------------------

    private class CleanupOutcome(val cleaned: String?, val failed: Boolean)

    /** Same toggle, chain, prompt and vocabulary as live dictation; applied piece by piece because
     *  a long transcript is far beyond a cleanup model's context window. */
    private fun cleanUp(id: String, raw: String): CleanupOutcome {
        if (!PostProcessingToggle.shouldRunCleanup(PostProcessingToggle.isEnabled(context))) return CleanupOutcome(null, false)
        val chain = ProviderChainRuntime.effectiveChainForCleanup(
            ProviderChainStore.load(context), CloudFeatureToggle.cleanupEnabled(context), DictationModeToggle.allowLocalFallback(context),
        )
        val waterfall = ProviderChainRuntime.cleanupWaterfallFor(chain)
        if (waterfall.steps.isEmpty()) return CleanupOutcome(null, false)

        event(id, JobEvent.CleaningStarted)
        val prefs = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
        val vocabulary = VocabularyTerms.parse(prefs.getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED))
        val savedPrompt = prefs.getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
        val prompt = PostProcessor.withKeepLanguage(PostProcessor.interpolateVocabulary(savedPrompt, vocabulary))
        val cursor = CleanupWaterfallCursor()

        var anyFailed = false
        var anyCleaned = false
        val out = ArrayList<String>()
        // A small on-device cleanup model collapses on multi-paragraph input (its output validator
        // rejects it), so it gets short pieces; cloud models take whole paragraphs.
        val pieceChars = if (waterfall.usesLocalLlm()) TranscriptChunker.LOCAL_MAX_CHARS else TranscriptChunker.DEFAULT_MAX_CHARS
        for (piece in TranscriptChunker.split(raw, pieceChars)) {
            if (isCancelled(id)) throw Stop(null)
            val latch = CountDownLatch(1)
            var result: PostProcessor.Result? = null
            PostProcessor.processProviderChain(
                text = piece,
                prompt = prompt,
                chain = chain,
                cursor = cursor,
                cancelHolder = inFlight,
                credentialLookup = { kind -> ProviderCredentialStore.getLegacyByKind(context, kind) },
                entryCredentialLookup = { entryId -> ProviderCredentialStore.get(context, entryId) },
                localModelPath = { ModelDownloader.localCleanupModelFile(context, LocalCleanupProvider.selectedModel(context))?.absolutePath },
                localPrompt = LocalCleanupProvider.selectedSystemPrompt(context),
                localVocabulary = vocabulary,
                temperatureCacheContext = context.applicationContext,
            ) { r -> result = r; latch.countDown() }
            if (!latch.await(CLEANUP_PIECE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) inFlight.cancel()
            if (isCancelled(id)) throw Stop(null)
            val cleaned = result?.text?.takeIf { it.isNotBlank() }
            if (cleaned != null) { out += cleaned; anyCleaned = true } else { out += piece; anyFailed = true }
        }
        // Nothing improved: report the raw transcript honestly rather than a "cleaned" copy of it.
        return if (anyCleaned) CleanupOutcome(out.joinToString("\n\n"), anyFailed) else CleanupOutcome(null, true)
    }

    companion object {
        private const val TAG = "AudioJob"
        private const val CLEANUP_PIECE_TIMEOUT_SECONDS = 240L

        /** Best-effort display name for a content URI (never throws). */
        fun displayNameOf(context: Context, uri: Uri, fallback: String): String = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
}
