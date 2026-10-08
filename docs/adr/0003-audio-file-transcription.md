# ADR-0003: Audio-file transcription and record-now/transcribe-later

Date: 2026-10-07
Status: Accepted
Refs: #285, #281 (items 2 and 4), ADR-0002 (#284)

## Decision

- **A separate, persisted job pipeline, not a `DictationRuntime` mode.** `DictationRuntime` is bound
  to one live recording, the overlay/IME host and the single process-wide session lease. A file job
  can run for many minutes, queue, survive a restart and have no host. Jobs are `AudioJob` rows in
  `audio_jobs.jsonl` (`AudioJobStore`), moved only by the pure `AudioJobMachine`. The *engines* are
  shared: `LocalTranscriber`, `TranscriberClient`, `GeminiTranscriberClient`, the cleanup waterfall,
  `DictationHistoryStore`, and the same settings (local vs cloud, provider chain and fall-back
  toggles, Dictation language, vocabulary, cleanup on/off).
- **Never widen where audio goes.** `AudioTranscriptionRoutes.plan` mirrors the live rules: an
  on-device user stays on-device unless "fall back to cloud" is on.
- **Decode to 16 kHz mono PCM16 with `MediaExtractor`/`MediaCodec`** (`AudioFileDecoder`,
  `PcmNormalizer`), streaming to a cache file so memory is flat for any length.
- **Chunking.** `AudioChunkPlanner` cuts at the quietest 100 ms frame near the limit. Limit = the
  smallest any route in the plan accepts: 5 min (9.6 MB PCM) for cloud — under the ~25 MB
  OpenAI-compatible upload cap and Gemini's 10 MB inline cap — 5 min for on-device with the VAD
  model (the recognizer then splits to <=15 s segments, #132), 25 s without it. A chunk is retried
  on the next route like the live provider walk. Cleanup runs on <=3000-char pieces (300 for the
  on-device cleanup model, which rejects long input).
- **Foreground-service types (targetSdk 36).** `AudioTranscriptionService` is `dataSync` (type and
  `FOREGROUND_SERVICE_DATA_SYNC` already declared for #56/#284): processing a user-initiated
  payload. `mediaProcessing` is API 35+ only (minSdk 30) and needs a new permission; `shortService`
  is capped at ~3 min. `AudioRecorderService` is `microphone` with the new, normal-protection
  `FOREGROUND_SERVICE_MICROPHONE`, required on Android 14+. Both are started only from a visible
  Activity (so the background-start and while-in-use restrictions never apply) and `onTimeout`
  fails the running job with its audio kept.
- **No storage permission.** Input is the share sheet (`ACTION_SEND`/`SEND_MULTIPLE`, `audio/*`) and
  `ACTION_OPEN_DOCUMENT`. The share target accepts only `content://` URIs and never this app's own
  authorities. No `READ_MEDIA_AUDIO`.
- **Privacy.** No transcript text in logs or notifications. A notification's Copy action reads
  History by timestamp at tap time. With History off, the text is put on the clipboard instead of
  being lost (and the job says so). Imported audio is deleted on success/cancel; a failed job keeps
  it for retry; a recorded note is only ever removed by the user.

## Known limits

- Cloud routes are covered by unit tests of routing/size limits but were not exercised on a device.
- A process kill mid-job fails it as "interrupted"; it is retryable if its audio was already copied.
- Chunk boundaries are quiet-point cuts, not word-aligned; a word straddling a cut can be mis-heard.
