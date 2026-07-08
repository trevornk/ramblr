# Root native-build entry point for the app module.
#
# The Android Gradle Plugin supports only a single externalNativeBuild.cmake.path
# per module, so this thin wrapper add_subdirectory()s both native components the
# app builds from source:
#   - llama_cleanup: on-device LLM cleanup JNI shim, built from the llama.cpp
#     submodule (#37 -- see llama_cleanup/CMakeLists.txt / README.md).
#   - sherpa_onnx:   speech-to-text JNI lib (libsherpa-onnx-jni.so), built from the
#     sherpa-onnx submodule instead of fetching the prebuilt release AAR from
#     GitHub Releases (F-Droid prep -- see sherpa_onnx/CMakeLists.txt / README.md).
#
# Both used to be wired independently (llama_cleanup via this block, sherpa-onnx via
# the fetchSherpaOnnxNativeLibs Gradle task); combining them here is what let the
# sherpa-onnx native build join the same CMake invocation without AGP's one-path
# limit forcing a choice between the two.
cmake_minimum_required(VERSION 3.22.1)
project("ramblr_native")

add_subdirectory(llama_cleanup)
add_subdirectory(sherpa_onnx)
