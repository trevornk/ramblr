# ADR-0002: Finish in-flight transcription when the user switches apps

Date: 2026-10-05
Status: Accepted (needs on-device validation, see the PR checklist)
Refs: #284, #281 (items 3 and 5)

## Problem

Issue #284: "Switching apps mid-transcription currently loses the work." The user taps stop,
switches to another app while Ramblr is transcribing/cleaning, and the dictation either vanishes
or lands somewhere it should not.

## What actually happens today (read from the code, per host)

The pipeline is one `DictationRuntime` per host. Capture ends at the stop tap (`stopAndTranscribe`),
then `continueTranscription` -> local/cloud transcription (plain `thread {}` / OkHttp callbacks)
-> `handleTranscriptionResult` -> cleanup waterfall -> `listener.deliverText` -> `resetToIdle`.
There is **no coroutine scope** to cancel: work runs on plain threads guarded by a token
(`TranscriptionGuard`) and a 400 s watchdog. Switching apps does not itself cancel any of it. What
differs is the host:

**Accessibility host (`WhisperAccessibilityService`).** Nothing cancels the work on an app switch;
`onAccessibilityEvent` is deliberately empty. The failure is at the *end* of the pipeline:
`deliverText` -> `injectText` -> `findInjectionCandidates()` reads `rootInActiveWindow` *at delivery
time* and writes into whatever field is focused **now**. So a finished dictation is typed into the
wrong app (a privacy bug, worse than losing it), or, when no editable field is focused, falls to the
clipboard-only fallback with only an overlay bubble the user has probably not seen. The process is
also not protected by anything stronger than a bound accessibility service plus the existing
`ramblr:transcription` wakelock.

**IME host (`RamblrImeService`).** Switching apps/fields delivers `onFinishInput` (and
`onWindowHidden` when the keyboard goes away). Both call `loseLifecycle`, which marks the
`ImePanelController` inactive, invalidates the destination ticket, calls `runtime.invalidate()`
(`beginShutdown`: cancels the in-flight HTTP call, resets the state machine, cancels the guard) and
queues `finishShutdown`. So an in-flight transcription is **actively cancelled and discarded**: the
result is never delivered, never reaches history, and no message is shown anywhere. This is the
real "loses the work" case from the report.

**Both hosts** share two further gaps: a transcription that fails or times out while the user is
elsewhere shows only a toast (and an in-panel message in the IME), and nothing keeps the process at
foreground priority across a long local decode or slow cloud call once the app is not visible.

## Decision

1. **Keep the pipeline, change who ends it.** No second transcription path and no new execution
   model. `DictationRuntime` keeps owning the work exactly as today.
2. **Foreground service as a process hold.** `BackgroundTranscriptionService` is a short-lived
   `dataSync` foreground service whose only job is to hold the process at foreground priority
   from transcription start to the pipeline's terminal state. `DictationRuntime` asks for it
   through a small seam (`BackgroundWork`): `begin()` when the stop tap mints the token (and on the
   10-minute auto-stop), `end()` from every terminal path (`resetToIdle`, `beginShutdown`,
   `releaseSessionLease`). A process-wide counter (`BackgroundWorkHolds`) makes overlapping
   dictations/runtimes safe. Hard stops: a 430 s self-cap (just past the 400 s watchdog), the
   platform `onTimeout` callbacks (API 34/35), and `START_NOT_STICKY`.
3. **Delivery asks "is the original target still there?"** (pure functions in
   `BackgroundDictation.kt`):
   - *Target current* -> exactly the pre-#284 code path. Nothing new: no clipboard write, no
     notification, no redirection.
   - *Target gone* -> **never insert**. Copy to the clipboard, keep the (already written) history
     row, post one notification ("on the clipboard / in history"). Exclusion (#256) and
     no-retention editors still win: nothing is copied, and the notice says so.
   - *Failure/timeout/no-speech while the target is gone* -> one failure notification that opens
     Ramblr. While the target is current the existing toast is the whole story.
4. **IME host: detach instead of cancel.** If the input lifecycle is lost while `TRANSCRIBING`,
   the controller and runtime are kept alive and delivery goes through the existing ticket check
   (`commitIfCurrent`): same editor still bound -> commit as today; otherwise the fallback above.
   Recording and idle are torn down exactly as before, and `DESTROYED` always tears down.
5. **Fail open.** Every new decision requires a *positive* signal that the user left (different app
   holding input focus, a different field in the same app with the captured node demonstrably
   alive-but-unfocused, device locked, another app in front with nothing focused). Anything
   unreadable, a transient SystemUI window, or a focus-stealing tap answers "still current", which
   is the old behavior.

### How "the target is gone" is determined (accessibility host)

At transcription start the host records the package of the focused input node (or the active
window) and the focused node itself. At delivery time it re-reads the world: input-focused
package, active-window package, keyguard state, and whether the captured node refreshes and is
now unfocused while another node has input focus. `isDeliveryTargetCurrent` is a pure function of
those facts. A fresh read of `rootInActiveWindow` is the same primitive the existing exclusion
check already uses at this exact moment (see PRIVACY.md), so no new accessibility event
subscription or polling is added.

### Android 14+ foreground-service constraints

- **Type.** Android 14 requires a declared `foregroundServiceType` and the matching
  `FOREGROUND_SERVICE_<TYPE>` permission. We use `dataSync` and reuse the manifest's existing
  `FOREGROUND_SERVICE_DATA_SYNC` and `FOREGROUND_SERVICE` permissions (already granted for model
  downloads); no new install-time permission is added. `dataSync` is the honest fit for "process a
  user-initiated payload (audio -> text)" that may involve a network transfer.
- **Why not `microphone`.** Capture has already ended. A `microphone`-type FGS is a *while-in-use*
  type: it cannot be started from the background, would imply continued mic access, and invites
  Play policy scrutiny for no benefit. (A full microphone-FGS for *recording* is the separate
  concern tracked in #204 and is out of scope here.)
- **Why not `shortService`.** Its ~3 minute limit is shorter than a worst-case local decode plus
  the cleanup waterfall (watchdog 400 s).
- **Start location.** Starting an FGS from the background is restricted on API 31+, but starting
  while the app is visible (or, for the IME, while it is the current input method) is allowed. The
  service is started at the stop tap, i.e. while the user is still in the field they dictated
  into; by the time they switch away it is already running. A start that is refused
  (`ForegroundServiceStartNotAllowedException`, e.g. a QS-tile stop with the shade closing) is
  caught: the dictation proceeds unprotected, exactly as before #284.
- **Timeouts.** On API 35+ `dataSync` is time-limited (~6 h/day) and the system calls
  `onTimeout`; API 34 calls the single-argument form. Both stop the service. A dictation is
  minutes at most, so the budget is not a practical limit.
- **POST_NOTIFICATIONS (API 33+).** Already declared; the user grants it from the existing
  permission prompt. The FGS notification is posted with `FOREGROUND_SERVICE_DEFERRED`, so a normal
  short dictation shows **no** notification at all (the system only surfaces it if the service
  outlives ~10 s). If notifications are denied the FGS still runs (the notification just isn't
  shown), and the *result/failure* notices fall back to a toast rather than silence.
- **Channels.** `background_dictation_work` (IMPORTANCE_LOW, the ongoing one) and
  `background_dictation_result` (IMPORTANCE_DEFAULT, the "text is on the clipboard" / "failed"
  ones) so the routine one can be muted without losing lost-work alerts. One fixed notification id
  for results so repeats replace instead of stacking.
- **Privacy.** No notification, log line or analytics event carries transcript text. Failure
  notices carry a coarse class (no speech / timed out / failed), never the raw provider error.

## Alternatives rejected

- **Coroutine/`WorkManager` re-architecture.** The report asks for a scope that outlives the UI,
  but the pipeline already outlives it (plain threads on a long-lived runtime). Rewriting it would
  fork or destabilise the daily-driver path for no gain. `WorkManager` expedited work cannot carry
  the in-memory PCM/native model state, and would reintroduce a second transcription path.
- **Insert into whatever is focused now ("be helpful").** Types dictated text into the wrong app.
  Rejected outright as a privacy/security bug; it is in fact the current accessibility-host
  behaviour this ADR removes.
- **Keep the IME fully alive across app switches (never tear down).** Recording must still stop at
  `onFinishInput`/hide (mic must be released), and a destroyed service cannot be saved. Only the
  TRANSCRIBING phase is detached.
- **Notify on every dictation.** The stay-in-the-field path is protected and must stay silent;
  every notification above is gated on a positive "target is gone" signal.
- **Persist the audio to retry later.** The existing pipeline deletes PCM on success/exhaustion
  by design (privacy). Adding a retained-audio store is a larger decision than this issue; the
  failure notice says the audio isn't kept.

## Consequences / known limitations

- The accessibility-tree reads cannot run under Robolectric: the unit tests prove the decision
  functions and the host wiring with the two platform reads substituted; real tree behaviour is on
  the on-device checklist in the PR.
- Detecting "a different field in the same app" requires the captured node to still refresh;
  apps that recycle nodes aggressively fall to "unknown" and keep the old behaviour (insert).
- IME: an `onStartInput` restart for the very same field bumps the editor generation, so a
  dictation that was transcribing across it takes the clipboard + notification path even though the
  user is back in the same field. Previously it was lost; this is strictly better but not perfect.
- A process kill (low-memory, force-stop) still loses an in-flight dictation: the hold lowers the
  odds, it cannot make them zero, and the audio is not persisted (see above).
- Failure notifications do not retain audio; "open Ramblr and dictate again" is the recovery.
