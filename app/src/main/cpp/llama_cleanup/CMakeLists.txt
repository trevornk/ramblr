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
# To actually build this: `add_subdirectory` below expects a `llama.cpp` checkout two levels up
# (mirroring SmolChat-Android's git submodule at `smollm/llama.cpp`) -- pin it to a specific
# llama.cpp release tag (e.g. the "b9867" tag referenced in this repo's #37 research) and fetch
# it via a Gradle task the same shape as fetchSherpaOnnxNativeLibs in app/build.gradle.kts.

cmake_minimum_required(VERSION 3.22.1)
project("llama_cleanup")

# llama.cpp's own CMakeLists.txt only builds its `common`/`llama-common` library (which
# LLMInference.cpp/llama_cleanup_jni.cpp need for chat templating and sampling helpers) when
# LLAMA_BUILD_COMMON is on -- which defaults to on only when llama.cpp is the top-level CMake
# project (LLAMA_STANDALONE), not when pulled in via add_subdirectory as we do here. Force it
# on as a cache variable so llama.cpp's own option() call (which only sets a default, and
# leaves an already-set cache variable alone) picks this up.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)

# 16KB ELF alignment (#44): NDK r27 (pinned via android.ndkVersion) doesn't default to 16KB
# page-size-aligned LOAD segments -- only r28+ does. Setting these here, before
# add_subdirectory(), makes them ordinary (non-cache) CMake variables in this directory scope,
# which CMake propagates into every subdirectory scope added below (llama.cpp/ggml/common)
# unless a child CMakeLists.txt overrides them -- verified none of them do. That covers every
# SHARED target the compatibility dialog flagged (llama, llama-common, ggml, ggml-base,
# ggml-cpu), plus llama-cleanup-jni itself since it's added after this point too.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

add_subdirectory(../../../../../llama.cpp llama.cpp)
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
