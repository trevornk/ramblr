# llama_cleanup native module (#37)

Native side of the LOCAL_LLM cleanup waterfall step. **Not currently compiled or packaged by the
Gradle build** -- see "Status" below. This directory exists so the actual native-build wiring is
a config/task change on top of real, cited source, not a from-scratch write.

## What's here

| File | Status |
| --- | --- |
| `LLMInference.h` | Vendored **verbatim** from [shubham0204/SmolChat-Android](https://github.com/shubham0204/SmolChat-Android)'s `smollm` module, commit `8408e1ced09e` (Apache-2.0). Only a citation header was added; `diff` against the fetched upstream file shows zero logic changes. |
| `LLMInference.cpp` | Same -- vendored verbatim, same commit, citation header only. |
| `llama_cleanup_jni.cpp` | **Adapted** from `smollm.cpp` (same commit): JNI symbol names renamed to this app's package (`Java_com_kafkasl_phonewhisper_LlamaCppInference_*`), and trimmed from 8 exported functions to the 6 `LlamaCppInference.kt` actually declares (dropped `getResponseGenerationSpeed`/`getContextSizeUsed`/`benchModel` -- perf/bench accessors unused by a single-shot cleanup call). See the file's own header comment for the full adaptation diff summary. |
| `CMakeLists.txt` | **Adapted** (heavily simplified) from `smollm`'s `CMakeLists.txt`: one baseline arm64-v8a build target instead of upstream's 7-way CPU-feature-variant matrix (Ramblr already restricts `ndk.abiFilters` to `arm64-v8a` only), and drops the separate `ggufreader` library (Ramblr hardcodes context size for its one curated model instead of parsing GGUF metadata at runtime). |
| `../../kotlin/.../LlamaCppInference.kt` | **Adapted** from `SmolLM.kt` (same commit): trimmed to a single-shot (no chat history, no streaming `Flow`, no CPU-variant library selection) completion API. |

All source was fetched live from the real upstream repository (`curl`/GitHub API against
`raw.githubusercontent.com` and `api.github.com`) during this work, not reproduced from memory.

## Status: NOT wired into the Gradle build

Confirmed during this work: this development sandbox has the Android SDK
(`$ANDROID_HOME/Library/Android/sdk`) but **no NDK** (`$ANDROID_HOME/ndk` doesn't exist, and
`ndk-build` isn't on `PATH`). Compiling any of the C++ above -- whether a full from-source
llama.cpp build or just this small JNI shim -- requires the NDK's CMake/Clang toolchain, which
this environment cannot provide. Per the #37 scope-down instructions, this is documented as a
known gap rather than forced through a build that can't actually be exercised or verified here.

Consequences of NOT wiring this up:
- `app/build.gradle.kts` has **no** `externalNativeBuild`/CMake block referencing this directory,
  so `make build`/`assembleDebug` never attempts to compile it (and can't fail because of it).
- No `libllama-cleanup-jni.so` is ever produced or packaged into the APK.
- `LlamaCppInference`'s `System.loadLibrary("llama-cleanup-jni")` will throw
  `UnsatisfiedLinkError` on a real device today. `RealLocalInferenceEngine` (see
  `CleanupWaterfallExecutor.kt`) catches this and reports it as an ordinary
  `LocalInferenceResult.Failure`, so a LOCAL_LLM waterfall step fails cleanly (falls through to
  the next step, or to raw-text injection) instead of crashing -- but **this has not been
  exercised on a real device**, only reasoned about from the code.

## Concrete next step (two viable options, in order of effort)

1. **Compile just this JNI shim against llama.cpp's own official prebuilt Android release**
   (lower effort, recommended first): llama.cpp's GitHub Releases publish a real
   `llama-b<N>-bin-android-arm64.tar.gz` asset (confirmed present, e.g. release `b9867`,
   containing `libllama.so`/`libggml*.so`/`libggml-base.so`). Fetch that tarball plus llama.cpp's
   public headers (`include/llama.h`, `common/common.h`, `common/chat.h`) for the *same* pinned
   release tag, add a real `externalNativeBuild`/CMake block to `app/build.gradle.kts` that links
   `LLMInference.cpp`/`llama_cleanup_jni.cpp` against those prebuilt `.so`s (no llama.cpp source
   compilation needed), and add a `fetchLlamaCppNativeLibs` Gradle task mirroring
   `fetchSherpaOnnxNativeLibs`'s shape to provision them. Requires a machine with the Android NDK
   installed to write and verify the CMake linking step once.
2. **Full from-source build** (matches SmolChat-Android's own approach exactly): add llama.cpp as
   a pinned git submodule (as SmolChat-Android does), wire `externalNativeBuild` to this
   `CMakeLists.txt` as-is (it already expects `../../../../../llama.cpp`), and let Gradle's
   `externalNativeBuild` invoke CMake/NDK to compile llama.cpp + this shim together. Higher
   effort and slower CI/build times, but the most battle-tested path since it's exactly what the
   real, shipping SmolChat-Android app does.

Either way, whoever picks this up needs a machine with the Android NDK (this sandbox doesn't have
one) to actually compile and, critically, **run on a real device** before trusting it -- see #36
for why "compiles and passes JVM-only tests" is not sufficient proof for native/JNI surfaces.

## Related model

The one curated GGUF model this binding is meant to load is `LOCAL_CLEANUP_MODEL` in
`ModelDownloader.kt` (Qwen2.5-0.5B-Instruct, Q4_K_M) -- downloaded via the existing
`ModelDownloader`/`ModelDownloadWorker` pattern, independent of this native-build gap.
