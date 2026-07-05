# llama_cleanup native module (#37)

Native side of the LOCAL_LLM cleanup waterfall step. **Now compiled and packaged by the Gradle
build** via `externalNativeBuild`/CMake, built from source against a pinned `llama.cpp` git
submodule (option 2 from this file's prior "next step" list, matching SmolChat-Android's own
approach).

## What's here

| File | Status |
| --- | --- |
| `LLMInference.h` | Vendored from [shubham0204/SmolChat-Android](https://github.com/shubham0204/SmolChat-Android)'s `smollm` module, commit `8408e1ced09e` (Apache-2.0), **plus targeted #87 fixes**: `= nullptr` default member initializers for the raw pointers, and a `_chatTemplateOwned` flag so the destructor can free a strdup'ed template without freeing model-owned memory. Each divergence is flagged in the file's header comment. |
| `LLMInference.cpp` | Same commit, **plus targeted #75/#87 fixes** over the original: EOG path no longer double-strdups (leaked one full response copy per completion) and honors `_storeChats`; `startCompletion` deletes the previous `_batch` before allocating; `minP` now actually adds a min-p sampler instead of being logged and ignored; destructor frees an owned chat template. Divergences are flagged in the file's header comment. |
| `llama_cleanup_jni.cpp` | **Adapted** from `smollm.cpp` (same commit): JNI symbol names renamed to this app's package (`Java_com_kafkasl_phonewhisper_LlamaCppInference_*`), and trimmed from 8 exported functions to the 6 `LlamaCppInference.kt` actually declares (dropped `getResponseGenerationSpeed`/`getContextSizeUsed`/`benchModel` -- perf/bench accessors unused by a single-shot cleanup call). See the file's own header comment for the full adaptation diff summary. |
| `CMakeLists.txt` | **Adapted** (heavily simplified) from `smollm`'s `CMakeLists.txt`: one baseline arm64-v8a build target instead of upstream's 7-way CPU-feature-variant matrix (Ramblr already restricts `ndk.abiFilters` to `arm64-v8a` only), and drops the separate `ggufreader` library (Ramblr hardcodes context size for its one curated model instead of parsing GGUF metadata at runtime). |
| `../../kotlin/.../LlamaCppInference.kt` | **Adapted** from `SmolLM.kt` (same commit): trimmed to a single-shot (no chat history, no streaming `Flow`, no CPU-variant library selection) completion API. |

All source was fetched live from the real upstream repository (`curl`/GitHub API against
`raw.githubusercontent.com` and `api.github.com`) during this work, not reproduced from memory.

## Status: wired into the Gradle build, native build passing

Confirmed working on a machine with the Android NDK (`27.2.12479018`) and a system `cmake`/`ninja`
(installed via Homebrew; the Android SDK had no bundled `cmake` package until the build itself
triggered `sdkmanager` to fetch CMake 3.22.1 into `$ANDROID_HOME/cmake/3.22.1`):

- `app/build.gradle.kts` sets `android.ndkVersion = "27.2.12479018"` and a real
  `externalNativeBuild { cmake { path = file("src/main/cpp/llama_cleanup/CMakeLists.txt") } }`
  block (plus `defaultConfig.externalNativeBuild.cmake.abiFilters += "arm64-v8a"`), so
  `./gradlew assembleDebug` genuinely invokes CMake/NDK and compiles this shim + llama.cpp from
  source.
- `libllama-cleanup-jni.so` (52KB), `libllama.so`, `libllama-common.so`, and the `libggml*.so`
  set are all produced and packaged into `app-debug.apk`'s `lib/arm64-v8a/` -- confirmed via
  `unzip -l app-debug.apk | grep -i llama`.
- The old `fetchLlamaCppNativeLibs` Gradle stub (which always threw, documenting the missing-NDK
  gap) has been removed now that the real build path works.
- `make test` (JVM unit tests) passes unaffected.

### Setup that made it work

1. **`llama.cpp` git submodule**: added at the repo root (`/llama.cpp`, i.e. a sibling of `app/`),
   pinned to release tag `b9867` -- this matches `CMakeLists.txt`'s pre-existing
   `add_subdirectory(../../../../../llama.cpp llama.cpp)` line exactly (5 `../` from
   `app/src/main/cpp/llama_cleanup/` lands at the repo root), so no path changes were needed in
   `CMakeLists.txt` itself, only the submodule's location.
2. **`LLAMA_BUILD_COMMON` cache override**: llama.cpp's own `CMakeLists.txt` only
   `add_subdirectory(common)` (which defines the `llama-common` target this shim links against for
   chat templating/sampling helpers) when `LLAMA_BUILD_COMMON` is on, which itself defaults to on
   only when llama.cpp is the top-level CMake project (`LLAMA_STANDALONE`) -- not true here, since
   we pull it in via `add_subdirectory`. `CMakeLists.txt` now does
   `set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)` immediately before the `add_subdirectory` call
   so llama.cpp's own `option()` call (which leaves an already-set cache variable alone) picks it
   up.
3. **Target name mismatch**: the SmolChat-Android-derived `CMakeLists.txt` linked against a target
   literally named `common` -- true in older llama.cpp versions, but at `b9867` the library was
   split into `llama-common-base` + `llama-common`. `target_link_libraries` now references
   `llama-common`.
4. **Vendored `nlohmann/json` include path**: `common/chat.h` (transitively included by
   `LLMInference.h`) pulls in `common/peg-parser.h`, which includes
   `<nlohmann/json_fwd.hpp>` from llama.cpp's `vendor/nlohmann/` directory. `llama-common`
   declares this as a `PUBLIC` include directory, but that usage requirement didn't propagate
   through to this shim's target as expected (even after fixing the target name above) -- so
   `CMakeLists.txt` now adds `${LLAMA_DIR}/vendor` directly to `llama-cleanup-jni`'s own
   `target_include_directories` as a defense against relying on transitive propagation here.

None of this required changing `LLMInference.cpp`, `LLMInference.h`, or `llama_cleanup_jni.cpp`
themselves -- the vendored/adapted JNI shim's actual C++ logic compiled against `b9867`'s API
surface unmodified; every fix needed was in `CMakeLists.txt`'s build wiring.

### What's still NOT verified

**Compiling is necessary but not sufficient.** There is currently no way to verify actual LLM
inference correctness (does the model load, does a real cleanup pass produce sane output, does it
crash on a real device) in this environment -- no physical or emulated Android device was
available during this work. See #36 for exactly why "compiles and passes JVM-only tests" is not
sufficient proof for native/JNI surfaces: a stale/mismatched native binding there caused an
on-device crash that passed all Kotlin-only tests. On-device verification of this native build
(installing the APK, triggering the LOCAL_LLM cleanup step, confirming `LlamaCppInference` loads
the model and returns real output without crashing) is a separate, later step.

## Related model

The one curated GGUF model this binding is meant to load is `LOCAL_CLEANUP_MODEL` in
`ModelDownloader.kt` (Qwen2.5-0.5B-Instruct, Q4_K_M) -- downloaded via the existing
`ModelDownloader`/`ModelDownloadWorker` pattern, independent of this native-build gap.
