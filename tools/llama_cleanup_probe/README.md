# llama_cleanup_probe

Standalone host verification tool for #37: proves the production
`app/src/main/cpp/llama_cleanup/LLMInference.cpp` (compiled **verbatim**, unmodified) actually
produces working inference against a real downloaded GGUF model and the pinned llama.cpp
submodule (`b9867`) -- without an Android device, emulator, or NDK. Not part of the Gradle build;
this is a manual verification tool, kept in the tree so the check can be re-run whenever the
llama.cpp submodule pin or the model catalog changes.

`fake_android/android/log.h` stands in for the one NDK-only header `LLMInference.cpp` includes
(`__android_log_print`/`ANDROID_LOG_*`) -- everything else it uses (`llama.h`, `common.h`,
`chat.h`) is llama.cpp's own portable C++ API, identical on host and Android.

## Usage

```
cmake -S tools/llama_cleanup_probe -B /tmp/llama_cleanup_probe_build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/llama_cleanup_probe_build -j
DYLD_LIBRARY_PATH=/tmp/llama_cleanup_probe_build/bin /tmp/llama_cleanup_probe_build/llama_cleanup_probe \
  /path/to/model.gguf "<system prompt>" "<user text>"
```

Drives `LLMInference` through the exact same call sequence
`LlamaCppInference.kt`'s `complete()` uses: `loadModel` (same
`minP`/`temperature`/`contextSize`/`nThreads`/`useMmap`/`useMlock` defaults as
`LlamaCppInference.load()`), one `addChatMessage(systemPrompt, "system")`, `startCompletion
(userText)`, then `completionLoop()` until `"[EOG]"`.

## Result (2026-07-04)

Ran against both real, sha256-verified `LOCAL_CLEANUP_MODEL_CATALOG` entries with the transcript
`"um so like i was thinking maybe we could uh go to the store today or whatever"`:

- **Qwen2.5-0.5B-Instruct-Q4_K_M** (the catalog's `recommended` default) with the app's actual
  default prompt (`PostProcessor.DEV_PROMPT`): `"I was thinking we could go to the store today or
  whatever."` -- coherent, correctly cleaned (filler removed, capitalized, punctuated). Model
  load 0.84s, generation 0.28s (13 tokens, 46.8 tok/s).
- **SmolLM2-360M-Instruct-Q4_K_M** (the catalog's smallest tier) with the shorter
  `PostProcessor.SIMPLE_PROMPT`: `"um yeah so i was thinking maybe we could go to the store today
  or whatever"` -- real, deterministic, on-topic completion, but only a partial cleanup (filler
  and casing/punctuation errors survive). With the longer, few-shot `DEV_PROMPT` this same model
  did not perform the task at all, instead replying with a generic assistant greeting -- a real
  model/prompt mismatch for the smallest tier, not a harness or plumbing bug (confirmed
  deterministic and reproducible across runs at `temperature = 0.0`).

Both runs are genuine, unmocked llama.cpp completions -- proof that the CMake/linking setup in
`app/src/main/cpp/llama_cleanup/CMakeLists.txt` and the exact call signature
`LlamaCppInference.kt` uses both produce real, working local cleanup for the catalog's default
model. See #37 for the full writeup, including the smallest-tier prompt-following caveat.
