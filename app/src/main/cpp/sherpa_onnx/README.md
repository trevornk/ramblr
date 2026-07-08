# sherpa_onnx native module (F-Droid prep, #36)

Builds `libsherpa-onnx-jni.so` — the speech-to-text native library the vendored
`com.k2fsa.sherpa.onnx.*` Kotlin bindings load via `System.loadLibrary("sherpa-onnx-jni")` — **from
source**, replacing the old `fetchSherpaOnnxNativeLibs` Gradle task that downloaded a prebuilt
release AAR from GitHub Releases.

## Why

F-Droid does not accept prebuilt binaries from untrusted sources. GitHub Releases doesn't qualify
(Maven Central, Google Maven and JitPack do). The two native `.so` files the app previously pulled
out of `sherpa-onnx-1.13.3.aar` were exactly such binaries. This module removes that blocker:

| Native lib | Before | Now |
| --- | --- | --- |
| `libsherpa-onnx-jni.so` | extracted from the GitHub Releases AAR | compiled from the `k2-fsa/sherpa-onnx` git submodule at tag `v1.13.3` |
| `libonnxruntime.so` | extracted from the same AAR | official Maven Central artifact `com.microsoft.onnxruntime:onnxruntime-android:1.24.3` |

Mirrors the from-source llama.cpp pattern in `../llama_cleanup/` (#37).

## How it's wired

- **Submodule:** `k2-fsa/sherpa-onnx` at the repo root (a sibling of `app/` and `llama.cpp/`; see
  `.gitmodules`), pinned to tag `v1.13.3` — the same tag the vendored Kotlin bindings come from, so
  the JNI symbols match by construction.
- **`CMakeLists.txt` (this dir):** `add_subdirectory()`s the submodule with a minimal feature set
  (`SHERPA_ONNX_ENABLE_TTS=OFF`, `SPEAKER_DIARIZATION=OFF`, `WEBSOCKET=OFF`, `PORTAUDIO=OFF`,
  `C_API=OFF`, `BINARY=OFF`) — the app only uses `OfflineRecognizer` / `OnlineRecognizer` / `Vad`.
  `BUILD_SHARED_LIBS=OFF` statically archives all of `sherpa-onnx-core` + `kaldi-native-fbank` +
  `kaldi-decoder` + `simple-sentencepiece` into a single self-contained `libsherpa-onnx-jni.so`,
  matching the old 2-file (`jni.so` + `onnxruntime.so`) layout. `SHERPA_ONNX_ENABLE_JNI=ON` makes
  upstream build its `sherpa-onnx-jni` target.
- **onnxruntime:** upstream's `cmake/onnxruntime.cmake` reads the *environment* variables
  `SHERPA_ONNXRUNTIME_INCLUDE_DIR` / `SHERPA_ONNXRUNTIME_LIB_DIR` to use a provided onnxruntime
  instead of downloading a prebuilt one. `app/build.gradle.kts`'s `extractOnnxRuntimeNative` task
  unzips the Maven AAR's `headers/` and `jni/arm64-v8a/libonnxruntime.so` into
  `build/onnxruntime-1.24.3/`, passes those paths as `-D` CMake args, and this `CMakeLists.txt`
  bridges them into the process environment before `add_subdirectory()`.
- **Combined build:** AGP allows only one `externalNativeBuild.cmake.path` per module, so the root
  `../CMakeLists.txt` `add_subdirectory()`s both `llama_cleanup` and `sherpa_onnx`; `build.gradle.kts`
  points AGP at that root.
- **16KB pages (#44):** upstream already appends `-Wl,-z,max-page-size=16384` for Android; this
  `CMakeLists.txt` sets it explicitly (plus `common-page-size`) in-scope too.

## Bumping the version

1. `cd sherpa-onnx && git checkout v<new>` and commit the submodule bump.
2. Re-vendor `app/src/main/kotlin/com/k2fsa/sherpa/onnx/*.kt` from the matching upstream tag and
   update `VENDORED.md`.
3. If sherpa-onnx changed its expected onnxruntime version, bump `onnxRuntimeVersion` in
   `app/build.gradle.kts` and re-run `./gradlew --write-verification-metadata sha256 help` to refresh
   `gradle/verification-metadata.xml`.

## Remaining F-Droid consideration

sherpa-onnx's CMake still `FetchContent`-downloads four **source** deps at configure time
(`kaldi-native-fbank`, `kaldi-decoder`, `simple-sentencepiece`, `nlohmann/json`). These are source
builds, not prebuilt binaries, but F-Droid's build server has no network during the build step, so a
fully-offline F-Droid build would additionally need these vendored/submoduled (or pre-seeded into the
paths their `.cmake` files probe, e.g. `$HOME/Downloads/…` / `/tmp/…`). Tracked as follow-up to this
onnxruntime + JNI from-source change.
