# Vendored sherpa-onnx Kotlin bindings

These `com.k2fsa.sherpa.onnx.*` files are copied verbatim from upstream
[k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), not written for this app. They are the
Kotlin/JNI bindings for the native libraries (`libsherpa-onnx-jni.so`, `libonnxruntime.so`). The
bindings and the native libraries **must stay in lockstep** — a JNI method signature mismatch
between them is an `UnsatisfiedLinkError` at runtime, not a compile error.

## Native libraries (built from source, F-Droid prep)

The native libs are no longer fetched as a prebuilt AAR from GitHub Releases (F-Droid disallows
prebuilt binaries from untrusted sources). Instead they are built from source at the **same**
upstream tag as these bindings, which is what keeps them in lockstep:

- **`libsherpa-onnx-jni.so`** is compiled from the `k2-fsa/sherpa-onnx` git submodule (pinned at tag
  `v1.13.3`, a sibling of `app/` — see `.gitmodules`) via `app/src/main/cpp/sherpa_onnx/CMakeLists.txt`,
  which `add_subdirectory()`s the submodule and builds its own `sherpa-onnx-jni` target with a minimal
  feature set (ASR + VAD only). Because both the JNI `.cc` sources and these Kotlin bindings come from
  the identical `v1.13.3` tag, their JNI symbols match by construction.
- **`libonnxruntime.so`** comes from the official Maven Central artifact
  `com.microsoft.onnxruntime:onnxruntime-android:1.24.3` (the exact onnxruntime version sherpa-onnx
  1.13.3 builds against). AGP packages it into the APK; the from-source JNI build links against its
  headers + `.so` (extracted by the `extractOnnxRuntimeNative` Gradle task).

See `app/src/main/cpp/sherpa_onnx/README.md` for the full native-build setup.

## Provenance

- **Upstream tag:** `v1.13.3`
- **Upstream source path:** `sherpa-onnx/kotlin-api/`
- **Pinned by:** the `sherpa-onnx` git submodule (see `.gitmodules`), checked out at tag `v1.13.3` --
  the same tag these bindings were vendored from.

## When bumping the sherpa-onnx version

1. Re-vendor these `.kt` files from the matching upstream tag (`sherpa-onnx/kotlin-api/` at that tag).
2. Update the `sherpa-onnx` submodule to the same tag (`cd sherpa-onnx && git checkout vX.Y.Z`, then
   commit the updated submodule pointer).
3. Bump `onnxRuntimeVersion` in `app/build.gradle.kts` if the new sherpa-onnx tag's
   `build-android-arm64-v8a.sh` pins a different onnxruntime version.
4. Update the tag recorded above.

Recorded to satisfy security audit L-7 (vendored code lacking recorded provenance).
