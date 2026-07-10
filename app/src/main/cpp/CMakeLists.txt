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

# --- Reproducible builds (F-Droid MR fdroid/fdroiddata!42401, reviewer linsui) -------
#
# F-Droid's `check apk` on v1.0.9 matched byte-for-byte except for the CMake-built native
# .so files, whose only real divergence was build-host-specific bytes the compiler bakes
# into them -- overwhelmingly absolute __FILE__ paths (verified: /Users/aiserver/ramblr/...
# on this macOS checkout vs /home/vagrant/build/com.trevornk.ramblr/... on F-Droid's Linux
# buildserver), plus the compiler `.comment` ident and the linker `--build-id` hash.
#
# Setting these as directory-scope CMAKE_*_FLAGS *before* the two add_subdirectory() calls
# below propagates them into every native target the app compiles from source: the app's own
# JNI shims, the llama.cpp / ggml subtree, and the sherpa-onnx subtree plus its seven vendored
# dependency submodules. (Both child CMakeLists.txt only ever *append* to these variables --
# verified -- so nothing here is clobbered; the ggml-hexagon/ggml-vulkan toolchain files that
# do reassign CMAKE_*_FLAGS are for backends this CPU-only arm64 build never configures.)
#
# Scoped to release builds only: AGP configures release native builds with a non-Debug
# CMAKE_BUILD_TYPE, so debug builds keep host paths and full symbols for usable local
# debugging while the release artifacts that actually ship to F-Droid are deterministic.
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    # The build-host-specific absolute prefix that __FILE__ leaks. Computed from this file's
    # own location (app/src/main/cpp -> repo root is four levels up) so that EACH host maps
    # ITS OWN checkout root to the same stable token: /Users/aiserver/ramblr and
    # /home/vagrant/build/com.trevornk.ramblr both collapse to "/ramblr", making the embedded
    # strings identical across hosts. This single mapping covers every leaking source root the
    # investigation enumerated (app cpp dir, llama.cpp, sherpa-onnx, and sherpa-onnx's vendored
    # deps/*), since all are descendants of the repo root.
    get_filename_component(RAMBLR_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)

    # AGP's CMake binary dir (app/.cxx/<config>/<hash>/arm64-v8a) also leaks: generated sources
    # such as llama.cpp's build-info.cpp are compiled from there, and its <hash> segment is not
    # stable across hosts. Map it to its own stable token; listed after the repo-root map so it
    # wins (clang applies the last matching -ffile-prefix-map) for paths under the binary dir.
    set(_RAMBLR_REPRO_CFLAGS
        "-ffile-prefix-map=${RAMBLR_REPO_ROOT}=/ramblr"
        "-ffile-prefix-map=${CMAKE_BINARY_DIR}=/ramblr-build"
        "-fno-ident")  # drop the compiler ident string, which differs between the macOS and Linux NDK clang
    string(JOIN " " _RAMBLR_REPRO_CFLAGS ${_RAMBLR_REPRO_CFLAGS})
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${_RAMBLR_REPRO_CFLAGS}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${_RAMBLR_REPRO_CFLAGS}")
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} ${_RAMBLR_REPRO_CFLAGS}")

    # Disable DWARF for release. This must win over the -g that AGP's release config appends
    # via CMAKE_<LANG>_FLAGS_RELWITHDEBINFO (=-O2 -g -DNDEBUG), which CMake emits *after*
    # CMAKE_<LANG>_FLAGS -- so a -g0 folded into the flag strings above would be overridden and
    # is not enough. add_compile_options() lands in the directory COMPILE_OPTIONS, which CMake
    # places after the per-config flags on the command line, so -g0 is the last debug flag and
    # wins. This matters beyond debug-section stripping: under -g, Clang's OpenMP frontend bakes
    # the *absolute, non-prefix-mapped* source location of every `#pragma omp` region into the
    # libomp ident_t "psource" string (`;<file>;<func>;<line>;<col>;;`) in .rodata -- a path that
    # -ffile-prefix-map does NOT rewrite (verified: it only remaps the debug-info copy, leaving
    # the raw psource string leaking the host path; -g0 degrades it to `;unknown;unknown;0;0;;`).
    # ggml links OpenMP into ggml-base/ggml-cpu, so ggml-quants.c and ggml-cpu.c were the last
    # source files still leaking /Users/aiserver/ramblr paths in libllama-cleanup-jni.so.
    # AGP strips release .so debug sections anyway, so dropping DWARF here costs nothing.
    add_compile_options(-g0)

    # --build-id embeds a hash derived from the linked bytes, which shifts with any toolchain
    # difference; drop it so the section is simply absent (and thus identical) in every rebuild.
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--build-id=none")
    set(CMAKE_EXE_LINKER_FLAGS    "${CMAKE_EXE_LINKER_FLAGS} -Wl,--build-id=none")

    # Relocation-table non-determinism (F-Droid MR follow-up, 2026-07-10): even with the
    # path/debug-info fixes above, F-Droid's Vagrant rebuild and GitHub Actions' Ubuntu-runner
    # rebuild produced byte-different libggml-base.so despite identical source commit and NDK
    # version -- confirmed via llvm-readelf that the two .so files have IDENTICAL section
    # headers (same layout) but DIFFERENT relocation target *values* in .rela.dyn once sorted,
    # ruling out simple relocation-table reordering. Root cause: AGP's CMake/Ninja native build
    # links objects in whatever order Ninja's parallel job scheduler happens to complete them,
    # which is host-CPU-core-count-dependent -- GitHub's runner and F-Droid's Vagrant VM don't
    # have the same core count, so the *link command's* file-list order differs between hosts,
    # shifting internal symbol addresses (hence relocation values) even though every input byte
    # is identical. Two fixes address this at its actual source:
    #  1. Force a single-threaded linker invocation so lld can't reorder relocation-table
    #     entries based on its own internal thread scheduling, independent of Ninja's job count.
    #  2. Force deterministic `ar` archive creation (mtime/uid/gid zeroed, member order fixed)
    #     for every static lib CMake creates on the way to the final .so -- ggml/llama.cpp/
    #     sherpa-onnx and their vendored deps build several intermediate .a archives, and `ar`
    #     is not deterministic by default (see CMake's own CMAKE_C_ARCHIVE_CREATE default).
    #     This does NOT force single-threaded *compilation* (Ninja's -j is untouched, so build
    #     time is unaffected) -- only archive creation and final linking are made deterministic.
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--threads=1")
    set(CMAKE_EXE_LINKER_FLAGS    "${CMAKE_EXE_LINKER_FLAGS} -Wl,--threads=1")
    set(CMAKE_C_ARCHIVE_CREATE   "<CMAKE_AR> qcD <TARGET> <LINK_FLAGS> <OBJECTS>")
    set(CMAKE_C_ARCHIVE_FINISH   "<CMAKE_RANLIB> -D <TARGET>")
    set(CMAKE_CXX_ARCHIVE_CREATE "<CMAKE_AR> qcD <TARGET> <LINK_FLAGS> <OBJECTS>")
    set(CMAKE_CXX_ARCHIVE_FINISH "<CMAKE_RANLIB> -D <TARGET>")

    message(STATUS "Ramblr reproducible-build flags active (CMAKE_BUILD_TYPE='${CMAKE_BUILD_TYPE}', repo root '${RAMBLR_REPO_ROOT}')")
endif()

add_subdirectory(llama_cleanup)
add_subdirectory(sherpa_onnx)
