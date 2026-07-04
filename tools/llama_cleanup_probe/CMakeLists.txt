# Standalone host build that proves LLMInference.cpp (the vendored core of #37's llama.cpp JNI
# shim -- see app/src/main/cpp/llama_cleanup/) actually produces working inference against the
# pinned llama.cpp submodule (b9867), without needing an Android device/NDK. Compiles the exact
# production LLMInference.cpp/.h verbatim (only android/log.h is faked, via fake_android/), so
# this is a real test of the same C++ that ships in the app -- not a reimplementation.
#
# Not referenced by app/build.gradle.kts or any Gradle task -- this is a manual verification tool,
# run via: cmake -S tools/llama_cleanup_probe -B /tmp/llama_cleanup_probe_build -G Ninja &&
# cmake --build /tmp/llama_cleanup_probe_build
cmake_minimum_required(VERSION 3.22.1)
project(llama_cleanup_probe CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../llama.cpp)
set(SHIM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/llama_cleanup)

# Same fix as app/src/main/cpp/llama_cleanup/CMakeLists.txt needs: `common` (the `llama-common`
# target) only gets add_subdirectory'd when LLAMA_BUILD_COMMON is on, which itself only defaults
# on when llama.cpp is the top-level CMake project -- not true here either, since we pull it in
# via add_subdirectory just like the production build does.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama.cpp)

add_executable(
        llama_cleanup_probe
        probe.cpp
        ${SHIM_DIR}/LLMInference.cpp
)
target_include_directories(
        llama_cleanup_probe
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/fake_android
        ${SHIM_DIR}
        ${LLAMA_DIR}/common
        ${LLAMA_DIR}/vendor
        ${LLAMA_DIR}/ggml/include
        ${LLAMA_DIR}/include
)
target_link_libraries(llama_cleanup_probe PRIVATE llama llama-common)
