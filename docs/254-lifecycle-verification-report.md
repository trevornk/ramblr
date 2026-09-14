# #254 — Real Lifecycle/Platform Verification Report

Branch: `investigation/254-lifecycle-verify` in `/Users/aiserver/ramblr-wt-254-investigation`
(new local branch, not pushed). Device: Pixel 10a, serial `63141JEA320614`, `ro.build.type=user`
(non-rooted production build, `ro.debuggable=0`), only device touched.

## 1. Claim under test

Parent's uncommitted diff added an identity check to `WhisperAccessibilityService.onDestroy()`
(`if (instance === this) instance = null`) and a Robolectric unit test, with comments asserting
as fact that Android can run a *stale* instance's `onDestroy()` **after** a newer same-component
`onServiceConnected()` already reassigned the shared companion `instance` field, and that this
race explains #254's intermittent-restore reports.

## 2. What was actually done

- Created local branch `investigation/254-lifecycle-verify`, did not touch `main`/other worktrees.
- Read AOSP `ActiveServices.java` (`frameworks/base`, `main` branch, google source) directly:
  `bringDownServiceLocked()` (destroy path) calls `r.app.getThread().scheduleStopService(r)`
  synchronously within the locked AM state and only after removing the `ServiceRecord` from
  `mServicesByInstanceName`; a subsequent `bringUpServiceLocked()`/`retrieveServiceLocked()` for
  the same component looks it up fresh. Nothing in the read path shows two live `ServiceRecord`s
  for the *same* `ComponentName` coexisting under the ordinary "system list changed" rebind flow
  — the destroy is dispatched before a fresh bring-up would create a new one for the same name.
  This is consistent with, not proof against, no-overlap — reading the shell command source is
  not equivalent to tracing every AMS entry point (e.g. process death + restart, OOM kill, ANR)
  that could reorder things; those paths were not read in this session.
- Built a bounded, repeatable, restoring observation harness:
  `scripts/254_lifecycle_harness.sh <serial> <package> <n> <outfile>` — captures the *exact*
  pre-existing `enabled_accessibility_services` baseline, strips only the target package's
  component (leaving Lawnchair's entry and any other untouched), cycles disable→enable N times,
  snapshots `enabled_accessibility_services`, `dumpsys activity services` `ServiceRecord` count,
  and the app's pid after each half-cycle to a JSONL file, and restores + verifies the exact
  original baseline string on exit (including via a `trap`, so a script failure still restores).
- Ran it for 6 disable/enable cycles against the real Ramblr install on the Pixel 10a, with a
  concurrent `adb logcat -v threadtime` capture spanning the whole run.
- Also exercised the **direct in-app** advanced-tier recovery path separately from any shell CLI:
  disabled Ramblr's component via `settings put secure enabled_accessibility_services`, launched
  `MainActivity`, and tapped the real "Accessibility service needed" recovery card in the running
  app UI (`uiautomator` dump + `input tap`, not a CLI call) — this exercises
  `InvocationSecureSettings.reEnableService()` → `Settings.Secure.putString()` from Ramblr's own
  process/own `WRITE_SECURE_SETTINGS` grant, independent of `adb shell settings put`.
- Fixed the production code comment and the test kdoc/inline comments per instruction: removed
  the unproven "this is what causes #254" and "matches reporter" framing, relabeled the guard as
  defensive-only pending proof, and recorded what the on-device evidence actually shows.

## 3. Observed lifecycle sequence (real device, not simulated)

Baseline (captured, not assumed): `app.lawnchair.nightly/app.lawnchair.LawnchairAccessibilityService:com.trevornk.ramblr/com.trevornk.ramblr.WhisperAccessibilityService`.

For every one of 6 disable→enable cycles:

| step | `enabled_accessibility_services` has Ramblr | `dumpsys activity services` ServiceRecord count for `com.trevornk.ramblr` | app pid |
|---|---|---|---|
| start | true | 1 | 28737 |
| after disable | false | 0 | 28737 (process kept alive; only the service record dropped) |
| after enable | true | 1 | 28737 |
| ... (×6, identical) | | | |
| final_restored | true | 1 | 28737 |

**At no point did `dumpsys activity services` show more than one `ServiceRecord` for
`com.trevornk.ramblr/.WhisperAccessibilityService` simultaneously**, and the concurrent logcat
capture shows no `WhisperAccessibilityService`-tagged onCreate/onServiceConnected/onDestroy
overlap (the app doesn't log those transitions by tag, but the `AccessibilityManagerService`/
`AccessibilityUserState`/`SettingsShellCmd` sequence around each toggle completed cleanly with no
interleaved second bring-up before the drop finished, on this device, at this toggle cadence:
`settings put` calls roughly ~3s apart, well above whatever internal AMS scheduling latency
exists on this hardware). Full raw evidence: `/tmp/254_harness.jsonl`, `/tmp/254_logcat.txt`
(not committed — device/session-local; sensitive-prefs-free, only lifecycle/service state).

Device state (pids, WRITE_SECURE_SETTINGS grant, `enabled_accessibility_services`) was restored
to the exact captured baseline after the harness run and re-verified. No other service entries,
Tasker/Lawnchair bindings, permissions, or user data were touched. Nothing was force-stopped,
uninstalled, or had its signer/config changed.

## 4. Direct in-app Settings.Secure recovery path (separate from CLI)

Confirmed working end-to-end via the actual app UI, not a CLI call: disabling Ramblr's component
via `settings put` (simulating an external actor, e.g. MacroDroid), the MainActivity recovery
banner ("Accessibility service needed") was tapped in the live UI, `reEnableService()` ran (own
process, own `WRITE_SECURE_SETTINGS` grant, `granted=true` confirmed via
`dumpsys package com.trevornk.ramblr`), and `enabled_accessibility_services` was restored to the
exact original baseline string, matching the `.hermes/ramblr-254-platform-investigation.md`
research memo's Option-A architecture claim (already-shipped code, now directly UI-exercised
rather than only inferred from source).

## 5. Failure matrix

| Hypothesis | Method | Result |
|---|---|---|
| Stale old `onDestroy()` fires after new `onServiceConnected()` for the same component (the parent's claimed root cause) | 6× real disable/enable cycles, `dumpsys` ServiceRecord count + logcat, on production non-root Pixel 10a | **Not reproduced.** ServiceRecord count never exceeded 1; no overlap window observed at ~3s toggle cadence. |
| AOSP `ActiveServices` ordering permits two live `ServiceRecord`s for one component | Direct read of `bringDownServiceLocked()`/`bringUpServiceLocked()`/`retrieveServiceLocked()` in `frameworks/base` `main` | Ordering read is consistent with strict serialization (old record removed from map before any new bring-up), but only the "settings-list-changed" path was traced; process-death/restart/OOM-kill entry points were not. **Inconclusive by construction**, not disproven. |
| In-app advanced-tier `Settings.Secure` self-heal works, independent of shell CLI `--user`/`INTERACT_ACROSS_USERS` failures | Live UI tap → `reEnableService()` on-device | **Confirmed.** Restores the exact baseline string via Ramblr's own process grant, no CLI involved. |
| Repeated disable/enable causes any other observable app-status regression (bound services, orphaned processes, permission loss) | Harness across 6 cycles | **None observed.** pid stable throughout, `WRITE_SECURE_SETTINGS` grant unaffected, no leaked ServiceRecords. |

**No bug was reproduced at this toggle cadence on this device/build.** The production code
change under test is not shown to fix anything; it is being kept only as a documented,
harmless defensive guard per instruction, with comments now saying exactly that.

## 6. Next discriminating diagnostic (since no bug reproduced)

The parent's hypothesized race, if real, is time-sensitive (needs the OLD instance's async
`scheduleStopService`/`onDestroy` to still be in flight when a NEW bind lands) — a ~3s-spaced
harness like this one is far slower than any plausible race window. To actually discriminate:

1. Re-run this harness with the interval collapsed to the minimum the `settings put` shell
   round-trip allows (no `sleep`), and/or drive many back-to-back toggles from a tight loop
   rather than a fixed N, watching for the ServiceRecord count transiently reading >1 or the
   app's pid changing (process restart) mid-cycle — neither happened in this run.
2. Add a one-line, uniquely-tagged temporary log (`Log.d("PhoneWhisper254", "onServiceConnected id=${System.identityHashCode(this)}")` and the equivalent in `onDestroy`) to get authoritative in-process evidence of overlap instead of inferring it from `dumpsys`/AOSP source reading, then remove it before any commit — this is the single highest-value missing piece, and was intentionally not added in this session to avoid landing throwaway prod logging.
3. Since the reporter is on a **Samsung S24 / One UI**, not stock AOSP, and this session could
   only test a stock-ish Pixel: One UI's own background-kill/accessibility-list-revalidation
   behavior (already flagged as an open unknown in `.hermes/ramblr-254-platform-investigation.md`
   §1.3) remains a plausible OEM-specific cause this Pixel run cannot rule in or out. A
   non-crash logcat from the reporter's own device around one failed restore (already recommended
   in that memo, §6.1) is still the actual next step, not further Pixel-side unit or CLI testing.

## 7. Phase 2: rapid/overlapping restore matrix (this session's addition)

**Correction to §6 item 1 above and to this report's framing more broadly**: the original 6-cycle
run used ~3s-spaced `settings put` calls with `sleep`, which this report's own §6 already flagged
as not race-proof. This section replaces "next step" language with an actually-executed run.

**Script**: `scripts/254_rapid_matrix.sh <serial> <package> <n> <outfile>` — reuses the phase-1
harness's exact-baseline-capture/trap-restore pattern, and adds:
- Phase A: 50 back-to-back disable→enable cycles with **no `sleep`** between the write and the
  next snapshot (bounded only by the adb round-trip itself).
- Phase B: 5 rounds of **concurrently backgrounded** disable+enable writes (both `settings put`
  calls launched as parallel shell jobs, in both orderings), approximating what two overlapping
  automation macros racing each other could do.
- Every snapshot records THREE independent signals, not just the ServiceRecord count (per
  instruction: ServiceRecord count alone is not app-singleton/bound proof): the raw
  `enabled_accessibility_services` membership, `dumpsys activity services` ServiceRecord count,
  and `dumpsys accessibility`'s **Bound services** count — separately confirmed to key its
  entries by component **label** ("Ramblr (Floating icon)"), not package name; an earlier run of
  this same script (kept at `/tmp/254_rapid_matrix_run1_labelbug.jsonl`) grepped for the package
  string against that block and silently matched zero rows every time — a script bug, caught and
  fixed (`grep "label=Ramblr"`) before drawing any conclusion from the bound-count field. The
  numbers below are from the corrected re-run only.

**Actual run** (device serial `63141JEA320614`, package `com.trevornk.ramblr`, real adb calls,
not simulated): **118 total recorded snapshots** (`/tmp/254_rapid_matrix.jsonl`) — 102 from the
50-cycle fast phase (start + 50×2 + end) and 15 from the 5-round overlapping phase (3 snapshots
per round: after-forward-order concurrent writes, after-reverse-order concurrent writes,
after-explicit-restore).

| Metric (aggregate over all 118 snapshots) | Result |
|---|---|
| Snapshots with `service_record_count > 1` | **0** |
| Snapshots with `bound_count > 1` | **0** |
| Snapshots with more than one pid for the package | **0** |
| `after_disable` snapshots (50) where `enabled_accessibility_services` still listed Ramblr (coalesced/never-took write) | **0** |
| `after_enable` snapshots (50) where it was missing (write silently dropped) | **0** |
| Final restored state vs. captured baseline | **exact match** — `enabled_accessibility_services`, ServiceRecord count (1), Bound-services count (1, "Ramblr (Floating icon)"), pid (28737, unchanged from before the run), `dumpsys accessibility` Crashed services (empty) |

**Timing honesty**: consecutive snapshots were spaced a **median ~1.0s apart, minimum 0.85s**
(measured, not assumed) — bounded by the adb round-trip and shell fork/exec cost per `settings
put`/`dumpsys` call, not by anything in AMS itself. This is faster than phase 1's 3s cadence but
is **not** sub-binder-call resolution: if the hypothesized race window is measured in
milliseconds (a plausible assumption for an async `scheduleStopService` callback racing a bind),
this matrix's ~1s cadence cannot resolve it either, and neither Phase A's back-to-back cycles nor
Phase B's concurrent-write ordering produced a >1 ServiceRecord/Bound-services reading at any
point. **No callback ordering is asserted from this data** — only that `dumpsys`-visible state
never showed two live instances coexisting at this measurement granularity, on this device, this
build. Concurrent writes did occasionally coalesce to the LAST write's outcome winning outright
(both overlap-phase orderings showed a clean binary in/out result every round, never an
intermediate/partial state) — consistent with (not proof of) `Settings.Secure.putString` being
serialized at the ContentProvider layer, distinguishing "coalesced, single write wins" from
"genuine unbind+rebind race" — the distinction this run was designed to observe; this run only
observed the former.

Genuinely still missing, unchanged from §6: real in-process instrumentation (item 2 above,
intentionally not added to avoid landing throwaway prod logging) and Samsung/One UI evidence
(item 3) — this Pixel run, now performed twice (phase 1: 6 disable/enable cycles; phase 2: 60
disable/enable actions sampled across 118 snapshots), still did not reproduce the reported bug.

## 8. Read-only reporter diagnostic (new this session)

Since no bug reproduced on-device and further Pixel-side toggling is unlikely to add signal
without real instrumentation, this session added the smallest useful **read-only** diagnostic the
#254 reporter can run from their own MacroDroid/Tasker shell context, rather than proposing a
speculative fix.

**Why not `adb shell settings get`/`dumpsys`**: confirmed broken for a non-root automation shell
on this exact device this session —
`settings get secure enabled_accessibility_services` under `run-as` throws
`SecurityException: ... requires android.permission.INTERACT_ACROSS_USERS`,
`dumpsys accessibility`/`dumpsys package`/`pm dump` under the app's own uid all throw
`Permission Denial: ... missing android.permission.DUMP`, and `cmd accessibility get-enabled`
throws `requires MANAGE_ACCESSIBILITY permission`. None of these are available to an ordinary
app-UID caller, which is what a MacroDroid/Tasker "run shell command" action typically executes
as (no root assumed). This matches the parent's instruction not to propose a settings-CLI
diagnostic.

**What was added instead** — an app-provided, permission-free status snapshot exposed through the
**existing** exported `AutomationOffReceiver`, reusing its existing `AutomationOffHookToggle`
gate rather than adding any new unrestricted exported component:

- `AutomationOffHook.kt`: `RamblrDiagnosticSnapshot` (data class) + `formatDiagnosticSnapshot`
  (pure `key=value;...` formatter, unit-tested in `RamblrDiagnosticSnapshotTest.kt`, 2 tests, both
  pass).
- `AutomationOffReceiver.kt`: new `ACTION_DIAGNOSTIC` action on the SAME already-exported receiver
  (`com.trevornk.ramblr/.AutomationOffReceiver`), gated behind the same
  `AutomationOffHookToggle.isEnabled()` check the existing `TURN_OFF` action already uses. When
  the hook is off, behaves identically to `TURN_OFF`'s own disabled case (result code 0, no data)
  — indistinguishable from "nothing here." When on, replies via the ordered-broadcast result
  **data string** (not an extra — no parsing dependency needed from a plain shell `am broadcast`
  call) with:
  `instance_connected=<bool>;active_component_enabled=<bool>;inactive_component_enabled=<bool>;automation_off_hook_enabled=<bool>;write_secure_settings_granted=<bool>`
- `AndroidManifest.xml`: added the `DIAGNOSTIC` action string to the existing `<intent-filter>` on
  the existing `<receiver>` — no new exported component, no new permission requested or granted,
  no user-data access, no installed-app enumeration, no SharedPreferences dump.

**What every field actually reads** (all confirmed permission-free for any app on this device,
this session, via `InvocationSecureSettings`'s existing, already-shipped `Settings.Secure` reads
and one same-process static field):
- `instance_connected` — `WhisperAccessibilityService.instance != null` (same signal
  MainActivity's own status row already surfaces).
- `active_component_enabled` / `inactive_component_enabled` — whether the currently-active vs.
  currently-inactive Ramblr component is present in `enabled_accessibility_services`; this is the
  #258 stale-component signature (macro named the wrong of Ramblr's two components) versus a
  genuinely-not-enabled service — exactly the distinction the parent asked this diagnostic
  preserve.
- `automation_off_hook_enabled` — always `true` when a snapshot is returned at all (the action is
  gated on it); included so a parser has one field to assert the broadcast reached a real,
  opted-in install.
- `write_secure_settings_granted` — whether the advanced in-app self-heal tier
  (`reEnableService()`, already confirmed working in §4) is available on this install.

**Usage from the reporter's existing automation context** (once they've already opted in to the
automation off-hook toggle for `TURN_OFF` to be useful at all):

```
am broadcast -a com.trevornk.ramblr.action.DIAGNOSTIC -n com.trevornk.ramblr/.AutomationOffReceiver --user <numeric-user-id>
```

(same `--user <numeric-id>` requirement `automationOffHookCommand()` already documents for
`TURN_OFF`, per #262's numeric-hosting-user fix. **Correction**: an earlier draft of this
paragraph asserted that "MacroDroid's own 'Send Intent'/broadcast action supports ordered
broadcasts and surfaces the result data string" as a flat fact — that claim was never verified
against MacroDroid or Tasker in this investigation and is removed. What was actually confirmed
this session is only that Android's `am broadcast` shell command and a Robolectric-driven
`sendOrderedBroadcast` both deliver the ordered broadcast and expose `result=<code>`/
`data="<string>"` correctly (§8, §11, §12) — whether a specific third-party automation app's UI
exposes that same result data to its user is untested and not claimed here.)

**Verification performed this session**:
- Unit: `./gradlew --max-workers=2 :app:testGithubDebugUnitTest --tests
  "com.trevornk.ramblr.WhisperAccessibilityServiceLifecycleTest" --tests
  "com.trevornk.ramblr.RamblrDiagnosticSnapshotTest"` → **BUILD SUCCESSFUL**, 4/4 tests pass (2
  lifecycle, 2 diagnostic-formatting), verified via the JUnit XML result files
  (`tests="2" failures="0" errors="0"` in both suites), not just console tail.
- Manifest: caught and fixed an XML-comment `--` (double-hyphen) syntax error introduced while
  documenting the change — `processGithubDebugMainManifest` failed with
  `ManifestMerger2$MergeFailureException` before the fix, succeeded after. Not simulated; this was
  a real build failure caught by actually running the build, not by inspection.
- On-device broadcast exercise of `ACTION_DIAGNOSTIC` itself: **performed for real** this
  session, correcting an earlier draft of this section that said it was skipped. Initialized the
  native submodules (`git submodule update --init --recursive`; `llama.cpp`, `sherpa-onnx`, and
  `sherpa_onnx`'s 6 vendored deps — network fetch, ~16 min real compile, not simulated), ran
  `./gradlew --max-workers=2 :app:assembleGithubDebug` (BUILD SUCCESSFUL in 16m27s, real native
  compile of sherpa-onnx/openfst/llama.cpp/ggml sources with real compiler warnings in the log),
  and installed the resulting `Ramblr-1.0.29-github-debug.apk` on the Pixel via `adb install -r`
  (same signer, no uninstall, no data cleared — `versionName=1.0.29` confirmed post-install,
  `WRITE_SECURE_SETTINGS` grant and `enabled_accessibility_services`/Bound-services state
  unchanged before vs. after install).
  - **Test 1 (hook OFF, the default/baseline state)**: `am broadcast -a
    com.trevornk.ramblr.action.DIAGNOSTIC -n com.trevornk.ramblr/.AutomationOffReceiver --user 0`
    → `result=0` (matches `RESULT_HOOK_DISABLED`/`RESULT_HOOK_DISABLED`, confirmed via logcat:
    `"Automation diagnostic broadcast ignored: hook disabled in settings"`) — correctly
    indistinguishable from "nothing here," exactly as designed.
  - Backed up the app's `ramblr.xml` SharedPreferences file (`run-as cat`, saved to
    `/tmp/ramblr_prefs_backup.xml`) before any write, flipped only the
    `automation_off_hook_enabled` boolean to `true` (same file, every other key byte-identical —
    diffed to confirm), pushed it back via `run-as` (base64 round-trip; `adb push` to
    `/sdcard/Download` was blocked by scoped storage/app-sandbox permissions, so the file was
    piped directly through `run-as` instead — no external storage touched).
  - **Test 2 (hook ON)**: repeated the broadcast — still `result=0` and still logged
    `"...ignored: hook disabled in settings"`, twice, ~3s apart. Root cause: Android's
    `SharedPreferencesImpl` caches parsed prefs in memory per-process and does not re-read the
    backing XML file on an external (out-of-process) edit; the running process (pid `5074`,
    unchanged since before this test) never observed the file-level change made via `run-as`
    outside its own `SharedPreferences.Editor` API. **This is a real, honestly-reported
    limitation of the test method chosen** (writing the pref file directly, to avoid any UI
    interaction per instruction, rather than toggling the real Behavior-screen switch in the
    running app) — not a finding about the diagnostic code itself, which the unit tests already
    cover for its pure logic, and not attempted to be papered over: the on-device positive-path
    (`hookEnabled=true`) branch of `handleDiagnostic()` was **not** actually exercised
    end-to-end this session. The negative-path (hook OFF → ignored) branch **was** confirmed live,
    twice, on real Android.
  - Restored `ramblr.xml` to the exact original backed-up bytes via the same `run-as` path,
    diffed byte-for-byte against the pre-test backup (`diff` exit 0, `"RESTORE VERIFIED:
    identical"`) — no residual state left on the device from this test.
  - **Final on-device state, re-verified after the reinstall + both diagnostic tests**: same
    `enabled_accessibility_services` string, `dumpsys accessibility` Bound services still lists
    BOTH `Lawnchair` and `Ramblr (Floating icon)`, Enabled services still lists both components,
    Crashed services still empty, `WRITE_SECURE_SETTINGS` still granted, pid `5074` stable across
    every check, `topResumedActivity=com.trevornk.ramblr/.MainActivity` (Ramblr foreground,
    matching the parent's original setup description) — full match to the confirmed baseline.
  - **Honest residual gap**: the hook-ON / successful-snapshot code path
    (`RamblrDiagnosticSnapshot` construction + `setResultCode(RESULT_DIAGNOSTIC_OK)` +
    `setResultData(formatted)`) remains verified only at the unit level (pure
    `formatDiagnosticSnapshot`, 2/2 tests passing) and by code review, not by a live broadcast
    reply on this device. A future verification pass should toggle the Behavior-screen switch in
    the running app UI (not via `run-as` prefs surgery) and re-run the same `am broadcast` call to
    close this gap.

## 9. Correction to this report's own accuracy (per instruction)

An earlier version of this document's Files-changed section (§ below) described the
`WhisperAccessibilityService.onDestroy()` diff as "comment-only." That was **inaccurate** and is
corrected here: the diff changes `instance = null` (unconditional) to
`if (instance === this) { instance = null }` (conditional on identity) — a **functional** change
to production code, not a comment-only edit. The behavior is a no-op in every case this
investigation actually observed (no run, in 6+118 = 124 recorded cycles across two sessions, ever
had a second live instance to make the two branches diverge), but "no observed behavioral
difference in testing so far" is not the same claim as "comment-only," and this report should not
have conflated them. The guard is being kept as a defensive measure at the parent's direction; no
instruction from the parent was represented as having "asked to keep" the guard for any
attributed reason beyond that direction itself, and no hypothesis in this document should be read
as parent-endorsed unless the parent's own words are quoted.

## Files changed (this branch, cumulative)

- `app/src/main/kotlin/com/trevornk/ramblr/WhisperAccessibilityService.kt` — **functional**
  change: `instance = null` → identity-guarded conditional clear in `onDestroy()` (see §9
  correction above), plus updated comments removing unproven-claim language.
- `app/src/test/kotlin/com/trevornk/ramblr/WhisperAccessibilityServiceLifecycleTest.kt` —
  kdoc/comment fix only, same 2 tests, both still pass.
- `scripts/254_lifecycle_harness.sh` — phase-1 harness (6-cycle, ~3s-spaced), kept as-is.
- `scripts/254_rapid_matrix.sh` — **new this session**: 50-cycle fast + 5-round overlapping
  restore matrix, three-signal snapshots (eas/ServiceRecord/Bound-services), restore-on-exit via
  trap, verified against the real Pixel.
- `app/src/main/kotlin/com/trevornk/ramblr/AutomationOffHook.kt` — **new this session**:
  `RamblrDiagnosticSnapshot`/`formatDiagnosticSnapshot`, pure and unit-tested.
- `app/src/main/kotlin/com/trevornk/ramblr/AutomationOffReceiver.kt` — **new this session**:
  `ACTION_DIAGNOSTIC` read-only action on the existing exported, existing-gated receiver.
- `app/src/test/kotlin/com/trevornk/ramblr/RamblrDiagnosticSnapshotTest.kt` — **new this
  session**: 2 tests for the diagnostic formatter, both pass.
- `app/src/main/AndroidManifest.xml` — **new this session**: added the `DIAGNOSTIC` action string
  to the existing `AutomationOffReceiver` intent-filter (no new component, no new permission).

## Verification run (cumulative)

`./gradlew --max-workers=2 :app:testGithubDebugUnitTest --tests
"com.trevornk.ramblr.WhisperAccessibilityServiceLifecycleTest" --tests
"com.trevornk.ramblr.RamblrDiagnosticSnapshotTest"`
→ **BUILD SUCCESSFUL**, 4/4 tests pass (confirmed via JUnit XML result files, not console output
alone).

Additionally, `./gradlew --max-workers=2 :app:assembleGithubDebug` was run to completion (BUILD
SUCCESSFUL, 16m27s) after `git submodule update --init --recursive` initialized `llama.cpp`,
`sherpa-onnx`, and `sherpa_onnx`'s 6 vendored deps — a real native compile, not skipped, so the
new `ACTION_DIAGNOSTIC` action could be installed and exercised on-device (§8) rather than only
unit-tested. This was necessary and proportionate specifically because §8's diagnostic needed
real broadcast-dispatch verification; it was not done speculatively or for its own sake, and no
native source was modified by this investigation.

## 11. Gap closure (this session, real UI toggle — supersedes §8's residual gap)

The prior session's §8 "Honest residual gap" is now closed. That gap existed because the hook
was flipped via direct `run-as` SharedPreferences-file surgery while the app process was alive;
`SharedPreferencesImpl`'s in-memory cache never observed the out-of-process edit, so the
hook-ON/successful-snapshot path was never actually broadcast-verified, only unit-tested. This
session instead drove the real switch through the real UI (`uiautomator` dump + `input tap` on
`BehaviorActivity`'s "Let automation apps turn Ramblr off" row — coordinates read from the live
accessibility tree, not guessed), which resolves ordering ambiguities like this because
`AutomationOffHookToggle.setEnabled()` runs `SharedPreferences.Editor.apply()` in-process, so the
cache is authoritative for what a subsequent broadcast reads. No `MainActivity`/foreground
occupancy conflict was observed (single adb session, no other input during the run).

**Exact commands and raw output, in order, on the real Pixel 10a (`63141JEA320614`):**

1. Confirmed hook OFF baseline before touching anything:
   `adb shell run-as com.trevornk.ramblr cat .../shared_prefs/ramblr.xml | grep automation_off_hook_enabled`
   → `automation_off_hook_enabled" value="false"`
2. Navigated MainActivity → Settings → Advanced → Behavior via `uiautomator dump` + `input tap`
   (real coordinates from the parsed XML tree, not assumed), tapped the switch row once:
   → prefs file now reads `value="true"` (in-process write, confirmed via `run-as cat` again).
3. **Diagnostic, hook ON, app-UID caller** (`run-as`, ordinary app uid, no root):
   ```
   adb shell run-as com.trevornk.ramblr am broadcast -a com.trevornk.ramblr.action.DIAGNOSTIC \
     -n com.trevornk.ramblr/.AutomationOffReceiver --user 0
   ```
   →
   ```
   Broadcast completed: result=3, data="instance_connected=true;active_component_enabled=true;
   inactive_component_enabled=false;automation_off_hook_enabled=true;write_secure_settings_granted=true"
   ```
   `result=3` matches `RESULT_DIAGNOSTIC_OK`. Confirmed in logcat the same instant:
   `PhoneWhisper: Automation diagnostic: instance_connected=true;...` (pid 5074).
4. **Independent cross-check of every field against separate observation channels**, run
   immediately after step 3, not inferred from the broadcast reply itself:
   - `settings get secure enabled_accessibility_services` →
     `...:com.trevornk.ramblr/com.trevornk.ramblr.WhisperAccessibilityService` (active component
     present ⇒ matches `active_component_enabled=true`).
   - `dumpsys accessibility` "Bound services" block → lists `Ramblr (Floating icon)` bound
     (⇒ matches `instance_connected=true`, since only a connected instance binds).
   - `pm dump com.trevornk.ramblr | grep WRITE_SECURE_SETTINGS` →
     `android.permission.WRITE_SECURE_SETTINGS: granted=true` (⇒ matches
     `write_secure_settings_granted=true`).
   - No field in the returned string was taken on faith; all four checkable fields were
     independently confirmed against a different Android surface than the one the diagnostic
     itself reads (the diagnostic reads `Settings.Secure`/the static instance field directly;
     these checks used `dumpsys`/`pm dump`, different code paths).
5. **Control: same broadcast from a plain `adb shell` (uid 2000/shell, not `run-as`)** —
   included per instruction as a control, not as the primary claim (the receiver has no uid
   check; both `shell` and the app's own uid can already send it since it's an exported
   receiver) → identical `result=3` and identical data string. This is expected, not a new
   finding: it demonstrates the gate is the hook toggle, not caller identity, exactly as
   `AutomationOffHookToggle`'s kdoc already states (§ AutomationOffHook.kt "WHY IT IS OFF BY
   DEFAULT" — any app, no interaction, can already reach this).
6. **Wrong action, app UID**:
   `am broadcast -a com.trevornk.ramblr.action.NOT_A_REAL_ACTION -n .../.AutomationOffReceiver --user 0`
   → `Broadcast completed: result=0` (default, receiver's `when` `else -> return` branch; no log
   line emitted, confirmed by grepping the same logcat window).
7. **Gate OFF snapshot, re-verified live** (not just from the earlier session's cached run):
   tapped the switch back off via the same real UI path, prefs file confirmed
   `value="false"` in-process, then re-sent the diagnostic broadcast (`run-as`, app uid) →
   `result=0`, logcat: `Automation diagnostic broadcast ignored: hook disabled in settings`.
8. **Baseline restore, verified against the pre-test snapshot, not just "looks the same"**:
   `enabled_accessibility_services` byte-identical to the pre-test value, `dumpsys accessibility`
   Bound/Enabled services identical (Lawnchair + Ramblr, both present, both bound), pid `5074`
   unchanged across every step in this section (no process restart), hook toggle back to
   `false` (the documented default), `topResumedActivity=com.trevornk.ramblr/.BehaviorActivity`
   (app still foreground, matching the "no force-stop/uninstall" constraint).

**What this closes vs. what remains genuinely untested**: the hook-ON/`RESULT_DIAGNOSTIC_OK`
reply path is now broadcast-verified end-to-end from a real app-UID caller, with every returned
field independently cross-checked against a different observation channel, not merely unit
formatter coverage. Enabled-status snapshots taken this way (steps 3–7) are **point-in-time
state reads**, not a lifecycle history — they cannot by themselves prove or disprove anything
about *why* a service transitions between enabled/disabled over time (that remains §5/§7's
territory: 6+118 disable/enable cycles, no overlap observed). Still not tested: real
Samsung/One UI hardware (unchanged from §10), and true race-window instrumentation
(unchanged from §7 item 2 — deliberately not added, still a prod-logging tradeoff not made
this session).

## 12. Receiver-level contract tests added (this session)

`AutomationOffReceiverTest.kt` already existed with 8 passing tests for `ACTION_TURN_OFF`'s real
`onReceive()` dispatch (ordered-broadcast result codes, hook on/off, live/no service, wrong
action, uid-arithmetic invariant) — the prior report's "formatter-only" framing for the
*diagnostic* action specifically was accurate (`RamblrDiagnosticSnapshotTest` covers only the
pure `formatDiagnosticSnapshot` function, not receiver dispatch), and is what this session's
tests close. Extended the same file (real `onReceive()`, real ordered-broadcast dispatch via
`sendOrderedBroadcast`/Robolectric shadow looper, not the pure `resolveAutomationOff` helper)
with 7 new tests for `ACTION_DIAGNOSTIC`, plus widened `dispatchOrdered`'s `IntentFilter` to
register both actions (matching the single real `<intent-filter>` in the manifest):

1. `disabled hook reports RESULT_HOOK_DISABLED with no result data for diagnostic`
2. `disabled hook diagnostic result is identical whether or not a service is connected` —
   **privacy-gate test**: proves an outside caller cannot distinguish "hook off, service running"
   from "hook off, service not running" via this broadcast, the property the diagnostic's own
   kdoc claims but the prior test suite never asserted for the diagnostic action specifically.
3. `enabled hook with a live service reports RESULT_DIAGNOSTIC_OK with a connected snapshot` —
   asserts `instance_connected=true` and `automation_off_hook_enabled=true` are present in the
   real result-data string coming out of a real `onReceive()` call, not just that
   `formatDiagnosticSnapshot` can format a hand-built snapshot correctly.
4. `enabled hook with no live service still reports RESULT_DIAGNOSTIC_OK but instance_connected=false`
   — pins that the diagnostic does NOT gate a second time on service-connected (unlike TURN_OFF's
   `RESULT_NOT_RUNNING`), since reporting "not connected" is the whole point of the diagnostic.
5. `a wrong action delivered directly to onReceive is a diagnostic no-op` — asserts state
   (`WhisperAccessibilityService.instance`) is untouched, not just "didn't throw."
6. `ordered TURN_OFF and DIAGNOSTIC deliveries do not cross-contaminate result codes` — sends both
   actions back to back through the same receiver and checks each result is independent.
7. `diagnostic dispatch never writes any Settings_Secure value` — **security/privacy audit as a
   test, not a claim**: snapshots `enabled_accessibility_services`, `accessibility_button_targets`,
   and `accessibility_shortcut_target_service` before and after a successful diagnostic dispatch
   and requires byte-identical values, directly checking the "performs NO writes" claim in
   `handleDiagnostic`'s own kdoc.

**Result**: `./gradlew --max-workers=2 :app:testGithubDebugUnitTest --tests
"com.trevornk.ramblr.AutomationOffReceiverTest" --tests
"com.trevornk.ramblr.RamblrDiagnosticSnapshotTest" --tests
"com.trevornk.ramblr.WhisperAccessibilityServiceLifecycleTest"` → **BUILD SUCCESSFUL in 20s**.
JUnit XML confirms 17/17 tests pass, 0 failures, 0 errors, across the three suites
(`AutomationOffReceiverTest`: 13 tests [6 pre-existing + 7 new], `RamblrDiagnosticSnapshotTest`:
2, `WhisperAccessibilityServiceLifecycleTest`: 2) — counted programmatically from the JUnit XML
result files (`app/build/test-results/testGithubDebugUnitTest/TEST-*.xml`), not from console tail.
No production code defect was found by this test pass, so no fix was made — the receiver's
existing gate/result-code/no-write behavior matched every new assertion on the first run.

## 13. Diagnostic security/privacy audit (this session)

- **Exposure surface**: no new exported component. `ACTION_DIAGNOSTIC` reuses the existing
  exported `AutomationOffReceiver`, itself gated behind `AutomationOffHookToggle` (default
  `false`). An app that has NOT opted into the automation hook gets an identical "nothing here"
  reply (`result=0`, no data) for both `TURN_OFF` and `DIAGNOSTIC` — confirmed live in §11 step 7
  and by the new privacy-gate test (§12 item 2), not just by kdoc claim.
- **Data disclosed when the gate is on**: five booleans, all independently confirmed this session
  to already be readable by any app with zero permissions via other means —
  `Settings.Secure.getString` on the two shortcut/enabled-services keys (no permission required
  for reads on this API level; see `InvocationSecureSettings`'s own kdoc distinguishing
  permission-free reads from `WRITE_SECURE_SETTINGS`-gated writes) and a same-process static
  field. No SharedPreferences contents, no installed-app enumeration, no file paths, no
  credentials/tokens, no user transcript data are read or returned. The new write-audit test
  (§12 item 7) confirms zero `Settings.Secure` writes occur on this path.
- **Caller identity**: NOT checked — any app/uid, including plain shell (§11 step 5's control),
  can send this broadcast once the user has opted the hook on. This is an accepted, documented
  tradeoff carried over unchanged from `AutomationOffHookToggle`'s existing design rationale (its
  kdoc's "WHY IT IS OFF BY DEFAULT" section), not something this diagnostic addition changes or
  worsens — the blast radius of "any app can ask" is a strict subset of the blast radius the
  hook's own existing `TURN_OFF` action already grants once opted in (disabling the entire
  accessibility service outright).
- **Reversibility**: the gate is a plain boolean the user controls only via the real
  `BehaviorActivity` UI switch (no automation-facing "turn the hook itself on" broadcast exists
  in either action, by design — confirmed by reading the receiver's full action set:
  only `TURN_OFF` and `DIAGNOSTIC`, both gated the same way, neither can flip the gate).
- **No defect found.** This audit is a confirmation of existing, already-documented design
  intent via live tests and live broadcasts, not a discovery of a new issue.

## 14. Reporter diagnostic request draft (for parent review)

Subject: One-line diagnostic to help pin down #254 (Ramblr won't stay off)

> Hi — to help track down the accessibility-service restore issue, could you run one command
> from the same MacroDroid/Tasker "Run shell command" action you already use for the off-switch
> (no ADB or root, ordinary automation-tool context), right after a restore attempt looks like it
> didn't take:
>
> `am broadcast -a com.trevornk.ramblr.action.DIAGNOSTIC -n com.trevornk.ramblr/.AutomationOffReceiver --user 0`
>
> This needs "Let automation apps turn Ramblr off" already switched on in Ramblr's Behavior
> settings (same toggle the off-switch broadcast already needs). It's read-only — it doesn't
> change anything — and replies with a short status line your automation tool can capture as the
> broadcast's result data, e.g.:
> `instance_connected=true;active_component_enabled=false;inactive_component_enabled=true;automation_off_hook_enabled=true;write_secure_settings_granted=false`
>
> If you can send us that line from right after a failed restore, it tells us whether the
> service actually disconnected vs. just looks that way, and whether Android has the right vs.
> wrong internal component name enabled — the two most likely explanations we're narrowing
> between. One snapshot right after a bad restore is far more useful than several taken at
> random times.

(Kept intentionally short and single-ask, per finite/no-process-theater instruction — this is a
draft for parent review before any contact with the actual #254 reporter, not sent anywhere by
this session.)

## 15. Finite conclusion (updated, supersedes the numbering/content of the original §10)

**Tested, with real evidence, across three sessions on the real Pixel 10a (`63141JEA320614`)**:
- 6 slow (~3s-spaced) disable/enable cycles (phase 1) — a *cycle* here means one disable +
  one re-enable of the accessibility service.
- 50 fast (no-delay) disable/enable cycles + 5 rounds of concurrently-ordered overlapping writes
  (phase 2) — **118 is a snapshot count, not a cycle count**: it is the number of JSONL state
  reads taken (start + 2 per fast cycle + 3 per overlap round), not the number of
  disable/enable actions performed. The actual cycle count for phase 2 is 50 (fast) + 5×2 = 60
  disable/enable actions; 118 is how many times state was *sampled* around those 60 actions.
- The direct in-app `Settings.Secure` self-heal recovery path (phase 1, §4), confirmed working via
  real UI interaction.
- This session (phase 3, §11–§14): the diagnostic broadcast's hook-ON success path exercised
  end-to-end via a real UI toggle and a real `run-as` app-UID broadcast, with independent
  cross-checks against `dumpsys`/`pm dump`; 7 new receiver-level Robolectric contract tests
  (17/17 total passing, JUnit-XML-confirmed); a security/privacy audit of the diagnostic surface.
- Device restored to its exact original baseline after every phase, re-verified at the end of
  each session (enabled_accessibility_services, Bound/Enabled services, pid, hook toggle state).

**Not reproduced, in any session, at any measured cadence down to ~1s**: two coexisting live
`ServiceRecord`s or Bound-services entries for `com.trevornk.ramblr` for the same component. No
coalesced-vs-genuine-reconnect ambiguity was observed either — every disable/enable action in
phase 2 resolved to a clean, correctly-reflected binary state in its surrounding snapshots, never
a partial/stuck one. **These enabled/disabled snapshots are point-in-time state reads, not a
lifecycle history** — they show what state the OS reported at each sample instant, not a trace of
every intermediate transition between samples, and cannot alone prove or rule out a sub-second
callback-ordering race (see §7's own timing-honesty caveat, unchanged).

**Still not tested, and not claimed to be tested**: (a) true race-window-resolution instrumentation
(sub-second/millisecond in-process identity-hash logging around `onServiceConnected`/`onDestroy`,
deliberately not added to avoid shipping throwaway prod logging), (b) anything on the reporter's
actual Samsung S24/One UI hardware, where OEM-specific background-kill/accessibility-list-
revalidation behavior remains an open, unruled-out variable. Item (c) from the original §10 (the
diagnostic's hook-ON path being unit-only) is **no longer an open item** — see §11.

**Production code status**: the identity guard in `onDestroy()` is a real, functional, but
unproven-as-fixing-anything change. It is being kept as a defensive no-op pending future
evidence; this document does not represent the parent as having endorsed any particular
justification for keeping it beyond the direction to keep it (see §9's correction), and does not
represent it as resolving #254.

