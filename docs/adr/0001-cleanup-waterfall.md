# ADR-0001: Multi-provider cleanup waterfall (OmniRoute + direct OpenAI + direct Anthropic)

Date: 2026-07-03
Status: Accepted

## Context

Ramblr's optional post-processing "cleanup" step (`PostProcessor.kt`) rewrites the raw
transcript through an LLM to strip filler words, fix self-corrections, and apply
formatting. As of issue #4, this call already targets an OpenAI-compatible
`chat/completions` endpoint with a configurable `base_url` + `model` + API key, defaulting
to `api.openai.com` / `gpt-4o-mini`. That single hardcoded endpoint is a reliability and
cost single point of failure: if the configured endpoint is unreachable or the key is
invalid, cleanup fails and the raw transcript is injected with a toast — safe, but not
resilient, and not using Trevor's existing subscriptions where cheaper/faster options are
available.

Trevor self-hosts **OmniRoute** (a private hostname, docker-host, behind
Nginx Proxy Manager with a valid public TLS cert) — a self-hosted OpenAI-compatible AI
gateway that fronts his Claude Max, ChatGPT/Codex, and Gemini access behind a single flat
consumer API key. OmniRoute does its own OAuth to upstream providers server-side; Ramblr
never needs to implement or hold provider OAuth credentials directly. **OmniRoute is
reachable only on Trevor's home LAN or over his VPN — confirmed NOT publicly reachable**,
despite having a public-looking hostname and valid TLS.

Verified live against the real OmniRoute instance (2026-07-03):
- Claude models work: `claude/claude-haiku-4-5-20251001` returned a real completion in ~79ms.
- OpenAI/Codex models work: `cx/gpt-5.4-mini` returned a real completion in ~982ms.
- Gemini models are listed in `/v1/models` but **not actually connected** — a real request
  returns `"No active credentials for provider: gemini"`. This is a known bug on Trevor's
  OmniRoute instance, expected to be fixed independently of this feature. The Gemini
  waterfall step is built generically against the same OpenAI-compatible shape so it will
  start working the moment the bug is fixed, but it is **not tested live** as part of this
  work.
- OmniRoute responses are Server-Sent-Event streamed by default; the waterfall client
  requests `"stream": false` to get the same flat `{choices:[{message:{content}}]}` shape
  `PostProcessor.parseResponse` already parses. This does not measurably slow down a short
  cleanup completion (a few sentences) and avoids a second response-parsing code path.

Trevor explicitly rejected:
- **OpenRouter** — a third-party paid proxy; not wanted as part of this design.
- **App-side OAuth reimplementation** — re-implementing Anthropic's/OpenAI's internal CLI
  OAuth flows inside a third-party Android app is fragile (undocumented, ToS-risky,
  breaks silently on upstream changes) and unnecessary, since OmniRoute already solves
  "use my subscription instead of pay-per-token" server-side.

An independent architecture review (Claude Opus, 2026-07-03) validated the overall shape
and flagged two real correctness issues that are incorporated into this ADR:
1. A flat sequential waterfall wastes 3x the connect-timeout budget when Trevor is away
   from home, because all 3 OmniRoute sub-steps fail identically (host unreachable). Fixed
   by treating OmniRoute's steps as one **host group**: one failed connection to
   OmniRoute disqualifies all of its steps for that call, advancing straight to the next
   host group.
2. (Originally flagged as a cleartext-HTTP risk; resolved — OmniRoute is served over valid
   HTTPS via NPM, so no `network-security-config` cleartext exception is needed.)

## Decision

Replace the single hardcoded cleanup endpoint with a **user-configurable, host-grouped,
ordered waterfall** of cleanup providers. On cleanup, try steps in order; on any failure
(network error, timeout, or non-2xx HTTP status of any kind — 401/403/429/5xx all count),
advance to the next step. If every step fails, fall back to the existing behavior: inject
raw text with a "cleanup failed" toast.

### Credentials (3, not 5)

Only three encrypted secrets are needed, extending the existing `ApiKeyStore`
(`EncryptedSharedPreferences`) pattern from issue #8:
- **OmniRoute key** — one consumer Bearer token, reused across all OmniRoute sub-steps
  (only the model string differs per sub-step; base_url is fixed at
  a private URL not committed to this repo — see `OmniRoute.kt`).
- **OpenAI key** — for the direct-OpenAI fallback step. Reuses transcription's OpenAI key
  field is NOT assumed; cleanup gets its own explicit field since the user may want a
  different key/quota for cleanup vs. cloud transcription.
- **Anthropic key** — for the direct-Anthropic fallback step (new).

### Waterfall shape (host-grouped, not flat)

```
Group 1 — OmniRoute (one credential, one host, private URL — see OmniRoute.kt)
  1a. claude/claude-sonnet-4-6      (or whatever model the user configures)
  1b. cx/gpt-5.5
  1c. gemini/gemini-2.5-flash        (built generically; untested live — known upstream bug)
Group 2 — direct OpenAI (existing OpenAI-compatible client, reused code path)
  2a. gpt-4o-mini (or user-configured model)
Group 3 — direct Anthropic (NEW native client — different wire format, cannot reuse
                             the OpenAI-compatible path)
  3a. claude-haiku-4-5 (or user-configured model)
```

Each step is `{group, credentialSlot, model, baseUrlOverride?}`. Steps within a group
share a credential and (for OmniRoute) a base URL; only the model string varies per step
within a group.

**Default out-of-box waterfall for a fresh install** (nothing configured): a single step
using today's existing OpenAI cloud key/base_url/model fields — i.e. zero behavior change
for anyone who hasn't touched this feature. The 5-step example above is what a user
(Trevor) configures manually in Settings, not a mandatory default; a smart pre-populated
default (if/when offered) should be 2-3 steps, not the full 5.

### Anthropic native client (new)

Direct Anthropic's real API (`/v1/messages`, `x-api-key` header, content-block response
shape) is **not** OpenAI-compatible and cannot reuse `PostProcessor`'s existing OpenAI-
compatible request/response code. OmniRoute's Claude routing already does this
translation server-side — that's *why* Claude appears in an OpenAI-compatible shape
through OmniRoute — but a genuine **direct** Anthropic fallback (Group 3) needs its own
small isolated client (`AnthropicCleanupProvider` or similar), not bolted onto the
existing OpenAI-compatible path.

### Executor behavior

- **No retries.** This is a foreground call blocking the user from pasting text they're
  actively waiting on. Advance immediately on any failure, including 429 (do not honor
  `Retry-After` — cleanup is optional polish, not a transaction worth waiting on).
- **Host-grouped fast-fail.** One failed connection to a group's host disqualifies the
  rest of that group's steps for this call; move straight to the next group.
- **Cursor-based last-known-good, reset on network change.** Remember the index of the
  last step that succeeded; start the next call there instead of always probing from step
  1. Reset the cursor to step 1 on Android connectivity/network change (SSID change, VPN
  up/down) so arriving home or leaving home self-heals automatically instead of requiring
  a timer. Also expire the cursor after a few minutes of idle as a belt-and-suspenders
  self-heal if a network-change event is missed.
- **Timeouts:** ~1.5s connect timeout per step (the critical lever for making the
  away-from-home case cheap), ~6-8s read timeout per step, ~12-15s hard cap for the whole
  waterfall before falling back to raw injection regardless of remaining steps.
- **One global cleanup prompt.** Per-step prompt/temperature overrides are explicitly out
  of scope for v1 (YAGNI) — different steps use different models, not different
  instructions.

### UI

Stays in plain Android Views — **no Compose or design-framework adoption** for this one
screen. This preserves the app-wide "plain Views, no framework" convention Trevor set
earlier in the project, and this is a screen configured once, not used repeatedly.
Targeted, cheap niceties earn their place here specifically because the feature is an
otherwise-invisible ordered process:
- Reorder via `ItemTouchHelper` drag handles or simple up/down arrows (either is fine for
  3-5 items; pick one, don't gold-plate).
- A colored status dot per step (grey = untested, green = last succeeded, red = last
  failed) — the single highest-value UI touch, doubles as a lightweight debug view.
- A "Test" button per credential that fires one trivial request and lights the dot —
  catches a typo'd model string (e.g. `cx/gpt-5.5` vs a nonexistent variant) before it
  silently falls through to a paid fallback and costs money.
- Advanced fields (base_url override, etc.) hidden behind an "Advanced" expand; default
  rows show credential + model only.
- A subtle "served by paid fallback" badge surfaced in dictation history (issue #25) when
  the debug/visibility toggle is on, since OmniRoute is free (subscriptions) and Groups
  2-3 are pay-per-token — this is the one cost-visibility feature worth building, gated
  behind the existing off-by-default debug toggle.

### Explicitly out of scope for v1

OAuth (correctly cut earlier), per-step retry/backoff config, token/cost accounting
beyond the single "paid fallback" badge, secrets rotation UI (editing the encrypted field
*is* rotation), per-step prompt/temperature overrides, live latency graphs, animated
transitions, a model picker populated from `/v1/models` (nice-if-cheap stretch, not v1 —
the Test button covers the same typo risk more cheaply).

## Consequences

- Cleanup gains real resilience without adding OAuth complexity or a third-party proxy
  dependency.
- Home/VPN sessions get near-zero cleanup cost and low latency (OmniRoute, subscription
  billing). Away-from-home sessions cost real API tokens on Groups 2-3 but stay fast
  thanks to host-grouped fast-fail and cursor caching.
- New `AnthropicCleanupProvider` is a maintenance surface distinct from the existing
  OpenAI-compatible path; if Anthropic's API shape changes, only this client needs
  updating.
- If OmniRoute's public reachability policy ever changes (e.g. Trevor opens it to the
  internet), no app changes are needed — the executor doesn't assume LAN-only, it just
  treats connection failure as connection failure regardless of cause.

## Follow-ups

- [ ] Re-test the Gemini-via-OmniRoute step live once Trevor confirms the upstream
      OmniRoute Gemini credential bug is fixed.
- [ ] Consider a `/v1/models`-backed model picker as a stretch goal once the flat-text
      field + Test button pattern has been used in practice.

## Addendum (2026-07-06): repo made public

`OmniRoute.BASE_URL` is no longer a hardcoded string constant in source — it's now sourced from
`BuildConfig.OMNIROUTE_BASE_URL`, populated at build time from an `OMNIROUTE_BASE_URL` entry in
`local.properties` (gitignored, machine-local; see `OmniRoute.kt` and `app/build.gradle.kts`).
Rationale: this repo went public, and even a non-secret personal hostname shouldn't sit in
source anyone can clone. Leaving the entry unset makes `OmniRoute.isConfigured` false, which
hides "Add OmniRoute provider" from `CloudProviderActivity`'s picker entirely — the feature is
dormant, not deleted, for anyone who hasn't configured their own gateway. No behavior changes
described elsewhere in this ADR are affected; only where the URL string comes from changed.
