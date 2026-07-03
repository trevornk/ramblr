# Adapted (heavily simplified) from shubham0204/SmolChat-Android's `smollm` module CMakeLists.txt
# (Apache License 2.0, commit 2b934d8e7da9, 2026-07-03):
# https://github.com/shubham0204/SmolChat-Android/blob/main/smollm/src/main/cpp/CMakeLists.txt
#
# NOT currently wired into the Gradle build (no `externalNativeBuild` block references this file)
# -- see app/src/main/cpp/llama_cleanup/README.md for exactly why and what's needed to finish
# wiring it up (#37). Left in the tree so the actual build step is a config change, not a
# from-scratch write, once a machine with the Android NDK is available.
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
        android log llama common
)
target_link_options(
        llama-cleanup-jni
        PRIVATE
        -Wl,--gc-sections -flto
        -Wl,--exclude-libs,ALL
)
