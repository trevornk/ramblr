# Builds libsherpa-onnx-jni.so from source (F-Droid prep).
#
# F-Droid disallows prebuilt binaries from untrusted sources, which is what the old
# fetchSherpaOnnxNativeLibs Gradle task did: download k2-fsa/sherpa-onnx's release AAR
# from GitHub Releases and extract libsherpa-onnx-jni.so + libonnxruntime.so. This
# builds the JNI lib from the pinned sherpa-onnx git submodule instead, mirroring the
# from-source llama.cpp pattern in ../llama_cleanup/ (#37).
#
# sherpa-onnx ships its own JNI target ("sherpa-onnx-jni") in
# sherpa-onnx/jni/CMakeLists.txt, gated behind SHERPA_ONNX_ENABLE_JNI. Rather than
# re-implementing it, we add_subdirectory() the whole submodule with a minimal feature
# set and let upstream build that target -- so the produced libsherpa-onnx-jni.so
# matches the vendored com.k2fsa.sherpa.onnx.* Kotlin bindings (also v1.13.3) symbol
# for symbol, avoiding the UnsatisfiedLinkError risk a hand-rolled JNI shim would carry.
# See ../../kotlin/com/k2fsa/sherpa/onnx/VENDORED.md.

cmake_minimum_required(VERSION 3.22.1)
project("sherpa_onnx_jni")

# --- onnxruntime: supplied by Maven Central, not downloaded by sherpa-onnx ---------
#
# The other F-Droid blocker: sherpa-onnx's cmake/onnxruntime.cmake normally downloads a
# prebuilt libonnxruntime.so at configure time. Instead we depend on the official
# com.microsoft.onnxruntime:onnxruntime-android artifact (see app/build.gradle.kts): its
# libonnxruntime.so is packaged into the APK by AGP, and its headers + .so are extracted
# by the `extractOnnxRuntimeNative` Gradle task and passed in here as -D CMake args.
#
# cmake/onnxruntime.cmake keys off two *environment* variables (not CMake cache vars) to
# take its "use a provided onnxruntime" branch and create an IMPORTED onnxruntime target
# rather than downloading -- so bridge the -D args into the process environment here,
# before add_subdirectory() below triggers include(onnxruntime).
if(NOT SHERPA_ONNXRUNTIME_INCLUDE_DIR OR NOT SHERPA_ONNXRUNTIME_LIB_DIR)
  message(FATAL_ERROR
    "SHERPA_ONNXRUNTIME_INCLUDE_DIR / SHERPA_ONNXRUNTIME_LIB_DIR must be passed in via -D "
    "(populated by the extractOnnxRuntimeNative task in app/build.gradle.kts).")
endif()
if(NOT EXISTS "${SHERPA_ONNXRUNTIME_LIB_DIR}/libonnxruntime.so")
  message(FATAL_ERROR "libonnxruntime.so not found in ${SHERPA_ONNXRUNTIME_LIB_DIR}")
endif()
set(ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR} "${SHERPA_ONNXRUNTIME_INCLUDE_DIR}")
set(ENV{SHERPA_ONNXRUNTIME_LIB_DIR} "${SHERPA_ONNXRUNTIME_LIB_DIR}")

# --- Minimal feature set -----------------------------------------------------------
#
# The app only uses OfflineRecognizer / OnlineRecognizer / Vad (see LocalTranscriber.kt
# and StreamingTranscriber.kt). Turning off TTS, speaker diarization, websocket,
# portaudio, binaries, the C API and tests drops every heavy FetchContent dependency
# (espeak-ng, piper-phonemize, hclust-cpp, asio, websocketpp, portaudio); only
# kaldi-native-fbank, kaldi-decoder, simple-sentencepiece and json remain -- exactly what
# the ASR/VAD code paths need.
set(SHERPA_ONNX_ENABLE_TTS OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_WEBSOCKET OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PORTAUDIO OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_BINARY OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_C_API OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_CHECK OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PYTHON OFF CACHE BOOL "" FORCE)
# ON -> produces libsherpa-onnx-jni.so, the lib the vendored bindings load via
# System.loadLibrary("sherpa-onnx-jni").
set(SHERPA_ONNX_ENABLE_JNI ON CACHE BOOL "" FORCE)

# Static-link everything except onnxruntime, so all of sherpa-onnx-core +
# kaldi-native-fbank + kaldi-decoder + simple-sentencepiece get archived into a single
# self-contained libsherpa-onnx-jni.so -- matching the 2-file (jni.so + onnxruntime.so)
# layout the old prebuilt AAR shipped, so the app's runtime System.loadLibrary path is
# unchanged. BUILD_SHARED_LIBS=OFF also forces CMAKE_POSITION_INDEPENDENT_CODE ON upstream,
# so the static libs are linkable into the shared JNI lib.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# 16KB ELF alignment (#44 -- same treatment ../llama_cleanup applies). sherpa-onnx's own
# top-level CMakeLists already appends -Wl,-z,max-page-size=16384 for ANDROID, but the app
# previously hit an Android 16KB-page compatibility warning for libsherpa-onnx-jni.so
# specifically (from the old prebuilt AAR), so set it explicitly in this scope too, adding
# common-page-size to match the llama_cleanup shim.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

# 5 levels up from app/src/main/cpp/sherpa_onnx/ lands at the repo root, where the
# sherpa-onnx submodule lives (a sibling of llama.cpp; see .gitmodules).
get_filename_component(SHERPA_ONNX_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../sherpa-onnx" ABSOLUTE)
add_subdirectory(${SHERPA_ONNX_DIR} sherpa-onnx)
