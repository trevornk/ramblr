# Ramblr — STT Dictation Product & Architecture Plan

**Date:** 2026-07-02
**Repo:** `trevornk/ramblr` 
**Status:** Approved planning baseline. Issues #1–#23 existed before this plan; issues #24+ were created by it. GitHub milestones M1–M4 mirror the milestones below.

## 1. Product goal

Make Ramblr a daily-driver Android dictation tool that matches or exceeds Wispr Flow, Fluid Voice, and Typeless on the axes that matter in practice:

1. **Transcription quality** — accurate STT (local sherpa-onnx or cloud Whisper) plus LLM cleanup that turns rambling speech into structured, intent-preserving text (filler removal, self-correction resolution, list detection).
2. **Reliability** — never lose a transcript, never leak the mic, never strand the UI. The accessibility service must survive weeks of daily use without a restart.
3. **Privacy** — honest data-flow story: local mode keeps audio on-device; anything that leaves the device is explicit and user-controlled (BYOK, custom endpoints incl. Trevor's LAN OmniRoute).
4. **App-aware output** — tone/style adapts to the target app (casual chat vs. formal email vs. terminal command).

### Non-goals

- **Not an IME/keyboard replacement.** The overlay + accessibility-injection model is intentional (per README); keep SwiftKey.
- **No backend service.** All network calls go device → user-configured API. No telemetry, no accounts.
- **No iOS/desktop.** Android only.
- **No real-time conversational agent.** This is dictation → text, not a voice assistant; the cleanup LLM must never *answer* the dictated text.
- **Streaming live preview is deferred**, not abandoned — it requires switching from `OfflineRecognizer` to a streaming recognizer and is tracked as a backlog issue, not a milestone item.

## 2. Competitor-quality acceptance criteria

Ramblr is "done enough" when all of these hold on a real device:

| Axis | Bar |
|---|---|
| Cleanup quality | On the eval-harness sample set (#2), structured cleanup removes fillers, resolves self-corrections, and formats spoken lists correctly on ≥90% of samples, with **zero hallucinated content** on any sample. |
| Transcript safety | No dictation is ever silently lost: every transcript is recoverable from local history even when injection fails and the clipboard toast is missed. |
| Reliability | 1 week of daily use with no service crash, no stuck TRANSCRIBING state, no mic-in-use indicator after stop, no OOM from long recordings. Every network call has a bounded timeout and a cancel path. |
| Latency | Tap-stop → text injected: ≤3s for a 15s utterance on-device (Parakeet 110M, mid-range phone); cloud path bounded only by network, with visible progress and cancel. |
| Injection compatibility | Documented compatibility matrix across ≥8 common apps (Gmail, Slack, Signal, Chrome, Google Messages, Termux, a WebView input, Discord); graceful, *noticeable* fallback where injection can't work. |
| Privacy | API keys encrypted at rest; no other-app screen text in logcat in release builds; clipboard marked sensitive and cleared after grace period; "local mode + cleanup sends text to cloud" is disclosed explicitly. |
| Onboarding | A new user reaches a successful first dictation in <3 minutes without reading the README. |

## 3. Architecture

### Current shape (as imported)

~1.6k lines of Kotlin, two entry points, no DI, callbacks + bare threads:

- `WhisperAccessibilityService` (628 lines) — overlay UI, touch handling, recording state machine, AudioRecord lifecycle, transcription dispatch, cleanup dispatch, node-finding + text injection, clipboard fallback. **God object; almost every audit finding lives here.**
- `MainActivity` (489 lines) — flat settings screen + model-download orchestration on bare threads.
- `LocalTranscriber` / sherpa-onnx JNI wrappers — offline batch recognition.
- `TranscriberClient` — OpenAI Whisper multipart upload (default OkHttp timeouts).
- `PostProcessor` — hardcoded OpenAI chat completions, `gpt-4o-mini`, two prompt constants.
- `ModelDownloader` — download + tar.bz2 extract, directory-existence "installed" check.
- Tests: JVM unit tests for WAV encoding, JSON parsing, catalog parsing only.

### Target seams (refactor incrementally, no big-bang rewrite)

The plan deliberately keeps the two-process-entry-point shape and carves seams *inside* it:

1. **`RecordingEngine`** — owns AudioRecord lifecycle end-to-end on its own thread (create → validate → drain → stop → release), exposes `start()/stop(): PcmResult` with a max-duration cap and file-backed buffering. Fixes the #9/#12/#16/#21 cluster in one seam. The service only flips UI state.
2. **`TranscriptionPipeline`** — one coordinator: `PcmResult → Transcriber (local|cloud) → Cleanup (optional) → CleanText`, with a single cancellable job, bounded timeouts, and a watchdog (fixes #14/#20). Both `TranscriberClient` and `PostProcessor` share one configured `OkHttpClient`.
3. **`CleanupService`** — prompt-family registry (light-touch / structured / styles), backend config (base URL, model, key — #4), personal-vocabulary interpolation. Pure-JVM testable: prompt assembly and response parsing take no Android deps.
4. **`TextInjector`** — node discovery, strategy ladder (custom paste → ACTION_PASTE → ACTION_SET_TEXT), clipboard handling (sensitive flag, scheduled clear — #11), fallback UX. Already the best-factored logic in the service; extract as-is.
5. **`ModelStore`** — catalog, atomic install (tmp-dir + rename + `.complete` marker), checksum verification, WorkManager-based downloads with resume (#13/#15/#18/#19/#22).
6. **`SettingsRepository`** — one typed wrapper over (Encrypted)SharedPreferences; the only place pref keys live. Keys move to `EncryptedSharedPreferences` (#8).
7. **`DictationHistory`** — local-only ring buffer (last N transcripts) backing undo and the history screen.

Rule of thumb for every fix: *move the logic behind the seam it belongs to while fixing it*, so the audit-fix milestone doubles as the refactor.

## 4. Milestones

Ordered by "what breaks a daily driver first". Each milestone is shippable to Trevor's phone.

### M1 — Daily-driver reliability & safety (do first)
The service must be safe to leave enabled 24/7 before any feature work.
- #9 mic release on destroy, #12 + #21 recording state machine (→ `RecordingEngine` seam), #16 recording cap + file-backed buffer
- #14 + #20 timeouts, cancel, watchdog (→ `TranscriptionPipeline` seam)
- #17 native resource lifecycle
- #8 encrypted key storage, #10 release-build log hygiene, #11 clipboard sensitivity/clear
- #24 CI: build + unit tests on every push (infrastructure for everything after)

### M2 — Model management robustness
Local mode must be trustworthy since it is the privacy story.
- #18 Whisper archive filename detection (shipped model currently broken)
- #13 + #19 atomic install, checksum, corrupt-model recovery UI
- #15 WorkManager downloads (single-flight, survives backgrounding), #22 free-space guard + resume

### M3 — Cleanup quality: Fluid-grade rewriting
The core differentiator vs. stock keyboard dictation.
- #2 eval harness with real rambling fixtures (build *first* — it gates #1)
- #1 structured-rewrite prompt family
- #4 custom base URL + model for cleanup (OmniRoute)
- #26 personal vocabulary management UI
- #3 tone/style presets (global first, per-app mapping as stretch)
- #23 explicit disclosure when local transcription + cloud cleanup

### M4 — Daily-driver UX & polish
- #6 first-run onboarding wizard
- #5 injection compatibility matrix + fallback UX
- #25 dictation history screen (transcript never lost)
- #27 undo last injection
- #28 rebrand to Ramblr (name/icon early; `applicationId` change as release step)

### Backlog (post-M4, explicitly deferred)
- #29 streaming live-preview transcription (engine-level change to streaming recognizer)
- Custom base URL for *transcription* (Whisper-compatible endpoints) — fast-follow to #4 if OmniRoute proxies transcription

## 5. Testing & eval strategy

**JVM unit tests (CI, every push — #24):** everything behind the seams that has no Android dependency: prompt assembly + vocabulary interpolation, response parsing, WAV encoding, catalog/checksum logic, injection-strategy selection given fake node trees, history ring buffer. Grow the existing 4 test files alongside each seam extraction.

**Offline fixtures:**
- `app/src/test/resources/eval_samples/` — 15–25 real rambling transcripts (#2): quick note, brainstorm, jargon-heavy, self-corrections, spoken lists. Text fixtures are the regression corpus for prompt work.
- Recorded WAV fixtures (a few short clips) for `RecordingEngine`/`WavWriter` round-trip tests and local-transcriber smoke tests where feasible.
- Fake sherpa archive layouts (bare and `base.en-`-prefixed file names) for `ModelStore` detection tests (#18).

**Cleanup eval harness (manual, costs API credits — #2):** standalone script runs every fixture through {prompt × backend} and emits a before/after markdown report for human review. Not in CI. Every prompt change must include a regenerated report in the PR. Hallucination check is a hard gate: any invented content fails the prompt.

**Manual device verification (per milestone, on Trevor's phone):**
- M1: dictate daily for several days; toggle service off mid-recording (mic indicator must clear); airplane-mode mid-transcription (must recover with cancel/watchdog); 10-minute recording (must cap, not OOM).
- M2: kill app mid-download and mid-extract; verify recovery UI; verify Whisper Base actually loads.
- M3: side-by-side eval report review; live round-trip against OmniRoute on LAN and graceful failure off-LAN.
- M4: fresh-install onboarding walkthrough; injection matrix across the 8-app list; history/undo flows.
- Every issue's **Verification** section names its device checks; PRs quote the results.

## 6. Security & privacy requirements

1. **Keys:** API keys in `EncryptedSharedPreferences` (Keystore-backed); masked input; display only `sk-…XXXX` suffix (#8). Never log keys or request headers.
2. **Logs:** release builds must never log other apps' screen text, transcripts, or node text — structural metadata only; verbose tracing behind `BuildConfig.DEBUG` (#10). Add a release-build logcat audit to M1 verification.
3. **Clipboard:** transcripts marked `EXTRA_IS_SENSITIVE`; cleared (or previous clip restored) after a short grace period once injection succeeds (#11).
4. **Mic:** recording provably stops on every exit path — stop, destroy, crash, timeout cap (#9/#12/#16). OS mic indicator is the acceptance check.
5. **Data flow honesty:** local mode + cleanup enabled ⇒ explicit disclosure that text leaves the device (#23); custom-endpoint mode documented in PRIVACY.md (#4). No analytics, no crash reporting that ships transcript content.
6. **No secrets in repo:** eval harness reads keys from env/local config; fixtures must be scrubbed of anything Trevor wouldn't publish.
7. **Accessibility scope:** service acts only on explicit tap; no event listening beyond what injection needs (`onAccessibilityEvent` stays empty).

## 7. Execution order

Within-milestone order, chosen so each step de-risks the next:

1. **#24** CI (tests must run on every PR before touching the risky code)
2. **#12 + #21 + #9 + #16** as one `RecordingEngine` extraction (same code, one coherent PR — review together)
3. **#14 + #20** shared OkHttp client, timeouts, cancel, watchdog (`TranscriptionPipeline`)
4. **#17** native lifecycle; **#8, #10, #11** security triplet (small, independent, parallelizable)
5. **#18** (broken shipped model — small fix, big win) → **#13 + #19** atomic install → **#15 + #22** WorkManager downloads
6. **#2** eval harness → **#1** structured prompt → **#4** OmniRoute endpoint → **#26** vocabulary UI → **#3** styles → **#23** disclosure
7. **#6** onboarding → **#25** history → **#27** undo → **#5** injection matrix → **#28** rebrand
8. Backlog: **#29** streaming preview

**Recommended first coding issues: #24 (CI), then the #12/#21/#9/#16 RecordingEngine cluster.**

Duplicate-cluster note (audit filed overlapping findings; treat as one unit of work each, close together):
- #12 + #21 (AudioRecord races), #13 + #19 (atomic install), #14 + #20 (timeouts — #20 adds cancel/watchdog on top).
