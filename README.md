<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Ramblr Logo">
</p>

# Ramblr

Push-to-talk dictation for Android.

Ramblr lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.\

It supports:

- **Local on-device transcription** with sherpa-onnx
- **Cloud transcription** with OpenAI Whisper
- **Optional cleanup** with OpenAI to fix punctuation and grammar

Ramblr is a private fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper).


## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest APK from [GitHub Releases](https://github.com/trevornk/ramblr/releases).

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/trevornk/ramblr.git && cd ramblr
make build
```

The build automatically downloads the sherpa-onnx native libs (`libsherpa-onnx-jni.so`,
`libonnxruntime.so`) it needs for local transcription — no manual step required.

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is transcribed locally or in the cloud
5. The text is inserted into the focused text field
6. If insertion fails, the text is copied to the clipboard

## Setup

### First-time setup

1. Open **Ramblr**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: paste your OpenAI API key

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Ramblr uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

Ramblr supports two modes:

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to OpenAI's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to OpenAI's chat API

I don't run a backend for this app. In cloud mode, requests go straight from your phone to OpenAI using your own API key.

Cleanup can instead be pointed at any OpenAI-compatible chat completions API (e.g. a self-hosted
router on your own LAN) by setting a custom base URL and model name in **Settings → Cleanup API
base URL / Cleanup model name**. In that mode, transcript text is sent to whatever host you
configure — e.g. `http://192.168.1.50:8000/v1` — not to OpenAI. The API key field is reused as a
Bearer token for the custom endpoint too. See [PRIVACY.md](PRIVACY.md) for details.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.kafkasl.phonewhisper/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| Parakeet 110M | 100 MB | Best default |
| Whisper Base | 199 MB | Solid baseline |
| Parakeet 0.6B | 465 MB | Best quality |
| Moonshine Tiny | 103 MB | Fastest |

The app downloads and extracts models directly from the sherpa-onnx release archives.

## Post-processing

When cloud cleanup is enabled, the raw transcript is sent to OpenAI's chat API with a system
prompt before being inserted. You can pick which prompt to use from **Settings → Edit current
prompt** presets, or write your own:

| Preset | Behavior |
|---|---|
| **Dev cleanup** (default) | Fixes spelling, grammar, and punctuation, corrects known project/technical names, and preserves the original sentence structure 1:1. Best for coding, CLI commands, and short technical notes. |
| **Simple cleanup** | Minimal edit: punctuation, capitalization, and obvious speech-to-text errors only. |
| **Structured rewrite** | Fluid-1/Typeless-style rewrite for rambling dictation: strips filler words and false starts ("um", "like", "you know"), collapses self-corrections ("wait no, actually...") down to the final intended meaning, and reorganizes long rambling monologue into paragraphs or numbered/bulleted lists when the speaker is clearly enumerating items or steps. It still corrects the same technical/project-name list as Dev cleanup, and never adds facts, opinions, or answers that weren't in the source. Short one-line notes are left as a single sentence rather than restructured. |
| **Custom** | Any prompt you type yourself in the "Edit current prompt" dialog. |

All three built-in presets are defined as prompt constants in `PostProcessor.kt`
(`DEV_PROMPT`, `SIMPLE_PROMPT`, `STRUCTURED_PROMPT`) so you can read or fork them directly.

**Settings → Personal vocabulary** lets you edit the list of project names and jargon (one per
line) that cleanup should recognize instead of mis-hearing, seeded with Ramblr's own
defaults on first run. Built-in prompts interpolate this list at send time via a `{{vocabulary}}`
placeholder; a fully custom prompt can opt in to the same behavior by including that placeholder
itself (see #26).

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

### Prompt eval harness

`PostProcessorTest.kt` only checks JSON parsing, not output *quality*. To compare cleanup
prompts (`SIMPLE_PROMPT`, `DEV_PROMPT`, `STRUCTURED_PROMPT`, or future variants) side by side,
there's a manual eval harness:

- Sample transcripts live in `app/src/test/resources/eval_samples/` — ~20 synthetic but
  realistic dictation samples covering rambling brainstorms, self-corrected sentences,
  technical jargon, spoken lists, and quick notes.
- The harness itself is `app/src/test/kotlin/com/kafkasl/phonewhisper/tools/EvalHarness.kt`, a
  standalone `main()` — **not** a JUnit test. It compiles as part of `make test`'s Kotlin
  compilation (so it's checked for compile errors), but JUnit never discovers or runs it, so it
  has no effect on `make test`, `make build`, or CI.

**This tool calls the real OpenAI API and spends real credits.** Only run it manually, with your
own key:

```bash
export OPENAI_API_KEY=sk-...   # your own key — never commit this, no .env is read by the tool
./gradlew runEvalHarness                                              # compares SIMPLE_PROMPT vs DEV_PROMPT
./gradlew runEvalHarness --args="SIMPLE_PROMPT,DEV_PROMPT,STRUCTURED_PROMPT"   # explicit prompt list
```

Optional overrides:

```bash
OPENAI_EVAL_MODEL=gpt-4o ./gradlew runEvalHarness   # defaults to gpt-4o-mini, matching PostProcessor
./gradlew runEvalHarness --args="DEV_PROMPT eval-reports/dev-only.md"  # custom output path
```

Each run writes a markdown report (default `eval-reports/<prompts>.md`) with the raw transcript
and the cleaned-up output from each prompt for every sample, for manual side-by-side review.
Review the report yourself before trusting a prompt change — the harness doesn't score or grade
output automatically. `eval-reports/` is gitignored so local runs don't clutter the repo; if you
want to share or archive a specific report, `git add -f` it.

To add a new prompt variant to the comparison, add it to the `PROMPT_REGISTRY` map at the top of
`EvalHarness.kt`.

## App compatibility

Ramblr works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Ramblr falls back to copying the transcript to the clipboard, and the
feedback bubble stays up longer and is tappable to re-copy so a failed insertion is hard to miss (see below).

If the very first scan for an insertable field comes up empty — which can happen for a moment right after the
overlay button is tapped, since the tap itself briefly steals focus — Ramblr waits ~200ms and scans once
more before giving up and falling back to the clipboard. This is a narrow fix for that specific transient race,
not a general compatibility improvement.

There is no compatibility table of tested apps (e.g. Gmail, Slack, Signal, Chrome, Discord, a Compose-based
messaging app, WebView-based inputs) in this README. Building one requires exercising each app on a real device
and recording which strategy succeeds, which hasn't been done — see [#5](https://github.com/trevornk/ramblr/issues/5)
for that as tracked follow-up work. Don't treat the absence of an app from this doc as either "supported" or
"unsupported."

Ramblr intentionally does not implement a full IME (replacement keyboard). It only acts once, after an
explicit tap on the overlay button, and never intercepts normal typing — becoming a keyboard is a different,
much larger feature and is out of scope.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Ramblr in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Cloud mode requires your own OpenAI API key

## Attribution

Ramblr is a private fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper), which this project is built on top of.

## License

Personal project. Do whatever you want with it.
