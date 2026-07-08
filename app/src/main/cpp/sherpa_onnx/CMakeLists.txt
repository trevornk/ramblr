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

# --- Offline FetchContent deps (F-Droid: no network during build) ------------------
#
# sherpa-onnx's own cmake/*.cmake files FetchContent_Declare() 5 small source deps at
# CMake configure time (kaldi-native-fbank, kaldi-decoder, openfst, simple-sentencepiece,
# json -- read directly from cmake/kaldi-native-fbank.cmake, cmake/kaldi-decoder.cmake,
# cmake/openfst.cmake, cmake/simple-sentencepiece.cmake, cmake/json.cmake). F-Droid's
# build sandbox has no network, so this breaks configure as-is.
#
# A 7th, further-transitive dep surfaced on the SAME network-isolation build test after
# fixing kissfft: kaldi-decoder's own CMakeLists.txt (not sherpa-onnx's) does an
# unconditional `include(kaldifst)` -> kaldi-decoder/cmake/kaldifst.cmake
# FetchContent_Declare()s k2-fsa/kaldifst v1.8.0. Vendored the same way.
#
# NOTE ON VERSION SKEW (documented, not hidden): kaldifst v1.8.0's OWN
# cmake/openfst.cmake pins openfst tag v1.8.5-2026-04-10 (hash c3549...), one day
# earlier than sherpa-onnx's own cmake/openfst.cmake pin of v1.8.5-2026-04-11 (hash
# 57fbc..., already vendored above). CMake's FetchContent_Declare() is idempotent per
# content name -- whichever include(openfst) runs first "wins" and the second
# redeclaration for the same name is a no-op -- so with FETCHCONTENT_SOURCE_DIR_OPENFST
# already overridden to our single vendored openfst submodule, BOTH consumers end up
# using the v1.8.5-2026-04-11 checkout, not kaldifst's originally-requested -04-10. This
# is a deliberate, low-risk choice (one-day-apart patch republish of the same fork, not
# a different major/minor version) rather than maintaining two separate openfst
# checkouts for a one-day tag skew.
#
# Each is vendored here as an additional pinned git submodule (deps/<name>, see
# .gitmodules), at the exact tag/commit each .cmake file's FetchContent_Declare()
# targets. FETCHCONTENT_SOURCE_DIR_<NAME> is CMake's own FetchContent override mechanism:
# when set, FetchContent_GetProperties()/_Populate() skip the network/URL entirely and
# treat the given path as already-populated -- no changes to sherpa-onnx's,
# kaldi-native-fbank's, or kaldi-decoder's own CMakeLists.txt needed. <NAME> is the exact
# FetchContent_Declare()'d name run through CMake's own string(TOUPPER ...) (see
# FetchContent.cmake's __FetchContent_Populate) -- a LITERAL uppercase, NOT a
# hyphen-to-underscore substitution. Verified empirically against the installed CMake
# 4.3.4 module: a -DFETCHCONTENT_SOURCE_DIR_SIMPLE_SENTENCEPIECE= with underscores
# silently falls through to a real network fetch attempt; only the hyphen-preserving
# -DFETCHCONTENT_SOURCE_DIR_SIMPLE-SENTENCEPIECE= is honored:
#   kaldi_native_fbank   -> FETCHCONTENT_SOURCE_DIR_KALDI_NATIVE_FBANK
#   kaldi_decoder        -> FETCHCONTENT_SOURCE_DIR_KALDI_DECODER
#   openfst              -> FETCHCONTENT_SOURCE_DIR_OPENFST
#   simple-sentencepiece -> FETCHCONTENT_SOURCE_DIR_SIMPLE-SENTENCEPIECE  (hyphen kept!)
#   json                 -> FETCHCONTENT_SOURCE_DIR_JSON
#   kissfft              -> FETCHCONTENT_SOURCE_DIR_KISSFFT
#   kaldifst             -> FETCHCONTENT_SOURCE_DIR_KALDIFST
set(_DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/deps")
set(FETCHCONTENT_SOURCE_DIR_KALDI_NATIVE_FBANK      "${_DEPS_DIR}/kaldi-native-fbank"   CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_KALDI_DECODER           "${_DEPS_DIR}/kaldi-decoder"        CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_OPENFST                 "${_DEPS_DIR}/openfst"              CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_SIMPLE-SENTENCEPIECE    "${_DEPS_DIR}/simple-sentencepiece" CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_JSON                    "${_DEPS_DIR}/json"                 CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_KISSFFT                 "${_DEPS_DIR}/kissfft"              CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_KALDIFST                "${_DEPS_DIR}/kaldifst"             CACHE PATH "" FORCE)

# 5 levels up from app/src/main/cpp/sherpa_onnx/ lands at the repo root, where the
# sherpa-onnx submodule lives (a sibling of llama.cpp; see .gitmodules).
get_filename_component(SHERPA_ONNX_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../sherpa-onnx" ABSOLUTE)
add_subdirectory(${SHERPA_ONNX_DIR} sherpa-onnx)
