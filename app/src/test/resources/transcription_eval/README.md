# Transcription eval fixture corpus (#129)

This directory defines the fixture corpus for the **manual** Gemini transcription benchmark
(`app/src/test/kotlin/com/trevornk/ramblr/tools/GeminiTranscriptionBenchmark.kt`). It is
deliberately shipped **empty**: `manifest.json` contains a placeholder, zero-entry `fixtures`
array, and **no audio files are checked into this repository**.

Nothing in this directory runs during `make test`, `./gradlew testGithubDebugUnitTest`, or CI.
The benchmark is a `main()` entry point, never a JUnit test.

## Why no audio is checked in

Recorded human speech is **biometric data**. A voice recording identifies its speaker, cannot be
revoked once published, and is subject to biometric-privacy law in several jurisdictions
(BIPA, GDPR Art. 9 special-category data, and others). Committing a colleague's or a user's voice
to a public git repository is irreversible. So: no recorded human speech is committed here, ever.

## Fixture contract

Each entry in `manifest.json`'s `fixtures` array is an object with these fields — all required
except `sha256`:

| Field           | Type      | Meaning |
|-----------------|-----------|---------|
| `id`            | string    | Unique, stable, non-blank identifier. Used as the report row key. |
| `audioPath`     | string    | Path to the `.wav`, relative to this directory. The file must exist. |
| `referenceText` | string    | Ground-truth transcript. Must be non-blank. |
| `durationMs`    | number    | Clip duration in milliseconds. Must be positive. |
| `language`      | string    | BCP-47 tag, e.g. `en-US`. Non-blank. |
| `scenarios`     | string[]  | One or more tags from the closed vocabulary below. |
| `source`        | string    | Provenance: exactly where this audio came from. Non-blank. |
| `consent`       | string    | Consent record or licence under which the audio may be used and redistributed. Non-blank. |
| `sha256`        | string?   | Optional lowercase hex SHA-256 of the audio file. Verified when present. |

Known scenario categories (`TranscriptionEvalManifest.KNOWN_SCENARIOS`) — an unknown tag is a
validation error, not a warning:

`short_command`, `long_dictation`, `technical_jargon`, `numbers_and_punctuation`, `proper_nouns`,
`self_correction`, `noisy_environment`, `accented_speech`, `fast_speech`, `quiet_speech`,
`code_switching`, `silence_or_nonspeech`.

The manifest is rejected outright (the run does not start) on: duplicate ids, a blank id, a blank
reference, a missing audio file, an unknown scenario tag, an empty scenario list, a non-positive
duration, blank provenance/consent, a blank language tag, or a `sha256` that doesn't match the
file on disk.

## Required audio format

`GeminiTranscriberClient.transcribe` consumes **raw PCM** and applies its own WAV header, so the
benchmark unwraps each fixture with `WavPcm` before calling it. Fixtures must therefore be:

- RIFF/WAVE container
- uncompressed PCM (`audioFormat == 1`)
- **16 000 Hz** sample rate
- **mono** (1 channel)
- **signed 16-bit little-endian** samples

Anything else is rejected with a clear error rather than resampled — a silently resampled fixture
would make the benchmark measure the resampler, not the model. Extra chunks (`LIST`, `fact`,
`junk`) between `fmt ` and `data` are fine; the parser walks the chunk headers rather than
assuming the canonical 44-byte layout.

Convert an existing file with:

```bash
ffmpeg -i input.m4a -ac 1 -ar 16000 -sample_fmt s16 -c:a pcm_s16le clip-a.wav
```

## Ground-truth authoring rule

**Write `referenceText` BEFORE you look at any model output.** Transcribe the clip by ear (or take
the exact script that was read aloud / fed to TTS), commit it, and only then run the benchmark.

Authoring or "correcting" a reference after seeing a model's transcript silently converts the
benchmark into a measurement of how much you agree with that model. If a reference turns out to be
genuinely wrong, fix it in its own commit that states what was wrong and why, and treat every
previously recorded score for that clip as void.

## Acceptable fixture sources

In descending order of preference:

1. **Synthetic TTS** (Piper, espeak-ng, macOS `say`) of scripts you wrote. No human speaker, no
   biometric exposure, permissive licence. Note the exact engine and voice in `source`.
2. **Your own voice**, recorded by you, with `consent` stating you are the speaker and consent to
   the use. Still not committed to the repo — keep it local (see below).
3. **Openly licensed speech corpora** (Common Voice CC0, LibriSpeech CC BY 4.0). Record the corpus,
   clip id, and licence in `source`/`consent`.

Never: recordings of other people without written consent, anything captured from a real user of
the app, or anything with third-party content you cannot relicense.

## Keeping local fixtures out of git

Keep local audio in this directory and do **not** `git add` it. Verify before every commit:

```bash
git status --short app/src/test/resources/transcription_eval/
```

If you maintain a private corpus, keep it outside the repo entirely and point the benchmark at it
with `TRANSCRIPTION_EVAL_DIR=/path/to/corpus`.

## Scoring and normalization contract

Implemented by `TranscriptionMetrics`. Every run reports both comparison modes:

- **STRICT** — NFKC normalization, apostrophe/dash variant folding, whitespace collapsing, and
  nothing else. Case and punctuation count as errors. Answers "did the model produce the text the
  user would have typed?"
- **NORMALIZED** — everything STRICT does, plus lowercasing and punctuation stripping
  (contraction apostrophes preserved, so `don't` stays one token). The conventional ASR mode.

Metrics:

- **WER** = word edit distance / reference word count, with substitutions, deletions, and
  insertions reported separately. Insertions can push the rate above 1.0; that is intended.
- **CER** = the same at character level.
- **Exact-match rates** under both modes.
- **Empty-reference contract**: an empty reference with an empty hypothesis scores 0.0; an empty
  reference with a non-empty hypothesis scores 1.0 (the ratio is undefined; 1.0 is the pessimistic
  reading). The validator rejects blank references anyway, so this only guards degenerate input.

Corpus aggregation is **micro**: summed edit counts divided by summed reference lengths. It is not
an unweighted mean of per-clip rates, which would let a three-word clip outweigh a two-minute one
and yield a number that corresponds to no real error count. The macro mean is reported alongside
purely so a skewed corpus is visible at a glance — never as the headline figure.

Latency percentiles use **nearest rank** (`ceil(p * n)` on the sorted sample), so p50/p95 are
always latencies that actually occurred rather than interpolated values that never happened.
