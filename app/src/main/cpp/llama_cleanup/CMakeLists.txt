# Adapted (heavily simplified) from shubham0204/SmolChat-Android's `smollm` module CMakeLists.txt
# (Apache License 2.0, commit 2b934d8e7da9, 2026-07-03):
# https://github.com/shubham0204/SmolChat-Android/blob/main/smollm/src/main/cpp/CMakeLists.txt
#
# Wired into the Gradle build: app/build.gradle.kts's `externalNativeBuild { cmake { ... } }`
# block references this file, so `./gradlew assembleDebug` compiles the shim + llama.cpp from
# source via the NDK (see this directory's README.md for the full setup, #37/#87).
#
# Adaptation from the original:
#   - Builds exactly one shared library ("llama-cleanup-jni", matching
#     LlamaCppInference.LIBRARY_NAME) instead of upstream's matrix of ARMv8/8.2/8.4
#     CPU-feature-specific variants selected at runtime -- that's a real performance
#     optimization SmolChat-Android made, but a v1 YAGNI cut here: Ramblr already restricts
#     `ndk.abiFilters` to arm64-v8a only (see app/build.gradle.kts), so a single baseline
#     armv8-a build covers every device this app installs on, at the cost of not using
#     newer CPU dot-product/fp16 instructions where available.
#   - Builds only llama_cleanup_jni.cpp + LLMInference.cpp (drops smollm's separate
#     ggufreader shared library -- Ramblr hardcodes context size/threads rather than reading
#     them from GGUF metadata at runtime, since it ships exactly one curated model; see
#     ModelDownloader.kt's LOCAL_CLEANUP_MODEL).
#
# To actually build this: `add_subdirectory` below expects a `llama.cpp` checkout two levels up.
# It's a git submodule (see .gitmodules), pinned to a specific llama.cpp release tag (e.g. the
# "b9867" tag referenced in this repo's #37 research) -- the same source-submodule pattern the
# sherpa_onnx module (../sherpa_onnx/CMakeLists.txt) uses for its own native dependency.

cmake_minimum_required(VERSION 3.22.1)
project("llama_cleanup")

# llama.cpp's own CMakeLists.txt only builds its `common`/`llama-common` library (which
# LLMInference.cpp/llama_cleanup_jni.cpp need for chat templating and sampling helpers) when
# LLAMA_BUILD_COMMON is on -- which defaults to on only when llama.cpp is the top-level CMake
# project (LLAMA_STANDALONE), not when pulled in via add_subdirectory as we do here. Force it
# on as a cache variable so llama.cpp's own option() call (which only sets a default, and
# leaves an already-set cache variable alone) picks this up.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)

# Reproducible builds (F-Droid MR fdroid/fdroiddata!42401): llama.cpp's build-info.cmake
# derives LLAMA_BUILD_NUMBER/COMMIT from `git rev-list --count`/`rev-parse` and bakes them
# into build-info.cpp (-> LLAMA_COMMIT string in the shipped JNI lib). The submodule is
# commit-pinned, so git yields identical values on every host that HAS git -- but F-Droid's
# build sandbox git availability is not something we control, and a host without git would
# instead embed llama.cpp's "unknown"/0 fallback, diverging from a host that has it. Pin both
# to llama.cpp's own canonical no-git fallback constants so the embedded build-info is
# host-independent regardless. llama.cpp only fills these in when they are NOT already DEFINED
# (see llama.cpp/CMakeLists.txt), so defining them here in the parent scope wins; the value's
# real provenance is the pinned commit recorded in .gitmodules / `git submodule status`.
set(LLAMA_BUILD_NUMBER 0)
set(LLAMA_BUILD_COMMIT "unknown")

# Follow-up (F-Droid MR re-verification, 2026-07-15): the pin above only covers
# LLAMA_BUILD_COMMIT/LLAMA_BUILD_NUMBER, which llama.cpp's own build-info.cmake consumes into
# common/build-info.cpp (-> the llama-common/llama libraries). It does NOT cover a second,
# separate git-derived string: ggml/CMakeLists.txt independently runs its own
# `find_program(GIT_EXE ...)` + `git rev-parse --short HEAD` and bakes the result directly into
# ggml-base via `target_compile_definitions(ggml-base PRIVATE GGML_COMMIT="${GGML_BUILD_COMMIT}")`.
# Confirmed via real F-Droid job-artifact vs. GitHub-release binary diff (job 15293325827): the
# rebuilt libggml-base.so differs from the reference APK's copy starting at byte 110521, inside
# .rodata, where the embedded string reads "GGML_NO_BACKTRACE\0<hash>\0q8_1\0..." on one host and
# "GGML_NO_BACKTRACE\0\0q8_1\0..." (empty/no hash) on the other -- i.e. GGML_COMMIT itself, not a
# path or timestamp. This explains why ggml-base was the ONLY native lib that failed to reproduce:
# every other library already got the LLAMA_BUILD_COMMIT pin above; only ggml-base has this
# second, independent git-lookup path.
#
# CORRECTION (F-Droid MR re-re-verification, 2026-07-16): the GIT_EXE-NOTFOUND cache pin below
# does NOT actually work -- confirmed against CMake's own find_program() docs: "If the variable
# is defined and its value is NOTFOUND, or ends in -NOTFOUND, the search will be performed."
# CMake explicitly treats any *-NOTFOUND cache value as "not yet found" and re-runs the search
# on every configure, so on any host where `git` actually is on PATH (GitHub Actions' runner),
# ggml/CMakeLists.txt's find_program(GIT_EXE ...) simply rediscovers real git regardless of this
# pin and re-embeds the true submodule commit hash. Verified via a real disassembly-level diff of
# the two hosts' actual libggml-base.so (F-Droid job 15384654280 vs. the v1.0.15 GitHub Release
# APK): GitHub's copy embeds "152d337" (the llama.cpp submodule's real pinned short hash --
# `git submodule status` confirms 152d337fadb93c2a099653c4072d5512c92c5bfd), while F-Droid's copy
# embeds a zero-length string in the same slot (its Vagrant buildserver sandbox apparently can't
# resolve a real git commit for the submodule tree it's handed, so `git rev-parse` fails there
# and GGML_BUILD_COMMIT stays empty) -- i.e. this line never blocked anything on either host; the
# hosts differ because git behaves differently in each environment, exactly as the -NOTFOUND
# semantics above predict. Kept below as a harmless best-effort (does still block find_program on
# hosts with a genuinely git-less PATH), but it is NOT what makes this deterministic -- see the
# real fix after add_subdirectory() below.
set(GIT_EXE "GIT_EXE-NOTFOUND" CACHE FILEPATH "" FORCE)

# 16KB ELF alignment (#44): NDK r27 (pinned via android.ndkVersion) doesn't default to 16KB
# page-size-aligned LOAD segments -- only r28+ does. Setting these here, before
# add_subdirectory(), makes them ordinary (non-cache) CMake variables in this directory scope,
# which CMake propagates into every subdirectory scope added below (llama.cpp/ggml/common)
# unless a child CMakeLists.txt overrides them -- verified none of them do. That covers every
# SHARED target the compatibility dialog flagged (llama, llama-common, ggml, ggml-base,
# ggml-cpu), plus llama-cleanup-jni itself since it's added after this point too.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

# CPU feature targeting (#37 follow-up, real on-device timeout hit): ggml's GGML_NATIVE
# defaults OFF for any cross-compiled build (this NDK build included -- see
# CMAKE_CROSSCOMPILING in llama.cpp/ggml/CMakeLists.txt), and with GGML_CPU_ARM_ARCH unset
# ggml-cpu/CMakeLists.txt's ARCH_FLAGS stays completely empty -- i.e. the ARM CPU quantized
# matmul kernels compile against the compiler's baseline armv8-a target only, with NO dotprod,
# i8mm, or fp16-vector-arithmetic acceleration, regardless of what the real device supports.
# llama.cpp's ARM CPU backend has dedicated fast paths for Q4_0 quantized matmul keyed on
# exactly these instructions (see ggml-cpu/CMakeLists.txt's check_arm_feature block), so leaving
# them off is a real, avoidable multiple-x decode slowdown, not just an untuned default.
# armv8.6-a+dotprod+i8mm+fp16 is confirmed present on Trevor's real device (Pixel 10 Pro Fold
# `/proc/cpuinfo` Features line: asimddp, i8mm, asimdhp/fphp) -- ndk.abiFilters already
# restricts this app to arm64-v8a only and it isn't yet distributed beyond this one device
# (see #99, Play Store distribution still unevaluated), so hardcoding to this device's real
# baseline is safe today. Revisit with GGML_CPU_ALL_VARIANTS (runtime dispatch) instead of a
# hardcoded -march if/when this ships to arbitrary ARM64 hardware.
set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

add_subdirectory(../../../../../llama.cpp llama.cpp)

# Real fix for the GGML_COMMIT non-determinism (F-Droid MR re-re-verification, 2026-07-16):
# the GIT_EXE-NOTFOUND pin above cannot block find_program() from re-discovering a real `git`
# binary on hosts that have one on PATH (see the CORRECTION comment above it), so
# ggml/CMakeLists.txt's own `target_compile_definitions(ggml-base PRIVATE GGML_COMMIT="...")`
# call -- which already ran during the add_subdirectory() above -- baked in whatever
# GGML_BUILD_COMMIT that host's git lookup produced. Rather than fight ggml's own git-detection
# (which cannot be reached from here without modifying the vendored submodule's CMakeLists.txt,
# something we don't want to fork/patch), override the compile definition it already set on the
# ggml-base target directly: a target can carry multiple -D flags for the same macro name, and
# both clang and gcc apply "last definition on the command line wins" for a repeated -D, so
# appending a fixed GGML_COMMIT here -- after ggml's own add_subdirectory() has already added its
# host-dependent one -- reliably wins on every host's compiler invocation regardless of what
# ggml's own git lookup found or didn't find. Value picked to match llama.cpp's own no-git
# fallback convention (see LLAMA_BUILD_COMMIT "unknown" above) for consistency; this is purely a
# diagnostic string ggml prints/exposes via ggml_commit(), not anything Ramblr's own code reads.
target_compile_definitions(ggml-base PRIVATE GGML_COMMIT="unknown")

set(LLAMA_DIR_RELATIVE "../../../../../llama.cpp")
get_filename_component(LLAMA_DIR ${LLAMA_DIR_RELATIVE} ABSOLUTE)

set(GGML_DIR ${LLAMA_DIR}/ggml)
set(COMMON_DIR ${LLAMA_DIR}/common)

add_library(
        llama-cleanup-jni
        SHARED
        LLMInference.cpp
        llama_cleanup_jni.cpp
)
target_include_directories(
        llama-cleanup-jni
        PUBLIC
        ${COMMON_DIR}
        ${LLAMA_DIR}/vendor
        ${GGML_DIR}/include
        ${LLAMA_DIR}/include
)
target_compile_options(
        llama-cleanup-jni
        PUBLIC
        -fvisibility=hidden -fvisibility-inlines-hidden
        -ffunction-sections -fdata-sections
        -DGGML_USE_CPU -O3
)
target_link_libraries(
        llama-cleanup-jni
        android log llama llama-common
)
target_link_options(
        llama-cleanup-jni
        PRIVATE
        -Wl,--gc-sections -flto
        -Wl,--exclude-libs,ALL
)
